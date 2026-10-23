package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpSecurityTest {
    @Test fun validatesTrustedUrls() {
        assertEquals("https://mcp.example.net/mcp",
            McpSecurity.validEndpoint("https://mcp.example.net/mcp"))
        assertEquals("http://127.0.0.1:4000/mcp",
            McpSecurity.validEndpoint("http://127.0.0.1:4000/mcp"))
    }
    @Test fun rejectsCredentialsInUrlAndUnsafeHttp() {
        for (bad in listOf(
            "http://mcp.example.net/mcp",
            "https://alice:password@mcp.example.net/mcp",
            "https://mcp.example.net/mcp#fragment",
            "https://mcp.example.net/mcp?token=secret",
            "javascript:alert(1)",
            "https://mcp.example.net/mcp\nX-Injection: yes"
        )) {
            assertThrows("Must reject $bad", Exception::class.java) {
                McpSecurity.validEndpoint(bad)
            }
        }
    }
    @Test fun blocksReservedApiHeaders() {
        for (unsafe in listOf("Authorization", "Host", "Cookie", "Mcp-Session-Id",
            "Proxy-Authorization", "Bad Header", "X-Custom\r\nInjection")) {
            assertThrows(IllegalArgumentException::class.java) {
                McpSecurity.validate(McpProfile(title = "t", endpoint = "https://mcp.example.net/mcp",
                    auth = McpAuth.API_KEY, apiKeyName = unsafe, apiKey = "secret"))
            }
        }
        assertEquals("X-Api-Key", McpSecurity.validate(McpProfile(
            title = "t", endpoint = "https://mcp.example.net/mcp",
            auth = McpAuth.API_KEY, apiKeyName = "X-Api-Key", apiKey = "secret"
        )).apiKeyName)
    }
    @Test fun serializesSensitiveProfilesLocallyWithoutLosingTypes() {
        val original = McpProfile(title = "Team",
            endpoint = "https://mcp.example.net/mcp",
            transport = McpTransport.LEGACY_SSE,
            auth = McpAuth.BASIC, username = "test", password = "pass")
        val loaded = McpProfile.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(McpTransport.LEGACY_SSE, loaded.transport)
        assertEquals(McpAuth.BASIC, loaded.auth)
        assertEquals("pass", loaded.password)
    }
    @Test fun oauthUrlsEncodeWithoutSecretsInQuery() {
        val query = McpOAuth.form(mapOf(
            "resource" to "https://mcp.example.net/server/mcp",
            "scope" to "tools:read tools:write",
            "state" to "ab+/="
        ))
        assertTrue(query.contains("tools%3Aread+tools%3Awrite"))
        assertEquals("ab+/=", McpOAuth.parseParams(query)["state"])
        assertThrows(IllegalArgumentException::class.java) {
            McpOAuth.parseParams("state=x&state=y")
        }
        assertEquals(43, McpOAuth.randomBase64(32).length)
    }
}
