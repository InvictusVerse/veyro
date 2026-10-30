using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Veyro.Core.Protocol.Media;

/// <summary>One record read from a VMS1 socket. Byte arrays are copies the caller owns.</summary>
public abstract record Vms1Frame
{
    public sealed record AuthFrame(Vms1.Auth Auth) : Vms1Frame;
    public sealed record AuthAck(byte Status) : Vms1Frame;
    public sealed record CloseFrame(Vms1.Close Close) : Vms1Frame;
    public sealed record VideoConfigFrame(Vms1.VideoConfig Config) : Vms1Frame;
    public sealed record Video(byte Flags, long? PtsUs, int? AuLength, byte[] Data) : Vms1Frame;
    public sealed record AudioConfigFrame(Vms1.AudioConfig Config) : Vms1Frame;
    public sealed record Audio(long PtsUs, byte[] Samples) : Vms1Frame;
}

/// <summary>The session a phone's preface names, if the PC is waiting for it.</summary>
/// <param name="PhoneId">The device id of the phone the session's control connection belongs to.</param>
/// <param name="ExportKey">That control session's key exporter (ADR-058).</param>
public sealed record Vms1PendingSession(string PhoneId, Func<string, byte[], int, byte[]> ExportKey);

/// <summary>Both directions of one VMS1 connection. Dispose wipes the keys; it does not close the stream.</summary>
public sealed class Vms1Channel(Vms1Writer writer, Vms1Reader reader) : IDisposable
{
    public Vms1Writer Writer { get; } = writer;
    public Vms1Reader Reader { get; } = reader;

    public void Dispose()
    {
        Writer.Dispose();
        Reader.Dispose();
    }

    /// <summary>
    /// The PC's side of the handshake (04-PROTOCOL.md 7.5.2): the phone's preface in, the session
    /// it names looked up (<paramref name="findSession"/> returns null for none), the PC's preface
    /// out, keys from that session's exporter, <c>AUTH</c> in and checked (both ids, source, sid),
    /// <c>AUTH_ACK</c> out. Any failure throws and nothing is answered past the preface. The
    /// caller bounds the whole call to 10 s with <paramref name="ct"/>.
    /// </summary>
    public static async Task<(Vms1Channel Channel, Vms1.Preface Preface)> AcceptAsPcAsync(
        Stream stream, string pcId, Func<Vms1.Preface, Vms1PendingSession?> findSession, CancellationToken ct)
    {
        byte[] buffer = new byte[Vms1.PrefaceSize];
        await stream.ReadExactlyAsync(buffer, ct).ConfigureAwait(false);
        Vms1.Preface preface = Vms1.ParsePreface(buffer);
        Vms1PendingSession session = findSession(preface) ?? throw new InvalidDataException("VMS1 preface names no waiting session.");
        byte[] saltPc = RandomNumberGenerator.GetBytes(Vms1.SaltSize);
        await stream.WriteAsync(Vms1.EncodePreface(preface.Source, preface.Sid, saltPc), ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
        byte[] okm = session.ExportKey(Vms1.ExportLabel(preface.Source), Vms1.ExportContext(preface.Source, preface.Sid, preface.Salt, saltPc), Vms1.OkmSize);
        Vms1Channel channel = FromOkm(stream, okm, phone: false, preface.Source);
        try
        {
            if (await channel.Reader.ReadAsync(ct).ConfigureAwait(false) is not Vms1Frame.AuthFrame { Auth: var auth })
                throw new InvalidDataException("VMS1 AUTH expected.");
            if (auth.PhoneId != session.PhoneId || auth.PcId != pcId || auth.Source != preface.Source || !auth.Sid.AsSpan().SequenceEqual(preface.Sid))
                throw new InvalidDataException("VMS1 AUTH does not match the session.");
            await channel.Writer.WriteAuthAckAsync(ct).ConfigureAwait(false);
            return (channel, preface);
        }
        catch
        {
            channel.Dispose();
            throw;
        }
    }

    /// <summary>Splits <paramref name="okm"/> into the two direction keys and wipes it.</summary>
    public static Vms1Channel FromOkm(Stream stream, byte[] okm, bool phone, byte source = Vms1.SourceScreen)
    {
        try
        {
            if (okm.Length != Vms1.OkmSize) throw new InvalidDataException($"VMS1 key derivation returned {okm.Length} bytes.");
            ReadOnlySpan<byte> up = okm.AsSpan(0, 32), down = okm.AsSpan(32, 32);
            return phone
                ? new Vms1Channel(new Vms1Writer(stream, up, Vms1.StreamUp), new Vms1Reader(stream, down, Vms1.StreamDown, fromPhone: false, source))
                : new Vms1Channel(new Vms1Writer(stream, down, Vms1.StreamDown), new Vms1Reader(stream, up, Vms1.StreamUp, fromPhone: true, source));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(okm);
        }
    }
}

/// <summary>
/// The only writer of one VMS1 direction: each record is sealed and written under one lock, so no
/// nonce is used twice. The PC writes AUTH_ACK and CLOSE; the media kinds are here for the tests
/// and the vectors (the phone is the real media writer).
/// </summary>
public sealed class Vms1Writer : IDisposable
{
    private readonly Stream _stream;
    private readonly AeadStreamFramer _framer;
    private readonly SemaphoreSlim _lock = new(1, 1);
    private readonly byte[] _plain = new byte[Vms1.MaxPlaintext];
    private readonly byte[] _record = new byte[Vms1.HeaderSize + Vms1.MaxPlaintext + Vms1.TagSize];

