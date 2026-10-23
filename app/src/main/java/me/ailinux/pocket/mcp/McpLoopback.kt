package me.ailinux.pocket.mcp

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/** Narrow one-shot OAuth receiver; never binds LAN or internet interfaces. */
class McpLoopback(private val onCallback: (String) -> Unit) {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        stop()
        val listener = ServerSocket(McpSecurity.LOOPBACK_PORT, 1,
            InetAddress.getByName("127.0.0.1"))
        listener.soTimeout = 600_000
        server = listener
        Thread({
            try {
                repeat(10) {
                    val socket = listener.accept()
                    socket.use { s ->
                        s.soTimeout = 3500
                        val reader = s.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        val first = reader.readLine().orEmpty().take(16000)
                        var host = ""
                        for (n in 0 until 32) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Host:", ignoreCase = true)) {
                                host = header.substringAfter(':').trim()
                            }
                        }
                        val target = first.split(' ').getOrNull(1).orEmpty()
                        val matching = first.startsWith("GET ") &&
                            host == "127.0.0.1:" + McpSecurity.LOOPBACK_PORT &&
                            target.startsWith("/oauth/callback?")
                        val content = if (matching)
                            "<h2>AILinux Pocket AI</h2><p>OAuth-Rueckmeldung empfangen. Zur App zurueckkehren.</p>"
                        else "<h2>Not Found</h2>"
                        val status = if (matching) "200 OK" else "404 Not Found"
                        val bytes = content.toByteArray(StandardCharsets.UTF_8)
                        val response = "HTTP/1.1 " + status +
                            "\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\n" +
                            "Content-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
                        s.getOutputStream().write(response.toByteArray(StandardCharsets.UTF_8) + bytes)
                        if (matching && target.length <= 16000) {
                            onCallback("http://127.0.0.1:" + McpSecurity.LOOPBACK_PORT + target)
                            return@Thread
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
                // Browser cancelled, socket closed, or app stopped.
            } finally {
                listener.close()
                if (server === listener) server = null
            }
        }, "PocketMcpOAuthLoopback").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        server?.close()
        server = null
    }
}
