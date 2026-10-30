package com.veyro.ui.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-061: the per-maker table behind "Keep Veyro connected". */
class OemBackgroundGuideTest {

    @Test
    fun `the user's phones are recognised`() {
        // Xiaomi 22041216I reports MANUFACTURER Xiaomi and BRAND Redmi or POCO; SM-A207F is samsung.
        assertEquals(OemBrand.XIAOMI, OemBackgroundGuide.brandOf("Xiaomi", "Redmi"))
        assertEquals(OemBrand.XIAOMI, OemBackgroundGuide.brandOf("Xiaomi", "POCO"))
        assertEquals(OemBrand.SAMSUNG, OemBackgroundGuide.brandOf("samsung", "samsung"))
    }

    @Test
    fun `every maker family maps, by manufacturer or by brand`() {
        assertEquals(OemBrand.OPPO, OemBackgroundGuide.brandOf("OPPO", "OPPO"))
        assertEquals(OemBrand.OPPO, OemBackgroundGuide.brandOf("realme", "realme"))
        assertEquals(OemBrand.OPPO, OemBackgroundGuide.brandOf("OnePlus", "OnePlus"))
        assertEquals(OemBrand.VIVO, OemBackgroundGuide.brandOf("vivo", "vivo"))
        assertEquals(OemBrand.VIVO, OemBackgroundGuide.brandOf("vivo", "iQOO"))
        assertEquals(OemBrand.HUAWEI, OemBackgroundGuide.brandOf("HUAWEI", "HUAWEI"))
        assertEquals(OemBrand.HUAWEI, OemBackgroundGuide.brandOf("HONOR", "HONOR"))
        assertEquals(OemBrand.MOTOROLA, OemBackgroundGuide.brandOf("motorola", "motorola"))
        assertEquals(OemBrand.ASUS, OemBackgroundGuide.brandOf("asus", "asus"))
        assertEquals(OemBrand.OTHER, OemBackgroundGuide.brandOf("Google", "google"))
        assertEquals(OemBrand.OTHER, OemBackgroundGuide.brandOf(null, null))
        assertEquals(OemBrand.OTHER, OemBackgroundGuide.brandOf(" ", ""))
        // Substrings do not count: "Lenovo" is not "vivo".
        assertEquals(OemBrand.OTHER, OemBackgroundGuide.brandOf("Lenovo", "Lenovo"))
    }

    @Test
    fun `Xiaomi offers autostart and battery saver, with the package extras`() {
        val guide = OemBackgroundGuide.guideFor(OemBrand.XIAOMI, 30)
        assertEquals(listOf(OemPurpose.AUTOSTART, OemPurpose.BATTERY), guide.purposes)
        val autostart = guide.candidates(OemPurpose.AUTOSTART).first()
        assertEquals("com.miui.securitycenter", autostart.packageName)
        val battery = guide.candidates(OemPurpose.BATTERY).first()
        assertEquals("com.miui.powerkeeper", battery.packageName)
        assertEquals(OemExtra.PACKAGE_NAME, battery.extras["package_name"])
        assertEquals(OemExtra.APP_LABEL, battery.extras["package_label"])
        assertEquals("Autostart", guide.labels[OemPurpose.AUTOSTART])
        assertTrue(guide.steps.any { "App info" in it })
    }

    @Test
    fun `Samsung steps follow the One UI version`() {
        val android10 = OemBackgroundGuide.guideFor(OemBrand.SAMSUNG, 29)
        val android11 = OemBackgroundGuide.guideFor(OemBrand.SAMSUNG, 30)
        val android12 = OemBackgroundGuide.guideFor(OemBrand.SAMSUNG, 31)
        assertTrue(android10.steps.any { "App power management" in it })
        assertTrue(android10.steps.none { "Never sleeping" in it })
        assertTrue(android11.steps.any { "Never sleeping apps" in it })
        assertTrue("Unrestricted is Android 12+", android11.steps.none { "Unrestricted" in it })
        assertTrue(android12.steps.any { "Unrestricted" in it })
        assertNotEquals(android10.steps, android11.steps)
        // Newest Device care first, older ones after.
        val battery = android11.candidates(OemPurpose.BATTERY)
        assertEquals("com.samsung.android.sm.battery.ui.BatteryActivity", battery.first().className)
        assertEquals(3, battery.size)
    }

    @Test
    fun `every maker has steps, and every purpose it offers has a label`() {
        for (brand in OemBrand.entries) {
            for (sdk in listOf(29, 30, 33, 37)) {
                val guide = OemBackgroundGuide.guideFor(brand, sdk)
                assertTrue("$brand has steps", guide.steps.isNotEmpty())
                for (purpose in guide.purposes) {
                    assertTrue("$brand labels $purpose", !guide.labels[purpose].isNullOrBlank())
                }
            }
        }
        assertTrue(OemBackgroundGuide.guideFor(OemBrand.OTHER, 33).screens.isEmpty())
        assertTrue(OemBackgroundGuide.guideFor(OemBrand.MOTOROLA, 33).screens.isEmpty())
    }

    @Test
    fun `the first resolving candidate wins, and a throwing check is a miss`() {
        val candidates = OemBackgroundGuide.guideFor(OemBrand.HUAWEI, 29).candidates(OemPurpose.AUTOSTART)
        val second = candidates[1]
        assertEquals(second, OemBackgroundGuide.firstAvailable(candidates) { it == second || it == candidates[2] })
        assertNull(OemBackgroundGuide.firstAvailable(candidates) { false })
        assertEquals(
            candidates[2],
            OemBackgroundGuide.firstAvailable(candidates) { if (it == candidates[2]) true else error("PackageManager died") },
        )
    }

    @Test
    fun `allPackages lists every maker app once`() {
        val all = OemBackgroundGuide.allPackages
        assertTrue("com.miui.securitycenter" in all)
        assertTrue("com.samsung.android.lool" in all)
        assertTrue("com.hihonor.systemmanager" in all)
        assertEquals(14, all.size)
    }
}
