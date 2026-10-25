package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Android 17 RC12 reproduction: user requested Workspace status,
 * but after 172-tool search Gemma E2B requested DETAILS for s3/status
 * on the DEV server. No request-driven checks existed in the host.
 */
class McpRequestedServerAffinityTest {
    private fun status() = McpTool("status", "Full system status",
        JSONObject().put("type", "object").put("properties", JSONObject()),
        JSONObject().put("readOnlyHint", true))

    private fun run(): McpProgressiveBridge.Run {
        val workspaceTools = (0 until 90).map {
            McpTool("ws_tool_$it", "Workspace tool", JSONObject().put("type", "object"))
        } + status()
        val discordTools = (0 until 80).map {
            McpTool("discord_tool_$it", "Discord tool", JSONObject().put("type", "object"))
        }
        val devTools = listOf(status())
        val out = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source("s1","workspace", "https://api.ailinux.me/v1/mcp",
                "AILinux TriForce Workspace",workspaceTools),
            McpProgressiveBridge.Source("s2","discord", "https://discord.ailinux.me/mcp",
                "AILinux Discord MCP",discordTools),
            McpProgressiveBridge.Source("s3","dev", "https://dev.ailinux.me/mcp",
                "AILinuX Triforce DEV MCP",devTools)
        ))
        assertEquals(172,out.totalTools)
        return out
    }

    private fun matchedIds(run: McpProgressiveBridge.Run, request: String): List<String> {
        val response = run.search("status", 6, request)
        val data = JSONObject(response.getJSONArray("content")
            .getJSONObject(0).getString("text"))
        val hits = data.getJSONArray("matches")
        val ids = (0 until hits.length()).map {
            hits.getJSONObject(it).getString("tool_id")
        }
        return ids
    }

    @Test fun workspaceRequestPrioritizesWorkspaceEvenWhenModelSearchQueryIsJustStatus() {
        val run = run()
        val request = "Brumo, pruefe per AUTO-MCP den TriForce Workspace Systemstatus"
        val matches = matchedIds(run, request)
        assertEquals("s1/status", matches.first())
        assertFalse(matches.contains("s3/status"))
        assertEquals(0,run.remoteCalls)
    }

    @Test fun rejectsWrongDevDetailsBeforeExposingSchema() {
        val run = run()
        val request = "Brumo, pruefe den TriForce Workspace Systemstatus"
        matchedIds(run, request)
        val error = assertThrows(IllegalArgumentException::class.java) {
            run.details("s3/status", request)
        }
        assertTrue(error.message.orEmpty().contains("Workspace"))
        assertEquals("s1/status", JSONObject(run.details("s1/status", request)
            .getJSONArray("content").getJSONObject(0).getString("text"))
            .getString("tool_id"))
    }

    @Test fun cannotDispatchDevAfterCorrectWorkspaceDetails() {
        val run = run()
        val request = "Workspace status via MCP"
        matchedIds(run, request)
        run.details("s1/status", request)
        assertThrows(IllegalArgumentException::class.java) {
            run.prepareCall("s3/status", "{}", request)
        }
        assertEquals("s1/status",run.prepareCall("s1/status","{}",request).toolId)
        assertEquals(0,run.remoteCalls)
    }

    @Test fun explicitlyRequestedDevCanAccessDevButNotWorkspace() {
        val run = run()
        val request = "Pruefe das Status-Tool auf dem TriForce DEV MCP-Server"
        assertEquals("s3/status",matchedIds(run,request).first())
        assertThrows(IllegalArgumentException::class.java) {
            run.details("s1/status",request)
        }
        run.details("s3/status",request)
        assertEquals("s3/status",run.prepareCall("s3/status","{}",request).toolId)
    }

    @Test fun hostFallbackMayPlanWorkspaceReadOnlyButCannotAutoDispatch() {
        val state = run()
        val request = "Brumo, prüfe per AUTO-MCP den TriForce Workspace Systemstatus"
        val proposal = McpOnDemandDiscovery.propose(request, state)
        assertNotNull("Host must use the explicitly named Workspace source", proposal)
        assertEquals("s1/status", proposal!!.toolId)
        assertTrue(proposal.hostFallback)
        assertEquals("{}", proposal.arguments.toString())
        assertEquals(0, state.remoteCalls)
    }

    @Test fun conflictingSourceHintsDoNotAuthorizeEitherServer() {
        val run = run()
        val request = "Vergleiche Status von DEV und Workspace"
        assertThrows(IllegalArgumentException::class.java) {
            matchedIds(run,request)
        }
        assertEquals(0,run.remoteCalls)
    }
}
