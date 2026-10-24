package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Pure in-process progressive MCP discovery; does not contact live servers. */
class McpProgressiveBridgeTest {
    private fun schema(vararg required: String) = JSONObject().put("type", "object")
        .put("properties", JSONObject()
            .put("guild_id", JSONObject().put("type", "string")
                .put("minLength", 17).put("maxLength", 20)))
        .apply {
            if (required.isNotEmpty()) put("required",
                JSONArray().apply { required.forEach { put(it) } })
        }

    private fun triForce() = McpProgressiveBridge.Source(
        "s1", "triforce-id", "https://api.ailinux.me/v1/mcp", "TriForce",
        listOf(
            McpTool("status", "Read overall status", JSONObject().put("type", "object")),
            McpTool("search", "Search project documents", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("query",
                    JSONObject().put("type", "string")))),
            McpTool("guild_info", "TriForce same-name tool", schema("guild_id"))
        ),
        McpAuth.BEARER
    )

    private fun discord() = McpProgressiveBridge.Source(
        "s2", "discord-id", "https://discord.ailinux.me/mcp", "Discord",
        listOf(
            McpTool("discord_connection_status", "Read linked Discord account",
                JSONObject().put("type", "object")),
            McpTool("guild_info", "Discord guild info read", schema("guild_id")),
            McpTool("discord_voice_status", "Read current voice channel",
                JSONObject().put("type", "object"))
        ),
        McpAuth.OAUTH,
        "public-cimid"
    )

    private fun run() = McpProgressiveBridge.Run(listOf(triForce(), discord()))

    private fun report(result: JSONObject): JSONObject =
        JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))

    @Test fun onlyThreeLocalMetaToolsAreVisibleToNativeSpark() {
        val bridge = McpProgressiveBridge.functions()
        assertEquals(3, bridge.size)
        assertEquals(listOf("mcp_search_tools", "mcp_get_tool_details", "mcp_call_tool"),
            bridge.map { it.name })
        val functions = JSONArray(McpAgentBridge.nativeToolsJson(bridge))
        assertEquals(3, functions.length())
        val raw = functions.toString()
        assertFalse(raw.contains("Bearer"))
        assertFalse(raw.contains("OAuth-Refresh"))
        assertFalse(raw.contains("https://api.ailinux.me"))
        assertFalse(raw.contains("discord_connection_status"))
        assertTrue(raw.contains("mcp_search_tools"))
        assertTrue(raw.contains("arguments_json"))
    }

    @Test fun progressiveSearchUsesActualServerToolsAndDisambiguatesNames() {
        val state = run()
        assertEquals(6, state.totalTools)
        val r = report(state.search("guild", 6))
        assertEquals("Pocket AI: authenticated tools/list snapshot",
            r.getString("origin"))
        val matches = r.getJSONArray("matches")
        val refs = (0 until matches.length()).map {
            matches.getJSONObject(it).getString("tool_id")
        }
        assertTrue(refs.contains("s1/guild_info"))
        assertTrue(refs.contains("s2/guild_info"))
        assertFalse(refs.contains("s9/hallucinated"))
        assertTrue(state.searchCount > 0)
    }

    @Test fun englishGermanSynonymSearchFindsRealDiscordStatus() {
        val state = run()
        val result = report(state.search("Prüfe Discord Verbindung", 4))
        val ids = result.getJSONArray("matches")
        val references = (0 until ids.length()).map {
            ids.getJSONObject(it).getString("tool_id")
        }
        assertTrue(references.contains("s2/discord_connection_status"))
        assertTrue(references.none { it.startsWith("s3/") })
    }

    @Test fun vagueCheckMcpToolsProducesBoundedRealCatalogNotInventedTools() {
        val r = report(run().search("mcp tools", 4))
        assertEquals(4, r.getJSONArray("matches").length())
        assertEquals(6, r.getInt("total_tools"))
        assertEquals(2, r.getInt("connected_servers"))
        val sample = r.getJSONArray("matches")
        val ids = (0 until sample.length()).map {
            sample.getJSONObject(it).getString("tool_id")
        }
        assertTrue(ids.any { it.startsWith("s1/") })
        assertTrue(ids.any { it.startsWith("s2/") })
        assertFalse(r.toString().contains("server_log_cleanup"))
        assertTrue(r.getString("next_step").contains("mcp_get_tool_details"))
    }

    @Test fun noMatchingToolReturnsEmptyResultsRatherThanFabrication() {
        val out = report(run().search("missile-language-with-no-mcp-match"))
        assertEquals(0, out.getJSONArray("matches").length())
        assertFalse(out.toString().contains("Tool successfully called"))
    }

    @Test fun detailsRequirePreviousSearchAndActualSourceId() {
        val state = run()
        assertThrows(IllegalArgumentException::class.java) {
            state.details("s2/discord_connection_status")
        }
        val found = report(state.search("connection", 4))
        val id = found.getJSONArray("matches").getJSONObject(0).getString("tool_id")
        val details = report(state.details(id))
        assertEquals(id, details.getString("tool_id"))
        assertTrue(details.has("inputSchema"))
        assertTrue(details.getString("next_step").contains("mcp_call_tool"))
        assertThrows(IllegalArgumentException::class.java) {
            state.details("s0/discord_connection_status")
        }
    }

    @Test fun remoteCallRequiresSearchDetailsAndExactSchemaArguments() {
        val state = run()
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s2/guild_info", "{}")
        }
        val matches = report(state.search("guild_info", 5)).getJSONArray("matches")
        assertEquals(2, matches.length())
        state.details("s2/guild_info")
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s2/guild_info", "{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s2/guild_info", """{"guild_id":123}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s2/guild_info", """{"guild_id":"123456789012345678","shell":"bad"}""")
        }
        val plan = state.prepareCall("s2/guild_info",
            """{"guild_id":"123456789012345678"}""")
        assertEquals("s2/guild_info", plan.toolId)
        assertEquals("Discord", plan.source.title)
        assertEquals("guild_info", plan.tool.name)
        assertEquals("123456789012345678", plan.arguments.getString("guild_id"))
    }

    @Test fun oneRealDispatchIsEnforcedEvenAfterNetworkUncertainty() {
        val state = run()
        state.search("status")
        state.details("s1/status")
        val call = state.prepareCall("s1/status", "{}")
        assertEquals(0, state.remoteCalls)
        state.markDispatched(call)
        assertEquals(1, state.remoteCalls)
        assertThrows(IllegalStateException::class.java) {
            state.prepareCall("s1/status", "{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.markDispatched(call)
        }
    }

    @Test fun catalogExpiresAndCannotBeUsedForSensitiveToolCalls() {
        val origin = 1_000L
        val state = McpProgressiveBridge.Run(listOf(triForce()), origin)
        assertFalse(state.expired(origin + 1_000))
        assertTrue(state.expired(origin + McpProgressiveBridge.CATALOG_LIFETIME_MS + 1))
        assertThrows(IllegalArgumentException::class.java) {
            state.search("status")
        }
    }

    @Test fun oversizedSchemasAreNeverMarkedDetailedForExecution() {
        val large = McpTool("huge_tool", "Large schema", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject()
                .put("type", "string").put("description", "x".repeat(1100)))))
        val source = triForce().copy(tools = listOf(large))
        val state = McpProgressiveBridge.Run(listOf(source))
        assertEquals(1, state.totalTools)
        state.search("huge", 6)
        assertThrows(IllegalArgumentException::class.java) {
            state.details("s1/huge_tool")
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s1/huge_tool", "{}")
        }
    }

    @Test fun unsupportedSchemaToolsNeverBecomeCallable() {
        val unsupported = McpTool("unsafe",
            "Do something powerful", JSONObject()
                .put("type", "object")
                .put("oneOf", JSONArray().put(JSONObject().put("type", "object"))))
        val source = triForce().copy(tools = listOf(unsupported))
        val state = McpProgressiveBridge.Run(listOf(source))
        assertEquals(0, state.totalTools)
        assertEquals(0, report(state.search("unsafe")).getJSONArray("matches").length())
    }

    @Test fun localMetaResponseCannotClaimRemoteExecution() {
        val success = McpProgressiveBridge.localResult(JSONObject()
            .put("matches", JSONArray()))
        assertEquals("complete", success.getString("resultType"))
        assertFalse(success.getBoolean("isError"))
        val error = McpProgressiveBridge.localFailure("credentials <secret>")
        assertTrue(error.getBoolean("isError"))
        assertFalse(error.toString().contains("<secret>"))
        assertTrue(error.toString().contains("Keine Serveraktion ausgeführt"))
    }

    @Test fun tooManyOrDuplicatedServersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            McpProgressiveBridge.Run(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpProgressiveBridge.Run(listOf(triForce(), triForce()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpProgressiveBridge.Run(List(5) { i ->
                triForce().copy(id = "s" + (i + 1))
            })
        }
    }

    @Test fun uiCallsOneRealAuthenticatedEndpointAndShowsRealConsent() {
        val ui = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        assertTrue(ui.contains("AUTO-BRIDGE · Tools automatisch finden"))
        assertTrue(ui.contains("setMultiChoiceItems"))
        assertTrue(ui.contains("McpConnection.inspect(authorized)"))
        assertTrue(ui.contains("mcpAgent") == false) // only UI, never model-triggered
        assertTrue(ui.contains("requestAutoApproval"))
        assertTrue(ui.contains("executeAuto"))
        assertTrue(ui.contains("McpToolSchema.equivalent"))
        assertTrue(ui.contains("McpConnection.callTool(authorized, current, plan.arguments)"))
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        assertTrue(main.contains("runProgressiveToolbridge("))
        assertTrue(main.contains("mcpAgent.requestAutoApproval(active, plan)"))
        assertTrue(main.contains("runtime.markDispatched(plan)"))
        assertTrue(main.contains("mcpAgent.executeAuto(active, plan)"))
        assertTrue(main.contains("engine.sendToolResult(tool.name, payload, 256)"))
        assertTrue(main.contains("resetUnresolvedNativeToolTurn"))
        assertTrue(main.contains("check(mcpAgent.current() == active)"))
        assertTrue(main.contains("if (hadRealCall)"))
    }
}
