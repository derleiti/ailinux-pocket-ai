package me.ailinux.pocket

import me.ailinux.pocket.mcp.McpDispatchState
import me.ailinux.pocket.mcp.McpExecutionUncertain
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

/** Regression guards for CodeRabbit PR #23 review. */
class McpReviewSafetyTest {
    private val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
    private val agent = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
    private val settings = File("src/main/java/me/ailinux/pocket/mcp/McpSettingsUi.kt").readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()

    @Test fun cancellationBeforeAndAfterDispatchAreNeverConfused() {
        val context = McpDispatchState()
        val cancelled = CancellationException("Job was cancelled")
        assertFalse(context.unconfirmed(cancelled))
        assertFalse(context.attempted)
        context.markAttempted()
        assertTrue(context.attempted)
        assertTrue(context.unconfirmed(cancelled))
        assertTrue(McpDispatchState().unconfirmed(
            McpExecutionUncertain(IllegalStateException("timeout"))))
    }

    @Test fun everyMcpPathSharesTheActualPerInvocationDispatchState() {
        assertTrue(main.contains("val dispatchState = McpDispatchState()"))
        assertTrue(main.contains("dispatchState.markAttempted()"))
        assertTrue(main.contains("dispatchState.unconfirmed(problem)"))
        assertTrue(main.contains("dispatchState.unconfirmed(error)"))
        assertTrue(main.contains("dispatchState.unconfirmed(e)"))
        assertTrue(agent.contains("dispatchState: McpDispatchState = McpDispatchState()"))
        assertEquals(2, Regex("dispatchState.markAttempted\\(\\)")
            .findAll(agent).count())
        assertEquals(2, Regex("PocketDiagnostics.markDispatch\\(").findAll(agent).count())
    }

    @Test fun manualMcpErrorsExplainUncertainSideEffectsAndNoRetry() {
        assertTrue(settings.contains("if (error is McpExecutionUncertain)"))
        assertTrue(settings.contains("möglicherweise schon"))
        assertTrue(settings.contains("NICHT automatisch erneut aufrufen"))
        assertTrue(settings.contains("PocketDiagnostics.markDispatch("))
    }

    @Test fun everyApprovalPathRecordsDecisionAndCancel() {
        val native = agent.substringAfter("suspend fun requestApproval(")
            .substringBefore("suspend fun requestQuickReadApproval(")
        assertTrue(native.contains("Step.APPROVAL_ACCEPT"))
        assertTrue(native.contains("Step.APPROVAL_DENY"))
        assertTrue(native.contains("setOnCancelListener"))
        val quick = agent.substringAfter("suspend fun requestQuickReadApproval(")
            .substringBefore("suspend fun execute(")
        assertTrue(quick.contains("setOnCancelListener"))
        assertTrue(quick.contains("Step.APPROVAL_DENY"))
    }

    @Test fun healthyProcessReportDoesNotProduceFakeFailure() {
        val show = main.substringAfter("private fun showError(")
        assertTrue(show.contains("val isReport = title == \"Android-Prozessdiagnose\""))
        assertTrue(show.contains("if (isReport) \"\" else"))
    }

    @Test fun accessibleStatusAndHonestOfflineNotice() {
        val status = layout.substringAfter("android:id=\"@+id/mcpAgentSettingsStatus\"")
            .substringBefore("/>")
        assertFalse(status.contains("android:contentDescription="))
        for (locale in listOf("values", "values-en")) {
            val xml = File("src/main/res/$locale/strings.xml").readText()
            val sentence = xml.substringAfter("<string name=\"offline_info\">")
                .substringBefore("</string>")
            assertTrue(sentence.contains("MCP"))
        }
    }

    @Test fun androidMinimumVersionExplainsLocalePersistenceReview() {
        val gradle = File("build.gradle.kts").readText()
        // minSdk 33 = Android 13, where OS app-locale persistence is native.
        // CodeRabbit's Android 12 pre-33 migration advice is out of scope.
        assertTrue(gradle.contains("minSdk = 33"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locale_config\""))
    }
}
