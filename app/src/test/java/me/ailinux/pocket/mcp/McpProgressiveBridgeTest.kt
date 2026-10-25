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

    @Test fun partialDiscoveryPreservesOriginalSourceIdentityAndApprovalSafety() {
        // When source #2 returned HTTP_FAILURE, source #3 must keep s3 IDs
        // rather than pretending to be the approved but unavailable source #2.
        val alive = listOf(triForce(), McpProgressiveBridge.Source(
            "s3", "workspace", "https://example.test/mcp", "AILinux Workspace",
            listOf(McpTool("workspace_status", "Workspace status and devices",
                JSONObject().put("type", "object")))))
        val run = McpProgressiveBridge.Run(alive)
        assertEquals(4, run.totalTools)
        val result = report(run.search("workspace status", 6))
        val hits = result.getJSONArray("matches")
        assertTrue((0 until hits.length()).any {
            hits.getJSONObject(it).getString("tool_id") == "s3/workspace_status"
        })
        assertFalse(result.toString().contains("s2/"))
        assertEquals("s3/workspace_status",
            report(run.details("s3/workspace_status")).getString("tool_id"))
        assertEquals(0, run.remoteCalls)
    }

    @Test fun mistypedWorkspaceNameLimitsSearchDetailsAndCallToWorkspaceSource() {
        val sources = listOf(
            McpProgressiveBridge.Source("s1","ws","https://example.test/ws",
                "AILinux TriForce Workspace", listOf(
                    McpTool("status","Read Workspace status",
                        JSONObject().put("type","object"),
                        JSONObject().put("readOnlyHint",true)))),
            McpProgressiveBridge.Source("s2","dev","https://example.test/dev",
                "AILinux TriForce DEV MCP", listOf(
                    McpTool("status","Read DEV status",
                        JSONObject().put("type","object"),
                        JSONObject().put("readOnlyHint",true))))
        )
        val state = McpProgressiveBridge.Run(sources)
        val user = "worspace status"
        val result = report(state.search("status", 5, user))
        val matches = result.getJSONArray("matches")
        assertEquals(1, matches.length())
        assertEquals("s1/status",
            matches.getJSONObject(0).getString("tool_id"))
        report(state.details("s1/status", user))
        assertThrows(IllegalArgumentException::class.java) {
            state.details("s2/status", user)
        }
        assertEquals("s1/status",
            state.prepareCall("s1/status", "{}", user).toolId)
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s2/status", "{}", user)
        }
        assertEquals(0, state.remoteCalls)
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
        assertTrue(r.isNull("suggested_tool_id"))
        assertTrue(r.getString("next_step").contains("mcp_get_tool_details"))
    }

    @Test fun largeMultiServerCatalogFindsExactStatusAndDiscordToolsAcrossLanguages() {
        val statusTool = McpTool("status", "Gesamtstatus des TriForce Systems",
            JSONObject().put("type", "object"), JSONObject().put("readOnlyHint", true))
        val triTools = (0 until 89).map {
            McpTool("ailinux_component_$it", "AILinux service component data",
                JSONObject().put("type", "object"))
        } + statusTool
        val discordTools = (0 until 49).map {
            McpTool("discord_feature_$it", "Discord tool list", JSONObject().put("type", "object"))
        } + McpTool("discord_connection_status", "Discord OAuth Verbindung prüfen",
            JSONObject().put("type", "object"))
        val workspaceTools = (0 until 32).map {
            McpTool("workspace_item_$it", "Workspace index entry",
                JSONObject().put("type", "object"))
        }
        val state = McpProgressiveBridge.Run(listOf(
            triForce().copy(tools = triTools),
            discord().copy(tools = discordTools),
            McpProgressiveBridge.Source("s3", "workspace", "https://example.test/mcp",
                "Workspace", workspaceTools)
        ))
        assertEquals(172, state.totalTools)
        val checks = listOf(
            "TriForce-Systemzustand abfragen" to "s1/status",
            "Prüfe den AILinux Systemstatus" to "s1/status",
            "Pruefe Discord Verbindungsstatus" to "s2/discord_connection_status",
            "Discord connection check" to "s2/discord_connection_status"
        )
        for ((query, expected) in checks) {
            val result = report(state.search(query, 5))
            val list = result.getJSONArray("matches")
            assertTrue("Query '$query' missed $expected", (0 until list.length()).any {
                list.getJSONObject(it).getString("tool_id") == expected
            })
            assertEquals("Tool selection must remain an authentic catalog ID", 172,
                result.getInt("total_tools"))
            assertEquals("Unambiguous rank hint for query: " + query,
                expected, result.optString("suggested_tool_id", "missing"))
            assertTrue(result.getBoolean("suggestion_is_not_authorization"))
        }
        // Search snapshots are not tool execution.
        assertEquals(0, state.remoteCalls)
    }

    @Test fun userVisibleSearchResultShowsCompleteNamesWithoutTruncatedJson() {
        val source = McpProgressiveBridge.Source(
            "s1", "big-100", "https://api.ailinux.me/v1/mcp", "TriForce",
            List(97) { i -> McpTool(
                "long_valid_tool_name_number_$i",
                "Read system telemetry available on demand, without taking any action.",
                JSONObject().put("type", "object")
            ) }
        )
        val discordSource = discord()
        val state = McpProgressiveBridge.Run(listOf(source, discordSource))
        assertEquals(100, state.totalTools)
        val original = state.search("mcp tools", 6)
        val textForModel = original.getJSONArray("content")
            .getJSONObject(0).getString("text")
        val parsed = JSONObject(textForModel)
        assertEquals(6, parsed.getJSONArray("matches").length())
        val forUser = McpProgressiveBridge.displaySearchResult(original)
        assertTrue(forUser.contains("100 echte Tools"))
        assertTrue(forUser.contains("2 verbundenen Servern"))
        assertTrue(forUser.contains("6 Treffer"))
        assertTrue(forUser.contains("s2/discord_connection_status"))
        assertTrue(forUser.contains("s1/long_valid_tool_name_number_"))
        assertTrue(forUser.contains("kein Tool ausgeführt"))
        assertFalse(forUser.contains("{\"origin\""))
        assertFalse(forUser.contains("Tool successfully called"))
    }

    @Test fun failedDiscoveryShowsNoAccidentalToolSuccess() {
        val display = McpProgressiveBridge.displaySearchResult(
            McpProgressiveBridge.localFailure("OAuth ist abgelaufen")
        )
        assertTrue(display.contains("OAuth ist abgelaufen"))
        assertTrue(display.contains("Keine Serveraktion ausgeführt"))
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
        val searchResult = report(state.search("guild_info", 5))
        val matches = searchResult.getJSONArray("matches")
        assertEquals(2, matches.length())
        assertTrue(searchResult.isNull("suggested_tool_id"))
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

    @Test fun largeDescriptionsDoNotHideSafeToolParametersFromSmallModels() {
        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject()
                .put("type", "string").put("description", "x".repeat(1500))
                .put("minLength", 4)))
            .put("required", JSONArray().put("query"))
            .put("additionalProperties", false)
        val state = McpProgressiveBridge.Run(listOf(
            triForce().copy(tools = listOf(McpTool("query_status", "Search status", schema)))))
        state.search("query_status")
        val compact = report(state.details("s1/query_status"))
        assertEquals("s1/query_status", compact.getString("tool_id"))
        assertTrue(compact.getBoolean("inputSchema_compacted"))
        assertTrue(compact.getInt("original_schema_bytes") > 950)
        val preview = compact.getJSONObject("inputSchema")
        val field = preview.getJSONObject("properties").getJSONObject("query")
        assertFalse(field.has("description"))
        assertEquals(4, field.getInt("minLength"))
        assertEquals("query", preview.getJSONArray("required").getString(0))
        assertFalse(preview.getBoolean("additionalProperties"))
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s1/query_status", "{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s1/query_status", """{"query":"x"}""")
        }
        val plan = state.prepareCall("s1/query_status", """{"query":"valid"}""")
        assertEquals("valid", plan.arguments.getString("query"))
    }

    @Test fun compactSchemaNeverDropsUserPropertiesCalledTitleOrDescription() {
        val nested = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("description", JSONObject()
                .put("type", "string").put("description", "long metadata ".repeat(100))))
            .put("required", JSONArray().put("description"))
        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("title", JSONObject().put("type","string")
                    .put("description", "long title metadata ".repeat(100)))
                .put("description", JSONObject().put("type","string"))
                .put("default", JSONObject().put("type","boolean"))
                .put("data", nested))
            .put("required", JSONArray().put("title").put("description").put("data"))
            .put("additionalProperties", false)
        val state = McpProgressiveBridge.Run(listOf(
            triForce().copy(tools = listOf(McpTool("submit_data","Read data",schema)))))
        state.search("submit_data")
        val detail = report(state.details("s1/submit_data"))
        val props = detail.getJSONObject("inputSchema").getJSONObject("properties")
        assertTrue(props.has("title"))
        assertTrue(props.has("description"))
        assertTrue(props.has("default"))
        assertTrue(props.has("data"))
        assertTrue(props.getJSONObject("data")
            .getJSONObject("properties").has("description"))
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("s1/submit_data",
                """{"title":"ok","description":"ok","data":{}}""")
        }
    }

    @Test fun uniqueNameOnlyDetailsResolveToSearchedCanonicalLocalId() {
        val state = run()
        state.search("status", 6)
        val details = report(state.details("status"))
        assertEquals("s1/status", details.getString("tool_id"))
        assertEquals("local", details.getString("id_namespace"))
        assertThrows(IllegalArgumentException::class.java) {
            state.prepareCall("status", "{}")
        }
        assertEquals("s1/status", state.prepareCall("s1/status", "{}").toolId)
    }

    @Test fun ambiguousNameOrInventedServerPrefixNeverRedirectsDetails() {
        val state = run()
        state.search("guild_info", 6)
        assertThrows(IllegalArgumentException::class.java) { state.details("guild_info") }
        assertThrows(IllegalArgumentException::class.java) {
            state.details("s4/guild_info")
        }
        assertEquals("s2/guild_info",
            report(state.details("s2/guild_info")).getString("tool_id"))
    }

    @Test fun oversizedSchemasAreNeverMarkedDetailedForExecution() {
        val hugeEnum = JSONArray().apply {
            repeat(140) { put("unique-value-number-" + it.toString().padStart(3, '0')) }
        }
        val large = McpTool("huge_tool", "Huge validation enum", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject()
                .put("type", "string").put("enum", hugeEnum))))
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
        assertTrue(main.contains("mcpAgent.executeAuto(active, plan, dispatchState)"))
        assertTrue(main.contains("engine.sendToolResult(tool.name, payload, 256)"))
        assertTrue(main.contains("resetUnresolvedNativeToolTurn"))
        assertTrue(main.contains("check(mcpAgent.current() == active)"))
        assertTrue(main.contains("if (hadRealCall)"))
    }
}
