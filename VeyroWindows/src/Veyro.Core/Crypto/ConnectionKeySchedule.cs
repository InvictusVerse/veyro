using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Veyro.Core.Crypto;

/// <summary>Which key schedule a connection runs (04-PROTOCOL.md 4, ADR-058).</summary>
public enum LinkMode { Session, PairConfirm, PairQr }

/// <summary>The dialler is the initiator; the side that answered the preamble is the responder.</summary>
public enum LinkRole { Initiator, Responder }

/// <summary>
/// A per-connection X25519 key pair. Generated for one connection, never stored, never reused.
/// The private half never leaves this object; <see cref="Dispose"/> zeroes it.
/// Mirrors com.veyro.core.crypto.EphemeralKey.
/// </summary>
public sealed class EphemeralKey : IDisposable
{
    private readonly byte[] _privateKey;
    private bool _disposed;

    private EphemeralKey(byte[] privateKey)
    {
        _privateKey = privateKey;
        PublicKey = X25519Crypto.FromSeed(privateKey).PublicKey;
    }

    /// <summary>The public half (<c>epk</c> on the wire).</summary>
    public byte[] PublicKey { get; }

    public static EphemeralKey Generate() => new(RandomNumberGenerator.GetBytes(32));

    /// <summary>A fixed ephemeral key, for the test vectors only.</summary>
    internal static EphemeralKey FromPrivate(byte[] scalar)
    {
        if (scalar.Length != 32) throw new ArgumentException("X25519 scalar must be 32 bytes.", nameof(scalar));
        return new EphemeralKey((byte[])scalar.Clone());
    }

    /// <summary><c>X25519(private, peerEpk)</c>. Throws <see cref="CryptographicException"/> for a low-order point.</summary>
    public byte[] Agree(byte[] peerEpk)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        return X25519Crypto.SharedSecret(_privateKey, peerEpk);
    }

    public void Dispose()
    {
        _disposed = true;
        CryptographicOperations.ZeroMemory(_privateKey);
    }

    public override string ToString() => "EphemeralKey(<redacted>)";
}

/// <summary>
/// The keys of one connection in one phase (bootstrap or session), from this side's point of
/// view. <see cref="ChainKey"/> exists only in pairing modes, <see cref="ExpSecret"/> only in
/// session mode. Never logged: <see cref="ToString"/> is redacted.
/// </summary>
public sealed class LinkKeys : IDisposable
{
    internal LinkKeys(LinkMode mode, LinkRole role, string initiatorId, string responderId, byte[] transcript,
        byte[] sendKey, byte[] recvKey, byte[]? chainKey, byte[]? expSecret)
    {
        Mode = mode;
        Role = role;
        InitiatorId = initiatorId;
        ResponderId = responderId;
        Transcript = transcript;
        SendKey = sendKey;
        RecvKey = recvKey;
        ChainKey = chainKey;
        ExpSecret = expSecret;
    }

    public LinkMode Mode { get; }
    public LinkRole Role { get; }
    public string InitiatorId { get; }
    public string ResponderId { get; }
    public string LocalId => Role == LinkRole.Initiator ? InitiatorId : ResponderId;
    public string PeerId => Role == LinkRole.Initiator ? ResponderId : InitiatorId;
    public byte[] Transcript { get; }
    internal byte[] SendKey { get; }
    internal byte[] RecvKey { get; }
    internal byte[]? ChainKey { get; }
    internal byte[]? ExpSecret { get; }

    public void Dispose()
    {
        CryptographicOperations.ZeroMemory(SendKey);
        CryptographicOperations.ZeroMemory(RecvKey);
        if (ChainKey is not null) CryptographicOperations.ZeroMemory(ChainKey);
        if (ExpSecret is not null) CryptographicOperations.ZeroMemory(ExpSecret);
    }

    public override string ToString() => $"LinkKeys(mode={Mode}, role={Role}, <redacted>)";
}

/// <summary>
/// The v3 connection key schedule (04-PROTOCOL.md 4.1-4.6, ADR-058). Pure functions over SHA-256,
/// HKDF-SHA256 (RFC 5869) and HMAC-SHA256, byte for byte with
/// com.veyro.core.crypto.ConnectionKeySchedule; the v3 files under protocol/testvectors pin them.
/// Inputs are not wiped here; ikm and prk are. Callers wipe dh, staticKey and qrMix.
/// </summary>
public static class ConnectionKeySchedule
{
    public const string LabelSession = "veyro-link-v3";
    public const string LabelPairConfirm = "veyro-pair-confirm-v3";
    public const string LabelPairQr = "veyro-pair-qr-v3";
    public const string LabelStatic = "veyro-static-v3";
    public const string LabelExport = "veyro-export-v3";
    public const string LabelQrSalt = "veyro-qr-v3";
    public const string LabelQrProof = "veyro-qr-proof-v3";
    public const string LabelQrMix = "veyro-qr-mix-v3";

