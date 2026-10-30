using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Veyro.Core.Protocol;

/// <summary>
/// VTC3: the sealed raw-TCP data plane (ADR-058, 04-PROTOCOL.md 7.3). Mirrors
/// com.veyro.core.protocol.tcp.Vtc3. Every data-socket attempt opens with one cleartext
/// stream-open record in each direction carrying a fresh 16-byte salt; the per-attempt keys come
/// from the session key exporter with both salts in the context:
/// <code>
/// okm    = exportKey("veyro-vtc3", utf8(tid) || 0x00 || saltA || saltB, 64)
/// keyS2R = okm[0..32]   stream id 1, sender seals, receiver opens
/// keyR2S = okm[32..64]  stream id 2, receiver seals, sender opens
/// </code>
/// </summary>
public static class Vtc3
{
    public const string ExportLabel = ProtocolConstants.ExportLabelVtc3;
    public const int SaltSize = 16;
    public const uint StreamSenderToReceiver = 1;
    public const uint StreamReceiverToSender = 2;
    public const int OkmSize = 64;

    /// <summary><c>(saltA, saltB) -> 64 bytes</c> from the session exporter for <paramref name="transferId"/>.</summary>
    public static Func<byte[], byte[], byte[]> KeyDeriver(Func<string, byte[], int, byte[]> exportKey, string transferId)
        => (saltA, saltB) => exportKey(ExportLabel, ExportContext(transferId, saltA, saltB), OkmSize);

    /// <summary><c>utf8(tid) || 0x00 || saltA || saltB</c>.</summary>
    public static byte[] ExportContext(string transferId, byte[] saltA, byte[] saltB)
    {
        if (saltA.Length != SaltSize || saltB.Length != SaltSize)
            throw new ArgumentException("VTC3 salts must be 16 bytes.");
        return [.. Encoding.UTF8.GetBytes(transferId), 0, .. saltA, .. saltB];
    }

