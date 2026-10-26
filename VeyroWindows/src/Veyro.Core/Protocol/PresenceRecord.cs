using System.Text;
using Veyro.Core.Models;

namespace Veyro.Core.Protocol;

/// <summary>
/// Base64url (RFC 4648 section 5) without padding. Mirrors com.veyro.core.protocol.Base64Url.
/// </summary>
public static class Base64Url
{
    public static string Encode(ReadOnlySpan<byte> data)
        => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[]? DecodeOrNull(string? encoded)
    {
        if (string.IsNullOrEmpty(encoded)) return null;
        string s = encoded.Replace('-', '+').Replace('_', '/');
        switch (s.Length % 4)
        {
            case 2: s += "=="; break;
            case 3: s += "="; break;
            case 1: return null;
        }
        try { return Convert.FromBase64String(s); }
        catch (FormatException) { return null; }
    }
}

/// <summary>
/// The presence record carried by both discovery mechanisms (04-PROTOCOL.md section 3).
///
/// **Everything in this type is attacker-controlled.** It answers one question only: which
/// IP:port might be a Veyro peer? It carries no authority. An attacker can broadcast a record
/// claiming your PC's deviceId all day; the handshake then fails because they lack the private
/// key (REQ-D07).
///
/// Mirrors com.veyro.core.protocol.PresenceRecord.
/// </summary>
public sealed record PresenceRecord
{
    public const int HashLength = 8;
    public const int TokenLength = 8;

    // Wire keys. Changing one is wire-breaking and requires a VLP_VERSION bump.
    public const string KeyVersion = "v";
    public const string KeyDeviceId = "id";
    public const string KeyPlatform = "pf";
    public const string KeyCapabilities = "cap";
    public const string KeyPubkeyHash = "pk8";
    public const string KeyToken = "tok";
    public const string KeyName = "n";
    public const string KeyPort = "p";
    public const string KeyPublicKey = "pk";

    public required int Version { get; init; }
    public required string DeviceId { get; init; }
    public required PlatformType Platform { get; init; }
    public required int CapabilityBits { get; init; }

    /// <summary><c>SHA-256("veyro-pk-v1" ‖ staticPublic)[0..7]</c>. Lets a browser pre-filter cheaply.</summary>
    public required byte[] PublicKeyHash { get; init; }

    /// <summary>Rotating presence token (section 3.4). 8 bytes.</summary>
    public required byte[] PresenceToken { get; init; }

    /// <summary>Display name. <b>Absent in private mode</b> so a stranger cannot harvest it (REQ-D06).</summary>
    public string? Name { get; init; }

    /// <summary>TCP port the peer is listening on. Ephemeral and advertised, never fixed.</summary>
    public required int Port { get; init; }

    /// <summary>
    /// The full X25519 static public key. Publishing it is safe — a public key is public, and
    /// knowing it does not permit impersonation. What it does not do is *authenticate* it, which
    /// is exactly why the 6-digit confirmation exists. Absent in private mode.
    /// </summary>
    public byte[]? PublicKey { get; init; }

    public void Validate()
    {
        if (PublicKeyHash.Length != HashLength)
            throw new ArgumentException($"publicKeyHash must be {HashLength} bytes");
        if (PresenceToken.Length != TokenLength)
            throw new ArgumentException($"presenceToken must be {TokenLength} bytes");
        if (Port is < 1 or > 65535)
            throw new ArgumentException($"port out of range: {Port}");
        if (PublicKey is not null && PublicKey.Length != 32)
            throw new ArgumentException("public key must be 32 bytes");
    }
}

public enum PresenceRejectReason
{
    Empty, TooLarge, MalformedPair, DuplicateKey, MissingRequired, BadVersion,
    BadDeviceId, BadCapabilities, BadHash, BadToken, BadPort, NameTooLong,
    NameInvalid, BadPublicKey
}

public abstract record PresenceResult
{
    public sealed record Ok(PresenceRecord Record) : PresenceResult;
    public sealed record Invalid(PresenceRejectReason Reason, string Detail) : PresenceResult;
}

