using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Veyro.Core.Crypto;
using Veyro.Core.Data;
using Veyro.Core.Diagnostics;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Veyro.Core.Transfer;

namespace Veyro.Core.Link;

/// <summary>Both ends of the phone's WebSocket, as the listener saw them. Either may be null.</summary>
public sealed record SessionAddresses(IPAddress? Remote, IPAddress? Local);

/// <summary>A local IPv4 address with its prefix length, for picking <c>decision.tcpHost</c>.</summary>
public sealed record LocalIpv4(IPAddress Address, int PrefixLength);

/// <summary>
/// An offer that needs the user's consent. <see cref="Abandoned"/> fires when the phone cancels
/// the offer or the session ends; the prompt must close then.
/// </summary>
public sealed record InboundOfferRequest(
    TrustedPeer Peer,
    PeerMessage.TransferOffer Offer,
    CancellationToken Abandoned = default);

/// <summary>
/// An unknown phone asks to pair. For confirm pairing (04-PROTOCOL.md 4.3) <see cref="Code"/> is
/// the 6-digit code the phone shows too; the user accepts only if they match. The prompt must close
/// itself when <see cref="Abandoned"/> fires (the phone cancelled or left) and declines at
/// <see cref="ExpiresAt"/>. The legacy PIN flow (tests only) passes the PIN as the code.
/// </summary>
public enum PcPairOutcome { Paired, Declined, TimedOut, Unreachable, Busy, Refused, IdentityMismatch, Cancelled, Failed }

/// <summary>Result of pairing started on this PC (<see cref="LinkServer.PairWithPhoneAsync"/>).</summary>
public sealed record PcPairResult(PcPairOutcome Outcome, string? PhoneName = null);

public sealed record InboundPairingRequest(
    string PeerDeviceId,
    string PeerName,
    string Code,
    DateTimeOffset ExpiresAt,
    CancellationToken Abandoned = default)
{
    // The code is a secret until pairing ends; keep it out of anything that formats the record.
    public override string ToString() => $"InboundPairingRequest {{ PeerDeviceId = {PeerDeviceId} }}";
}

/// <summary>
/// Runs a persistent session this PC dialled (ADR-040) with the same loop as an inbound one, so
/// every companion feature and transfer works over it.
/// </summary>
public interface ILinkSessionRunner
{
    /// <summary>False while the listener is stopped ("Pause sync"); nothing is dialled then.</summary>
    bool IsRunning { get; }

    /// <summary>
    /// Registers <paramref name="secure"/> in the session registry before the returned task first
    /// yields, then runs it until the peer closes, a newer session replaces it, the listener stops
    /// or <paramref name="ct"/> is cancelled. Never faults; the caller disposes the session after.
    /// </summary>
    Task RunOutboundSessionAsync(SecureLinkSession secure, TrustedPeer peer, SessionAddresses addresses, CancellationToken ct = default);
}

/// <summary>
/// Windows listener for authenticated inbound sessions. An unknown peer pairs either through the
/// open <see cref="Link.PairingWindow"/> (ADR-030) or, when none is open, only after the
/// <see cref="PairingAuthorizer"/> prompt approves it. Also runs the sessions this PC dials
/// (<see cref="ILinkSessionRunner"/>, ADR-040) with the same session loop.
/// </summary>
public sealed class LinkServer : IAsyncDisposable, ILinkSessionRunner
{
    /// <summary>How long a replaced session waits for the peer to answer its close frame.</summary>
    public static readonly TimeSpan ReplacedCloseTimeout = TimeSpan.FromSeconds(5);

    public const int PortRangeStart = 5150;
    public const int PortRangeEnd = 5169;
    private readonly string _localDeviceId;
    private readonly string _localName;
    private readonly ITrustStore _trustStore;
    private readonly string _destinationRoot;
    private readonly byte[] _localPublicKey;
    private readonly Func<byte[], byte[], byte[], byte[]>? _deriveSharedKey;
    private CancellationTokenSource _stop = new();
    private readonly PeerSessionRegistry _sessions;
    private WebApplication? _app;

    /// <summary>What this listener's sessions support, sent in HelloAck.</summary>
    public const int LocalCapabilities =
        PeerCapabilities.FileTransfer | PeerCapabilities.Resume | PeerCapabilities.NotificationMirror |
        PeerCapabilities.Clipboard |
        PeerCapabilities.MediaControl | PeerCapabilities.DeviceStatus |
        PeerCapabilities.Messaging | PeerCapabilities.Calls | PeerCapabilities.Photos |
        PeerCapabilities.Wallpaper;

    /// <summary>
    /// Claims offers that answer a request this PC made (photoFetch, ADR-034). Those are accepted
    /// without a prompt into the claim's folder and do not count against the one-offer-at-a-time
    /// rule, so opening photos one after another never meets "busy".
    /// </summary>
    public IRequestedOfferHandler? RequestedOffers { get; set; }

    /// <summary>
    /// The Accept / Decline prompt. Confirm pairing always asks (with the code both screens show);
    /// the legacy PIN flow asks only when no <see cref="PairingWindow"/> is open.
    /// </summary>
    public Func<InboundPairingRequest, Task<bool>> PairingAuthorizer { get; set; } = _ => Task.FromResult(false);

    /// <summary>How long the user has to compare and accept a confirm code (04-PROTOCOL.md 4.3).</summary>
    public TimeSpan ConfirmTimeout { get; set; } = TimeSpan.FromSeconds(120);

    /// <summary>Bounds each step of the confirm exchange before the prompt (nonce, pairHello).</summary>
    public TimeSpan ConfirmHandshakeTimeout { get; set; } = TimeSpan.FromSeconds(30);
    public Func<InboundOfferRequest, Task<bool>> OfferAuthorizer { get; set; } = _ => Task.FromResult(false);

    /// <summary>
    /// The incoming-files prompt with a precise outcome (ADR-036). When set it replaces
    /// <see cref="OfferAuthorizer"/>, so a timeout reaches the phone as <c>timeout</c>, not <c>declined</c>.
    /// </summary>
    public Func<InboundOfferRequest, Task<OfferVerdict>>? OfferConsent { get; set; }

    /// <summary>How long an accepted sender has to open the VTC1 data socket.</summary>
    public TimeSpan DataConnectTimeout { get; set; } = BulkTransfer.DefaultAcceptTimeout;

    /// <summary>How long the VTC1 data socket may stay silent mid-transfer.</summary>
    public TimeSpan DataIdleTimeout { get; set; } = BulkTransfer.DefaultIdleTimeout;

    /// <summary>
    /// How long a device pairing through the open window has to send a PairHello that verifies.
    /// The user may still be typing the code on the phone, so this is generous. Exceeding it
    /// counts as a failed attempt (04-PROTOCOL.md 4.2).
    /// </summary>
    public TimeSpan WindowPairHelloTimeout { get; set; } = TimeSpan.FromMinutes(2);

