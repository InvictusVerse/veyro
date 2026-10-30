package com.veyro.ui.keepalive

import com.veyro.core.network.LocalNetworkPermission
import com.veyro.core.network.LocalNetworkState

/**
 * What `:app` read from the system for the "Keep Veyro connected" checklist (ADR-061). Plain
 * values, so the checklist is JVM-tested; every read happens again when the screen resumes,
 * since all of these change in system screens outside the app.
 */
public data class KeepAliveFacts(
    val sdkInt: Int,
    val brand: OemBrand,
    val localNetwork: LocalNetworkState,
    /** `NotificationManager.areNotificationsEnabled()`: the permission on 33+, the app switch below. */
    val notificationsEnabled: Boolean,
    val batteryOptimizationIgnored: Boolean,
    /** `ActivityManager.isBackgroundRestricted()` (API 28+). */
    val backgroundRestricted: Boolean,
    /** `UsageStatsManager.getAppStandbyBucket()` (API 28+); null when it could not be read. */
    val standbyBucket: Int?,
    /** The maker screens that resolved on this phone. */
    val oemScreens: Set<OemPurpose> = emptySet(),
)

/** A system screen a checklist button opens. [LOCAL_NETWORK] goes through the Activity. */
public enum class KeepAliveTarget {
    LOCAL_NETWORK,
    NOTIFICATIONS,
    BATTERY_EXEMPTION,
    APP_INFO,
    OEM_AUTOSTART,
    OEM_BATTERY,
}

public enum class CheckId { LOCAL_NETWORK, NOTIFICATIONS, BATTERY_OPTIMIZATION, BACKGROUND_RESTRICTION, STANDBY_BUCKET, MANUFACTURER }

public enum class CheckStatus {
    OK,

    /** Set the wrong way for a background connection; the button fixes it. */
    NEEDS_ACTION,

    /** Shown for information only. */
    INFO,

    /** Veyro cannot read it (maker settings): follow the steps. */
    CHECK,
}

public data class CheckAction(val label: String, val target: KeepAliveTarget)

public data class CheckRow(
    val id: CheckId,
    val title: String,
    val summary: String,
    val status: CheckStatus,
    val actions: List<CheckAction> = emptyList(),
    val steps: List<String> = emptyList(),
)

/** The checklist's rows and the decisions built on them (ADR-061). */
public object KeepConnectedChecklist {

    /** `UsageStatsManager.STANDBY_BUCKET_*` values (API 28+; 45 is API 30+). */
    public const val BUCKET_EXEMPTED: Int = 5
    public const val BUCKET_ACTIVE: Int = 10
    public const val BUCKET_WORKING_SET: Int = 20
    public const val BUCKET_FREQUENT: Int = 30
    public const val BUCKET_RARE: Int = 40
    public const val BUCKET_RESTRICTED: Int = 45

    public fun rows(facts: KeepAliveFacts): List<CheckRow> = listOfNotNull(
        localNetworkRow(facts),
        notificationsRow(facts),
        batteryRow(facts),
        restrictionRow(facts),
        bucketRow(facts),
        manufacturerRow(facts),
    )

    /** How many rows need the user, for the Settings row's subtitle. */
    public fun issueCount(facts: KeepAliveFacts): Int = rows(facts).count { it.status == CheckStatus.NEEDS_ACTION }

    /**
     * Home's "Veyro may stop in the background" notice: only for a setting that really stops
     * the background connection, and only while "Run in background" is on. A blocked local
     * network has its own notice, and battery optimisation alone is the default for every app, so
     * neither shows this one.
     */
    public fun homeNotice(facts: KeepAliveFacts, runInBackground: Boolean): Boolean =
        runInBackground && (facts.backgroundRestricted || facts.standbyBucket == BUCKET_RESTRICTED)

    private fun localNetworkRow(facts: KeepAliveFacts): CheckRow? {
        val state = facts.localNetwork
        if (state == LocalNetworkState.NOT_NEEDED) return null
        return CheckRow(
            id = CheckId.LOCAL_NETWORK,
            title = "Local network",
            summary = LocalNetworkPermission.explanation(state),
            status = if (state.isBlocked) CheckStatus.NEEDS_ACTION else CheckStatus.OK,
            actions = listOfNotNull(LocalNetworkPermission.actionLabel(state)?.let { CheckAction(it, KeepAliveTarget.LOCAL_NETWORK) }),
        )
    }

