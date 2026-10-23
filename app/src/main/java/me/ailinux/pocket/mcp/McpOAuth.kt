package me.ailinux.pocket.mcp

import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class McpOAuthStart(val profile: McpProfile, val authorizationUrl: String, val redirect: String) {
    val browserUrl: String get() = authorizationUrl
}

/**
 * Native public OAuth client: system browser, PKCE S256, resource indicators,
 * protected-resource and AS metadata discovery, optional dynamic registration.
 * No implicit flow, embedded webview, app secret or bearer in a URL.
 */
class McpOAuth(private val vault: McpTokenStore, private val http: McpHttp = McpHttp()) {
    private data class Discovery(
        val issuer: String, val authorizationEndpoint: String, val tokenEndpoint: String,
        val registrationEndpoint: String?, val scopes: String
    )

    // Named adapters used by the Android SETUP controller.
    fun start(profile: McpProfile): McpOAuthStart = begin(profile)
    fun finish(callback: URI): McpProfile = complete(callback.toString())
    fun refreshIfNeeded(profile: McpProfile): McpProfile = authorized(profile)

    fun begin(original: McpProfile): McpOAuthStart {
        require(original.auth == McpAuth.OAUTH) { "OAuth-Profil erforderlich" }
        val profile = McpSecurity.validate(original)
        val redirect = if (profile.oauthRedirect == McpRedirect.APP)
            McpSecurity.APP_REDIRECT else McpSecurity.LOOPBACK_REDIRECT
        val resource = McpSecurity.resource(profile.endpoint)
        val discovery = discover(profile, resource)
        var clientId = profile.oauthClientId.trim()
        if (clientId.isEmpty()) {
            val register = discovery.registrationEndpoint
                ?: throw IOException("OAuth-Client-ID fehlt. Server bietet keine dynamische Clientregistrierung an.")
            val info = JSONObject().put("client_name", "AILinux Pocket AI")
                .put("redirect_uris", org.json.JSONArray().put(redirect))
                .put("token_endpoint_auth_method", "none")
                .put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token"))
                .put("response_types", org.json.JSONArray().put("code"))
            val response = http.call(register, "POST", info.toString())
            clientId = JSONObject(response.body).optString("client_id")
            require(clientId.isNotBlank() && clientId.length <= 500) { "Clientregistrierung lieferte keine gültige ID" }
        }
        val verifier = randomBase64(32)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val state = randomBase64(32)
        val scope = McpSecurity.scope(profile.oauthScopes).ifEmpty { discovery.scopes }
        val target = profile.copy(
            oauthClientId = clientId, oauthScopes = scope,
            oauthIssuer = discovery.issuer,
            oauthAuthorizationEndpoint = discovery.authorizationEndpoint,
            oauthTokenEndpoint = discovery.tokenEndpoint,
            oauthRedirectRegistered = redirect,
            oauthAccessToken = "", oauthRefreshToken = "", oauthExpiresAt = 0L
        )
        vault.upsert(target)
        vault.putPendingOAuth(JSONObject()
            .put("profileId", target.id).put("state", state).put("verifier", verifier)
            .put("redirect", redirect).put("tokenEndpoint", discovery.tokenEndpoint)
            .put("clientId", clientId).put("resource", resource)
            .put("timestamp", System.currentTimeMillis()))
        val params = linkedMapOf(
            "response_type" to "code", "client_id" to clientId,
            "redirect_uri" to redirect, "state" to state,
            "code_challenge" to challenge, "code_challenge_method" to "S256",
            "resource" to resource
        )
        if (scope.isNotBlank()) params["scope"] = scope
        return McpOAuthStart(target, addQuery(discovery.authorizationEndpoint, params), redirect)
    }

    fun pendingRedirect(): String? = vault.pendingOAuth()?.optString("redirect")?.ifBlank { null }

