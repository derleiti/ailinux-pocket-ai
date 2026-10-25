package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** RC13 Pixel: native LiteRT returns malformed arguments after SEARCH + DETAILS. */
class McpDetailedHostFallbackTest {
    private fun tool(name: String, readOnly: Boolean = true,
                     schema: JSONObject = JSONObject().put("type", "object")) =
        McpTool(name, "Read-only status of " + name, schema,
            JSONObject().put("readOnlyHint", readOnly))

    private fun run(status: McpTool = tool("status")): McpProgressiveBridge.Run {
        val workspace = (0 until 85).map {
            tool("workspace_item_" + it)
        } + status
        val dev = (0 until 83).map {
            tool("dev_item_" + it)
        } + tool("status")
        val discord = listOf(tool("discord_connection_status"),
            tool("current_time"), tool("discord_voice_status"))
        // 86 + 84 + 3 = 173 real tools, well beyond the model's 3 meta-tools.
        return McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1", "workspace-profile",
                "https://api.ailinux.me/v1/mcp", "AILinuX TriForce Workspace", workspace),
            McpProgressiveBridge.Source("s2", "dev-profile",
                "https://api.ailinux.me/v1/dev", "AILinuX TriForce DEV MCP", dev),
            McpProgressiveBridge.Source("s3", "discord-profile",
                "https://discord.ailinux.me/mcp", "AILinux Discord MCP", discord)
        ))
    }

    @Test fun alreadyDetailedWorkspaceStatusSurvivesMalformedNativeMetaCall() {
        val state = run()
        val request = "Brumo, prüfe mit MCP den Workspace-Systemstatus"
        assertEquals(173, state.totalTools)
        state.search("status", originalRequest = request)
        assertFalse(state.details("s1/status", originalRequest = request)
            .optBoolean("isError"))
        assertEquals(1, state.detailedCount)
        val plan = McpOnDemandDiscovery.proposeAfterDetails(request, state)
        assertNotNull("Must preserve exact previously detailed local tool", plan)
        assertEquals("s1/status", plan!!.toolId)
        assertEquals("workspace-profile", plan.source.profileId)
        assertEquals("{}", plan.arguments.toString())
        assertTrue(plan.hostFallback)
        assertEquals("Host is only planning, not dispatching", 0, state.remoteCalls)
    }

    @Test fun duplicateToolWithoutUserSelectedSourceStaysBlocked() {
        val state = run()
        state.search("status")
        state.details("s1/status")
        var reason: McpOnDemandDiscovery.Rejection? = null
        assertNull(McpOnDemandDiscovery.proposeAfterDetails(
            "Prüfe den TriForce MCP Status", state) { reason = it })
        assertEquals(McpOnDemandDiscovery.Rejection.AMBIGUOUS_TOOL, reason)
        assertEquals(0, state.remoteCalls)
    }

    @Test fun detailedWrongSourceCannotBeSubstitutedAfterRequestChanges() {
        val state = run()
        state.search("status")
        state.details("s2/status")
        assertNull(McpOnDemandDiscovery.proposeAfterDetails(
            "Prüfe den Workspace MCP Status", state))
        assertEquals(0, state.remoteCalls)
    }

    @Test fun extraDetailedToolOnOtherServerCannotRedirectUserIntent() {
        val state = run()
        state.search("discord", originalRequest = "Discord Verbindung prüfen")
        state.details("s3/discord_connection_status",
            originalRequest = "Discord Verbindung prüfen")
        assertNull(McpOnDemandDiscovery.proposeAfterDetails(
            "Prüfe den Workspace MCP Status", state))
        assertEquals(0, state.remoteCalls)
    }

    @Test fun serverReadOnlyAnnotationFalseRemainsBlockedAfterDetails() {
        val state = run(tool("status", readOnly = false))
        val request = "Prüfe Workspace MCP Status"
        state.search("status", originalRequest = request)
        state.details("s1/status", originalRequest = request)
        var reason: McpOnDemandDiscovery.Rejection? = null
        assertNull(McpOnDemandDiscovery.proposeAfterDetails(request, state) {
            reason = it
        })
        assertEquals(McpOnDemandDiscovery.Rejection.UNVERIFIED_READONLY, reason)
        assertEquals(0, state.remoteCalls)
    }

    @Test fun requiredNonUrlArgumentsNeverGetInventedAfterDetails() {
        val schema = JSONObject().put("type","object")
            .put("properties", JSONObject().put("account", JSONObject()
                .put("type","string")))
            .put("required", JSONArray().put("account"))
        val state = run(tool("status", schema = schema))
        val request = "Prüfe Workspace MCP Status"
        state.search("status", originalRequest = request)
        state.details("s1/status", originalRequest = request)
        var reason: McpOnDemandDiscovery.Rejection? = null
        assertNull(McpOnDemandDiscovery.proposeAfterDetails(request, state) {
            reason = it
        })
        assertEquals(McpOnDemandDiscovery.Rejection.ARGUMENTS_UNAVAILABLE, reason)
        assertEquals(0, state.remoteCalls)
    }

    @Test fun explanationOnlyQuestionNeverUsesDetailedCatalog() {
        val state = run()
        state.search("status")
        state.details("s1/status")
        assertNull(McpOnDemandDiscovery.proposeAfterDetails(
            "Erkläre mir, was der MCP Systemstatus bedeutet", state))
        assertEquals(0, state.remoteCalls)
    }

    @Test fun oldRequestCannotUsePreviouslyDispatchedTool() {
        val state = run()
        val request = "Prüfe Workspace MCP Status"
        state.search("status", originalRequest = request)
        state.details("s1/status", originalRequest = request)
        val plan = state.prepareCall("s1/status", "{}", request)
        state.markDispatched(plan)
        assertNull(McpOnDemandDiscovery.proposeAfterDetails(request, state))
        assertEquals(1, state.remoteCalls)
    }
}
