using System.Text.Json;
using System.Text.Json.Serialization;

namespace Veyro.Core.Link;

/// <summary>
/// Bumped on any change to bytes on the wire. 3 = per-connection keys, ChaCha20-Poly1305 frames,
/// QR secret and the sealed VTC3 data plane (ADR-058). Every other version is refused with
/// unsupported-protocol-version; there is no v2 support.
/// </summary>
public static class LinkProtocol
{
    public const int Version = 3;
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
    [property: JsonPropertyName("protocolVersion")] int ProtocolVersion = LinkProtocol.Version,
    /// <summary>How to pair if the receiver does not know us: confirm | qr | absent (session only; ADR-058 removed the PIN flow).</summary>
    [property: JsonPropertyName("pair")] string? Pair = null,
    /// <summary>Sender's static X25519 public key, base64. Sent with pair = confirm and pair = qr.</summary>
    [property: JsonPropertyName("pk")] string? Pk = null,
    /// <summary>32 fresh random bytes, standard base64 (44 characters). Required (ADR-058).</summary>
    [property: JsonPropertyName("cn")] string? Cn = null,
    /// <summary>This connection's ephemeral X25519 public key, standard base64. Required.</summary>
    [property: JsonPropertyName("epk")] string? Epk = null)
{
    /// <summary>Compare a code on both screens and accept on the receiver (04-PROTOCOL.md 4.3).</summary>
    public const string PairConfirm = "confirm";

    /// <summary>The receiver's open QR window supplies the code (04-PROTOCOL.md 4.2).</summary>
    public const string PairQr = "qr";
}

/// <summary>The receiver's answer: which mode this connection will run in.</summary>
public sealed record LinkPreambleAck(
    [property: JsonPropertyName("mode")] string Mode,
    [property: JsonPropertyName("reason")] string? Reason = null,
    /// <summary>Receiver's static X25519 public key, base64. Only with pairing-confirm.</summary>
    [property: JsonPropertyName("pk")] string? Pk = null,
    /// <summary>SHA-256("veyro-commit-v1" || pkB || pkA || nB), base64. Only with pairing-confirm.</summary>
    [property: JsonPropertyName("commit")] string? Commit = null,
    /// <summary>The responder's 32 fresh bytes; with session, pairing-confirm and pairing (ADR-058).</summary>
    [property: JsonPropertyName("cn")] string? Cn = null,
    /// <summary>The responder's ephemeral X25519 public key; same modes as cn.</summary>
    [property: JsonPropertyName("epk")] string? Epk = null)
{
    public const string ModeSession = "session";
    public const string ModePairing = "pairing";
    public const string ModePairingConfirm = "pairing-confirm";
    public const string ModeBusy = "busy";
    public const string ModeRefused = "refused";
}

/// <summary>
/// One nonce of the confirm exchange: an unauthenticated text frame like the preamble
/// (04-PROTOCOL.md 4.3). The phone sends nA first; the PC answers with the nB its commitment
/// fixed. Never logged.
/// </summary>
public sealed record LinkPairNonce(
    [property: JsonPropertyName("t")] string T,
    /// <summary>32 random bytes, base64.</summary>
    [property: JsonPropertyName("n")] string N)
{
    public const string Type = "pairNonce";

    public static LinkPairNonce For(byte[] nonce) => new(Type, Convert.ToBase64String(nonce));

    public override string ToString() => "LinkPairNonce";
}

/// <summary>
/// The outer wire frame (04-PROTOCOL.md 5.1, ADR-058): <c>{"f": fromDeviceId, "c": base64(ciphertext || tag)}</c>.
///
/// Every frame after the ack is sealed with ChaCha20-Poly1305; the nonce is a counter each side
/// tracks itself, so it is not on the wire. <c>f</c> is untrusted: the receiver requires it to
/// equal the expected peer id and binds that id into the AAD. Declared with init properties so
/// the member order matches the Kotlin declaration order.
/// </summary>
public sealed record LinkWireFrame
{
    [JsonPropertyName("f")] public required string From { get; init; }
    [JsonPropertyName("c")] public required string Ciphertext { get; init; }
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
    [JsonPropertyName("payload")] public required PeerMessage Payload { get; init; }
}

