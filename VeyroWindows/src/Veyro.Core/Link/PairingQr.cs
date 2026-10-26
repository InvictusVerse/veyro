using System.Globalization;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Veyro.Core.Models;

namespace Veyro.Core.Link;

/// <summary>The decoded content of a pairing QR code (04-PROTOCOL.md 4.2, ADR-030).</summary>
public sealed record PairingQrPayload(
    string DeviceId,
    string Name,
    IReadOnlyList<IPEndPoint> Endpoints,
    string Code,
    long ExpiresAtUnixSeconds)
{
    /// <summary>The phone's rule: reject when <c>now &gt; e + 600</c>, which tolerates clock skew.</summary>
    public bool IsExpired(DateTimeOffset now)
        => now.ToUnixTimeSeconds() > ExpiresAtUnixSeconds + PairingQr.ClockSkewSeconds;

    /// <summary>Never print the code: records would otherwise leak it into any log line.</summary>
    public override string ToString() => $"PairingQrPayload(id={DeviceId}, endpoints={Endpoints.Count})";
}

/// <summary>
/// Builds and parses <c>veyro:pair?v=1&amp;id=..&amp;n=..&amp;a=..&amp;c=..&amp;e=..</c>.
/// Out of band, not on the wire: the code feeds the existing PIN bootstrap unchanged, so
/// <c>VLP_VERSION</c> does not move. The content holds the pairing code and is never logged.
/// </summary>
public static class PairingQr
{
    public const string Prefix = "veyro:pair?";
    public const int FormatVersion = 1;
    public const int MaxNameBytes = 64;
    public const int MaxEndpoints = 4;
    public const long ClockSkewSeconds = 600;
    public const int CodeLength = 6;

    public static string Build(PairingQrPayload payload)
    {
        if (Models.DeviceId.ParseOrNull(payload.DeviceId) is not { } id)
            throw new ArgumentException("Invalid device id.", nameof(payload));
        if (!IsValidCode(payload.Code))
            throw new ArgumentException("The pairing code must be exactly 6 digits.", nameof(payload));
        List<IPEndPoint> endpoints = payload.Endpoints.Take(MaxEndpoints).ToList();
        if (endpoints.Count == 0)
            throw new ArgumentException("At least one endpoint is required.", nameof(payload));
        foreach (IPEndPoint endpoint in endpoints)
        {
            if (!IsAdvertisable(endpoint.Address) || endpoint.Port is < 1 or > 65535)
                throw new ArgumentException("Endpoints must be private or link-local IPv4.", nameof(payload));
        }

        var builder = new StringBuilder(Prefix);
        builder.Append("v=").Append(FormatVersion.ToString(CultureInfo.InvariantCulture));
        builder.Append("&id=").Append(id.Value);
        // The phone rejects control characters in the name, so they are dropped here.
        string name = new(payload.Name.Where(c => !char.IsControl(c)).ToArray());
        builder.Append("&n=").Append(Uri.EscapeDataString(TruncateName(name)));
        builder.Append("&a=").Append(string.Join(',', endpoints.Select(e =>
            $"{e.Address}:{e.Port.ToString(CultureInfo.InvariantCulture)}")));
        builder.Append("&c=").Append(payload.Code);
        builder.Append("&e=").Append(payload.ExpiresAtUnixSeconds.ToString(CultureInfo.InvariantCulture));
        return builder.ToString();
    }

    /// <summary>
    /// Total parser: returns null for anything that breaks a rule in 4.2. Unknown keys are ignored
    /// so a later format can add fields; a repeated key is rejected. Expiry is not checked here
    /// (<see cref="PairingQrPayload.IsExpired"/>).
    /// </summary>
    public static PairingQrPayload? TryParse(string? text)
    {
        if (text is null || text.Length > 2048 || !text.StartsWith(Prefix, StringComparison.Ordinal)) return null;

        var fields = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (string part in text[Prefix.Length..].Split('&'))
        {
            int eq = part.IndexOf('=');
            if (eq <= 0) return null;
            if (!fields.TryAdd(part[..eq], part[(eq + 1)..])) return null;
        }

        if (!fields.TryGetValue("v", out string? v) || v != "1") return null;
        if (!fields.TryGetValue("id", out string? rawId) || Models.DeviceId.ParseOrNull(rawId) is not { } id) return null;
        if (!fields.TryGetValue("c", out string? code) || !IsValidCode(code)) return null;
        if (!fields.TryGetValue("e", out string? rawExpiry) ||
            !long.TryParse(rawExpiry, NumberStyles.None, CultureInfo.InvariantCulture, out long expiry)) return null;
        if (!fields.TryGetValue("a", out string? rawEndpoints)) return null;

        string[] entries = rawEndpoints.Split(',');
        if (entries.Length is < 1 or > MaxEndpoints) return null;
        var endpoints = new List<IPEndPoint>(entries.Length);
        foreach (string entry in entries)
        {
            if (TryParseEndpoint(entry) is not { } endpoint) return null;
            endpoints.Add(endpoint);
        }

        string name = string.Empty;
        if (fields.TryGetValue("n", out string? rawName))
        {
            // Like the Android parser: '+' is a space, a literal plus is %2B.
            try { name = Uri.UnescapeDataString(rawName.Replace('+', ' ')); }
            catch (UriFormatException) { return null; }
            if (Encoding.UTF8.GetByteCount(name) > MaxNameBytes) return null;
        }

        return new PairingQrPayload(id.Value, name, endpoints, code, expiry);
    }

