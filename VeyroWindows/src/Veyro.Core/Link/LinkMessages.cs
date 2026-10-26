using System.Text.Json;
using System.Text.Json.Serialization;

namespace Veyro.Core.Link;

/// <summary>Bumped on any change to bytes on the wire.</summary>
public static class LinkProtocol
{
    public const int Version = 1;
}

/// <summary>
/// The unauthenticated identification preamble.
///
/// Sent as the <b>first</b> frame of every connection, before any key exists. It is a hint and
/// nothing more: it says "I claim to be this device" so the receiver can decide which mode to
/// run. It carries no authority; the mode decision that follows is what grants anything.
/// </summary>
public sealed record LinkPreamble(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("platform")] string Platform = "windows",
    [property: JsonPropertyName("protocolVersion")] int ProtocolVersion = LinkProtocol.Version);

/// <summary>The receiver's answer: which mode this connection will run in.</summary>
public sealed record LinkPreambleAck(
    [property: JsonPropertyName("mode")] string Mode,
    [property: JsonPropertyName("reason")] string? Reason = null)
{
    public const string ModeSession = "session";
    public const string ModePairing = "pairing";
    public const string ModeBusy = "busy";
    public const string ModeRefused = "refused";
}

/// <summary>
/// The outer wire frame. Either <c>c</c> (session mode) or <c>p</c> (pairing mode) is set.
///
/// <c>f</c> travels in the clear because the receiver needs it to pick a key before it can verify
/// anything. It is therefore untrusted — but it is also inside the MAC, so a frame cannot be
/// re-attributed to a different sender without breaking the tag.
///
/// Declared with init properties rather than positionally so the emitted member order matches the
/// Kotlin declaration order. JSON objects are unordered and the MAC covers the bytes as received,
/// so order is not a correctness requirement — it just keeps packet captures comparable.
/// </summary>
public sealed record LinkWireFrame
{
    [JsonPropertyName("f")] public required string From { get; init; }
    [JsonPropertyName("n")] public required string Nonce { get; init; }
    [JsonPropertyName("c")] public string? Ciphertext { get; init; }
    [JsonPropertyName("p")] public string? Plaintext { get; init; }
    [JsonPropertyName("m")] public required string Mac { get; init; }
}

/// <summary>
/// The envelope inside a frame.
///
/// <c>seq</c> is monotonic per sender and enforced on receipt, which is what makes a reordered or
/// truncated stream detectable rather than silently accepted.
/// </summary>
public sealed record LinkEnvelope
{
    [JsonPropertyName("v")] public int V { get; init; } = LinkProtocol.Version;
    [JsonPropertyName("msgId")] public required string MsgId { get; init; }
    [JsonPropertyName("from")] public required string From { get; init; }
    [JsonPropertyName("seq")] public long Seq { get; init; }
    [JsonPropertyName("ts")] public required long Ts { get; init; }
    [JsonPropertyName("payload")] public required LinkMessage Payload { get; init; }
}

/// <summary>Bits for Hello/HelloAck <c>capabilities</c>. Mirrors Kotlin <c>Capability</c>.</summary>
public static class LinkCapabilities
{
    public const int FileTransfer = 0x0001;
    public const int FolderTransfer = 0x0002;
    public const int Resume = 0x0004;
    public const int NotificationMirror = 0x0100;
    public const int MediaControl = 0x0200;
    public const int DeviceStatus = 0x0400;
}

public sealed record MediaSessionInfo(
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("app")] string? App = null,
    [property: JsonPropertyName("title")] string? Title = null,
    [property: JsonPropertyName("artist")] string? Artist = null,
    [property: JsonPropertyName("album")] string? Album = null,
    [property: JsonPropertyName("playing")] bool Playing = false,
    [property: JsonPropertyName("positionMs")] long? PositionMs = null,
    [property: JsonPropertyName("durationMs")] long? DurationMs = null,
    /// <summary>Sender clock (epoch ms) at which PositionMs was sampled.</summary>
    [property: JsonPropertyName("ts")] long? Ts = null,
    [property: JsonPropertyName("canPrev")] bool CanPrev = false,
    [property: JsonPropertyName("canNext")] bool CanNext = false,
    [property: JsonPropertyName("canSeek")] bool CanSeek = false,
    /// <summary>Artwork PNG or JPEG, at most 160x160 and 24 KiB, base64.</summary>
    [property: JsonPropertyName("art")] string? Art = null);

