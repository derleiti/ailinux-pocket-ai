package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpToolCatalogTest {
    private fun discord() = McpProfile(
        title = "AILinux Discord MCP",
        endpoint = "https://discord.ailinux.me/mcp",
        auth = McpAuth.OAUTH,
        oauthScopes = "discord:read offline_access",
        oauthRedirect = McpRedirect.LOOPBACK
    )

    @Test fun recognizesQuestionsAboutLiveAvailableToolsInGermanAndEnglish() {
        val cases = mapOf(
            "Welche MCP Tools hat Discord?" to "discord",
            "Zeige mir die Discord MCP Funktionen" to "discord",
            "Was kann Discord MCP?" to "discord",
            "Welche Tools sind im MCP verfügbar?" to "",
            "Wie viele MCP Tools gibt es?" to "",
            "/mcp tools" to "",
            "MCP Tools" to "",
            "mcp werkzeuge" to "",
            "/mcp tools discord" to "discord",
            "show available Discord MCP tools" to "discord",
            "mcp tool check" to "",
            "check mcp tools" to "",
            "prüfe MCP Tools" to "",
            "teste Discord MCP Tools" to "discord"
        )
        for ((message, expected) in cases) {
            assertEquals(message, expected, McpToolCatalog.requestedFilter(message))
        }
    }

    @Test fun doesNotInterceptRegularModelConversationOrToolExecutionIntent() {
        for (message in listOf(
            "Hallo Brumo, wie geht es dir?",
            "Verbinde mich mit Discord Voice",
            "Bitte Discord Status prüfen",
            "MCP ist nicht gut",
            "Ich habe Discord über MCP verbunden"
        )) assertNull(message, McpToolCatalog.requestedFilter(message))
    }

    @Test fun liveListDisplaysOnlyActuallyReturnedTools() {
        val info = McpServerInfo("ailinux-discord-mcp", "2025-11-25", listOf(
            McpTool("discord_connection_status", "Read account connection", JSONObject()),
            McpTool("discord_authorized_guilds", "List allowed guilds", JSONObject()),
            McpTool("discord_voice_status", "Read Nova status", JSONObject())
        ))
        val result = McpToolCatalog.format(discord(), info, "discord")
        assertTrue(result.contains("ECHTE MCP-TOOLLISTE"))
        assertTrue(result.contains("Live tools/list"))
        assertTrue(result.contains("Gemeldete Tools: 3"))
        assertTrue(result.contains("discord_voice_status"))
        assertTrue(result.contains("discord_connection_status"))
        assertFalse(result.contains("discord_voice_join"))
        assertTrue(result.contains("nicht aus der Modellantwort"))
    }

    @Test fun authAndMissingServerNeverInventTools() {
        val message = McpToolCatalog.loginRequired(discord())
        assertTrue(message.contains("Anmeldung erforderlich"))
        assertFalse(message.contains("discord_connection_status"))
        assertTrue(McpToolCatalog.missingProfile("discord")
            .contains("https://discord.ailinux.me/mcp"))
        assertTrue(McpToolCatalog.isDiscordServer(discord()))
        assertFalse(McpToolCatalog.isDiscordServer(discord()
            .copy(endpoint = "https://api.ailinux.me/v1/mcp")))
    }

    @Test fun filterMatchesToolsOnServerRatherThanGenericExplanations() {
        val info = McpServerInfo("TriForce", "2026-07-28", listOf(
            McpTool("system_health", "Read local health", JSONObject()),
            McpTool("file_search", "Find a document", JSONObject())
        ))
        val filtered = McpToolCatalog.format(
            discord().copy(title = "TriForce", endpoint = "https://api.ailinux.me/v1/mcp"),
            info, "discord")
        assertTrue(filtered.contains("Keine passenden Tools"))
        assertTrue(filtered.contains("Für die AILinux-Discord-Tools"))
        assertFalse(filtered.contains("discord_voice_join"))
    }

    @Test fun nativeBindingIsPresentInRuntimeNotOnlyAUserPrompt() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val jni = File("../inference/src/main/cpp/ai_chat.cpp").readText()
        val engine = File("../inference/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt")
            .readText()
        assertTrue(main.contains("engine.resetConversationWithTools("))
        assertTrue(main.contains("engine.setSystemPrompt(groundedSystemPrompt(activeMcp), nativeTools)"))
        assertTrue(main.contains("val request = text"))
        assertFalse(main.contains("McpAgentBridge.prompt(text, agentSession.tools)"))
        assertTrue(jni.contains("inputs.tools = g_chat_tools"))
        assertTrue(jni.contains("common_chat_tools_parse_oaicompat(parsed_json)"))
        assertTrue(engine.contains("processSystemPrompt(prompt, toolsJson)"))
    }

    @Test fun preconfiguredDiscordProfileUsesPublicOAuthLoopbackNotBotToken() {
        val settings = File("src/main/java/me/ailinux/pocket/mcp/McpSettingsUi.kt").readText()
        assertTrue(settings.contains("https://discord.ailinux.me/mcp"))
        assertTrue(settings.contains("auth = McpAuth.OAUTH"))
        assertTrue(settings.contains("oauthScopes = \"discord:read offline_access\""))
        assertTrue(settings.contains("oauthRedirect = McpRedirect.LOOPBACK"))
    }
}
