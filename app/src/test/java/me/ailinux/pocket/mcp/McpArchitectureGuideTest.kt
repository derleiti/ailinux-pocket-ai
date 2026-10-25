package me.ailinux.pocket.mcp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpArchitectureGuideTest {
    @Test fun originalTypoRichQwenPromptIsConceptualNotTrainingOrToolExecution() {
        for (request in listOf(
            "wie würdes du über eine androud app mcp tooling beibringen mit mind 172 tools auf 3s servern.",
            "Wie würdest du einem Qwen-0.8B Modell 172 MCP Tools auf 3 Servern beibringen?",
            "Wie implementiere ich Progressive-Disclosure für MCP-Werkzeuge auf Android?",
            "How would you teach a small Android model to use MCP tools across 3 servers?",
            "Erkläre die Architektur zum Einbinden von MCP-Tools in eine kleine Offline-KI.",
            "Erkläre mir MCP, ohne externe Werkzeuge zu benutzen.",
            "Explain MCP without invoking external tools.",
            "How do I get started with MCP integration?",
            "How do I create an MCP integration?"
        )) {
            assertTrue("conceptual routing: $request", McpArchitectureGuide.applies(request))
            assertFalse("no native MCP agent for app architecture: $request",
                McpRequestRouting.useModelAgent(request, true))
            assertFalse("no external execution evidence necessary: $request",
                McpRequestRouting.expectsToolEvidence(request))
        }
    }

    @Test fun inventoryAndRemoteActionsAreNotHijackedByLocalArchitectureHelp() {
        for (request in listOf(
            "/mcp tools", "Welche MCP-Tools stehen dir zur Verfügung?",
            "Brumo, prüfe den TriForce-Systemstatus über MCP.",
            "Wie integriere ich MCP? Prüfe danach den Discord-Status.",
            "How to integrate MCP tools and check Discord connection",
            "How do I integrate MCP and send a message to Discord?",
            "How do I integrate MCP and post to Discord?",
            "How do I integrate MCP and delete a message?",
            "Wie integriere ich MCP? Poste danach eine Nachricht.",
            "Erkläre MCP und schicke eine Nachricht.",
            "Wie integriere ich MCP? Schreibe danach eine Nachricht.",
            "How do I integrate MCP and read a message?",
            "How do I design a functions API?",
            "Erkläre MCP und nutze danach den TriForce-Systemstatus.",
            "Explain MCP and use the workspace status tool.",
            "How to integrate tools with an Android app?",
            "Wie trainiere ich ein Google Tensor Modell auf Android?",
            "Prüfe die Verbindung zu einem MCP-Server und lies die Logs."
        )) assertFalse(request, McpArchitectureGuide.applies(request))
        assertTrue(McpRequestRouting.useModelAgent(
            "Wie integriere ich MCP? Prüfe danach den Discord-Status.", true))
        assertTrue(McpRequestRouting.expectsToolEvidence(
            "Wie integriere ich MCP? Prüfe danach den Discord-Status."))
        assertTrue(McpRequestRouting.useModelAgent(
            "How do I integrate MCP and send a message to Discord?", true))
    }

    @Test fun deviceReproductionNoExternalMcpExplanationIsAppHelpNotAnEmptyLiteRtPlanner() {
        val question = "Erkläre mir MCP, ohne externe Werkzeuge zu benutzen."
        assertTrue(McpArchitectureGuide.applies(question))
        assertFalse(McpRequestRouting.useModelAgent(question, true))
        assertFalse(McpRequestRouting.expectsToolEvidence(question))
        val response = McpArchitectureGuide.explain(listOf(91, 46, 35), 0)
        assertTrue(response.contains("keine Modellantwort"))
        assertTrue(response.contains("kein tools/call"))
        assertTrue(response.contains("mcp_search_tools"))
        assertFalse(response.contains("Keine Modellantwort erzeugt"))
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val send = main.substringAfter("private fun sendMessage()")
            .substringBefore("private fun exportModel(")
        assertTrue(send.indexOf("McpArchitectureGuide.applies(text)") <
            send.indexOf("sendLiteRtMessage(text)"))
    }

    @Test fun snapshotCountsAreDynamicAndExplicitlyNotLive() {
        val response = McpArchitectureGuide.explain(listOf(91, 46, 35), 0)
        assertTrue(response.contains("3 Server, 172 Tooldefinitionen"))
        assertTrue(response.contains("nicht live nachgeprüft"))
        assertTrue(response.contains("keine Modellantwort"))
        assertTrue(response.contains("kein tools/call"))
        assertTrue(response.contains("mcp_search_tools"))
        assertTrue(response.contains("mcp_get_tool_details"))
        assertTrue(response.contains("mcp_call_tool"))
        assertTrue(response.contains("Autorisierung"))
        assertTrue(response.contains("SERVER_RESULT"))
        assertFalse(response.contains("https://"))
        assertFalse(response.contains("oauth"))
        val changed = McpArchitectureGuide.explain(listOf(2, 4), 0)
        assertTrue(changed.contains("2 Server, 6 Tooldefinitionen"))
        assertFalse(changed.contains("172 Tooldefinitionen"))
        val none = McpArchitectureGuide.explain(emptyList(), 0)
        assertTrue(none.contains("kein MCP-Katalog"))
        assertFalse(none.contains("172 Tooldefinitionen"))
        val manual = McpArchitectureGuide.explain(emptyList(), 4)
        assertTrue(manual.contains("4"))
        assertFalse(manual.contains("AUTO-MCP-Snapshot"))
    }

    @Test fun appHelpRunsBeforeLiteRtAndIsPersistedWithoutAnyDispatch() {
        val code = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val send = code.substringAfter("private fun sendMessage()")
            .substringBefore("private fun exportModel(")
        assertTrue(send.indexOf("McpArchitectureGuide.applies(text)") >= 0)
        assertTrue(send.indexOf("McpArchitectureGuide.applies(text)") <
            send.indexOf("val modelToolRequest = McpRequestRouting.useModelAgent("))
        val direct = send.substringAfter("if (McpArchitectureGuide.applies(text))")
            .substringBefore("// When an MCP agent")
        assertTrue(direct.contains("appendReadOnlyChat(text"))
        assertTrue(direct.contains("return"))
        assertFalse(direct.contains("callTool("))
        assertFalse(direct.contains("executeAuto("))
        val directChat = code.substringAfter("private fun appendReadOnlyChat(")
            .substringBefore("private fun readMcpToolCatalog(")
        assertTrue(directChat.contains("requestChatPersist()"))
    }
}