    /// <summary>The cleartext stream-open record: <c>"VTC3" | 0x09 | u16 tidLen | tid | u64 0 | u32 16 | salt</c>.</summary>
    public static byte[] StreamOpenRecord(string transferId, byte[] salt)
    {
        if (salt.Length != SaltSize) throw new ArgumentException("VTC3 salt must be 16 bytes.", nameof(salt));
        byte[] tid = TidBytes(transferId);
        byte[] o = new byte[4 + 1 + 2 + tid.Length + 8 + 4 + SaltSize];
        TcpFrameCodec.Magic.CopyTo(o);
        o[4] = TcpFrameCodec.TypeStreamOpen;
        BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(5, 2), (ushort)tid.Length);
        tid.CopyTo(o.AsSpan(7));
        BinaryPrimitives.WriteInt64BigEndian(o.AsSpan(7 + tid.Length, 8), 0);
        BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(15 + tid.Length, 4), SaltSize);
        salt.CopyTo(o.AsSpan(19 + tid.Length));
        return o;
    }

    /// <summary>The data-socket dialler: writes its salt first, then reads the receiver's.</summary>
    public static async Task<Vtc3Channel> OpenAsSenderAsync(Stream stream, string transferId, Func<byte[], byte[], byte[]> deriveKeys, CancellationToken ct)
    {
        byte[] saltA = RandomNumberGenerator.GetBytes(SaltSize);
        await stream.WriteAsync(StreamOpenRecord(transferId, saltA), ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
        byte[] saltB = await ReadStreamOpenAsync(stream, transferId, ct).ConfigureAwait(false);
        return Channel(stream, transferId, deriveKeys(saltA, saltB), sender: true);
    }

    /// <summary>The data-socket listener: reads the sender's salt first, then writes its own.</summary>
    public static async Task<Vtc3Channel> OpenAsReceiverAsync(Stream stream, string transferId, Func<byte[], byte[], byte[]> deriveKeys, CancellationToken ct)
    {
        byte[] saltA = await ReadStreamOpenAsync(stream, transferId, ct).ConfigureAwait(false);
        byte[] saltB = RandomNumberGenerator.GetBytes(SaltSize);
        await stream.WriteAsync(StreamOpenRecord(transferId, saltB), ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
        return Channel(stream, transferId, deriveKeys(saltA, saltB), sender: false);
    }

    /// <summary>Reads and checks one stream-open record; returns the peer's salt.</summary>
    public static async Task<byte[]> ReadStreamOpenAsync(Stream input, string transferId, CancellationToken ct)
    {
        byte[] expected = TidBytes(transferId);
        byte[] prefix = new byte[7];
        await ReadExactAsync(input, prefix, ct).ConfigureAwait(false);
        if (!prefix.AsSpan(0, 4).SequenceEqual(TcpFrameCodec.Magic)) throw new InvalidDataException("VTC3 stream-open has a bad magic.");
        if (prefix[4] != TcpFrameCodec.TypeStreamOpen) throw new InvalidDataException("VTC3 stream-open expected.");
        int tidLen = BinaryPrimitives.ReadUInt16BigEndian(prefix.AsSpan(5, 2));
        if (tidLen != expected.Length) throw new InvalidDataException("VTC3 stream-open for another transfer.");
        byte[] rest = new byte[tidLen + 12 + SaltSize];
        await ReadExactAsync(input, rest, ct).ConfigureAwait(false);
        if (!rest.AsSpan(0, tidLen).SequenceEqual(expected)) throw new InvalidDataException("VTC3 stream-open for another transfer.");
        if (BinaryPrimitives.ReadInt64BigEndian(rest.AsSpan(tidLen, 8)) != 0) throw new InvalidDataException("VTC3 stream-open seq must be 0.");
        if (BinaryPrimitives.ReadInt32BigEndian(rest.AsSpan(tidLen + 8, 4)) != SaltSize) throw new InvalidDataException("VTC3 stream-open length must be 16.");
        return rest.AsSpan(tidLen + 12, SaltSize).ToArray();
    }

    internal static byte[] TidBytes(string transferId)
    {
        byte[] tid = Encoding.UTF8.GetBytes(transferId);
        if (tid.Length is 0 or > TcpFrameCodec.MaxTransferIdLength)
            throw new ArgumentException("Transfer id must be 1..256 UTF-8 bytes.", nameof(transferId));
        return tid;
    }

    internal static async Task ReadExactAsync(Stream input, Memory<byte> buffer, CancellationToken ct)
    {
        int total = 0;
        while (total < buffer.Length)
        {
            int r = await input.ReadAsync(buffer[total..], ct).ConfigureAwait(false);
            if (r == 0) throw new EndOfStreamException("VTC3 stream ended inside a record.");
            total += r;
        }
    }

    private static Vtc3Channel Channel(Stream stream, string transferId, byte[] okm, bool sender)
    {
        try
        {
            if (okm.Length != OkmSize) throw new InvalidDataException("VTC3 key derivation returned the wrong length.");
            ReadOnlySpan<byte> s2r = okm.AsSpan(0, 32);
            ReadOnlySpan<byte> r2s = okm.AsSpan(32, 32);
            var writer = sender
                ? new Vtc3Writer(stream, s2r, StreamSenderToReceiver, transferId)
                : new Vtc3Writer(stream, r2s, StreamReceiverToSender, transferId);
            var reader = sender
                ? new Vtc3Reader(stream, r2s, StreamReceiverToSender, transferId)
                : new Vtc3Reader(stream, s2r, StreamSenderToReceiver, transferId);
            return new Vtc3Channel(writer, reader);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(okm);
        }
    }
}

/// <summary>Both directions of one data-socket attempt. Dispose wipes both framers; it does not close the stream.</summary>
public sealed class Vtc3Channel(Vtc3Writer writer, Vtc3Reader reader) : IDisposable
{
    public Vtc3Writer Writer { get; } = writer;
    public Vtc3Reader Reader { get; } = reader;

    public void Dispose()
    {
        Writer.Dispose();
        Reader.Dispose();
    }
}

/// <summary>
/// The only writer of one data-socket direction: allocates the counter, seals and writes under
/// one gate, so no caller can send two records with the same nonce (invariant 1).
/// </summary>
public sealed class Vtc3Writer : IDisposable
{
    private readonly Stream _output;
    private readonly AeadStreamFramer _framer;
    private readonly byte[] _tid;
    private readonly int _headerLen;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private byte[] _record = [];

    public Vtc3Writer(Stream output, ReadOnlySpan<byte> key, uint streamId, string transferId)
    {
        _output = output;
        _framer = new AeadStreamFramer(key, streamId, FramerRole.Seal);
        _tid = Vtc3.TidBytes(transferId);
        _headerLen = 4 + 1 + 2 + _tid.Length + 8 + 4;
    }

    /// <summary>Records written so far.</summary>
    public long NextSeq => _framer.NextCounter;

    /// <summary>Seals and writes <paramref name="frame"/>. Its <c>Seq</c> is ignored; the framer counter is written instead.</summary>
    public async Task WriteAsync(TcpFrame frame, bool? flush = null, CancellationToken ct = default)
    {
        if (!Encoding.UTF8.GetBytes(frame.TransferId).AsSpan().SequenceEqual(_tid))
            throw new ArgumentException("Frame belongs to another transfer.", nameof(frame));
        if (frame is TcpFrame.Chunk chunk)
        {
            await WriteChunkAsync(chunk.ItemIndex, chunk.ChunkIndex, chunk.Data, flush ?? false, ct).ConfigureAwait(false);
            return;
        }
        byte[] payload = TcpFrameCodec.EncodePayload(frame);
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            int total = Prepare(TcpFrameCodec.FrameType(frame), payload.Length);
            payload.CopyTo(_record.AsSpan(_headerLen));
            Seal(payload.Length);
            await _output.WriteAsync(_record.AsMemory(0, total), ct).ConfigureAwait(false);
            if (flush ?? true) await _output.FlushAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>
    /// Seals one chunk from the caller's buffer: the data is copied once into the reused record
    /// buffer and encrypted in place (no per-chunk allocation, FR-S5).
    /// </summary>
    public async Task WriteChunkAsync(int itemIndex, int chunkIndex, ReadOnlyMemory<byte> data, bool flush, CancellationToken ct = default)
    {
        int size = 12 + data.Length;
        if (size > TcpFrameCodec.MaxPayloadSize) throw new InvalidDataException("VTC3 frame too large.");
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            int total = Prepare(TcpFrameCodec.TypeChunk, size);
            Span<byte> p = _record.AsSpan(_headerLen, size);
            BinaryPrimitives.WriteInt32BigEndian(p[..4], itemIndex);
            BinaryPrimitives.WriteInt32BigEndian(p.Slice(4, 4), chunkIndex);
            BinaryPrimitives.WriteInt32BigEndian(p.Slice(8, 4), data.Length);
            data.Span.CopyTo(p[12..]);
            Seal(size);
            await _output.WriteAsync(_record.AsMemory(0, total), ct).ConfigureAwait(false);
            if (flush) await _output.FlushAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose()
    {
        _framer.Dispose();
        CryptographicOperations.ZeroMemory(_record);
    }

    /// <summary>Call under the gate: grows the record buffer and writes the header; returns the record length.</summary>
    private int Prepare(byte type, int size)
    {
        if (size > TcpFrameCodec.MaxPayloadSize) throw new InvalidDataException("VTC3 frame too large.");
        int total = _headerLen + size + AeadStreamFramer.TagSize;
        if (_record.Length < total) _record = new byte[total];
        Span<byte> h = _record.AsSpan(0, _headerLen);
        TcpFrameCodec.Magic.CopyTo(h);
        h[4] = type;
        BinaryPrimitives.WriteUInt16BigEndian(h.Slice(5, 2), (ushort)_tid.Length);
        _tid.CopyTo(h[7..]);
        BinaryPrimitives.WriteInt64BigEndian(h.Slice(7 + _tid.Length, 8), _framer.NextCounter);
        BinaryPrimitives.WriteInt32BigEndian(h.Slice(15 + _tid.Length, 4), size + AeadStreamFramer.TagSize);
        return total;
    }

    /// <summary>Encrypts the plaintext at the header's end in place (exact overlap is allowed).</summary>
    private void Seal(int size)
    {
        Span<byte> body = _record.AsSpan(_headerLen, size + AeadStreamFramer.TagSize);
        _framer.Seal(_record.AsSpan(0, _headerLen), body[..size], body);
    }
}

/// <summary>The only reader of one data-socket direction.</summary>
public sealed class Vtc3Reader : IDisposable
{
    private readonly Stream _input;
    private readonly AeadStreamFramer _framer;
    private readonly byte[] _tid;
    private readonly string _transferId;
    private readonly byte[] _header;
    private byte[] _sealed = [];
    private byte[] _plain = [];
    private bool _failed;

    public Vtc3Reader(Stream input, ReadOnlySpan<byte> key, uint streamId, string transferId)
    {
        _input = input;
        _framer = new AeadStreamFramer(key, streamId, FramerRole.Open);
        _tid = Vtc3.TidBytes(transferId);
        _transferId = transferId;
        _header = new byte[4 + 1 + 2 + _tid.Length + 8 + 4];
    }

    /// <summary>
    /// The next frame, or null on a clean EOF at a record boundary. Any violation throws
    /// <see cref="InvalidDataException"/> (or <see cref="EndOfStreamException"/> for a truncated
    /// record), and the reader stays failed: a cancellation inside a record also fails it,
    /// because the stream position is lost.
    /// </summary>
    public async Task<TcpFrame?> ReadOrNullAsync(CancellationToken ct = default)
    {
        if (_failed) throw new InvalidDataException("VTC3 stream failed earlier.");
        int first = await _input.ReadAsync(_header.AsMemory(0, 1), ct).ConfigureAwait(false);
        if (first == 0) return null;
        try
        {
            return await ReadRestAsync(ct).ConfigureAwait(false);
        }
        catch
        {
            _failed = true;
            throw;
        }
    }

    private async Task<TcpFrame> ReadRestAsync(CancellationToken ct)
    {
        await Vtc3.ReadExactAsync(_input, _header.AsMemory(1, 6), ct).ConfigureAwait(false);
        if (!_header.AsSpan(0, 4).SequenceEqual(TcpFrameCodec.Magic)) throw new InvalidDataException("VTC3 record has a bad magic.");
        byte type = _header[4];
        if (type is < TcpFrameCodec.TypeAuth or > TcpFrameCodec.TypeCancel) throw new InvalidDataException("VTC3 record has an unexpected type.");
        int tidLen = BinaryPrimitives.ReadUInt16BigEndian(_header.AsSpan(5, 2));
        if (tidLen is < 1 or > TcpFrameCodec.MaxTransferIdLength || tidLen != _tid.Length)
            throw new InvalidDataException("VTC3 record for another transfer.");
        await Vtc3.ReadExactAsync(_input, _header.AsMemory(7, tidLen + 12), ct).ConfigureAwait(false);
        if (!_header.AsSpan(7, tidLen).SequenceEqual(_tid)) throw new InvalidDataException("VTC3 record for another transfer.");
        long seq = BinaryPrimitives.ReadInt64BigEndian(_header.AsSpan(7 + tidLen, 8));
        if (seq < 0 || seq != _framer.NextCounter) throw new InvalidDataException("VTC3 record out of order.");
        int ctLen = BinaryPrimitives.ReadInt32BigEndian(_header.AsSpan(15 + tidLen, 4));
        if (ctLen < AeadStreamFramer.TagSize || ctLen > TcpFrameCodec.MaxPayloadSize + AeadStreamFramer.TagSize)
            throw new InvalidDataException("VTC3 record has a bad length.");
        if (_sealed.Length < ctLen) _sealed = new byte[ctLen];
        if (_plain.Length < ctLen) _plain = new byte[ctLen];
        await Vtc3.ReadExactAsync(_input, _sealed.AsMemory(0, ctLen), ct).ConfigureAwait(false);
        int len = _framer.Open(_header, _sealed.AsSpan(0, ctLen), _plain);
        try
        {
            return TcpFrameCodec.DecodePayload(type, seq, _transferId, _plain, len);
        }
        catch (Exception error) when (error is not InvalidDataException)
        {
            throw new InvalidDataException("VTC3 record payload is malformed.", error);
        }
    }

    public void Dispose()
    {
        _framer.Dispose();
        CryptographicOperations.ZeroMemory(_plain);
    }
}