/// <summary>
/// Encodes and decodes <see cref="PresenceRecord"/> as <c>key=value</c> pairs.
///
/// One encoding serves both transports: mDNS TXT records are natively key=value, so reusing that
/// form for the UDP beacon payload means one encoding to implement, test, and mirror rather than
/// two representations of the same record. It is also readable in a packet capture.
///
/// The decoder parses hostile input: every field is bounds-checked, unknown keys are ignored for
/// forward compatibility, duplicate keys are rejected, and a malformed record yields a typed
/// failure rather than a partially-populated object.
/// </summary>
public static class PresenceCodec
{
    /// <summary>Mirrors mDNS TXT size reality; also caps a UDP beacon payload.</summary>
    public const int MaxEncodedBytes = 400;

    /// <summary>
    /// Unicode bidirectional overrides. These reorder rendered text, so a device could advertise
    /// a name that *displays* as a trusted one while being something else (REQ-U04).
    /// </summary>
    private static readonly char[] BidiOverrides =
    [
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069'
    ];

    public static List<KeyValuePair<string, string>> EncodeToPairs(PresenceRecord r)
    {
        var list = new List<KeyValuePair<string, string>>(9)
        {
            new(PresenceRecord.KeyVersion, r.Version.ToString()),
            new(PresenceRecord.KeyDeviceId, r.DeviceId),
            new(PresenceRecord.KeyPlatform, ToWire(r.Platform)),
            new(PresenceRecord.KeyCapabilities, Convert.ToString(r.CapabilityBits, 16)),
            new(PresenceRecord.KeyPubkeyHash, Convert.ToHexString(r.PublicKeyHash).ToLowerInvariant()),
            new(PresenceRecord.KeyToken, Convert.ToHexString(r.PresenceToken).ToLowerInvariant()),
            new(PresenceRecord.KeyPort, r.Port.ToString())
        };

        // Omitted entirely in private mode, not sent empty: an empty value still tells an observer
        // that a name exists and is being withheld.
        if (r.Name is not null)
            list.Add(new(PresenceRecord.KeyName, r.Name));
        if (r.PublicKey is not null)
            list.Add(new(PresenceRecord.KeyPublicKey, Base64Url.Encode(r.PublicKey)));

        return list;
    }

    /// <summary>Newline-separated form, used as the UDP beacon payload.</summary>
    public static byte[] EncodeToBytes(PresenceRecord record)
        => Encoding.UTF8.GetBytes(
            string.Join("\n", EncodeToPairs(record).Select(kv => $"{kv.Key}={kv.Value}")));

    public static PresenceResult DecodeFromBytes(ReadOnlySpan<byte> bytes)
    {
        if (bytes.IsEmpty) return new PresenceResult.Invalid(PresenceRejectReason.Empty, "no bytes");
        if (bytes.Length > MaxEncodedBytes)
            return new PresenceResult.Invalid(PresenceRejectReason.TooLarge, $"{bytes.Length} bytes");

        string text;
        try { text = Encoding.UTF8.GetString(bytes); }
        catch { return new PresenceResult.Invalid(PresenceRejectReason.MalformedPair, "not valid UTF-8"); }

        var pairs = new List<KeyValuePair<string, string>>();
        foreach (string line in text.Split('\n'))
        {
            if (line.Length == 0) continue;
            int eq = line.IndexOf('=');
            if (eq <= 0)
                return new PresenceResult.Invalid(PresenceRejectReason.MalformedPair, "no '=' in a pair");
            pairs.Add(new(line[..eq], line[(eq + 1)..]));
        }

        return DecodeFromPairs(pairs);
    }

