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

    @Test fun toolPromptListsOnlyUserSelectedToolsNeverSecrets() {
        val request = McpAgentBridge.prompt("Bitte verbinde Discord", listOf(tool()))
        assertTrue(request.contains("discord_voice_join"))
        assertTrue(request.contains("channel_id"))
        assertTrue(request.contains("Behaupte niemals"))
        assertFalse(request.contains("Bearer"))
        assertFalse(request.contains("Authorization"))
        assertThrows(IllegalArgumentException::class.java) {
            McpAgentBridge.prompt("x".repeat(2000), listOf(tool()))
        }
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
