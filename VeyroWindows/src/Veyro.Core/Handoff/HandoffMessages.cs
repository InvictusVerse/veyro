using System.Buffers.Binary;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Veyro.Core.Handoff;

/// <summary>Where a hand-off came from (ADR-039).</summary>
public enum HandoffKind
{
    /// <summary>The Windows Share sheet. Usually asks which phone, see <see cref="SendTargetSelector"/>.</summary>
    Share = 1,

    /// <summary>"Send with Veyro" in the Explorer right-click menu. The target is already chosen.</summary>
    ContextMenu = 2,

    /// <summary>"Pair a phone..." in the Explorer menu: open the pairing screen. No files.</summary>
    Pair = 3
}

public enum HandoffTargetKind
{
    /// <summary>Let the main window ask (or send straight to the only connected phone).</summary>
    Choose = 0,
    Device = 1,
    AllConnected = 2
}

/// <summary>
/// Files another Veyro process (Share activation, Explorer menu server) hands to the running Veyro.
/// <see cref="Paths"/> are user data: never logged.
/// </summary>
public sealed record HandoffRequest(
    HandoffKind Kind,
    IReadOnlyList<string> Paths,
    HandoffTargetKind Target = HandoffTargetKind.Choose,
    string? DeviceId = null);

public enum HandoffOutcome
{
    /// <summary>First reply: the request was read and is being handled.</summary>
    Accepted = 0,
    Sent = 1,
    PartlySent = 2,
    Cancelled = 3,
    Failed = 4,
    NoPhonePaired = 5
}

/// <summary><see cref="Message"/> is shown to the user (Share sheet error); it never names files.</summary>
public sealed record HandoffReply(HandoffOutcome Outcome, string? Message = null);

/// <summary>
/// Wire format of the local hand-off pipe (ADR-039): a 4-byte little-endian length, then UTF-8 JSON.
/// Local IPC between two Veyro processes of the same user, not part of VLP. Decoding is strict:
/// anything malformed throws <see cref="FormatException"/> and nothing is sent.
/// </summary>
public static class HandoffCodec
{
    public const int Version = 1;
    public const int MaxFrameBytes = 4 * 1024 * 1024;
    public const int MaxPaths = 4096;
    public const int MaxPathLength = 32767;
    public const int MaxDeviceIdLength = 128;
    public const int MaxMessageLength = 512;

    private static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    private sealed record RequestDto(int V, int Kind, List<string>? Paths, int Target, string? DeviceId);

    private sealed record ReplyDto(int V, int Outcome, string? Message);

    public static byte[] EncodeRequest(HandoffRequest request)
    {
        ArgumentNullException.ThrowIfNull(request);
        Validate(request);
        return JsonSerializer.SerializeToUtf8Bytes(
            new RequestDto(Version, (int)request.Kind, request.Paths.ToList(), (int)request.Target, request.DeviceId), Json);
    }

    public static HandoffRequest DecodeRequest(ReadOnlySpan<byte> payload)
    {
        RequestDto dto = Deserialize<RequestDto>(payload);
        if (dto.V != Version) throw new FormatException("Unsupported hand-off version.");
        if (!Enum.IsDefined((HandoffKind)dto.Kind)) throw new FormatException("Unknown hand-off kind.");
        if (!Enum.IsDefined((HandoffTargetKind)dto.Target)) throw new FormatException("Unknown hand-off target.");
        var request = new HandoffRequest((HandoffKind)dto.Kind, dto.Paths ?? [], (HandoffTargetKind)dto.Target, dto.DeviceId);
        Validate(request);
        return request;
    }

    public static byte[] EncodeReply(HandoffReply reply)
    {
        ArgumentNullException.ThrowIfNull(reply);
        string? message = reply.Message is { Length: > MaxMessageLength } text ? text[..MaxMessageLength] : reply.Message;
        return JsonSerializer.SerializeToUtf8Bytes(new ReplyDto(Version, (int)reply.Outcome, message), Json);
    }

    public static HandoffReply DecodeReply(ReadOnlySpan<byte> payload)
    {
        ReplyDto dto = Deserialize<ReplyDto>(payload);
        if (dto.V != Version) throw new FormatException("Unsupported hand-off version.");
        if (!Enum.IsDefined((HandoffOutcome)dto.Outcome)) throw new FormatException("Unknown hand-off outcome.");
        if (dto.Message is { Length: > MaxMessageLength }) throw new FormatException("Hand-off message too long.");
        return new HandoffReply((HandoffOutcome)dto.Outcome, dto.Message);
    }

    public static async Task WriteFrameAsync(Stream stream, byte[] payload, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(payload);
        if (payload.Length > MaxFrameBytes) throw new ArgumentException("Hand-off frame too large.", nameof(payload));
        byte[] frame = new byte[4 + payload.Length];
        BinaryPrimitives.WriteInt32LittleEndian(frame, payload.Length);
        payload.CopyTo(frame, 4);
        await stream.WriteAsync(frame, ct).ConfigureAwait(false);
        await stream.FlushAsync(ct).ConfigureAwait(false);
    }

    /// <summary>Reads one frame. Throws <see cref="EndOfStreamException"/> when the other side went away.</summary>
    public static async Task<byte[]> ReadFrameAsync(Stream stream, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(stream);
        byte[] header = new byte[4];
        await stream.ReadExactlyAsync(header, ct).ConfigureAwait(false);
        int length = BinaryPrimitives.ReadInt32LittleEndian(header);
        if (length is < 2 or > MaxFrameBytes) throw new FormatException("Hand-off frame length out of range.");
        byte[] payload = new byte[length];
        await stream.ReadExactlyAsync(payload, ct).ConfigureAwait(false);
        return payload;
    }

    private static T Deserialize<T>(ReadOnlySpan<byte> payload) where T : class
    {
        if (payload.Length is 0 or > MaxFrameBytes) throw new FormatException("Hand-off payload size out of range.");
        try
        {
            return JsonSerializer.Deserialize<T>(payload, Json) ?? throw new FormatException("Empty hand-off payload.");
        }
        catch (JsonException error)
        {
            throw new FormatException("Malformed hand-off payload.", error);
        }
    }

    private static void Validate(HandoffRequest request)
    {
        if (request.Paths is null) throw new FormatException("Missing paths.");
        if (request.Kind == HandoffKind.Pair)
        {
            if (request.Paths.Count != 0) throw new FormatException("A pairing hand-off carries no files.");
        }
        else if (request.Paths.Count is 0 or > MaxPaths)
        {
            throw new FormatException("File count out of range.");
        }
        foreach (string path in request.Paths)
        {
            if (string.IsNullOrEmpty(path) || path.Length > MaxPathLength || path.Contains('\0') ||
                !Path.IsPathFullyQualified(path))
                throw new FormatException("Every path must be absolute.");
        }
        bool needsDevice = request.Target == HandoffTargetKind.Device;
        if (needsDevice != (request.DeviceId is not null)) throw new FormatException("Device id does not match the target.");
        if (request.DeviceId is { } id && !IsPlausibleDeviceId(id)) throw new FormatException("Malformed device id.");
    }

    /// <summary>Shape check only; the receiver still looks the id up in its verified trust store.</summary>
    public static bool IsPlausibleDeviceId(string id)
        => id.Length is > 0 and <= MaxDeviceIdLength && id.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_');
}
