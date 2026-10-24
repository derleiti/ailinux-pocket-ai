package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 2024-11-05 HTTP+SSE compatibility:
 * GET /sse -> "endpoint" event -> POST messages -> "message" SSE events.
 *
 * A session is bound to one GET stream. Never forward auth across origins.
 */
class McpLegacySseClient(private val profile: McpProfile) : Closeable {
    private val numbers = AtomicInteger(200)
    private val endpointEvents = ArrayBlockingQueue<String>(1)
    private val responseEvents = LinkedBlockingQueue<JSONObject>(64)
    private var inbound: HttpURLConnection? = null
    private var outbound: URI? = null
    @Volatile private var streamError: String = ""

    fun open() {
        val source = McpSecurity.requireUrl(profile.endpoint, permitLoopback = true)
        val conn = URL(profile.endpoint).openConnection() as HttpURLConnection
        inbound = conn
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 45_000
        conn.setRequestProperty("Accept", "text/event-stream")
        McpAuthorizationHeader.forProfile(profile).forEach { (k,v) ->
            conn.setRequestProperty(k, v)
        }
        val status = conn.responseCode
        if (status != 200) {
            val challenge = conn.getHeaderField("WWW-Authenticate")
            throw McpHttpFailure(status, challenge, "SSE-MCP-Server meldet HTTP $status")
        }
        if (!conn.contentType.orEmpty().startsWith("text/event-stream")) {
            throw IOException("Kein Legacy-SSE-Stream vom MCP-Server")
        }
        Thread({
            try {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    var event = ""
                    val data = StringBuilder()
                    var frames = 0
                    fun consume() {
                        val content = data.toString()
                        if (event == "endpoint" && content.isNotBlank()) {
                            endpointEvents.offer(content)
                        } else if ((event == "message" || event.isBlank()) && content.isNotBlank()) {
                            val parsed = runCatching { JSONObject(content) }.getOrNull()
                            if (parsed != null && parsed.has("id") &&
                                (parsed.has("result") || parsed.has("error"))) {
                                responseEvents.offer(parsed)
                            }
                        }
                        data.clear()
                        event = ""
                    }
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        frames++
                        if (frames > 100_000) throw IOException("SSE-Streamlimit erreicht")
                        if (line.isEmpty()) consume()
                        else if (line.startsWith("event:")) event = line.substringAfter(':').trim()
                        else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(':').trimStart())
                            if (data.length > 1024 * 1024) throw IOException("SSE-Ereignis zu groß")
                        }
                    }
                }
            } catch (e: Exception) {
                streamError = e.message?.take(180) ?: "SSE-Verbindung abgebrochen"
            }
        }, "PocketAiMcpLegacySse").apply {
            isDaemon = true
            start()
        }
        val endpoint = endpointEvents.poll(16, TimeUnit.SECONDS)
            ?: throw IOException("Kein SSE endpoint-Ereignis erhalten. " + streamError)
        val target = source.resolve(endpoint.trim())
        val basePort = if (source.port >= 0) source.port else if (source.scheme == "https") 443 else 80
        val targetPort = if (target.port >= 0) target.port else if (target.scheme == "https") 443 else 80
        require(source.scheme.equals(target.scheme, true) &&
            source.host.equals(target.host, true) && basePort == targetPort &&
            target.userInfo == null && target.fragment == null) {
            "Legacy-MCP-Endpoint verweist auf einen anderen Server: Credentials werden nicht weitergereicht"
        }
        // Query may contain a server-generated session ID, but never a client secret.
        require(target.rawQuery.orEmpty().length <= 2048) {
            "Legacy-MCP-Endpoint-Parameter zu lang"
        }
        outbound = target
    }

    private fun post(message: JSONObject) {
        val target = outbound ?: throw IOException("SSE-Endpoint ist nicht verbunden")
        val conn = target.toURL().openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            McpAuthorizationHeader.forProfile(profile).forEach { (key, value) ->
                conn.setRequestProperty(key, value)
            }
            val body = message.toString().toByteArray(StandardCharsets.UTF_8)
            require(body.size <= 1024 * 1024) { "Legacy-MCP-Anfrage zu groß" }
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status in 300..399) throw IOException("MCP-Redirect abgelehnt (kein Token-Leak)")
            if (status == 401 || status == 403) {
                throw McpHttpFailure(status, conn.getHeaderField("WWW-Authenticate"),
                    "Legacy MCP nicht autorisiert (HTTP $status)")
            }
            if (status !in 200..299) throw IOException("Legacy MCP HTTP $status")
            // Some hybrid servers answer POST with JSON instead of the SSE GET.
            if (conn.contentType.orEmpty().contains("application/json")) {
                val data = conn.inputStream.bufferedReader().readText().take(1024 * 1024)
                val json = runCatching { JSONObject(data) }.getOrNull()
                if (json?.has("id") == true) responseEvents.offer(json)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun request(method: String, params: JSONObject? = null): JSONObject {
        val reqId = numbers.incrementAndGet()
        val request = JSONObject().put("jsonrpc", "2.0").put("id", reqId)
            .put("method", method)
        if (params != null) request.put("params", params)
        post(request)
        val deadline = System.currentTimeMillis() + 32_000
        while (System.currentTimeMillis() < deadline) {
            val response = responseEvents.poll(500, TimeUnit.MILLISECONDS)
            if (response == null) {
                if (streamError.isNotBlank()) throw IOException("SSE-Verbindung beendet: " + streamError)
                continue
            }
            if (response.optInt("id", -1) != reqId) continue
            if (response.has("error")) {
                throw IOException("Legacy-MCP-Fehler: " +
                    response.optJSONObject("error")?.optString("message").orEmpty().take(200))
            }
            return response.optJSONObject("result") ?: JSONObject()
        }
        throw IOException("Legacy SSE: Antwortzeit überschritten ($method)")
    }

    private fun initialize(): JSONObject {
        val result = request("initialize", JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "AILinux Pocket AI")
                .put("version", "1.0.0-rc.13")))
        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        return result
    }

    fun inspect(): McpServerInfo {
        val handshake = initialize()
        val guidance = McpServerGuidance.fromDiscovery(handshake)
        val name = handshake.optJSONObject("serverInfo")?.optString("name").orEmpty()
            .ifBlank { profile.title }
        val tools = ArrayList<McpTool>()
        var cursor: String? = null
        repeat(10) {
            val args = JSONObject()
            if (cursor != null) args.put("cursor", cursor)
            val result = request("tools/list", args)
            val values = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until values.length()) {
                if (tools.size >= 250) break
                val tool = values.optJSONObject(i) ?: continue
                val name = tool.optString("name")
                if (name.isNotBlank()) tools.add(McpTool(name,
                    tool.optString("description").take(650),
                    tool.optJSONObject("inputSchema") ?: JSONObject()))
            }
            cursor = result.optString("nextCursor").ifBlank { null }
            if (cursor == null || tools.size >= 250) {
                return McpServerInfo(name, "2024-11-05 (HTTP+SSE)", tools, guidance)
            }
        }
        return McpServerInfo(name, "2024-11-05 (HTTP+SSE)", tools, guidance)
    }

    fun callTool(name: String, args: JSONObject): JSONObject {
        initialize()
        return request("tools/call", JSONObject().put("name", name).put("arguments", args))
    }

    override fun close() {
        inbound?.disconnect()
        inbound = null
    }
}
