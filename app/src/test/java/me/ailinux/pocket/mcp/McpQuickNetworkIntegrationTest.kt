package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Real app McpClient and JSON-RPC protocol against a controllable in-memory
 * HTTP transport. Nothing here sends a real OAuth token or contacts production.
 */
class McpQuickNetworkIntegrationTest {
    private class Stub(
        val tool: String = "discord_connection_status",
        val requiredField: String? = null,
        val failingMethod: String? = null,
        val error: Exception? = null,
        val toolResult: JSONObject = JSONObject()
            .put("discord_linked", true).put("approved_servers", 0)
    ) : McpHttp() {
        val methods = mutableListOf<String>()
        override fun call(
            url: String, method: String, body: String?,
            headers: Map<String, String>, contentType: String, allowLocal: Boolean
        ): McpHttpReply {
            assertEquals("POST", method)
            assertEquals("https://discord.ailinux.me/mcp", url)
            val rpc = JSONObject(body!!)
            val rpcMethod = rpc.getString("method")
            methods.add(rpcMethod)
            if (rpcMethod == failingMethod) throw error!!
            val result = when (rpcMethod) {
                "server/discover" -> JSONObject()
                    .put("supportedVersions", JSONArray().put("2026-07-28"))
                    .put("serverInfo", JSONObject().put("name", "auth-discord-mcp"))
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject()
                    .put("name", tool)
                    .put("description", "Account read only")
                    .put("inputSchema", JSONObject()
                        .put("type", "object")
                        .put("properties", if (requiredField == null) JSONObject() else
                            JSONObject().put(requiredField, JSONObject().put("type", "string")))
                        .put("required", if (requiredField == null) JSONArray() else
                            JSONArray().put(requiredField)))))
                "tools/call" -> {
                    assertEquals(tool, rpc.getJSONObject("params").getString("name"))
                    toolResult
                }
                else -> throw IllegalArgumentException("Unexpected RPC method: " + rpcMethod)
            }
            val reply = JSONObject().put("jsonrpc", "2.0")
                .put("id", rpc.getInt("id")).put("result", result)
            return McpHttpReply(200, "application/json", reply.toString(), null, null)
        }
    }

    private fun profile() = McpProfile(
        title = "Test Discord", endpoint = "https://discord.ailinux.me/mcp",
        transport = McpTransport.STREAMABLE_HTTP, auth = McpAuth.OAUTH,
        oauthAccessToken = "test-token-not-real"
    )
    private fun req() = McpQuickActions.Selection.Request(
        McpQuickActions.Target.DISCORD, "discord_connection_status"
    )

    @Test fun liveDiscoveryIsNotAToolCallUntilExplicitApproval() {
        val fake = Stub()
        val client = McpClient(profile(), fake)
        val info = client.inspect()
        val plan = McpQuickActions.validatedProposal(req(), info)
        assertEquals("discord_connection_status", plan.tool.name)
        assertEquals(listOf("server/discover", "tools/list"), fake.methods)

        val declined = false // as if approval dialog returned false
        if (declined) client.callTool(plan.tool.name, plan.args, plan.tool.schema)
        assertEquals(listOf("server/discover", "tools/list"), fake.methods)

        // The actual call is performed only after approval.
        val approved = true
        val actual = if (approved) client.callTool(
            plan.tool.name, plan.args, plan.tool.schema
        ) else JSONObject()
        assertTrue(actual.getBoolean("discord_linked"))
        assertEquals(0, actual.getInt("approved_servers"))
        assertEquals(listOf("server/discover", "tools/list",
            "server/discover", "tools/call"), fake.methods)
    }

    @Test fun changedServerSchemaDeniesQuickCallBeforeDispatch() {
        val fake = Stub(requiredField = "new_mandatory_secret")
        val info = McpClient(profile(), fake).inspect()
        assertThrows(IllegalArgumentException::class.java) {
            McpQuickActions.validatedProposal(req(), info)
        }
        assertEquals(listOf("server/discover", "tools/list"), fake.methods)
    }

    @Test fun missingToolOnServerBlocksWithoutCallingSomethingElse() {
        val fake = Stub(tool = "different_action")
        val info = McpClient(profile(), fake).inspect()
        assertThrows(IllegalStateException::class.java) {
            McpQuickActions.validatedProposal(req(), info)
        }
        assertFalse(fake.methods.contains("tools/call"))
    }

    @Test fun deniedOAuth401Or403NeverDowngradesOrCallsTool() {
        for (code in listOf(401, 403)) {
            val fake = Stub(failingMethod = "tools/list",
                error = McpHttpFailure(code, "Bearer", "Refused"))
            val ex = assertThrows(McpHttpFailure::class.java) {
                McpClient(profile(), fake).inspect()
            }
            assertEquals(code, ex.code)
            assertFalse(fake.methods.contains("initialize"))
            assertFalse(fake.methods.contains("tools/call"))
        }
    }

    @Test fun timeoutDuringDiscoveryCannotBeMisreportedAsSuccess() {
        val fake = Stub(failingMethod = "server/discover",
            error = SocketTimeoutException("timeout"))
        assertThrows(SocketTimeoutException::class.java) {
            McpClient(profile(), fake).inspect()
        }
        assertEquals(listOf("server/discover"), fake.methods)
    }

    @Test fun failureAfterDispatchMustNotAutomaticallyRetry() {
        val fake = Stub(failingMethod = "tools/call",
            error = McpHttpFailure(403, null, "Tool permission denied"))
        assertThrows(McpHttpFailure::class.java) {
            McpClient(profile(), fake)
                .callTool("discord_connection_status", JSONObject())
        }
        assertEquals(listOf("server/discover", "tools/call"), fake.methods)
    }

    @Test fun http200WithMcpErrorIsNotAnActionSuccess() {
        val fake = Stub(toolResult = JSONObject()
            .put("isError", true).put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "read grant required"))))
        val result = McpClient(profile(), fake)
            .callTool("discord_connection_status", JSONObject())
        val receipt = McpQuickActions.displayReceipt(
            McpTool("discord_connection_status", "read", JSONObject()), result)
        assertTrue(receipt.contains("SERVER MELDET FEHLER"))
        assertTrue(receipt.contains("read grant required"))
    }
}