public sealed record NotificationLine(
    [property: JsonPropertyName("sender")] string? Sender,
    [property: JsonPropertyName("text")] string Text);

public sealed record NotificationActionInfo(
    [property: JsonPropertyName("index")] int Index,
    [property: JsonPropertyName("label")] string Label);

public sealed record LinkItem(
    [property: JsonPropertyName("index")] int Index,
    [property: JsonPropertyName("relPath")] string RelPath,
    [property: JsonPropertyName("size")] long Size,
    [property: JsonPropertyName("mtime")] long Mtime,
    [property: JsonPropertyName("isDir")] bool IsDir = false);

/// <summary>
/// Everything that can travel inside an envelope.
///
/// ## Forward compatibility is mandatory
///
/// Every field added after v1 must be nullable with a null default, so an older peer omits it
/// rather than failing to decode. Unknown-key tolerance handles the other direction. Together
/// those mean a new field is <b>not</b> a breaking change and does not need a version bump — only
/// removing a field or changing its meaning does.
/// </summary>
[JsonPolymorphic(TypeDiscriminatorPropertyName = "t")]
[JsonDerivedType(typeof(PairHello), "pairHello")]
[JsonDerivedType(typeof(PairOk), "pairOk")]
[JsonDerivedType(typeof(PairFail), "pairFail")]
[JsonDerivedType(typeof(Hello), "hello")]
[JsonDerivedType(typeof(HelloAck), "helloAck")]
[JsonDerivedType(typeof(TransferOffer), "offer")]
[JsonDerivedType(typeof(TransferDecision), "decision")]
[JsonDerivedType(typeof(TransferProgressNotice), "progressNotice")]
[JsonDerivedType(typeof(ItemBegin), "itemBegin")]
[JsonDerivedType(typeof(ItemEnd), "itemEnd")]
[JsonDerivedType(typeof(ItemResult), "itemResult")]
[JsonDerivedType(typeof(TransferEnd), "transferEnd")]
[JsonDerivedType(typeof(Chunk), "chunk")]
[JsonDerivedType(typeof(Cancel), "cancel")]
[JsonDerivedType(typeof(Ping), "ping")]
[JsonDerivedType(typeof(Pong), "pong")]
[JsonDerivedType(typeof(Notification), "notif")]
[JsonDerivedType(typeof(NotificationActionRequest), "notifAction")]
[JsonDerivedType(typeof(NotificationReplyRequest), "notifReply")]
[JsonDerivedType(typeof(NotificationDismissRequest), "notifDismiss")]
[JsonDerivedType(typeof(MediaState), "mediaState")]
[JsonDerivedType(typeof(MediaCommand), "mediaCmd")]
[JsonDerivedType(typeof(DeviceStatus), "devStatus")]
[JsonDerivedType(typeof(DeviceControl), "devControl")]
public abstract record LinkMessage
{
    // ── Companion: notification mirroring (ADR-025) ──────────────────────────────────────
    // Only exchanged with a peer whose Hello/HelloAck capabilities include
    // LinkCapabilities.NotificationMirror. Contents are user data: never log title, text or
    // messages.

