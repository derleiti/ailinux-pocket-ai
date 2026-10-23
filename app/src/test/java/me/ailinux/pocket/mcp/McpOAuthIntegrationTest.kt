package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Offline contract test: protected-resource/AS discovery, browser link, PKCE,
 * state, issuer, single-use callbacks, DCR and refresh without real credentials.
 */
class McpOAuthIntegrationTest {
    private class MemoryStore : McpTokenStore {
        val map = mutableMapOf<String, McpProfile>()
        var pending: JSONObject? = null
        override fun upsert(profile: McpProfile) { map[profile.id] = profile }
        override fun get(id: String): McpProfile? = map[id]
        override fun putPendingOAuth(pending: JSONObject) {
            this.pending = JSONObject(pending.toString())
        }
        override fun pendingOAuth(): JSONObject? =
            pending?.let { JSONObject(it.toString()) }
        override fun clearPendingOAuth() { pending = null }
    }

    private class FakeServer : McpHttp() {
        val calls = mutableListOf<Pair<String, String?>>()
        var tokenCalls = 0
        var registrationCalls = 0
        override fun call(url: String, method: String, body: String?,
                          headers: Map<String, String>, contentType: String,
                          allowLocal: Boolean): McpHttpReply {
            calls.add(url to body)
            val response = when {
                url == "https://mcp.example.test/.well-known/oauth-protected-resource/mcp" ->
                    JSONObject().put("resource", "https://mcp.example.test/mcp")
                        .put("authorization_servers", JSONArray()
                            .put("https://auth.example.test"))
                url == "https://auth.example.test/.well-known/oauth-authorization-server" ->
                    JSONObject().put("issuer", "https://auth.example.test")
                        .put("authorization_endpoint",
                            "https://auth.example.test/authorize")
                        .put("token_endpoint", "https://auth.example.test/token")
                        .put("registration_endpoint", "https://auth.example.test/register")
                        .put("code_challenge_methods_supported", JSONArray().put("S256"))
                url == "https://auth.example.test/register" -> {
                    registrationCalls++
                    assertEquals("POST", method)
                    val registration = JSONObject(body!!)
                    assertEquals("none", registration.getString("token_endpoint_auth_method"))
                    JSONObject().put("client_id", "auto-registered-client")
                }
                url == "https://auth.example.test/token" -> {
                    assertEquals("POST", method)
                    assertEquals("application/x-www-form-urlencoded", contentType)
                    tokenCalls++
                    JSONObject().put("token_type", "Bearer")
                        .put("access_token", "unit-test-access-token")
                        .put("refresh_token", "unit-test-refresh-token")
                        .put("expires_in", 3600)
                }
                else -> throw IllegalArgumentException("Unexpected test request: " + url)
            }
            return McpHttpReply(200, "application/json", response.toString(), null, null)
        }
    }

    private fun profile() = McpProfile(
        title = "Test OAuth", endpoint = "https://mcp.example.test/mcp",
        auth = McpAuth.OAUTH, oauthClientId = "public-client",
        oauthRedirect = McpRedirect.APP, oauthScopes = "tools:read"
    )

    private fun linkParams(start: McpOAuthStart) =
        McpOAuth.parseParams(URI(start.authorizationUrl).rawQuery)

    @Test fun discoveryAndPkceBrowserUrlLeadToOneTimeTokenExchange() {
        val store = MemoryStore()
        val http = FakeServer()
        val oauth = McpOAuth(store, http)
        val link = oauth.begin(profile())
        assertEquals(McpSecurity.APP_REDIRECT, link.redirect)
        assertEquals("https://auth.example.test", link.profile.oauthIssuer)
        val query = linkParams(link)
        assertEquals("code", query["response_type"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("https://mcp.example.test/mcp", query["resource"])
        assertEquals("tools:read", query["scope"])
        assertEquals("public-client", query["client_id"])
        assertFalse(link.authorizationUrl.contains("unit-test-access-token"))
        val state = query["state"]!!
        val verifier = store.pending!!.getString("verifier")
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        assertEquals(expected, query["code_challenge"])
        val result = oauth.complete(McpSecurity.APP_REDIRECT + "?code=abc123&state=" + state +
            "&iss=https%3A%2F%2Fauth.example.test")
        assertEquals("unit-test-access-token", result.oauthAccessToken)
        assertEquals("unit-test-refresh-token", store.get(result.id)?.oauthRefreshToken)
        assertEquals(1, http.tokenCalls)
        assertNull(store.pendingOAuth())
        assertThrows(Exception::class.java) {
            oauth.complete(McpSecurity.APP_REDIRECT + "?code=abc123&state=" + state)
        }
        assertEquals(1, http.tokenCalls)
    }

    @Test fun rejectsSpoofedStateAndWrongCallbackHostWithoutTokenExchange() {
        val store = MemoryStore()
        val http = FakeServer()
        val oauth = McpOAuth(store, http)
        val start = oauth.begin(profile())
        assertThrows(Exception::class.java) {
            oauth.complete("me.ailinux.pocket://evil/callback?code=xxx&state=" +
                linkParams(start)["state"])
        }
        assertThrows(Exception::class.java) {
            oauth.complete(McpSecurity.APP_REDIRECT + "?code=xxx&state=attacker")
        }
        assertThrows(Exception::class.java) {
            oauth.complete(McpSecurity.APP_REDIRECT + "?code=xxx&state=" +
                linkParams(start)["state"] + "&iss=https%3A%2F%2Fevil.test")
        }
        assertEquals(0, http.tokenCalls)
    }

    @Test fun dynamicClientRegistrationRequiresPublicClientPkce() {
        val store = MemoryStore()
        val http = FakeServer()
        val start = McpOAuth(store, http).begin(
            profile().copy(oauthClientId = "", oauthRedirect = McpRedirect.LOOPBACK)
        )
        assertEquals("auto-registered-client", start.profile.oauthClientId)
        assertEquals(McpSecurity.LOOPBACK_REDIRECT, start.redirect)
        assertEquals(1, http.registrationCalls)
        assertNotNull(store.pending)
    }

    @Test fun expiredAccessTokenIsRefreshedAndPersisted() {
        val store = MemoryStore()
        val http = FakeServer()
        val oauth = McpOAuth(store, http)
        val loaded = profile().copy(
            oauthTokenEndpoint = "https://auth.example.test/token",
            oauthAccessToken = "expired", oauthRefreshToken = "refresh-before",
            oauthExpiresAt = System.currentTimeMillis() - 10000
        )
        store.upsert(loaded)
        val result = oauth.authorized(loaded)
        assertEquals("unit-test-access-token", result.oauthAccessToken)
        assertEquals(1, http.tokenCalls)
        assertEquals("unit-test-access-token", store.get(loaded.id)?.oauthAccessToken)
        assertTrue(http.calls.last().second!!.contains("grant_type=refresh_token"))
        assertFalse(http.calls.last().first.contains("refresh-before"))
    }
}