    public LinkServer(
        string localDeviceId,
        string localName,
        ITrustStore trustStore,
        string destinationRoot,
        ReadOnlySpan<byte> localPublicKey,
        Func<InboundOfferRequest, Task<bool>> authorize,
        int port = 0,
        PeerSessionRegistry? sessions = null,
        TimeProvider? timeProvider = null,
        Func<byte[], byte[], byte[], byte[]>? deriveSharedKey = null)
    {
        // (peerPublicKey, salt, info) -> HKDF(X25519(identity, peer)); IIdentityManager.DeriveSharedKey
        // in the app. Without it confirm pairing is refused.
        _deriveSharedKey = deriveSharedKey;
        _sessions = sessions ?? new PeerSessionRegistry();
        _localDeviceId = localDeviceId;
        _localName = localName;
        _trustStore = trustStore;
        _destinationRoot = destinationRoot;
        _localPublicKey = localPublicKey.ToArray();
        OfferAuthorizer = authorize;
        Port = port == 0 ? FindFreePort() : port;
        PairingWindow = new PairingWindow(
            localDeviceId,
            () => _localName,
            () => IsRunning ? PairingQr.LocalEndpoints(Port) : [],
            timeProvider);
    }

    public int Port { get; }
    public bool IsRunning => _app is not null;

    /// <summary>The QR / on-screen code pairing window (ADR-030). Closed unless the UI opens it.</summary>
    public PairingWindow PairingWindow { get; }

    /// <summary>A new device was paired (either path). Raised on a server thread.</summary>
    public event Action<TrustedPeer>? PairingCompleted;

    /// <summary>A phone removed this PC (ADR-042); its trust record is already gone. Server thread.</summary>
    public event Action<string>? PeerUnpaired;

    /// <summary>Live authenticated sessions, for companion features (ADR-025).</summary>
    public IPeerSessionRegistry Sessions => _sessions;

