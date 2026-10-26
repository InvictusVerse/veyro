using System.Buffers.Binary;
using System.Text;
using Veyro.Core.Models;

namespace Veyro.Core.Protocol;

/// <summary>
/// Binary encoder/decoder for the VTC1 raw TCP transport stream.
/// Mirrors com.veyro.core.protocol.tcp.TcpFrameCodec byte-for-byte.
///
/// Wire header layout:
///   4 bytes  magic "VTC1" (0x56 0x54 0x43 0x31)
///   1 byte   frame type (u8)
///   2 bytes  transferId length in bytes (u16 BE)
///   N bytes  transferId (UTF-8)
///   8 bytes  monotonic sequence number (u64 BE)
///   4 bytes  payload length in bytes (u32 BE)
///   M bytes  payload
/// </summary>
public static class TcpFrameCodec
{
    public static ReadOnlySpan<byte> Magic => "VTC1"u8;

    public const int MaxPayloadSize = 16 * 1024 * 1024;
    public const int MaxTransferIdLength = 256;

    public const byte TypeAuth = 0x01;
    public const byte TypeAuthAck = 0x02;
    public const byte TypeItemBegin = 0x03;
    public const byte TypeChunk = 0x04;
    public const byte TypeItemEnd = 0x05;
    public const byte TypeItemResult = 0x06;
    public const byte TypeTransferEnd = 0x07;
    public const byte TypeCancel = 0x08;

    private static byte FrameType(TcpFrame frame) => frame switch
    {
        TcpFrame.Auth => TypeAuth,
        TcpFrame.AuthAck => TypeAuthAck,
        TcpFrame.ItemBegin => TypeItemBegin,
        TcpFrame.Chunk => TypeChunk,
        TcpFrame.ItemEnd => TypeItemEnd,
        TcpFrame.ItemResult => TypeItemResult,
        TcpFrame.TransferEnd => TypeTransferEnd,
        TcpFrame.Cancel => TypeCancel,
        _ => throw new NotSupportedException($"Unsupported frame: {frame.GetType().Name}")
    };

