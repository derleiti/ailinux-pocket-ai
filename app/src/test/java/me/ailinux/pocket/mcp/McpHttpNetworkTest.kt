package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual JDK HTTP transport without com.sun.net.httpserver. */
class McpHttpNetworkTest {
    private fun oneShot(status: String, mime: String, response: String,
                        inspect: (String) -> Unit = {}): Pair<String, Thread> {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:" + listener.localPort + "/mcp"
        val worker = Thread {
            listener.use { server ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val first = input.readLine()
                    inspect(first)
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) {
                            length = line.substringAfter(":").trim().toInt()
                        }
                    }
                    if (length > 0) {
                        val chars = CharArray(length)
                        var total = 0
                        while (total < chars.size) {
                            val n = input.read(chars, total, chars.size - total)
                            if (n < 0) break
                            total += n
                        }
                    }
                    val bytes = response.toByteArray(StandardCharsets.UTF_8)
                    val header = "HTTP/1.1 " + status + "\r\nContent-Type: " + mime +
                        "\r\nLocation: https://not-mcp.example/steal\r\n" +
                        "Content-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().use {
                        it.write(header.toByteArray(StandardCharsets.UTF_8))
                        it.write(bytes)
                    }
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        return url to worker
    }

    @Test fun parsesRequestScopedSseJsonRpc() {
        val payload = "event: message\ndata: " +
            JSONObject().put("jsonrpc", "2.0").put("id", 42)
                .put("result", JSONObject().put("ok", true)).toString() + "\n\n"
        val (url, worker) = oneShot("200 OK", "text/event-stream", payload)
        val result = McpHttp().call(url, "POST", "{\"jsonrpc\":\"2.0\",\"id\":42}",
            allowLocal = true)
        assertEquals("text/event-stream", result.contentType)
        assertTrue(JSONObject(result.body).getJSONObject("result").getBoolean("ok"))
        worker.join(3000)
    }

    @Test fun neverAutomaticallyRedirectsSensitiveHeaders() {
        val (url, worker) = oneShot("307 Temporary Redirect", "text/plain", "")
        val error = assertThrows(McpHttpFailure::class.java) {
            McpHttp().call(url, "POST", "{}", mapOf("Authorization" to "Bearer abc"),
                allowLocal = true)
        }
        assertEquals(307, error.code)
        worker.join(3000)
    }

    @Test fun checksUnsafeInsecureOriginBeforeOpeningSocket() {
        assertThrows(IllegalArgumentException::class.java) {
            McpHttp().call("http://mcp.external.test/mcp", "POST", "{}", emptyMap())
        }
    }
}
