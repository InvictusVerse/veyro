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

public sealed record InboundOfferRequest(
    TrustedPeer Peer,
    LinkMessage.TransferOffer Offer);

/// <summary>
/// An unknown phone asks to pair. For confirm pairing (04-PROTOCOL.md 4.3) <see cref="Code"/> is
/// the 6-digit code the phone shows too; the user accepts only if they match. The prompt must close
/// itself when <see cref="Abandoned"/> fires (the phone cancelled or left) and declines at
/// <see cref="ExpiresAt"/>. The legacy PIN flow (tests only) passes the PIN as the code.
/// </summary>
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
/// Windows listener for authenticated inbound sessions. An unknown peer pairs either through the
/// open <see cref="Link.PairingWindow"/> (ADR-030) or, when none is open, only after the
/// <see cref="PairingAuthorizer"/> prompt approves it.
/// </summary>
public sealed class VeyroLinkServer : IAsyncDisposable
{
    public const int PortRangeStart = 5150;
    public const int PortRangeEnd = 5169;
    private readonly string _localDeviceId;
    private readonly string _localName;
    private readonly ITrustStore _trustStore;
    private readonly string _destinationRoot;
    private readonly byte[] _localPublicKey;
    private readonly Func<byte[], byte[], byte[], byte[]>? _deriveSharedKey;
    private readonly CancellationTokenSource _stop = new();
    private readonly PeerSessionRegistry _sessions;
    private WebApplication? _app;

    /// <summary>What this listener's sessions support, sent in HelloAck.</summary>
    public const int LocalCapabilities =
        LinkCapabilities.FileTransfer | LinkCapabilities.Resume | LinkCapabilities.NotificationMirror |
        LinkCapabilities.MediaControl | LinkCapabilities.DeviceStatus |
        LinkCapabilities.Messaging | LinkCapabilities.Calls | LinkCapabilities.Photos;

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
    /// How long a device pairing through the open window has to send a PairHello that verifies.
    /// The user may still be typing the code on the phone, so this is generous. Exceeding it
    /// counts as a failed attempt (04-PROTOCOL.md 4.2).
    /// </summary>
    public TimeSpan WindowPairHelloTimeout { get; set; } = TimeSpan.FromMinutes(2);

    public VeyroLinkServer(
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

    /// <summary>Live authenticated sessions, for companion features (ADR-025).</summary>
    public IPeerSessionRegistry Sessions => _sessions;

    public async Task StartAsync()
    {
        if (_app is not null) return;
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
        if (_app is not null) await _app.StopAsync().ConfigureAwait(false);
        _app = null;
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
            await HandleAsync(socket, context.Connection.RemoteIpAddress, _stop.Token).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            VeyroTrace.Error($"WebSocket upgrade failed from {context.Connection.RemoteIpAddress}; path={context.Request.Path}", error);
            if (!context.Response.HasStarted) context.Response.StatusCode = StatusCodes.Status500InternalServerError;
        }
    }