    public Vms1Writer(Stream stream, ReadOnlySpan<byte> key, uint streamId)
    {
        _stream = stream;
        _framer = new AeadStreamFramer(key, streamId, FramerRole.Seal);
    }

    public Task WriteAuthAsync(string phoneId, string pcId, byte source, byte[] sid, CancellationToken ct) =>
        WriteRecordAsync(Vms1.KindAuth, 0, Vms1.EncodeAuth(phoneId, pcId, source, sid), ReadOnlyMemory<byte>.Empty, ct);

    public Task WriteAuthAckAsync(CancellationToken ct) => WriteRecordAsync(Vms1.KindAuthAck, 0, new byte[] { 0 }, ReadOnlyMemory<byte>.Empty, ct);

    public Task WriteCloseAsync(byte code, string reason, CancellationToken ct) =>
        WriteRecordAsync(Vms1.KindClose, 0, Vms1.EncodeClose(code, reason), ReadOnlyMemory<byte>.Empty, ct);

    public Task WriteVideoConfigAsync(Vms1.VideoConfig config, CancellationToken ct) =>
        WriteRecordAsync(Vms1.KindVideoConfig, 0, Vms1.EncodeVideoConfig(config), ReadOnlyMemory<byte>.Empty, ct);

    public Task WriteAudioConfigAsync(Vms1.AudioConfig config, CancellationToken ct) =>
        WriteRecordAsync(Vms1.KindAudioConfig, 0, Vms1.EncodeAudioConfig(config), ReadOnlyMemory<byte>.Empty, ct);

    public async Task WriteAccessUnitAsync(long ptsUs, ReadOnlyMemory<byte> data, bool key, bool discontinuity, CancellationToken ct)
    {
        foreach (var f in AccessUnitFragmenter.Plan(data.Length, key, discontinuity))
        {
            ReadOnlyMemory<byte> body = data.Slice(f.Offset, f.Length);
            if ((f.Flags & Vms1.FlagStart) != 0)
            {
                byte[] head = new byte[Vms1.StartPrefix];
                BinaryPrimitives.WriteInt64BigEndian(head, ptsUs);
                BinaryPrimitives.WriteInt32BigEndian(head.AsSpan(8), data.Length);
                await WriteRecordAsync(Vms1.KindVideo, f.Flags, head, body, ct).ConfigureAwait(false);
            }
            else
            {
                await WriteRecordAsync(Vms1.KindVideo, f.Flags, body, ReadOnlyMemory<byte>.Empty, ct).ConfigureAwait(false);
            }
        }
    }

    public Task WriteAudioAsync(long ptsUs, ReadOnlyMemory<byte> samples, CancellationToken ct)
    {
        byte[] head = new byte[8];
        BinaryPrimitives.WriteInt64BigEndian(head, ptsUs);
        return WriteRecordAsync(Vms1.KindAudio, 0, head, samples, ct);
    }

    private async Task WriteRecordAsync(byte kind, byte flags, ReadOnlyMemory<byte> head, ReadOnlyMemory<byte> body, CancellationToken ct)
    {
        int plainLen = head.Length + body.Length;
        if (plainLen > Vms1.MaxPlaintext) throw new InvalidDataException("VMS1 record too large.");
        await _lock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            head.Span.CopyTo(_plain);
            body.Span.CopyTo(_plain.AsSpan(head.Length));
            Vms1.WriteHeader(_record, kind, flags, plainLen + Vms1.TagSize);
            int sealedLen;
            try
            {
                sealedLen = _framer.Seal(_record.AsSpan(0, Vms1.HeaderSize), _plain.AsSpan(0, plainLen), _record.AsSpan(Vms1.HeaderSize));
            }
            finally
            {
                CryptographicOperations.ZeroMemory(_plain.AsSpan(0, plainLen));
            }
            await _stream.WriteAsync(_record.AsMemory(0, Vms1.HeaderSize + sealedLen), ct).ConfigureAwait(false);
            await _stream.FlushAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            _lock.Release();
        }
    }

    public void Dispose() => _framer.Dispose();
}

