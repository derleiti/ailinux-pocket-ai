package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI

/** Regressions for the real "Unknown OAuth client or redirect URI" Pixel failure. */
class McpDiscordOAuthRepairTest {
    private class Vault : McpTokenStore {
        private val profiles = mutableMapOf<String, McpProfile>()
        private var pending: JSONObject? = null
        override fun upsert(profile: McpProfile) { profiles[profile.id] = profile }
        override fun get(id: String) = profiles[id]
        override fun putPendingOAuth(value: JSONObject) {
            pending = JSONObject(value.toString())
        }
        override fun pendingOAuth() = pending?.let { JSONObject(it.toString()) }
        override fun clearPendingOAuth() { pending = null }
    }

    private class DiscordServer(
        var wrongRedirect: Boolean = false,
        var failRegistration: Boolean = false
    ) : McpHttp() {
        var registrations = 0
        var tokens = 0
        var unrelatedEndpoints = 0

        override fun call(
            url: String, method: String, body: String?,
            headers: Map<String, String>, contentType: String,
            allowLocal: Boolean
        ): McpHttpReply {
            val json = when (url) {
                "https://discord.ailinux.me/.well-known/oauth-protected-resource/mcp" ->
                    JSONObject().put("resource", "https://discord.ailinux.me/mcp")
                        .put("authorization_servers", JSONArray()
                            .put("https://discord.ailinux.me"))
                        .put("scopes_supported", JSONArray()
                            .put("discord:read").put("offline_access"))
                "https://discord.ailinux.me/.well-known/oauth-authorization-server" ->
                    JSONObject().put("issuer", "https://discord.ailinux.me")
                        .put("authorization_endpoint", "https://discord.ailinux.me/authorize")
                        .put("token_endpoint", "https://discord.ailinux.me/token")
                        .put("registration_endpoint", "https://discord.ailinux.me/register")
                        .put("code_challenge_methods_supported", JSONArray().put("S256"))
                "https://discord.ailinux.me/register" -> {
                    registrations++
                    assertEquals("POST", method)
                    if (failRegistration) throw McpHttpFailure(
                        429, null, "Server meldet HTTP 429"
                    )
                    val sent = JSONObject(body!!)
                    assertEquals("AILinux Pocket AI", sent.getString("client_name"))
                    assertEquals("none", sent.getString("token_endpoint_auth_method"))
                    assertEquals(listOf(McpSecurity.LOOPBACK_REDIRECT),
                        listOf(sent.getJSONArray("redirect_uris").getString(0)))
                    JSONObject().put("client_id", "dmcp_new-pixel-public-client")
                        .put("redirect_uris", JSONArray().put(
                            if (wrongRedirect) "http://127.0.0.1:12345/wrong" else
                                McpSecurity.LOOPBACK_REDIRECT
                        ))
                        .put("client_id_issued_at", 1792820000L)
                }
                "https://discord.ailinux.me/token" -> {
                    tokens++
                    assertEquals("POST", method)
                    assertEquals("application/x-www-form-urlencoded", contentType)
                    val values = McpOAuth.parseParams(body!!)
                    assertEquals("dmcp_new-pixel-public-client", values["client_id"])
                    assertEquals(McpSecurity.LOOPBACK_REDIRECT, values["redirect_uri"])
                    JSONObject().put("token_type", "Bearer")
                        .put("access_token", "fake-discord-access")
                        .put("refresh_token", "fake-discord-refresh")
                        .put("expires_in", 7200)
                        .put("scope", "discord:read offline_access")
                }
                else -> {
                    unrelatedEndpoints++
                    throw IOException("Unexpected OAuth endpoint: " + url)
                }
            }
            return McpHttpReply(200, "application/json", json.toString(), null, null)
        }
    }

    private fun discord() = McpProfile(
        title = "Discord", endpoint = McpOAuth.DISCORD_MCP_ENDPOINT,
        transport = McpTransport.STREAMABLE_HTTP,
        auth = McpAuth.OAUTH,
        oauthIssuer = McpOAuth.DISCORD_MCP_ISSUER,
        oauthScopes = "discord:read offline_access",
        oauthRedirect = McpRedirect.LOOPBACK
    )

    @Test fun legacyUnknownClientIsAutomaticallyReplacedWithFreshRegistration() {
        val original = discord().copy(
            oauthClientId = "stale-client-id-from-old-server",
            oauthRedirectRegistered = McpSecurity.LOOPBACK_REDIRECT
        )
        val vault = Vault().also { it.upsert(original) }
        val network = DiscordServer()
        val oauth = McpOAuth(vault, network)
        val start = oauth.begin(original)
        assertEquals(1, network.registrations)
        assertEquals("dmcp_new-pixel-public-client", start.profile.oauthClientId)
        assertTrue(start.profile.oauthClientRegisteredAt > 0L)
        assertEquals(McpSecurity.LOOPBACK_REDIRECT, start.redirect)
        val params = McpOAuth.parseParams(URI(start.browserUrl).rawQuery)
        assertEquals("dmcp_new-pixel-public-client", params["client_id"])
        assertEquals(McpSecurity.LOOPBACK_REDIRECT, params["redirect_uri"])
        assertEquals("discord:read offline_access", params["scope"])
        assertEquals("S256", params["code_challenge_method"])
        assertFalse(start.browserUrl.contains("stale-client-id-from-old-server"))
        // Same generated public client is reused if the first consent was
        // interrupted and the user simply reopens the login once.
        oauth.begin(vault.get(original.id)!!)
        assertEquals(1, network.registrations)
        assertEquals(0, network.unrelatedEndpoints)
    }

