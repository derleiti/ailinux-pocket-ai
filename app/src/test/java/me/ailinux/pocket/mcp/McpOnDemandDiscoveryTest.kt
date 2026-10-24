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
        assertTrue(bridge.contains("mcpAgent.executeAuto(active, plan)"))
        assertTrue(bridge.contains("Kein automatischer Wiederholungsversuch"))
    }
}
