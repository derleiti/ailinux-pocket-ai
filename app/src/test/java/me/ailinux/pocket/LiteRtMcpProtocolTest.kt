package me.ailinux.pocket

import me.ailinux.pocket.mcp.McpTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiteRtMcpProtocolTest {
    private val oneTool = McpTool("mcp_search_tools", "Offline bounded catalog search",
        JSONObject("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""))

    @Test fun discoveryUsesOnlyThreeFunctionsAndBoundedPrompt() {
        val s = LiteRtMcpProtocol.shortInstructions(true)
        assertTrue(s.contains("mcp_search_tools"))
        assertTrue(s.contains("mcp_get_tool_details"))
        assertTrue(s.contains("mcp_call_tool"))
        assertTrue(s.contains("At most one remote action"))
        assertTrue(s.contains("DO NOT explain MCP"))
        assertTrue(s.length < 510)
        assertFalse(s.contains("Bearer"))
    }

    @Test fun nativeToolArgumentsMustMatchSelectedSchema() {
        val (actual, args) = LiteRtMcpPolicy.validateRaw(
            "mcp_search_tools", JSONObject().put("query", "discord status"), listOf(oneTool)
        )
        assertEquals(oneTool, actual)
        assertEquals("discord status", args.getString("query"))
    }

    @Test fun rejectUnknownNativeToolWithoutExecution() {
        assertThrows(IllegalArgumentException::class.java) {
            LiteRtMcpPolicy.validateRaw(
                "mcp_call_tool", JSONObject().put("tool_id", "s1/danger"),
                listOf(oneTool)
            )
        }
    }

    @Test fun rejectInvalidArgumentsAndTooManyTools() {
        assertThrows(IllegalArgumentException::class.java) {
            LiteRtMcpPolicy.validateRaw("mcp_search_tools", JSONObject(), listOf(oneTool))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiteRtMcpPolicy.validateRaw(
                "mcp_search_tools", JSONObject().put("query", "status"),
                List(6) { oneTool }
            )
        }
    }

    @Test fun nativeProviderMayNeverRunToolsAutomatically() {
        val source = File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt").readText()
        assertTrue(source.contains("automaticToolCalling = false"))
        assertTrue(source.contains("throw SecurityException("))
        assertTrue(source.contains("suspend fun toolFeedback("))
        assertTrue(source.contains("Content.ToolResponse"))
    }

    @Test fun secondToolProposalAfterRealReceiptIsStoppedWithoutSecondDispatch() {
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
            .substringAfter("private suspend fun runLiteRtToolbridge(")
            .substringBefore("private fun sendLiteRtMessage(")
        val afterCalls = source.substringAfter("if (turn.calls.isEmpty())")
        val guard = afterCalls.indexOf("if (hadRealCall)")
        val validator = afterCalls.indexOf("LiteRtMcpProtocol.validate(")
        assertTrue("Guard must precede model tool parsing", guard >= 0 && guard < validator)
        assertTrue(afterCalls.contains("Weiterer Modell-Toolvorschlag"))
        assertTrue(afterCalls.contains("maximal ein externer Aufruf"))
        assertTrue(source.contains("outcome.toString().take(12_000)"))
    }

    @Test fun remoteDispatchRequiresApprovalAndOneRunGate() {
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
            .substringAfter("private suspend fun runLiteRtToolbridge(")
            .substringBefore("private fun sendLiteRtMessage(")
        assertTrue(source.contains("requestAutoApproval(active, plan)"))
        assertTrue(source.contains("requestApproval(active, proposal)"))
        assertTrue(source.contains("run.markDispatched(plan)"))
        assertTrue(source.contains("mcpAgent.executeAuto(active, plan)"))
        assertTrue(source.contains("mcpAgent.execute(active, proposal)"))
        assertTrue(source.contains("Kein automatischer Wiederholungsversuch"))
        assertFalse(source.contains("Runtime.getRuntime()"))
    }
}
