package com.veyro.ui.keepalive

import com.veyro.core.network.LocalNetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ADR-061: what "Keep Veyro connected" shows for what the system reports. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepConnectedChecklistTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val healthy = KeepAliveFacts(
        sdkInt = 30,
        brand = OemBrand.SAMSUNG,
        localNetwork = LocalNetworkState.NOT_NEEDED,
        notificationsEnabled = true,
        batteryOptimizationIgnored = true,
        backgroundRestricted = false,
        standbyBucket = KeepConnectedChecklist.BUCKET_ACTIVE,
        oemScreens = setOf(OemPurpose.BATTERY),
    )

    private fun row(facts: KeepAliveFacts, id: CheckId): CheckRow? = KeepConnectedChecklist.rows(facts).firstOrNull { it.id == id }

    @Test
    fun `a healthy phone has nothing to fix and no Home notice`() {
        assertEquals(0, KeepConnectedChecklist.issueCount(healthy))
        assertFalse(KeepConnectedChecklist.homeNotice(healthy, runInBackground = true))
        assertNull("no local network row below Android 17", row(healthy, CheckId.LOCAL_NETWORK))
        assertEquals(CheckStatus.INFO, row(healthy, CheckId.STANDBY_BUCKET)?.status)
        assertEquals(CheckStatus.CHECK, row(healthy, CheckId.MANUFACTURER)?.status)
    }

    @Test
    fun `each wrong setting needs a change and names the screen that fixes it`() {
        val bad = healthy.copy(notificationsEnabled = false, batteryOptimizationIgnored = false, backgroundRestricted = true)
        assertEquals(3, KeepConnectedChecklist.issueCount(bad))
        assertEquals(KeepAliveTarget.NOTIFICATIONS, row(bad, CheckId.NOTIFICATIONS)?.actions?.first()?.target)
        assertEquals(KeepAliveTarget.BATTERY_EXEMPTION, row(bad, CheckId.BATTERY_OPTIMIZATION)?.actions?.first()?.target)
        assertEquals("Allow", row(bad, CheckId.BATTERY_OPTIMIZATION)?.actions?.first()?.label)
        val restriction = row(bad, CheckId.BACKGROUND_RESTRICTION)
        assertEquals(CheckStatus.NEEDS_ACTION, restriction?.status)
        assertEquals(KeepAliveTarget.APP_INFO, restriction?.actions?.first()?.target)
    }

    @Test
    fun `Home warns only for a real background restriction and only with Run in background on`() {
        val restricted = healthy.copy(backgroundRestricted = true)
        assertTrue(KeepConnectedChecklist.homeNotice(restricted, runInBackground = true))
        assertFalse(KeepConnectedChecklist.homeNotice(restricted, runInBackground = false))
        val restrictedBucket = healthy.copy(standbyBucket = KeepConnectedChecklist.BUCKET_RESTRICTED)
        assertTrue(KeepConnectedChecklist.homeNotice(restrictedBucket, runInBackground = true))
        // Battery optimisation on is every app's default: listed in the checklist, no Home notice.
        val optimised = healthy.copy(batteryOptimizationIgnored = false)
        assertFalse(KeepConnectedChecklist.homeNotice(optimised, runInBackground = true))
        assertEquals(1, KeepConnectedChecklist.issueCount(optimised))
    }

    @Test
    fun `the local network row appears on Android 17 and says how to fix it`() {
        val blocked = healthy.copy(sdkInt = 37, localNetwork = LocalNetworkState.DENIED_SETTINGS)
        val local = row(blocked, CheckId.LOCAL_NETWORK)
        assertEquals(CheckStatus.NEEDS_ACTION, local?.status)
        assertEquals(CheckAction("Open settings", KeepAliveTarget.LOCAL_NETWORK), local?.actions?.single())
        val granted = row(blocked.copy(localNetwork = LocalNetworkState.GRANTED), CheckId.LOCAL_NETWORK)
        assertEquals(CheckStatus.OK, granted?.status)
        assertTrue(granted?.actions.isNullOrEmpty())
    }

    @Test
    fun `maker buttons show only for screens that resolved, App info always`() {
        val xiaomi = healthy.copy(brand = OemBrand.XIAOMI, oemScreens = setOf(OemPurpose.AUTOSTART))
        val actions = row(xiaomi, CheckId.MANUFACTURER)?.actions.orEmpty()
        assertEquals(
            listOf(CheckAction("Autostart", KeepAliveTarget.OEM_AUTOSTART), CheckAction("App info", KeepAliveTarget.APP_INFO)),
            actions,
        )
        val both = row(xiaomi.copy(oemScreens = setOf(OemPurpose.AUTOSTART, OemPurpose.BATTERY)), CheckId.MANUFACTURER)
        assertEquals(KeepAliveTarget.OEM_BATTERY, both?.actions?.get(1)?.target)
        val none = row(xiaomi.copy(oemScreens = emptySet()), CheckId.MANUFACTURER)
        assertEquals(listOf(CheckAction("App info", KeepAliveTarget.APP_INFO)), none?.actions)
        assertTrue(none?.steps.orEmpty().isNotEmpty())
    }

    @Test
    fun `the standby bucket is information only and unread buckets are left out`() {
        val rare = row(healthy.copy(standbyBucket = KeepConnectedChecklist.BUCKET_RARE), CheckId.STANDBY_BUCKET)
        assertEquals(CheckStatus.INFO, rare?.status)
        assertTrue(rare?.summary.orEmpty().startsWith("Rare"))
        assertNull(row(healthy.copy(standbyBucket = null), CheckId.STANDBY_BUCKET))
        assertEquals(0, KeepConnectedChecklist.issueCount(healthy.copy(standbyBucket = KeepConnectedChecklist.BUCKET_RESTRICTED)))
    }

    @Test
    fun `the view model re-reads on refresh and follows the local network state`() {
        var facts = healthy.copy(sdkInt = 37, localNetwork = LocalNetworkState.NOT_ASKED)
        val network = MutableStateFlow(LocalNetworkState.NOT_ASKED)
        val opened = mutableListOf<KeepAliveTarget>()
        val viewModel = KeepConnectedViewModel(
            readFacts = { facts },
            localNetwork = network,
            open = { opened += it; it != KeepAliveTarget.OEM_AUTOSTART },
        )
        viewModel.refresh()
        assertEquals(CheckStatus.NEEDS_ACTION, viewModel.state.value.rows.first { it.id == CheckId.LOCAL_NETWORK }.status)

        network.value = LocalNetworkState.GRANTED
        assertEquals(CheckStatus.OK, viewModel.state.value.rows.first { it.id == CheckId.LOCAL_NETWORK }.status)

        facts = facts.copy(backgroundRestricted = true, localNetwork = LocalNetworkState.GRANTED)
        viewModel.refresh()
        assertEquals(CheckStatus.NEEDS_ACTION, viewModel.state.value.rows.first { it.id == CheckId.BACKGROUND_RESTRICTION }.status)

        viewModel.onAction(KeepAliveTarget.APP_INFO)
        assertNull(viewModel.state.value.message)
        viewModel.onAction(KeepAliveTarget.OEM_AUTOSTART)
        assertTrue(viewModel.state.value.message != null)
        viewModel.messageShown()
        assertNull(viewModel.state.value.message)
        assertEquals(listOf(KeepAliveTarget.APP_INFO, KeepAliveTarget.OEM_AUTOSTART), opened)
    }
}
