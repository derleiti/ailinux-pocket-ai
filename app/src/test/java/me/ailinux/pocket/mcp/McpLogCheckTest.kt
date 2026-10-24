package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The actual Pixel Spark output is a regression input, not server evidence. */
class McpLogCheckTest {
    private fun triForce() = McpProfile(
        title = "triforce workspace",
        endpoint = "https://api.ailinux.me/v1/mcp",
        transport = McpTransport.AUTO,
        auth = McpAuth.NONE
    )

    @Test fun routesActualPixelLogRequestBeforeTheTinyModel() {
        val real = "versuche die triforce server logs zu durchsuchen."
        assertTrue(McpLogCheck.requested(real))
        for (message in listOf(
            "/mcp logs",
            "/mcp logs triforce",
            "/mcp journal",
            "Prüfe die TriForce-Server-Logs",
            "Bitte die TriForce Logs analysieren",
            "Suche im api.ailinux.me journal nach Fehlern",
            "Kontrolliere die TriForce Logdateien",
            "Zeige mir die TriForce Protokolle",
            "check triforce server logs",
            "TRIFORCE LOGS DURCHSUCHEN"
        )) assertTrue(message, McpLogCheck.requested(message))
    }

    @Test fun ordinaryStatusHealthQuestionsAndExplanationsAreNotHijacked() {
        for (text in listOf(
            "Erkläre mir, wie TriForce Logs speichert.",
            "Was ist ein TriForce Server Log?",
            "Warum behauptet Spark, die TriForce Logs seien sauber?",
            "Schreib ein Gedicht über TriForce Logs.",
            "Prüfe den TriForce Server Status",
            "check triforce server mcp status",
            "MCP-Health-Check",
            "Kann ich dir eine Logdatei zum Zusammenfassen geben?",
            "Prüfe meinen Discord Server",
            "Bitte erstelle einen Log Parser",
            "Hallo Brumo!"
        )) assertFalse(text, McpLogCheck.requested(text))
    }

    @Test fun onlyExactOnlineTriForceProfileCanBeSelected() {
        assertTrue(McpLogCheck.relevantProfile(triForce()))
        assertFalse(McpLogCheck.relevantProfile(triForce()
            .copy(endpoint = "http://api.ailinux.me/v1/mcp")))
        assertFalse(McpLogCheck.relevantProfile(triForce()
            .copy(endpoint = "https://api.ailinux.me/v1/mcp/sse")))
        assertFalse(McpLogCheck.relevantProfile(triForce()
            .copy(endpoint = "https://api.ailinux.me.attacker.invalid/v1/mcp")))
        assertFalse(McpLogCheck.relevantProfile(triForce()
            .copy(endpoint = "https://discord.ailinux.me/mcp")))
    }

    @Test fun noLogToolMeansNoClaimOfLogReview() {
        val tools = listOf(
            McpTool("status", "Backend health", JSONObject()),
            McpTool("workspace_status", "Workspace state", JSONObject()),
            McpTool("service_ops", "Read or control a service", JSONObject())
        )
        val server = McpServerInfo("triforce workspace", "2026-07-28", tools)
        val result = McpLogCheck.capabilityReport(triForce(), server)
        assertTrue(result.contains("Live tools/list"))
        assertTrue(result.contains("Gemeldete Tools: 3"))
        assertTrue(result.contains("Log-Lesewerkzeuge gefunden: 0"))
        assertTrue(result.contains("KEIN MCP-LOGLESEWERKZEUG VERFÜGBAR"))
        assertTrue(result.contains("KEINE Logzeilen gelesen"))
        assertTrue(result.contains("status-Tool ist kein Ersatz"))
        assertTrue(result.contains("/mcp quick triforce-status"))
        assertFalse(result.contains("Alle Logs sind sauber"))
        assertFalse(result.contains("Loganalyse erfolgreich"))
    }

