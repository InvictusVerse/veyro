using System.Buffers.Binary;
using System.Text;

namespace Veyro.Core.Protocol.Media;

/// <summary>
/// VMS1: the sealed media socket of screen mirroring (ADR-066, 04-PROTOCOL.md 7.5). Mirrors
/// com.veyro.core.protocol.media.Vms1 byte for byte.
/// <code>
/// okm   = exportKey(label(source), "VMS1" || u8 source || sid || saltPhone || saltPc, 64)
/// kUp   = okm[0..32]   stream id 1, phone seals, PC opens
/// kDown = okm[32..64]  stream id 2, PC seals, phone opens
/// record = u8 kind || u8 flags || u32 len || ChaCha20-Poly1305(k, nonce(stream id, counter), aad = first 6 bytes)
/// </code>
/// Each side first sends a cleartext 40-byte preface with a fresh salt. Video and audio share the
/// one upward counter and carry ptsUs from the phone's one monotonic clock, so they can be synced.
/// </summary>
public static class Vms1
{
    public static ReadOnlySpan<byte> Magic => "VMS1"u8;
    public const byte Version = 1;

    public const byte SourceScreen = 0x01;
    public const byte SourceCamera = 0x02;
    /// <summary>ADR-073: the PC's sound to the phone; the only source whose media the PC sends.</summary>
    public const byte SourceSpeaker = 0x03;

    public static bool IsSource(byte source) => source is SourceScreen or SourceCamera or SourceSpeaker;

    public const int SidSize = 16;
    public const int SaltSize = 16;
    public const int PrefaceSize = 40;
    public const int OkmSize = 64;
    public const uint StreamUp = 1;
    public const uint StreamDown = 2;
    public const int HeaderSize = 6;
    public const int TagSize = 16;

    public const byte KindAuth = 0x01;
    public const byte KindAuthAck = 0x02;
    public const byte KindClose = 0x03;
    public const byte KindVideoConfig = 0x10;
    public const byte KindVideo = 0x11;
    public const byte KindAudioConfig = 0x20;
    public const byte KindAudio = 0x21;

    public const byte FlagKey = 0x01;
    public const byte FlagStart = 0x02;
    public const byte FlagEnd = 0x04;
    public const byte FlagDiscontinuity = 0x08;
    private const byte VideoFlags = FlagKey | FlagStart | FlagEnd | FlagDiscontinuity;

    public const int MaxFragment = 65_536;
    public const int StartPrefix = 12;
    public const int MaxPlaintext = StartPrefix + MaxFragment;
    public const int MaxAccessUnit = 8 * 1024 * 1024;
    public const int MaxAudioBytes = 16_384;
    public const int MaxIdBytes = 64;
    public const int MaxReasonBytes = 64;

    public const byte VideoCodecH264 = 1;
    public const byte VideoCodecH265 = 2;
    public const byte ColourBt709Limited = 1;
    public const int VideoConfigSize = 22;
    public const byte AudioCodecPcmS16Le = 1;
    public const int AudioConfigSize = 6;

    public static string ExportLabel(byte source) => source switch
    {
        SourceScreen => ProtocolConstants.ExportLabelScreen,
        SourceCamera => ProtocolConstants.ExportLabelCamera,
        SourceSpeaker => ProtocolConstants.ExportLabelSpeaker,
        _ => throw new ArgumentException("Unknown VMS1 source.", nameof(source)),
    };

    /// <summary><c>"VMS1" || u8 source || sid || saltPhone || saltPc</c> (53 bytes).</summary>
    public static byte[] ExportContext(byte source, ReadOnlySpan<byte> sid, ReadOnlySpan<byte> saltPhone, ReadOnlySpan<byte> saltPc)
    {
        if (sid.Length != SidSize || saltPhone.Length != SaltSize || saltPc.Length != SaltSize)
            throw new ArgumentException("VMS1 sid and salts are 16 bytes.");
        return [.. Magic, source, .. sid, .. saltPhone, .. saltPc];
    }

    // ── Preface ──────────────────────────────────────────────────────────────────────────────

    public sealed record Preface(byte Source, byte[] Sid, byte[] Salt);

    public static byte[] EncodePreface(byte source, ReadOnlySpan<byte> sid, ReadOnlySpan<byte> salt)
    {
        if (!IsSource(source)) throw new ArgumentException("Unknown VMS1 source.", nameof(source));
        if (sid.Length != SidSize || salt.Length != SaltSize) throw new ArgumentException("VMS1 sid and salt are 16 bytes.");
        byte[] o = new byte[PrefaceSize];
        Magic.CopyTo(o);
        o[4] = Version;
        o[5] = source;
        sid.CopyTo(o.AsSpan(8));
        salt.CopyTo(o.AsSpan(24));
        return o;
    }

