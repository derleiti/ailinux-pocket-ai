package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpOnDemandDiscoveryTest {
    private fun run(tools: List<McpTool>) = McpProgressiveBridge.Run(listOf(
        McpProgressiveBridge.Source("s1", "profile", "https://example.test/mcp", "TriForce", tools)
    ))
    private fun tool(
        name: String,
        schema: JSONObject = JSONObject().put("type", "object"),
        annotations: JSONObject = JSONObject().put("readOnlyHint", true)
    ) = McpTool(name, "Tool for $name", schema, annotations)

    @Test fun autoModeCanPlanNamedReadOnlyToolAmong172WithoutSendingTheCatalog() {
        val tri = (0 until 89).map {
            tool("ailinux_component_$it")
        } + tool("status")
        val discord = (0 until 49).map {
            tool("discord_feature_$it")
        } + tool("discord_connection_status")
        val workspace = (0 until 32).map {
            tool("workspace_item_$it")
        }
        val state = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "tri", "https://example.test/mcp",
                "TriForce", tri),
            McpProgressiveBridge.Source("s2", "discord", "https://example.test/discord",
                "Discord", discord),
            McpProgressiveBridge.Source("s3", "workspace", "https://example.test/ws",
                "Workspace", workspace)
        ))
        assertEquals(172, state.totalTools)
        val triPlan = McpOnDemandDiscovery.propose(
            "Brumo, frage mit MCP den TriForce-Systemzustand ab", state)
        assertEquals("s1/status", triPlan?.toolId)
        val discordPlan = McpOnDemandDiscovery.propose(
            "Prüfe per MCP den Discord Verbindungsstatus", state)
        assertEquals("s2/discord_connection_status", discordPlan?.toolId)
        assertEquals(0, state.remoteCalls)
        assertNull(McpOnDemandDiscovery.propose(
            "Erkläre mir den Discord Verbindungsstatus", state))
    }

    @Test fun vagueDiscordConnectedRequestWith172ToolsMustNotGuessAnyOtherTool() {
        val triforce = (0 until 90).map {
            tool("triforce_check_$it")
        }
        val discord = (0 until 49).map {
            tool("discord_feature_$it")
        } + tool("discord_connection_status")
        val workspace = (0 until 32).map {
            tool("workspace_utility_$it")
        }
        val state = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1","tri","https://example.test/tri",
                "TriForce",triforce),
            McpProgressiveBridge.Source("s2","discord","https://example.test/discord",
                "Discord",discord),
            McpProgressiveBridge.Source("s3","workspace","https://example.test/ws",
                "Workspace",workspace)
        ))
        assertEquals(172,state.totalTools)
        for (request in listOf(
            "Prüfe, ob Discord verbunden ist",
            "Check my Discord connection",
            "Verify the Discord connection using MCP"
        )) {
            val proposal = McpOnDemandDiscovery.propose(request,state)
            assertEquals(request, "s2/discord_connection_status",proposal?.toolId)
            assertTrue(request,proposal!!.hostFallback)
        }
        assertEquals(0,state.remoteCalls)
    }

    @Test fun internationalDiscordConnectionPromptsPlanOnlyRealReadOnlyTool() {
        val state = run(listOf(tool("discord_connection_status"),
            tool("read_and_clear_notifications")))
        for (request in listOf(
            "Check my Discord connection",
            "Verify the Discord connection using MCP",
            "Prüfe, ob Discord verbunden ist",
            "Kannst du bitte nachsehen, ob die Discord-Verbindung besteht?"
        )) {
            val plan = McpOnDemandDiscovery.propose(request, state)
            assertEquals("Wrong tool for " + request,
                "discord_connection_status", plan?.tool?.name)
            assertTrue(plan!!.hostFallback)
        }
        assertEquals("Only discovery and planning, no tools/call",0,state.remoteCalls)
        assertNull(McpOnDemandDiscovery.propose(
            "Explain how to check Discord connection", state))
        assertNull(McpOnDemandDiscovery.propose(
            "How do I check my Discord connection?", state))
        assertNull(McpOnDemandDiscovery.propose(
            "Wie kann ich prüfen, ob Discord verbunden ist?", state))
    }

    @Test fun naturalDevMcpStatusToolCallResolvesExactlyNamedProvider() {
        val status = tool("status")
        val providers = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "workspace-profile",
                "https://api.ailinux.me/v1/mcp", "AILinux TriForce Workspace",
                listOf(status)),
            McpProgressiveBridge.Source("s2", "dev-profile",
                "https://api.ailinux.me/dev/mcp", "AILinuX TriForce DEV MCP",
                listOf(status)),
            McpProgressiveBridge.Source("s3", "discord-profile",
                "https://discord.ailinux.me/mcp", "AILinux Discord MCP",
                listOf(tool("discord_connection_status")))
        ))
        val plan = McpOnDemandDiscovery.propose(
            "prüf den truforce dev mcp status per mcp tool call", providers)
        assertNotNull("User explicitly requested DEV provider + status, not generic tool call", plan)
        assertEquals("s2/status", plan!!.toolId)
        assertEquals("dev-profile", plan.source.profileId)
        assertTrue(plan.hostFallback)
        assertEquals(0, providers.remoteCalls)
    }

    @Test fun largeCatalogTargetsDevServerWithoutPromptingAllDefinitions() {
        val a = (0 until 89).map {
            tool("aihelper_telemetry_" + it)
        } + tool("status")
        val b = (0 until 79).map {
            tool("dev_diagnostic_" + it)
        } + tool("status")
        val c = listOf(tool("discord_connection_status"), tool("current_time"))
        val run = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "workspace-profile",
                "https://api.ailinux.me/v1/mcp", "AILinuX TriForce Workspace", a),
            McpProgressiveBridge.Source("s2", "dev-profile",
                "https://api.ailinux.me/dev/mcp", "AILinuX TriForce DEV MCP", b),
            McpProgressiveBridge.Source("s3", "discord-profile",
                "https://discord.ailinux.me/mcp", "AILinuX Discord MCP", c)
        ))
        assertEquals(172, run.totalTools)
        val candidate = McpOnDemandDiscovery.propose(
            "Prüfe den Truforce DEV MCP Status per MCP Tool Call", run)
        assertNotNull(candidate)
        assertEquals("s2/status", candidate!!.toolId)
        assertTrue(candidate.hostFallback)
        assertEquals(0, run.remoteCalls)
        assertEquals("{}", candidate.arguments.toString())
    }

    @Test fun shortWorkspaceStatusTypoPlansOnlyRealReadOnlyWorkspaceTool() {
        val run = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "workspace-id",
                "https://example.test/ws", "AILinux TriForce Workspace",
                listOf(tool("status"))),
            McpProgressiveBridge.Source("s2", "dev-id",
                "https://example.test/dev", "AILinux TriForce DEV MCP",
                listOf(tool("status")))
        ))
        for (userRequest in listOf("workspace status", "worspace status",
                "Brumo, worspace status")) {
            val proposed = McpOnDemandDiscovery.propose(userRequest, run)
            assertNotNull("Cannot omit a safe app proposal: " + userRequest, proposed)
            assertEquals(userRequest, "s1/status", proposed!!.toolId)
            assertEquals("workspace-id", proposed.source.profileId)
            assertEquals("{}", proposed.arguments.toString())
            assertTrue(proposed.hostFallback)
        }
        assertNull(McpOnDemandDiscovery.propose("status", run))
        assertNull(McpOnDemandDiscovery.propose(
            "Erkläre mir den Workspace-Status", run))
        assertEquals(0, run.remoteCalls)
    }

    @Test fun ambiguousProviderForIdenticalToolNamesAbstainsWithoutDispatch() {
        val sameName = tool("status")
        val providers = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "p1",
                "https://api.ailinux.me/v1/mcp", "AILinuX TriForce Workspace",
                listOf(sameName)),
            McpProgressiveBridge.Source("s2", "p2",
                "https://example.test/mcp", "AILinuX TriForce DEV MCP",
                listOf(sameName))
        ))
        assertNull(McpOnDemandDiscovery.propose(
            "Prüfe mit MCP den TriForce-Systemstatus", providers))
        assertEquals(0, providers.remoteCalls)
    }

    @Test fun genericToolCallWordIsNotAnExplicitMcpToolName() {
        val state = run(listOf(tool("status")))
        assertNotNull(McpOnDemandDiscovery.propose(
            "Rufe den MCP Status über einen Tool Call ab", state))
        assertNull(McpOnDemandDiscovery.propose(
            "Erkläre mir bitte was ein MCP Tool Call ist", state))
        assertEquals(0, state.remoteCalls)
    }

    @Test fun explicitDevMcpStatusTargetsOnlyTheNamedServerInAutoMode() {
        val statusTool = tool("status")
        val run = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "workspace", "https://example.test/ws",
                "AILinux TriForce Workspace", listOf(statusTool)),
            McpProgressiveBridge.Source("s2", "dev", "https://example.test/dev",
                "TriForce DEV MCP", listOf(statusTool)),
            McpProgressiveBridge.Source("s3", "discord", "https://example.test/discord",
                "Discord MCP", listOf(tool("discord_connection_status")))
        ))
        val proposal = McpOnDemandDiscovery.propose(
            "nutze das triforce dev mcp status tool", run)
        assertNotNull("The explicit DEV server makes status unambiguous", proposal)
        assertEquals("s2/status", proposal!!.toolId)
        assertTrue(proposal.hostFallback)
        assertEquals(0, run.remoteCalls)
    }

    @Test fun sameNamedToolsAcrossTwoServersWithoutSourceRemainAmbiguous() {
        val same = tool("status")
        val run = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "one", "https://example.test/one",
                "TriForce Primary MCP", listOf(same)),
            McpProgressiveBridge.Source("s2", "two", "https://example.test/two",
                "TriForce DEV MCP", listOf(same))
        ))
        assertNull(McpOnDemandDiscovery.propose("MCP status prüfen",run))
        assertEquals(0, run.remoteCalls)
    }

    @Test fun autoHostPlanRejectsAmbiguousSourcesWithSafeDiagnosticReason() {
        val tool = tool("status")
        val state = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "p1", "https://example.test/1",
                "TriForce Workspace", listOf(tool)),
            McpProgressiveBridge.Source("s2", "p2", "https://example.test/2",
                "TriForce DEV MCP", listOf(tool))
        ))
        var reason: McpOnDemandDiscovery.Rejection? = null
        assertNull(McpOnDemandDiscovery.propose(
            "Prüfe MCP TriForce Status", state) { reason = it })
        assertEquals(McpOnDemandDiscovery.Rejection.AMBIGUOUS_TOOL, reason)
        assertEquals(0, state.remoteCalls)
    }

    @Test fun autoHostPlanRejectsUnknownReadOnlyRiskWithoutDispatch() {
        val tool = tool("status", annotations = JSONObject())
        val state = run(listOf(tool))
        var reason: McpOnDemandDiscovery.Rejection? = null
        assertNull(McpOnDemandDiscovery.propose(
            "Rufe MCP Status ab", state) { reason = it })
        assertEquals(McpOnDemandDiscovery.Rejection.UNVERIFIED_READONLY, reason)
        assertEquals(0, state.remoteCalls)
    }

    @Test fun exactCrawlRequestCreatesOnlyApprovedCandidateNotExecution() {
        val schema = JSONObject("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
        val run = run(listOf(tool("crawl", schema)))
        val plan = McpOnDemandDiscovery.propose("check mit mcp crawl tool https://ailinux.me", run)
        assertNotNull(plan)
        assertEquals("crawl", plan!!.tool.name)
        assertTrue(plan.hostFallback)
        assertEquals("https://ailinux.me", plan.arguments.getString("url"))
        assertEquals(0, run.remoteCalls)
    }

    @Test fun statusAfterNativeSearchAndDetailsMayBeProposedButNotDispatched() {
        val state = run(listOf(tool("status"), tool("device_info")))
        val search = state.search("status")
        assertFalse(search.optBoolean("isError"))
        val details = state.details("s1/status")
        assertFalse(details.optBoolean("isError"))
        val plan = McpOnDemandDiscovery.propose(
            "Brumo, rufe bitte mit MCP den TriForce-Systemstatus ab", state)
        assertNotNull("Host must continue after native discovery", plan)
        assertEquals("status", plan!!.tool.name)
        assertEquals("{}", plan.arguments.toString())
        assertEquals("Search and details never dispatch remotely", 0, state.remoteCalls)
    }

    @Test fun onlyExplicitStatusIntentCreatesAnAuditedReadOnlyPlan() {
        val state = run(listOf(tool("status"), tool("device_info")))
        assertNotNull(McpOnDemandDiscovery.propose("Prüfe den MCP Status", state))
        assertNull(McpOnDemandDiscovery.propose(
            "Erkläre mir bitte, was der MCP Status bedeutet", state))
    }

    @Test fun queryStringIsPreservedAndMisleadingReadNameRejected() {
        val schema = JSONObject("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
        val plan = McpOnDemandDiscovery.propose(
            "mcp crawl https://ailinux.me/search?q=gemma&lang=de",
            run(listOf(tool("crawl", schema))))
        assertEquals("https://ailinux.me/search?q=gemma&lang=de",
            plan!!.arguments.getString("url"))
        assertNull(McpOnDemandDiscovery.propose("mcp tool read_and_clear_notifications",
            run(listOf(tool("read_and_clear_notifications")))))
    }

    @Test fun hostFallbackRequiresExplicitBooleanReadOnlyHint() {
        val intent = "Brumo, prüfe den MCP TriForce Status"
        assertNull(McpOnDemandDiscovery.propose(intent,
            run(listOf(tool("status", annotations = JSONObject())))))
        assertNull(McpOnDemandDiscovery.propose(intent,
            run(listOf(tool("status", annotations =
                JSONObject().put("readOnlyHint", false))))))
        assertNull(McpOnDemandDiscovery.propose(intent,
            run(listOf(tool("status", annotations =
                JSONObject().put("readOnlyHint", "true"))))))
        val good = McpOnDemandDiscovery.propose(intent,
            run(listOf(tool("status", annotations =
                JSONObject().put("readOnlyHint", true)))))
        assertNotNull(good)
        assertTrue(good!!.hostFallback)
    }

    @Test fun readOnlyHintIsNotEnoughWithoutAuditedToolName() {
        val malicious = tool("read_and_clear_notifications")
        assertTrue(malicious.declaresReadOnly)
        assertNull(McpOnDemandDiscovery.propose(
            "MCP tool read_and_clear_notifications", run(listOf(malicious))))
    }

    @Test fun parenthesizedCrawlUrlKeepsQueryButNotClosingDelimiters() {
        val schema = JSONObject("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
        val plan = McpOnDemandDiscovery.propose(
            "mcp crawl (https://ailinux.me/search?q=gemma&lang=de)",
            run(listOf(tool("crawl", schema))))
        assertNotNull(plan)
        assertEquals("https://ailinux.me/search?q=gemma&lang=de",
            plan!!.arguments.getString("url"))
    }

    @Test fun noSideEffectingFallbackAndNoInventedParameters() {
        assertNull(McpOnDemandDiscovery.propose("mcp tool delete", run(listOf(tool("delete")))))
        val required = JSONObject("""{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""")
        assertNull(McpOnDemandDiscovery.propose("mcp tool read_message", run(listOf(tool("read_message", required)))))
    }

    @Test fun ambiguousToolNamesFailClosedAndUnrelatedChatDoesNothing() {
        val run = run(listOf(tool("get_info"), tool("read_info")))
        assertNull(McpOnDemandDiscovery.propose("MCP info", run))
        assertNull(McpOnDemandDiscovery.propose("Servus Brumo", run))
    }

    @Test fun hostFallbackSharedBetweenEnginePathsAndRequiresApproval() {
        val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        assertTrue(activity.contains("tryHostDiscovery(active, request, runtime, answer, answerIndex)"))
        assertFalse(activity.contains("if (!hadRealCall && !hadSearch &&"))
        assertFalse(activity.contains("(catalog?.searchCount ?: 0) == 0"))
        assertTrue(activity.contains("McpRequestRouting.expectsToolEvidence(request)"))
        assertTrue(activity.contains("tryHostDiscovery(active, request, checkNotNull(catalog)"))
        val bridge = activity.substringAfter("private suspend fun tryHostDiscovery(")
            .substringBefore("private suspend fun runLiteRtToolbridge(")
        assertTrue(bridge.contains("requestAutoApproval(active, plan)"))
        assertTrue(bridge.contains("run.markDispatched(plan)"))
        assertTrue(bridge.contains("mcpAgent.executeAuto(active, plan, dispatchState)"))
        assertTrue(bridge.contains("Kein automatischer Wiederholungsversuch"))
    }
}