    public async Task StartAsync()
    {
        if (_app is not null) return;
        // Restartable: "Pause sync" stops the listener and "Resume" starts it again.
        if (_stop.IsCancellationRequested)
        {
            _stop.Dispose();
            _stop = new CancellationTokenSource();
        }
        VeyroTrace.Info($"Starting control listener; requested port={Port}, range={PortRangeStart}-{PortRangeEnd}");
        string localIpv4 = string.Join(", ", NetworkInterface.GetAllNetworkInterfaces()
            .Where(network => network.OperationalStatus == OperationalStatus.Up)
            .SelectMany(network => network.GetIPProperties().UnicastAddresses)
            .Select(address => address.Address)
            .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address)));
        VeyroTrace.Info($"Local IPv4 addresses: {localIpv4}");
        var builder = WebApplication.CreateSlimBuilder();
        // VLP discovery advertises concrete IPv4 addresses. Bind IPv4 explicitly so Android
        // never receives an endpoint that is only reachable through an IPv6 listener.
        builder.WebHost.ConfigureKestrel(options => options.Listen(IPAddress.Any, Port));
        var app = builder.Build();
        // Sessions are persistent (ADR-025). Transport-level WebSocket pings detect a phone that
        // left the network without closing, so its session (and mirrored state) is torn down.
        app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(20),
            KeepAliveTimeout = TimeSpan.FromSeconds(30)
        });
        app.Map("/veyro", async context => await AcceptWebSocketAsync(context).ConfigureAwait(false));
        _app = app;
        await app.StartAsync().ConfigureAwait(false);
        VeyroTrace.Info($"Control listener started on IPv4 0.0.0.0:{Port}; WebSocket paths=/veyro,/veyro/");
    }

    public async Task StopAsync()
    {
        _stop.Cancel();
        if (_app is { } app)
        {
            _app = null;
            await app.StopAsync().ConfigureAwait(false);
            await app.DisposeAsync().ConfigureAwait(false);
        }
        VeyroTrace.Info("Control listener stopped");
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        PairingWindow.Dispose();
        _stop.Dispose();
    }

    private async Task AcceptWebSocketAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            VeyroTrace.Warn($"Rejected non-WebSocket request: path={context.Request.Path}, remote={context.Connection.RemoteIpAddress}");
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }
        try
        {
            WebSocket socket = await context.WebSockets.AcceptWebSocketAsync().ConfigureAwait(false);
            VeyroTrace.Info($"Accepted WebSocket connection from {context.Connection.RemoteIpAddress}; path={context.Request.Path}");
            await HandleAsync(socket, new SessionAddresses(context.Connection.RemoteIpAddress, context.Connection.LocalIpAddress), _stop.Token).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            VeyroTrace.Error($"WebSocket upgrade failed from {context.Connection.RemoteIpAddress}; path={context.Request.Path}", error);
            if (!context.Response.HasStarted) context.Response.StatusCode = StatusCodes.Status500InternalServerError;
        }
    }

    private async Task HandleAsync(WebSocket socket, SessionAddresses addresses, CancellationToken ct)
    {
        await using var sessionOwner = new WebSocketOwner(socket);
        try
        {
            LinkPreamble? preamble = PeerMessageJson.Decode<LinkPreamble>(await ReceiveTextAsync(socket, ct).ConfigureAwait(false));
            VeyroTrace.Info($"Received preamble: id={preamble?.Id ?? "<null>"}, platform={preamble?.Platform ?? "<null>"}, version={preamble?.ProtocolVersion.ToString() ?? "<null>"}");
            if (preamble is not null && preamble.ProtocolVersion != LinkProtocol.Version)
            {
                // Version 1 predates confirm pairing (ADR-031); refuse rather than guess.
                await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "unsupported-protocol-version")), ct).ConfigureAwait(false);
                return;
            }
            if (preamble is null ||
                DeviceId.ParseOrNull(preamble.Id) is null ||
                string.Equals(preamble.Id, _localDeviceId, StringComparison.OrdinalIgnoreCase))
            {
                await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "invalid-identity")), ct).ConfigureAwait(false);
                return;
            }

            TrustedPeer? trusted = _trustStore.GetPeer(preamble.Id);
            if (trusted is null && preamble.Pair == LinkPreamble.PairConfirm)
            {
                // ADR-031: compare a code on both screens, accept here. Nothing is typed.
                await RunConfirmPairingAsync(socket, preamble, addresses, ct).ConfigureAwait(false);
                return;
            }
            if (trusted is null && preamble.Pair is not null and not LinkPreamble.PairQr)
            {
                await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "unsupported-pairing")), ct).ConfigureAwait(false);
                return;
            }
            bool pairing = trusted is null;
            string? pairingPin = null;
            PairingAttempt? windowAttempt = null;
            if (trusted is null)
            {
                windowAttempt = PairingWindow.TryBeginAttempt();
                if (windowAttempt is not null)
                {
                    // ADR-030: the user opened the pairing screen, so scanning or typing its code
                    // is the consent. No prompt; the code is never logged.
                    pairingPin = windowAttempt.Code;
                    VeyroTrace.Info($"Unknown peer {preamble.Id} pairing through the open pairing window");
                }
                else if (preamble.Pair == LinkPreamble.PairQr)
                {
                    // A scanned code is only valid while its window is open (04-PROTOCOL.md 4.2).
                    VeyroTrace.Info($"QR pairing from {preamble.Id} refused: no pairing window is open");
                    await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "no-pairing-window")), ct).ConfigureAwait(false);
                    return;
                }
                else
                {
                    // Legacy PIN flow (pair absent), kept for tests: the prompt shows the PIN.
                    pairingPin = FrameCrypto.NewPin();
                    bool approved = await PairingAuthorizer(new InboundPairingRequest(
                        preamble.Id, preamble.Name, pairingPin, DateTimeOffset.UtcNow + TimeSpan.FromSeconds(60), ct)).ConfigureAwait(false);
                    if (!approved)
                    {
                        await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "declined")), ct).ConfigureAwait(false);
                        return;
                    }
                }
            }

            byte[] secret = pairing
                ? FrameCrypto.DeriveBootstrapKey(pairingPin!, preamble.Id, _localDeviceId)
                : DecodeBase64(trusted!.PairingSecretBase64, "stored pairing secret");
            if (secret.Length != 32)
            {
                await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "invalid-trust-record")), ct).ConfigureAwait(false);
                return;
            }

            await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(
                pairing ? LinkPreambleAck.ModePairing : LinkPreambleAck.ModeSession)), ct).ConfigureAwait(false);
            await using var secure = new SecureLinkSession(socket, _localDeviceId, secret, encrypt: !pairing, preamble.Id);
            if (pairing)
            {
                SecureLinkMessage pairMessage = (windowAttempt is null
                    ? await secure.ReceiveAsync(ct).ConfigureAwait(false)
                    : await ReceiveWindowPairHelloAsync(secure, windowAttempt, ct).ConfigureAwait(false))
                    ?? throw new InvalidDataException("Peer closed before PairHello.");
                if (pairMessage.Envelope.Payload is not PeerMessage.PairHello pairHello ||
                    !string.Equals(pairHello.DeviceId, preamble.Id, StringComparison.OrdinalIgnoreCase))
                    throw new UnauthorizedAccessException("PairHello identity mismatch.");
                byte[] peerPublicKey = DecodeBase64(pairHello.PublicKeyB64, "PairHello public key");
                var peerId = DeviceId.ParseOrNull(pairHello.DeviceId);
                if (peerId is null || peerPublicKey.Length != 32 || !DeviceId.Matches(peerId.Value, peerPublicKey))
                    throw new UnauthorizedAccessException("PairHello public-key binding failed.");
                byte[] sessionSecret = FrameCrypto.NewSessionSecret();
                trusted = new TrustedPeer(
                    peerId.Value.Value,
                    pairHello.DeviceName,
                    PlatformType.Android,
                    Convert.ToBase64String(peerPublicKey),
                    Convert.ToBase64String(sessionSecret),
                    false,
                    DateTimeOffset.UtcNow);
                await _trustStore.UpsertAsync(trusted).ConfigureAwait(false);
                await secure.SendSealedAsync(new PeerMessage.PairOk(
                    _localDeviceId,
                    _localName,
                    "windows",
                    Convert.ToBase64String(sessionSecret),
                    Convert.ToBase64String(_localPublicKey)), ct).ConfigureAwait(false);
                secure.Rekey(sessionSecret);
                if (windowAttempt is not null) PairingWindow.Complete(trusted);
                try { PairingCompleted?.Invoke(trusted); }
                catch (Exception error) { VeyroTrace.Error("PairingCompleted handler failed", error); }
            }
            TrustedPeer pairedPeer = trusted ?? throw new UnauthorizedAccessException("Pairing did not establish trust.");
            await RunSessionAsync(secure, pairedPeer, addresses, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
        catch (Exception error)
        {
            // Do not disclose trust, filesystem, or protocol details to an unauthenticated peer.
            VeyroTrace.Error("Inbound WebSocket session failed", error);
            try { await socket.CloseAsync(WebSocketCloseStatus.ProtocolError, "protocol-error", CancellationToken.None).ConfigureAwait(false); }
            catch { }
        }
    }

    private enum ConfirmOutcome { Accepted, Declined, TimedOut, Abandoned }

    /// <summary>
    /// Receiver side of confirm pairing (04-PROTOCOL.md 4.3, ADR-031). The phone is A, this PC B:
    /// commit to nB, learn nA, reveal nB, check the phone's pairHello under the bootstrap key K,
    /// then ask the user to compare the code. <c>pairOk</c> is sent only after Accept. Nonces, the
    /// code and K are never logged.
    /// </summary>
    private async Task RunConfirmPairingAsync(WebSocket socket, LinkPreamble preamble, SessionAddresses addresses, CancellationToken ct)
    {
        byte[]? pkA = TryDecodeKey(preamble.Pk);
        DeviceId? claimed = DeviceId.ParseOrNull(preamble.Id);
        if (_deriveSharedKey is null || pkA is null || claimed is null || !DeviceId.Matches(claimed.Value, pkA))
        {
            string reason = _deriveSharedKey is null ? "pairing-unavailable" : "identity-mismatch";
            VeyroTrace.Warn($"Confirm pairing from {preamble.Id} refused: {reason}");
            await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, reason)), ct).ConfigureAwait(false);
            return;
        }
        string idA = claimed.Value.Value;

        byte[] nB = PairConfirm.NewNonce();
        await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreambleAck(
            LinkPreambleAck.ModePairingConfirm,
            Pk: Convert.ToBase64String(_localPublicKey),
            Commit: Convert.ToBase64String(PairConfirm.Commitment(_localPublicKey, pkA, nB)))), ct).ConfigureAwait(false);

        byte[] nA = await ReceivePairNonceAsync(socket, ct).ConfigureAwait(false);
        await SendTextAsync(socket, PeerMessageJson.Encode(LinkPairNonce.For(nB)), ct).ConfigureAwait(false);

        string code = PairConfirm.Code(pkA, _localPublicKey, nA, nB);
        byte[] key = _deriveSharedKey(pkA, PairConfirm.BootstrapSalt(nA, nB), PairConfirm.BootstrapInfo(idA, _localDeviceId));
        await using var secure = new SecureLinkSession(socket, _localDeviceId, key, encrypt: false, preamble.Id);
        CryptographicOperations.ZeroMemory(key);

        // A pairHello that verifies under K proves the phone holds the private key behind pkA.
        PeerMessage.PairHello hello = await ReceiveConfirmPairHelloAsync(secure, ct).ConfigureAwait(false);
        byte[] helloKey = DecodeBase64(hello.PublicKeyB64, "PairHello public key");
        if (!string.Equals(hello.DeviceId, idA, StringComparison.OrdinalIgnoreCase) ||
            !CryptographicOperations.FixedTimeEquals(helloKey, pkA))
            throw new UnauthorizedAccessException("PairHello does not match the confirmed identity.");

        VeyroTrace.Info($"Confirm pairing with {idA}: waiting for the user");
        using var abandon = CancellationTokenSource.CreateLinkedTokenSource(ct);
        using var timer = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var request = new InboundPairingRequest(idA, hello.DeviceName, code, DateTimeOffset.UtcNow + ConfirmTimeout, abandon.Token);
        Task<bool> decision = AuthorizeSafelyAsync(request);
        Task timeout = Task.Delay(ConfirmTimeout, timer.Token);
        Task<SecureLinkMessage?> pending = secure.ReceiveAsync(ct);
        ConfirmOutcome outcome;
        while (true)
        {
            Task winner = await Task.WhenAny(decision, pending, timeout).ConfigureAwait(false);
            if (winner == decision) { outcome = decision.Result ? ConfirmOutcome.Accepted : ConfirmOutcome.Declined; break; }
            if (winner == timeout) { outcome = ConfirmOutcome.TimedOut; break; }
            if (pending.IsCompletedSuccessfully && pending.Result?.Envelope.Payload is PeerMessage.Ping ping)
            {
                await secure.SendAsync(new PeerMessage.Pong(ping.Nonce), ct).ConfigureAwait(false);
                pending = secure.ReceiveAsync(ct);
                continue;
            }
            // pairFail("cancelled"), a closed socket, or anything else: the phone gave up.
            outcome = ConfirmOutcome.Abandoned;
            break;
        }
        timer.Cancel();

        if (outcome != ConfirmOutcome.Accepted)
        {
            abandon.Cancel(); // closes the prompt if it is still open
            _ = pending.ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted);
            string? reply = outcome switch
            {
                ConfirmOutcome.Declined => "declined",
                ConfirmOutcome.TimedOut => "timeout",
                _ => null
            };
            VeyroTrace.Info($"Confirm pairing with {idA} ended: {outcome}");
            if (reply is not null)
            {
                try { await secure.SendAsync(new PeerMessage.PairFail(reply), ct).ConfigureAwait(false); }
                catch (Exception error) when (error is WebSocketException or IOException or InvalidOperationException) { }
                // Let the phone read it and close first, so the close does not race the reply.
                await Task.WhenAny(pending, Task.Delay(TimeSpan.FromSeconds(2), ct)).ConfigureAwait(false);
            }
            return;
        }

        byte[] sessionSecret = FrameCrypto.NewSessionSecret();
        var trusted = new TrustedPeer(
            idA,
            hello.DeviceName,
            PlatformType.Android,
            Convert.ToBase64String(pkA),
            Convert.ToBase64String(sessionSecret),
            false,
            DateTimeOffset.UtcNow);
        await _trustStore.UpsertAsync(trusted).ConfigureAwait(false);
        await secure.SendSealedAsync(new PeerMessage.PairOk(
            _localDeviceId,
            _localName,
            "windows",
            Convert.ToBase64String(sessionSecret),
            Convert.ToBase64String(_localPublicKey)), ct).ConfigureAwait(false);
        secure.Rekey(sessionSecret);
        CryptographicOperations.ZeroMemory(sessionSecret);
        VeyroTrace.Info($"Confirm pairing with {idA} completed");
        try { PairingCompleted?.Invoke(trusted); }
        catch (Exception error) { VeyroTrace.Error("PairingCompleted handler failed", error); }
        await RunSessionAsync(secure, trusted, addresses, ct, pending).ConfigureAwait(false);
    }

    /// <summary>
    /// Pairs with a phone the user picked from this PC's nearby list (hardware test: "if the phone
    /// shows on the PC I should be able to pair from there"). Same confirm protocol (04-PROTOCOL.md
    /// 4.3) with roles swapped: this PC is the initiator A, the phone is B and shows Accept /
    /// Decline. <paramref name="showCode"/> receives the 6-digit code to display while waiting.
    /// On success the phone is trusted and its session keeps running like any other.
    /// </summary>
    public async Task<PcPairResult> PairWithPhoneAsync(
        PeerEndpoint endpoint,
        string phoneDeviceId,
        Action<string> showCode,
        CancellationToken ct)
    {
        if (_deriveSharedKey is null || !IsRunning) return new PcPairResult(PcPairOutcome.Failed);
        DeviceId? expected = DeviceId.ParseOrNull(phoneDeviceId);
        if (expected is null) return new PcPairResult(PcPairOutcome.IdentityMismatch);

        var socket = new ClientWebSocket { Options = { KeepAliveInterval = TimeSpan.FromSeconds(20) } };
        bool handedOff = false;
        try
        {
            using (var connect = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                connect.CancelAfter(TimeSpan.FromSeconds(8));
                try { await socket.ConnectAsync(new Uri($"ws://{endpoint.Host}:{endpoint.Port}/veyro"), connect.Token).ConfigureAwait(false); }
                catch (Exception error) when (error is WebSocketException or OperationCanceledException && !ct.IsCancellationRequested)
                {
                    VeyroTrace.Warn($"PC-initiated pairing: phone unreachable ({error.GetType().Name})");
                    return new PcPairResult(PcPairOutcome.Unreachable);
                }
            }

            await SendTextAsync(socket, PeerMessageJson.Encode(new LinkPreamble(
                _localDeviceId, _localName, "windows",
                Pair: LinkPreamble.PairConfirm,
                Pk: Convert.ToBase64String(_localPublicKey))), ct).ConfigureAwait(false);
            LinkPreambleAck? ack = PeerMessageJson.Decode<LinkPreambleAck>(await ReceiveTextAsync(socket, ct).ConfigureAwait(false));
            if (ack is null || ack.Mode != LinkPreambleAck.ModePairingConfirm)
            {
                VeyroTrace.Warn($"PC-initiated pairing refused: {ack?.Mode ?? "no answer"} {ack?.Reason}");
                return new PcPairResult(ack?.Mode == LinkPreambleAck.ModeBusy ? PcPairOutcome.Busy : PcPairOutcome.Refused);
            }

            // The answering key must hash to the phone the user picked, and its nonce must match
            // the commitment it made before seeing ours.
            byte[]? pkB = TryDecodeKey(ack.Pk);
            byte[]? commit = string.IsNullOrEmpty(ack.Commit) ? null : DecodeBase64(ack.Commit, "commit");
            if (pkB is null || commit is null || !DeviceId.Matches(expected.Value, pkB))
                return new PcPairResult(PcPairOutcome.IdentityMismatch);

            byte[] nA = PairConfirm.NewNonce();
            await SendTextAsync(socket, PeerMessageJson.Encode(LinkPairNonce.For(nA)), ct).ConfigureAwait(false);
            byte[] nB = await ReceivePairNonceAsync(socket, ct).ConfigureAwait(false);
            if (!CryptographicOperations.FixedTimeEquals(commit, PairConfirm.Commitment(pkB, _localPublicKey, nB)))
            {
                VeyroTrace.Warn("PC-initiated pairing aborted: commitment mismatch; nothing stored");
                return new PcPairResult(PcPairOutcome.IdentityMismatch);
            }

            string phoneId = expected.Value.Value;
            showCode(PairConfirm.Code(_localPublicKey, pkB, nA, nB));
            byte[] key = _deriveSharedKey(pkB, PairConfirm.BootstrapSalt(nA, nB), PairConfirm.BootstrapInfo(_localDeviceId, phoneId));
            var secure = new SecureLinkSession(socket, _localDeviceId, key, encrypt: false, phoneId);
            CryptographicOperations.ZeroMemory(key);
            try
            {
                await secure.SendAsync(new PeerMessage.PairHello(
                    _localDeviceId, _localName, "windows", "1.0",
                    PublicKeyB64: Convert.ToBase64String(_localPublicKey)), ct).ConfigureAwait(false);

                // The phone shows the same code; wait for its Accept (pairOk) or Decline (pairFail).
                using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
                wait.CancelAfter(ConfirmTimeout);
                PeerMessage.PairOk? ok = null;
                while (ok is null)
                {
                    SecureLinkMessage? message;
                    try { message = await secure.ReceiveAsync(wait.Token).ConfigureAwait(false); }
                    catch (OperationCanceledException) when (!ct.IsCancellationRequested)
                    {
                        return new PcPairResult(PcPairOutcome.TimedOut);
                    }
                    switch (message?.Envelope.Payload)
                    {
                        case null:
                            return new PcPairResult(PcPairOutcome.Failed);
                        case PeerMessage.PairFail fail:
                            VeyroTrace.Info($"PC-initiated pairing ended by the phone: {fail.Reason}");
                            return new PcPairResult(fail.Reason == "timeout" ? PcPairOutcome.TimedOut : PcPairOutcome.Declined);
                        case PeerMessage.Ping ping:
                            await secure.SendAsync(new PeerMessage.Pong(ping.Nonce), ct).ConfigureAwait(false);
                            break;
                        case PeerMessage.PairOk pairOk:
                            ok = pairOk;
                            break;
                    }
                }

                byte[] okKey = DecodeBase64(ok.PublicKeyB64, "pairOk public key");
                byte[] sessionSecret = DecodeBase64(ok.SessionSecretB64, "session secret");
                if (!string.Equals(ok.DeviceId, phoneId, StringComparison.OrdinalIgnoreCase) ||
                    !CryptographicOperations.FixedTimeEquals(okKey, pkB) || sessionSecret.Length != 32)
                    return new PcPairResult(PcPairOutcome.IdentityMismatch);

                var trusted = new TrustedPeer(
                    phoneId, ok.DeviceName, PlatformType.Android,
                    Convert.ToBase64String(pkB), Convert.ToBase64String(sessionSecret),
                    false, DateTimeOffset.UtcNow);
                await _trustStore.UpsertAsync(trusted).ConfigureAwait(false);
                secure.Rekey(sessionSecret);
                CryptographicOperations.ZeroMemory(sessionSecret);
                await secure.SendAsync(new PeerMessage.Hello(
                    _localDeviceId, _localName, "windows", Capabilities: LocalCapabilities), ct).ConfigureAwait(false);
                VeyroTrace.Info($"PC-initiated pairing with {phoneId} completed");
                try { PairingCompleted?.Invoke(trusted); }
                catch (Exception error) { VeyroTrace.Error("PairingCompleted handler failed", error); }

                IPAddress? remote = IPAddress.TryParse(endpoint.Host, out IPAddress? parsed) ? parsed : null;
                Task run = RunOutboundSessionAsync(secure, trusted, new SessionAddresses(remote, null), CancellationToken.None);
                _ = run.ContinueWith(_ => secure.DisposeAsync().AsTask(), CancellationToken.None,
                    TaskContinuationOptions.None, TaskScheduler.Default).Unwrap();
                handedOff = true;
                return new PcPairResult(PcPairOutcome.Paired, ok.DeviceName);
            }
            finally
            {
                if (!handedOff) await secure.DisposeAsync().ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            return new PcPairResult(PcPairOutcome.Cancelled);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("PC-initiated pairing failed", error);
            return new PcPairResult(PcPairOutcome.Failed);
        }
        finally
        {
            // Owned by the running session once handed off; otherwise closed here.
            if (!handedOff) socket.Dispose();
        }
    }

    private async Task<bool> AuthorizeSafelyAsync(InboundPairingRequest request)
    {
        try { return await PairingAuthorizer(request).ConfigureAwait(false); }
        catch (Exception error)
        {
            VeyroTrace.Error("Pairing prompt failed; treating it as Decline", error);
            return false;
        }
    }

    private async Task<byte[]> ReceivePairNonceAsync(WebSocket socket, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(ConfirmHandshakeTimeout);
        LinkPairNonce? message = PeerMessageJson.Decode<LinkPairNonce>(await ReceiveTextAsync(socket, timeout.Token).ConfigureAwait(false));
        if (message is null || message.T != LinkPairNonce.Type)
            throw new InvalidDataException("Expected pairNonce.");
        byte[] nonce = DecodeBase64(message.N, "pairNonce");
        if (nonce.Length != PairConfirm.NonceSize)
            throw new InvalidDataException("pairNonce has the wrong length.");
        return nonce;
    }

    private async Task<PeerMessage.PairHello> ReceiveConfirmPairHelloAsync(SecureLinkSession secure, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(ConfirmHandshakeTimeout);
        SecureLinkMessage message = await secure.ReceiveAsync(timeout.Token).ConfigureAwait(false)
            ?? throw new InvalidDataException("Peer closed before PairHello.");
        return message.Envelope.Payload as PeerMessage.PairHello
            ?? throw new InvalidDataException("Expected PairHello.");
    }

    private static byte[]? TryDecodeKey(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        try
        {
            byte[] key = Convert.FromBase64String(value);
            return key.Length == 32 ? key : null;
        }
        catch (FormatException) { return null; }
    }

    /// <summary>
    /// Receives the PairHello of a device pairing through the open window. A frame that does not
    /// verify under the window's code (wrong code) or no PairHello in time is a failed attempt;
    /// after <see cref="PairingWindow.MaxFailures"/> the window issues a new code.
    /// </summary>
    private async Task<SecureLinkMessage?> ReceiveWindowPairHelloAsync(
        SecureLinkSession secure, PairingAttempt attempt, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(WindowPairHelloTimeout);
        try
        {
            return await secure.ReceiveAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (UnauthorizedAccessException)
        {
            VeyroTrace.Warn("Pairing window: PairHello did not verify (wrong code)");
            PairingWindow.RecordFailure(attempt);
            throw;
        }
        catch (Exception error) when (error is OperationCanceledException or WebSocketException &&
                                      timeout.IsCancellationRequested && !ct.IsCancellationRequested)
        {
            VeyroTrace.Warn("Pairing window: no PairHello before the timeout");
            PairingWindow.RecordFailure(attempt);
            throw new TimeoutException("PairHello did not arrive in time.", error);
        }
    }

    private static async Task SendTextAsync(WebSocket socket, string text, CancellationToken ct)
        => await socket.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, ct).ConfigureAwait(false);

    private static async Task<string> ReceiveTextAsync(WebSocket socket, CancellationToken ct)
    {
        byte[] buffer = new byte[64 * 1024];
        WebSocketReceiveResult result = await socket.ReceiveAsync(buffer, ct).ConfigureAwait(false);
        if (result.MessageType != WebSocketMessageType.Text || !result.EndOfMessage || result.Count > buffer.Length)
            throw new InvalidDataException("Invalid VLP preamble.");
        return Encoding.UTF8.GetString(buffer, 0, result.Count);
    }

    /// <summary>
    /// A session this PC dialled (ADR-040), run by the same loop as an inbound one. The outbound
    /// Hello was already sent by the dialler; the phone's HelloAck and its own Hello are handled
    /// here as on any session.
    /// </summary>
    public Task RunOutboundSessionAsync(SecureLinkSession secure, TrustedPeer peer, SessionAddresses addresses, CancellationToken ct = default)
    {
        if (!IsRunning) throw new InvalidOperationException("The listener is stopped.");
        // The listener's stop token ends dialled sessions too, so "Pause sync" disconnects them.
        var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, _stop.Token);
        return RunOutboundCoreAsync(linked);

        async Task RunOutboundCoreAsync(CancellationTokenSource cancellation)
        {
            try
            {
                await RunSessionAsync(secure, peer, addresses, cancellation.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
            catch (Exception error)
            {
                VeyroTrace.Error("Outbound session failed", error);
            }
            finally
            {
                cancellation.Dispose();
            }
        }
    }

    private static async Task CloseReplacedAsync(SecureLinkSession secure, CancellationTokenSource sessionStop)
    {
        VeyroTrace.Info("Session replaced by a newer one from the same peer; closing it");
        await secure.CloseOutputAsync("replaced").ConfigureAwait(false);
        // A peer that never answers the close must not keep the old loop alive.
        try { sessionStop.CancelAfter(ReplacedCloseTimeout); }
        catch (ObjectDisposedException) { }
    }

    /// <summary>
    /// The persistent authenticated session (ADR-025), shared by inbound sessions and the ones
    /// this PC dials (ADR-040). Runs until the peer closes the socket, a newer session from the
    /// same peer replaces it, or the server stops. Transfers run one at a time in the background
    /// so heartbeats and companion messages keep flowing while a VTC1 transfer is in progress.
    /// </summary>
    private async Task RunSessionAsync(
        SecureLinkSession secure,
        TrustedPeer peer,
        SessionAddresses addresses,
        CancellationToken ct,
        Task<SecureLinkMessage?>? pendingReceive = null)
    {
        using var sessionStop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        CancellationToken sessionCt = sessionStop.Token;
        using PeerSessionRegistry.Registration registration = _sessions.Open(
            peer.DeviceId, secure.SendAsync, onReplaced: () => _ = CloseReplacedAsync(secure, sessionStop));
        VeyroTrace.Info($"Session opened for peer {peer.DeviceId}");
        Task? activeTransfer = null;
        string? activeTransferId = null;
        CancellationTokenSource? transferCancellation = null;
        // ADR-034: offers answering our own requests, keyed by transferId.
        var requested = new Dictionary<string, (Task Task, CancellationTokenSource Cancellation)>(StringComparer.Ordinal);
        try
        {
            while (true)
            {
                SecureLinkMessage? message;
                try
                {
                    // Confirm pairing hands over the receive it already had outstanding while the
                    // user decided; it reads with whatever key is current, so after Rekey that is
                    // the session secret.
                    Task<SecureLinkMessage?> receive = pendingReceive ?? secure.ReceiveAsync(sessionCt);
                    pendingReceive = null;
                    message = await receive.ConfigureAwait(false);
                }
                catch (Exception error) when (error is WebSocketException or IOException && !ct.IsCancellationRequested)
                {
                    VeyroTrace.Info(registration.IsReplaced
                        ? $"Replaced session closed for peer {peer.DeviceId}"
                        : $"Session closed by peer {peer.DeviceId}");
                    break;
                }
                if (message is null) break;

                // ADR-034: replies to a PC-to-phone transfer sent over this session go to its sender.
                if (registration.Transfers.TryDeliver(message.Envelope.Payload)) continue;

                // ADR-040: a replaced session only drains until its close completes; the newer
                // session carries the companion state, so nothing here is published twice.
                if (registration.IsReplaced) continue;

                switch (message.Envelope.Payload)
                {
                    case PeerMessage.Hello hello:
                        // Answer first: setting the capabilities lets companion services send
                        // their on-connect snapshots, which must not overtake the HelloAck.
                        await secure.SendAsync(new PeerMessage.HelloAck(
                            _localDeviceId, _localName, "windows", LocalCapabilities), sessionCt).ConfigureAwait(false);
                        registration.Capabilities = hello.Capabilities;
                        break;
                    case PeerMessage.HelloAck helloAck:
                        registration.Capabilities = helloAck.Capabilities;
                        break;
                    case PeerMessage.Ping ping:
                        await secure.SendAsync(new PeerMessage.Pong(ping.Nonce), sessionCt).ConfigureAwait(false);
                        break;
                    case PeerMessage.Pong:
                        break;
                    case PeerMessage.TransferOffer offer when ClaimRequested(peer.DeviceId, offer) is { } claim:
                    {
                        foreach (string done in requested.Where(entry => entry.Value.Task.IsCompleted).Select(entry => entry.Key).ToList())
                        {
                            requested[done].Cancellation.Dispose();
                            requested.Remove(done);
                        }
                        if (requested.ContainsKey(offer.TransferId))
                        {
                            VeyroTrace.Warn("Requested offer answered busy: its transfer id is already running");
                            await secure.SendAsync(new PeerMessage.TransferDecision(offer.TransferId, false, OfferDeclineReasons.Busy), sessionCt).ConfigureAwait(false);
                            RequestedOffers?.OnFailed(peer.DeviceId, offer.RequestId!, new InvalidDataException("Duplicate transfer id."));
                            break;
                        }
                        var cancellation = CancellationTokenSource.CreateLinkedTokenSource(sessionCt);
                        requested[offer.TransferId] = (
                            HandleOfferAsync(secure, peer, offer, addresses, claim, cancellation.Token),
                            cancellation);
                        break;
                    }
                    case PeerMessage.TransferOffer offer:
                        // HandleOfferAsync always finishes (prompt deadline, data connect and idle
                        // timeouts), so a failed transfer cannot leave this session busy for good.
                        if (activeTransfer is { IsCompleted: false })
                        {
                            VeyroTrace.Info("Offer answered busy: another transfer is running on this session");
                            await secure.SendAsync(new PeerMessage.TransferDecision(offer.TransferId, false, OfferDeclineReasons.Busy), sessionCt).ConfigureAwait(false);
                            break;
                        }
                        transferCancellation?.Dispose();
                        transferCancellation = CancellationTokenSource.CreateLinkedTokenSource(sessionCt);
                        activeTransferId = offer.TransferId;
                        activeTransfer = HandleOfferAsync(secure, peer, offer, addresses, claim: null, transferCancellation.Token);
                        break;
                    case PeerMessage.Cancel cancel:
                        if (activeTransfer is { IsCompleted: false } && cancel.TransferId == activeTransferId)
                            transferCancellation!.Cancel();
                        else if (requested.TryGetValue(cancel.TransferId, out var running) && !running.Task.IsCompleted)
                            running.Cancellation.Cancel();
                        break;
                    // ADR-034. File names and images are user data: never logged.
                    case PeerMessage.Photos or PeerMessage.PhotoThumb or PeerMessage.PhotoNew or PeerMessage.PhotoRemoved:
                        registration.Capabilities |= PeerCapabilities.Photos;
                        _sessions.PublishPhotos(peer.DeviceId, message.Envelope.Payload);
                        break;
                    case PeerMessage.Notification notification:
                        // A peer that sends notif speaks the notification family even if this
                        // session carried no Hello (e.g. straight after pairing). Contents are
                        // user data: never logged (04-PROTOCOL.md 6.1).
                        registration.Capabilities |= PeerCapabilities.NotificationMirror;
                        _sessions.PublishNotification(peer.DeviceId, notification);
                        break;
                    // ADR-026. Same inference as notif: a peer that sends the family speaks it.
                    // Media metadata is user data: never logged (04-PROTOCOL.md 6.2).
                    case PeerMessage.MediaState mediaState:
                        registration.Capabilities |= PeerCapabilities.MediaControl;
                        _sessions.PublishMediaState(peer.DeviceId, mediaState);
                        break;
                    case PeerMessage.MediaCommand mediaCommand:
                        registration.Capabilities |= PeerCapabilities.MediaControl;
                        _sessions.PublishMediaCommand(peer.DeviceId, mediaCommand);
                        break;
                    case PeerMessage.DeviceStatus deviceStatus:
                        registration.Capabilities |= PeerCapabilities.DeviceStatus;
                        _sessions.PublishDeviceStatus(peer.DeviceId, deviceStatus);
                        break;
                    // ADR-038. Only an authenticated session reaches here; trust is re-read so a
                    // phone removed while connected cannot lock the PC.
                    case PeerMessage.PcLock:
                        if (_trustStore.GetPeer(peer.DeviceId) is null)
                        {
                            VeyroTrace.Warn("PC lock ignored: the peer is no longer trusted");
                            break;
                        }
                        VeyroTrace.Info("PC lock requested");
                        _sessions.PublishDeviceMessage(peer.DeviceId, message.Envelope.Payload);
                        break;
                    // ADR-042: the phone removed this PC. Forget it here too and end the session.
                    case PeerMessage.Unpair:
                        VeyroTrace.Info("Peer removed this PC; forgetting it");
                        await _trustStore.RemoveAsync(peer.DeviceId).ConfigureAwait(false);
                        try { PeerUnpaired?.Invoke(peer.DeviceId); }
                        catch (Exception error) { VeyroTrace.Error("PeerUnpaired handler failed", error); }
                        return;
                    // ADR-043. Clipboard contents are user data: never logged.
                    case PeerMessage.Clip:
                        registration.Capabilities |= PeerCapabilities.Clipboard;
                        _sessions.PublishDeviceMessage(peer.DeviceId, message.Envelope.Payload);
                        break;
                    // Wallpaper bytes are user data: never logged.
                    case PeerMessage.Wallpaper:
                        registration.Capabilities |= PeerCapabilities.Wallpaper;
                        _sessions.PublishDeviceMessage(peer.DeviceId, message.Envelope.Payload);
                        break;
                    // ADR-027, same inference. Bodies, numbers and names are never logged.
                    case PeerMessage.SimList or PeerMessage.SmsThreads or PeerMessage.SmsMessages or
                         PeerMessage.SmsNew or PeerMessage.SmsSendResult:
                        registration.Capabilities |= PeerCapabilities.Messaging;
                        _sessions.PublishMessagingOrCall(peer.DeviceId, message.Envelope.Payload);
                        break;
                    case PeerMessage.CallState or PeerMessage.CallLog:
                        registration.Capabilities |= PeerCapabilities.Calls;
                        _sessions.PublishMessagingOrCall(peer.DeviceId, message.Envelope.Payload);
                        break;
                    default:
                        VeyroTrace.Info($"Ignoring {message.Envelope.Payload.GetType().Name} in session");
                        break;
                }
            }
        }
        catch (Exception error) when (registration.IsReplaced && !ct.IsCancellationRequested &&
                                      error is OperationCanceledException or WebSocketException or IOException or InvalidOperationException)
        {
            // ADR-040: the close of a replaced session (its close frame went out, or the peer did
            // not answer it in time). Not a failure.
            VeyroTrace.Info($"Replaced session ended for peer {peer.DeviceId}");
        }
        finally
        {
            if (activeTransfer is not null)
            {
                transferCancellation!.Cancel();
                try { await activeTransfer.ConfigureAwait(false); } catch { }
            }
            transferCancellation?.Dispose();
            foreach ((Task task, CancellationTokenSource cancellation) in requested.Values)
            {
                cancellation.Cancel();
                try { await task.ConfigureAwait(false); } catch { }
                cancellation.Dispose();
            }
            VeyroTrace.Info($"Session ended for peer {peer.DeviceId}");
        }
    }

    private async Task HandleOfferAsync(
        SecureLinkSession secure,
        TrustedPeer sessionPeer,
        PeerMessage.TransferOffer offer,
        SessionAddresses addresses,
        RequestedOfferClaim? claim,
        CancellationToken ct)
    {
        bool decided = false;
        string destinationRoot = claim?.DestinationDirectory ?? _destinationRoot;
        try
        {
            // Re-read trust per offer: the user may have revoked the peer or changed auto-accept
            // since the session was opened. A claimed offer answers our own request (ADR-034): the
            // user already asked for it, so there is no prompt.
            TrustedPeer? peer = _trustStore.GetPeer(sessionPeer.DeviceId);
            OfferVerdict verdict = peer is null ? OfferVerdict.Declined
                : claim is not null || peer.AutoAcceptTransfers ? OfferVerdict.Accepted
                : await AskConsentAsync(new InboundOfferRequest(peer, offer, ct)).ConfigureAwait(false);
            // The phone cancelled the offer while the prompt was up: it expects no decision.
            ct.ThrowIfCancellationRequested();
            if (verdict != OfferVerdict.Accepted)
            {
                decided = true;
                string reason = OfferDeclineReasons.For(verdict);
                VeyroTrace.Info($"Inbound offer declined: reason={reason}");
                await secure.SendAsync(new PeerMessage.TransferDecision(offer.TransferId, false, reason), ct).ConfigureAwait(false);
                if (claim is not null) RequestedOffers?.OnFailed(sessionPeer.DeviceId, offer.RequestId!, new UnauthorizedAccessException("The peer is no longer trusted."));
                return;
            }

            var inboundItems = offer.Items
                .Where(item => !item.IsDir)
                .Select(item => new InboundTransferItem(item.Index, item.RelPath, item.Size))
                .ToList();
            Directory.CreateDirectory(destinationRoot);
            var resume = BulkTransfer.BuildResume(destinationRoot, inboundItems);
            BulkTransferResult result;

            using var tcp = new TcpListener(IPAddress.Any, FindFreePort(Port));
            tcp.Start();
            try
            {
                string host = FindAdvertisableAddress(addresses);
                string token = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
                decided = true;
                // Whatever the phone preferred (WIFI_DIRECT, LAN_TCP), Windows serves LAN_TCP on
                // the address the phone already reached this session on (04-PROTOCOL.md 7.1).
                await secure.SendAsync(new PeerMessage.TransferDecision(
                    offer.TransferId,
                    true,
                    Route: RouteWireNames.LanTcp,
                    TcpHost: host,
                    TcpPort: ((IPEndPoint)tcp.LocalEndpoint).Port,
                    FallbackHost: host,
                    AuthToken: token,
                    Resume: resume), ct).ConfigureAwait(false);
                result = await BulkTransfer.ReceiveAsync(
                    tcp,
                    offer.TransferId,
                    token,
                    sessionPeer.DeviceId,
                    destinationRoot,
                    inboundItems,
                    ct: ct,
                    // Android's sender waits for the session itemResult after each item, not the
                    // VTC1 one; without it every phone-to-PC transfer (photos included) stalled.
                    onItemResult: (index, ok, error, sendCt) =>
                        secure.SendAsync(new PeerMessage.ItemResult(offer.TransferId, index, ok, error), sendCt),
                    acceptTimeout: DataConnectTimeout,
                    idleTimeout: DataIdleTimeout).ConfigureAwait(false);
            }
            finally
            {
                tcp.Stop();
            }
            await secure.SendAsync(new PeerMessage.TransferEnd(offer.TransferId, true), ct).ConfigureAwait(false);
            if (claim is not null)
            {
                // BulkTransfer validated every path against the root before writing it.
                var paths = result.CompletedPaths
                    .Select(relative => Path.GetFullPath(Path.Combine(destinationRoot, relative.Replace('/', Path.DirectorySeparatorChar))))
                    .ToList();
                RequestedOffers?.OnCompleted(sessionPeer.DeviceId, offer.RequestId!, paths);
            }
        }
        catch (OperationCanceledException error) when (ct.IsCancellationRequested)
        {
            VeyroTrace.Info("Inbound transfer cancelled");
            if (claim is not null) RequestedOffers?.OnFailed(sessionPeer.DeviceId, offer.RequestId!, error);
        }
        catch (Exception error)
        {
            // Type only: exception messages can name the user's files.
            VeyroTrace.Warn($"Inbound transfer failed: {error.GetType().Name}");
            if (claim is not null) RequestedOffers?.OnFailed(sessionPeer.DeviceId, offer.RequestId!, error);
            try
            {
                PeerMessage failure = decided
                    ? new PeerMessage.TransferEnd(offer.TransferId, false)
                    : new PeerMessage.TransferDecision(offer.TransferId, false, OfferDeclineReasons.Error);
                await secure.SendAsync(failure, CancellationToken.None).ConfigureAwait(false);
            }
            catch { }
        }
    }

    /// <summary>A throwing prompt is an "error" decision (the outer catch), never a silent Decline.</summary>
    private async Task<OfferVerdict> AskConsentAsync(InboundOfferRequest request)
    {
        if (OfferConsent is { } consent) return await consent(request).ConfigureAwait(false);
        return await OfferAuthorizer(request).ConfigureAwait(false) ? OfferVerdict.Accepted : OfferVerdict.Declined;
    }

    /// <summary>ADR-034: an offer carrying a requestId the handler issued. Others take the consent path.</summary>
    private RequestedOfferClaim? ClaimRequested(string peerId, PeerMessage.TransferOffer offer)
    {
        if (offer.RequestId is null || RequestedOffers is not { } handler) return null;
        try { return handler.TryClaim(peerId, offer); }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Requested offer claim failed: {error.GetType().Name}");
            return null;
        }
    }

    private static byte[] DecodeBase64(string? value, string field)
    {
        if (string.IsNullOrWhiteSpace(value))
            throw new InvalidDataException($"Missing {field}.");
        string normalized = value.Replace('-', '+').Replace('_', '/');
        normalized = normalized.PadRight(normalized.Length + ((4 - normalized.Length % 4) % 4), '=');
        try { return Convert.FromBase64String(normalized); }
        catch (FormatException error)
        {
            VeyroTrace.Error($"Invalid Base64 in {field} (length={value.Length})", error);
            throw new InvalidDataException($"Invalid {field} encoding.", error);
        }
    }

    private static int FindFreePort(int? excludedPort = null)
    {
        for (int port = PortRangeStart; port <= PortRangeEnd; port++)
        {
            if (port == excludedPort) continue;
            try
            {
                using var probe = new TcpListener(IPAddress.Any, port);
                probe.Start();
                return port;
            }
            catch (SocketException) { }
        }
        throw new IOException($"No Veyro control port available in {PortRangeStart}-{PortRangeEnd}.");
    }

    private static string FindAdvertisableAddress(SessionAddresses session)
    {
        var candidates = NetworkInterface.GetAllNetworkInterfaces()
            .Where(network => network.OperationalStatus == OperationalStatus.Up)
            .SelectMany(network => network.GetIPProperties().UnicastAddresses)
            .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(address => new LocalIpv4(address.Address, address.PrefixLength))
            .ToList();
        return ChooseAdvertisableAddress(session, candidates)?.ToString()
            ?? throw new InvalidOperationException("No advertisable IPv4 address.");
    }

    /// <summary>
    /// The IPv4 address to put in <c>decision.tcpHost</c>. Prefers the address the phone reached
    /// this session on (proven reachable), then one on the phone's subnet, then any usable one.
    /// A loopback peer (local tooling and tests) gets loopback. Null when nothing is usable.
    /// </summary>
    public static IPAddress? ChooseAdvertisableAddress(SessionAddresses session, IReadOnlyList<LocalIpv4> candidates)
    {
        IPAddress? remote = Ipv4(session.Remote);
        if (remote is not null && IPAddress.IsLoopback(remote)) return IPAddress.Loopback;

        var usable = candidates.Where(candidate => Ipv4(candidate.Address) is { } address &&
                                                   !IPAddress.IsLoopback(address) &&
                                                   !address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
            .Select(candidate => candidate with { Address = Ipv4(candidate.Address)! })
            .ToList();
        IPAddress? local = Ipv4(session.Local);
        if (local is not null && usable.Any(candidate => candidate.Address.Equals(local))) return local;
        if (remote is not null)
        {
            LocalIpv4? sameSubnet = usable.FirstOrDefault(candidate => SameSubnet(candidate, remote));
            if (sameSubnet is not null) return sameSubnet.Address;
        }
        return usable.FirstOrDefault()?.Address;
    }

    /// <summary>IPv4, unwrapping IPv4-mapped IPv6 (a dual-stack socket reports ::ffff:a.b.c.d).</summary>
    private static IPAddress? Ipv4(IPAddress? address) => address switch
    {
        null => null,
        { IsIPv4MappedToIPv6: true } => address.MapToIPv4(),
        { AddressFamily: AddressFamily.InterNetwork } => address,
        _ => null
    };

    private static bool SameSubnet(LocalIpv4 local, IPAddress remote)
    {
        // A missing or odd prefix falls back to /24, the common home-network size.
        int prefix = local.PrefixLength is > 0 and <= 32 ? local.PrefixLength : 24;
        uint mask = prefix == 32 ? uint.MaxValue : ~(uint.MaxValue >> prefix);
        return (ToUInt32(local.Address) & mask) == (ToUInt32(remote) & mask);
    }

    private static uint ToUInt32(IPAddress address)
    {
        byte[] bytes = address.GetAddressBytes();
        return (uint)(bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3]);
    }

    private sealed class WebSocketOwner(WebSocket socket) : IAsyncDisposable
    {
        public async ValueTask DisposeAsync()
        {
            if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                try { await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "closed", CancellationToken.None).ConfigureAwait(false); } catch { }
            socket.Dispose();
        }
    }
}