    public static Preface ParsePreface(ReadOnlySpan<byte> b)
    {
        if (b.Length != PrefaceSize) throw new InvalidDataException("VMS1 preface has a bad length.");
        if (!b[..4].SequenceEqual(Magic)) throw new InvalidDataException("VMS1 preface has a bad magic.");
        if (b[4] != Version) throw new InvalidDataException("VMS1 version not supported.");
        if (!IsSource(b[5])) throw new InvalidDataException("VMS1 preface has an unknown source.");
        if (b[6] != 0 || b[7] != 0) throw new InvalidDataException("VMS1 preface reserved bytes set.");
        return new Preface(b[5], b.Slice(8, SidSize).ToArray(), b.Slice(24, SaltSize).ToArray());
    }

    // ── Records ──────────────────────────────────────────────────────────────────────────────

    /// <summary>Media flows from the phone, except on a speaker socket, where only the PC sends sound (ADR-073).</summary>
    public static bool AllowedFrom(byte kind, bool fromPhone, byte source = SourceScreen) => kind switch
    {
        KindClose => true,
        KindAuth => fromPhone,
        KindAuthAck => !fromPhone,
        KindVideoConfig or KindVideo => fromPhone && source != SourceSpeaker,
        KindAudioConfig or KindAudio => fromPhone == (source != SourceSpeaker),
        _ => false,
    };

    /// <summary>Header check before any buffer is used. Throws <see cref="InvalidDataException"/>.</summary>
    public static void CheckHeader(byte kind, byte flags, int sealedLength, bool fromPhone, byte source = SourceScreen)
    {
        if (!AllowedFrom(kind, fromPhone, source)) throw new InvalidDataException("VMS1 record kind not allowed.");
        if (kind == KindVideo ? (flags & ~VideoFlags) != 0 : flags != 0) throw new InvalidDataException("VMS1 record flags not allowed.");
        if (sealedLength < TagSize + 1 || sealedLength > MaxPlaintext + TagSize) throw new InvalidDataException("VMS1 record has a bad length.");
    }

    public static void WriteHeader(Span<byte> dst, byte kind, byte flags, int sealedLength)
    {
        dst[0] = kind;
        dst[1] = flags;
        BinaryPrimitives.WriteInt32BigEndian(dst.Slice(2, 4), sealedLength);
    }

    public sealed record Auth(string PhoneId, string PcId, byte Source, byte[] Sid);

