package com.veyro.ui.keepalive

/**
 * Phone makers whose own battery managers close background apps beyond what Android does
 * (ADR-061). Grouped by the settings app they share.
 */
public enum class OemBrand(public val label: String) {
    XIAOMI("Xiaomi, Redmi and POCO"),
    SAMSUNG("Samsung"),
    OPPO("Oppo, Realme and OnePlus"),
    VIVO("Vivo and iQOO"),
    HUAWEI("Huawei and Honor"),
    MOTOROLA("Motorola"),
    ASUS("Asus"),
    OTHER("This phone"),
}

/** What a maker's screen is for. */
public enum class OemPurpose { AUTOSTART, BATTERY }

/** An extra a maker's screen expects, filled in by `:app` with Veyro's own values. */
public enum class OemExtra { PACKAGE_NAME, APP_LABEL }

/**
 * One maker settings screen, as an explicit component. These are not public APIs: they move
 * between ROM versions, so `:app` resolves each with PackageManager before offering it, tries the
 * next on any failure, and falls back to App info.
 */
public data class OemScreen(
    val purpose: OemPurpose,
    val packageName: String,
    val className: String,
    val extras: Map<String, OemExtra> = emptyMap(),
)

/** What the checklist says and offers for one maker. */
public data class OemGuide(
    val brand: OemBrand,
    val title: String,
    val steps: List<String>,
    /** Candidates in the order they are tried, per purpose. */
    val screens: List<OemScreen>,
    /** The button label for each purpose. */
    val labels: Map<OemPurpose, String>,
) {
    public fun candidates(purpose: OemPurpose): List<OemScreen> = screens.filter { it.purpose == purpose }

    /** The purposes this maker has screens for, in button order. */
    public val purposes: List<OemPurpose> get() = screens.map { it.purpose }.distinct()
}

/**
 * The per-maker table (ADR-061), pure Kotlin so it is JVM-tested. Component names are the
 * commonly documented ones for each ROM family (vendor support pages, dontkillmyapp.com); none is
 * guaranteed to exist on a given phone, which is why every one is resolved first.
 */
public object OemBackgroundGuide {

    /** [manufacturer] and [brand] are `Build.MANUFACTURER` and `Build.BRAND`. */
    public fun brandOf(manufacturer: String?, brand: String?): OemBrand {
        val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
        fun any(vararg keys: String) = names.any { it in keys }
        return when {
            any("xiaomi", "redmi", "poco", "blackshark") -> OemBrand.XIAOMI
            any("samsung") -> OemBrand.SAMSUNG
            any("oppo", "realme", "oneplus") -> OemBrand.OPPO
            any("vivo", "iqoo") -> OemBrand.VIVO
            any("huawei", "honor") -> OemBrand.HUAWEI
            any("motorola", "moto") -> OemBrand.MOTOROLA
            any("asus") -> OemBrand.ASUS
            else -> OemBrand.OTHER
        }
    }

    private const val RECENTS_LOCK =
        "Open recent apps, press and hold Veyro and tap the lock, so clearing recent apps does not close it."

