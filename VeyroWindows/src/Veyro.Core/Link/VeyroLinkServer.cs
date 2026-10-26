using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
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

public sealed record InboundPairingRequest(
    string PeerDeviceId,
    string PeerName,
    string Pin);

/// <summary>
/// Windows listener for authenticated, already-paired inbound sessions.
/// Unknown peers are refused; pairing remains a separate explicit workflow.
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
    private readonly CancellationTokenSource _stop = new();
    private WebApplication? _app;

    public Func<InboundPairingRequest, Task<bool>> PairingAuthorizer { get; set; } = _ => Task.FromResult(false);
    public Func<InboundOfferRequest, Task<bool>> OfferAuthorizer { get; set; } = _ => Task.FromResult(false);

    public VeyroLinkServer(
        string localDeviceId,
        string localName,
        ITrustStore trustStore,
        string destinationRoot,
        ReadOnlySpan<byte> localPublicKey,
        Func<InboundOfferRequest, Task<bool>> authorize,
        int port = 0)
    {
        _localDeviceId = localDeviceId;
        _localName = localName;
        _trustStore = trustStore;
        _destinationRoot = destinationRoot;
        _localPublicKey = localPublicKey.ToArray();
        OfferAuthorizer = authorize;
        Port = port == 0 ? FindFreePort() : port;
    }

    public int Port { get; }
    public bool IsRunning => _app is not null;

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
        app.UseWebSockets();
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
            if (preamble is null || preamble.ProtocolVersion != LinkProtocol.Version ||
                DeviceId.ParseOrNull(preamble.Id) is null ||
                string.Equals(preamble.Id, _localDeviceId, StringComparison.OrdinalIgnoreCase))
            {
                await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "invalid-identity")), ct).ConfigureAwait(false);
                return;
            }

            TrustedPeer? trusted = _trustStore.GetPeer(preamble.Id);
            bool pairing = trusted is null;
            string? pairingPin = null;
            if (trusted is null)
            {
                pairingPin = FrameCrypto.NewPin();
                bool approved = await PairingAuthorizer(new InboundPairingRequest(preamble.Id, preamble.Name, pairingPin)).ConfigureAwait(false);
                if (!approved)
                {
                    await SendTextAsync(socket, LinkJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeRefused, "declined")), ct).ConfigureAwait(false);
                    return;
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
                SecureLinkMessage pairMessage = await secure.ReceiveAsync(ct).ConfigureAwait(false)
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
            }
            // Android's session heartbeat can arrive before the user selects files. Handle Ping
            // frames while waiting; pairing and already-paired sessions may also differ by Hello.
            LinkMessage.TransferOffer offer = await ReceiveOfferAsync(secure, ct).ConfigureAwait(false);

            TrustedPeer pairedPeer = trusted ?? throw new UnauthorizedAccessException("Pairing did not establish trust.");
            bool allowed = pairedPeer.AutoAcceptTransfers || await OfferAuthorizer(new InboundOfferRequest(pairedPeer, offer)).ConfigureAwait(false);
            if (!allowed)
            {
                await secure.SendAsync(new LinkMessage.TransferDecision(offer.TransferId, false, "declined"), ct).ConfigureAwait(false);
                return;
            }

            var inboundItems = offer.Items
                .Where(item => !item.IsDir)
                .Select(item => new InboundTransferItem(item.Index, item.RelPath, item.Size))
                .ToList();
            Directory.CreateDirectory(_destinationRoot);
            var resume = Vtc1BulkTransfer.BuildResume(_destinationRoot, inboundItems);

            using var tcp = new TcpListener(IPAddress.Any, FindFreePort(Port));
            tcp.Start();
            string host = FindAdvertisableAddress(remoteAddress);
            string token = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
            await secure.SendAsync(new LinkMessage.TransferDecision(
                offer.TransferId,
                true,
                Route: "LAN_TCP",
                TcpHost: host,
                TcpPort: ((IPEndPoint)tcp.LocalEndpoint).Port,
                FallbackHost: host,
                AuthToken: token,
                Resume: resume), ct).ConfigureAwait(false);
            Vtc1TransferResult result = await Vtc1BulkTransfer.ReceiveAsync(
                tcp,
                offer.TransferId,
                token,
                preamble.Id,
                _destinationRoot,
                inboundItems,
                ct: ct).ConfigureAwait(false);
            await secure.SendAsync(new LinkMessage.TransferEnd(offer.TransferId, true), ct).ConfigureAwait(false);
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

    private static async Task<LinkMessage.TransferOffer> ReceiveOfferAsync(
        SecureLinkSession secure,
        CancellationToken ct)
    {
        bool helloReceived = false;
        while (true)
        {
            SecureLinkMessage message = await secure.ReceiveAsync(ct).ConfigureAwait(false)
                ?? throw new InvalidDataException("Peer closed before TransferOffer.");
            switch (message.Envelope.Payload)
            {
                case LinkMessage.Ping ping:
                    await secure.SendAsync(new LinkMessage.Pong(ping.Nonce), ct).ConfigureAwait(false);
                    continue;
                case LinkMessage.Pong:
                    continue;
                case LinkMessage.Hello:
                    helloReceived = true;
                    continue;
                case LinkMessage.TransferOffer offer:
                    return offer;
                case LinkMessage.Cancel cancel:
                    throw new OperationCanceledException($"Peer cancelled before transfer: {cancel.Reason}", ct);
                default:
                    throw new InvalidDataException(helloReceived
                        ? "Expected TransferOffer after Hello."
                        : "Expected Hello, heartbeat, or TransferOffer.");
            }
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