    /// <summary>
    /// Writes one frame. Chunk frames do NOT auto-flush by default, matching the Kotlin
    /// default of <c>autoFlush = (frame !is TcpFrame.Chunk)</c>; the caller controls the
    /// flush cadence for bulk data.
    /// </summary>
    public static async Task WriteFrameAsync(
        Stream output, TcpFrame frame, bool? autoFlush = null, CancellationToken ct = default)
    {
        byte[] transferIdBytes = Encoding.UTF8.GetBytes(frame.TransferId);
        if (transferIdBytes.Length is 0 or > MaxTransferIdLength)
            throw new ArgumentException(
                $"Transfer ID must be 1..{MaxTransferIdLength} UTF-8 bytes.", nameof(frame));

        bool flush = autoFlush ?? frame is not TcpFrame.Chunk;
        byte type = FrameType(frame);
        byte[] payload = EncodePayload(frame);

        if (payload.Length > MaxPayloadSize)
            throw new InvalidOperationException($"Payload {payload.Length} exceeds {MaxPayloadSize}.");

        int headerLen = 4 + 1 + 2 + transferIdBytes.Length + 8 + 4;
        byte[] header = new byte[headerLen];
        Magic.CopyTo(header.AsSpan(0, 4));
        header[4] = type;
        BinaryPrimitives.WriteUInt16BigEndian(header.AsSpan(5, 2), (ushort)transferIdBytes.Length);
        transferIdBytes.CopyTo(header.AsSpan(7));

        int off = 7 + transferIdBytes.Length;
        BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(off, 8), frame.Seq);
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(off + 8, 4), payload.Length);

        await output.WriteAsync(header, ct).ConfigureAwait(false);
        if (payload.Length > 0)
            await output.WriteAsync(payload, ct).ConfigureAwait(false);
        if (flush)
            await output.FlushAsync(ct).ConfigureAwait(false);
    }

    public static async Task<byte[]> EncodeAsync(TcpFrame frame, CancellationToken ct = default)
    {
        using var ms = new MemoryStream();
        await WriteFrameAsync(ms, frame, autoFlush: false, ct).ConfigureAwait(false);
        return ms.ToArray();
    }

    /// <summary>
    /// Reads the next frame, or null on a clean EOF at a frame boundary (0 bytes read).
    /// Throws <see cref="EndOfStreamException"/> if the stream closes mid-frame.
    /// </summary>
    public static async Task<TcpFrame?> ReadFrameOrNullAsync(Stream input, CancellationToken ct = default)
    {
        byte[] prefix = new byte[7];
        int first = await input.ReadAsync(prefix.AsMemory(0, 1), ct).ConfigureAwait(false);
        if (first == 0)
            return null; // clean EOF at frame boundary

        await ReadExactAsync(input, prefix.AsMemory(1, 6), ct).ConfigureAwait(false);

        if (!prefix.AsSpan(0, 4).SequenceEqual(Magic))
            throw new InvalidDataException("Invalid TCP frame magic header: expected 'VTC1'");

        byte type = prefix[4];
        int transferIdLen = BinaryPrimitives.ReadUInt16BigEndian(prefix.AsSpan(5, 2));
        if (transferIdLen <= 0 || transferIdLen > MaxTransferIdLength)
            throw new InvalidDataException($"Invalid transfer ID length: {transferIdLen}");

        byte[] transferIdBytes = new byte[transferIdLen];
        await ReadExactAsync(input, transferIdBytes, ct).ConfigureAwait(false);
        string transferId = Encoding.UTF8.GetString(transferIdBytes);

        byte[] suffix = new byte[12];
        await ReadExactAsync(input, suffix, ct).ConfigureAwait(false);
        long seq = BinaryPrimitives.ReadInt64BigEndian(suffix.AsSpan(0, 8));
        int payloadLen = BinaryPrimitives.ReadInt32BigEndian(suffix.AsSpan(8, 4));

        if (payloadLen < 0 || payloadLen > MaxPayloadSize)
            throw new InvalidDataException($"Invalid payload length: {payloadLen} (bounds: 0..{MaxPayloadSize})");

        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0)
            await ReadExactAsync(input, payload, ct).ConfigureAwait(false);

        return DecodePayload(type, seq, transferId, payload);
    }

    private static byte[] EncodePayload(TcpFrame frame)
    {
        switch (frame)
        {
            case TcpFrame.Auth f:
            {
                byte[] token = Encoding.UTF8.GetBytes(f.AuthToken);
                byte[] devId = Encoding.UTF8.GetBytes(f.SenderDeviceId);
                byte route = f.Route == TransferRoute.WifiDirect ? (byte)0x01 : (byte)0x02;
                byte[] o = new byte[2 + token.Length + 2 + devId.Length + 1];
                int p = 0;
                BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(p, 2), (ushort)token.Length); p += 2;
                token.CopyTo(o.AsSpan(p)); p += token.Length;
                BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(p, 2), (ushort)devId.Length); p += 2;
                devId.CopyTo(o.AsSpan(p)); p += devId.Length;
                o[p] = route;
                return o;
            }
            case TcpFrame.AuthAck f:
            {
                byte[] reason = f.Reason is null ? [] : Encoding.UTF8.GetBytes(f.Reason);
                byte[] o = new byte[1 + 2 + reason.Length];
                o[0] = f.Ok ? (byte)0x01 : (byte)0x00;
                BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(1, 2), (ushort)reason.Length);
                reason.CopyTo(o.AsSpan(3));
                return o;
            }
            case TcpFrame.ItemBegin f:
            {
                byte[] o = new byte[4 + 8 + 4 + 4];
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(0, 4), f.ItemIndex);
                BinaryPrimitives.WriteInt64BigEndian(o.AsSpan(4, 8), f.SizeBytes);
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(12, 4), f.ChunkCount);
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(16, 4), f.ChunkSize);
                return o;
            }
            case TcpFrame.Chunk f:
            {
                byte[] o = new byte[4 + 4 + 4 + f.Data.Length];
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(0, 4), f.ItemIndex);
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(4, 4), f.ChunkIndex);
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(8, 4), f.Data.Length);
                f.Data.CopyTo(o.AsSpan(12));
                return o;
            }
            case TcpFrame.ItemEnd f:
            {
                byte[] hash = Encoding.UTF8.GetBytes(f.Sha256Hex);
                byte[] o = new byte[4 + 2 + hash.Length];
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(0, 4), f.ItemIndex);
                BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(4, 2), (ushort)hash.Length);
                hash.CopyTo(o.AsSpan(6));
                return o;
            }
            case TcpFrame.ItemResult f:
            {
                byte[] err = f.ErrorMessage is null ? [] : Encoding.UTF8.GetBytes(f.ErrorMessage);
                byte[] o = new byte[4 + 1 + 2 + err.Length];
                BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(0, 4), f.ItemIndex);
                o[4] = f.Ok ? (byte)0x01 : (byte)0x00;
                BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(5, 2), (ushort)err.Length);
                err.CopyTo(o.AsSpan(7));
                return o;
            }
            case TcpFrame.TransferEnd f:
                return [f.Completed ? (byte)0x01 : (byte)0x00];

            case TcpFrame.Cancel f:
            {
                byte[] reason = Encoding.UTF8.GetBytes(f.Reason);
                byte[] o = new byte[2 + reason.Length];
                BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(0, 2), (ushort)reason.Length);
                reason.CopyTo(o.AsSpan(2));
                return o;
            }
            default:
                throw new NotSupportedException($"Unsupported frame: {frame.GetType().Name}");
        }
    }

    private static TcpFrame DecodePayload(byte type, long seq, string transferId, byte[] p)
    {
        switch (type)
        {
            case TypeAuth:
            {
                if (p.Length < 5) throw new InvalidDataException($"Truncated Auth payload: {p.Length} bytes");
                int tokenLen = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(0, 2));
                if (p.Length < 2 + tokenLen + 3) throw new InvalidDataException("Truncated Auth token payload");
                string token = Encoding.UTF8.GetString(p, 2, tokenLen);
                int off = 2 + tokenLen;
                int devIdLen = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(off, 2));
                off += 2;
                if (p.Length < off + devIdLen + 1) throw new InvalidDataException("Truncated Auth devId payload");
                string devId = Encoding.UTF8.GetString(p, off, devIdLen);
                off += devIdLen;
                var route = p[off] == 0x01 ? TransferRoute.WifiDirect : TransferRoute.LanTcp;
                return new TcpFrame.Auth(seq, transferId, token, devId, route);
            }
            case TypeAuthAck:
            {
                if (p.Length < 3) throw new InvalidDataException($"Truncated AuthAck payload: {p.Length} bytes");
                bool ok = p[0] == 0x01;
                int reasonLen = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(1, 2));
                string? reason = reasonLen > 0 && p.Length >= 3 + reasonLen
                    ? Encoding.UTF8.GetString(p, 3, reasonLen)
                    : null;
                return new TcpFrame.AuthAck(seq, transferId, ok, reason);
            }
            case TypeItemBegin:
            {
                if (p.Length < 20) throw new InvalidDataException($"Truncated ItemBegin payload: {p.Length} bytes");
                return new TcpFrame.ItemBegin(
                    seq, transferId,
                    BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(0, 4)),
                    BinaryPrimitives.ReadInt64BigEndian(p.AsSpan(4, 8)),
                    BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(12, 4)),
                    BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(16, 4)));
            }
            case TypeChunk:
            {
                if (p.Length < 12) throw new InvalidDataException($"Truncated Chunk payload: {p.Length} bytes");
                int itemIndex = BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(0, 4));
                int chunkIndex = BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(4, 4));
                int dataLen = BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(8, 4));
                if (dataLen < 0 || p.Length < 12 + dataLen)
                    throw new InvalidDataException($"Corrupted chunk data length: {dataLen} vs payload size {p.Length}");
                byte[] data = p.AsSpan(12, dataLen).ToArray();
                return new TcpFrame.Chunk(seq, transferId, itemIndex, chunkIndex, data);
            }
            case TypeItemEnd:
            {
                if (p.Length < 6) throw new InvalidDataException($"Truncated ItemEnd payload: {p.Length} bytes");
                int itemIndex = BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(0, 4));
                int hashLen = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(4, 2));
                if (p.Length < 6 + hashLen) throw new InvalidDataException("Truncated ItemEnd hash payload");
                return new TcpFrame.ItemEnd(seq, transferId, itemIndex, Encoding.UTF8.GetString(p, 6, hashLen));
            }
            case TypeItemResult:
            {
                if (p.Length < 7) throw new InvalidDataException($"Truncated ItemResult payload: {p.Length} bytes");
                int itemIndex = BinaryPrimitives.ReadInt32BigEndian(p.AsSpan(0, 4));
                bool ok = p[4] == 0x01;
                int errLen = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(5, 2));
                string? err = errLen > 0 && p.Length >= 7 + errLen
                    ? Encoding.UTF8.GetString(p, 7, errLen)
                    : null;
                return new TcpFrame.ItemResult(seq, transferId, itemIndex, ok, err);
            }
            case TypeTransferEnd:
            {
                if (p.Length == 0) throw new InvalidDataException("Empty TransferEnd payload");
                return new TcpFrame.TransferEnd(seq, transferId, p[0] == 0x01);
            }
            case TypeCancel:
            {
                if (p.Length < 2) throw new InvalidDataException("Truncated Cancel payload");
                int reasonLen = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(0, 2));
                string reason = reasonLen > 0 && p.Length >= 2 + reasonLen
                    ? Encoding.UTF8.GetString(p, 2, reasonLen)
                    : string.Empty;
                return new TcpFrame.Cancel(seq, transferId, reason);
            }
            default:
                throw new InvalidDataException($"Unknown TCP frame type: {type}");
        }
    }

    private static async Task ReadExactAsync(Stream input, Memory<byte> buffer, CancellationToken ct)
    {
        int total = 0;
        while (total < buffer.Length)
        {
            int r = await input.ReadAsync(buffer[total..], ct).ConfigureAwait(false);
            if (r == 0)
                throw new EndOfStreamException(
                    $"Stream closed prematurely: read {total} of {buffer.Length} expected bytes");
            total += r;
        }
    }
}
