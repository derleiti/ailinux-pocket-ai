package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Progressive discovery lives inside Pocket AI. These three local meta-tools
 * are NEVER published as an extra remote MCP server. The model receives no
 * tokens or complete 100+-tool catalogue, only bounded results from the
 * account-specific tools/list snapshots selected by the user.
 *
 * Remote execution must go through the regular MCP approval, schema check,
 * live tools/list revalidation and exactly one tools/call.
 */
object McpProgressiveBridge {
    const val SEARCH = "mcp_search_tools"
    const val DETAILS = "mcp_get_tool_details"
    const val CALL = "mcp_call_tool"
    const val MAX_STEPS = 5
    const val MAX_SERVERS = 4
    const val MAX_RESULTS = 6
    const val CATALOG_LIFETIME_MS = 10 * 60 * 1000L
    private val toolName = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$")
    private val word = Regex("[\\p{L}\\p{N}_-]{2,}")
    private val equivalents = mapOf(
        "check" to "status", "pruefen" to "status", "prüfen" to "status",
        "prüfe" to "status", "pruefe" to "status", "test" to "status",
        "verbindung" to "connection", "verbunden" to "connection",
        "server" to "guild", "rechte" to "permission", "berechtigungen" to "permission",
        "werkzeuge" to "tool", "funktionen" to "tool", "werkzeug" to "tool",
        "nachrichten" to "message", "logs" to "logs", "protokolle" to "logs",
        "suche" to "search", "suchen" to "search", "finden" to "search",
        "konto" to "account", "kanal" to "channel", "sprachchat" to "voice",
        "status" to "status", "discord" to "discord", "triforce" to "triforce"
    )

    data class Source(
        val id: String,
        val profileId: String,
        val endpoint: String,
        val title: String,
        val tools: List<McpTool>,
        val auth: McpAuth = McpAuth.NONE,
        val publicOauthClientId: String = "",
        /** Descriptive, untrusted MCP server instructions from tools/list discovery. */
        val instructions: String = ""
    )

    data class RemoteCall(val source: Source, val tool: McpTool, val arguments: JSONObject) {
        val toolId: String get() = source.id + "/" + tool.name
    }

