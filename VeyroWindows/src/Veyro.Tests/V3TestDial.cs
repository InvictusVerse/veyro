using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Crypto;
using Veyro.Core.Link;
using Veyro.Core.Protocol;

namespace Veyro.Tests;

/// <summary>
/// A v3 dialler played by a test (ADR-058): sends the preamble with fresh cn/epk, keeps the exact
/// preamble and ack bytes, and derives this connection's keys from them like the phone does.
/// </summary>
internal sealed class V3Dialer(string localId) : IDisposable
{
    private readonly EphemeralKey _eph = EphemeralKey.Generate();

    public string LocalId { get; } = localId;
    public byte[] PreambleBytes { get; private set; } = [];
    public byte[] AckBytes { get; private set; } = [];
    public LinkPreambleAck? Ack { get; private set; }
    public byte[] Transcript => ConnectionKeySchedule.Transcript(PreambleBytes, AckBytes);

    public async Task<LinkPreambleAck> SendPreambleAsync(
        WebSocket socket, string name, CancellationToken ct,
        string platform = "android", string? pair = null, byte[]? pk = null, int protocolVersion = LinkProtocol.Version)
    {
        PreambleBytes = Encoding.UTF8.GetBytes(PeerMessageJson.Encode(new LinkPreamble(
            LocalId, name, platform, protocolVersion, pair, pk is null ? null : Convert.ToBase64String(pk),
            Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)), Convert.ToBase64String(_eph.PublicKey))));
        await socket.SendAsync(PreambleBytes, WebSocketMessageType.Text, true, ct);
        byte[] buffer = new byte[64 * 1024];
        WebSocketReceiveResult result = await socket.ReceiveAsync(buffer, ct);
        if (result.MessageType == WebSocketMessageType.Close) throw new WebSocketException("closed before the ack");
        AckBytes = buffer.AsSpan(0, result.Count).ToArray();
        Ack = PeerMessageJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(AckBytes))!;
        return Ack;
    }

    /// <summary>X25519 with the responder's ack epk.</summary>
    public byte[] Dh() => _eph.Agree(FrameCrypto.DecodeKey32(Ack!.Epk)!);

    public LinkKeys SessionKeys(string peerId, byte[] secret)
        => ConnectionKeySchedule.Session(LinkRole.Initiator, LocalId, peerId, Transcript, Dh(), secret);

    public void Dispose() => _eph.Dispose();
}

internal static class TestLinkKeys
{
    private static readonly byte[] Preamble = Encoding.UTF8.GetBytes("{\"test\":\"preamble\"}");
    private static readonly byte[] AckBytes = Encoding.UTF8.GetBytes("{\"test\":\"ack\"}");

    /// <summary>(initiator, responder) keys of one fresh connection, from the real schedule.</summary>
    public static (LinkKeys Initiator, LinkKeys Responder) SessionPair(string idI, string idR, byte[] secret)
    {
        using EphemeralKey eI = EphemeralKey.Generate();
        using EphemeralKey eR = EphemeralKey.Generate();
        byte[] transcript = ConnectionKeySchedule.Transcript(Preamble, AckBytes);
        return (ConnectionKeySchedule.Session(LinkRole.Initiator, idI, idR, transcript, eI.Agree(eR.PublicKey), secret),
                ConnectionKeySchedule.Session(LinkRole.Responder, idI, idR, transcript, eR.Agree(eI.PublicKey), secret));
    }
}

/// <summary>A key exporter over a fixed secret, for tests that drive <c>BulkTransfer</c> directly.</summary>
internal sealed class FixedExporter(byte[] expSecret)
{
    public byte[] ExportKey(string label, byte[] context, int length) => ConnectionKeySchedule.Export(expSecret, label, context, length);

    public Func<byte[], byte[], byte[]> Deriver(string transferId) => Vtc3.KeyDeriver(ExportKey, transferId);

    public static FixedExporter Default { get; } = new(Enumerable.Repeat((byte)0x5A, 32).ToArray());
}
