package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Regression from Android 17 / Gemma E2B Google Tensor: first status call had a
 * real server receipt, next identical request returned model prose with NO
 * native FunctionCall despite the unchanged selected MCP status tool.
 *
 * This only validates the safe host *proposal*; physical RC11 QA still needed.
 */
class McpSelectedHostFallbackTest {
    private fun tool(
        name: String = "status",
        schema: JSONObject = JSONObject()
            .put("type", "object").put("properties", JSONObject())
            .put("additionalProperties", false),
        annotations: JSONObject = JSONObject().put("readOnlyHint", true)
    ) = McpTool(name, "Read-only $name from authorized catalog", schema, annotations)

    private fun session(vararg tools: McpTool) = McpAgentSession(
        profileId = "real-credential-bound-profile",
        endpoint = "https://example.test/v1/mcp",
        serverName = "TriForce",
        tools = tools.toList()
    )

    @Test fun repeatedStatusRequestCanProposeLiveSelectedToolWithoutDispatch() {
        val active = session(tool())
        val request = "Brumo, frage den TriForce-Systemstatus erneut mit dem MCP-Werkzeug status ab."
        val proposed = McpOnDemandDiscovery.proposeSelected(request, active)
        assertNotNull("Host should propose safe status when LiteRT omits its native call", proposed)
        assertEquals("status", proposed!!.tool.name)
        assertEquals("{}", proposed.args.toString())
        assertSame(active.tools.single(), proposed.tool)
    }

    @Test fun explanatorySentenceWithDelayedVerbCannotTriggerMcpAction() {
        val request = "Kannst du mir den TriForce-Systemstatus erklären?"
        assertTrue(McpRequestRouting.isExplanationOnly(request))
        assertFalse(McpRequestRouting.expectsToolEvidence(request))
        assertNull(McpOnDemandDiscovery.proposeSelected(request, session(tool())))
    }

    @Test fun alternativesNeverTriggerOneOfTwoExplicitToolNames() {
        val selected = session(tool("status"), tool("device_info"))
        assertNull(McpOnDemandDiscovery.proposeSelected(
            "Nutze MCP Tool status oder Tool device_info", selected))
        assertNull(McpOnDemandDiscovery.proposeSelected(
            "MCP tool status, alternativ Tool device_info", selected))
        assertNotNull(McpOnDemandDiscovery.proposeSelected(
            "Bitte nutze das MCP Tool status", selected))
    }

    @Test fun explanationOnlyAndUnrelatedPromptsNeverProposeAnExternalAction() {
        val active = session(tool())
        assertNull(McpOnDemandDiscovery.proposeSelected(
            "Brumo, erklär mir, was MCP-Status bedeutet", active))
        assertNull(McpOnDemandDiscovery.proposeSelected("Guten Morgen", active))
        assertNull(McpOnDemandDiscovery.proposeSelected("Welche Tools gibt es?", active))
        assertNull(McpOnDemandDiscovery.proposeSelected("Fasse diesen Text zusammen", active))
    }

    @Test fun unknownOrWriteEnabledToolsNeverReceiveHostProposals() {
        val request = "Brumo, rufe mit MCP den TriForce-Systemstatus ab"
        assertNull(McpOnDemandDiscovery.proposeSelected(request, session(tool("status",
            annotations = JSONObject().put("readOnlyHint", false)))))
        assertNull(McpOnDemandDiscovery.proposeSelected(request, session(tool("status",
            annotations = JSONObject()))))
        assertNull(McpOnDemandDiscovery.proposeSelected("MCP tool delete",
            session(tool("delete"))))
        assertNull(McpOnDemandDiscovery.proposeSelected(request,
            session(tool("status"), tool("status"))))
    }

    @Test fun selectedExecutorNeverElevatesCrawlThroughReadOnlyAnnotation() {
        assertTrue(McpOnDemandDiscovery.isAuditedReadOnlyTool(tool("status")))
        assertFalse(McpOnDemandDiscovery.isAuditedReadOnlyTool(tool("crawl")))
        assertFalse(McpOnDemandDiscovery.isAuditedReadOnlyTool(tool("delete_files")))
        assertFalse(McpOnDemandDiscovery.isAuditedReadOnlyTool(tool("status",
            annotations = JSONObject().put("readOnlyHint", false))))
        assertFalse(McpOnDemandDiscovery.isAuditedReadOnlyTool(tool("status",
            annotations = JSONObject())))

        val agent = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        val execute = agent.substringAfter("    suspend fun execute(")
            .substringBefore("    private fun error(")
        assertTrue(execute.contains("McpOnDemandDiscovery.isAuditedReadOnlyTool(current)"))
        assertFalse(execute.contains("\"crawl\""))
    }

    @Test fun unsafeMandatoryArgumentsCannotBeGuessed() {
        val required = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("target", JSONObject().put("type","string")))
            .put("required", JSONArray().put("target"))
        assertNull(McpOnDemandDiscovery.proposeSelected(
            "Brumo, rufe den TriForce-Systemstatus ab", session(tool("status", required))))
    }

    @Test fun progressiveAgentNeverUsesTheManuallySelectedFallback() {
        val active = session(tool()).copy(progressiveSources = listOf(
            McpProgressiveBridge.Source("s1", "real-credential-bound-profile",
                "https://example.test/v1/mcp", "TriForce", listOf(tool()))
        ))
        assertNull(McpOnDemandDiscovery.proposeSelected(
            "Brumo, rufe den MCP TriForce status ab", active))
    }

    @Test fun fallbackShowsOriginAndRequiresRealApprovalAndSingleCall() {
        val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val source = activity.substringAfter("private suspend fun trySelectedHostFallback(")
            .substringBefore("private suspend fun runLiteRtToolbridge(")
        assertTrue(source.contains("McpOnDemandDiscovery.proposeSelected(request, active)"))
        assertTrue(source.contains("requestApproval(active, proposal)"))
        assertTrue(source.contains("hostFallback = true"))
        assertTrue(source.contains("App-gestützter Werkzeugvorschlag"))
        assertTrue(source.contains("Kein automatischer Wiederholungsversuch"))
        assertTrue(source.contains("Keine native Modell-Toolfortsetzung behauptet"))
        assertEquals(1, Regex("mcpAgent\\.execute\\(").findAll(source).count())
        assertTrue(activity.contains("!progressive &&"))
        assertTrue(activity.contains("trySelectedHostFallback(active, request, answer, answerIndex)"))
        val agent = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        assertTrue(agent.contains("McpOnDemandDiscovery.isAuditedReadOnlyTool(current)"))
        assertTrue(agent.contains("McpToolSchema.equivalent(current.annotations, proposal.tool.annotations)"))
    }
}