    private async Task HandleAsync(WebSocket socket, IPAddress? remoteAddress, CancellationToken ct)
    {
        await using var sessionOwner = new WebSocketOwner(socket);
        try
        {
            LinkPreamble? preamble = LinkJson.Decode<LinkPreamble>(await ReceiveTextAsync(socket, ct).ConfigureAwait(false));
            VeyroTrace.Info($"Received preamble: id={preamble?.Id ?? "<null>"}, platform={preamble?.Platform ?? "<null>"}, version={preamble?.ProtocolVersion.ToString() ?? "<null>"}");
            if (preamble is not null && preamble.ProtocolVersion != LinkProtocol.Version)
            {
                // Version 1 predates confirm pairing (ADR-031); refuse rather than guess.
                await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "unsupported-protocol-version")), ct).ConfigureAwait(false);
                return;
            }
            if (preamble is null ||
                DeviceId.ParseOrNull(preamble.Id) is null ||
                string.Equals(preamble.Id, _localDeviceId, StringComparison.OrdinalIgnoreCase))
            {
                await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "invalid-identity")), ct).ConfigureAwait(false);
                return;
            }

            TrustedPeer? trusted = _trustStore.GetPeer(preamble.Id);
            if (trusted is null && preamble.Pair == LinkPreamble.PairConfirm)
            {
                // ADR-031: compare a code on both screens, accept here. Nothing is typed.
                await RunConfirmPairingAsync(socket, preamble, remoteAddress, ct).ConfigureAwait(false);
                return;
            }
            if (trusted is null && preamble.Pair is not null and not LinkPreamble.PairQr)
            {
                await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "unsupported-pairing")), ct).ConfigureAwait(false);
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
                    await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "no-pairing-window")), ct).ConfigureAwait(false);
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
                        await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "declined")), ct).ConfigureAwait(false);
                        return;
                    }
                }
            }

            byte[] secret = pairing
                ? FrameCrypto.DeriveBootstrapKey(pairingPin!, preamble.Id, _localDeviceId)
                : DecodeBase64(trusted!.PairingSecretBase64, "stored pairing secret");
            if (secret.Length != 32)
            {
                await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "invalid-trust-record")), ct).ConfigureAwait(false);
                return;
            }

            await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(
                pairing ? LinkPreambleAck.ModePairing : LinkPreambleAck.ModeSession)), ct).ConfigureAwait(false);
            await using var secure = new SecureLinkSession(socket, _localDeviceId, secret, encrypt: !pairing, preamble.Id);
            if (pairing)
            {
                SecureLinkMessage pairMessage = (windowAttempt is null
                    ? await secure.ReceiveAsync(ct).ConfigureAwait(false)
                    : await ReceiveWindowPairHelloAsync(secure, windowAttempt, ct).ConfigureAwait(false))
                    ?? throw new InvalidDataException("Peer closed before PairHello.");
                if (pairMessage.Envelope.Payload is not LinkMessage.PairHello pairHello ||
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
                await secure.SendSealedAsync(new LinkMessage.PairOk(
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
            await RunSessionAsync(secure, pairedPeer, remoteAddress, ct).ConfigureAwait(false);
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
    private async Task RunConfirmPairingAsync(WebSocket socket, LinkPreamble preamble, IPAddress? remoteAddress, CancellationToken ct)
    {
        byte[]? pkA = TryDecodeKey(preamble.Pk);
        DeviceId? claimed = DeviceId.ParseOrNull(preamble.Id);
        if (_deriveSharedKey is null || pkA is null || claimed is null || !DeviceId.Matches(claimed.Value, pkA))
        {
            string reason = _deriveSharedKey is null ? "pairing-unavailable" : "identity-mismatch";
            VeyroTrace.Warn($"Confirm pairing from {preamble.Id} refused: {reason}");
            await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, reason)), ct).ConfigureAwait(false);
            return;
        }
        string idA = claimed.Value.Value;

        byte[] nB = PairConfirm.NewNonce();
        await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(
            LinkPreambleAck.ModePairingConfirm,
            Pk: Convert.ToBase64String(_localPublicKey),
            Commit: Convert.ToBase64String(PairConfirm.Commitment(_localPublicKey, pkA, nB)))), ct).ConfigureAwait(false);

        byte[] nA = await ReceivePairNonceAsync(socket, ct).ConfigureAwait(false);
        await SendTextAsync(socket, LinkJson.Encode(LinkPairNonce.For(nB)), ct).ConfigureAwait(false);

        string code = PairConfirm.Code(pkA, _localPublicKey, nA, nB);
        byte[] key = _deriveSharedKey(pkA, PairConfirm.BootstrapSalt(nA, nB), PairConfirm.BootstrapInfo(idA, _localDeviceId));
        await using var secure = new SecureLinkSession(socket, _localDeviceId, key, encrypt: false, preamble.Id);
        CryptographicOperations.ZeroMemory(key);

        // A pairHello that verifies under K proves the phone holds the private key behind pkA.
        LinkMessage.PairHello hello = await ReceiveConfirmPairHelloAsync(secure, ct).ConfigureAwait(false);
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
            if (pending.IsCompletedSuccessfully && pending.Result?.Envelope.Payload is LinkMessage.Ping ping)
            {
                await secure.SendAsync(new LinkMessage.Pong(ping.Nonce), ct).ConfigureAwait(false);
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
                try { await secure.SendAsync(new LinkMessage.PairFail(reply), ct).ConfigureAwait(false); }
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
        await secure.SendSealedAsync(new LinkMessage.PairOk(
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
        await RunSessionAsync(secure, trusted, remoteAddress, ct, pending).ConfigureAwait(false);
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
        LinkPairNonce? message = LinkJson.Decode<LinkPairNonce>(await ReceiveTextAsync(socket, timeout.Token).ConfigureAwait(false));
        if (message is null || message.T != LinkPairNonce.Type)
            throw new InvalidDataException("Expected pairNonce.");
        byte[] nonce = DecodeBase64(message.N, "pairNonce");
        if (nonce.Length != PairConfirm.NonceSize)
            throw new InvalidDataException("pairNonce has the wrong length.");
        return nonce;
    }

    private async Task<LinkMessage.PairHello> ReceiveConfirmPairHelloAsync(SecureLinkSession secure, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(ConfirmHandshakeTimeout);
        SecureLinkMessage message = await secure.ReceiveAsync(timeout.Token).ConfigureAwait(false)
            ?? throw new InvalidDataException("Peer closed before PairHello.");
        return message.Envelope.Payload as LinkMessage.PairHello
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
    /// The persistent authenticated session (ADR-025). Runs until the peer closes the socket or
    /// the server stops. Transfers run one at a time in the background so heartbeats and
    /// companion messages keep flowing while a VTC1 transfer is in progress.
    /// </summary>
    private async Task RunSessionAsync(
        SecureLinkSession secure,
        TrustedPeer peer,
        IPAddress? remoteAddress,
        CancellationToken ct,
        Task<SecureLinkMessage?>? pendingReceive = null)
    {
        using PeerSessionRegistry.Registration registration = _sessions.Open(peer.DeviceId, secure.SendAsync);
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
                    Task<SecureLinkMessage?> receive = pendingReceive ?? secure.ReceiveAsync(ct);
                    pendingReceive = null;
                    message = await receive.ConfigureAwait(false);
                }
                catch (Exception error) when (error is WebSocketException or IOException && !ct.IsCancellationRequested)
                {
                    VeyroTrace.Info($"Session closed by peer {peer.DeviceId}");
                    break;
                }
                if (message is null) break;

                // ADR-034: replies to a PC-to-phone transfer sent over this session go to its sender.
                if (registration.Transfers.TryDeliver(message.Envelope.Payload)) continue;

                switch (message.Envelope.Payload)
                {
                    case LinkMessage.Hello hello:
                        // Answer first: setting the capabilities lets companion services send
                        // their on-connect snapshots, which must not overtake the HelloAck.
                        await secure.SendAsync(new LinkMessage.HelloAck(
                            _localDeviceId, _localName, "windows", LocalCapabilities), ct).ConfigureAwait(false);
                        registration.Capabilities = hello.Capabilities;
                        break;
                    case LinkMessage.HelloAck helloAck:
                        registration.Capabilities = helloAck.Capabilities;
                        break;
                    case LinkMessage.Ping ping:
                        await secure.SendAsync(new LinkMessage.Pong(ping.Nonce), ct).ConfigureAwait(false);
                        break;
                    case LinkMessage.Pong:
                        break;
                    case LinkMessage.TransferOffer offer when ClaimRequested(peer.DeviceId, offer) is { } claim:
                    {
                        foreach (string done in requested.Where(entry => entry.Value.Task.IsCompleted).Select(entry => entry.Key).ToList())
                        {
                            requested[done].Cancellation.Dispose();
                            requested.Remove(done);
                        }
                        if (requested.ContainsKey(offer.TransferId))
                        {
                            await secure.SendAsync(new LinkMessage.TransferDecision(offer.TransferId, false, "busy"), ct).ConfigureAwait(false);
                            RequestedOffers?.OnFailed(peer.DeviceId, offer.RequestId!, new InvalidDataException("Duplicate transfer id."));
                            break;
                        }
                        var cancellation = CancellationTokenSource.CreateLinkedTokenSource(ct);
                        requested[offer.TransferId] = (
                            HandleOfferAsync(secure, peer, offer, remoteAddress, claim, cancellation.Token),
                            cancellation);
                        break;
                    }
                    case LinkMessage.TransferOffer offer:
                        if (activeTransfer is { IsCompleted: false })
                        {
                            await secure.SendAsync(new LinkMessage.TransferDecision(offer.TransferId, false, "busy"), ct).ConfigureAwait(false);
                            break;
                        }
                        transferCancellation?.Dispose();
                        transferCancellation = CancellationTokenSource.CreateLinkedTokenSource(ct);
                        activeTransferId = offer.TransferId;
                        activeTransfer = HandleOfferAsync(secure, peer, offer, remoteAddress, claim: null, transferCancellation.Token);
                        break;
                    case LinkMessage.Cancel cancel:
                        if (activeTransfer is { IsCompleted: false } && cancel.TransferId == activeTransferId)
                            transferCancellation!.Cancel();
                        else if (requested.TryGetValue(cancel.TransferId, out var running) && !running.Task.IsCompleted)
                            running.Cancellation.Cancel();
                        break;
                    // ADR-034. File names and images are user data: never logged.
                    case LinkMessage.Photos or LinkMessage.PhotoThumb or LinkMessage.PhotoNew or LinkMessage.PhotoRemoved:
                        registration.Capabilities |= LinkCapabilities.Photos;
                        _sessions.PublishPhotos(peer.DeviceId, message.Envelope.Payload);
                        break;
                    case LinkMessage.Notification notification:
                        // A peer that sends notif speaks the notification family even if this
                        // session carried no Hello (e.g. straight after pairing). Contents are
                        // user data: never logged (04-PROTOCOL.md 6.1).
                        registration.Capabilities |= LinkCapabilities.NotificationMirror;
                        _sessions.PublishNotification(peer.DeviceId, notification);
                        break;
                    // ADR-026. Same inference as notif: a peer that sends the family speaks it.
                    // Media metadata is user data: never logged (04-PROTOCOL.md 6.2).
                    case LinkMessage.MediaState mediaState:
                        registration.Capabilities |= LinkCapabilities.MediaControl;
                        _sessions.PublishMediaState(peer.DeviceId, mediaState);
                        break;
                    case LinkMessage.MediaCommand mediaCommand:
                        registration.Capabilities |= LinkCapabilities.MediaControl;
                        _sessions.PublishMediaCommand(peer.DeviceId, mediaCommand);
                        break;
                    case LinkMessage.DeviceStatus deviceStatus:
                        registration.Capabilities |= LinkCapabilities.DeviceStatus;
                        _sessions.PublishDeviceStatus(peer.DeviceId, deviceStatus);
                        break;
                    // ADR-027, same inference. Bodies, numbers and names are never logged.
                    case LinkMessage.SimList or LinkMessage.SmsThreads or LinkMessage.SmsMessages or
                         LinkMessage.SmsNew or LinkMessage.SmsSendResult:
                        registration.Capabilities |= LinkCapabilities.Messaging;
                        _sessions.PublishMessagingOrCall(peer.DeviceId, message.Envelope.Payload);
                        break;
                    case LinkMessage.CallState or LinkMessage.CallLog:
                        registration.Capabilities |= LinkCapabilities.Calls;
                        _sessions.PublishMessagingOrCall(peer.DeviceId, message.Envelope.Payload);
                        break;
                    default:
                        VeyroTrace.Info($"Ignoring {message.Envelope.Payload.GetType().Name} in session");
                        break;
                }
            }
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
        LinkMessage.TransferOffer offer,
        IPAddress? remoteAddress,
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
            bool allowed = peer is not null &&
                (claim is not null || peer.AutoAcceptTransfers ||
                 await OfferAuthorizer(new InboundOfferRequest(peer, offer)).ConfigureAwait(false));
            if (!allowed)
            {
                decided = true;
                await secure.SendAsync(new LinkMessage.TransferDecision(offer.TransferId, false, "declined"), ct).ConfigureAwait(false);
                if (claim is not null) RequestedOffers?.OnFailed(sessionPeer.DeviceId, offer.RequestId!, new UnauthorizedAccessException("The peer is no longer trusted."));
                return;
            }

            var inboundItems = offer.Items
                .Where(item => !item.IsDir)
                .Select(item => new InboundTransferItem(item.Index, item.RelPath, item.Size))
                .ToList();
            Directory.CreateDirectory(destinationRoot);
            var resume = Vtc1BulkTransfer.BuildResume(destinationRoot, inboundItems);
            Vtc1TransferResult result;

            using var tcp = new TcpListener(IPAddress.Any, FindFreePort(Port));
            tcp.Start();
            try
            {
                string host = FindAdvertisableAddress(remoteAddress);
                string token = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
                decided = true;
                await secure.SendAsync(new LinkMessage.TransferDecision(
                    offer.TransferId,
                    true,
                    Route: "LAN_TCP",
                    TcpHost: host,
                    TcpPort: ((IPEndPoint)tcp.LocalEndpoint).Port,
                    FallbackHost: host,
                    AuthToken: token,
                    Resume: resume), ct).ConfigureAwait(false);
                result = await Vtc1BulkTransfer.ReceiveAsync(
                    tcp,
                    offer.TransferId,
                    token,
                    sessionPeer.DeviceId,
                    destinationRoot,
                    inboundItems,
                    ct: ct).ConfigureAwait(false);
            }
            finally
            {
                tcp.Stop();
            }
            await secure.SendAsync(new LinkMessage.TransferEnd(offer.TransferId, true), ct).ConfigureAwait(false);
            if (claim is not null)
            {
                // Vtc1BulkTransfer validated every path against the root before writing it.
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
                LinkMessage failure = decided
                    ? new LinkMessage.TransferEnd(offer.TransferId, false)
                    : new LinkMessage.TransferDecision(offer.TransferId, false, "error");
                await secure.SendAsync(failure, CancellationToken.None).ConfigureAwait(false);
            }
            catch { }
        }
    }

    /// <summary>ADR-034: an offer carrying a requestId the handler issued. Others take the consent path.</summary>
    private RequestedOfferClaim? ClaimRequested(string peerId, LinkMessage.TransferOffer offer)
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

    private static string FindAdvertisableAddress(IPAddress? remote)
    {
        // A loopback peer (local tooling and tests) can only reach us on loopback.
        if (remote is not null && IPAddress.IsLoopback(remote)) return IPAddress.Loopback.ToString();

        var addresses = NetworkInterface.GetAllNetworkInterfaces()
            .Where(network => network.OperationalStatus == OperationalStatus.Up)
            .SelectMany(network => network.GetIPProperties().UnicastAddresses)
            .Select(address => address.Address)
            .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address) && !address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
            .ToList();
        if (remote is not null)
        {
            byte[] remoteBytes = remote.GetAddressBytes();
            var sameSubnet = addresses.FirstOrDefault(address =>
            {
                byte[] localBytes = address.GetAddressBytes();
                return localBytes.Length == 4 && remoteBytes.Length == 4 && localBytes[0] == remoteBytes[0] && localBytes[1] == remoteBytes[1] && localBytes[2] == remoteBytes[2];
            });
            if (sameSubnet is not null) return sameSubnet.ToString();
        }
        return addresses.FirstOrDefault()?.ToString() ?? throw new InvalidOperationException("No advertisable IPv4 address.");
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