package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpAgentBridgeTest {
    private fun tool() = McpTool(
        "discord_voice_join", "Connect to a Discord voice channel",
        JSONObject().put("type", "object")
            .put("required", JSONArray().put("channel_id"))
            .put("properties", JSONObject()
                .put("channel_id", JSONObject().put("type", "string"))
                .put("mute", JSONObject().put("type", "boolean"))
                .put("volume", JSONObject().put("type", "integer")))
    )

    @Test fun nativeToolSchemaActuallySuppliesFunctionDeclarations() {
        val list = McpAgentBridge.nativeToolsJson(listOf(tool()))
        val toolDefs = JSONArray(list)
        assertEquals(1, toolDefs.length())
        val descriptor = toolDefs.getJSONObject(0)
        assertEquals("function", descriptor.getString("type"))
        val function = descriptor.getJSONObject("function")
        assertEquals("discord_voice_join", function.getString("name"))
        assertTrue(function.getString("description").contains("Discord"))
        val params = function.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertEquals("channel_id", params.getJSONArray("required").getString(0))
        assertTrue(params.getJSONObject("properties").has("channel_id"))
        assertFalse(list.contains("Authorization"))
        assertFalse(list.contains("Bearer"))
    }

    @Test fun nativeBindingRejectsExcessToolsAndIncompleteRequiredSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            McpAgentBridge.nativeToolsJson(List(6) { n ->
                tool().copy(name = "tool_" + n)
            })
        }
        val invalid = McpTool("only_partial", "Test", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("known", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("secret_missing")))
        assertThrows(IllegalArgumentException::class.java) {
            McpAgentBridge.nativeToolsJson(listOf(invalid))
        }
    }

    @Test fun sparkNativeFunctionCallIsParsedWithSchema() {
        val answer = "<tool_call>discord_voice_join" +
            "<arg_key>channel_id</arg_key><arg_value>999</arg_value>" +
            "<arg_key>mute</arg_key><arg_value>false</arg_value>" +
            "<arg_key>volume</arg_key><arg_value>42</arg_value></tool_call>"
        val parsed = McpAgentBridge.parse(answer, listOf(tool()))
        assertTrue(parsed is McpAgentBridge.Decision.Proposed)
        val args = (parsed as McpAgentBridge.Decision.Proposed).args
        assertEquals("999", args.getString("channel_id"))
        assertFalse(args.getBoolean("mute"))
        assertEquals(42, args.getInt("volume"))
    }

    @Test fun mcpThinkingModeDefaultsToLowButOffersAllSparkOptions() {
        val session = McpAgentSession(
            profileId = "test", endpoint = "https://example.com/mcp",
            serverName = "Test", tools = listOf(tool())
        )
        assertEquals("low", session.thinkingMode)
        val chooser = java.io.File(
            "src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt"
        ).readText()
        assertTrue(chooser.contains("LOW · empfohlen für MCP"))
        assertTrue(chooser.contains("AUS · schnell"))
        assertTrue(chooser.contains("HIGH · gründlicher"))
        assertTrue(chooser.contains("thinkingPicker.selectedItemPosition"))
        val main = java.io.File(
            "src/main/java/me/ailinux/pocket/MainActivity.kt"
        ).readText()
        assertTrue(main.contains("agentSession.thinkingMode != \"off\""))
        assertTrue(main.contains("engine.setThinkingMode(thinkingMode != \"off\""))
        assertTrue(main.contains("groundedSystemPrompt(active), json, thinkingMode"))
    }

    @Test fun nativeThinkingConfigurationIsRebuiltWithNewSystemPrefix() {
        val kotlin = java.io.File(
            "../inference/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
        ).readText()
        val native = java.io.File(
            "../inference/src/main/cpp/ai_chat.cpp"
        ).readText()
        assertTrue(kotlin.contains("setReasoningModeNative(reasoningMode)"))
        assertTrue(kotlin.contains("reasoningMode in setOf(\"off\", \"low\", \"high\")"))
        assertTrue(native.contains("chat_template_kwargs[\"reasoning_effort\"]"))
        assertTrue(native.contains("g_reasoning_effort = mode == \"low\" ? \"low\" : \"high\""))
        assertTrue(native.contains("msg.reasoning_content = generated.substr"))
        assertTrue(native.contains("g_incomplete_thinking_turn"))
    }

    @Test fun sparkThinkingLowPrefilledPrefixCanProduceRealToolCall() {
        // The actual Spark GGUF generation prompt ends in <think>; the
        // generated stream therefore starts inside a thought, not at <think>.
        val streamed = "I should query the authorized Discord account status." +
            "</think><tool_call>discord_voice_join" +
            "<arg_key>channel_id</arg_key><arg_value>999</arg_value>" +
            "</tool_call>"
        val result = McpAgentBridge.parse(
            streamed, listOf(tool()), thinkingEnabled = true
        )
        assertTrue(result is McpAgentBridge.Decision.Proposed)
        assertEquals("999",
            (result as McpAgentBridge.Decision.Proposed).args.getString("channel_id"))
        val answer = McpAgentBridge.parse(
            "I cannot prove the server status.</think>Keine Verbindung geprüft.",
            listOf(tool()), thinkingEnabled = true
        )
        assertEquals("Keine Verbindung geprüft.",
            (answer as McpAgentBridge.Decision.Answer).text)
    }

    @Test fun unfinishedOrMalformedThinkingMustNeverExecuteATool() {
        val toolCall = "<tool_call>discord_voice_join" +
            "<arg_key>channel_id</arg_key><arg_value>999</arg_value></tool_call>"
        val cases = listOf(
            "I need to think about $toolCall",
            toolCall,
            "</think>$toolCall",
            "<think>thought</think>$toolCall",
            "I am still thinking and do not have a completed tool result"
        )
        for (raw in cases) {
            val result = McpAgentBridge.parse(
                raw, listOf(tool()), thinkingEnabled = true
            )
            assertTrue(raw, result is McpAgentBridge.Decision.Invalid)
        }
    }

    @Test fun acceptsJsonFallbackButNeverCallsUnknownTool() {
        val text = """{"mcp_tool":"discord_voice_join","arguments":{"channel_id":"123"}}"""
        val yes = McpAgentBridge.parse(text, listOf(tool()))
        assertTrue(yes is McpAgentBridge.Decision.Proposed)
        assertTrue(McpAgentBridge.parse(text, emptyList()) is McpAgentBridge.Decision.Invalid)
    }

    @Test fun blocksUntrustedArgumentsAndMissingRequiredFields() {
        for (text in listOf(
            """{"mcp_tool":"discord_voice_join","arguments":{}}""",
            """{"mcp_tool":"discord_voice_join","arguments":{"channel_id":"123","hidden":"x"}}""",
            """{"mcp_tool":"discord_voice_join","arguments":{"channel_id":"123","mute":"true"}}""",
            """{"mcp_tool":"discord_voice_join","arguments":{"channel_id":null}}"""
        )) assertTrue(text, McpAgentBridge.parse(text, listOf(tool())) is McpAgentBridge.Decision.Invalid)
    }

    @Test fun malformedOrMultipleCallsCannotExecute() {
        val cases = listOf(
            "Okay <tool_call>discord_voice_join</tool_call> done!",
            "<tool_call>discord_voice_join<arg_key>channel_id</arg_key>" +
                "<arg_value>123</arg_value></tool_call><tool_call>discord_voice_join</tool_call>",
            "<tool_call>discord_voice_join<arg_key>channel_id</arg_key>" +
                "<arg_value>123</arg_value><arg_key>channel_id</arg_key><arg_value>x</arg_value></tool_call>",
            "<tool_call>discord_voice_join<arg_key>channel_id</arg_key><arg_value>123",
            "<think>incomplete",
            """{"mcp_tool":"discord_voice_join","arguments":"evil"}"""
        )
        for (text in cases) assertTrue(text,
            McpAgentBridge.parse(text, listOf(tool())) is McpAgentBridge.Decision.Invalid)
    }

    @Test fun noToolMeansModelTextOnlyNeverVerifiedAction() {
        val modelLie = "Ich habe den Discord-Voice-Channel schon verbunden."
        val output = McpAgentBridge.parse(modelLie, listOf(tool()))
        assertEquals(modelLie, (output as McpAgentBridge.Decision.Answer).text)
    }

    @Test fun disablingMcpRemovesAllNativeToolDefinitions() {
        assertEquals("[]", McpAgentBridge.nativeToolsJson(emptyList()))
    }

    @Test fun rejectedEnumsDoNotReachToolExecutor() {
        val enumTool = McpTool("volume_set", "Change channel volume",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("level",
                    JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("quiet").put("normal")))))
        val valid = """{"mcp_tool":"volume_set","arguments":{"level":"normal"}}"""
        val invalid = """{"mcp_tool":"volume_set","arguments":{"level":"overdrive"}}"""
        assertTrue(McpAgentBridge.parse(valid, listOf(enumTool)) is McpAgentBridge.Decision.Proposed)
        assertTrue(McpAgentBridge.parse(invalid, listOf(enumTool)) is McpAgentBridge.Decision.Invalid)
    }

    @Test fun rejectsNonSelectedToolsEvenWithPerfectlyFormattedNativeTag() {
        val answer = "<tool_call>shell<arg_key>command</arg_key><arg_value>id</arg_value></tool_call>"
        assertTrue(McpAgentBridge.parse(answer, listOf(tool())) is McpAgentBridge.Decision.Invalid)
    }

    @Test fun actualToolResponseIsLabelledAsServerResultNotIndependentProof() {
        val reply = JSONObject().put("isError", true)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "No permission")))
        val receipt = McpAgentBridge.receipt(tool(), reply)
        assertTrue(receipt.contains("SERVER MELDET FEHLER"))
        assertTrue(McpAgentBridge.summaryPrompt(tool(), reply).contains("No permission"))
    }
}
