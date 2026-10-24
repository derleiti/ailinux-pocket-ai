package me.ailinux.pocket.mcp

import org.json.JSONObject
import java.net.URI
import java.util.UUID

enum class McpAuth(val title: String) {
    NONE("Ohne Anmeldung"),
    BEARER("Bearer-Token"),
    API_KEY("API-Key (HTTP-Header)"),
    BASIC("Benutzername / Passwort"),
    OAUTH("OAuth 2.0 + PKCE")
}

enum class McpTransport(val title: String) {
    AUTO("Automatisch"),
    STREAMABLE_HTTP("Streamable HTTP"),
    LEGACY_SSE("Legacy HTTP+SSE")
}

enum class McpRedirect(val title: String) {
    LOOPBACK("127.0.0.1 (MCP-konform)"),
    APP("Android-App-Redirect (kompatibel)")
}

data class McpProfile(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val endpoint: String = "",
    val transport: McpTransport = McpTransport.AUTO,
    val auth: McpAuth = McpAuth.NONE,
    val bearer: String = "",
    val apiKeyName: String = "X-API-Key",
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val oauthClientId: String = "",
    val oauthScopes: String = "",
    val oauthRedirect: McpRedirect = McpRedirect.LOOPBACK,
    val oauthIssuer: String = "",
    val oauthAuthorizationEndpoint: String = "",
    val oauthTokenEndpoint: String = "",
    val oauthAccessToken: String = "",
    val oauthRefreshToken: String = "",
    val oauthExpiresAt: Long = 0L,
    val oauthRefreshScopes: String = "",
    val oauthRedirectRegistered: String = "",
    // Added in RC7: 0 means a legacy/preset client ID with unknown provenance.
    // Needed to repair stale server registrations after a backend migration.
    val oauthClientRegisteredAt: Long = 0L
) {
    fun publicSummary(): String = title + " · " + endpoint + " · " + auth.title

    fun toJson() = JSONObject()
        .put("id", id).put("title", title).put("endpoint", endpoint)
        .put("transport", transport.name)
        .put("auth", auth.name).put("bearer", bearer)
        .put("apiKeyName", apiKeyName).put("apiKey", apiKey)
        .put("username", username).put("password", password)
        .put("oauthClientId", oauthClientId).put("oauthScopes", oauthScopes)
        .put("oauthRedirect", oauthRedirect.name)
        .put("oauthIssuer", oauthIssuer)
        .put("oauthAuthorizationEndpoint", oauthAuthorizationEndpoint)
        .put("oauthTokenEndpoint", oauthTokenEndpoint)
        .put("oauthAccessToken", oauthAccessToken)
        .put("oauthRefreshToken", oauthRefreshToken)
        .put("oauthExpiresAt", oauthExpiresAt)
        .put("oauthRefreshScopes", oauthRefreshScopes)
        .put("oauthRedirectRegistered", oauthRedirectRegistered)
        .put("oauthClientRegisteredAt", oauthClientRegisteredAt)

    companion object {
        fun fromJson(j: JSONObject) = McpProfile(
            id = j.optString("id", UUID.randomUUID().toString()),
            title = j.optString("title"),
            endpoint = j.optString("endpoint"),
            transport = enumOr(McpTransport.AUTO, j.optString("transport")),
            auth = enumOr(McpAuth.NONE, j.optString("auth")),
            bearer = j.optString("bearer"),
            apiKeyName = j.optString("apiKeyName", "X-API-Key"),
            apiKey = j.optString("apiKey"),
            username = j.optString("username"),
            password = j.optString("password"),
            oauthClientId = j.optString("oauthClientId"),
            oauthScopes = j.optString("oauthScopes"),
            oauthRedirect = enumOr(McpRedirect.LOOPBACK, j.optString("oauthRedirect")),
            oauthIssuer = j.optString("oauthIssuer"),
            oauthAuthorizationEndpoint = j.optString("oauthAuthorizationEndpoint"),
            oauthTokenEndpoint = j.optString("oauthTokenEndpoint"),
            oauthAccessToken = j.optString("oauthAccessToken"),
            oauthRefreshToken = j.optString("oauthRefreshToken"),
            oauthExpiresAt = j.optLong("oauthExpiresAt"),
            oauthRefreshScopes = j.optString("oauthRefreshScopes"),
            oauthRedirectRegistered = j.optString("oauthRedirectRegistered"),
            oauthClientRegisteredAt = j.optLong("oauthClientRegisteredAt", 0L)
        )

        private inline fun <reified T : Enum<T>> enumOr(fallback: T, value: String): T =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback
    }
}

