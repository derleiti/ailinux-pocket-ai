package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Wire-level fake HTTP transport: deterministic JSON-RPC handshake, tool
 * discovery and call tests, including 2026 modern headers and legacy fallback.
 * Physical HttpURLConnection + SSE is tested in McpHttpNetworkTest.
 */
class McpStreamableIntegrationTest {
    private class Stub(private val modern: Boolean = false) : McpHttp() {
        val calls = mutableListOf<Pair<JSONObject, Map<String, String>>>()
        var sessionId: String? = null
        override fun call(
            url: String, method: String, body: String?,
            headers: Map<String, String>, contentType: String, allowLocal: Boolean
        ): McpHttpReply {
            require(method == "POST")
            val msg = JSONObject(body!!)
            val name = msg.optString("method")
            calls.add(msg to headers)
            if (!modern && name == "server/discover") {
                val error = JSONObject().put("jsonrpc", "2.0").put("id", msg.getInt("id"))
                    .put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
                throw McpHttpFailure(404, null, "Method missing", error.toString())
            }
            if (name == "notifications/initialized") return McpHttpReply(202, "", "", sessionId, null)
            val result = when (name) {
                "server/discover" -> JSONObject()
                    .put("protocolVersion", "2026-07-28")
                    .put("serverInfo", JSONObject().put("name", "Mock-MCP Modern"))
                    .put("instructions", "Use ping for test connectivity. No action without consent.")
                "initialize" -> JSONObject().put("protocolVersion", "2025-11-25")
                    .put("serverInfo", JSONObject().put("name", "Mock-MCP Legacy"))
                    .put("instructions", "Legacy tip: ping checks availability.")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject()
                    .put("name", "ping").put("description", "Ping")
                    .put("inputSchema", JSONObject()
                        .put("type", "object").put("properties", JSONObject()
                            .put("region", JSONObject().put("type", "string")
                                .put("x-mcp-header", "Region"))))))
                "tools/call" -> JSONObject().put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "pong")))
                else -> throw IllegalArgumentException("Unknown fake method " + name)
            }
            return McpHttpReply(200, "application/json",
                JSONObject().put("jsonrpc", "2.0").put("id", msg.getInt("id"))
                    .put("result", result).toString(), sessionId, null)
        }
    }

    private fun profile(auth: McpAuth = McpAuth.NONE) = McpProfile(
        title = "Test", endpoint = "https://mcp.example.test/mcp",
        auth = auth, bearer = "abc-secret", apiKey = "key-secret",
        username = "alice", password = "pass", apiKeyName = "X-Api-Key"
    )

    @Test fun initializesLegacyAfterRecognizedModernMethodMissing() {
        val transport = Stub()
        val info = McpClient(profile(), transport).inspect()
        assertEquals("Mock-MCP Legacy", info.title)
        assertEquals("2025-11-25", info.protocol)
        assertEquals("Legacy tip: ping checks availability.", info.instructions)
        assertEquals("ping", info.tools.single().name)
        val methods = transport.calls.map { it.first.getString("method") }
        assertEquals(listOf("server/discover", "initialize", "notifications/initialized",
            "tools/list"), methods)
    }

    @Test fun modernMcpUsesStatelessPerRequestMetadataAndHeaders() {
        val transport = Stub(true)
        val info = McpClient(profile(), transport).inspect()
        assertEquals("Mock-MCP Modern", info.title)
        assertEquals("2026-07-28", info.protocol)
        assertEquals("Use ping for test connectivity. No action without consent.",
            info.instructions)
        val headers = transport.calls[1].second
        assertEquals("tools/list", headers["Mcp-Method"])
        assertEquals("2026-07-28", headers["MCP-Protocol-Version"])
        assertNull(headers["Mcp-Session-Id"])
        val meta = transport.calls[1].first.getJSONObject("params").getJSONObject("_meta")
        assertEquals("2026-07-28",
            meta.getString("io.modelcontextprotocol/protocolVersion"))
        assertEquals(2, transport.calls.size) // discover, tools/list; no handshake
    }

    @Test fun callsToolWithModernNameAndAnnotatedParameterHeaders() {
        val transport = Stub(true)
        val schema = JSONObject().put("properties", JSONObject()
            .put("region", JSONObject().put("type", "string").put("x-mcp-header", "Region")))
        val arguments = JSONObject().put("region", "Hello, Welt 🌍")
        val result = McpClient(profile(), transport).callTool("ping", arguments, schema)
        assertEquals("pong", result.getJSONArray("content").getJSONObject(0).getString("text"))
        val headers = transport.calls.last().second
        assertEquals("tools/call", headers["Mcp-Method"])
        assertEquals("ping", headers["Mcp-Name"])
        assertTrue(headers["Mcp-Param-Region"]!!.startsWith("=?base64?"))
        assertEquals("Hello, Welt 🌍", String(java.util.Base64.getDecoder().decode(
            headers["Mcp-Param-Region"]!!.removePrefix("=?base64?").removeSuffix("?=")
        ), Charsets.UTF_8))
    }

    @Test fun supportsBasicBearerAndApiKeyAuthenticationHeaders() {
        val cases = listOf(
            McpAuth.BEARER to ("Authorization" to "Bearer abc-secret"),
            McpAuth.BASIC to ("Authorization" to "Basic YWxpY2U6cGFzcw=="),
            McpAuth.API_KEY to ("X-Api-Key" to "key-secret")
        )
        for ((mode, expected) in cases) {
            val transport = Stub(true)
            McpClient(profile(mode), transport).inspect()
            assertEquals(expected.second, transport.calls.first().second[expected.first])
        }
        val noAuth = Stub(true)
        McpClient(profile(), noAuth).inspect()
        assertNull(noAuth.calls.first().second["Authorization"])
    }

    @Test fun oauth401DoesNotDowngradeToLegacy() {
        val transport = object : McpHttp() {
            override fun call(url: String, method: String, body: String?,
                              headers: Map<String, String>, contentType: String,
                              allowLocal: Boolean): McpHttpReply {
                throw McpHttpFailure(401, "Bearer", "Login required")
            }
        }
        val error = assertThrows(McpHttpFailure::class.java) {
            McpClient(profile(), transport).inspect()
        }
        assertEquals(401, error.code)
    }

    @Test fun toolHttpErrorIsNotAutomaticallyReplayedViaLegacyTransport() {
        val methods = mutableListOf<String>()
        val fake = object : McpHttp() {
            override fun call(
                url: String, method: String, body: String?,
                headers: Map<String, String>, contentType: String,
                allowLocal: Boolean
            ): McpHttpReply {
                val rpcMethod = JSONObject(body!!).getString("method")
                methods.add(rpcMethod)
                if (rpcMethod == "server/discover") {
                    return McpHttpReply(200, "application/json",
                        JSONObject().put("jsonrpc", "2.0")
                            .put("id", JSONObject(body).getInt("id"))
                            .put("result", JSONObject()
                                .put("supportedVersions", org.json.JSONArray().put("2026-07-28"))
                                .put("capabilities", JSONObject().put("tools", JSONObject())))
                            .toString(), null, null)
                }
                throw McpHttpFailure(404, null, "Result lost after server dispatch")
            }
        }
        assertThrows(McpHttpFailure::class.java) {
            McpClient(profile(), fake).callTool("some_action", JSONObject())
        }
        assertEquals(listOf("server/discover", "tools/call"), methods)
    }

    @Test fun rejectsUnsafeOrDuplicateCustomHeaders() {
        val bad = JSONObject().put("properties", JSONObject()
            .put("x", JSONObject().put("type", "string").put("x-mcp-header", "Authorization")))
        // Reserved headers cannot be used as nested Mcp-Param-* overrides.
        assertEquals("x", McpClient.parameterHeaderAnnotations(bad)["Authorization"]?.single())
        val duplicate = JSONObject().put("properties", JSONObject()
            .put("a", JSONObject().put("type", "string").put("x-mcp-header", "Q"))
            .put("b", JSONObject().put("type", "boolean").put("x-mcp-header", "q")))
        assertThrows(IllegalArgumentException::class.java) {
            McpClient.parameterHeaderAnnotations(duplicate)
        }
    }
}