/// <summary>The only reader of one VMS1 direction. <c>fromPhone</c>: the records it expects come from the phone.</summary>
public sealed class Vms1Reader : IDisposable
{
    private readonly Stream _stream;
    private readonly AeadStreamFramer _framer;
    private readonly bool _fromPhone;
    private readonly byte _source;
    private readonly byte[] _header = new byte[Vms1.HeaderSize];
    private readonly byte[] _sealed = new byte[Vms1.MaxPlaintext + Vms1.TagSize];
    private readonly byte[] _plain = new byte[Vms1.MaxPlaintext];
    private bool _failed;
    private bool _first = true;

    public Vms1Reader(Stream stream, ReadOnlySpan<byte> key, uint streamId, bool fromPhone, byte source = Vms1.SourceScreen)
    {
        _source = source;
        _stream = stream;
        _framer = new AeadStreamFramer(key, streamId, FramerRole.Open);
        _fromPhone = fromPhone;
    }

    /// <summary>The next record, or null on a clean end of stream at a record boundary. Not for concurrent use.</summary>
    public async Task<Vms1Frame?> ReadAsync(CancellationToken ct)
    {
        if (_failed) throw new InvalidDataException("VMS1 stream failed earlier.");
        try
        {
            int first = await _stream.ReadAsync(_header.AsMemory(0, 1), ct).ConfigureAwait(false);
            if (first == 0) return null;
            await _stream.ReadExactlyAsync(_header.AsMemory(1, Vms1.HeaderSize - 1), ct).ConfigureAwait(false);
            byte kind = _header[0], flags = _header[1];
            int len = BinaryPrimitives.ReadInt32BigEndian(_header.AsSpan(2));
            Vms1.CheckHeader(kind, flags, len, _fromPhone, _source);
            byte expectedFirst = _fromPhone ? Vms1.KindAuth : Vms1.KindAuthAck;
            if (_first != (kind == expectedFirst)) throw new InvalidDataException("VMS1 handshake record out of order.");
            await _stream.ReadExactlyAsync(_sealed.AsMemory(0, len), ct).ConfigureAwait(false);
            int n = _framer.Open(_header, _sealed.AsSpan(0, len), _plain);
            _first = false;
            return Decode(kind, flags, _plain.AsSpan(0, n));
        }
        catch (Exception error) when (error is not OperationCanceledException)
        {
            _failed = true;
            if (error is InvalidDataException or EndOfStreamException or IOException) throw;
            throw new InvalidDataException("VMS1 record is malformed.", error);
        }
        catch
        {
            _failed = true;
            throw;
        }
    }

    private static Vms1Frame Decode(byte kind, byte flags, ReadOnlySpan<byte> p)
    {
        switch (kind)
        {
            case Vms1.KindAuth: return new Vms1Frame.AuthFrame(Vms1.DecodeAuth(p));
            case Vms1.KindAuthAck:
                if (p.Length != 1) throw new InvalidDataException("VMS1 AUTH_ACK has a bad length.");
                return new Vms1Frame.AuthAck(p[0]);
            case Vms1.KindClose: return new Vms1Frame.CloseFrame(Vms1.DecodeClose(p));
            case Vms1.KindVideoConfig: return new Vms1Frame.VideoConfigFrame(Vms1.DecodeVideoConfig(p));
            case Vms1.KindAudioConfig: return new Vms1Frame.AudioConfigFrame(Vms1.DecodeAudioConfig(p));
            case Vms1.KindVideo:
                if ((flags & Vms1.FlagStart) != 0)
                {
                    if (p.Length < Vms1.StartPrefix + 1) throw new InvalidDataException("VMS1 START fragment is truncated.");
                    long pts = BinaryPrimitives.ReadInt64BigEndian(p);
                    int auLength = BinaryPrimitives.ReadInt32BigEndian(p[8..]);
                    if (pts < 0 || auLength is < 1 or > Vms1.MaxAccessUnit) throw new InvalidDataException("VMS1 START fragment out of range.");
                    return new Vms1Frame.Video(flags, pts, auLength, p[Vms1.StartPrefix..].ToArray());
                }
                if (p.Length > Vms1.MaxFragment) throw new InvalidDataException("VMS1 fragment too large.");
                return new Vms1Frame.Video(flags, null, null, p.ToArray());
            case Vms1.KindAudio:
                if (p.Length < 9 || p.Length - 8 > Vms1.MaxAudioBytes) throw new InvalidDataException("VMS1 AUDIO has a bad length.");
                long audioPts = BinaryPrimitives.ReadInt64BigEndian(p);
                if (audioPts < 0) throw new InvalidDataException("VMS1 AUDIO pts out of range.");
                return new Vms1Frame.Audio(audioPts, p[8..].ToArray());
            default: throw new InvalidDataException("VMS1 record kind not allowed.");
        }
    }

    public void Dispose()
    {
        _framer.Dispose();
        CryptographicOperations.ZeroMemory(_plain);
    }
}
