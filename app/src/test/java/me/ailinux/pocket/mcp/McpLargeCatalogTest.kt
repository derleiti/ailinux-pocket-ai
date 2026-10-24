package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** No production server or OAuth account used. */
class McpLargeCatalogTest {
    private fun profile() = McpProfile(
        title = "Paged", endpoint = "https://example.test/mcp",
        transport = McpTransport.STREAMABLE_HTTP, auth = McpAuth.NONE
    )
    private class PagingFake(
        val pageSize: Int,
        val pages: Int,
        val cycle: Boolean = false
    ) : McpHttp() {
        val requested = mutableListOf<String?>()
        override fun call(url: String, method: String, body: String?,
                          headers: Map<String, String>, contentType: String,
                          allowLocal: Boolean): McpHttpReply {
            val rpc = JSONObject(checkNotNull(body))
            val methodName = rpc.getString("method")
            val result = when (methodName) {
                "server/discover" -> JSONObject()
                    .put("protocolVersion", "2026-07-28")
                    .put("serverInfo", JSONObject().put("name", "paged"))
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                "tools/list" -> {
                    val params = rpc.optJSONObject("params") ?: JSONObject()
                    val cursor = params.optString("cursor").ifBlank { null }
                    requested.add(cursor)
                    val page = cursor?.removePrefix("p")?.toIntOrNull() ?: 0
                    val tools = JSONArray()
                    for (i in 0 until pageSize) {
                        tools.put(JSONObject()
                            .put("name", "tool_" + (page * pageSize + i))
                            .put("description", "Read-only status")
                            .put("annotations", JSONObject().put("readOnlyHint", true))
                            .put("inputSchema", JSONObject().put("type", "object")))
                    }
                    JSONObject().put("tools", tools).apply {
                        if (page < pages - 1) put("nextCursor",
                            if (cycle) "p0" else "p" + (page + 1))
                    }
                }
                else -> error("Unexpected RPC $methodName")
            }
            val response = JSONObject().put("jsonrpc", "2.0").put("id", rpc.getInt("id"))
                .put("result", result)
            return McpHttpReply(200, "application/json", response.toString(), null, null)
        }
    }

    @Test fun severalThousandToolsCanBePagedWithoutPromptExpansion() {
        val fake = PagingFake(320, 5)
        val discovered = McpClient(profile(), fake).inspect()
        assertEquals(1600, discovered.tools.size)
        assertEquals(5, fake.requested.size)
        assertEquals(listOf(null, "p1", "p2", "p3", "p4"), fake.requested)
        assertTrue(discovered.tools.first().declaresReadOnly)
        val run = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source(
                "s1", "local", "https://example.test/mcp",
                "Paged", discovered.tools)
        ))
        assertEquals(1600, run.totalTools)
        val search = run.search("tool_1440", 3)
        val payload = JSONObject(search.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(1600, payload.getInt("total_tools"))
        assertTrue(payload.getJSONArray("matches").length() <= 3)
        assertEquals("s1/tool_1440",
            payload.getJSONArray("matches").getJSONObject(0).getString("tool_id"))
    }

    @Test fun loopingPaginationIsRejectedBeforeAdvertisingIncompleteCatalogue() {
        val fake = PagingFake(3, 8, cycle = true)
        assertThrows(IllegalArgumentException::class.java) {
            McpClient(profile(), fake).inspect()
        }
        assertEquals(listOf(null, "p0"), fake.requested)
    }

    @Test fun overCapacityCatalogueIsRejectedNotSilentlyCutOff() {
        val fake = PagingFake(1001, 4)
        assertThrows(IllegalArgumentException::class.java) {
            McpClient(profile(), fake).inspect()
        }
    }
}
