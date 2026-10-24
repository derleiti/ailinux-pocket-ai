package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * MCP result -> bounded untrusted tool-role payload for the local GGUF.
 *
 * MCP 2026-07-28: tools/call result is one of complete or input_required,
 * with isError for tool-level failures. 2025-11-25 omitted resultType,
 * which must be interpreted as complete for backward compatibility.
 *
 * No user role, prompts, tool credentials, images, audio, or remote script
 * instructions are synthesized here. The original, bounded server receipt
 * is always displayed separately to the user.
 */
object McpToolFeedback {
    private const val MAX_NATIVE_BYTES = 2100
    private const val MAX_TEXT_CHARS = 1350
    private val namePattern = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$")

    enum class Outcome { COMPLETE, ERROR, INPUT_REQUIRED, UNRECOGNIZED }

    fun outcome(result: JSONObject): Outcome = when {
        result.optString("resultType").isNotBlank() &&
            result.optString("resultType") !in setOf("complete", "input_required") ->
            Outcome.UNRECOGNIZED
        result.optString("resultType") == "input_required" -> Outcome.INPUT_REQUIRED
        result.optBoolean("isError", false) -> Outcome.ERROR
        else -> Outcome.COMPLETE
    }

    fun shouldResumeModel(result: JSONObject): Boolean =
        outcome(result) == Outcome.COMPLETE || outcome(result) == Outcome.ERROR

    /**
     * Use actual MCP content and structuredContent, not tool descriptions or
     * model-generated claims. Strip every '<' so Spark's reserved/control tags
     * or <tool_response> boundaries can never be introduced by remote text.
     */
    fun nativePayload(tool: McpTool, result: JSONObject): String {
        require(namePattern.matches(tool.name)) { "Ungültiger MCP-Werkzeugname" }
        require(shouldResumeModel(result)) {
            "MCP tools/call ist nicht abgeschlossen; Werkzeugantwort nicht als Ergebnis verwendbar"
        }
        val blocks = result.optJSONArray("content")
        val pieces = ArrayList<String>()
        if (blocks != null) for (i in 0 until minOf(12, blocks.length())) {
            val part = blocks.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") {
                val value = part.optString("text")
                if (value.isNotBlank()) pieces.add(value)
            }
        }
        val structure = result.optJSONObject("structuredContent")
        if (structure != null) pieces.add("structuredContent=" + structure.toString())
        val raw = if (pieces.isEmpty()) {
            val compact = JSONObject()
            for (key in listOf("available", "status", "reason", "linked",
                "discord_linked", "approved_servers", "errors")) {
                if (result.has(key)) compact.put(key, result.get(key))
            }
            if (compact.length() > 0) compact.toString()
            else "Keine lesbaren Text-/Strukturdaten geliefert."
        } else pieces.joinToString("\n").take(MAX_TEXT_CHARS)
        val cleaned = raw.replace("<", "‹")
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .take(MAX_TEXT_CHARS)
        val base = JSONObject()
            .put("source", "MCP tools/call, untrusted server data")
            .put("tool", tool.name)
            .put("resultType", result.optString("resultType").ifBlank { "complete" })
            .put("isError", result.optBoolean("isError", false))
            .put("content", cleaned)
        // Network results can be huge, and UTF-8 can use multiple bytes.
        // Truncate only the content, not the evidence/status fields.
        var encoded = base.toString()
        while (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_NATIVE_BYTES) {
            val old = base.getString("content")
            require(old.length > 64) { "MCP tool feedback cannot fit native context" }
            base.put("content", old.take(old.length - 100))
            encoded = base.toString()
        }
        check('<' !in encoded) { "Untrusted MCP role markup escaped incorrectly" }
        return encoded
    }

    fun terminalMessage(result: JSONObject): String? = when (outcome(result)) {
        Outcome.INPUT_REQUIRED ->
            "⚠️ Der MCP-Server benötigt weitere Eingaben (resultType=input_required). " +
                "Pocket AI führt dafür keinen stillen Folgetoolaufruf aus. " +
                "Der vollständige Serverstatus steht oben; die Eingabe muss separat " +
                "und nach ausdrücklicher Bestätigung behandelt werden."
        Outcome.UNRECOGNIZED ->
            "⚠️ Der MCP-Server lieferte einen unbekannten resultType. " +
                "Keine erfolgreiche Werkzeugausführung bestätigt."
        else -> null
    }

    fun summaryBanner(result: JSONObject): String = when (outcome(result)) {
        Outcome.ERROR -> "⚠️ Lokale Modellinterpretation des MCP-Fehlers (kein Erfolg):\n"
        Outcome.COMPLETE ->
            "ⓘ Lokale Modellzusammenfassung der obigen MCP-Serverdaten " +
                "(keine zusätzliche Prüfung):\n"
        Outcome.INPUT_REQUIRED, Outcome.UNRECOGNIZED ->
            "⚠️ Keine Modellzusammenfassung: Werkzeugaufruf nicht abgeschlossen."
    }

    /**
     * A tool-level MCP error must not be reframed as success by a small GGUF.
     * The original isError flag and raw receipt always take precedence.
     */
    fun misleadingSummary(result: JSONObject, modelText: String): Boolean {
        if (outcome(result) != Outcome.ERROR) return false
        val approval = Regex(
            """\b(?:erfolgreich|fehlerfrei|alles\s+(?:gut|sauber)|""" +
                """kein(?:e|en)?\s+(?:fehler|problem)|erledigt|""" +
                """success(?:ful)?|completed\s+successfully)\b""",
            RegexOption.IGNORE_CASE
        )
        return approval.containsMatchIn(modelText)
    }

    fun noMoreCalls(): String =
        "⛔ Weiterer Modell-Toolaufruf in derselben Antwort blockiert. " +
        "Neuen Auftrag separat starten. Das bestätigte MCP-Ergebnis steht oben."
}
