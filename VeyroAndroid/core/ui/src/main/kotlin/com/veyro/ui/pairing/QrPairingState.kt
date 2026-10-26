package com.veyro.ui.pairing

import com.veyro.core.link.ConnectionManager.CodePairingFailure
import com.veyro.core.protocol.PairingQrCode

/** Where a scan-to-pair attempt is (ADR-030). Shown as one status card. */
public sealed interface QrPairingState {
    public data object Idle : QrPairingState

    /** Dialling the endpoints from the code (and any discovered for the same id). */
    public data class Connecting(val pcName: String?) : QrPairingState

    /** The PC answered in pairing mode; the code-derived key exchange is running. */
    public data class Securing(val pcName: String?) : QrPairingState

    public data class Paired(val pcName: String, val fingerprint: String) : QrPairingState

    public data class Failed(val error: QrPairingError) : QrPairingState
}

/** Named failures, each with text the user can act on. */
public enum class QrPairingError(public val title: String, public val hint: String) {
    SCANNER_UNAVAILABLE(
        "QR scanning is not available",
        "This phone has no working Google Play services scanner right now. Pick your PC from " +
            "the list below instead; both screens then show the same code.",
    ),
    NOT_A_VEYRO_CODE(
        "That is not a Veyro pairing code",
        "Scan the QR code on the pairing screen of Veyro for Windows.",
    ),
    UNSUPPORTED_VERSION(
        "This code is from a newer Veyro",
        "Update Veyro on this phone, or pick the PC from the nearby list instead.",
    ),
    EXPIRED(
        "This code has expired",
        "Open the pairing screen on your PC again to get a fresh code, then scan it.",
    ),
    UNREACHABLE(
        "Could not reach your PC",
        "Make sure the phone and PC are on the same Wi-Fi network, and that Windows Firewall " +
            "allows Veyro on private networks.",
    ),
    NOT_ACCEPTING(
        "Your PC is not accepting pairing right now",
        "Keep the pairing screen open on the PC and scan again.",
    ),
    CODE_REJECTED(
        "The PC rejected the code",
        "The code may have changed. Scan the code currently shown on the PC.",
    ),
    IDENTITY_MISMATCH(
        "This is not the PC you scanned",
        "A different device answered at that address, so nothing was saved. Scan again on " +
            "your own network.",
    ),
    OTHER(
        "Pairing did not finish",
        "Try scanning again. If it keeps failing, pick the PC from the nearby list instead.",
    ),
    ;

    public companion object {
        public fun from(error: PairingQrCode.Error): QrPairingError = when (error) {
            PairingQrCode.Error.EXPIRED -> EXPIRED
            PairingQrCode.Error.UNSUPPORTED_VERSION -> UNSUPPORTED_VERSION
            else -> NOT_A_VEYRO_CODE
        }

        public fun from(failure: CodePairingFailure): QrPairingError = when (failure) {
            CodePairingFailure.UNREACHABLE -> UNREACHABLE
            CodePairingFailure.NOT_ACCEPTING -> NOT_ACCEPTING
            CodePairingFailure.CODE_REJECTED -> CODE_REJECTED
            CodePairingFailure.IDENTITY_MISMATCH -> IDENTITY_MISMATCH
            // Confirm-pairing outcomes; a QR pairing does not produce them.
            CodePairingFailure.DECLINED, CodePairingFailure.TIMEOUT -> NOT_ACCEPTING
            CodePairingFailure.CANCELLED, CodePairingFailure.OTHER -> OTHER
        }
    }
}
