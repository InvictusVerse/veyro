package com.veyro.di

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.veyro.core.common.VeyroLog
import com.veyro.core.network.LocalNetworkState
import com.veyro.ui.keepalive.KeepAliveFacts
import com.veyro.ui.keepalive.KeepAliveTarget
import com.veyro.ui.keepalive.OemBackgroundGuide
import com.veyro.ui.keepalive.OemExtra
import com.veyro.ui.keepalive.OemGuide
import com.veyro.ui.keepalive.OemPurpose
import com.veyro.ui.keepalive.OemScreen

/**
 * The Android side of "Keep Veyro connected" (ADR-061): reads each fact the checklist shows and
 * opens the screen behind each button. Maker screens are private components that come and go
 * between ROM versions, so each is resolved with PackageManager first (the packages are listed in
 * the manifest's `<queries>`), started inside a try, and App info is the fallback for all of them.
 * Nothing here crashes on a missing activity.
 */
internal class KeepAliveProbe(private val context: Context) {

    private val brand = OemBackgroundGuide.brandOf(Build.MANUFACTURER, Build.BRAND)

    private val guide: OemGuide get() = OemBackgroundGuide.guideFor(brand, Build.VERSION.SDK_INT)

    fun facts(localNetwork: LocalNetworkState): KeepAliveFacts {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        return KeepAliveFacts(
            sdkInt = Build.VERSION.SDK_INT,
            brand = brand,
            localNetwork = localNetwork,
            notificationsEnabled = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false,
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(),
            backgroundRestricted = runCatching { activityManager?.isBackgroundRestricted == true }.getOrDefault(false),
            standbyBucket = runCatching { usage?.appStandbyBucket }.getOrNull(),
            oemScreens = guide.purposes.filter { purpose -> available(purpose) != null }.toSet(),
        )
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Opens [target]'s screen; false only when not even App info could be opened. */
    fun open(target: KeepAliveTarget): Boolean {
        val candidates: List<Intent> = when (target) {
            // Handled by the Activity (a permission dialog); App info is the fallback here.
            KeepAliveTarget.LOCAL_NETWORK -> emptyList()
            KeepAliveTarget.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            KeepAliveTarget.BATTERY_EXEMPTION -> if (isIgnoringBatteryOptimizations()) {
                listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                // Asked only from this explicit button (ADR-036), never on its own.
                listOf(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            KeepAliveTarget.APP_INFO -> emptyList()
            KeepAliveTarget.OEM_AUTOSTART -> oemIntents(OemPurpose.AUTOSTART)
            KeepAliveTarget.OEM_BATTERY -> oemIntents(OemPurpose.BATTERY)
        }
        for (intent in candidates + appInfoIntent()) {
            if (start(intent)) return true
        }
        return false
    }

    /** The first maker screen of [purpose] that resolves to an exported activity, or null. */
    private fun available(purpose: OemPurpose): OemScreen? =
        OemBackgroundGuide.firstAvailable(guide.candidates(purpose)) { resolves(intentFor(it)) }

    /** Every resolving candidate of [purpose], in order: if the first refuses to start, the next is tried. */
    private fun oemIntents(purpose: OemPurpose): List<Intent> =
        guide.candidates(purpose).map(::intentFor).filter(::resolves)

    private fun intentFor(screen: OemScreen): Intent = Intent().apply {
        component = ComponentName(screen.packageName, screen.className)
        for ((key, value) in screen.extras) {
            putExtra(
                key,
                when (value) {
                    OemExtra.PACKAGE_NAME -> context.packageName
                    OemExtra.APP_LABEL -> APP_LABEL
                },
            )
        }
    }

    private fun resolves(intent: Intent): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0)
        }
        info?.activityInfo?.exported == true
    }.getOrDefault(false)

    private fun appInfoIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // A maker screen that resolves but is not ours to open on this ROM version.
        VeyroLog.i(TAG) { "Settings screen refused (${e.javaClass.simpleName}); trying the next" }
        false
    } catch (e: RuntimeException) {
        VeyroLog.w(TAG) { "Settings screen failed (${e.javaClass.simpleName}); trying the next" }
        false
    }

    private companion object {
        const val TAG = "KeepAlive"
        const val APP_LABEL = "Veyro"
    }
}