    /// <summary>Phone to PC: a notification was posted, is already active, or was removed.</summary>
    public sealed record Notification(
        [property: JsonPropertyName("key")] string Key,
        /// <summary><c>posted</c> | <c>active</c> | <c>removed</c>. A removed frame carries only the key.</summary>
        [property: JsonPropertyName("kind")] string Kind,
        [property: JsonPropertyName("pkg")] string? Pkg = null,
        [property: JsonPropertyName("app")] string? App = null,
        [property: JsonPropertyName("title")] string? Title = null,
        [property: JsonPropertyName("text")] string? Text = null,
        [property: JsonPropertyName("messages")] IReadOnlyList<NotificationLine>? Messages = null,
        [property: JsonPropertyName("ts")] long? Ts = null,
        [property: JsonPropertyName("group")] string? Group = null,
        [property: JsonPropertyName("actions")] IReadOnlyList<NotificationActionInfo>? Actions = null,
        [property: JsonPropertyName("canReply")] bool? CanReply = null,
        /// <summary>App icon PNG, at most 96x96 and 16 KiB, base64.</summary>
        [property: JsonPropertyName("iconPng")] string? IconPng = null) : LinkMessage
    {
        public const string KindPosted = "posted";
        public const string KindActive = "active";
        public const string KindRemoved = "removed";
    }

    /// <summary>PC to phone: invoke action <c>index</c> of the phone's notification.</summary>
    public sealed record NotificationActionRequest(
        [property: JsonPropertyName("key")] string Key,
        [property: JsonPropertyName("index")] int Index) : LinkMessage;

    /// <summary>PC to phone: send text through the notification's reply action.</summary>
    public sealed record NotificationReplyRequest(
        [property: JsonPropertyName("key")] string Key,
        [property: JsonPropertyName("text")] string Text) : LinkMessage;

    /// <summary>PC to phone: dismiss the notification on the phone.</summary>
    public sealed record NotificationDismissRequest(
        [property: JsonPropertyName("key")] string Key) : LinkMessage;

    // ── Companion: media control (ADR-026), gated by LinkCapabilities.MediaControl ─────────
    // Symmetric. Position is not streamed; extrapolate from PositionMs + Ts while Playing.

    /// <summary>Full snapshot of the sender's media sessions. Sent on connect and on change.</summary>
    public sealed record MediaState(
        [property: JsonPropertyName("sessions")] IReadOnlyList<MediaSessionInfo> Sessions,
        [property: JsonPropertyName("volume")] int? Volume = null,
        [property: JsonPropertyName("muted")] bool? Muted = null) : LinkMessage;

    /// <summary>
    /// Drive a session on the receiver. Action: play | pause | toggle | next | prev |
    /// seek (Value = ms) | volume (Value = 0..100, master) | mute (Value = 1/0, master).
    /// </summary>
    public sealed record MediaCommand(
        [property: JsonPropertyName("action")] string Action,
        [property: JsonPropertyName("id")] string? Id = null,
        [property: JsonPropertyName("value")] long? Value = null) : LinkMessage
    {
        public const string Play = "play";
        public const string Pause = "pause";
        public const string Toggle = "toggle";
        public const string Next = "next";
        public const string Prev = "prev";
        public const string Seek = "seek";
        public const string Volume = "volume";
        public const string Mute = "mute";
    }

    // ── Companion: device status (ADR-026), gated by LinkCapabilities.DeviceStatus ─────────

    /// <summary>Sender's own status. Absent fields are unknown or unsupported.</summary>
    public sealed record DeviceStatus(
        [property: JsonPropertyName("battery")] int? Battery = null,
        [property: JsonPropertyName("charging")] bool? Charging = null,
        /// <summary>normal | vibrate | silent (phones only).</summary>
        [property: JsonPropertyName("ringer")] string? Ringer = null,
        [property: JsonPropertyName("dnd")] bool? Dnd = null) : LinkMessage;

    /// <summary>PC to phone. Each set field is a request the phone applies if permitted.</summary>
    public sealed record DeviceControl(
        /// <summary>True: ring at full volume until stopped or 30 s. False: stop.</summary>
        [property: JsonPropertyName("ring")] bool? Ring = null,
        [property: JsonPropertyName("ringer")] string? Ringer = null,
        [property: JsonPropertyName("dnd")] bool? Dnd = null) : LinkMessage;

