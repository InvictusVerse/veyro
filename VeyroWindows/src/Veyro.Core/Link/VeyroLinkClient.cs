using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Veyro.Core.Transfer;

using Veyro.Core.Diagnostics;

namespace Veyro.Core.Link;

/// <summary>
/// Windows outbound transfers to already-paired peers. Pairing remains an explicit UI operation;
/// this class refuses to connect without the persisted session secret.
///
/// A transfer goes over the phone's live session when there is one (ADR-034): Android closes an
/// existing session when a second inbound one from the same peer arrives, so dialling a separate
/// connection per transfer killed the companion session. Dialling is the fallback for a phone
/// that is visible on the network but not connected.
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

    /// <summary>How long the phone has to accept or decline an offer sent over the live session.</summary>
    public TimeSpan DecisionTimeout { get; init; } = TransferMailbox.DecisionTimeout;

    /// <summary>
    /// Loopback tests only. A phone never legitimately names 127.0.0.1, and connecting there would
    /// let a peer aim the PC's file bytes at a local service, so the app leaves this false.
    /// </summary>
    public bool AllowLoopbackDataPlane { get; init; }

    /// <summary>
    /// Sends over the peer's live session if <paramref name="sessions"/> has one, otherwise dials
    /// <paramref name="discovered"/> (untrusted endpoint; the session secret authenticates it).
    /// </summary>
    public async Task<Vtc1TransferResult> SendFilesAsync(
        IPeerSessionRegistry sessions,
        PeerDevice? discovered,
        TrustedPeer trustedPeer,
        IReadOnlyList<string> filePaths,
        IProgress<long>? progress = null,
        CancellationToken ct = default)
    {
        TransferPlan plan = BuildPlan(filePaths);
        IOutboundTransferChannel? channel = null;
        if (sessions.IsConnected(trustedPeer.DeviceId))
        {
            // The session can close between the check and the registration; then dial instead.
            try { channel = sessions.OpenOutboundTransfer(trustedPeer.DeviceId, plan.TransferId); }
            catch (InvalidOperationException) { channel = null; }
        }
        if (channel is not null)
        {
            using (channel)
            {
                VeyroTrace.Info($"Outbound transfer over the live session: items={plan.Items.Count}");
                return await SendOverChannelAsync(channel, plan, progress, ct).ConfigureAwait(false);
            }
        }
        if (discovered is null)
            throw new InvalidOperationException("The phone is not connected and not visible on the network.");
        VeyroTrace.Info($"Outbound transfer over a new connection: items={plan.Items.Count}");
        return await SendOverNewConnectionAsync(discovered, trustedPeer, plan, progress, ct).ConfigureAwait(false);
    }

    /// <summary>Fallback: a separate authenticated connection that carries one transfer.</summary>
    public Task<Vtc1TransferResult> SendFilesAsync(
        PeerDevice peer,
        TrustedPeer trustedPeer,
        IReadOnlyList<string> filePaths,
        IProgress<long>? progress = null,
        CancellationToken ct = default)
        => SendOverNewConnectionAsync(peer, trustedPeer, BuildPlan(filePaths), progress, ct);

    private async Task<Vtc1TransferResult> SendOverNewConnectionAsync(
        PeerDevice peer,
        TrustedPeer trustedPeer,
        TransferPlan plan,
        IProgress<long>? progress,
        CancellationToken ct)
    {
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
        // NotificationMirror. It does advertise the companion families so the phone knows the
        // PC speaks them; any companion frame the phone sends here is skipped while waiting for
        // the decision, the phone's persistent session is where that state is handled.
        await session.SendAsync(new LinkMessage.Hello(
            _localDeviceId, _localName, "windows",
            Capabilities: LinkCapabilities.FileTransfer | LinkCapabilities.Resume |
                          LinkCapabilities.MediaControl | LinkCapabilities.DeviceStatus |
                          LinkCapabilities.Messaging | LinkCapabilities.Calls |
                          LinkCapabilities.Photos), ct).ConfigureAwait(false);

        await session.SendAsync(plan.Offer, ct).ConfigureAwait(false);

        LinkMessage.TransferDecision decision = await ReceiveDecisionAsync(session, plan.TransferId, ct).ConfigureAwait(false);
        return await RunDataPlaneAsync(plan, decision, progress, ct).ConfigureAwait(false);
    }

    private async Task<Vtc1TransferResult> SendOverChannelAsync(
        IOutboundTransferChannel channel,
        TransferPlan plan,
        IProgress<long>? progress,
        CancellationToken ct)
    {
        await channel.SendAsync(plan.Offer, ct).ConfigureAwait(false);
        LinkMessage.TransferDecision decision;
        try
        {
            decision = await channel.WaitForDecisionAsync(DecisionTimeout, ct).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            await TryCancelAsync(channel, "timeout").ConfigureAwait(false);
            throw new TimeoutException("The phone did not accept the transfer in time.");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            await TryCancelAsync(channel, "cancelled").ConfigureAwait(false);
            throw;
        }

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, channel.PeerAborted);
        try
        {
            return await RunDataPlaneAsync(plan, decision, progress, linked.Token).ConfigureAwait(false);
        }
        catch (Exception error) when (error is OperationCanceledException or IOException or System.Net.Sockets.SocketException &&
                                      channel.PeerAborted.IsCancellationRequested && !ct.IsCancellationRequested)
        {
            throw new IOException("The phone stopped the transfer.", error);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            await TryCancelAsync(channel, "cancelled").ConfigureAwait(false);
            throw;
        }
    }

    private static async Task TryCancelAsync(IOutboundTransferChannel channel, string reason)
    {
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await channel.SendAsync(new LinkMessage.Cancel(channel.TransferId, reason), timeout.Token).ConfigureAwait(false);
        }
        catch (Exception error) when (error is not OutOfMemoryException)
        {
            VeyroTrace.Warn($"Sending cancel failed: {error.GetType().Name}");
        }
    }

    private sealed record PlannedItem(int Index, string Path, string RelativePath, long Size);

    private sealed record TransferPlan(string TransferId, IReadOnlyList<PlannedItem> Items, LinkMessage.TransferOffer Offer);

    private TransferPlan BuildPlan(IReadOnlyList<string> filePaths)
    {
        if (filePaths.Count == 0) throw new ArgumentException("At least one file is required.", nameof(filePaths));
        var items = filePaths
            .Where(File.Exists)
            .Select((path, index) => new PlannedItem(
                index,
                path,
                RelativePath.SanitizeOutgoingSegment(Path.GetFileName(path)),
                new FileInfo(path).Length))
            .ToList();
        if (items.Count == 0) throw new FileNotFoundException("No selected file exists.");
        string transferId = Guid.NewGuid().ToString("N");
        var offer = new LinkMessage.TransferOffer(
            transferId,
            items.Select(item => new LinkItem(item.Index, item.RelativePath, item.Size, File.GetLastWriteTimeUtc(item.Path).Ticks, false)).ToList(),
            items.Sum(item => item.Size),
            _localName,
            // Wire name is "LAN_TCP" (04-PROTOCOL 7.1). "LanTcp".ToUpperInvariant() gave "LANTCP",
            // which Android did not recognise: it answered with a WebSocket-only decision and
            // no VTC1 endpoint ("Peer accepted without a complete VTC1 endpoint").
            [RouteWireNames.LanTcp]);
        return new TransferPlan(transferId, items, offer);
    }

    /// <summary>Validates the decision and runs VTC1 to the endpoint it names.</summary>
    private async Task<Vtc1TransferResult> RunDataPlaneAsync(
        TransferPlan plan,
        LinkMessage.TransferDecision decision,
        IProgress<long>? progress,
        CancellationToken ct)
    {
        if (!decision.Accept) throw new UnauthorizedAccessException($"Peer declined transfer: {decision.Reason ?? "no reason"}");
        if (!string.Equals(decision.Route, "LAN_TCP", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(decision.Route, TransferRoute.LanTcp.ToString(), StringComparison.OrdinalIgnoreCase))
            throw new NotSupportedException("Windows currently supports LAN_TCP for the VTC1 data plane.");
        if (decision.TcpPort is null || string.IsNullOrWhiteSpace(decision.TcpHost) || string.IsNullOrWhiteSpace(decision.AuthToken))
            throw new InvalidDataException("Peer accepted without a complete VTC1 endpoint.");
        if (decision.TcpHost is "0.0.0.0" || (decision.TcpHost is "127.0.0.1" && !AllowLoopbackDataPlane) ||
            decision.TcpPort is < 1 or > 65535)
            throw new InvalidDataException("Peer advertised an invalid VTC1 endpoint.");

        return await Vtc1BulkTransfer.SendAsync(
            decision.TcpHost,
            decision.TcpPort.Value,
            plan.TransferId,
            decision.AuthToken,
            _localDeviceId,
            plan.Items.Select(item => new OutboundTransferItem(item.Index, item.Path, item.RelativePath)).ToList(),
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
                // Android opens every session with its own Hello (not HelloAck); it arrives before
                // the decision. Treating it as a protocol error failed every PC-to-phone send.
                case LinkMessage.Hello:
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
                case LinkMessage.Cancel cancel when cancel.TransferId == transferId:
                    return new LinkMessage.TransferDecision(transferId, false, cancel.Reason);
                default:
                    // Unrelated or newer message types are not errors while waiting; the session
                    // stays authenticated and ordered. Only the type name is logged.
                    VeyroTrace.Info($"Skipping '{message.Envelope.Payload.GetType().Name}' while waiting for TransferDecision");
                    continue;
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