    public const int KeySize = 32;
    public const int MaxExportLength = 8160;
    public const int MaxExportLabel = 64;
    public const int MaxExportContext = 1024;

    /// <summary><c>SHA-256(u32be(len P) || P || u32be(len A) || A)</c> over the exact preamble and ack bytes.</summary>
    public static byte[] Transcript(ReadOnlySpan<byte> preamble, ReadOnlySpan<byte> ack)
    {
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        Span<byte> len = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(len, preamble.Length);
        sha.AppendData(len);
        sha.AppendData(preamble);
        BinaryPrimitives.WriteInt32BigEndian(len, ack.Length);
        sha.AppendData(len);
        sha.AppendData(ack);
        return sha.GetHashAndReset();
    }

    /// <summary><c>utf8("veyro-static-v3") || 0x00 || utf8(idI) || 0x00 || utf8(idR)</c>.</summary>
    public static byte[] StaticInfo(string idI, string idR) => Joined(LabelStatic, idI, idR);

    /// <summary><c>utf8(label) || 0x00 || utf8(role) || 0x00 || utf8(idI) || 0x00 || utf8(idR)</c>.</summary>
    public static byte[] Info(string label, string role, string idI, string idR) => Joined(label, role, idI, idR);

    /// <summary>Session mode: <c>ikm = dh || pairingSecret</c>, salt = transcript.</summary>
    public static LinkKeys Session(LinkRole role, string idI, string idR, byte[] transcript, byte[] dh, byte[] pairingSecret)
    {
        RequireSize(dh, nameof(dh));
        RequireSize(pairingSecret, nameof(pairingSecret));
        return Derive(LinkMode.Session, LabelSession, role, idI, idR, transcript, transcript, [.. dh, .. pairingSecret]);
    }

    /// <summary>Confirm pairing: <c>ikm = dh || staticKey</c>, salt = <c>SHA-256(transcript || nA || nB)</c>.</summary>
    public static LinkKeys PairConfirm(LinkRole role, string idI, string idR, byte[] transcript, byte[] nA, byte[] nB, byte[] dh, byte[] staticKey)
    {
        RequireSize(dh, nameof(dh));
        RequireSize(staticKey, nameof(staticKey));
        RequireSize(nA, nameof(nA));
        RequireSize(nB, nameof(nB));
        byte[] saltInput = [.. transcript, .. nA, .. nB];
        byte[] salt = SHA256.HashData(saltInput);
        return Derive(LinkMode.PairConfirm, LabelPairConfirm, role, idI, idR, transcript, salt, [.. dh, .. staticKey]);
    }

    /// <summary>QR pairing: <c>ikm = dh || staticKey || qrMix</c>, salt = transcript.</summary>
    public static LinkKeys PairQr(LinkRole role, string idI, string idR, byte[] transcript, byte[] dh, byte[] staticKey, byte[] qrMix)
    {
        RequireSize(dh, nameof(dh));
        RequireSize(staticKey, nameof(staticKey));
        RequireSize(qrMix, nameof(qrMix));
        return Derive(LinkMode.PairQr, LabelPairQr, role, idI, idR, transcript, transcript, [.. dh, .. staticKey, .. qrMix]);
    }

    /// <summary>
    /// The session keys after <c>pairOk</c>: <c>prk = Extract(transcript, chainKey || newPairingSecret)</c>
    /// with veyro-link-v3 labels. Wipes <c>boot.ChainKey</c>; the caller disposes the rest.
    /// </summary>
    public static LinkKeys AfterPairing(LinkKeys boot, byte[] newPairingSecret)
    {
        byte[] chain = boot.ChainKey ?? throw new ArgumentException("AfterPairing needs bootstrap (pairing-mode) keys.", nameof(boot));
        RequireSize(newPairingSecret, nameof(newPairingSecret));
        LinkKeys keys = Derive(LinkMode.Session, LabelSession, boot.Role, boot.InitiatorId, boot.ResponderId,
            boot.Transcript, boot.Transcript, [.. chain, .. newPairingSecret]);
        CryptographicOperations.ZeroMemory(chain);
        return keys;
    }

