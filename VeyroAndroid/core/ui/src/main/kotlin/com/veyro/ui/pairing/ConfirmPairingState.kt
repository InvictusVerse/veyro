package com.veyro.ui.pairing

import com.veyro.core.link.ConnectionManager.CodePairingFailure

/**
 * Where a pairing started from the nearby list is (confirm code, ADR-031). Shown as one dialog.
 * [onPc] picks the wording: a PC is accepted with a click, a phone with a tap.
 */
public sealed interface ConfirmPairingState {
    public data object Idle : ConfirmPairingState

    public data class Connecting(val peerName: String, val onPc: Boolean) : ConfirmPairingState

    /** Keys and nonces are being exchanged; the code is not known yet. */
    public data class Securing(val peerName: String, val onPc: Boolean) : ConfirmPairingState

    /** Both screens show [code]; waiting for Accept on the other device until [expiresAtMillis]. */
    public data class AwaitingAcceptance(
        val peerName: String,
        val onPc: Boolean,
        val code: String,
        val expiresAtMillis: Long,
    ) : ConfirmPairingState {
        override fun toString(): String = "AwaitingAcceptance(peerName=$peerName)"
    }

    public data class Paired(val peerName: String, val fingerprint: String) : ConfirmPairingState

    public data class Failed(val peerName: String, val onPc: Boolean, val error: ConfirmPairingError) :
        ConfirmPairingState
}

/** Named failures, each with text the user can act on. */
public enum class ConfirmPairingError(public val title: String, public val hint: String) {
    DECLINED(
        "Pairing was declined",
        "Decline was chosen on the other device. Pair again and accept there if the codes match.",
    ),
    TIMEOUT(
        "Nobody accepted in time",
        "Pair again and accept on the other device within two minutes.",
    ),
    UNREACHABLE(
        "Could not reach the device",
        "Make sure both devices are on the same Wi-Fi network. On a PC, Windows Firewall must " +
            "allow Veyro on private networks; Veyro's network check on the PC shows what is blocking.",
    ),
    NOT_ACCEPTING(
        "The device is not accepting pairing right now",
        "It may be busy with another device. Try again in a moment.",
    ),
    IDENTITY_MISMATCH(
        "Could not verify this device",
        "The device that answered is not the one you picked, so nothing was saved. Try again on " +
            "a network you trust.",
    ),
    OTHER(
        "Pairing did not finish",
        "Try again. If it keeps failing, scan the QR code on the PC instead.",
    ),
    ;

    public companion object {
        /** Null for [CodePairingFailure.CANCELLED]: the user cancelled, so there is nothing to explain. */
        public fun from(failure: CodePairingFailure): ConfirmPairingError? = when (failure) {
            CodePairingFailure.UNREACHABLE -> UNREACHABLE
            CodePairingFailure.NOT_ACCEPTING -> NOT_ACCEPTING
            CodePairingFailure.DECLINED -> DECLINED
            CodePairingFailure.TIMEOUT -> TIMEOUT
            CodePairingFailure.IDENTITY_MISMATCH -> IDENTITY_MISMATCH
            CodePairingFailure.CANCELLED -> null
            CodePairingFailure.CODE_REJECTED, CodePairingFailure.OTHER -> OTHER
        }
    }
}