    private fun notificationsRow(facts: KeepAliveFacts): CheckRow = if (facts.notificationsEnabled) {
        CheckRow(
            id = CheckId.NOTIFICATIONS,
            title = "Notifications",
            summary = "On. Incoming files can ask you, and you can see when Veyro runs in the background.",
            status = CheckStatus.OK,
            actions = listOf(CheckAction("Review", KeepAliveTarget.NOTIFICATIONS)),
        )
    } else {
        CheckRow(
            id = CheckId.NOTIFICATIONS,
            title = "Notifications",
            summary = "Off. Incoming files cannot ask you while Veyro is closed, and some phones stop " +
                "background apps that show no notification.",
            status = CheckStatus.NEEDS_ACTION,
            actions = listOf(CheckAction("Turn on", KeepAliveTarget.NOTIFICATIONS)),
        )
    }

    private fun batteryRow(facts: KeepAliveFacts): CheckRow = if (facts.batteryOptimizationIgnored) {
        CheckRow(
            id = CheckId.BATTERY_OPTIMIZATION,
            title = "Battery optimisation",
            summary = "Off for Veyro. Android lets it keep the connection open with the screen off.",
            status = CheckStatus.OK,
            actions = listOf(CheckAction("Review", KeepAliveTarget.BATTERY_EXEMPTION)),
        )
    } else {
        CheckRow(
            id = CheckId.BATTERY_OPTIMIZATION,
            title = "Battery optimisation",
            summary = "On for Veyro. Android may close the connection while the screen is off. " +
                "Veyro does not poll; it only reconnects when the network changes.",
            status = CheckStatus.NEEDS_ACTION,
            actions = listOf(CheckAction("Allow", KeepAliveTarget.BATTERY_EXEMPTION)),
        )
    }

    private fun restrictionRow(facts: KeepAliveFacts): CheckRow = if (facts.backgroundRestricted) {
        CheckRow(
            id = CheckId.BACKGROUND_RESTRICTION,
            title = "Background use",
            summary = "Restricted. Android stops Veyro as soon as you leave it. In App info, open " +
                "Battery and choose Unrestricted, or turn off Background restriction.",
            status = CheckStatus.NEEDS_ACTION,
            actions = listOf(CheckAction("Open App info", KeepAliveTarget.APP_INFO)),
        )
    } else {
        CheckRow(
            id = CheckId.BACKGROUND_RESTRICTION,
            title = "Background use",
            summary = "Allowed. Android does not restrict Veyro in the background.",
            status = CheckStatus.OK,
        )
    }

    private fun bucketRow(facts: KeepAliveFacts): CheckRow? {
        val bucket = facts.standbyBucket ?: return null
        val summary = when {
            bucket <= BUCKET_ACTIVE -> "Active. Android treats Veyro as an app in use."
            bucket <= BUCKET_WORKING_SET -> "Working set. Veyro is used often; background work is rarely delayed."
            bucket <= BUCKET_FREQUENT -> "Frequent. Android may delay some background work a little."
            bucket <= BUCKET_RARE -> "Rare. Android delays Veyro's background work. Opening Veyro now and then moves it up."
            else -> "Restricted. Android limits Veyro strongly. Open Veyro, and allow the battery settings above."
        }
        return CheckRow(
            id = CheckId.STANDBY_BUCKET,
            title = "App standby group",
            summary = summary,
            status = CheckStatus.INFO,
        )
    }

    private fun manufacturerRow(facts: KeepAliveFacts): CheckRow {
        val guide = OemBackgroundGuide.guideFor(facts.brand, facts.sdkInt)
        val oem = guide.purposes
            .filter { it in facts.oemScreens }
            .mapNotNull { purpose ->
                val label = guide.labels[purpose] ?: return@mapNotNull null
                CheckAction(label, if (purpose == OemPurpose.AUTOSTART) KeepAliveTarget.OEM_AUTOSTART else KeepAliveTarget.OEM_BATTERY)
            }
        return CheckRow(
            id = CheckId.MANUFACTURER,
            title = guide.title,
            summary = if (facts.brand == OemBrand.OTHER) {
                "Veyro cannot read these settings. Check them if the connection still drops."
            } else {
                "${facts.brand.label} phones close background apps on their own. Veyro cannot read these " +
                    "settings, so check each one."
            },
            status = CheckStatus.CHECK,
            actions = oem + CheckAction("App info", KeepAliveTarget.APP_INFO),
            steps = guide.steps,
        )
    }
}
