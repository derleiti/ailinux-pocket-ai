package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpOnDemandDiscoveryTest {
    private fun run(tools: List<McpTool>) = McpProgressiveBridge.Run(listOf(
        McpProgressiveBridge.Source("s1", "profile", "https://example.test/mcp", "TriForce", tools)
    ))
    private fun tool(name: String, schema: JSONObject = JSONObject().put("type", "object")) =
        McpTool(name, "Tool for $name", schema)

    @Test fun exactCrawlRequestCreatesOnlyApprovedCandidateNotExecution() {
        val schema = JSONObject("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
        val run = run(listOf(tool("crawl", schema)))
        val plan = McpOnDemandDiscovery.propose("check mit mcp crawl tool https://ailinux.me", run)
        assertNotNull(plan)
        assertEquals("crawl", plan!!.tool.name)
        assertEquals("https://ailinux.me", plan.arguments.getString("url"))
        assertEquals(0, run.remoteCalls)
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
        assertTrue(activity.contains("tryHostDiscovery(active, request, checkNotNull(catalog)"))
        val bridge = activity.substringAfter("private suspend fun tryHostDiscovery(")
            .substringBefore("private suspend fun runLiteRtToolbridge(")
        assertTrue(bridge.contains("requestAutoApproval(active, plan)"))
        assertTrue(bridge.contains("run.markDispatched(plan)"))
        assertTrue(bridge.contains("mcpAgent.executeAuto(active, plan)"))
        assertTrue(bridge.contains("Kein automatischer Wiederholungsversuch"))
    }
}