    public fun guideFor(brand: OemBrand, sdkInt: Int): OemGuide = when (brand) {
        // MIUI and HyperOS. On the newest versions the powerkeeper screen is gone; App info has
        // both switches too, which the last step says.
        OemBrand.XIAOMI -> OemGuide(
            brand = brand,
            title = "Xiaomi autostart and battery saver",
            steps = listOf(
                "Tap Autostart and turn Veyro on.",
                "Tap Battery saver and choose No restrictions.",
                RECENTS_LOCK,
                "If a button opens the wrong page, tap App info: Autostart and Battery saver are there too.",
            ),
            screens = listOf(
                OemScreen(OemPurpose.AUTOSTART, "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                OemScreen(
                    OemPurpose.BATTERY,
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                    extras = mapOf("package_name" to OemExtra.PACKAGE_NAME, "package_label" to OemExtra.APP_LABEL),
                ),
            ),
            labels = mapOf(OemPurpose.AUTOSTART to "Autostart", OemPurpose.BATTERY to "Battery saver"),
        )
        // One UI. Android 10 (One UI 2) and Android 11+ (One UI 3+) name the list differently.
        OemBrand.SAMSUNG -> OemGuide(
            brand = brand,
            title = "Samsung battery settings",
            steps = if (sdkInt >= 30) {
                listOfNotNull(
                    "Tap Battery, then Background usage limits.",
                    "Open Never sleeping apps, tap +, choose Veyro and tap Add.",
                    "Check that Veyro is not in Sleeping apps or Deep sleeping apps.",
                    if (sdkInt >= 31) "In App info > Battery, choose Unrestricted." else null,
                )
            } else {
                listOf(
                    "Tap Battery, then App power management.",
                    "Open Apps that won't be put to sleep and add Veyro.",
                    "Check that Veyro is not in Sleeping apps.",
                )
            },
            screens = listOf(
                OemScreen(OemPurpose.BATTERY, "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                OemScreen(OemPurpose.BATTERY, "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                OemScreen(OemPurpose.BATTERY, "com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            ),
            labels = mapOf(OemPurpose.BATTERY to "Battery"),
        )
        // ColorOS, realme UI and OxygenOS.
        OemBrand.OPPO -> OemGuide(
            brand = brand,
            title = "Auto-launch and battery",
            steps = listOf(
                "Tap Auto-launch and allow Veyro.",
                "In App info > Battery usage, turn on Allow background activity.",
                RECENTS_LOCK,
            ),
            screens = listOf(
                OemScreen(OemPurpose.AUTOSTART, "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                OemScreen(OemPurpose.BATTERY, "com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            ),
            labels = mapOf(OemPurpose.AUTOSTART to "Auto-launch", OemPurpose.BATTERY to "Battery"),
        )
        // Funtouch OS and OriginOS.
        OemBrand.VIVO -> OemGuide(
            brand = brand,
            title = "Background start and power",
            steps = listOf(
                "Tap Background start and allow Veyro.",
                "Tap Background power and allow high background power use for Veyro.",
                RECENTS_LOCK,
            ),
            screens = listOf(
                OemScreen(OemPurpose.AUTOSTART, "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                OemScreen(OemPurpose.AUTOSTART, "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                OemScreen(OemPurpose.BATTERY, "com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            ),
            labels = mapOf(OemPurpose.AUTOSTART to "Background start", OemPurpose.BATTERY to "Background power"),
        )
        // EMUI and MagicOS.
        OemBrand.HUAWEI -> OemGuide(
            brand = brand,
            title = "App launch",
            steps = listOf(
                "Tap App launch, find Veyro and turn off Manage automatically.",
                "In the window that opens, turn on Auto-launch, Secondary launch and Run in background.",
                RECENTS_LOCK,
            ),
            screens = listOf(
                OemScreen(OemPurpose.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ),
            labels = mapOf(OemPurpose.AUTOSTART to "App launch"),
        )
        // Close to stock Android: App info has what is needed.
        OemBrand.MOTOROLA -> OemGuide(
            brand = brand,
            title = "Motorola battery settings",
            steps = listOf(
                "Tap App info, then Battery, and choose Unrestricted (or turn on Background usage).",
            ),
            screens = emptyList(),
            labels = emptyMap(),
        )
        OemBrand.ASUS -> OemGuide(
            brand = brand,
            title = "Auto-start manager",
            steps = listOf(
                "Tap Auto-start and allow Veyro.",
                "In App info > Battery, choose Unrestricted if your phone shows it.",
            ),
            screens = listOf(
                OemScreen(OemPurpose.AUTOSTART, "com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                OemScreen(OemPurpose.AUTOSTART, "com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
            ),
            labels = mapOf(OemPurpose.AUTOSTART to "Auto-start"),
        )
        OemBrand.OTHER -> OemGuide(
            brand = brand,
            title = "Battery settings",
            steps = listOf(
                "Most phones need only the battery step above.",
                "If Veyro still disconnects, tap App info, then Battery, and choose Unrestricted.",
            ),
            screens = emptyList(),
            labels = emptyMap(),
        )
    }

    /** Every maker package in the table, for the manifest's `<queries>` (package visibility). */
    public val allPackages: Set<String>
        get() = OemBrand.entries.flatMap { b -> guideFor(b, 37).screens + guideFor(b, 29).screens }.map { it.packageName }.toSet()

    /** The first candidate [resolves] accepts, or null; `:app` passes a PackageManager check. */
    public fun firstAvailable(candidates: List<OemScreen>, resolves: (OemScreen) -> Boolean): OemScreen? =
        candidates.firstOrNull { runCatching { resolves(it) }.getOrDefault(false) }
}
