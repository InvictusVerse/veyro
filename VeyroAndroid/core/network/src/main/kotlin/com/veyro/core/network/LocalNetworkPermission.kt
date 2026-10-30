package com.veyro.core.network

/**
 * Where Veyro stands with Android 17's local network permission (ADR-061).
 *
 * An app targeting API 37 has every local network packet blocked on Android 17 (outgoing and
 * incoming TCP, UDP unicast, multicast and broadcast, mDNS) until the user grants
 * `ACCESS_LOCAL_NETWORK`. Veyro is nothing but local network traffic, so without it no device can
 * be found, paired or connected.
 */
public enum class LocalNetworkState {
    /** Below Android 17: the network is open, nothing to ask for. */
    NOT_NEEDED,

    GRANTED,

    /** Never asked on this install: the system dialog can be shown. */
    NOT_ASKED,

    /** Denied, and Android still lets Veyro ask again. */
    DENIED_CAN_ASK,

    /** Denied (or revoked) and Android no longer shows the dialog: only App info can grant it. */
    DENIED_SETTINGS,
    ;

    /** Local network traffic is blocked right now. */
    public val isBlocked: Boolean get() = this == NOT_ASKED || this == DENIED_CAN_ASK || this == DENIED_SETTINGS
}

/** What a button that fixes [LocalNetworkState] should do. */
public enum class LocalNetworkFix {
    NONE,

    /** Show the system permission dialog. */
    REQUEST,

    /** Open Veyro's App info so the user can allow "Nearby devices" there. */
    OPEN_SETTINGS,
}

/**
 * The decisions around `ACCESS_LOCAL_NETWORK`, as plain functions of plain values so they are
 * JVM-tested. The Android reads (SDK level, the grant, whether a rationale may be shown, whether it
 * was asked before) happen in `:app`.
 *
 * The permission must never be requested below API 37: Android's guidance for apps not yet
 * enforced is to leave it alone, and an older system does not know it.
 */
public object LocalNetworkPermission {

    /** `android.permission.ACCESS_LOCAL_NETWORK`, in the NEARBY_DEVICES group. */
    public const val NAME: String = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Android 17. Enforcement applies to apps targeting this level, which Veyro does. */
    public const val ENFORCED_FROM_API: Int = 37

    public fun isRequired(sdkInt: Int): Boolean = sdkInt >= ENFORCED_FROM_API

    /**
     * [rationaleAllowed] is `shouldShowRequestPermissionRationale`, which is false both before the
     * first request and after a final denial; [askedBefore] tells the two apart.
     */
    public fun state(sdkInt: Int, granted: Boolean, askedBefore: Boolean, rationaleAllowed: Boolean): LocalNetworkState =
        when {
            !isRequired(sdkInt) -> LocalNetworkState.NOT_NEEDED
            granted -> LocalNetworkState.GRANTED
            !askedBefore -> LocalNetworkState.NOT_ASKED
            rationaleAllowed -> LocalNetworkState.DENIED_CAN_ASK
            else -> LocalNetworkState.DENIED_SETTINGS
        }

    /** Asked for with the other launch permissions, once, so a new install just works. */
    public fun requestAtLaunch(state: LocalNetworkState): Boolean = state == LocalNetworkState.NOT_ASKED

    /** What the "Allow" button on Home, onboarding and the checklist does. */
    public fun fix(state: LocalNetworkState): LocalNetworkFix = when (state) {
        LocalNetworkState.NOT_NEEDED, LocalNetworkState.GRANTED -> LocalNetworkFix.NONE
        LocalNetworkState.NOT_ASKED, LocalNetworkState.DENIED_CAN_ASK -> LocalNetworkFix.REQUEST
        LocalNetworkState.DENIED_SETTINGS -> LocalNetworkFix.OPEN_SETTINGS
    }

    /**
     * Home's notice shows for every blocked state, NOT_ASKED included: a launch request the user
     * dismissed, or a first start from boot, must still say why nothing connects.
     */
    public fun showNotice(state: LocalNetworkState): Boolean = state.isBlocked

    /**
     * The onboarding Pair page asks by itself once, when it is reached and nothing was asked yet:
     * pairing finds nothing without it. Later states wait for the user's tap.
     */
    public fun requestOnPairPage(state: LocalNetworkState): Boolean = state == LocalNetworkState.NOT_ASKED

    /** The notice's title. */
    public const val TITLE: String = "Veyro can't reach your devices"

    /** Plain words for what is wrong and how to fix it, for the notice and the checklist. */
    public fun explanation(state: LocalNetworkState): String = when (state) {
        LocalNetworkState.NOT_NEEDED, LocalNetworkState.GRANTED ->
            "Veyro can find and connect to your PC and phones on this Wi-Fi."
        LocalNetworkState.NOT_ASKED, LocalNetworkState.DENIED_CAN_ASK ->
            "Android blocks apps from your home Wi-Fi until you allow it. Veyro uses it only to " +
                "find and connect to your own PC and phones. Tap Allow, then choose Allow."
        LocalNetworkState.DENIED_SETTINGS ->
            "Android blocks apps from your home Wi-Fi until you allow it. Veyro uses it only to " +
                "find and connect to your own PC and phones. Tap Open settings, then " +
                "Permissions > Nearby devices > Allow."
    }

    /** The button label for [fix]; null when there is nothing to fix. */
    public fun actionLabel(state: LocalNetworkState): String? = when (fix(state)) {
        LocalNetworkFix.NONE -> null
        LocalNetworkFix.REQUEST -> "Allow"
        LocalNetworkFix.OPEN_SETTINGS -> "Open settings"
    }

    /** The background notification while blocked (ADR-061): it must not fail silently. */
    public const val BACKGROUND_TITLE: String = TITLE
    public const val BACKGROUND_TEXT: String =
        "Android blocks the local network until you allow Nearby devices for Veyro. Tap to fix it."
}
