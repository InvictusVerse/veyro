package com.veyro.core.link

import com.veyro.core.model.TransferItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bumped on any change to bytes on the wire. 2 = confirm-code pairing (04-PROTOCOL.md 4.3,
 * ADR-031); version 1 peers are refused.
 */
public const val LINK_PROTOCOL_VERSION: Int = 2

/**
 * The unauthenticated identification preamble.
 *
 * Sent as the **first** frame of every connection, before any key exists. It is a hint and nothing
 * more: it says "I claim to be this device" so the receiver can decide which mode to run. It
 * carries no authority whatsoever, and the mode decision that follows is what grants anything.
 *
 * Ported from the Invox ecosystem handshake, which is the part that makes connecting work: without
 * a mode-branch step, a device has no way to tell "we are already paired, start a session" from
 * "we have never met, run pairing" before deciding which key to use.
 */
@Serializable
public data class LinkPreamble(
    val id: String,
    val name: String,
    val platform: String = "android",
    val protocolVersion: Int = LINK_PROTOCOL_VERSION,
    /** How to pair if the receiver does not know us: [PAIR_CONFIRM], [PAIR_QR], or absent (legacy PIN). */
    val pair: String? = null,
    /** Sender's static X25519 public key, base64. Sent with [PAIR_CONFIRM]. */
    val pk: String? = null,
) {
    public companion object {
        /** Compare a code on both screens and accept on the receiver (04-PROTOCOL.md 4.3). */
        public const val PAIR_CONFIRM: String = "confirm"

        /** The receiver's open QR window supplies the code (04-PROTOCOL.md 4.2). */
        public const val PAIR_QR: String = "qr"
    }
}

/** The receiver's answer: which mode this connection will run in. */
@Serializable
public data class LinkPreambleAck(
    /** `session` | `pairing` | `pairing-confirm` | `busy` | `refused` */
    val mode: String,
    val reason: String? = null,
    /** Receiver's static X25519 public key, base64. Only with [MODE_PAIRING_CONFIRM]. */
    val pk: String? = null,
    /** `SHA-256("veyro-commit-v1" || pkB || pkA || nB)`, base64. Only with [MODE_PAIRING_CONFIRM]. */
    val commit: String? = null,
) {
    public companion object {
        public const val MODE_SESSION: String = "session"
        public const val MODE_PAIRING: String = "pairing"
        public const val MODE_PAIRING_CONFIRM: String = "pairing-confirm"
        public const val MODE_BUSY: String = "busy"
        public const val MODE_REFUSED: String = "refused"
    }
}

/**
 * One nonce of the confirm exchange, an unauthenticated text frame like the preamble
 * (04-PROTOCOL.md 4.3). The initiator sends `nA` first; the receiver answers with `nB`, which its
 * commitment already fixed. Never logged.
 */
@Serializable
public data class LinkPairNonce(
    val t: String = TYPE,
    /** 32 random bytes, base64. */
    val n: String,
) {
    public companion object {
        public const val TYPE: String = "pairNonce"
    }
}

/**
 * The outer wire frame. Either `ciphertext` (session mode) or `plaintext` (pairing mode) is set.
 *
 * `from` travels in the clear because the receiver needs it to pick a key before it can verify
 * anything. It is therefore untrusted - but it is also inside the MAC, so a frame cannot be
 * re-attributed to a different sender without breaking the tag.
 */
@Serializable
public data class LinkWireFrame(
    @SerialName("f") val from: String,
    @SerialName("n") val nonce: String,
    @SerialName("c") val ciphertext: String? = null,
    @SerialName("p") val plaintext: String? = null,
    @SerialName("m") val mac: String,
)

/**
 * The envelope inside a frame.
 *
 * `seq` is monotonic per sender and is enforced on receipt, which is what makes a reordered or
 * truncated stream detectable rather than silently accepted.
 */
@Serializable
public data class LinkEnvelope(
    val v: Int = LINK_PROTOCOL_VERSION,
    val msgId: String,
    val from: String,
    val seq: Long = 0,
    val ts: Long,
    val payload: LinkMessage,
)

