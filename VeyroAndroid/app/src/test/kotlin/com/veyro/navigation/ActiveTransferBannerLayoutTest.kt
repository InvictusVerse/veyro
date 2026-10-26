package com.veyro.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActiveTransferBannerLayoutTest {

    @Test
    fun `ActiveTransferBanner is uniquely hosted in VeyroNavHost and not duplicated in individual screens`() {
        val projectRoot = findProjectRoot()
        val navHostFile = File(projectRoot, "app/src/main/kotlin/com/veyro/navigation/VeyroNavHost.kt")
        assertTrue("VeyroNavHost.kt must exist", navHostFile.exists())

        val navHostContent = navHostFile.readText()
        assertTrue(
            "VeyroNavHost must render ActiveTransferBanner",
            navHostContent.contains("ActiveTransferBanner("),
        )

        // Verify screens do NOT render their own separate duplicate ActiveTransferBanner
        val screensDir = File(projectRoot, "core/ui/src/main/kotlin/com/veyro/ui")
        assertTrue("core/ui source dir must exist", screensDir.exists())

        val screenFiles = screensDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "ActiveTransferBanner.kt" }
            .toList()

        for (file in screenFiles) {
            val content = file.readText()
            assertFalse(
                "File ${file.name} must not render ActiveTransferBanner directly to prevent duplicate banners",
                content.contains("ActiveTransferBanner("),
            )
        }
    }

    @Test
    fun `VeyroNavHost floats the banner above the root NavHost so nothing below it moves`() {
        val projectRoot = findProjectRoot()
        val content = File(projectRoot, "app/src/main/kotlin/com/veyro/navigation/VeyroNavHost.kt").readText()

        // ADR-038: the banner overlays the graph in a Box instead of pushing it down in a Column,
        // so appearing and sliding out never shift the screen below.
        val boxIdx = content.indexOf("Box(")
        val navHostCallIdx = content.indexOf("\n        NavHost(", boxIdx)
        val bannerIdx = content.indexOf("ActiveTransferBanner(", navHostCallIdx)
        assertTrue("root content must be a Box", boxIdx > 0)
        assertTrue("NavHost must be inside the Box", navHostCallIdx > boxIdx)
        assertTrue("banner must be drawn after (above) the NavHost", bannerIdx > navHostCallIdx)
        assertTrue(content.contains(".align(Alignment.TopCenter)"))

        val navHostBlock = content.substring(navHostCallIdx, content.indexOf("composable<VeyroDestination.Main>", navHostCallIdx))
        assertFalse("the graph must not be resized for the banner", navHostBlock.contains("consumeWindowInsets"))

        // The banner carries its own status-bar inset.
        val banner = File(projectRoot, "core/ui/src/main/kotlin/com/veyro/ui/components/ActiveTransferBanner.kt").readText()
        assertTrue(banner.contains("statusBarsPadding()"))
    }

    @Test
    fun `the tab graph does not host a second banner`() {
        val projectRoot = findProjectRoot()
        val mainScreen = File(projectRoot, "app/src/main/kotlin/com/veyro/navigation/MainScreen.kt")
        assertTrue("MainScreen.kt must exist", mainScreen.exists())
        assertFalse(mainScreen.readText().contains("ActiveTransferBanner("))
    }

    @Test
    fun `VeyroNavHost binds dismiss to the banner model and cancel to the coordinator`() {
        val projectRoot = findProjectRoot()
        val content = File(projectRoot, "app/src/main/kotlin/com/veyro/navigation/VeyroNavHost.kt").readText()

        assertTrue(content.contains("onDismiss = { bannerModel.dismiss() }"))
        assertTrue(content.contains("onCancel = { transferCoordinator?.cancel() }"))
        assertTrue(
            "slid-out banners clear only their own transfer",
            content.contains("transferCoordinator?.clearActiveTransfer(id)"),
        )
    }

    private fun findProjectRoot(): File {
        val candidates = listOf(
            File("."),
            File(".."),
            File("../../.."),
        )
        return candidates.firstOrNull { File(it, "app/src/main/kotlin/com/veyro/navigation/VeyroNavHost.kt").exists() }
            ?: File("D:/Coding/Projects/veyro/VeyroAndroid")
    }
}
