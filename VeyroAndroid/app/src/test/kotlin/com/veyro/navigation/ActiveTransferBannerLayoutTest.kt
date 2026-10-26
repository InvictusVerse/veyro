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
    fun `VeyroNavHost places banner in Column before the root NavHost with weight to reserve height`() {
        val projectRoot = findProjectRoot()
        val navHostFile = File(projectRoot, "app/src/main/kotlin/com/veyro/navigation/VeyroNavHost.kt")
        val content = navHostFile.readText()

        // Since the Sefirah UI port the banner sits above the root graph (tabs and pushed screens),
        // not inside the tab Scaffold, so it is still shown exactly once on every screen.
        assertTrue("VeyroNavHost root content must use a Column", content.contains("Column("))

        // Verify ActiveTransferBanner is rendered BEFORE the root NavHost invocation
        val bannerIdx = content.indexOf("ActiveTransferBanner(", content.indexOf("Column("))
        val navHostCallIdx = content.indexOf("\n        NavHost(", bannerIdx)
        assertTrue("ActiveTransferBanner must be present", bannerIdx > 0)
        assertTrue("NavHost call must be present after ActiveTransferBanner", navHostCallIdx > 0)
        assertTrue(
            "ActiveTransferBanner must precede NavHost in Column layout",
            bannerIdx < navHostCallIdx,
        )

        // Verify NavHost has weight(1f) to lay out underneath banner without overlap
        val navHostBlock = content.substring(navHostCallIdx, content.indexOf("composable<VeyroDestination.Main>", navHostCallIdx))
        assertTrue(
            "NavHost must declare weight(1f) to occupy remaining vertical space below banner",
            navHostBlock.contains(".weight(1f)"),
        )

        // When shown, the banner takes the status-bar inset and the graph is told it is consumed,
        // so the top bars below do not pad for the status bar a second time.
        assertTrue(content.contains("statusBarsPadding()"))
        assertTrue(navHostBlock.contains("consumeWindowInsets(WindowInsets.statusBars)"))
    }

    @Test
    fun `the tab graph does not host a second banner`() {
        val projectRoot = findProjectRoot()
        val mainScreen = File(projectRoot, "app/src/main/kotlin/com/veyro/navigation/MainScreen.kt")
        assertTrue("MainScreen.kt must exist", mainScreen.exists())
        assertFalse(mainScreen.readText().contains("ActiveTransferBanner("))
    }

    @Test
    fun `VeyroNavHost binds onCancel and onDismiss actions correctly`() {
        val projectRoot = findProjectRoot()
        val navHostFile = File(projectRoot, "app/src/main/kotlin/com/veyro/navigation/VeyroNavHost.kt")
        val content = navHostFile.readText()

        assertTrue(
            "onCancel must invoke transferCoordinator?.cancel()",
            content.contains("onCancel = { transferCoordinator?.cancel() }"),
        )
        assertTrue(
            "onDismiss must invoke transferCoordinator?.clearActiveTransfer()",
            content.contains("onDismiss = { transferCoordinator?.clearActiveTransfer() }"),
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