/**
 * Everything that can travel inside an envelope.
 *
 * ## Forward compatibility is mandatory
 *
 * Every field added after v1 must be nullable with a null default, so an older peer omits it rather
 * than failing to decode. `ignoreUnknownKeys` handles the other direction. Together those mean a
 * new field is **not** a breaking change and does not need a version bump - only removing a field
 * or changing its meaning does.
 *
 * This discipline is taken directly from the Invox protocol, where it is the reason two app versions
 * can talk to each other at all.
 */
@Serializable
public sealed interface LinkMessage {

    /** Sent by the pairing initiator, under the PIN-derived bootstrap key. */
    @Serializable
    @SerialName("pairHello")
    public data class PairHello(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val appVersion: String,
        val protocolVersion: Int = LINK_PROTOCOL_VERSION,
        val publicKeyB64: String? = null,
    ) : LinkMessage

    /**
     * The target's answer, carrying the long-lived session secret.
     *
     * **Always sent sealed**, even though the session is still nominally in plaintext pairing mode.
     * The secret must not cross the LAN in the clear, and the bootstrap key is available to seal it
     * with. Invox had to keep a plaintext fallback for older peers; Veyro has no deployed versions,
     * so sealing is unconditional and there is no downgrade path.
     */
    @Serializable
    @SerialName("pairOk")
    public data class PairOk(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val sessionSecretB64: String,
        val publicKeyB64: String? = null,
    ) : LinkMessage

    @Serializable
    @SerialName("pairFail")
    public data class PairFail(val reason: String) : LinkMessage