    public static PresenceResult DecodeFromPairs(IReadOnlyList<KeyValuePair<string, string>> pairs)
    {
        if (pairs.Count == 0) return new PresenceResult.Invalid(PresenceRejectReason.Empty, "no pairs");

        var map = new Dictionary<string, string>(pairs.Count, StringComparer.Ordinal);
        foreach (var (key, value) in pairs)
        {
            // A duplicate key means two conflicting claims. Picking either silently would let an
            // attacker get a different reading on the two platforms.
            if (!map.TryAdd(key, value))
                return new PresenceResult.Invalid(PresenceRejectReason.DuplicateKey, key);
        }

        if (!map.TryGetValue(PresenceRecord.KeyVersion, out string? versionRaw)
            || !int.TryParse(versionRaw, out int version))
            return new PresenceResult.Invalid(PresenceRejectReason.MissingRequired, PresenceRecord.KeyVersion);

        // Refuse rather than guess (REQ-S08). Not an error to surface for every stray packet —
        // it just means "not a peer we can talk to".
        if (version != VlpConstants.VlpVersion)
            return new PresenceResult.Invalid(PresenceRejectReason.BadVersion, $"version {version}");

        // Untrusted input: the id must actually be a well-formed deviceId, not merely present.
        // Kotlin uses DeviceId.parseOrNull here; accepting a malformed id on one platform and
        // rejecting it on the other is exactly the kind of divergence the vectors exist to catch.
        var parsedId = DeviceId.ParseOrNull(map.GetValueOrDefault(PresenceRecord.KeyDeviceId));
        if (parsedId is null)
            return new PresenceResult.Invalid(
                PresenceRejectReason.BadDeviceId,
                map.GetValueOrDefault(PresenceRecord.KeyDeviceId) ?? "absent");
        string deviceId = parsedId.Value.Value;

        if (!map.TryGetValue(PresenceRecord.KeyCapabilities, out string? capsRaw))
            return new PresenceResult.Invalid(PresenceRejectReason.MissingRequired, PresenceRecord.KeyCapabilities);
        int caps;
        try { caps = Convert.ToInt32(capsRaw, 16); }
        catch { return new PresenceResult.Invalid(PresenceRejectReason.BadCapabilities, capsRaw); }

        byte[]? hash = HexOrNull(map.GetValueOrDefault(PresenceRecord.KeyPubkeyHash));
        if (hash is null || hash.Length != PresenceRecord.HashLength)
            return new PresenceResult.Invalid(
                PresenceRejectReason.BadHash, map.GetValueOrDefault(PresenceRecord.KeyPubkeyHash) ?? "absent");

        byte[]? token = HexOrNull(map.GetValueOrDefault(PresenceRecord.KeyToken));
        if (token is null || token.Length != PresenceRecord.TokenLength)
            return new PresenceResult.Invalid(
                PresenceRejectReason.BadToken, map.GetValueOrDefault(PresenceRecord.KeyToken) ?? "absent");

        if (!map.TryGetValue(PresenceRecord.KeyPort, out string? portRaw))
            return new PresenceResult.Invalid(PresenceRejectReason.MissingRequired, PresenceRecord.KeyPort);
        if (!int.TryParse(portRaw, out int port) || port is < 1 or > 65535)
            return new PresenceResult.Invalid(PresenceRejectReason.BadPort, portRaw);

        byte[]? publicKey = null;
        if (map.TryGetValue(PresenceRecord.KeyPublicKey, out string? pkRaw))
        {
            publicKey = Base64Url.DecodeOrNull(pkRaw);
            if (publicKey is null || publicKey.Length != 32)
                return new PresenceResult.Invalid(PresenceRejectReason.BadPublicKey, "not a 32-byte key");
        }

        string? name = map.GetValueOrDefault(PresenceRecord.KeyName);
        if (name is not null)
        {
            if (Encoding.UTF8.GetByteCount(name) > VlpConstants.MaxDisplayNameBytes)
                return new PresenceResult.Invalid(PresenceRejectReason.NameTooLong, $"{name.Length} chars");

            foreach (char c in name)
            {
                if (c < 0x20 || c == 0x7F || Array.IndexOf(BidiOverrides, c) >= 0)
                    return new PresenceResult.Invalid(
                        PresenceRejectReason.NameInvalid, "control or bidi character");
            }
        }

        return new PresenceResult.Ok(new PresenceRecord
        {
            Version = version,
            DeviceId = deviceId,
            Platform = FromWire(map.GetValueOrDefault(PresenceRecord.KeyPlatform)),
            CapabilityBits = caps,
            PublicKeyHash = hash,
            PresenceToken = token,
            Name = name,
            Port = port,
            PublicKey = publicKey
        });
    }

    private static byte[]? HexOrNull(string? hex)
    {
        if (string.IsNullOrEmpty(hex) || hex.Length % 2 != 0) return null;
        try { return Convert.FromHexString(hex); }
        catch (FormatException) { return null; }
    }

    internal static string ToWire(PlatformType p) => p switch
    {
        PlatformType.Android => "android",
        PlatformType.Windows => "windows",
        _ => "unknown"
    };

    internal static PlatformType FromWire(string? value) => value?.ToLowerInvariant() switch
    {
        "android" => PlatformType.Android,
        "windows" => PlatformType.Windows,
        _ => PlatformType.Unknown
    };
}