    public static byte[] EncodeAuth(string phoneId, string pcId, byte source, ReadOnlySpan<byte> sid)
    {
        byte[] phone = Encoding.UTF8.GetBytes(phoneId), pc = Encoding.UTF8.GetBytes(pcId);
        if (phone.Length is < 1 or > MaxIdBytes || pc.Length is < 1 or > MaxIdBytes) throw new ArgumentException("VMS1 ids are 1..64 bytes.");
        if (sid.Length != SidSize) throw new ArgumentException("VMS1 sid is 16 bytes.");
        byte[] o = new byte[2 + phone.Length + 2 + pc.Length + 1 + SidSize];
        int p = 0;
        BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(p), (ushort)phone.Length); p += 2;
        phone.CopyTo(o, p); p += phone.Length;
        BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(p), (ushort)pc.Length); p += 2;
        pc.CopyTo(o, p); p += pc.Length;
        o[p++] = source;
        sid.CopyTo(o.AsSpan(p));
        return o;
    }

    public static Auth DecodeAuth(ReadOnlySpan<byte> s)
    {
        int p = 0;
        string phone = ReadId(s, ref p), pc = ReadId(s, ref p);
        if (p + 1 + SidSize != s.Length) throw new InvalidDataException("VMS1 AUTH has a bad length.");
        return new Auth(phone, pc, s[p], s.Slice(p + 1, SidSize).ToArray());
    }

    private static string ReadId(ReadOnlySpan<byte> s, ref int p)
    {
        if (p + 2 > s.Length) throw new InvalidDataException("VMS1 AUTH is truncated.");
        int n = BinaryPrimitives.ReadUInt16BigEndian(s[p..]);
        p += 2;
        if (n is < 1 or > MaxIdBytes || p + n > s.Length) throw new InvalidDataException("VMS1 AUTH id length.");
        string id = Encoding.UTF8.GetString(s.Slice(p, n));
        p += n;
        return id;
    }

    public sealed record Close(byte Code, string Reason);

    public static byte[] EncodeClose(byte code, string reason)
    {
        if (reason.Length > MaxReasonBytes || reason.Any(c => c is < (char)0x21 or > (char)0x7E)) throw new ArgumentException("VMS1 close reason.");
        byte[] r = Encoding.ASCII.GetBytes(reason);
        byte[] o = new byte[3 + r.Length];
        o[0] = code;
        BinaryPrimitives.WriteUInt16BigEndian(o.AsSpan(1), (ushort)r.Length);
        r.CopyTo(o, 3);
        return o;
    }

    public static Close DecodeClose(ReadOnlySpan<byte> s)
    {
        if (s.Length < 3) throw new InvalidDataException("VMS1 CLOSE is truncated.");
        int n = BinaryPrimitives.ReadUInt16BigEndian(s[1..]);
        if (n > MaxReasonBytes || 3 + n != s.Length) throw new InvalidDataException("VMS1 CLOSE reason length.");
        foreach (byte b in s[3..]) if (b is < 0x21 or > 0x7E) throw new InvalidDataException("VMS1 CLOSE reason not ASCII.");
        return new Close(s[0], Encoding.ASCII.GetString(s[3..]));
    }

    /// <summary>The 22-byte VIDEO_CONFIG (04-PROTOCOL.md 7.5.4).</summary>
    public sealed record VideoConfig(byte Codec, int Width, int Height, int VisX, int VisY, int VisW, int VisH, int Rotation, bool Mirror, long Generation, byte Colour = ColourBt709Limited)
    {
        public bool IsValid =>
            Codec is VideoCodecH264 or VideoCodecH265 && Colour == ColourBt709Limited &&
            Width is >= 16 and <= 8192 && Height is >= 16 and <= 8192 &&
            VisW >= 1 && VisH >= 1 && VisX >= 0 && VisY >= 0 && VisX + VisW <= Width && VisY + VisH <= Height &&
            Rotation is 0 or 90 or 180 or 270 && Generation is >= 1 and <= 0xFFFF_FFFFL;
    }

    public static byte[] EncodeVideoConfig(VideoConfig c)
    {
        if (!c.IsValid) throw new ArgumentException("VMS1 video config out of range.");
        byte[] o = new byte[VideoConfigSize];
        o[0] = c.Codec;
        o[1] = c.Colour;
        Span<byte> s = o;
        BinaryPrimitives.WriteUInt16BigEndian(s[2..], (ushort)c.Width);
        BinaryPrimitives.WriteUInt16BigEndian(s[4..], (ushort)c.Height);
        BinaryPrimitives.WriteUInt16BigEndian(s[6..], (ushort)c.VisX);
        BinaryPrimitives.WriteUInt16BigEndian(s[8..], (ushort)c.VisY);
        BinaryPrimitives.WriteUInt16BigEndian(s[10..], (ushort)c.VisW);
        BinaryPrimitives.WriteUInt16BigEndian(s[12..], (ushort)c.VisH);
        BinaryPrimitives.WriteUInt16BigEndian(s[14..], (ushort)c.Rotation);
        o[16] = c.Mirror ? (byte)1 : (byte)0;
        BinaryPrimitives.WriteUInt32BigEndian(s[18..], (uint)c.Generation);
        return o;
    }

    public static VideoConfig DecodeVideoConfig(ReadOnlySpan<byte> s)
    {
        if (s.Length != VideoConfigSize) throw new InvalidDataException("VMS1 VIDEO_CONFIG has a bad length.");
        if ((s[16] & ~1) != 0 || s[17] != 0) throw new InvalidDataException("VMS1 VIDEO_CONFIG reserved bits set.");
        var c = new VideoConfig(
            s[0],
            BinaryPrimitives.ReadUInt16BigEndian(s[2..]), BinaryPrimitives.ReadUInt16BigEndian(s[4..]),
            BinaryPrimitives.ReadUInt16BigEndian(s[6..]), BinaryPrimitives.ReadUInt16BigEndian(s[8..]),
            BinaryPrimitives.ReadUInt16BigEndian(s[10..]), BinaryPrimitives.ReadUInt16BigEndian(s[12..]),
            BinaryPrimitives.ReadUInt16BigEndian(s[14..]), s[16] == 1,
            BinaryPrimitives.ReadUInt32BigEndian(s[18..]), s[1]);
        return c.IsValid ? c : throw new InvalidDataException("VMS1 VIDEO_CONFIG out of range.");
    }

    /// <summary><c>u8 codec || u8 channels || u32 rate</c>.</summary>
    public sealed record AudioConfig(int Channels, int Rate, byte Codec = AudioCodecPcmS16Le)
    {
        public bool IsValid => Codec == AudioCodecPcmS16Le && Channels is 1 or 2 && Rate is >= 8_000 and <= 96_000;
        /// <summary>Bytes per sample frame (all channels).</summary>
        public int FrameBytes => 2 * Channels;
    }

    public static byte[] EncodeAudioConfig(AudioConfig c)
    {
        if (!c.IsValid) throw new ArgumentException("VMS1 audio config out of range.");
        byte[] o = new byte[AudioConfigSize];
        o[0] = c.Codec;
        o[1] = (byte)c.Channels;
        BinaryPrimitives.WriteInt32BigEndian(o.AsSpan(2), c.Rate);
        return o;
    }

    public static AudioConfig DecodeAudioConfig(ReadOnlySpan<byte> s)
    {
        if (s.Length != AudioConfigSize) throw new InvalidDataException("VMS1 AUDIO_CONFIG has a bad length.");
        var c = new AudioConfig(s[1], BinaryPrimitives.ReadInt32BigEndian(s[2..]), s[0]);
        return c.IsValid ? c : throw new InvalidDataException("VMS1 AUDIO_CONFIG out of range.");
    }
}