    fun complete(callback: String): McpProfile {
        val pending = vault.pendingOAuth() ?: throw IOException("Keine offene OAuth-Anmeldung. Bitte erneut verbinden.")
        val timestamp = pending.optLong("timestamp")
        if (System.currentTimeMillis() - timestamp !in 0..(10 * 60 * 1000L)) {
            vault.clearPendingOAuth()
            throw IOException("OAuth-Anmeldung abgelaufen. Bitte erneut starten.")
        }
        val incoming = URI(callback)
        val redirect = URI(pending.getString("redirect"))
        require(incoming.scheme.equals(redirect.scheme, true)
            && incoming.host.equals(redirect.host, true)
            && incoming.port == redirect.port
            && incoming.path == redirect.path && incoming.fragment == null) {
            "OAuth-Redirect stimmt nicht mit dem registrierten Rückweg überein"
        }
        val query = parseParams(incoming.rawQuery.orEmpty())
        val returnedState = query["state"] ?: ""
        require(returnedState.isNotBlank() && MessageDigest.isEqual(
            returnedState.toByteArray(StandardCharsets.UTF_8),
            pending.getString("state").toByteArray(StandardCharsets.UTF_8))) {
            "OAuth-state ungültig: Anmeldung nicht übernommen"
        }
        val error = query["error"]
        if (!error.isNullOrBlank()) {
            vault.clearPendingOAuth()
            throw IOException("OAuth-Anmeldung abgelehnt: " + error.take(100))
        }
        val code = query["code"] ?: throw IOException("OAuth-Anmeldung ohne Authorization Code")
        require(code.isNotBlank() && code.length < 8192) { "Ungültiger OAuth-Code" }
        vault.clearPendingOAuth() // state and code are single-use, including on network failures
        val profile = vault.get(pending.getString("profileId"))
            ?: throw IOException("MCP-Profil wurde während der Anmeldung gelöscht")
        require(profile.auth == McpAuth.OAUTH
            && profile.oauthClientId == pending.getString("clientId")
            && McpSecurity.resource(profile.endpoint) == pending.getString("resource")) {
            "MCP-Profil hat sich während der Anmeldung geändert"
        }
        val tokenParams = linkedMapOf(
            "grant_type" to "authorization_code", "code" to code,
            "redirect_uri" to pending.getString("redirect"),
            "client_id" to pending.getString("clientId"),
            "code_verifier" to pending.getString("verifier"),
            "resource" to pending.getString("resource")
        )
        if (query["iss"]?.isNotBlank() == true) {
            require(query["iss"] == profile.oauthIssuer) {
                "OAuth-Issuer stimmt nicht mit ursprünglichem Server überein"
            }
        }
        val token = tokenRequest(pending.getString("tokenEndpoint"), tokenParams)
        val updated = withToken(profile, token)
        vault.upsert(updated)
        return updated
    }

    fun authorized(profile: McpProfile): McpProfile {
        if (profile.auth != McpAuth.OAUTH) return profile
        if (profile.oauthAccessToken.isBlank()) throw IOException("OAuth nicht verbunden. Unter Setup anmelden.")
        if (profile.oauthExpiresAt == 0L || profile.oauthExpiresAt > System.currentTimeMillis() + 60_000)
            return profile
        if (profile.oauthRefreshToken.isBlank()) throw IOException("OAuth-Token abgelaufen; bitte neu anmelden.")
        val tokenEndpoint = McpSecurity.requireUrl(profile.oauthTokenEndpoint).toString()
        val params = linkedMapOf(
            "grant_type" to "refresh_token", "refresh_token" to profile.oauthRefreshToken,
            "client_id" to profile.oauthClientId,
            "resource" to McpSecurity.resource(profile.endpoint)
        )
        val updated = withToken(profile, tokenRequest(tokenEndpoint, params))
        vault.upsert(updated)
        return updated
    }

    private fun withToken(previous: McpProfile, token: JSONObject): McpProfile {
        val access = token.optString("access_token")
        require(token.optString("token_type").equals("Bearer", ignoreCase = true)) {
            "OAuth-Server lieferte kein Bearer-Token"
        }
        require(access.isNotBlank() && access.length <= 16384) { "OAuth-Server lieferte kein Access-Token" }
        val expiresIn = token.optLong("expires_in", 0L).coerceIn(0L, 365L * 86400)
        return previous.copy(
            oauthAccessToken = access,
            oauthRefreshToken = token.optString("refresh_token").ifBlank { previous.oauthRefreshToken },
            oauthExpiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
            oauthRefreshScopes = token.optString("scope").ifBlank { previous.oauthScopes }
        )
    }

    private fun tokenRequest(url: String, params: Map<String, String>): JSONObject {
        McpSecurity.requireUrl(url)
        val reply = http.call(url, "POST", form(params), contentType = "application/x-www-form-urlencoded")
        val json = JSONObject(reply.body)
        val failure = json.optString("error")
        if (failure.isNotBlank()) throw IOException("OAuth-Tokenfehler: " + failure.take(100))
        return json
    }

