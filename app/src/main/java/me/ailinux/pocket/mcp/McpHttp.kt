package me.ailinux.pocket.mcp

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

data class McpHttpReply(
    val status: Int,
    val contentType: String,
    val body: String,
    val sessionId: String?,
    val authenticate: String?
)

class McpHttpFailure(val code: Int, val challenge: String?, explanation: String,
    val responseBody: String = "") : java.io.IOException(explanation)

/**
 * Strict transport: no automatic redirects, no auth tokens in URLs,
 * no credentials forwarded cross-origin. TLS checked by platform.
 */
open class McpHttp {
    private val maxBytes = 2 * 1024 * 1024

    open fun call(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
        allowLocal: Boolean = false
    ): McpHttpReply {
        McpSecurity.requireUrl(url, permitLoopback = allowLocal)
        require(method == "GET" || method == "POST") { "HTTP-Methode nicht unterstützt" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 35_000
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("User-Agent", "AILinux-Pocket-AI-MCP/0.1")
            headers.forEach { (key, value) ->
                require(!key.contains('\r') && !key.contains('\n') &&
                    !value.contains('\r') && !value.contains('\n')) { "Ungültiger HTTP-Header" }
                conn.setRequestProperty(key, value)
            }
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                val data = (body ?: "").toByteArray(StandardCharsets.UTF_8)
                require(data.size < maxBytes) { "MCP-Anfrage zu groß" }
                conn.outputStream.use { it.write(data) }
            }
            val status = conn.responseCode
            val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase() ?: ""
            val challenge = conn.getHeaderField("WWW-Authenticate")
            val session = conn.getHeaderField("Mcp-Session-Id")
            if (status in 300..399) {
                throw McpHttpFailure(status, challenge, "Weiterleitung abgelehnt: neue Serveradresse prüfen (HTTP $status)")
            }
            if (status == 401 || status == 403) {
                throw McpHttpFailure(status, challenge, if (status == 401)
                    "Anmeldung erforderlich (HTTP 401)" else "Keine Berechtigung (HTTP 403)")
            }
            if (status !in 200..299) {
                val error = conn.errorStream?.use { readLimited(it, 256 * 1024) }.orEmpty()
                val message = runCatching {
                    JSONObject(error).optJSONObject("error")?.optString("message")
                }.getOrNull()?.take(180).orEmpty()
                throw McpHttpFailure(status, challenge,
                    "Server meldet HTTP $status" + if (message.isNotBlank()) ": $message" else "",
                    error.take(32768))
            }
            val responseBody = if (status == 204 || status == 202) "" else
                conn.inputStream.use {
                    if (mime == "text/event-stream") readSse(it)
                    else readLimited(it, maxBytes)
                }
            return McpHttpReply(status, mime, responseBody, session, challenge)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val len = input.read(block)
            if (len < 0) break
            require(output.size() + len <= limit) { "MCP-Antwort über Größenlimit" }
            output.write(block, 0, len)
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    /**
     * Streamable HTTP SSE is request-scoped. Ignore progress notifications and
     * return the final JSON-RPC response. The transport timeout is bounded.
     */
    private fun readSse(input: java.io.InputStream): String {
        val reader = input.bufferedReader(StandardCharsets.UTF_8)
        var event = ""
        val data = StringBuilder()
        var total = 0
        var lastResponse: String? = null
        fun consume(): Boolean {
            val payload = data.toString()
            if (payload.isNotBlank() && (event.isEmpty() || event == "message")) {
                val obj = runCatching { JSONObject(payload) }.getOrNull()
                if (obj != null && obj.has("jsonrpc") && obj.has("id") &&
                    (obj.has("result") || obj.has("error"))) {
                    lastResponse = payload
                    return true
                }
            }
            event = ""
            data.setLength(0)
            return false
        }
        while (true) {
            val line = reader.readLine() ?: break
            total += line.length
            require(total <= maxBytes) { "SSE-Antwort über Größenlimit" }
            if (line.isEmpty()) {
                if (consume()) break
            } else if (line.startsWith("event:")) {
                event = line.substringAfter(':').trim()
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substringAfter(':').trimStart())
            }
        }
        if (lastResponse == null) consume()
        return lastResponse ?: throw java.io.IOException("Keine JSON-RPC-Antwort im SSE-Stream")
    }
}

object McpAuthorizationHeader {
    fun forProfile(profile: McpProfile): Map<String, String> = when (profile.auth) {
        McpAuth.NONE -> emptyMap()
        McpAuth.BEARER -> mapOf("Authorization" to "Bearer " + profile.bearer.trim())
        McpAuth.API_KEY -> mapOf(profile.apiKeyName.trim() to profile.apiKey)
        McpAuth.BASIC -> {
            val encoded = Base64.getEncoder().encodeToString(
                (profile.username + ":" + profile.password).toByteArray(StandardCharsets.UTF_8)
            )
            mapOf("Authorization" to "Basic $encoded")
        }
        McpAuth.OAUTH -> if (profile.oauthAccessToken.isBlank()) {
            throw IllegalStateException("OAuth-Verbindung noch nicht autorisiert")
        } else mapOf("Authorization" to "Bearer " + profile.oauthAccessToken)
    }
}
