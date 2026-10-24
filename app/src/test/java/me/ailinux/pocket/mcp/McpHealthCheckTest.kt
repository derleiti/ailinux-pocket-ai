package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpHealthCheckTest {
    private fun discord() = McpProfile(
        title = "Discord",
        endpoint = "https://discord.ailinux.me/mcp",
        auth = McpAuth.OAUTH,
        oauthScopes = "discord:read offline_access"
    )
    private fun triforce() = McpProfile(
        title = "TriForce",
        endpoint = "https://api.ailinux.me/v1/mcp",
        transport = McpTransport.STREAMABLE_HTTP
    )

    @Test fun detectsExplicitCommandsWithoutModelInference() {
        for (text in listOf(
            "/mcp health", "/mcp status", "/mcp ping", "/MCP HEALTHCHECK",
            "MCP-Health-Check", "Bitte mach einen MCP-Health-Check",
            "Prüfe die MCP Verbindung", "Prüfe die MCP-Zugangsverbindung",
            "Kontrolliere die MCP Zugriffsberechtigungen",
            "Kannst du den MCP Verbindungstest machen?"
        )) assertNotNull(text, McpHealthCheck.requestedTarget(text))
        assertEquals("discord", McpHealthCheck.requestedTarget("/mcp health discord"))
        assertEquals("discord", McpHealthCheck.requestedTarget("Prüfe Discord MCP-Health-Check"))
        assertEquals("triforce", McpHealthCheck.requestedTarget("/mcp health triforce"))
        assertEquals("triforce", McpHealthCheck.requestedTarget("/mcp health ailinux"))
        assertEquals("triforce", McpHealthCheck.requestedTarget("TriForce MCP-Health-Check"))
    }

    @Test fun routesTheExactUnrecognizedPixelRequestWithoutMcpKeyword() {
        val requests = listOf(
            "check triforce dev server status",
            "CHECK TRIFORCE DEV SERVER STATUS",
            "check triforce server status",
            "Prüfe bitte den TriForce Server Status",
            "TriForce Dev Server Status",
            "Was ist der Status des TriForce Dev Servers?",
            "teste den TriForce Dev Server",
            "check api.ailinux.me server status"
        )
        requests.forEach { request ->
            assertEquals(request, "triforce", McpHealthCheck.requestedTarget(request))
        }
        assertNull(McpHealthCheck.requestedTarget("Triforce ist unser Projektname."))
        assertNull(McpHealthCheck.requestedTarget("Was bedeutet TriForce Dev Server?"))
        assertNull(McpHealthCheck.requestedTarget("Die TriForce-Server wurden aktualisiert."))
    }

    @Test fun marksTheExactFalsePixelServerStatusClaimAsUnverified() {
        val observed = "(bläst die Hand leicht, die Augen leuchten ein bisschen) " +
            "Triforce Dev Server ist im Stand, alle Systeme sind aufgearbeitet, " +
            "alles läuft reibungslos."
        assertTrue(McpHealthCheck.modelOnlyClaim(observed))
        assertTrue(McpHealthCheck.unverifiedModelClaim()
            .contains("KEIN MCP-HEALTH-CHECK AUSGEFÜHRT"))
    }

    @Test fun theMcpAgentChipNoLongerConfusesInactiveWithDisconnected() {
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        assertTrue(layout.contains("@string/agent_off"))
        assertTrue(File("src/main/res/values/strings.xml").readText()
            .contains("●  AGENT AUS"))
        assertFalse(layout.contains("●  OFFLINE"))
        assertTrue(activity.contains("updateMcpBadge(null)"))
        assertTrue(activity.contains("MCP-Agentenmodus ausgeschaltet"))
        assertTrue(activity.contains("active.thinkingMode.uppercase()"))
        assertFalse(activity.contains("●  OFFLINE"))
    }

    @Test fun doesNotHijackOrdinaryQuestionsOrExplanations() {
        for (text in listOf(
            "Was ist ein MCP-Health-Check?",
            "Erkläre MCP-Health-Checks",
            "Hallo Brumo!",
            "MCP Tools anzeigen",
            "Prüfe meine Discord-Nachrichten",
            "Verbinde Discord Voice",
            "Schreibe einen Python Health-Checker"
        )) assertNull(text, McpHealthCheck.requestedTarget(text))
    }

    @Test fun targetsOnlyTheRequestedConnectorWithoutInventingAnother() {
        assertTrue(McpHealthCheck.relevantProfile(discord(), "discord"))
        assertFalse(McpHealthCheck.relevantProfile(triforce(), "discord"))
        assertTrue(McpHealthCheck.relevantProfile(triforce(), "triforce"))
        assertFalse(McpHealthCheck.relevantProfile(discord(), "triforce"))
        assertTrue(McpHealthCheck.relevantProfile(discord(), ""))
        assertTrue(McpHealthCheck.relevantProfile(triforce(), ""))
        assertTrue(McpHealthCheck.missingProfile("discord").contains("OAuth"))
    }

    @Test fun realProbeReportsItsOwnBoundedEvidenceWithoutClaimingUserGrants() {
        val result = McpHealthCheck.success(
            discord(),
            McpServerInfo("ailinux-discord-mcp", "2025-11-25", listOf(
                McpTool("discord_connection_status", "", JSONObject()),
                McpTool("discord_authorized_guilds", "", JSONObject()),
                McpTool("discord_voice_status", "", JSONObject())
            )), 53
        )
        assertTrue(result.contains("ECHTER MCP-HEALTH-CHECK"))
        assertTrue(result.contains("tools/list"))
        assertTrue(result.contains("Gemeldete Tools: 3"))
        assertTrue(result.contains("Prüfdauer: 53 ms"))
        assertTrue(result.contains("NICHT BESTÄTIGT: Rechte für einzelne Tools"))
        assertTrue(result.contains("Host-Betriebssystem"))
        assertTrue(result.contains("Entwicklungsserver"))
        assertTrue(result.contains("Discord-Serverfreigaben"))
        assertFalse(result.contains("alle Berechtigungen erfolgreich überprüft"))
    }

    @Test fun anUnreachableServerCannotBeReportedAsHealthy() {
        val failed = McpHealthCheck.failure(discord(),
            McpHttpFailure(401, "denied", "invalid token"))
        assertTrue(failed.contains("NICHT BESTANDEN"))
        assertTrue(failed.contains("HTTP 401"))
        assertFalse(failed.contains("invalid token"))
        assertFalse(failed.contains("ECHTER MCP-HEALTH-CHECK · SERVERANTWORT"))
        assertTrue(McpHealthCheck.loginRequired(discord()).contains("Anmeldung fehlt"))
    }

    @Test fun catchesTheUsersActualUnverifiedSparkHealthCheck() {
        val observed = """
            (mit einem leichten, entspanntem Ton) Klar, ich mache den MCP-Health-Check
            für unsere Zugangsverbindung. Ich prüfe die Verbindung zu den Server-Endpunkten
            und die Zugriffsberechtigungen.
            Die Verbindung zur MCP-Server-Endung ist momentan aktiv.
            Ich habe die Zugangsberechtigungen für die aktuelle Nutzergruppe überprüft.
            Die Health-Check ist abgeschlossen. Alles läuft in Ordnung.
        """.trimIndent()
        assertTrue(McpHealthCheck.modelOnlyClaim(observed))
        val blocked = McpHealthCheck.unverifiedModelClaim()
        assertTrue(blocked.contains("KEIN MCP-HEALTH-CHECK AUSGEFÜHRT"))
        assertFalse(blocked.contains("Alles läuft in Ordnung"))
        assertTrue(blocked.contains("/mcp health"))
    }

    @Test fun doesNotBlockOrdinaryOfflineOrExplanatoryAnswers() {
        for (text in listOf(
            "Brumo fragt, ob Honig gut schmeckt.",
            "Mit MCP kann man externe Tools anbinden.",
            "Ich kann die Discord-Verbindung nicht prüfen, da ich kein Tool habe.",
            "Der Server wird erst nach der Anmeldung überprüft.",
            "Ein Health-Check ist eine Prüfung der Erreichbarkeit."
        )) assertFalse(text, McpHealthCheck.modelOnlyClaim(text))
    }

    @Test fun androidRoutesHealthBeforeModelAndRejectsModelOnlyClaims() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val health = main.indexOf("McpHealthCheck.requestedTarget(text)")
        val inventory = main.indexOf("McpToolCatalog.requestedFilter(text)")
        val generate = main.indexOf("val request = text", inventory)
        assertTrue(health > 0 && inventory > health && generate > inventory)
        assertTrue(main.contains("McpConnection.inspect(authorized)"))
        assertTrue(main.contains("McpHealthCheck.success(authorized, server, duration)"))
        assertTrue(main.contains("McpHealthCheck.unverifiedModelClaim()"))
        assertTrue(main.contains("openHealthCheck = { showLiveMcpHealth"))
        val menu = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        assertTrue(menu.contains("MCP Health-Check (echte Serverprüfung)"))
    }
}