    /// <summary>
    /// The key exporter (04-PROTOCOL.md 5.4): <c>HKDF-Expand(expSecret, info, length)</c> with
    /// <c>info = u16be(15) || "veyro-export-v3" || u16be(len label) || label || u16be(len ctx) || ctx</c>.
    /// </summary>
    public static byte[] Export(byte[] expSecret, string label, byte[] context, int length)
    {
        ArgumentNullException.ThrowIfNull(label);
        ArgumentNullException.ThrowIfNull(context);
        if (label.Length is < 1 or > MaxExportLabel || label.Any(c => c < 0x21 || c > 0x7E))
            throw new ArgumentException($"Export label must be 1..{MaxExportLabel} printable ASCII characters.", nameof(label));
        if (context.Length > MaxExportContext)
            throw new ArgumentException($"Export context must be at most {MaxExportContext} bytes.", nameof(context));
        if (length is < 1 or > MaxExportLength)
            throw new ArgumentException($"Export length must be 1..{MaxExportLength}.", nameof(length));
        byte[] prefix = Encoding.ASCII.GetBytes(LabelExport);
        byte[] labelBytes = Encoding.ASCII.GetBytes(label);
        byte[] info = [.. U16(prefix.Length), .. prefix, .. U16(labelBytes.Length), .. labelBytes, .. U16(context.Length), .. context];
        return Hkdf.Expand(expSecret, info, length);
    }

    /// <summary><c>qrPrk = HKDF-Extract(utf8("veyro-qr-v3"), qrSecret)</c>.</summary>
    public static byte[] QrPrk(byte[] qrSecret) => Hkdf.Extract(Encoding.ASCII.GetBytes(LabelQrSalt), qrSecret);

    /// <summary><c>proofKey = HKDF-Expand(qrPrk, "veyro-qr-proof-v3", 32)</c>.</summary>
    public static byte[] QrProofKey(byte[] qrSecret)
    {
        byte[] prk = QrPrk(qrSecret);
        try { return Hkdf.Expand(prk, Encoding.ASCII.GetBytes(LabelQrProof), KeySize); }
        finally { CryptographicOperations.ZeroMemory(prk); }
    }

    /// <summary><c>qrMix = HKDF-Expand(qrPrk, "veyro-qr-mix-v3", 32)</c>; enters the QR pairing ikm.</summary>
    public static byte[] QrMix(byte[] qrSecret)
    {
        byte[] prk = QrPrk(qrSecret);
        try { return Hkdf.Expand(prk, Encoding.ASCII.GetBytes(LabelQrMix), KeySize); }
        finally { CryptographicOperations.ZeroMemory(prk); }
    }

    /// <summary><c>HMAC-SHA256(proofKey, "veyro-qr-proof-v3" || 0x00 || idPhone || 0x00 || idPc || 0x00 || transcript)</c>.</summary>
    public static byte[] QrProof(byte[] proofKey, string idPhone, string idPc, byte[] transcript)
    {
        byte[] message = [.. Joined(LabelQrProof, idPhone, idPc), 0, .. transcript];
        return HMACSHA256.HashData(proofKey, message);
    }

    private static LinkKeys Derive(LinkMode mode, string label, LinkRole role, string idI, string idR,
        byte[] transcript, byte[] salt, byte[] ikm)
    {
        byte[] prk = Hkdf.Extract(salt, ikm);
        try
        {
            byte[] i2r = Hkdf.Expand(prk, Info(label, "i2r", idI, idR), KeySize);
            byte[] r2i = Hkdf.Expand(prk, Info(label, "r2i", idI, idR), KeySize);
            byte[]? exp = mode == LinkMode.Session ? Hkdf.Expand(prk, Info(label, "exp", idI, idR), KeySize) : null;
            byte[]? chain = mode != LinkMode.Session ? Hkdf.Expand(prk, Info(label, "chain", idI, idR), KeySize) : null;
            bool initiator = role == LinkRole.Initiator;
            return new LinkKeys(mode, role, idI, idR, (byte[])transcript.Clone(),
                initiator ? i2r : r2i, initiator ? r2i : i2r, chain, exp);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(prk);
            CryptographicOperations.ZeroMemory(ikm);
        }
    }

    private static byte[] Joined(params string[] parts)
    {
        using var ms = new MemoryStream();
        for (int i = 0; i < parts.Length; i++)
        {
            if (i > 0) ms.WriteByte(0);
            byte[] b = Encoding.UTF8.GetBytes(parts[i]);
            ms.Write(b, 0, b.Length);
        }
        return ms.ToArray();
    }

    private static byte[] U16(int value) => [(byte)(value >> 8), (byte)value];

    private static void RequireSize(byte[] value, string name)
    {
        if (value is null || value.Length != KeySize)
            throw new ArgumentException($"{name} must be {KeySize} bytes.", name);
    }
}
