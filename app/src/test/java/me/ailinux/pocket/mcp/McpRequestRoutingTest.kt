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

    @Test fun negatedHealthCheckDoesNotBypassNativeToolPlanner() {
        val request = "Prüfe den Systemstatus von TriForce, nicht nur einen Health-Check"
        assertTrue(McpRequestRouting.useModelAgent(request, true))
        assertTrue(McpRequestRouting.expectsToolEvidence(request))
        assertFalse(McpRequestRouting.useModelAgent("Bitte einen MCP-Health-Check machen", true))
    }

    @Test fun explanatoryMcpQuestionsDoNotNeedExternalAction() {
        for (text in listOf(
            "Erkläre mir, was Status im Discord-Server bedeutet",
            "Was bedeutet ein MCP-Tool?",
            "Brumo, erkläre mir die MCP-Tools",
            "Wie funktioniert TriForce MCP?"
        )) assertFalse(text, McpRequestRouting.expectsToolEvidence(text))
        assertTrue(McpRequestRouting.expectsToolEvidence("Was ist der Status des TriForce Servers?"))
    }

    @Test fun searchIsNotEvidenceOfRemoteToolsCall() {
        val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        assertTrue(activity.contains("if (!hadRealCall && McpRequestRouting.expectsToolEvidence(request))"))
        assertTrue(activity.contains("Die Werkzeugliste oben beweist keine Ausführung"))
        assertFalse(activity.contains("!hadRealCall && (catalog?.searchCount ?: 0) == 0"))
    }

    @Test fun crawlAndStatusActionsRequireEvidenceButExplanationsDoNot() {
        assertTrue(McpRequestRouting.expectsToolEvidence(
            "Check mit dem MCP-Crawl-Tool https://ailinux.me und zeige die Serverantwort"))
        assertTrue(McpRequestRouting.expectsToolEvidence(
            "Rufe den MCP Status von TriForce ab"))
        assertFalse(McpRequestRouting.expectsToolEvidence(
            "Erkläre mir die MCP Status-Werkzeuge"))
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
