using System.Buffers.Binary;
using System.Text;
using Veyro.Core.Models;

namespace Veyro.Core.Protocol;

/// <summary>
/// Payload encoder/decoder for the raw TCP data plane (VTC3, ADR-058, 04-PROTOCOL.md 7.3).
/// Mirrors com.veyro.core.protocol.tcp.TcpFrameCodec byte-for-byte. Record framing, sealing and
/// counters live in <see cref="Vtc3Writer"/> and <see cref="Vtc3Reader"/>; the per-type payload
/// encodings are unchanged from VTC1 and are the VTC3 plaintexts.
///
/// Record header layout:
///   4 bytes  magic "VTC3" (0x56 0x54 0x43 0x33)
///   1 byte   frame type (u8)
///   2 bytes  transferId length in bytes (u16 BE)
///   N bytes  transferId (UTF-8)
///   8 bytes  sequence number = the AEAD counter (u64 BE)
///   4 bytes  ciphertext length, tag included (u32 BE)
///   M bytes  ciphertext || 16-byte tag
/// </summary>
public static class TcpFrameCodec
{
    public static ReadOnlySpan<byte> Magic => "VTC3"u8;

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

    /// <summary>The cleartext stream-open record that carries one side's 16-byte salt.</summary>
    public const byte TypeStreamOpen = 0x09;

    internal static byte FrameType(TcpFrame frame) => frame switch
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
    internal static byte[] EncodePayload(TcpFrame frame)
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

    internal static TcpFrame DecodePayload(byte type, long seq, string transferId, byte[] buffer, int length)
    {
        ReadOnlySpan<byte> p = buffer.AsSpan(0, length);
        switch (type)
        {
            case TypeAuth:
            {
                if (p.Length < 5) throw new InvalidDataException($"Truncated Auth payload: {p.Length} bytes");
                int tokenLen = BinaryPrimitives.ReadUInt16BigEndian(p.Slice(0, 2));
                if (p.Length < 2 + tokenLen + 3) throw new InvalidDataException("Truncated Auth token payload");
                string token = Encoding.UTF8.GetString(p.Slice(2, tokenLen));
                int off = 2 + tokenLen;
                int devIdLen = BinaryPrimitives.ReadUInt16BigEndian(p.Slice(off, 2));
                off += 2;
                if (p.Length < off + devIdLen + 1) throw new InvalidDataException("Truncated Auth devId payload");
                string devId = Encoding.UTF8.GetString(p.Slice(off, devIdLen));
                off += devIdLen;
                var route = p[off] == 0x01 ? TransferRoute.WifiDirect : TransferRoute.LanTcp;
                return new TcpFrame.Auth(seq, transferId, token, devId, route);
            }
            case TypeAuthAck:
            {
                if (p.Length < 3) throw new InvalidDataException($"Truncated AuthAck payload: {p.Length} bytes");
                bool ok = p[0] == 0x01;
                int reasonLen = BinaryPrimitives.ReadUInt16BigEndian(p.Slice(1, 2));
                string? reason = reasonLen > 0 && p.Length >= 3 + reasonLen
                    ? Encoding.UTF8.GetString(p.Slice(3, reasonLen))
                    : null;
                return new TcpFrame.AuthAck(seq, transferId, ok, reason);
            }
            case TypeItemBegin:
            {
                if (p.Length < 20) throw new InvalidDataException($"Truncated ItemBegin payload: {p.Length} bytes");
                return new TcpFrame.ItemBegin(
                    seq, transferId,
                    BinaryPrimitives.ReadInt32BigEndian(p.Slice(0, 4)),
                    BinaryPrimitives.ReadInt64BigEndian(p.Slice(4, 8)),
                    BinaryPrimitives.ReadInt32BigEndian(p.Slice(12, 4)),
                    BinaryPrimitives.ReadInt32BigEndian(p.Slice(16, 4)));
            }
            case TypeChunk:
            {
                if (p.Length < 12) throw new InvalidDataException($"Truncated Chunk payload: {p.Length} bytes");
                int itemIndex = BinaryPrimitives.ReadInt32BigEndian(p.Slice(0, 4));
                int chunkIndex = BinaryPrimitives.ReadInt32BigEndian(p.Slice(4, 4));
                int dataLen = BinaryPrimitives.ReadInt32BigEndian(p.Slice(8, 4));
                if (dataLen < 0 || p.Length < 12 + dataLen)
                    throw new InvalidDataException($"Corrupted chunk data length: {dataLen} vs payload size {p.Length}");
                // Must stay a copy: the VTC3 reader reuses its record buffer for the next record
                // while BulkTransfer still writes this chunk on the pool (write-behind, review issue 16).
                byte[] data = p.Slice(12, dataLen).ToArray();
                return new TcpFrame.Chunk(seq, transferId, itemIndex, chunkIndex, data);
            }
            case TypeItemEnd:
            {
                if (p.Length < 6) throw new InvalidDataException($"Truncated ItemEnd payload: {p.Length} bytes");
                int itemIndex = BinaryPrimitives.ReadInt32BigEndian(p.Slice(0, 4));
                int hashLen = BinaryPrimitives.ReadUInt16BigEndian(p.Slice(4, 2));
                if (p.Length < 6 + hashLen) throw new InvalidDataException("Truncated ItemEnd hash payload");
                return new TcpFrame.ItemEnd(seq, transferId, itemIndex, Encoding.UTF8.GetString(p.Slice(6, hashLen)));
            }
            case TypeItemResult:
            {
                if (p.Length < 7) throw new InvalidDataException($"Truncated ItemResult payload: {p.Length} bytes");
                int itemIndex = BinaryPrimitives.ReadInt32BigEndian(p.Slice(0, 4));
                bool ok = p[4] == 0x01;
                int errLen = BinaryPrimitives.ReadUInt16BigEndian(p.Slice(5, 2));
                string? err = errLen > 0 && p.Length >= 7 + errLen
                    ? Encoding.UTF8.GetString(p.Slice(7, errLen))
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
                int reasonLen = BinaryPrimitives.ReadUInt16BigEndian(p.Slice(0, 2));
                string reason = reasonLen > 0 && p.Length >= 2 + reasonLen
                    ? Encoding.UTF8.GetString(p.Slice(2, reasonLen))
                    : string.Empty;
                return new TcpFrame.Cancel(seq, transferId, reason);
            }
            default:
                throw new InvalidDataException($"Unknown TCP frame type: {type}");
        }
    }
}