object McpSecurity {
    const val APP_REDIRECT = "me.ailinux.pocket://oauth/callback"
    const val LOOPBACK_PORT = 18467
    const val LOOPBACK_REDIRECT = "http://127.0.0.1:$LOOPBACK_PORT/oauth/callback"
    private val header = Regex("^[A-Za-z][A-Za-z0-9-]{0,62}$")
    private val forbiddenHeader = setOf(
        "authorization", "cookie", "host", "content-length", "content-type",
        "mcp-session-id", "mcp-protocol-version", "origin", "proxy-authorization",
        "connection", "transfer-encoding"
    )

    fun validEndpoint(value: String): String = requireUrl(value, true).toString().trimEnd('/')

    /** Resource indicator includes the MCP endpoint path; query is not a resource identity. */
    fun resource(value: String): String {
        val uri = requireUrl(value, true)
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port,
            uri.path.ifEmpty { "/" }, null, null).toString()
    }

    fun requireUrl(value: String, permitLoopback: Boolean = false): URI {
        require(value.length in 8..2048 && !value.contains('\n') && !value.contains('\r')) {
            "Ungültige Adresse"
        }
        val uri = URI(value).normalize()
        require(uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null) {
            "Serveradresse ohne Benutzerinfo oder Fragment eingeben"
        }
        val host = uri.host.lowercase()
        val allowed = uri.scheme.equals("https", true) ||
            (permitLoopback && uri.scheme.equals("http", true) &&
                host in setOf("127.0.0.1", "::1", "localhost"))
        require(allowed) { "HTTPS erforderlich (HTTP nur für 127.0.0.1 / localhost)" }
        require(uri.port in -1..65535 && uri.port != 0) { "Ungültiger Port" }
        return uri
    }

    fun validate(profile: McpProfile): McpProfile {
        require(profile.title.trim().isNotEmpty() && profile.title.length <= 100) {
            "Servername fehlt oder ist zu lang"
        }
        val endpoint = validEndpoint(profile.endpoint.trim())
        when (profile.auth) {
            McpAuth.BEARER -> require(profile.bearer.isNotBlank()) { "Bearer-Token fehlt" }
            McpAuth.API_KEY -> {
                require(profile.apiKey.isNotBlank()) { "API-Key fehlt" }
                val name = profile.apiKeyName.trim()
                require(header.matches(name) && name.lowercase() !in forbiddenHeader) {
                    "Unsicherer oder ungültiger Headername"
                }
            }
            McpAuth.BASIC -> require(profile.username.isNotEmpty() && profile.password.isNotEmpty()) {
                "Benutzername und Passwort erforderlich"
            }
            McpAuth.OAUTH -> {
                require(profile.oauthClientId.length <= 500) { "OAuth-Client-ID zu lang" }
                if (profile.oauthIssuer.isNotBlank()) requireUrl(profile.oauthIssuer)
            }
            else -> Unit
        }
        return profile.copy(title = profile.title.trim(), endpoint = endpoint)
    }

    fun scope(value: String): String = value.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.distinct().joinToString(" ")

    fun credentialChanged(a: McpProfile, b: McpProfile): Boolean =
        a.endpoint != b.endpoint || a.transport != b.transport || a.auth != b.auth ||
        a.bearer != b.bearer || a.apiKey != b.apiKey || a.apiKeyName != b.apiKeyName ||
        a.username != b.username || a.password != b.password ||
        a.oauthClientId != b.oauthClientId ||
        a.oauthScopes != b.oauthScopes || a.oauthRedirect != b.oauthRedirect ||
        a.oauthIssuer != b.oauthIssuer || a.oauthAuthorizationEndpoint != b.oauthAuthorizationEndpoint ||
        a.oauthTokenEndpoint != b.oauthTokenEndpoint
}
