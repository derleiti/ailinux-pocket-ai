package me.ailinux.pocket.mcp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpRequestRoutingTest {
    @Test fun naturalSystemStatusRequestReachesNativeModelTools() {
        val userRequest = "Brumo, prüfe bitte mit deinen verbundenen MCP-Werkzeugen " +
            "den aktuellen Systemstatus von AILinux TriForce. Nutze die echte Serverantwort."
        assertEquals("triforce", McpHealthCheck.requestedTarget(userRequest))
        assertTrue(McpRequestRouting.useModelAgent(userRequest, true))
        assertFalse(McpRequestRouting.useModelAgent(userRequest, false))
        assertTrue(McpRequestRouting.expectsToolEvidence(userRequest))
    }

    @Test fun explicitDiagnosticsAndStandaloneInventoryStayDirect() {
        for (input in listOf(
            "/mcp health", "/mcp health triforce", "MCP-Health-Check",
            "Prüfe den MCP Verbindungstest", "/mcp tools", "MCP Tools",
            "Welche MCP Tools hat Discord?", "/mcp quick discord-status"
        )) assertFalse(input, McpRequestRouting.useModelAgent(input, true))
    }

    @Test fun naturalAccountAndVoiceStatusTasksReachModel() {
        for (input in listOf(
            "Prüfe bitte den TriForce Server Status",
            "Brumo, prüfe meinen Discord-Kontostatus mit deinen MCP-Tools",
            "Brumo, prüfe bitte Discord Voice mit deinen Werkzeugen",
            "Bitte suche mit MCP-Werkzeugen die Server-Logs"
        )) {
            assertTrue(input, McpRequestRouting.useModelAgent(input, true))
            assertTrue(input, McpRequestRouting.expectsToolEvidence(input))
        }
    }

    @Test fun ordinaryChatDoesNotRequireMcpTool() {
        val chat = "Servus Brumo, wie geht's?"
        assertTrue(McpRequestRouting.useModelAgent(chat, true))
        assertFalse(McpRequestRouting.expectsToolEvidence(chat))
    }

    @Test fun activityPrefersAgentBeforeDirectHealthAndReportsNoTool() {
        val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val planner = activity.indexOf("McpRequestRouting.useModelAgent(")
        val direct = activity.indexOf("McpHealthCheck.requestedTarget(text)")
        assertTrue(planner >= 0 && direct > planner)
        assertTrue(activity.contains("if (!modelToolRequest)"))
        assertTrue(activity.contains("LiteRT hat keinen nativen MCP-ToolCall angefordert"))
        assertTrue(activity.contains("McpRequestRouting.expectsToolEvidence(request)"))
    }
}
