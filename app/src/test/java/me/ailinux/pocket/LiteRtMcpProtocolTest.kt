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

    @Test fun classifyInvalidTinyModelNativeCallsWithoutLoggingParameters() {
        val tools = listOf(oneTool)
        assertNull(LiteRtMcpPolicy.diagnoseRaw(
            "mcp_search_tools", """{"query":"status"}""", tools))
        assertNull(LiteRtMcpPolicy.diagnoseRaw(
            "mcp_search_tools", mapOf("query" to "system status"), tools))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_SCHEMA,
            LiteRtMcpPolicy.diagnoseRaw(
                "mcp_search_tools", mapOf("limit" to 3), tools))
        assertEquals(LiteRtMcpPolicy.Fault.UNKNOWN_TOOL_NAME,
            LiteRtMcpPolicy.diagnoseRaw(
                "status", """{"query":"status"}""", tools))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_NOT_JSON,
            LiteRtMcpPolicy.diagnoseRaw(
                "mcp_search_tools", "not json", tools))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_SCHEMA,
            LiteRtMcpPolicy.diagnoseRaw(
                "mcp_search_tools", "{}", tools))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_TOO_LARGE,
            LiteRtMcpPolicy.diagnoseRaw(
                "mcp_search_tools", "{\"query\":\"" + "x".repeat(3500) + "\"}", tools))
        assertEquals(LiteRtMcpPolicy.Fault.TOO_MANY_TOOLS,
            LiteRtMcpPolicy.diagnoseRaw(
                "mcp_search_tools", """{"query":"status"}""", List(6) { oneTool }))
    }

    @Test fun cancelledLiteRtTurnMustNotBeRecoveredAsNativeParserFailure() {
        val rejected = java.util.concurrent.atomic.AtomicInteger()
        val cancellation = kotlinx.coroutines.CancellationException("user cancelled")
        val error = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                LiteRtNativeResponseBoundary.call(
                    invokeNative = { throw cancellation },
                    onInvalidNativeResponse = { rejected.incrementAndGet() }
                )
            }
        }
        assertSame(cancellation, error)
        assertEquals(0, rejected.get())
        for (nativeError in listOf(IllegalArgumentException("invalid"),
            IllegalStateException("invalid"))) {
            val actual = kotlinx.coroutines.runBlocking {
                LiteRtNativeResponseBoundary.call(
                    invokeNative = { throw nativeError },
                    onInvalidNativeResponse = { rejected.incrementAndGet() }
                )
            }
            assertNull(actual)
        }
        assertEquals(2, rejected.get())
        val bridge = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
            .substringAfter("private suspend fun runLiteRtToolbridge(")
            .substringBefore("private fun sendLiteRtMessage(")
        assertEquals(3, Regex("LiteRtNativeResponseBoundary\\.call\\(").findAll(bridge).count())
    }

    @Test fun nativeParserFailureBeforeToolCallIsHandledForInitialAndFollowup() {
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
            .substringAfter("private suspend fun runLiteRtToolbridge(")
            .substringBefore("private fun sendLiteRtMessage(")
        assertTrue(source.indexOf("val catalog =") < source.indexOf("runtime.askMcp(request)"))
        val initial = source.substringAfter("var turn = LiteRtNativeResponseBoundary.call(")
            .substringBefore("for (step in 0 until")
        assertTrue(initial.contains("runtime.askMcp(request)"))
        assertTrue(initial.contains("onInvalidNativeResponse = { recoverNativeFailure() }"))
        val followup = source.substringAfter("val boundedFeedback =")
        assertTrue(followup.contains("runtime.toolFeedback(tool.name, boundedFeedback)"))
        assertTrue(followup.contains("recoverNativeFailure()"))
        assertTrue(source.contains("if (hadRealCall) {"))
        val recovery = source.substringAfter("suspend fun recoverNativeFailure() {")
            .substringBefore("var turn = LiteRtNativeResponseBoundary.call(")
        assertTrue(recovery.contains("if (hadRealCall) {"))
        assertTrue(recovery.contains("runtime.clearConversation()"))
        assertTrue(recovery.contains("tryHostDiscovery("))
        assertTrue(recovery.contains("kein weiterer Aufruf"))
    }

    private fun metaCall() = McpTool("mcp_call_tool", "Local bridge",
        JSONObject("""{"type":"object","properties":{
            "tool_id":{"type":"string"},
            "arguments_json":{"type":"string","minLength":2,"maxLength":3000}
            },"required":["tool_id","arguments_json"],
            "additionalProperties":false}"""))

    @Test fun metaJsonObjectMayBeConvertedToJsonStringWithoutGuessingParameters() {
        val allowed = listOf(metaCall())
        val asObject = """{"tool_id":"s3/status","arguments_json":{}}"""
        val asMap = mapOf("tool_id" to "s3/status",
            "arguments_json" to emptyMap<String, Any>())
        assertTrue(LiteRtMcpPolicy.hasObjectStyleMetaArguments("mcp_call_tool", asObject))
        assertTrue(LiteRtMcpPolicy.hasObjectStyleMetaArguments("mcp_call_tool", asMap))
        assertNull(LiteRtMcpPolicy.diagnoseRaw("mcp_call_tool", asObject, allowed))
        assertNull(LiteRtMcpPolicy.diagnoseRaw("mcp_call_tool", asMap, allowed))
        val normalized = LiteRtMcpPolicy.normalizeRaw("mcp_call_tool", asMap)!!
        assertEquals("s3/status", normalized.getString("tool_id"))
        assertEquals("{}", normalized.getString("arguments_json"))
        val validated = LiteRtMcpPolicy.validateRaw("mcp_call_tool", normalized, allowed)
        assertEquals("{}", validated.second.getString("arguments_json"))
        assertFalse(LiteRtMcpPolicy.hasObjectStyleMetaArguments("mcp_call_tool",
            """{"tool_id":"s3/status","arguments_json":"{}"}"""))
    }

    @Test fun metaJsonNormalizationNeverSuppliesMissingFieldsOrRelaxedTypes() {
        val allowed = listOf(metaCall())
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_SCHEMA,
            LiteRtMcpPolicy.diagnoseRaw("mcp_call_tool",
                """{"tool_id":"s3/status"}""", allowed))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_SCHEMA,
            LiteRtMcpPolicy.diagnoseRaw("mcp_call_tool",
                """{"arguments_json":{}}""", allowed))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_SCHEMA,
            LiteRtMcpPolicy.diagnoseRaw("mcp_call_tool",
                """{"tool_id":"s3/status","arguments_json":[] }""", allowed))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_SCHEMA,
            LiteRtMcpPolicy.diagnoseRaw("mcp_call_tool",
                """{"tool_id":"s3/status","arguments_json":{},"permission":"write"}""",
                allowed))
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_TOO_LARGE,
            LiteRtMcpPolicy.diagnoseRaw("mcp_call_tool",
                mapOf("tool_id" to "s3/status",
                    "arguments_json" to mapOf("private" to "z".repeat(3600))),
                allowed))
        // This conversion is strictly for local mcp_call_tool, never a remote
        // tool nor any other host meta-function.
        assertEquals(LiteRtMcpPolicy.Fault.ARGUMENTS_SCHEMA,
            LiteRtMcpPolicy.diagnoseRaw("mcp_search_tools",
                """{"query":{"name":"status"}}""", listOf(oneTool)))
        assertFalse(LiteRtMcpPolicy.hasObjectStyleMetaArguments("status",
            mapOf("arguments_json" to emptyMap<String, Any>())))
    }

    @Test fun invalidNativeProposalResetsOnlyConversationAndNeverDispatches() {
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
            .substringAfter("private suspend fun runLiteRtToolbridge(")
            .substringBefore("private fun sendLiteRtMessage(")
        val guard = source.indexOf("val invalid =")
        val execute = source.indexOf("mcpAgent.executeAuto(")
        assertTrue("validation must run before dispatch", guard >= 0 && guard < execute)
        val section = source.substringAfter("if (invalid != null) {")
            .substringBefore("val (tool, args) =")
        assertTrue(section.contains("PocketDiagnostics.Step.SCHEMA_CHECK"))
        assertTrue(section.contains("runtime.clearConversation()"))
        assertTrue(section.contains("PocketDiagnostics.Step.MODEL_RESET"))
        assertTrue(section.contains("Kein tools/call ausgeführt"))
        assertFalse(section.contains("mcpAgent.executeAuto("))
        assertFalse(section.contains("mcpAgent.execute("))
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
        assertTrue(source.contains("mcpAgent.executeAuto(active, plan, dispatchState)"))
        assertTrue(source.contains("mcpAgent.execute(active, proposal, dispatchState)"))
        assertTrue(source.contains("Kein automatischer Wiederholungsversuch"))
        assertFalse(source.contains("Runtime.getRuntime()"))
    }
}
