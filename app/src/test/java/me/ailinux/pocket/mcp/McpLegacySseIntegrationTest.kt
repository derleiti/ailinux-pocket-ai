package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Full legacy HTTP+SSE GET endpoint -> POST message -> GET response round-trip. */
class McpLegacySseIntegrationTest {
    private lateinit var listener: ServerSocket
    private lateinit var accepting: Thread
    @Volatile private var active = true
    @Volatile private var stream: OutputStream? = null
    private val lock = Any()
    private val requests = AtomicInteger()
    private var port = 0

    @Before fun setup() {
        listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        port = listener.localPort
        accepting = Thread {
            while (active) {
                try {
                    val socket = listener.accept()
                    Thread({ handle(socket) }, "mock-legacy-handler").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: SocketException) {
                    if (!active) break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun cleanup() {
        active = false
        listener.close()
        runCatching { stream?.close() }
        accepting.join(800)
    }

    private fun send(out: OutputStream, status: String, mime: String,
                     bytes: ByteArray = byteArrayOf()) {
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(mime).append("\r\n")
            if (mime != "text/event-stream") append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun writeEvent(text: String) {
        synchronized(lock) {
            val out = stream ?: return
            out.write(("event: message\ndata: " + text + "\n\n")
                .toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 8000
            val input = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val requestLine = input.readLine() ?: return
            val headers = hashMapOf<String, String>()
            for (i in 0 until 40) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                if (line.contains(":")) {
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
            }
            val out = sock.getOutputStream()
            if (requestLine.startsWith("GET /sse")) {
                send(out, "200 OK", "text/event-stream")
                synchronized(lock) { stream = out }
                out.write(("event: endpoint\ndata: /messages?session=test123\n\n")
                    .toByteArray(StandardCharsets.UTF_8))
                out.flush()
                while (active && stream === out) {
                    Thread.sleep(50)
                }
            } else if (requestLine.startsWith("POST /sse")) {
                send(out, "405 Method Not Allowed", "text/plain")
            } else if (requestLine.startsWith("POST /messages?session=test123")) {
                requests.incrementAndGet()
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val chars = CharArray(length)
                var count = 0
                while (count < length) {
                    val n = input.read(chars, count, length - count)
                    if (n < 0) break
                    count += n
                }
                val json = JSONObject(String(chars, 0, count))
                val method = json.optString("method")
                send(out, "202 Accepted", "application/json")
                if (json.has("id")) {
                    val content = when (method) {
                        "initialize" -> JSONObject().put("protocolVersion", "2024-11-05")
                            .put("serverInfo", JSONObject().put("name", "Legacy-Mock"))
                            .put("instructions", "Legacy SSE: ping is a read-only connectivity check.")
                        "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject()
                            .put("name", "ping").put("inputSchema", JSONObject())))
                        "tools/call" -> JSONObject().put("content", JSONArray().put(
                            JSONObject().put("type", "text").put("text", "pong")))
                        else -> JSONObject()
                    }
                    writeEvent(JSONObject().put("jsonrpc", "2.0")
                        .put("id", json.getInt("id")).put("result", content).toString())
                }
            } else {
                send(out, "404 Not Found", "text/plain")
            }
        }
    }

    private fun profile(transport: McpTransport) = McpProfile(
        title = "Mock", endpoint = "http://127.0.0.1:$port/sse", transport = transport
    )

    @Test fun explicitLegacyTransportConnectsAndListsTools() {
        val result = McpLegacySseClient(profile(McpTransport.LEGACY_SSE)).use {
            it.open()
            it.inspect()
        }
        assertEquals("Legacy-Mock", result.title)
        assertEquals("2024-11-05 (HTTP+SSE)", result.protocol)
        assertEquals("Legacy SSE: ping is a read-only connectivity check.",
            result.instructions)
        assertEquals("ping", result.tools.single().name)
        assertTrue(requests.get() >= 3)
    }

    @Test fun autoRecognizesSseUrl() {
        val info = McpConnection.inspect(profile(McpTransport.AUTO))
        assertEquals("ping", info.tools.single().name)
    }

    @Test fun explicitLegacyRunsManualTool() {
        val result = McpLegacySseClient(profile(McpTransport.LEGACY_SSE)).use {
            it.open()
            it.callTool("ping", JSONObject())
        }
        assertEquals("pong", result.getJSONArray("content")
            .getJSONObject(0).getString("text"))
    }
}