    /** First message of an established session. Confirms both sides can decrypt. */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val capabilities: Int = 0,
        val maxChunk: Int = 4 * 1024 * 1024,
    ) : LinkMessage

    @Serializable
    @SerialName("helloAck")
    public data class HelloAck(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val capabilities: Int = 0,
        val chunkSize: Int = 4 * 1024 * 1024,
    ) : LinkMessage

    // ── Transfer ───────────────────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("offer")
    public data class TransferOffer(
        val transferId: String,
        val items: List<Item>,
        val totalBytes: Long,
        val senderName: String,
        val preferredRoutes: List<String> = emptyList(),
        val senderWifiP2pAddress: String? = null,
        /** Set when this offer answers a `photoFetch`; the receiver accepts it without a prompt. */
        val requestId: String? = null,
    ) : LinkMessage

    @Serializable
    @SerialName("decision")
    public data class TransferDecision(
        val transferId: String,
        val accept: Boolean,
        val reason: String? = null,
        /** itemIndex to the chunk indices still needed. Absent means "send everything". */
        val resume: Map<Int, List<Int>>? = null,
        val route: String? = null,
        val tcpHost: String? = null,
        val tcpPort: Int? = null,
        val fallbackHost: String? = null,
        val authToken: String? = null,
        val p2pSsid: String? = null,
        val p2pPassphrase: String? = null,
    ) : LinkMessage

    /**
     * Genuine receiver-side progress notification reported over WebSocket.
     */
    @Serializable
    @SerialName("progressNotice")
    public data class TransferProgressNotice(
        val transferId: String,
        val bytesReceived: Long,
        val totalBytes: Long,
        val currentItemIndex: Int = 0,
        val speedBps: Long = 0L,
    ) : LinkMessage

    @Serializable
    @SerialName("itemBegin")
    public data class ItemBegin(
        val transferId: String,
        val index: Int,
        val size: Long,
        val chunkCount: Int,
        val chunkSize: Int,
    ) : LinkMessage

    @Serializable
    @SerialName("itemEnd")
    public data class ItemEnd(
        val transferId: String,
        val index: Int,
        val sha256: String,
    ) : LinkMessage

    @Serializable
    @SerialName("itemResult")
    public data class ItemResult(
        val transferId: String,
        val index: Int,
        val ok: Boolean,
        val error: String? = null,
    ) : LinkMessage

    @Serializable
    @SerialName("transferEnd")
    public data class TransferEnd(
        val transferId: String,
        val completed: Boolean,
        val failedIndices: List<Int> = emptyList(),
    ) : LinkMessage

    /**
     * One chunk of file data, base64 in the JSON envelope.
     *
     * Base64 costs 33% overhead, which is real but acceptable at this stage: it keeps **one** frame
     * format for everything, so there is no second binary path to get wrong. Moving bulk data to
     * binary WebSocket frames is a measured optimisation for later, not a correctness issue - and
     * doing it now would mean two framing paths before either is proven.
     */
    @Serializable
    @SerialName("chunk")
    public data class Chunk(
        val transferId: String,
        val index: Int,
        val chunkIndex: Int,
        val dataB64: String,
    ) : LinkMessage

    @Serializable
    @SerialName("cancel")
    public data class Cancel(val transferId: String, val reason: String) : LinkMessage

    @Serializable
    @SerialName("ping")
    public data class Ping(val nonce: String) : LinkMessage

    @Serializable
    @SerialName("pong")
    public data class Pong(val nonce: String) : LinkMessage

    // ── Companion: notification mirroring (ADR-025) ──────────────────────────────────────────
    //
    // Only sent to a peer whose Hello/HelloAck `capabilities` include NOTIFICATION_MIRROR
    // (0x0100). An older peer cannot decode these types, so the capability gate is what makes
    // them non-breaking. Contents are user data: never log title, text or messages.

    /** Phone to PC: a notification was posted, is already active, or was removed. */
    @Serializable
    @SerialName("notif")
    public data class Notification(
        /** `StatusBarNotification.key`. Opaque; identifies the notification in every reply. */
        val key: String,
        /** `posted` | `active` | `removed`. A `removed` frame carries only `key`. */
        val kind: String,
        val pkg: String? = null,
        val app: String? = null,
        val title: String? = null,
        val text: String? = null,
        /** Conversation lines for messaging-style notifications, oldest first. */
        val messages: List<NotificationLine>? = null,
        val ts: Long? = null,
        val group: String? = null,
        val actions: List<NotificationActionInfo>? = null,
        /** True when the notification has a RemoteInput reply action. */
        val canReply: Boolean? = null,
        /** App icon as PNG, at most 96x96 and 16 KiB, base64. Absent if unavailable or too big. */
        val iconPng: String? = null,
    ) : LinkMessage {
        public companion object {
            public const val KIND_POSTED: String = "posted"
            public const val KIND_ACTIVE: String = "active"
            public const val KIND_REMOVED: String = "removed"
        }
    }

    /** PC to phone: invoke action [index] (index into the phone's own `Notification.actions`). */
    @Serializable
    @SerialName("notifAction")
    public data class NotificationActionRequest(val key: String, val index: Int) : LinkMessage

    /** PC to phone: send [text] through the notification's RemoteInput reply action. */
    @Serializable
    @SerialName("notifReply")
    public data class NotificationReplyRequest(val key: String, val text: String) : LinkMessage

    /** PC to phone: dismiss the notification on the phone. */
    @Serializable
    @SerialName("notifDismiss")
    public data class NotificationDismissRequest(val key: String) : LinkMessage

    // ── Companion: media control (ADR-026), gated by MEDIA_CONTROL (0x0200) ──────────────────
    //
    // Symmetric: each side publishes its own media sessions with `mediaState` and the other side
    // drives them with `mediaCmd`. Position is not streamed: the receiver extrapolates from
    // `position` + `ts` while `playing`. Track metadata is user data: never log it.

    /** Full snapshot of the sender's media sessions. Sent on connect, then on every change. */
    @Serializable
    @SerialName("mediaState")
    public data class MediaState(
        val sessions: List<MediaSessionInfo>,
        /** Sender's master volume 0..100, if the sender can report it. */
        val volume: Int? = null,
        val muted: Boolean? = null,
    ) : LinkMessage

    /**
     * Drive a session on the receiver. [action]: `play` | `pause` | `toggle` | `next` | `prev` |
     * `seek` (value = position ms) | `volume` (value = 0..100, master, [id] ignored) |
     * `mute` (value = 1 or 0, master). Unknown actions and unknown [id]s are ignored.
     */
    @Serializable
    @SerialName("mediaCmd")
    public data class MediaCommand(
        val action: String,
        val id: String? = null,
        val value: Long? = null,
    ) : LinkMessage {
        public companion object {
            public const val PLAY: String = "play"
            public const val PAUSE: String = "pause"
            public const val TOGGLE: String = "toggle"
            public const val NEXT: String = "next"
            public const val PREV: String = "prev"
            public const val SEEK: String = "seek"
            public const val VOLUME: String = "volume"
            public const val MUTE: String = "mute"
        }
    }

    @Serializable
    public data class MediaSessionInfo(
        /** Sender-chosen opaque id, stable for the session's lifetime. */
        val id: String,
        val app: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val playing: Boolean = false,
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        /** Sender clock (epoch ms) at which [positionMs] was sampled. */
        val ts: Long? = null,
        val canPrev: Boolean = false,
        val canNext: Boolean = false,
        val canSeek: Boolean = false,
        /** Artwork PNG or JPEG, at most 160x160 and 24 KiB, base64. */
        val art: String? = null,
    )

    // ── Companion: device status (ADR-026), gated by DEVICE_STATUS (0x0400) ──────────────────

    /** Sender's own status. Every field optional; absent means "unknown/unchanged-unsupported". */
    @Serializable
    @SerialName("devStatus")
    public data class DeviceStatus(
        val battery: Int? = null,
        val charging: Boolean? = null,
        /** `normal` | `vibrate` | `silent` (phones only). */
        val ringer: String? = null,
        val dnd: Boolean? = null,
        /** Wi-Fi signal 0..4, or -1 when Wi-Fi is off/disconnected (ADR-038). */
        val wifi: Int? = null,
        /** Strongest cellular signal 0..4, or -1 for no service. */
        val cell: Int? = null,
        /** Carrier name of the data SIM. User data: never log. */
        val carrier: String? = null,
        val bluetooth: Boolean? = null,
        /** Mobile data in use for the internet connection. */
        val mobileData: Boolean? = null,
    ) : LinkMessage

    /**
     * Either direction: "I removed you". The receiver deletes its trust record for the sender and
     * closes the session, so removing a device on one side removes it on both (ADR-042). Older
     * peers drop the unknown type, which is harmless: they simply fail to authenticate next time.
     */
    @Serializable
    @SerialName("unpair")
    public data object Unpair : LinkMessage

    /**
     * Either direction: one clipboard item (ADR-043, 04-PROTOCOL.md 6.5), gated by CLIPBOARD.
     *
     * [apply] true: put it on the receiver's system clipboard now (text or a link shared from the
     * phone). False: keep it in the receiver's virtual clipboard (the PC's own copies arriving on
     * the phone, or the phone's clipboard sent with the in-app button). Exactly one of [text] or
     * [image] is set. Clipboard contents are user data: never log them.
     */
    @Serializable
    @SerialName("clip")
    public data class Clip(
        /** Sender-chosen id, unique per item, so a receiver can drop duplicates. */
        val id: String,
        /** `text` | `image` */
        val kind: String,
        /** UTF-8 text, at most [MAX_TEXT_CHARS]. */
        val text: String? = null,
        /** PNG or JPEG, at most [MAX_IMAGE_BYTES] before base64. */
        val image: String? = null,
        val apply: Boolean = false,
        val ts: Long = 0,
    ) : LinkMessage {
        public companion object {
            public const val KIND_TEXT: String = "text"
            public const val KIND_IMAGE: String = "image"
            public const val MAX_TEXT_CHARS: Int = 100_000
            public const val MAX_IMAGE_BYTES: Int = 2 * 1024 * 1024
        }
    }

    /** Phone to PC: lock the PC (Phone Link "Lock PC"). Gated by DEVICE_STATUS (ADR-038). */
    @Serializable
    @SerialName("pcLock")
    public data object PcLock : LinkMessage

    /**
     * Either direction: the sender's wallpaper, sent on connect and when it changes.
     * JPEG, longest side <= 720 px, <= 96 KiB, base64. Gated by WALLPAPER (0x4000).
     */
    @Serializable
    @SerialName("wallpaper")
    public data class Wallpaper(val jpeg: String? = null) : LinkMessage

    /** PC to phone. Each set field is a request; the phone applies what it is permitted to. */
    @Serializable
    @SerialName("devControl")
    public data class DeviceControl(
        /** True: ring at full volume until stopped or 30 s; false: stop. */
        val ring: Boolean? = null,
        val ringer: String? = null,
        val dnd: Boolean? = null,
    ) : LinkMessage

    // ── Companion: SMS (ADR-027), gated by MESSAGING (0x0800) ───────────────────────────────
    //
    // Pull model: the PC asks for threads and pages of history; the phone pushes only new
    // messages and send results. Bodies, numbers and names are user data: never log them.

    /** PC to phone: list recent threads, newest first. */
    @Serializable
    @SerialName("smsThreadsReq")
    public data class SmsThreadsRequest(val limit: Int = 50) : LinkMessage

    @Serializable
    @SerialName("smsThreads")
    public data class SmsThreads(val threads: List<SmsThread>) : LinkMessage

    /** PC to phone: one page of a thread, newest first, strictly older than [beforeTs] if set. */
    @Serializable
    @SerialName("smsMessagesReq")
    public data class SmsMessagesRequest(
        val threadId: Long,
        val beforeTs: Long? = null,
        val limit: Int = 50,
    ) : LinkMessage

    @Serializable
    @SerialName("smsMessages")
    public data class SmsMessages(
        val threadId: Long,
        val messages: List<SmsMessage>,
        /** True if older messages exist before the last one in [messages]. */
        val more: Boolean = false,
    ) : LinkMessage

    /** Phone to PC: a message arrived or was sent (by any app) since the session started. */
    @Serializable
    @SerialName("smsNew")
    public data class SmsNew(val message: SmsMessage, val thread: SmsThread? = null) : LinkMessage

    /** PC to phone: send a text. [clientId] is echoed in [SmsSendResult]. */
    @Serializable
    @SerialName("smsSend")
    public data class SmsSend(
        val clientId: String,
        val addresses: List<String>,
        val body: String,
        /** Subscription id from [SimList]; absent means the phone's default SMS SIM. */
        val sim: Int? = null,
    ) : LinkMessage

    @Serializable
    @SerialName("smsSendResult")
    public data class SmsSendResult(
        val clientId: String,
        val ok: Boolean,
        /** `permission` | `disabled` | `invalid` | `radio` | `no-service` | `failed` */
        val error: String? = null,
    ) : LinkMessage

    /** Phone to PC, on connect: SIMs that can send SMS. */
    @Serializable
    @SerialName("simList")
    public data class SimList(val sims: List<SimInfo>) : LinkMessage

    @Serializable
    public data class SmsThread(
        val threadId: Long,
        val addresses: List<String>,
        /** Contact display names aligned with [addresses]; null entries where unknown. */
        val names: List<String?>? = null,
        val snippet: String? = null,
        val ts: Long,
        val unread: Int = 0,
    )

    @Serializable
    public data class SmsMessage(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val ts: Long,
        /** True if sent from this phone. */
        val out: Boolean,
        val read: Boolean = true,
        /** `sent` | `pending` | `failed`, outgoing only. */
        val status: String? = null,
        val sim: Int? = null,
    )

    @Serializable
    public data class SimInfo(val id: Int, val label: String, val slot: Int? = null)

    // ── Companion: calls (ADR-027), gated by CALLS (0x1000) ─────────────────────────────────

    /** Phone to PC: call state changed. [callId] is stable for one call. */
    @Serializable
    @SerialName("callState")
    public data class CallState(
        val callId: String,
        /** `ringing` | `active` | `ended` */
        val state: String,
        /** `in` | `out` */
        val direction: String? = null,
        val number: String? = null,
        val name: String? = null,
    ) : LinkMessage

    /**
     * PC to phone. [action]: `decline` | `silence` | `answer` | `end` | `dial` (needs [number]).
     * Applied only if the phone holds the needed permission; audio stays on the phone.
     */
    @Serializable
    @SerialName("callAction")
    public data class CallAction(
        val action: String,
        val callId: String? = null,
        val number: String? = null,
    ) : LinkMessage

    @Serializable
    @SerialName("callLogReq")
    public data class CallLogRequest(val limit: Int = 100) : LinkMessage

    @Serializable
    @SerialName("callLog")
    public data class CallLog(val entries: List<CallLogEntry>) : LinkMessage

    @Serializable
    public data class CallLogEntry(
        val id: Long,
        val number: String,
        val name: String? = null,
        val ts: Long,
        val durationSec: Long = 0,
        /** `in` | `out` | `missed` | `rejected` | `blocked` */
        val type: String,
    )

    // ── Companion: photos (ADR-034), gated by PHOTOS (0x2000) ──────────────────────────────
    //
    // Phone Link-style recent photos. The PC lists metadata, pulls thumbnails for what is on
    // screen, and fetches an original as a normal transfer whose offer carries [requestId].
    // File names and images are user data: never log them.

    /** PC to phone: newest-first photo list. */
    @Serializable
    @SerialName("photosReq")
    public data class PhotosRequest(val limit: Int = 2000) : LinkMessage

    @Serializable
    @SerialName("photos")
    public data class Photos(val items: List<PhotoInfo>) : LinkMessage

    /** PC to phone: thumbnails for up to 50 ids. Answered by one [PhotoThumb] per id. */
    @Serializable
    @SerialName("photoThumbReq")
    public data class PhotoThumbRequest(val ids: List<Long>, val size: Int = 256) : LinkMessage

    @Serializable
    @SerialName("photoThumb")
    public data class PhotoThumb(
        val id: Long,
        /** JPEG, longest side <= requested size (max 512), <= 48 KiB, base64. Absent if unreadable. */
        val jpeg: String? = null,
    ) : LinkMessage

    /** PC to phone: send the original. The phone answers with a TransferOffer carrying [requestId]. */
    @Serializable
    @SerialName("photoFetch")
    public data class PhotoFetch(val id: Long, val requestId: String) : LinkMessage

    /** Phone to PC: a photo was added (camera, screenshot) since the list was sent. */
    @Serializable
    @SerialName("photoNew")
    public data class PhotoNew(val item: PhotoInfo) : LinkMessage

    @Serializable
    @SerialName("photoRemoved")
    public data class PhotoRemoved(val id: Long) : LinkMessage

    @Serializable
    public data class PhotoInfo(
        /** MediaStore `_ID`. */
        val id: Long,
        val name: String,
        /** Date taken (or added) in epoch ms. */
        val ts: Long,
        val width: Int? = null,
        val height: Int? = null,
        val size: Long? = null,
        val mime: String? = null,
        /** `camera` | `screenshots` */
        val bucket: String? = null,
    )

    @Serializable
    public data class NotificationLine(val sender: String? = null, val text: String)

    @Serializable
    public data class NotificationActionInfo(val index: Int, val label: String)

    @Serializable
    public data class Item(
        val index: Int,
        val relPath: String,
        val size: Long,
        val mtime: Long,
        val isDir: Boolean = false,
    ) {
        public fun toDomain(): TransferItem = TransferItem(
            index = index,
            relativePath = relPath,
            sizeBytes = size,
            lastModifiedEpochMillis = mtime,
            isDirectory = isDir,
        )
    }
}

/** The single JSON codec for the link protocol. */
public object LinkJson {
    public val codec: Json = Json {
        // Unknown keys ignored, never rejected: a newer peer adding a field must not break an
        // older one (see the LinkMessage note on forward compatibility).
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "t"
        explicitNulls = false
    }
}

public fun TransferItem.toLinkItem(): LinkMessage.Item = LinkMessage.Item(
    index = index,
    relPath = relativePath,
    size = sizeBytes,
    mtime = lastModifiedEpochMillis,
    isDir = isDirectory,
)
