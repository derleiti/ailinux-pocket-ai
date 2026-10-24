package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpQuickActionsTest {
    private fun profile(endpoint: String) = McpProfile(title = "Quick Test", endpoint = endpoint)
    private fun request(text: String) =
        McpQuickActions.requestedAction(text) as McpQuickActions.Selection.Request

    @Test fun directCommandsAreExactAndDeterministic() {
        val commands = mapOf(
            "/mcp quick discord-status" to "discord_connection_status",
            "/mcp quick discord-guilds" to "discord_authorized_guilds",
            "/mcp quick discord-voice" to "discord_voice_status",
            "/mcp quick triforce-status" to "status",
            "/mcp quick notifications" to "notify_list",
            "/mcp search linux mirror" to "search",
            "/mcp code-search processSystemPrompt" to "code_search",
            "/mcp memory-search Brumo" to "memory_search"
        )
        commands.forEach { (input, tool) ->
            assertEquals(input, tool, request(input).toolName)
        }
        assertEquals("linux mirror",
            request("/mcp search linux mirror").args.getString("query"))
    }

    @Test fun clearDiscordReadOnlyNaturalLanguage() {
        val requests = mapOf(
            "Prüfe ob mein Discord Konto verbunden ist" to "discord_connection_status",
            "check discord connection" to "discord_connection_status",
            "Welche Discord Guilds sind freigegeben?" to "discord_authorized_guilds",
            "Prüfe Discord Voice Status" to "discord_voice_status",
            "Zeig den Status von Nova Discord Voice" to "discord_voice_status"
        )
        requests.forEach { (text, expected) ->
            assertEquals(text, expected, request(text).toolName)
        }
    }

    @Test fun ambiguousOrMutatingCommandsNeverAutoRoute() {
        val inputs = listOf(
            "Verbinde mich mit Discord Voice",
            "join Discord Voice",
            "Schreib Dennis eine Discord Nachricht",
            "Lösche einen Discord Server",
            "Erkläre Discord Accounts",
            "Was ist ein Discord Server",
            "Hallo Brumo!",
            "Zeig alle Dateien auf deinem Handy",
            "/mcp quick shell"
        )
        for (input in inputs) {
            val matched = McpQuickActions.requestedAction(input)
            assertFalse(input, matched is McpQuickActions.Selection.Request)
        }
        assertTrue(McpQuickActions.requestedAction("/mcp quick shell")
            is McpQuickActions.Selection.Invalid)
    }

    @Test fun onlyMatchingHttpsEndpointsCanReceiveQuickCalls() {
        assertTrue(McpQuickActions.matchesProfile(
            profile("https://discord.ailinux.me/mcp"), McpQuickActions.Target.DISCORD))
        assertTrue(McpQuickActions.matchesProfile(
            profile("https://api.ailinux.me/v1/mcp"), McpQuickActions.Target.TRIFORCE))
        assertFalse(McpQuickActions.matchesProfile(
            profile("https://discord.ailinux.me.evil.example/mcp"), McpQuickActions.Target.DISCORD))
        assertFalse(McpQuickActions.matchesProfile(
            profile("http://discord.ailinux.me/mcp"), McpQuickActions.Target.DISCORD))
        assertFalse(McpQuickActions.matchesProfile(
            profile("https://api.ailinux.me/v1/mcp/sse"), McpQuickActions.Target.TRIFORCE))
        assertFalse(McpQuickActions.matchesProfile(
            profile("https://api.ailinux.me/v1/mcp"), McpQuickActions.Target.DISCORD))
    }

    @Test fun onlyActualAdvertisedAndSchemaValidToolsAreProposed() {
        val req = request("/mcp quick discord-status")
        val server = McpServerInfo("discord", "2025-11-25", listOf(
            McpTool("discord_connection_status", "Read account", JSONObject()
                .put("type", "object").put("properties", JSONObject())),
            McpTool("discord_voice_status", "Read voice", JSONObject())
        ))
        val proposal = McpQuickActions.validatedProposal(req, server)
        assertEquals("discord_connection_status", proposal.tool.name)
        assertEquals(0, proposal.args.length())
        assertThrows(IllegalStateException::class.java) {
            McpQuickActions.validatedProposal(req, server.copy(tools = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpQuickActions.validatedProposal(req, server.copy(tools = listOf(
                McpTool("discord_connection_status", "changed contract", JSONObject()
                    .put("required", JSONArray().put("secret"))
                    .put("properties", JSONObject().put("secret", JSONObject().put("type", "string")))
            ))))
        }
    }

    @Test fun refusesMismatchedTargetAndUnexpectedArguments() {
        val discord = McpTool("discord_connection_status", "Read", JSONObject())
        val info = McpServerInfo("discord", "2025-11-25", listOf(discord))
        assertThrows(IllegalArgumentException::class.java) {
            McpQuickActions.validatedProposal(
                McpQuickActions.Selection.Request(
                    McpQuickActions.Target.TRIFORCE, "discord_connection_status"
                ), info
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpQuickActions.validatedProposal(
                McpQuickActions.Selection.Request(
                    McpQuickActions.Target.DISCORD, "discord_connection_status",
                    JSONObject().put("surprise", "bad")
                ), info
            )
        }
    }

    @Test fun quickCommandsAreVisibleAndRequireOneTimeApproval() {
        val main = java.io.File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val ui = java.io.File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        val health = main.indexOf("McpHealthCheck.requestedTarget(text)")
        val catalog = main.indexOf("McpToolCatalog.requestedFilter(text)")
        val quick = main.indexOf("McpQuickActions.requestedAction(text)")
        val llm = main.indexOf("val request = text")
        assertTrue(health in 1 until catalog)
        assertTrue(catalog < quick && quick < llm)
        assertTrue(main.contains("McpConnection.inspect(authorized)"))
        assertTrue(main.contains("McpQuickActions.validatedProposal(request, info)"))
        assertTrue(main.contains("mcpAgent.requestQuickReadApproval(authorized, proposal)"))
        assertTrue(main.contains("if (!approved)"))
        assertTrue(main.contains("McpConnection.callTool(authorized, proposal.tool, proposal.args)"))
        assertTrue(ui.contains("MCP Schnellbefehle (ohne Spark)"))
        assertTrue(ui.contains("Einmal lesen"))
    }

    @Test fun searchIsValidatedAgainstActualSchemaAndNeverExecutesOtherTools() {
        val req = request("/mcp code-search MCP Bridge")
        val tool = McpTool("code_search", "read code", JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("query"))
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string"))))
        val parsed = McpQuickActions.validatedProposal(req,
            McpServerInfo("Triforce", "2026-07-28", listOf(tool)))
        assertEquals("MCP Bridge", parsed.args.getString("query"))
        assertEquals("code_search", parsed.tool.name)
        assertThrows(IllegalStateException::class.java) {
            McpQuickActions.validatedProposal(req,
                McpServerInfo("Triforce", "2026-07-28", listOf(
                    McpTool("shell", "danger", JSONObject())
                )))
        }
    }

    @Test fun permissionFailuresCannotBeMisreportedAsVoiceConnected() {
        val tool = McpTool("discord_voice_status", "read", JSONObject())
        val reply = JSONObject().put("available", false)
            .put("reason", "server_read_grant_required")
        val rendered = McpQuickActions.displayReceipt(tool, reply)
        assertTrue(rendered.contains("server_read_grant_required"))
        assertTrue(rendered.contains("Lesefreigabe"))
        assertTrue(rendered.contains("Live tools/call"))
        assertFalse(rendered.contains("Voice verbunden und aktiv"))
    }
}