    /// <summary>
    /// Cuts <paramref name="name"/> to at most 64 UTF-8 bytes without splitting a character
    /// (a surrogate pair counts as one character).
    /// </summary>
    public static string TruncateName(string? name)
    {
        if (string.IsNullOrEmpty(name)) return string.Empty;
        if (Encoding.UTF8.GetByteCount(name) <= MaxNameBytes) return name;

        var builder = new StringBuilder();
        int bytes = 0;
        foreach (Rune rune in name.EnumerateRunes())
        {
            int length = rune.Utf8SequenceLength;
            if (bytes + length > MaxNameBytes) break;
            builder.Append(rune.ToString());
            bytes += length;
        }
        return builder.ToString();
    }

    public static bool IsValidCode(string? code)
        => code is { Length: CodeLength } && code.All(char.IsAsciiDigit);

    /// <summary>Private (10/8, 172.16/12, 192.168/16) or link-local (169.254/16) IPv4 only.</summary>
    public static bool IsAdvertisable(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        byte[] b = address.GetAddressBytes();
        return b[0] == 10 ||
               (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
               (b[0] == 192 && b[1] == 168) ||
               IsLinkLocal(address);
    }

    private static bool IsLinkLocal(IPAddress address)
    {
        byte[] b = address.GetAddressBytes();
        return b.Length == 4 && b[0] == 169 && b[1] == 254;
    }

    /// <summary>
    /// Up to four advertisable IPv4 endpoints for the control listener on <paramref name="port"/>.
    /// Interfaces with a default gateway (the Wi-Fi or Ethernet the phone is likely on) come
    /// first, link-local addresses last, so virtual adapters do not crowd out the real network.
    /// </summary>
    public static IReadOnlyList<IPEndPoint> LocalEndpoints(int port)
    {
        if (port is < 1 or > 65535) return [];
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(nic => nic.OperationalStatus == OperationalStatus.Up &&
                              nic.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
                .SelectMany(nic =>
                {
                    IPInterfaceProperties properties = nic.GetIPProperties();
                    bool hasGateway = properties.GatewayAddresses.Any(g =>
                        g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                    return properties.UnicastAddresses.Select(u => (u.Address, hasGateway));
                })
                .Where(x => IsAdvertisable(x.Address))
                .OrderBy(x => IsLinkLocal(x.Address))
                .ThenByDescending(x => x.hasGateway)
                .Select(x => x.Address)
                .Distinct()
                .Take(MaxEndpoints)
                .Select(address => new IPEndPoint(address, port))
                .ToList();
        }
        catch (NetworkInformationException)
        {
            return [];
        }
    }

    private static IPEndPoint? TryParseEndpoint(string entry)
    {
        int colon = entry.LastIndexOf(':');
        if (colon <= 0 || colon == entry.Length - 1) return null;
        string host = entry[..colon];
        string rawPort = entry[(colon + 1)..];
        if (rawPort.Length > 5 || !int.TryParse(rawPort, NumberStyles.None, CultureInfo.InvariantCulture, out int port) ||
            port is < 1 or > 65535) return null;
        if (TryParseStrictIPv4(host) is not { } address || !IsAdvertisable(address)) return null;
        return new IPEndPoint(address, port);
    }

    /// <summary>Exactly four dotted decimal octets. IPAddress.TryParse also accepts "10" or "10.1".</summary>
    private static IPAddress? TryParseStrictIPv4(string host)
    {
        string[] octets = host.Split('.');
        if (octets.Length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++)
        {
            string octet = octets[i];
            if (octet.Length is < 1 or > 3 || !octet.All(char.IsAsciiDigit)) return null;
            int value = int.Parse(octet, CultureInfo.InvariantCulture);
            if (value > 255) return null;
            bytes[i] = (byte)value;
        }
        return new IPAddress(bytes);
    }
}