    @Test fun newClientCanFinishStrictPkceLoopbackRedirectAfterConsent() {
        val vault = Vault()
        val network = DiscordServer()
        val oauth = McpOAuth(vault, network)
        val start = oauth.begin(discord())
        val state = McpOAuth.parseParams(URI(start.browserUrl).rawQuery)["state"]!!
        val completed = oauth.complete(McpSecurity.LOOPBACK_REDIRECT +
            "?state=" + state + "&code=one-time-test&iss=https%3A%2F%2Fdiscord.ailinux.me")
        assertEquals("fake-discord-access", completed.oauthAccessToken)
        assertEquals("fake-discord-refresh", completed.oauthRefreshToken)
        assertEquals("dmcp_new-pixel-public-client", completed.oauthClientId)
        assertEquals(1, network.tokens)
        assertNull(vault.pendingOAuth())
    }

    @Test fun manualRepairClearsLocalCredentialsButKeepsServerIdentity() {
        val old = discord().copy(
            oauthClientId = "dmcp_stale", oauthClientRegisteredAt = 170000000L,
            oauthRedirect = McpRedirect.APP,
            oauthAccessToken = "old-access", oauthRefreshToken = "old-refresh",
            oauthExpiresAt = 180000000L,
            oauthRedirectRegistered = McpSecurity.APP_REDIRECT,
            oauthScopes = "discord:read discord:admin"
        )
        val repaired = McpOAuth.resetDiscordRegistration(old)
        assertEquals(old.id, repaired.id)
        assertEquals(old.endpoint, repaired.endpoint)
        assertEquals(old.title, repaired.title)
        assertEquals("", repaired.oauthClientId)
        assertEquals("", repaired.oauthAccessToken)
        assertEquals("", repaired.oauthRefreshToken)
        assertEquals("", repaired.oauthRedirectRegistered)
        assertEquals(McpRedirect.LOOPBACK, repaired.oauthRedirect)
        assertEquals("discord:read discord:admin", repaired.oauthScopes)
        assertEquals(0L, repaired.oauthClientRegisteredAt)
        assertThrows(IllegalArgumentException::class.java) {
            McpOAuth.resetDiscordRegistration(old.copy(endpoint = "https://other.test/mcp"))
        }
    }

    @Test fun oldCustomSchemeRedirectIsRejectedWithRepairGuidance() {
        val old = discord().copy(oauthRedirect = McpRedirect.APP)
        val network = DiscordServer()
        val error = assertThrows(IOException::class.java) {
            McpOAuth(Vault(), network).begin(old)
        }
        assertTrue(error.message!!.contains("OAuth reparieren"))
        assertEquals(0, network.registrations)
    }

    @Test fun discordsAuthorizationIssuerCannotBeReplacedByAnotherProvider() {
        val wrong = discord().copy(oauthIssuer = "https://attacker.example")
        val fake = DiscordServer()
        val error = assertThrows(IOException::class.java) {
            McpOAuth(Vault(), fake).begin(wrong)
        }
        assertTrue(error.message!!.contains("fremden Authorization-Server"))
        assertEquals(0, fake.registrations)
        assertEquals(0, fake.unrelatedEndpoints)
    }

    @Test fun forgedRegistrationCallbackMustNotBeUsed() {
        val fake = DiscordServer(wrongRedirect = true)
        val error = assertThrows(IllegalArgumentException::class.java) {
            McpOAuth(Vault(), fake).begin(discord())
        }
        assertTrue(error.message!!.contains("andere Weiterleitungsadresse"))
    }

    @Test fun registrationRateLimitShowsActionableErrorWithoutBypass() {
        val fake = DiscordServer(failRegistration = true)
        val error = assertThrows(IOException::class.java) {
            McpOAuth(Vault(), fake).begin(discord())
        }
        assertTrue(error.message!!.contains("zu viele Clients"))
        assertEquals(1, fake.registrations)
    }

    @Test fun validDiscordClientMayBeReusedWhileOtherProvidersRemainUnchanged() {
        val known = discord().copy(
            oauthClientId = "dmcp_known", oauthClientRegisteredAt = System.currentTimeMillis(),
            oauthRedirectRegistered = McpSecurity.LOOPBACK_REDIRECT
        )
        val fake = DiscordServer()
        McpOAuth(Vault(), fake).begin(known)
        assertEquals(0, fake.registrations)

        val other = known.copy(
            endpoint = "https://other.example/mcp", oauthIssuer = "https://other.example"
        )
        assertFalse(McpOAuth.isDiscordMcp(other))
        assertFalse(McpOAuth.needsFreshDiscordRegistration(other, McpSecurity.LOOPBACK_REDIRECT))
    }

    @Test fun registrationMetadataPersistsAndRepairIsVisibleInAndroidSetup() {
        val old = discord()
        val original = old.toJson()
        val recovered = McpProfile.fromJson(original)
        assertEquals(old.oauthClientRegisteredAt, recovered.oauthClientRegisteredAt)
        assertEquals(0L, McpProfile.fromJson(
            JSONObject().put("title", "legacy").put("endpoint", old.endpoint)
        ).oauthClientRegisteredAt)
        val originalCode = java.io.File(
            "src/main/java/me/ailinux/pocket/mcp/McpSettingsUi.kt"
        ).readText()
        assertTrue(originalCode.contains("Discord OAuth reparieren"))
        assertTrue(originalCode.contains("McpOAuth.resetDiscordRegistration(fresh)"))
        assertTrue(originalCode.contains("authorize(reset)"))
        val activity = java.io.File(
            "src/main/java/me/ailinux/pocket/MainActivity.kt"
        ).readText()
        assertTrue(activity.contains("●  AGENT AUS"))
    }
}
