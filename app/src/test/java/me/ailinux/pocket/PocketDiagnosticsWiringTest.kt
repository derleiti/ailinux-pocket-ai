package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source-level guards do not replace live Pixel MCP QA. */
class PocketDiagnosticsWiringTest {
    private val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
    private val agent = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
    private val settings = File("src/main/java/me/ailinux/pocket/mcp/McpSettingsUi.kt").readText()
    private val diag = File("src/main/java/me/ailinux/pocket/PocketDiagnostics.kt").readText()

    @Test fun toolsListNeverGeneratesAFakeServerReceipt() {
        assertTrue(main.contains("PocketDiagnostics.Step.SEARCH"))
        assertTrue(main.contains("PocketDiagnostics.Step.DETAILS"))
        assertTrue(main.contains("PocketDiagnostics.Step.DISCOVERY_DONE"))
        assertTrue(main.contains("PocketDiagnostics.Step.PROPOSAL"))
        assertTrue(agent.contains("PocketDiagnostics.resultPhase(result)"))
        assertTrue(agent.contains("McpConnection.callTool(authorized, current, plan.arguments)"))
        assertTrue(diag.contains("result.optBoolean(\"isError\", false)"))
        assertTrue(diag.contains("UNCONFIRMED"))
    }

    @Test fun irreversibleDispatchGetsDurableMarkerBeforeNetworkAttempt() {
        val auto = agent.substringAfter("suspend fun executeAuto(")
            .substringBefore("suspend fun requestApproval(")
        val marker = auto.indexOf("PocketDiagnostics.markDispatch(")
        val actual = auto.indexOf("McpConnection.callTool(authorized, current, plan.arguments)")
        assertTrue(marker >= 0 && actual > marker)
        val manual = settings.substringAfter("setPositiveButton(\"Tool starten\")")
        assertTrue(manual.indexOf("PocketDiagnostics.markDispatch(") in
            0 until manual.indexOf("McpConnection.callTool(p, actual, arguments)"))
        assertTrue(agent.contains("throw McpExecutionUncertain(error)"))
    }

    @Test fun sourceDoesNotLogRawToolCredentialsOrModelOutputs() {
        assertFalse(diag.contains("put(\"arguments\""))
        assertFalse(diag.contains("put(\"endpoint\""))
        assertFalse(diag.contains("put(\"token\""))
        assertFalse(diag.contains("put(\"prompt\""))
        assertFalse(diag.contains("getStackTrace"))
        assertTrue(main.contains("PocketDiagnostics.publicError(error)"))
        assertTrue(main.contains("showDiagnosticTimeline(inChat = true)"))
        assertTrue(main.contains("MCP/MODELL-EVENTS"))
        assertTrue(main.contains("text.equals(\"/mcp debug\", true)"))
    }
}