/// <summary>Route names exactly as they appear on the wire (Kotlin <c>TransferRoute.name</c>).</summary>
public static class RouteWireNames
{
    public const string LanTcp = "LAN_TCP";
    public const string WifiDirect = "WIFI_DIRECT";
}

/// <summary>Bits for Hello/HelloAck <c>capabilities</c>. Mirrors Kotlin <c>Capability</c>.</summary>
public static class PeerCapabilities
{
    public const int FileTransfer = 0x0001;
    public const int FolderTransfer = 0x0002;
    public const int Resume = 0x0004;
    public const int Clipboard = 0x0008;
    public const int NotificationMirror = 0x0100;
    public const int MediaControl = 0x0200;
    public const int DeviceStatus = 0x0400;
    public const int Messaging = 0x0800;
    public const int Calls = 0x1000;
    public const int Photos = 0x2000;
    public const int Wallpaper = 0x4000;
    /// <summary>ADR-066: the phone can share its screen (streamCaps, streamStart, VMS1). Phone only.</summary>
    public const int ScreenSource = 0x8000;
    /// <summary>ADR-066: this PC can show a phone's screen. Not advertised until the viewer is built.</summary>
    public const int ScreenView = 0x10000;
    /// <summary>ADR-066: touch and keys from the PC to the phone while mirroring (reserved).</summary>
    public const int RemoteInput = 0x20000;
    /// <summary>ADR-060: flashlight, media volume and brightness in devStatus / devControl. PC to phone only.</summary>
    public const int PhoneControls = 0x40000;
    /// <summary>ADR-059: deviceInfo, the sender's own name (and a phone's clipboard-receive switch).</summary>
    public const int DeviceInfo = 0x200000;
}

/// <summary>
/// ADR-060, in devStatus.controls: per control "ok" or why the PC cannot change it now. An absent
/// entry or an unknown value means "not available". Mirrors Kotlin PeerMessage.PhoneControlState.
/// </summary>
public sealed record PhoneControlState(
    [property: JsonPropertyName("torch")] string? Torch = null,
    [property: JsonPropertyName("volume")] string? Volume = null,
    [property: JsonPropertyName("brightness")] string? Brightness = null)
{
    public const string Ok = "ok";
    /// <summary>The phone's "Let PCs control flashlight, volume and brightness" switch is off.</summary>
    public const string Off = "off";
    /// <summary>The phone needs a system grant first (brightness: "Modify system settings").</summary>
    public const string Permission = "permission";
    /// <summary>No flashlight, or a fixed-volume phone.</summary>
    public const string Unsupported = "unsupported";
    /// <summary>Another app holds the flashlight (camera open).</summary>
    public const string Busy = "busy";
}

public sealed record PhotoInfo(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("ts")] long Ts,
    [property: JsonPropertyName("width")] int? Width = null,
    [property: JsonPropertyName("height")] int? Height = null,
    [property: JsonPropertyName("size")] long? Size = null,
    [property: JsonPropertyName("mime")] string? Mime = null,
    /// <summary>camera | screenshots</summary>
    [property: JsonPropertyName("bucket")] string? Bucket = null);

public sealed record SmsThread(
    [property: JsonPropertyName("threadId")] long ThreadId,
    [property: JsonPropertyName("addresses")] IReadOnlyList<string> Addresses,
    /// <summary>Contact names aligned with Addresses; null entries where unknown.</summary>
    [property: JsonPropertyName("names")] IReadOnlyList<string?>? Names = null,
    [property: JsonPropertyName("snippet")] string? Snippet = null,
    [property: JsonPropertyName("ts")] long Ts = 0,
    [property: JsonPropertyName("unread")] int Unread = 0);

