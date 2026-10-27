namespace Veyro.Core.Protocol;

/// <summary>
/// Veyro Link Protocol (VLP) v1 constants.
///
/// Mirrors com.veyro.core.protocol.Vlp. Every value here is normative and must match the
/// Kotlin side byte-for-byte; see ProjectInfo/04-PROTOCOL.md.
///
/// NOTE: the Kotlin Vlp object still carries vestigial Noise/CBOR constants
/// (NOISE_PATTERN_*, PROLOGUE_PREFIX, MAX_NOISE_*, FRAME_CONTROL/BULK/KEEPALIVE) that no
/// shipping code path uses since ADR-020 replaced the handshake with the Invox-derived link
/// layer. They are deliberately NOT mirrored here.
/// </summary>
public static class ProtocolConstants
{
    public const int VlpVersion = 1;

    // ── Discovery ──────────────────────────────────────────────────────────────────────
    public const string MdnsServiceType = "_veyro._tcp";
    public const string MdnsDomain = "local.";
    public const int UdpDiscoveryPort = 47653;
    public const string UdpMulticastGroup = "239.255.42.99";
    public const long UdpBeaconIntervalMillis = 1_000L;

    /// <summary>"VEYR" — the first four bytes of every UDP beacon.</summary>
    public static ReadOnlySpan<byte> UdpMagic => "VEYR"u8;

    public const byte BeaconTypeQuery = 0x01;
    public const byte BeaconTypeAnnounce = 0x02;

    // ── HKDF info strings (changing any of these is wire-breaking) ─────────────────────
    public const string InfoSas = "veyro-sas-v1";
    public const string InfoPairingSecret = "veyro-pair-v1";
    public const string InfoPresenceToken = "veyro-presence-v1";

    // ── Timeouts ───────────────────────────────────────────────────────────────────────
    public const long HandshakeTimeoutMillis = 10_000L;
    public const long IdleSessionTimeoutMillis = 60_000L;
    public const long KeepaliveIntervalMillis = 20_000L;
    public const long PairingWindowMillis = 120_000L;

    // ── Bulk transfer ──────────────────────────────────────────────────────────────────
    public const int DefaultChunkSize = 4 * 1024 * 1024;
    public const int MinChunkSize = 256 * 1024;
    public const int MaxChunkSize = 16 * 1024 * 1024;

    public const string PartSuffix = ".veyropart";
    public const string MetaSuffix = ".veyrometa";

    // ── Limits ─────────────────────────────────────────────────────────────────────────
    public const int MaxDisplayNameBytes = 32;
    public const int MaxRelativePathBytes = 1024;

    /// <summary>Application-level bound enforced by PeerConnection (ADR-021).</summary>
    public const int MaxFrameSizeBytes = 24 * 1024 * 1024;
    public const int MaxPreambleBytes = 64 * 1024;

    /// <summary>Picks the chunk size both peers can handle.</summary>
    public static int NegotiateChunkSize(int ours, int theirs)
        => Math.Clamp(Math.Min(ours, theirs), MinChunkSize, MaxChunkSize);
}

/// <summary>
/// Protocol-level error codes (04-PROTOCOL.md section 9).
/// <c>Fatal</c> means the session must be closed; non-fatal fails one item only.
/// </summary>
public sealed record VlpError(string Code, bool Fatal)
{
    public static readonly VlpError VersionUnsupported = new("VERSION_UNSUPPORTED", true);
    public static readonly VlpError NotPaired = new("NOT_PAIRED", true);
    public static readonly VlpError HandshakeFailed = new("HANDSHAKE_FAILED", true);
    public static readonly VlpError AuthTimeout = new("AUTH_TIMEOUT", true);
    public static readonly VlpError FrameInvalid = new("FRAME_INVALID", true);
    public static readonly VlpError OfferRejected = new("OFFER_REJECTED", false);
    public static readonly VlpError PathRejected = new("PATH_REJECTED", false);
    public static readonly VlpError HashMismatch = new("HASH_MISMATCH", false);
    public static readonly VlpError NoSpace = new("NO_SPACE", false);
    public static readonly VlpError Cancelled = new("CANCELLED", false);
    public static readonly VlpError Internal = new("INTERNAL", false);

    public static IReadOnlyList<VlpError> All =>
    [
        VersionUnsupported, NotPaired, HandshakeFailed, AuthTimeout, FrameInvalid,
        OfferRejected, PathRejected, HashMismatch, NoSpace, Cancelled, Internal
    ];

    public static VlpError FromWire(string? code)
        => All.FirstOrDefault(e => e.Code == code) ?? Internal;
}