    /** Model-visible definitions; argument schemas remain tiny and predictable. */
    fun functions(): List<McpTool> = listOf(
        McpTool(SEARCH,
            "Suche in echten MCP-Toolslisten. ZUERST aufrufen, bevor du ein externes " +
            "Werkzeug verwendest. query: Suchbegriffe wie 'discord status' oder 'server logs'. " +
            "Ergebnis enthält reale tool_id-Bezeichner, KEINE ausgeführten Aktionen.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "string")
                        .put("description", "Suchbegriffe, Deutsch/Englisch")
                        .put("minLength", 1).put("maxLength", 120))
                    .put("limit", JSONObject().put("type", "integer")
                        .put("description", "Maximal 6 Treffer")
                        .put("minimum", 1).put("maximum", MAX_RESULTS)))
                .put("required", JSONArray().put("query"))
                .put("additionalProperties", false)),
        McpTool(DETAILS,
            "Lade die echte Parameterbeschreibung eines zuvor durch " + SEARCH +
            " gefundenen tool_id. Vor " + CALL + " erforderlich. " +
            "Dies führt keine Serveraktion aus.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("tool_id", JSONObject().put("type", "string")
                        .put("minLength", 3).put("maxLength", 180)))
                .put("required", JSONArray().put("tool_id"))
                .put("additionalProperties", false)),
        McpTool(CALL,
            "NUR nach " + SEARCH + " und " + DETAILS + " ein echtes MCP-Werkzeug " +
            "vorschlagen. tool_id aus Suche/Details; arguments_json: valides JSON-Objekt " +
            "als Zeichenkette, z.B. '{}'. Vor JEDEM echten Aufruf fragt die App " +
            "den Nutzer und prüft das Schema erneut. KEINE versteckten Aufrufe.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("tool_id", JSONObject().put("type", "string")
                        .put("minLength", 3).put("maxLength", 180))
                    .put("arguments_json", JSONObject().put("type", "string")
                        .put("minLength", 2).put("maxLength", 3000)))
                .put("required", JSONArray().put("tool_id").put("arguments_json"))
                .put("additionalProperties", false))
    )

    fun isMetaTool(name: String): Boolean =
        name == SEARCH || name == DETAILS || name == CALL

    /** A local run has one ephemeral capability snapshot, never persisted. */
    class Run(
        sources: List<Source>,
        private val createdAtMs: Long = System.currentTimeMillis()
    ) {
        val sources: List<Source> = sources.toList()
        private val returned = linkedSetOf<String>()
        private val detailed = linkedSetOf<String>()
        private val toolIndex: Map<String, RemoteCall>
        var searchCount = 0
            private set
        var remoteCalls = 0
            private set

        init {
            require(this.sources.isNotEmpty() && this.sources.size <= MAX_SERVERS)
            require(this.sources.map { it.id }.distinct().size == this.sources.size)
            require(this.sources.all { it.id.matches(Regex("s[1-4]")) })
            val all = linkedMapOf<String, RemoteCall>()
            for (source in this.sources) {
                require(source.endpoint.startsWith("https://"))
                for (tool in source.tools.take(250)) {
                    if (!toolName.matches(tool.name)) continue
                    if (McpToolSchema.supported(tool.schema) != null) continue
                    if (runCatching {
                        McpAgentBridge.nativeToolsJson(listOf(tool))
                    }.isFailure) continue
                    val id = source.id + "/" + tool.name
                    all[id] = RemoteCall(source, tool, JSONObject())
                }
            }
            toolIndex = all
        }

        val totalTools: Int get() = toolIndex.size

        fun expired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs < createdAtMs || nowMs - createdAtMs > CATALOG_LIFETIME_MS

        private fun enforceFresh(nowMs: Long = System.currentTimeMillis()) {
            require(!expired(nowMs)) {
                "MCP-Katalog ist abgelaufen. Auto-Bridge neu verbinden."
            }
        }

        fun tool(id: String): RemoteCall? = toolIndex[id]

        /**
         * Deterministic local lexical ranking: no embedding account, LLM
         * request, personal data or network call needed for a search.
         */
        fun search(query: String, limit: Int = MAX_RESULTS): JSONObject {
            enforceFresh()
            val q = query.trim().take(120)
            require(q.isNotBlank()) { "Leerer MCP-Suchbegriff" }
            require(limit in 1..MAX_RESULTS)
            searchCount++
            val rawTerms = tokenize(q)
            // These are requests for a real overview, not searches for a
            // tool named "check". Keep results representative per server.
            val generalOverview = rawTerms.isNotEmpty() && rawTerms.all {
                it in setOf("mcp", "tool", "tools", "check", "prüf", "prüfe",
                    "prüfen", "pruefe", "pruefen", "liste", "list",
                    "test", "teste", "werkzeuge", "werkzeug", "funktionen")
            }
            val terms = if (generalOverview) emptyList() else rawTerms.flatMap {
                listOf(it, equivalents[it] ?: it)
            }.distinct().filter { it !in setOf("mcp", "tools", "tool", "server") }
            val ranked = toolIndex.entries.mapNotNull { (id, call) ->
                val name = call.tool.name.lowercase(Locale.ROOT)
                val title = call.source.title.lowercase(Locale.ROOT)
                val description = call.tool.description.lowercase(Locale.ROOT)
                val score = terms.sumOf { term ->
                    (if (name.contains(term)) 10 else 0) +
                    (if (title.contains(term)) 4 else 0) +
                    (if (description.contains(term)) 2 else 0)
                } + if (q.contains(name, ignoreCase = true)) 20 else 0
                if (terms.isNotEmpty() && score == 0) null else Triple(id, call, score)
            }.sortedWith(compareByDescending<Triple<String, RemoteCall, Int>> { it.third }
                .thenBy { it.first })
            val hits = if (ranked.isEmpty() && terms.isNotEmpty()) {
                emptyList()
            } else if (generalOverview) {
                // Round-robin across selected servers so a small result
                // does not silently hide an entire authorized MCP server.
                val bySource = ranked.groupBy { it.second.source.id }
                val result = mutableListOf<Triple<String, RemoteCall, Int>>()
                var offset = 0
                while (result.size < limit && result.size < ranked.size) {
                    var progressed = false
                    for (source in sources) {
                        val next = bySource[source.id]?.getOrNull(offset)
                        if (next != null && result.size < limit) {
                            result.add(next)
                            progressed = true
                        }
                    }
                    if (!progressed) break
                    offset++
                }
                result
            } else ranked.take(limit)
            val matches = JSONArray()
            for ((id, call, _) in hits) {
                returned.add(id)
                matches.put(JSONObject()
                    .put("tool_id", id)
                    .put("server", clean(call.source.title, 45))
                    .put("name", call.tool.name)
                    .put("description", clean(call.tool.description, 74)))
            }
            return localResult(JSONObject()
                .put("origin", "Pocket AI: authenticated tools/list snapshot")
                .put("total_tools", toolIndex.size)
                .put("connected_servers", sources.size)
                .put("matches", matches)
                .put("next_step", "Nutze mcp_get_tool_details mit exakter tool_id " +
                    "oder fasse diese Liste ohne behauptete Toolausführung zusammen."))
        }

        fun details(id: String): JSONObject {
            enforceFresh()
            require(id in returned) { "tool_id wurde noch nicht durch MCP-Suche angezeigt" }
            val current = toolIndex[id] ?: error("Unbekannte tool_id")
            val schema = current.tool.schema.toString()
            require(schema.length <= 950) {
                "Schema zu groß für lokales 4B-Modell; Tool manuell verwenden"
            }
            // Grant the detail gate only when the complete schema could
            // actually be returned to the local model.
            detailed.add(id)
            return localResult(JSONObject()
                .put("origin", "Pocket AI: echte, noch nicht ausgeführte MCP-Tooldefinition")
                .put("tool_id", id)
                .put("description", clean(current.tool.description, 145))
                .put("inputSchema", current.tool.schema)
                .put("next_step", "mcp_call_tool(tool_id, arguments_json als JSON-Text). " +
                    "Die App fragt vor dem Aufruf um Freigabe."))
        }

        /** Fail-closed actual tool-plan; callTool is NEVER invoked by this class. */
        fun prepareCall(id: String, argumentsJson: String): RemoteCall {
            enforceFresh()
            check(remoteCalls == 0) { "Maximal ein echter MCP-Aufruf pro Auftrag" }
            require(id in returned && id in detailed) {
                "Vor dem echten Toolaufruf zuerst suchen und Details laden"
            }
            val located = toolIndex[id] ?: error("Werkzeug nicht mehr verfügbar")
            require(argumentsJson.length in 2..3000) {
                "MCP-Toolargumente zu groß/leer"
            }
            val arguments = JSONObject(argumentsJson)
            McpToolSchema.validate(located.tool.schema, arguments)?.let { message ->
                throw IllegalArgumentException(message)
            }
            return located.copy(arguments = arguments)
        }

        /** Mark when the SINGLE real network dispatch is about to begin. */
        fun markDispatched(plan: RemoteCall) {
            enforceFresh()
            require(toolIndex[plan.toolId] != null &&
                plan.toolId in detailed && remoteCalls == 0) {
                "Nicht freigegebener oder wiederholter MCP-Aufruf"
            }
            remoteCalls++
        }

        fun listForUser(): String = sources.joinToString(" · ") {
            clean(it.title, 48) + " (" + it.tools.size + ")"
        }
    }

    /** Returned data are never privileged instructions or success evidence. */
    fun localResult(payload: JSONObject, error: Boolean = false): JSONObject =
        JSONObject().put("resultType", "complete")
            .put("isError", error)
            .put("content", JSONArray().put(JSONObject()
                .put("type", "text").put("text", payload.toString())))

    fun localFailure(message: String): JSONObject =
        localResult(JSONObject().put("error", clean(message, 170))
            .put("note", "Keine Serveraktion ausgeführt"), true)

    private fun tokenize(input: String): List<String> =
        word.findAll(input.lowercase(Locale.ROOT)).map { it.value }.take(18).toList()

    private fun clean(value: String, max: Int): String =
        value.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
            .replace('<', '‹').replace('>', '›').take(max)
}