    @Test fun newlyAdvertisedReadToolIsOnlyDiscoveredNotExecuted() {
        val server = McpServerInfo("triforce workspace", "2026-07-28", listOf(
            McpTool("logs_errors", "Recent errors", JSONObject()),
            McpTool("log_viewer", "Read logs", JSONObject()),
            McpTool("shell", "Execute arbitrary command", JSONObject())
        ))
        val text = McpLogCheck.capabilityReport(triForce(), server)
        assertTrue(text.contains("Log-Lesewerkzeuge gefunden: 2"))
        assertTrue(text.contains("• logs_errors"))
        assertTrue(text.contains("• log_viewer"))
        assertFalse(text.contains("• shell"))
        assertTrue(text.contains("NICHT ausgeführt"))
        assertTrue(text.contains("KEINE Logzeilen gelesen"))
    }

    @Test fun actualSparkFakeLogAuditIsFlaggedAsUnverified() {
        val userRequest = "versuche die triforce server logs zu durchsuchen."
        val actualModelOutput = """
            (Bewegung der Arme, ein leises Geräusch von mechanischen Knöpfen,
            die sich anspannen)

            Die Triforce-Server-Logs sind gerade abgeschlossen
            durchgearbeitet. Die Daten sind sauber und vollständig,
            keine versteckten Probleme oder unvollständige Einträge sind zu finden.
        """.trimIndent()
        assertTrue(McpLogCheck.modelOnlyAuditClaim(userRequest, actualModelOutput))
        assertTrue(McpLogCheck.unverifiedModelClaim().contains(
            "KEINE TRIFORCE-LOGPRÜFUNG AUSGEFÜHRT"))
        assertFalse(McpLogCheck.unverifiedModelClaim().contains("Logs sind sauber"))
    }

    @Test fun denialAndHypotheticalTextAreNotFalseSuccessClaims() {
        val request = "Prüfe die TriForce Server Logs"
        for (text in listOf(
            "Ich kann die TriForce Logs nicht prüfen, da mir die Tools fehlen.",
            "Die Logs wurden noch nicht geprüft.",
            "Ohne MCP-Zugriff kann ich nicht sagen, ob die Logs sauber sind.",
            "Um die Logs zu prüfen, sollte ein Lesewerkzeug eingebunden werden.",
            "Ich kann keine echten Logdaten auslesen.",
            "Eine mögliche Logprüfung sollte nur mit Freigabe erfolgen."
        )) assertFalse(text, McpLogCheck.modelOnlyAuditClaim(request, text))
        assertFalse(McpLogCheck.modelOnlyAuditClaim(
            "Schreib eine Geschichte über TriForce Logs",
            "Die Logs wurden geprüft und waren sauber."
        ))
    }

    @Test fun networkAndAuthenticationFailuresNeverPretendLogSuccess() {
        val missing = McpLogCheck.missingProfile()
        assertTrue(missing.contains("Kein passendes TriForce"))
        assertTrue(missing.contains("Keine Logdaten gelesen"))
        val blocked = McpLogCheck.failure(
            triForce(), McpHttpFailure(403, null, "some unsafe secret"))
        assertTrue(blocked.contains("HTTP 403"))
        assertFalse(blocked.contains("some unsafe secret"))
        assertTrue(blocked.contains("Keine Logdaten gelesen"))
    }

    @Test fun androidHasDeterministicRouteBeforeHealthCatalogAndModel() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val log = main.indexOf("McpLogCheck.requested(text)")
        val health = main.indexOf("McpHealthCheck.requestedTarget(text)")
        val catalog = main.indexOf("McpToolCatalog.requestedFilter(text)")
        val llm = main.indexOf("val request = text", catalog)
        assertTrue(log > 0 && log < health && health < catalog && catalog < llm)
        assertTrue(main.contains("McpConnection.inspect(authorized)"))
        assertTrue(main.contains("McpLogCheck.capabilityReport(authorized, info)"))
        assertTrue(main.contains("McpLogCheck.modelOnlyAuditClaim(request, answer.content)"))
        assertTrue(main.contains("McpLogCheck.unverifiedModelClaim()"))
        assertFalse(main.contains("McpConnection.callTool(authorized, \"logs\""))
        assertTrue(main.contains("TriForce: echten Log-Zugriff prüfen (ohne Spark)"))
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(layout.contains("@string/thinking_explanation"))
        assertTrue(File("src/main/res/values/strings.xml").readText()
            .contains("Es aktiviert weder MCP-Zugriff noch Werkzeuge"))
    }
}