    /// <summary>Sent by the pairing initiator, under the PIN-derived bootstrap key.</summary>
    public sealed record PairHello(
        [property: JsonPropertyName("deviceId")] string DeviceId,
        [property: JsonPropertyName("deviceName")] string DeviceName,
        [property: JsonPropertyName("platform")] string Platform,
        [property: JsonPropertyName("appVersion")] string AppVersion,
        [property: JsonPropertyName("protocolVersion")] int ProtocolVersion = LinkProtocol.Version,
        [property: JsonPropertyName("publicKeyB64")] string? PublicKeyB64 = null) : LinkMessage;

    /// <summary>
    /// The target's answer, carrying the long-lived session secret.
    /// <b>Always sent sealed</b>, even though the connection is still nominally in plaintext
    /// pairing mode: the secret must not cross the LAN in the clear.
    /// </summary>
    public sealed record PairOk(
        [property: JsonPropertyName("deviceId")] string DeviceId,
        [property: JsonPropertyName("deviceName")] string DeviceName,
        [property: JsonPropertyName("platform")] string Platform,
        [property: JsonPropertyName("sessionSecretB64")] string SessionSecretB64,
        [property: JsonPropertyName("publicKeyB64")] string? PublicKeyB64 = null) : LinkMessage;

    public sealed record PairFail(
        [property: JsonPropertyName("reason")] string Reason) : LinkMessage;

    /// <summary>First message of an established session. Confirms both sides can decrypt.</summary>
    public sealed record Hello(
        [property: JsonPropertyName("deviceId")] string DeviceId,
        [property: JsonPropertyName("deviceName")] string DeviceName,
        [property: JsonPropertyName("platform")] string Platform,
        [property: JsonPropertyName("capabilities")] int Capabilities = 0,
        [property: JsonPropertyName("maxChunk")] int MaxChunk = 4 * 1024 * 1024) : LinkMessage;

    public sealed record HelloAck(
        [property: JsonPropertyName("deviceId")] string DeviceId,
        [property: JsonPropertyName("deviceName")] string DeviceName,
        [property: JsonPropertyName("platform")] string Platform,
        [property: JsonPropertyName("capabilities")] int Capabilities = 0,
        [property: JsonPropertyName("chunkSize")] int ChunkSize = 4 * 1024 * 1024) : LinkMessage;