public sealed record SmsMessage(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("threadId")] long ThreadId,
    [property: JsonPropertyName("address")] string Address,
    [property: JsonPropertyName("body")] string Body,
    [property: JsonPropertyName("ts")] long Ts,
    /// <summary>True if sent from the phone.</summary>
    [property: JsonPropertyName("out")] bool Out,
    [property: JsonPropertyName("read")] bool Read = true,
    /// <summary>sent | pending | failed, outgoing only.</summary>
    [property: JsonPropertyName("status")] string? Status = null,
    [property: JsonPropertyName("sim")] int? Sim = null);

public sealed record SimInfo(
    [property: JsonPropertyName("id")] int Id,
    [property: JsonPropertyName("label")] string Label,
    [property: JsonPropertyName("slot")] int? Slot = null);

public sealed record CallLogEntry(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("number")] string Number,
    [property: JsonPropertyName("name")] string? Name = null,
    [property: JsonPropertyName("ts")] long Ts = 0,
    [property: JsonPropertyName("durationSec")] long DurationSec = 0,
    /// <summary>in | out | missed | rejected | blocked</summary>
    [property: JsonPropertyName("type")] string Type = "in");

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
[JsonDerivedType(typeof(SmsThreadsRequest), "smsThreadsReq")]
[JsonDerivedType(typeof(SmsThreads), "smsThreads")]
[JsonDerivedType(typeof(SmsMessagesRequest), "smsMessagesReq")]
[JsonDerivedType(typeof(SmsMessages), "smsMessages")]
[JsonDerivedType(typeof(SmsNew), "smsNew")]
[JsonDerivedType(typeof(SmsSend), "smsSend")]
[JsonDerivedType(typeof(SmsSendResult), "smsSendResult")]
[JsonDerivedType(typeof(SimList), "simList")]
[JsonDerivedType(typeof(CallState), "callState")]
[JsonDerivedType(typeof(CallAction), "callAction")]
[JsonDerivedType(typeof(CallLogRequest), "callLogReq")]
[JsonDerivedType(typeof(CallLog), "callLog")]
[JsonDerivedType(typeof(PhotosRequest), "photosReq")]
[JsonDerivedType(typeof(Photos), "photos")]
[JsonDerivedType(typeof(PhotoThumbRequest), "photoThumbReq")]
[JsonDerivedType(typeof(PhotoThumb), "photoThumb")]
[JsonDerivedType(typeof(PhotoFetch), "photoFetch")]
[JsonDerivedType(typeof(PhotoNew), "photoNew")]
[JsonDerivedType(typeof(PhotoRemoved), "photoRemoved")]
[JsonDerivedType(typeof(PcLock), "pcLock")]
[JsonDerivedType(typeof(Unpair), "unpair")]
[JsonDerivedType(typeof(Clip), "clip")]
[JsonDerivedType(typeof(Wallpaper), "wallpaper")]
[JsonDerivedType(typeof(DeviceInfo), "deviceInfo")]
// Screen mirroring messages (ADR-066) and their derived-type attributes are in PeerMessages.Stream.cs.
public abstract partial record PeerMessage
{
    // ── Companion: notification mirroring (ADR-025) ──────────────────────────────────────
    // Only exchanged with a peer whose Hello/HelloAck capabilities include
    // PeerCapabilities.NotificationMirror. Contents are user data: never log title, text or
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
        [property: JsonPropertyName("iconPng")] string? IconPng = null) : PeerMessage
    {
        public const string KindPosted = "posted";
        public const string KindActive = "active";
        public const string KindRemoved = "removed";
    }

    /// <summary>PC to phone: invoke action <c>index</c> of the phone's notification.</summary>
    public sealed record NotificationActionRequest(
        [property: JsonPropertyName("key")] string Key,
        [property: JsonPropertyName("index")] int Index) : PeerMessage;

    /// <summary>PC to phone: send text through the notification's reply action.</summary>
    public sealed record NotificationReplyRequest(
        [property: JsonPropertyName("key")] string Key,
        [property: JsonPropertyName("text")] string Text) : PeerMessage;

    /// <summary>PC to phone: dismiss the notification on the phone.</summary>
    public sealed record NotificationDismissRequest(
        [property: JsonPropertyName("key")] string Key) : PeerMessage;

    // ── Companion: media control (ADR-026), gated by PeerCapabilities.MediaControl ─────────
    // Symmetric. Position is not streamed; extrapolate from PositionMs + Ts while Playing.

    /// <summary>Full snapshot of the sender's media sessions. Sent on connect and on change.</summary>
    public sealed record MediaState(
        [property: JsonPropertyName("sessions")] IReadOnlyList<MediaSessionInfo> Sessions,
        [property: JsonPropertyName("volume")] int? Volume = null,
        [property: JsonPropertyName("muted")] bool? Muted = null) : PeerMessage;

    /// <summary>
    /// Drive a session on the receiver. Action: play | pause | toggle | next | prev |
    /// seek (Value = ms) | volume (Value = 0..100, master) | mute (Value = 1/0, master).
    /// </summary>
    public sealed record MediaCommand(
        [property: JsonPropertyName("action")] string Action,
        [property: JsonPropertyName("id")] string? Id = null,
        [property: JsonPropertyName("value")] long? Value = null) : PeerMessage
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

    // ── Companion: device status (ADR-026), gated by PeerCapabilities.DeviceStatus ─────────

    /// <summary>Sender's own status. Absent fields are unknown or unsupported.</summary>
    public sealed record DeviceStatus(
        [property: JsonPropertyName("battery")] int? Battery = null,
        [property: JsonPropertyName("charging")] bool? Charging = null,
        /// <summary>normal | vibrate | silent (phones only).</summary>
        [property: JsonPropertyName("ringer")] string? Ringer = null,
        [property: JsonPropertyName("dnd")] bool? Dnd = null,
        /// <summary>Wi-Fi signal 0..4, -1 off/disconnected (ADR-038).</summary>
        [property: JsonPropertyName("wifi")] int? Wifi = null,
        /// <summary>Strongest cellular signal 0..4, -1 no service.</summary>
        [property: JsonPropertyName("cell")] int? Cell = null,
        /// <summary>Carrier of the data SIM. User data: never log.</summary>
        [property: JsonPropertyName("carrier")] string? Carrier = null,
        [property: JsonPropertyName("bluetooth")] bool? Bluetooth = null,
        [property: JsonPropertyName("mobileData")] bool? MobileData = null,
        // ADR-060, from a phone that advertises PhoneControls (04-PROTOCOL.md 6.2.2).
        /// <summary>Flashlight on. Absent when the phone has no flashlight or cannot tell.</summary>
        [property: JsonPropertyName("torch")] bool? Torch = null,
        /// <summary>Media stream volume, 0..100 percent of the stream's range.</summary>
        [property: JsonPropertyName("mediaVolume")] int? MediaVolume = null,
        /// <summary>Screen brightness setting, 0..100 percent of its range.</summary>
        [property: JsonPropertyName("brightness")] int? Brightness = null,
        [property: JsonPropertyName("autoBrightness")] bool? AutoBrightness = null,
        /// <summary>Whether each control can be changed from the PC now, and if not, why.</summary>
        [property: JsonPropertyName("controls")] PhoneControlState? Controls = null) : PeerMessage;

    /// <summary>
    /// Either direction (ADR-059, 04-PROTOCOL.md 6.7), gated by PeerCapabilities.DeviceInfo: the
    /// sender's own details, on connect and whenever one changes. Each field is applied on its own.
    /// Names are user data: never log them.
    /// </summary>
    public sealed record DeviceInfo(
        /// <summary>The name the sender's user gave it, 1..64 chars, no control characters.</summary>
        [property: JsonPropertyName("name")] string? Name = null,
        /// <summary>Phone only: false asks this PC not to send its automatic copies (clip, apply = false).</summary>
        [property: JsonPropertyName("clipReceive")] bool? ClipReceive = null) : PeerMessage;

    /// <summary>Phone to PC: lock this PC (Phone Link "Lock PC").</summary>
    public sealed record PcLock : PeerMessage;

    /// <summary>Either direction: the sender removed us. Delete the trust record and close (ADR-042).</summary>
    public sealed record Unpair : PeerMessage;

    /// <summary>
    /// Either direction: one clipboard item (ADR-043), gated by PeerCapabilities.Clipboard.
    /// Apply = true: put it on the receiver's clipboard now (shared from the phone). False: keep it
    /// in the receiver's virtual clipboard. Exactly one of Text / Image is set. Never log contents.
    /// </summary>
    public sealed record Clip(
        [property: JsonPropertyName("id")] string Id,
        /// <summary>text | image</summary>
        [property: JsonPropertyName("kind")] string Kind,
        [property: JsonPropertyName("text")] string? Text = null,
        /// <summary>PNG or JPEG, at most MaxImageBytes before base64.</summary>
        [property: JsonPropertyName("image")] string? Image = null,
        [property: JsonPropertyName("apply")] bool Apply = false,
        [property: JsonPropertyName("ts")] long Ts = 0) : PeerMessage
    {
        public const string KindText = "text";
        public const string KindImage = "image";
        public const int MaxTextChars = 100_000;
        public const int MaxImageBytes = 2 * 1024 * 1024;
    }

    /// <summary>Either direction: sender's wallpaper, JPEG &lt;= 720 px, &lt;= 96 KiB, base64.</summary>
    public sealed record Wallpaper(
        [property: JsonPropertyName("jpeg")] string? Jpeg = null) : PeerMessage;

    // ── Companion: SMS (ADR-027), gated by PeerCapabilities.Messaging ─────────────────────
    // Pull model. Bodies, numbers and names are user data: never log them.

    /// <summary>PC to phone: list recent threads, newest first.</summary>
    public sealed record SmsThreadsRequest(
        [property: JsonPropertyName("limit")] int Limit = 50) : PeerMessage;

    public sealed record SmsThreads(
        [property: JsonPropertyName("threads")] IReadOnlyList<SmsThread> Threads) : PeerMessage;

    /// <summary>PC to phone: one page of a thread, newest first, strictly older than BeforeTs.</summary>
    public sealed record SmsMessagesRequest(
        [property: JsonPropertyName("threadId")] long ThreadId,
        [property: JsonPropertyName("beforeTs")] long? BeforeTs = null,
        [property: JsonPropertyName("limit")] int Limit = 50) : PeerMessage;

    public sealed record SmsMessages(
        [property: JsonPropertyName("threadId")] long ThreadId,
        [property: JsonPropertyName("messages")] IReadOnlyList<SmsMessage> Messages,
        /// <summary>True if older messages exist.</summary>
        [property: JsonPropertyName("more")] bool More = false) : PeerMessage;

    /// <summary>Phone to PC: a message arrived or was sent since the session started.</summary>
    public sealed record SmsNew(
        [property: JsonPropertyName("message")] SmsMessage Message,
        [property: JsonPropertyName("thread")] SmsThread? Thread = null) : PeerMessage;

    /// <summary>PC to phone: send a text. ClientId is echoed in SmsSendResult.</summary>
    public sealed record SmsSend(
        [property: JsonPropertyName("clientId")] string ClientId,
        [property: JsonPropertyName("addresses")] IReadOnlyList<string> Addresses,
        [property: JsonPropertyName("body")] string Body,
        /// <summary>Subscription id from SimList; null = phone default SMS SIM.</summary>
        [property: JsonPropertyName("sim")] int? Sim = null) : PeerMessage;

    public sealed record SmsSendResult(
        [property: JsonPropertyName("clientId")] string ClientId,
        [property: JsonPropertyName("ok")] bool Ok,
        /// <summary>permission | disabled | invalid | radio | no-service | failed</summary>
        [property: JsonPropertyName("error")] string? Error = null) : PeerMessage;

    /// <summary>Phone to PC, on connect: SIMs that can send SMS.</summary>
    public sealed record SimList(
        [property: JsonPropertyName("sims")] IReadOnlyList<SimInfo> Sims) : PeerMessage;

    // ── Companion: calls (ADR-027), gated by PeerCapabilities.Calls ───────────────────────

    /// <summary>Phone to PC: call state changed. CallId is stable for one call.</summary>
    public sealed record CallState(
        [property: JsonPropertyName("callId")] string CallId,
        /// <summary>ringing | active | ended</summary>
        [property: JsonPropertyName("state")] string State,
        /// <summary>in | out</summary>
        [property: JsonPropertyName("direction")] string? Direction = null,
        [property: JsonPropertyName("number")] string? Number = null,
        [property: JsonPropertyName("name")] string? Name = null) : PeerMessage;

    /// <summary>
    /// PC to phone. Action: decline | silence | answer | end | dial (needs Number).
    /// Applied only if the phone holds the permission; audio stays on the phone.
    /// </summary>
    public sealed record CallAction(
        [property: JsonPropertyName("action")] string Action,
        [property: JsonPropertyName("callId")] string? CallId = null,
        [property: JsonPropertyName("number")] string? Number = null) : PeerMessage;

    // ── Companion: photos (ADR-034), gated by PeerCapabilities.Photos ─────────────────────
    // File names and images are user data: never log them.

    /// <summary>PC to phone: newest-first photo list.</summary>
    public sealed record PhotosRequest(
        [property: JsonPropertyName("limit")] int Limit = 2000) : PeerMessage;

    public sealed record Photos(
        [property: JsonPropertyName("items")] IReadOnlyList<PhotoInfo> Items) : PeerMessage;

    /// <summary>PC to phone: thumbnails for up to 50 ids; one PhotoThumb answers each id.</summary>
    public sealed record PhotoThumbRequest(
        [property: JsonPropertyName("ids")] IReadOnlyList<long> Ids,
        [property: JsonPropertyName("size")] int Size = 256) : PeerMessage;

    /// <summary>JPEG, longest side &lt;= requested size (max 512), &lt;= 48 KiB, base64.</summary>
    public sealed record PhotoThumb(
        [property: JsonPropertyName("id")] long Id,
        [property: JsonPropertyName("jpeg")] string? Jpeg = null) : PeerMessage;

    /// <summary>PC to phone: send the original as a TransferOffer carrying RequestId.</summary>
    public sealed record PhotoFetch(
        [property: JsonPropertyName("id")] long Id,
        [property: JsonPropertyName("requestId")] string RequestId) : PeerMessage;

    public sealed record PhotoNew(
        [property: JsonPropertyName("item")] PhotoInfo Item) : PeerMessage;

    public sealed record PhotoRemoved(
        [property: JsonPropertyName("id")] long Id) : PeerMessage;

    public sealed record CallLogRequest(
        [property: JsonPropertyName("limit")] int Limit = 100) : PeerMessage;

    public sealed record CallLog(
        [property: JsonPropertyName("entries")] IReadOnlyList<CallLogEntry> Entries) : PeerMessage;

    /// <summary>PC to phone. Each set field is a request the phone applies if permitted.</summary>
    public sealed record DeviceControl(
        /// <summary>True: ring at full volume until stopped or 30 s. False: stop.</summary>
        [property: JsonPropertyName("ring")] bool? Ring = null,
        [property: JsonPropertyName("ringer")] string? Ringer = null,
        [property: JsonPropertyName("dnd")] bool? Dnd = null,
        // ADR-060, only to a phone that advertises PhoneControls. 0..100; the phone ignores others.
        [property: JsonPropertyName("torch")] bool? Torch = null,
        [property: JsonPropertyName("mediaVolume")] int? MediaVolume = null,
        /// <summary>Also turns automatic brightness off on the phone.</summary>
        [property: JsonPropertyName("brightness")] int? Brightness = null,
        [property: JsonPropertyName("autoBrightness")] bool? AutoBrightness = null) : PeerMessage;

    /// <summary>Sent by the pairing initiator, sealed under the bootstrap keys (ADR-058).</summary>
    public sealed record PairHello(
        [property: JsonPropertyName("deviceId")] string DeviceId,
        [property: JsonPropertyName("deviceName")] string DeviceName,
        [property: JsonPropertyName("platform")] string Platform,
        [property: JsonPropertyName("appVersion")] string AppVersion,
        [property: JsonPropertyName("protocolVersion")] int ProtocolVersion = LinkProtocol.Version,
        [property: JsonPropertyName("publicKeyB64")] string? PublicKeyB64 = null,
        /// <summary>QR pairing: HMAC-SHA256(proofKey, ...) over the transcript, standard base64 (04-PROTOCOL.md 4.2).</summary>
        [property: JsonPropertyName("qrProof")] string? QrProof = null) : PeerMessage;

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
        [property: JsonPropertyName("publicKeyB64")] string? PublicKeyB64 = null) : PeerMessage;

    public sealed record PairFail(
        [property: JsonPropertyName("reason")] string Reason) : PeerMessage;

    /// <summary>First message of an established session. Confirms both sides can decrypt.</summary>
    public sealed record Hello(
        [property: JsonPropertyName("deviceId")] string DeviceId,
        [property: JsonPropertyName("deviceName")] string DeviceName,
        [property: JsonPropertyName("platform")] string Platform,
        [property: JsonPropertyName("capabilities")] int Capabilities = 0,
        [property: JsonPropertyName("maxChunk")] int MaxChunk = 4 * 1024 * 1024) : PeerMessage;

    public sealed record HelloAck(
        [property: JsonPropertyName("deviceId")] string DeviceId,
        [property: JsonPropertyName("deviceName")] string DeviceName,
        [property: JsonPropertyName("platform")] string Platform,
        [property: JsonPropertyName("capabilities")] int Capabilities = 0,
        [property: JsonPropertyName("chunkSize")] int ChunkSize = 4 * 1024 * 1024) : PeerMessage;

    public sealed record TransferOffer(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("items")] IReadOnlyList<LinkItem> Items,
        [property: JsonPropertyName("totalBytes")] long TotalBytes,
        [property: JsonPropertyName("senderName")] string SenderName,
        [property: JsonPropertyName("preferredRoutes")] IReadOnlyList<string> PreferredRoutes,
        [property: JsonPropertyName("senderWifiP2pAddress")] string? SenderWifiP2pAddress = null,
        /// <summary>Set when the offer answers a photoFetch; accepted without a prompt.</summary>
        [property: JsonPropertyName("requestId")] string? RequestId = null)
        : PeerMessage
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
               && RequestId == other.RequestId
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
        [property: JsonPropertyName("p2pPassphrase")] string? P2pPassphrase = null) : PeerMessage
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
        [property: JsonPropertyName("speedBps")] long SpeedBps = 0) : PeerMessage;

    public sealed record ItemBegin(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("size")] long Size,
        [property: JsonPropertyName("chunkCount")] int ChunkCount,
        [property: JsonPropertyName("chunkSize")] int ChunkSize) : PeerMessage;

    public sealed record ItemEnd(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("sha256")] string Sha256) : PeerMessage;

    public sealed record ItemResult(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("ok")] bool Ok,
        [property: JsonPropertyName("error")] string? Error = null) : PeerMessage;

    public sealed record TransferEnd(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("completed")] bool Completed,
        [property: JsonPropertyName("failedIndices")] IReadOnlyList<int>? FailedIndices = null)
        : PeerMessage
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
        [property: JsonPropertyName("dataB64")] string DataB64) : PeerMessage;

    public sealed record Cancel(
        [property: JsonPropertyName("transferId")] string TransferId,
        [property: JsonPropertyName("reason")] string Reason) : PeerMessage;

    public sealed record Ping(
        [property: JsonPropertyName("nonce")] string Nonce) : PeerMessage;

    public sealed record Pong(
        [property: JsonPropertyName("nonce")] string Nonce) : PeerMessage;
}

/// <summary>
/// The single JSON codec for the link protocol. Settings are normative and must match the Kotlin
/// <c>PeerMessageJson</c>: unknown keys ignored (never rejected, so a newer peer adding a field does not
/// break an older one), nulls omitted, and <c>"t"</c> as the type discriminator.
/// </summary>
public static class PeerMessageJson
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
