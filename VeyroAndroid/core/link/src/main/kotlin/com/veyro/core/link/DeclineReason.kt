package com.veyro.core.link

/**
 * Why a receiver answered an offer with `decision{accept:false}` (04-PROTOCOL.md 6.4).
 *
 * The wire carries a free-text `reason`; both clients send one of the four tokens below. The
 * sender shows the matching sentence instead of a generic "declined", because "busy" and "error"
 * need a different reaction from the user than a real decline.
 */
public enum class DeclineReason(public val wire: String) {
    /** The user tapped Decline. */
    DECLINED("declined"),

    /** Nobody answered the prompt in time. */
    TIMEOUT("timeout"),

    /** The receiver is already running a transfer. */
    BUSY("busy"),

    /** The receiver could not take the transfer (no endpoint, storage, internal failure). */
    ERROR("error"),
    ;

    /** One sentence for the sender's UI, naming the receiving device. */
    public fun message(peerName: String): String = when (this) {
        DECLINED -> "$peerName declined the files"
        TIMEOUT -> "Nobody accepted on $peerName in time"
        BUSY -> "$peerName is busy with another transfer. Try again when it finishes."
        ERROR -> "$peerName could not receive the files"
    }

    public companion object {
        /**
         * Maps a wire reason to a [DeclineReason]. An absent reason is a plain decline (older
         * peers sent none); any other unrecognised text is reported as [ERROR] rather than
         * pretending the user said no.
         */
        public fun fromWire(reason: String?): DeclineReason {
            val token = reason?.trim()?.lowercase() ?: return DECLINED
            return when (token) {
                "", "declined", "user declined", "rejected" -> DECLINED
                "timeout", "timed out" -> TIMEOUT
                "busy" -> BUSY
                else -> ERROR
            }
        }
    }
}
