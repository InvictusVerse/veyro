using System.Text;

namespace Veyro.Core.Handoff;

/// <summary>A paired phone as the island sees it (ADR-076). No keys, fingerprints or addresses.</summary>
public sealed record IslandDevice(string Id, string Name, bool Connected);

/// <summary>The running transfer, if any. <see cref="Permille"/> is 0..1000.</summary>
public sealed record IslandTransfer(bool Incoming, string Stage, int Permille, string Peer);

public sealed record IslandStatus(IReadOnlyList<IslandDevice> Devices, IslandTransfer? Transfer);

public enum IslandOp
{
    /// <summary>Keep the connection open; Veyro pushes a status frame now and on every change.</summary>
    Status,
    Text,
    Files
}

/// <summary><see cref="DeviceId"/> null means "the connected phones" (text) or "ask / the only one" (files).</summary>
public sealed record IslandRequest(IslandOp Op, string? DeviceId, string? Text, IReadOnlyList<string> Paths);

public sealed record IslandResult(bool Ok, string Message);

/// <summary>
/// ADR-076: the local bridge between Veyro and the Islune dynamic island ("VIB1"). Frames reuse
/// <see cref="HandoffCodec"/>'s 4-byte length prefix; the payload is UTF-8 text, simple enough
/// for Islune to parse without a JSON library:
/// <code>
/// request : "VIB1 status *" | "VIB1 text &lt;id|*&gt;\n&lt;text&gt;" | "VIB1 files &lt;id|*&gt;\n&lt;path&gt;\n&lt;path&gt;..."
/// status  : "VIB1 status\n" + "device\t&lt;id&gt;\t&lt;0|1&gt;\t&lt;name&gt;\n"... + "transfer\t&lt;in|out&gt;\t&lt;stage&gt;\t&lt;permille&gt;\t&lt;peer&gt;\n"?
/// result  : "VIB1 result ok|fail\n&lt;message&gt;"
/// </code>
/// Local IPC between two processes of the same user, not part of VLP. Decoding is strict.
/// Text and paths are user data: never logged.
/// </summary>
public static class IslandBridgeCodec
{
    public const string Magic = "VIB1";
    public const int MaxNameChars = 64;
    public const int MaxDevices = 16;
    public const int MaxMessageChars = 200;

    public static byte[] EncodeRequest(IslandRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        Validate(request);
        var text = new StringBuilder($"{Magic} {OpName(request.Op)} {request.DeviceId ?? "*"}");
        switch (request.Op)
        {
            case IslandOp.Text:
                text.Append('\n').Append(request.Text);
                break;
            case IslandOp.Files:
                foreach (string path in request.Paths) text.Append('\n').Append(path);
                break;
        }
        return Encoding.UTF8.GetBytes(text.ToString());
    }

    public static IslandRequest DecodeRequest(ReadOnlySpan<byte> payload)
    {
        string text = Utf8(payload);
        int newline = text.IndexOf('\n');
        string header = newline < 0 ? text : text[..newline];
        string body = newline < 0 ? string.Empty : text[(newline + 1)..];
        string[] parts = header.Split(' ');
        if (parts.Length != 3 || parts[0] != Magic) throw new FormatException("Not an island request.");
        IslandOp op = parts[1] switch
        {
            "status" => IslandOp.Status,
            "text" => IslandOp.Text,
            "files" => IslandOp.Files,
            _ => throw new FormatException("Unknown island request.")
        };
        string? device = parts[2] == "*" ? null : parts[2];
        var request = op switch
        {
            IslandOp.Status when newline < 0 => new IslandRequest(op, device, null, []),
            IslandOp.Text when newline >= 0 => new IslandRequest(op, device, body, []),
            IslandOp.Files when newline >= 0 => new IslandRequest(op, device, null, body.Split('\n')),
            _ => throw new FormatException("Malformed island request.")
        };
        Validate(request);
        return request;
    }

