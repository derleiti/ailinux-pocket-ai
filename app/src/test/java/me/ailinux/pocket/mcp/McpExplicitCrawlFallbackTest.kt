package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Real TriForce "crawl" creates a persistent server-side crawler job.
 * It must never be misrepresented as a read-only tool, even when
 * server-supplied annotations claim readOnlyHint=true.
 */
class McpExplicitCrawlFallbackTest {
    private fun crawl(hint: Any? = null): McpTool {
        val annotations = JSONObject()
        if (hint != null) annotations.put("readOnlyHint", hint)
        return McpTool("crawl", "Starts a crawl job; may store results",
            JSONObject().put("type","object")
                .put("properties", JSONObject().put("url", JSONObject().put("type","string")))
                .put("required", JSONArray().put("url")),
            annotations)
    }
    private fun catalog(vararg tools: McpTool) = McpProgressiveBridge.Run(listOf(
        McpProgressiveBridge.Source("s1", "profile",
            "https://api.ailinux.me/v1/mcp",
            "AILinux TriForce DEV MCP", tools.toList())
    ))
    @Test fun explicitCrawlCanOnlyProposeRiskLabelledUserConfirmedJob() {
        for (annotation in listOf(null, false, true)) {
            val run = catalog(crawl(annotation))
            val plan = McpOnDemandDiscovery.propose(
                "über triforce dev mcp https://ailinux.me crawlen per mcp tool", run)
            assertNotNull("Crawl should propose a *manual* reviewed job", plan)
            assertEquals("crawl", plan!!.tool.name)
            assertTrue(plan.hostFallback)
            assertTrue("Crawl must never be called read-only",plan.hostWriteIntent)
            assertEquals("https://ailinux.me", plan.arguments.getString("url"))
            assertTrue(McpOnDemandDiscovery.isVerifiedExplicitCrawlPlan(plan))
            assertEquals(0, run.remoteCalls)
        }
    }
    @Test fun readOnlyNameSpoofCannotSkipExplicitWriteConsent() {
        val run = catalog(crawl(true))
        val plan = McpOnDemandDiscovery.propose("MCP crawl https://ailinux.me", run)!!
        assertFalse(McpOnDemandDiscovery.isVerifiedReadOnlyHostPlan(plan))
        assertTrue(plan.hostWriteIntent)
        assertFalse(McpOnDemandDiscovery.isVerifiedExplicitCrawlPlan(
            plan.copy(hostFallback = false)))
        assertFalse(McpOnDemandDiscovery.isVerifiedExplicitCrawlPlan(
            plan.copy(tool = McpTool("delete_files", "dangerous", plan.tool.schema))))
    }
    @Test fun missingUnsafeAndDuplicatedUrlsNeverCreateCrawlJob() {
        val run = catalog(crawl())
        for (prompt in listOf(
            "crawl httpsailinux.me",
            "crawl http://ailinux.me",
            "Erkläre wie man https://ailinux.me crawlt",
            "crawl https://ailinux.me und https://example.com",
            "crawl https://root:secret@ailinux.me",
            "crawl https://localhost",
            "crawl https://127.0.0.1"
        )) {
            assertNull("Must reject: " + prompt, McpOnDemandDiscovery.propose(prompt, run))
        }
        assertEquals(0,run.remoteCalls)
    }
    @Test fun selectedToolReadOnlyFallbackCannotStartCrawlerJob() {
        val selected = McpAgentSession("profile",
            "https://api.ailinux.me/v1/mcp", "AILinux TriForce DEV MCP",
            listOf(crawl(true)))
        assertNull(McpOnDemandDiscovery.proposeSelected(
            "Crawl https://ailinux.me over MCP", selected))
    }

    @Test fun unsafeRemoteToolsOtherThanExplicitCrawlRemainBlocked() {
        val run = catalog(McpTool("status", "system status", JSONObject().put("type","object"),
            JSONObject()), McpTool("mail_send", "send messages",
            JSONObject().put("type","object"), JSONObject().put("readOnlyHint",true)))
        assertNull(McpOnDemandDiscovery.propose("Prüfe TriForce DEV MCP status",run))
        assertNull(McpOnDemandDiscovery.propose("MCP mail_send an user",run))
    }
}
