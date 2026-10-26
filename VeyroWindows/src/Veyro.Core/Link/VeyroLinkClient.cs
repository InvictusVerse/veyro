using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Veyro.Core.Transfer;

namespace Veyro.Core.Link;

/// <summary>
/// Windows outbound client for already-paired peers. Pairing remains an explicit UI operation;
/// this class refuses to connect without the persisted session secret.
/// </summary>
public sealed class VeyroLinkClient
{
    private const int MaxPreambleBytes = 64 * 1024;
    private readonly string _localDeviceId;
    private readonly string _localName;

    public VeyroLinkClient(string localDeviceId, string localName)
    {
        _localDeviceId = localDeviceId;
        _localName = localName;
    }

    public async Task<Vtc1TransferResult> SendFilesAsync(
        PeerDevice peer,
        TrustedPeer trustedPeer,
        IReadOnlyList<string> filePaths,
        IProgress<long>? progress = null,
        CancellationToken ct = default)
    {
        if (filePaths.Count == 0) throw new ArgumentException("At least one file is required.", nameof(filePaths));
        if (!string.Equals(peer.DeviceId, trustedPeer.DeviceId, StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException("Trusted identity does not match the selected peer.");

        byte[] secret;
        try { secret = Convert.FromBase64String(trustedPeer.PairingSecretBase64); }
        catch (FormatException ex) { throw new CryptographicException("Stored peer secret is malformed.", ex); }
        if (secret.Length != 32) throw new CryptographicException("Stored peer secret has invalid length.");

        PeerEndpoint endpoint = peer.Endpoints.FirstOrDefault()
            ?? throw new InvalidOperationException("The peer has no discovered endpoint.");
        using var socket = new ClientWebSocket { Options = { KeepAliveInterval = TimeSpan.FromSeconds(20) } };
        await socket.ConnectAsync(new Uri($"ws://{endpoint.Host}:{endpoint.Port}/veyro"), ct).ConfigureAwait(false);
        await SendTextAsync(socket, LinkJson.Encode(new LinkPreamble(_localDeviceId, _localName, "windows")), ct).ConfigureAwait(false);

        LinkPreambleAck ack = LinkJson.Decode<LinkPreambleAck>(await ReceiveTextAsync(socket, ct).ConfigureAwait(false))
            ?? throw new InvalidDataException("Peer returned an invalid VLP preamble response.");
        if (!string.Equals(ack.Mode, LinkPreambleAck.ModeSession, StringComparison.Ordinal))
            throw new UnauthorizedAccessException($"Peer refused an authenticated session: {ack.Reason ?? ack.Mode}");

        await using var session = new SecureLinkSession(socket, _localDeviceId, secret, encrypt: true, peer.DeviceId);
        // This outbound connection only carries one transfer, so it does not advertise
        // NotificationMirror. It does advertise the ADR-026/027 families so the phone knows the
        // PC speaks them; any companion frame the phone sends here is skipped while waiting for
        // the decision, the phone's persistent session is where that state is handled.
        await session.SendAsync(new LinkMessage.Hello(
            _localDeviceId, _localName, "windows",
            Capabilities: LinkCapabilities.FileTransfer | LinkCapabilities.Resume |
                          LinkCapabilities.MediaControl | LinkCapabilities.DeviceStatus |
                          LinkCapabilities.Messaging | LinkCapabilities.Calls), ct).ConfigureAwait(false);

        var items = filePaths
            .Where(File.Exists)
            .Select((path, index) => new
            {
                Index = index,
                Path = path,
                RelativePath = RelativePath.SanitizeOutgoingSegment(Path.GetFileName(path)),
                Size = new FileInfo(path).Length
            })
            .ToList();
        if (items.Count == 0) throw new FileNotFoundException("No selected file exists.");
        string transferId = Guid.NewGuid().ToString("N");
        long totalBytes = items.Sum(item => item.Size);
        await session.SendAsync(new LinkMessage.TransferOffer(
            transferId,
            items.Select(item => new LinkItem(item.Index, item.RelativePath, item.Size, File.GetLastWriteTimeUtc(item.Path).Ticks, false)).ToList(),
            totalBytes,
            _localName,
            [TransferRoute.LanTcp.ToString().ToUpperInvariant()]), ct).ConfigureAwait(false);

        LinkMessage.TransferDecision decision = await ReceiveDecisionAsync(session, transferId, ct).ConfigureAwait(false);
        if (!decision.Accept) throw new UnauthorizedAccessException($"Peer declined transfer: {decision.Reason ?? "no reason"}");
        if (!string.Equals(decision.Route, "LAN_TCP", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(decision.Route, TransferRoute.LanTcp.ToString(), StringComparison.OrdinalIgnoreCase))
            throw new NotSupportedException("Windows currently supports LAN_TCP for the VTC1 data plane.");
        if (decision.TcpPort is null || string.IsNullOrWhiteSpace(decision.TcpHost) || string.IsNullOrWhiteSpace(decision.AuthToken))
            throw new InvalidDataException("Peer accepted without a complete VTC1 endpoint.");
        if (decision.TcpHost is "0.0.0.0" or "127.0.0.1" || decision.TcpPort is < 1 or > 65535)
            throw new InvalidDataException("Peer advertised an invalid VTC1 endpoint.");

        return await Vtc1BulkTransfer.SendAsync(
            decision.TcpHost,
            decision.TcpPort.Value,
            transferId,
            decision.AuthToken,
            _localDeviceId,
            items.Select(item => new OutboundTransferItem(item.Index, item.Path, item.RelativePath)).ToList(),
            resume: decision.Resume,
            progress: progress,
            ct: ct).ConfigureAwait(false);
    }

    /// <summary>The peer may answer Hello with HelloAck and heartbeat before deciding.</summary>
    private static async Task<LinkMessage.TransferDecision> ReceiveDecisionAsync(
        SecureLinkSession session,
        string transferId,
        CancellationToken ct)
    {
        while (true)
        {
            SecureLinkMessage message = await session.ReceiveAsync(ct).ConfigureAwait(false)
                ?? throw new InvalidDataException("Peer closed before returning TransferDecision.");
            switch (message.Envelope.Payload)
            {
                case LinkMessage.HelloAck:
                case LinkMessage.Pong:
                case LinkMessage.MediaState:
                case LinkMessage.MediaCommand:
                case LinkMessage.DeviceStatus:
                case LinkMessage.Notification:
                case LinkMessage.SimList:
                case LinkMessage.SmsThreads:
                case LinkMessage.SmsMessages:
                case LinkMessage.SmsNew:
                case LinkMessage.SmsSendResult:
                case LinkMessage.CallState:
                case LinkMessage.CallLog:
                    continue;
                case LinkMessage.Ping ping:
                    await session.SendAsync(new LinkMessage.Pong(ping.Nonce), ct).ConfigureAwait(false);
                    continue;
                case LinkMessage.TransferDecision decision when decision.TransferId == transferId:
                    return decision;
                default:
                    throw new InvalidDataException("Expected a matching TransferDecision.");
            }
        }
    }

    private static async Task SendTextAsync(ClientWebSocket socket, string text, CancellationToken ct)
        => await socket.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, ct).ConfigureAwait(false);

    private static async Task<string> ReceiveTextAsync(ClientWebSocket socket, CancellationToken ct)
    {
        byte[] buffer = new byte[MaxPreambleBytes];
        WebSocketReceiveResult result = await socket.ReceiveAsync(buffer, ct).ConfigureAwait(false);
        if (result.MessageType != WebSocketMessageType.Text || !result.EndOfMessage || result.Count > MaxPreambleBytes)
            throw new InvalidDataException("Invalid VLP preamble frame.");
        return Encoding.UTF8.GetString(buffer, 0, result.Count);
    }
}