    private fun discover(profile: McpProfile, resource: String): Discovery {
        val uri = URI(resource)
        val origin = URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString().trimEnd('/')
        val suffix = uri.rawPath.takeIf { it != "/" }.orEmpty()
        val metadataUrls = listOf(
            origin + "/.well-known/oauth-protected-resource" + suffix,
            origin + "/.well-known/oauth-protected-resource"
        )
        var protected: JSONObject? = null
        // Discover well-known resource metadata first: GET on an MCP endpoint
        // may itself open a long-lived SSE connection and block for its timeout.
        // Only issue an unauthenticated challenge request when metadata fails.
        fun resolveMetadata(candidates: List<String>): JSONObject? {
            for (url in candidates.distinct()) {
                try {
                    McpSecurity.requireUrl(url, permitLoopback =
                        uri.host in setOf("localhost", "127.0.0.1", "::1"))
                    val json = JSONObject(http.call(url, "GET", allowLocal = true).body)
                    val metadataResource = json.optString("resource")
                    if (metadataResource.isNotEmpty()) {
                        require(McpSecurity.resource(metadataResource) == resource) {
                            "OAuth-Ressourcenkennung stimmt nicht mit dem MCP-Server überein"
                        }
                    }
                    if (json.optJSONArray("authorization_servers") != null) return json
                } catch (_: McpHttpFailure) {
                    // Try the next discovery location.
                }
            }
            return null
        }
        protected = resolveMetadata(metadataUrls)
        if (protected == null && profile.oauthIssuer.isBlank()) {
            val challenge = runCatching {
                http.call(profile.endpoint, "GET", allowLocal = true)
            }.exceptionOrNull() as? McpHttpFailure
            val advertised = challenge?.challenge?.let {
                Regex("resource_metadata\\s*=\\s*\"([^\"]+)\"").find(it)
                    ?.groupValues?.getOrNull(1)
            }
            if (!advertised.isNullOrBlank()) protected = resolveMetadata(listOf(advertised))
        }
        val issuer = profile.oauthIssuer.ifBlank {
            protected?.optJSONArray("authorization_servers")?.optString(0).orEmpty()
        }
        require(issuer.isNotBlank()) {
            "OAuth-Discovery fehlgeschlagen. Authorization-Server-Issuer im Profil eintragen."
        }
        val server = McpSecurity.requireUrl(issuer)
        val root = URI(server.scheme, null, server.host, server.port, null, null, null).toString().trimEnd('/')
        val path = server.rawPath.trimEnd('/')
        val locations = listOf(
            root + "/.well-known/oauth-authorization-server" + path,
            root + "/.well-known/openid-configuration" + path,
            root + path + "/.well-known/openid-configuration"
        ).distinct()
        var metadata: JSONObject? = null
        for (candidate in locations) {
            try {
                val found = JSONObject(http.call(candidate, "GET").body)
                if (found.optString("issuer").trimEnd('/') != issuer.trimEnd('/')) continue
                metadata = found
                break
            } catch (_: McpHttpFailure) {}
        }
        val info = metadata ?: throw IOException("OAuth-Authorization-Server-Metadaten nicht gefunden: " + issuer)
        val methods = info.optJSONArray("code_challenge_methods_supported")
            ?: throw IOException("OAuth-Server bestätigt PKCE-Unterstützung nicht (S256 erforderlich)")
        require((0 until methods.length()).any { methods.optString(it) == "S256" }) {
            "OAuth-Server unterstützt kein PKCE S256"
        }
        val auth = info.optString("authorization_endpoint")
        val token = info.optString("token_endpoint")
        McpSecurity.requireUrl(auth)
        McpSecurity.requireUrl(token)
        val registration = info.optString("registration_endpoint").takeIf { it.isNotBlank() }
        registration?.let { McpSecurity.requireUrl(it) }
        val supported = protected?.optJSONArray("scopes_supported")
            ?: info.optJSONArray("scopes_supported")
        val scopes = if (supported != null) (0 until supported.length())
            .map { supported.optString(it) }.filter { it.isNotBlank() }.joinToString(" ") else ""
        return Discovery(issuer, auth, token, registration, scopes)
    }

    companion object {
        fun randomBase64(length: Int): String = ByteArray(length).also {
            SecureRandom().nextBytes(it)
        }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun form(params: Map<String, String>): String = params.entries.joinToString("&") {
            enc(it.key) + "=" + enc(it.value)
        }

        fun addQuery(url: String, params: Map<String, String>): String =
            url + (if (url.contains('?')) "&" else "?") + form(params)

        fun parseParams(query: String): Map<String, String> {
            val out = linkedMapOf<String, String>()
            if (query.isBlank()) return out
            require(query.length <= 16000) { "OAuth-Callback zu lang" }
            for (part in query.split("&")) {
                val index = part.indexOf('=')
                val k = dec(if (index < 0) part else part.substring(0, index))
                val v = dec(if (index < 0) "" else part.substring(index + 1))
                require(!out.containsKey(k)) { "Doppelter OAuth-Callback-Parameter" }
                out[k] = v
            }
            return out
        }

        private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        private fun dec(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