    public static byte[] EncodeStatus(IslandStatus status)
    {
        ArgumentNullException.ThrowIfNull(status);
        var text = new StringBuilder($"{Magic} status\n");
        foreach (IslandDevice device in status.Devices.Take(MaxDevices))
        {
            if (!HandoffCodec.IsPlausibleDeviceId(device.Id)) continue;
            text.Append("device\t").Append(device.Id).Append('\t').Append(device.Connected ? '1' : '0')
                .Append('\t').Append(Clean(device.Name)).Append('\n');
        }
        if (status.Transfer is { } transfer)
        {
            text.Append("transfer\t").Append(transfer.Incoming ? "in" : "out").Append('\t')
                .Append(Clean(transfer.Stage)).Append('\t').Append(Math.Clamp(transfer.Permille, 0, 1000))
                .Append('\t').Append(Clean(transfer.Peer)).Append('\n');
        }
        return Encoding.UTF8.GetBytes(text.ToString());
    }

    public static IslandStatus DecodeStatus(ReadOnlySpan<byte> payload)
    {
        string[] lines = Utf8(payload).Split('\n');
        if (lines[0] != $"{Magic} status") throw new FormatException("Not an island status.");
        var devices = new List<IslandDevice>();
        IslandTransfer? transfer = null;
        foreach (string line in lines.Skip(1).Where(line => line.Length > 0))
        {
            string[] fields = line.Split('\t');
            if (fields[0] == "device" && fields.Length == 4 && HandoffCodec.IsPlausibleDeviceId(fields[1]) &&
                fields[2] is "0" or "1" && devices.Count < MaxDevices)
                devices.Add(new IslandDevice(fields[1], fields[3], fields[2] == "1"));
            else if (fields[0] == "transfer" && fields.Length == 5 && fields[1] is "in" or "out" &&
                     int.TryParse(fields[3], out int permille) && permille is >= 0 and <= 1000)
                transfer = new IslandTransfer(fields[1] == "in", fields[2], permille, fields[4]);
            else throw new FormatException("Malformed island status line.");
        }
        return new IslandStatus(devices, transfer);
    }

    public static byte[] EncodeResult(IslandResult result)
    {
        ArgumentNullException.ThrowIfNull(result);
        string message = Clean(result.Message ?? string.Empty, MaxMessageChars);
        return Encoding.UTF8.GetBytes($"{Magic} result {(result.Ok ? "ok" : "fail")}\n{message}");
    }

    public static IslandResult DecodeResult(ReadOnlySpan<byte> payload)
    {
        string text = Utf8(payload);
        int newline = text.IndexOf('\n');
        string header = newline < 0 ? text : text[..newline];
        string message = newline < 0 ? string.Empty : text[(newline + 1)..];
        return header switch
        {
            $"{Magic} result ok" => new IslandResult(true, message),
            $"{Magic} result fail" => new IslandResult(false, message),
            _ => throw new FormatException("Not an island result.")
        };
    }

    /// <summary>Names and messages are one line, without control characters, bounded.</summary>
    public static string Clean(string value, int max = MaxNameChars)
    {
        var text = new StringBuilder(Math.Min(value.Length, max));
        foreach (char c in value)
        {
            if (text.Length == max) break;
            text.Append(char.IsControl(c) ? ' ' : c);
        }
        return text.ToString().Trim();
    }

    private static string OpName(IslandOp op) => op switch
    {
        IslandOp.Status => "status",
        IslandOp.Text => "text",
        _ => "files"
    };

    private static string Utf8(ReadOnlySpan<byte> payload)
    {
        if (payload.Length is 0 or > HandoffCodec.MaxFrameBytes) throw new FormatException("Island payload size out of range.");
        try
        {
            return new UTF8Encoding(false, true).GetString(payload);
        }
        catch (DecoderFallbackException error)
        {
            throw new FormatException("Island payload is not UTF-8.", error);
        }
    }

    private static void Validate(IslandRequest request)
    {
        if (request.DeviceId is { } id && !HandoffCodec.IsPlausibleDeviceId(id)) throw new FormatException("Malformed device id.");
        switch (request.Op)
        {
            case IslandOp.Text:
                if (string.IsNullOrEmpty(request.Text) || request.Text.Length > Link.PeerMessage.Clip.MaxTextChars)
                    throw new FormatException("Text length out of range.");
                break;
            case IslandOp.Files:
                if (request.Paths.Count is 0 or > HandoffCodec.MaxPaths) throw new FormatException("File count out of range.");
                foreach (string path in request.Paths)
                {
                    if (string.IsNullOrEmpty(path) || path.Length > HandoffCodec.MaxPathLength || path.Contains('\0') ||
                        path.Contains('\n') || !Path.IsPathFullyQualified(path))
                        throw new FormatException("Every path must be absolute.");
                }
                break;
        }
    }
}
