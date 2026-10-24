package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

data class McpTool(val name: String, val description: String, val schema: JSONObject)
data class McpServerInfo(val title: String, val protocol: String, val tools: List<McpTool>)
class McpRpcFailure(val code: Int, method: String, detail: String) :
    IOException("MCP " + method + " (" + code + "): " + detail.take(250))

/**
 * MCP Streamable HTTP 2026-07-28 (stateless) and 2025-11-25 (handshake).
 * No silent legacy HTTP+SSE GET fallback, arbitrary redirects, or client
 * sampling. Every server tool call is initiated by the explicit UI.
 */
class McpClient(
    private val profile: McpProfile,
    private val http: McpHttp = McpHttp()
) {
    private val id = AtomicInteger(100)
    private var modern = false
    private var session: String? = null
    private var version = "2025-11-25"
    private val headers: Map<String, String>
        get() = McpAuthorizationHeader.forProfile(profile) +
            mapOf("MCP-Protocol-Version" to version) +
            (if (modern) emptyMap() else session?.let { mapOf("Mcp-Session-Id" to it) }
                ?: emptyMap())

    private fun invoke(method: String, rawParams: JSONObject? = null,
                       extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val requestId = id.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", requestId).put("method", method)
        val params = rawParams ?: JSONObject()
        if (modern) {
            params.put("_meta", JSONObject()
                .put("io.modelcontextprotocol/protocolVersion", version)
                .put("io.modelcontextprotocol/clientInfo", JSONObject()
                    .put("name", "AILinux Pocket AI").put("version", "1.0.0-rc.12"))
                .put("io.modelcontextprotocol/clientCapabilities", JSONObject()))
        }
        if (modern || rawParams != null) body.put("params", params)
        val modernHeaders = if (modern) {
            val name = if (method == "tools/call" || method == "prompts/get")
                params.optString("name") else if (method == "resources/read")
                params.optString("uri") else ""
            mapOf("Mcp-Method" to method) +
                (if (name.isNotBlank()) mapOf("Mcp-Name" to encodeHeaderValue(name)) else emptyMap())
        } else emptyMap()
        val reply = http.call(profile.endpoint, "POST", body.toString(),
            headers + modernHeaders + extraHeaders, allowLocal = true)
        if (!modern) session = reply.sessionId ?: session
        if (reply.body.isBlank()) throw IOException("MCP-Server hat keine Antwort geliefert")
        val response = JSONObject(reply.body)
        require(response.optString("jsonrpc") == "2.0" && response.optInt("id", -1) == requestId) {
            "Unerwartete MCP-JSON-RPC-Antwort"
        }
        if (response.has("error")) {
            val error = response.optJSONObject("error")
            throw McpRpcFailure(error?.optInt("code", -32000) ?: -32000,
                method, error?.optString("message") ?: "Serverfehler")
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private fun notifyInitialized() {
        val message = JSONObject().put("jsonrpc", "2.0")
            .put("method", "notifications/initialized")
        val reply = http.call(profile.endpoint, "POST", message.toString(), headers, allowLocal = true)
        session = reply.sessionId ?: session
        if (reply.status !in listOf(200, 202, 204)) throw IOException("MCP-Handshake fehlgeschlagen")
    }

    /**
     * Probe server/discover first (new stateless era). Only fall back on a
     * recognised no-method/no-era response. 401/403 or malformed requests
     * are NOT silently treated as legacy servers.
     */
    private fun init(): JSONObject {
        modern = true
        version = "2026-07-28"
        session = null
        try {
            return invoke("server/discover")
        } catch (error: McpRpcFailure) {
            if (error.code != -32601 && error.code != -32022) throw error
        } catch (error: McpHttpFailure) {
            if (error.code !in setOf(400, 404)) throw error
            if (error.responseBody.isNotBlank()) {
                val err = runCatching {
                    JSONObject(error.responseBody).optJSONObject("error")
                }.getOrNull()
                val code = err?.optInt("code", 0) ?: 0
                // 2026-era header mismatch and generic validation errors
                // are genuine failures, not signals to downgrade.
                if (code != 0 && code !in setOf(-32601, -32022)) throw error
            }
        }
        modern = false
        version = "2025-11-25"
        val result = invoke("initialize", JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject()
                .put("name", "AILinux Pocket AI").put("version", "1.0.0-rc.12")))
        version = result.optString("protocolVersion", version)
        require(version in setOf("2025-11-25", "2025-06-18", "2025-03-26")) {
            "Nicht unterstützte alte MCP-Version: " + version
        }
        notifyInitialized()
        return result
    }

    fun inspect(): McpServerInfo {
        if (profile.transport == McpTransport.LEGACY_SSE) {
            return McpLegacySseClient(profile).use { it.open(); it.inspect() }
        }
        // AUTO selects 2026/2025 Streamable HTTP for /mcp, whereas the
        // external McpConnection dispatcher recognises conventional /sse.
        // Do NOT downgrade on 400/404/405: a successful legacy inspection
        // would otherwise advertise tools the execution transport cannot call.
        return inspectModern()
    }

    private fun inspectModern(): McpServerInfo {
        val hello = init()
        val name = hello.optJSONObject("serverInfo")?.optString("name")
            ?.take(100)?.ifBlank { profile.title } ?: profile.title
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(10) {
            val args = JSONObject()
            if (cursor != null) args.put("cursor", cursor)
            val result = invoke("tools/list", args)
            val items = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until items.length()) {
                if (tools.size >= 250) break
                val tool = items.optJSONObject(i) ?: continue
                val toolName = tool.optString("name")
                if (toolName.isNotBlank() && toolName.length <= 200) {
                    val schema = tool.optJSONObject("inputSchema") ?: JSONObject()
                    if (runCatching { parameterHeaderAnnotations(schema) }.isSuccess) {
                        tools.add(McpTool(toolName, tool.optString("description").take(650), schema))
                    }
                }
            }
            cursor = result.optString("nextCursor").ifBlank { null }
            if (cursor == null || tools.size >= 250) return McpServerInfo(name, version, tools)
        }
        return McpServerInfo(name, version, tools)
    }

    fun callTool(name: String, params: JSONObject, schema: JSONObject = JSONObject()): JSONObject {
        require(name.isNotBlank() && name.length <= 200) { "Ungültiger Toolname" }
        if (profile.transport == McpTransport.LEGACY_SSE) {
            return McpLegacySseClient(profile).use { it.open(); it.callTool(name, params) }
        }
        // Never retry a side-effecting tools/call through a different transport.
        // An HTTP error may happen AFTER the server performed the action.
        // Legacy endpoints are selected by the explicit dispatcher McpConnection.
        return callToolModern(name, params, schema)
    }

    private fun callToolModern(name: String, params: JSONObject, schema: JSONObject): JSONObject {
        init()
        val headers = if (modern) parameterHeaders(schema, params) else emptyMap()
        return invoke("tools/call", JSONObject().put("name", name).put("arguments", params), headers)
    }

    companion object {
        private val headerToken = Regex("^[A-Za-z][A-Za-z0-9-]{0,62}$")

        /** SEP-2243: non-ASCII, leading/trailing whitespace and sentinels are encoded. */
        fun encodeHeaderValue(value: String): String {
            val plain = value.isNotEmpty() && value.trim() == value &&
                value.all { it.code in 32..126 } &&
                !(value.startsWith("=?base64?") && value.endsWith("?="))
            return if (plain) value else "=?base64?" +
                Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8)) + "?="
        }

        /** Traverse only static properties; nested JSON schema arrays/refs do not qualify. */
        fun parameterHeaderAnnotations(schema: JSONObject): Map<String, List<String>> {
            val found = linkedMapOf<String, List<String>>()
            val used = hashSetOf<String>()
            fun scan(obj: JSONObject, path: List<String>) {
                val fields = obj.optJSONObject("properties") ?: return
                val names = fields.keys()
                while (names.hasNext()) {
                    val key = names.next()
                    val child = fields.optJSONObject(key) ?: continue
                    val route = path + key
                    if (child.has("x-mcp-header")) {
                        val header = child.optString("x-mcp-header")
                        require(headerToken.matches(header)) { "Ungültiger MCP-Parameterheader" }
                        require(used.add(header.lowercase())) { "Doppelter MCP-Parameterheader" }
                        require(child.optString("type") in setOf("string", "integer", "boolean")) {
                            "MCP-Parameterheader nur für string/integer/boolean"
                        }
                        found[header] = route
                    }
                    if (child.optString("type") == "object") scan(child, route)
                }
            }
            scan(schema, emptyList())
            return found
        }

        fun parameterHeaders(schema: JSONObject, args: JSONObject): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for ((name, route) in parameterHeaderAnnotations(schema)) {
                var value: Any? = args
                for (item in route) value = (value as? JSONObject)?.opt(item)
                if (value == null || value === JSONObject.NULL) continue
                val plain = when (value) {
                    is String -> value
                    is Boolean -> value.toString()
                    is Number -> {
                        require(value.toString().matches(Regex("^-?[0-9]+$"))) {
                            "MCP-Header erwartet eine Ganzzahl"
                        }
                        value.toString()
                    }
                    else -> throw IllegalArgumentException("MCP-Headerparameter darf kein Objekt/Array sein")
                }
                require(plain.toByteArray(StandardCharsets.UTF_8).size <= 2048) {
                    "MCP-Headerwert zu lang"
                }
                result["Mcp-Param-" + name] = encodeHeaderValue(plain)
            }
            return result
        }
    }
}
