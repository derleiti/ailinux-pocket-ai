package me.ailinux.pocket.mcp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Keep encrypted account IO off the main UI thread to minimize stalls. */
class McpUiBackgroundWorkTest {
    @Test fun mcpProfileRenderingIsAsyncAndDropsOutdatedResponses() {
        val source = File("src/main/java/me/ailinux/pocket/mcp/McpSettingsUi.kt").readText()
        val method = source.substringAfter("fun render() {")
            .substringBefore("private fun renderRecords(")
        assertTrue(method.contains("activity.lifecycleScope.launch"))
        assertTrue(method.contains("withContext(Dispatchers.IO)"))
        assertTrue(method.contains("runCatching { vault.list() }"))
        assertTrue(method.contains("generation != renderGeneration"))
        assertTrue(method.contains("activity.isDestroyed"))
        assertFalse(method.contains("val servers = vault.list()"))
    }

    @Test fun manualAndAutoAgentSelectorsDecryptProfilesOffMain() {
        val source = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        assertTrue(source.contains("private fun loadProfilesWithoutBlockingUi("))
        val loader = source.substringAfter("private fun loadProfilesWithoutBlockingUi(")
            .substringBefore("private fun showAutoServerChooser(")
        assertTrue(loader.contains("withContext(Dispatchers.IO)"))
        assertTrue(loader.contains("runCatching { vault.list() }"))
        assertTrue(source.contains("loadProfilesWithoutBlockingUi { profiles -> showAutoServerChooser(profiles) }"))
        assertTrue(source.contains("loadProfilesWithoutBlockingUi { profiles -> showManualServerChooser(profiles) }"))
    }

    @Test fun liveToolsAndServerInstructionsMenusUseAsyncProfileLoader() {
        val source = File("src/main/java/me/ailinux/pocket/mcp/McpSettingsUi.kt").readText()
        assertTrue(source.contains("loadProfileMenuSafely { profiles -> openServerGuidanceMenu(profiles) }"))
        assertTrue(source.contains("loadProfileMenuSafely { profiles -> openToolsMenu(profiles) }"))
        val loader = source.substringAfter("private fun loadProfileMenuSafely(")
            .substringBefore("private fun openToolsMenu(")
        assertTrue(loader.contains("withContext(Dispatchers.IO)"))
        assertTrue(loader.contains("vault.list()"))
        assertTrue(loader.contains("!activity.isDestroyed"))
    }

    @Test fun modelStartDiagnosticFsyncDoesNotBlockAndroidMainThread() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val loader = main.substringAfter("private fun loadModel(")
            .substringBefore("private fun showMcpAgentMenu()")
        assertTrue(loader.contains("withContext(Dispatchers.IO)"))
        val marker = loader.indexOf("putString(PREF_LOADING_MODEL, file.name)")
        val time = loader.indexOf("putLong(PREF_LOADING_MODEL_STARTED_MS, System.currentTimeMillis())")
        val commit = loader.indexOf(".commit())", marker)
        assertTrue("Model identity and timestamp must be persisted together",
            marker >= 0 && time > marker && commit > time)
        assertTrue(loader.indexOf("withContext(Dispatchers.IO) {\n                    check(") < marker)
        assertTrue("Must durably persist before GGUF JNI load",
            commit < loader.indexOf("engine.loadModel(file.absolutePath)"))
        assertEquals("GGUF and LiteRT both need an exit timestamp",
            2, Regex("putLong\\(PREF_LOADING_MODEL_STARTED_MS, System.currentTimeMillis\\(\\)\\)")
                .findAll(loader).count())
    }

    @Test fun interruptedModelExitEvidenceIsReadOffMainWithoutGuessingCause() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val startup = main.substringAfter("val interruptedModel =")
            .substringBefore("refreshLibrary()")
        assertTrue(startup.contains("lifecycleScope.launch"))
        assertTrue(startup.contains("withContext(Dispatchers.IO)"))
        assertTrue(startup.indexOf("withContext(Dispatchers.IO)") <
            startup.indexOf("getHistoricalProcessExitReasons("))
        assertTrue(startup.contains("ModelExitEvidence.correlate("))
        assertTrue(startup.contains("Kein zeitlich zugeordneter Android-Exitgrund"))
        assertTrue(startup.contains("ein Crash ist nicht bewiesen"))
        assertTrue(startup.contains(".remove(PREF_LOADING_MODEL_STARTED_MS)"))
    }

    @Test fun normalPackageUpdateIsStillNotLabeledAnAppCrash() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        assertTrue(main.contains("ApplicationExitInfo.REASON_PACKAGE_UPDATED -> \"App-Update (kein Crash)\""))
        assertTrue(main.contains("uiResponsiveness.report()"))
        assertTrue(main.contains("nicht die Ursache der Beendigung"))
    }
}