    public sealed record TransferOffer(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("items")] IReadOnlyList<LinkItem> Items,
        [property: JsonPropertyName("totalBytes")] long TotalBytes,
        [property: JsonPropertyName("senderName")] string SenderName,
        [property: JsonPropertyName("preferredRoutes")] IReadOnlyList<string> PreferredRoutes,
        [property: JsonPropertyName("senderWifiP2pAddress")] string? SenderWifiP2pAddress = null)
        : LinkMessage
    {
        // Kotlin data classes with List members get structural equality from List.equals.
        // C# records compare collection members by reference, so without this the same message
        // decoded twice would compare unequal. Mirrored deliberately.
        public bool Equals(TransferOffer? other)
            => other is not null
               && TransferId == other.TransferId
               && TotalBytes == other.TotalBytes
               && SenderName == other.SenderName
               && SenderWifiP2pAddress == other.SenderWifiP2pAddress
               && Items.SequenceEqual(other.Items)
               && PreferredRoutes.SequenceEqual(other.PreferredRoutes);

        public override int GetHashCode()
            => HashCode.Combine(TransferId, TotalBytes, SenderName, Items.Count, PreferredRoutes.Count);
    }

    public sealed record TransferDecision(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("accept")] bool Accept,
        [property: JsonPropertyName("reason")] string? Reason = null,
        /// <summary>itemIndex to the chunk indices still needed. Absent means "send everything".</summary>
        [property: JsonPropertyName("resume")] IReadOnlyDictionary<int, IReadOnlyList<int>>? Resume = null,
        [property: JsonPropertyName("route")] string? Route = null,
        [property: JsonPropertyName("tcpHost")] string? TcpHost = null,
        [property: JsonPropertyName("tcpPort")] int? TcpPort = null,
        [property: JsonPropertyName("fallbackHost")] string? FallbackHost = null,
        [property: JsonPropertyName("authToken")] string? AuthToken = null,
        [property: JsonPropertyName("p2pSsid")] string? P2pSsid = null,
        [property: JsonPropertyName("p2pPassphrase")] string? P2pPassphrase = null) : LinkMessage
    {
        public bool Equals(TransferDecision? other)
            => other is not null
               && TransferId == other.TransferId
               && Accept == other.Accept
               && Reason == other.Reason
               && Route == other.Route
               && TcpHost == other.TcpHost
               && TcpPort == other.TcpPort
               && FallbackHost == other.FallbackHost
               && AuthToken == other.AuthToken
               && P2pSsid == other.P2pSsid
               && P2pPassphrase == other.P2pPassphrase
               && ResumeEquals(Resume, other.Resume);

        private static bool ResumeEquals(
            IReadOnlyDictionary<int, IReadOnlyList<int>>? a,
            IReadOnlyDictionary<int, IReadOnlyList<int>>? b)
        {
            if (ReferenceEquals(a, b)) return true;
            if (a is null || b is null) return false;
            if (a.Count != b.Count) return false;

            foreach (var (k, v) in a)
            {
                if (!b.TryGetValue(k, out var other) || !v.SequenceEqual(other)) return false;
            }
            return true;
        }

        public override int GetHashCode()
            => HashCode.Combine(TransferId, Accept, Route, TcpHost, TcpPort, AuthToken, Resume?.Count ?? 0);
    }

    public sealed record TransferProgressNotice(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("bytesReceived")] long BytesReceived,
        [property: JsonPropertyName("totalBytes")] long TotalBytes,
        [property: JsonPropertyName("currentItemIndex")] int CurrentItemIndex = 0,
        [property: JsonPropertyName("speedBps")] long SpeedBps = 0) : LinkMessage;

    public sealed record ItemBegin(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("size")] long Size,
        [property: JsonPropertyName("chunkCount")] int ChunkCount,
        [property: JsonPropertyName("chunkSize")] int ChunkSize) : LinkMessage;

    public sealed record ItemEnd(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("sha256")] string Sha256) : LinkMessage;

    public sealed record ItemResult(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("ok")] bool Ok,
        [property: JsonPropertyName("error")] string? Error = null) : LinkMessage;

    public sealed record TransferEnd(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("completed")] bool Completed,
        [property: JsonPropertyName("failedIndices")] IReadOnlyList<int>? FailedIndices = null)
        : LinkMessage
    {
        public bool Equals(TransferEnd? other)
            => other is not null
               && TransferId == other.TransferId
               && Completed == other.Completed
               && ((FailedIndices is null && other.FailedIndices is null)
                   || (FailedIndices is not null && other.FailedIndices is not null
                       && FailedIndices.SequenceEqual(other.FailedIndices)));

        public override int GetHashCode()
            => HashCode.Combine(TransferId, Completed, FailedIndices?.Count ?? -1);
    }

    /// <summary>
    /// One chunk of file data, base64 in the JSON envelope. Fallback only — the normal path is
    /// the VTC1 raw TCP data plane (ADR-022).
    /// </summary>
    public sealed record Chunk(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("chunkIndex")] int ChunkIndex,
        [property: JsonPropertyName("dataB64")] string DataB64) : LinkMessage;

    public sealed record Cancel(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("reason")] string Reason) : LinkMessage;

    public sealed record Ping(
        [property: JsonPropertyName("nonce")] string Nonce) : LinkMessage;

    public sealed record Pong(
        [property: JsonPropertyName("nonce")] string Nonce) : LinkMessage;
}

/// <summary>
/// The single JSON codec for the link protocol. Settings are normative and must match the Kotlin
/// <c>LinkJson</c>: unknown keys ignored (never rejected, so a newer peer adding a field does not
/// break an older one), nulls omitted, and <c>"t"</c> as the type discriminator.
/// </summary>
public static class LinkJson
{
    public static readonly JsonSerializerOptions Codec = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNameCaseInsensitive = false,
        WriteIndented = false
    };

    public static string Encode<T>(T value) => JsonSerializer.Serialize(value, Codec);

    public static T? Decode<T>(string json) => JsonSerializer.Deserialize<T>(json, Codec);
}
