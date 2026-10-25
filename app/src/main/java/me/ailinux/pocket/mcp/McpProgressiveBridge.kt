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
    /** Large authenticated catalogues stay on the host, never in the model prompt. */
    const val MAX_CATALOG_TOOLS_PER_SERVER = 4000
    const val MAX_CATALOG_PAGES = 80
    const val CATALOG_LIFETIME_MS = 10 * 60 * 1000L
    /** Metadata-only fields have no validation semantics in our schema subset. */
    private val schemaMetadata = setOf(
        "description", "title", "examples", "default", "deprecated",
        "readOnly", "writeOnly", "\$schema", "\$id", "x-mcp-header"
    )

    /**
     * Token-aware preview: remove descriptions and other non-assertion
     * metadata while retaining EVERY validation constraint (required,
     * properties, types, enums, bounds, additionalProperties, nested items).
     * The original full schema remains on the host and MUST be revalidated.
     */
    private fun compactSchema(schema: JSONObject): JSONObject =
        JSONObject().also { out ->
            val keys = schema.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in schemaMetadata) continue
                val original = schema.opt(key)
                when (key) {
                    // The names inside "properties" are USER PARAMETER NAMES.
                    // A field named "title" or "description" is NOT metadata.
                    "properties" -> {
                        val properties = schema.getJSONObject(key)
                        val mapped = JSONObject()
                        val names = properties.keys()
                        while (names.hasNext()) {
                            val name = names.next()
                            mapped.put(name, compactSchema(properties.getJSONObject(name)))
                        }
                        out.put(key, mapped)
                    }
                    // Only array item schemas are schema nodes. Never mutate
                    // enum or const literal JSON objects (validation values).
                    "items" -> out.put(key, compactSchema(schema.getJSONObject(key)))
                    else -> out.put(key, original)
                }
            }
        }

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
        "status" to "status", "discord" to "discord", "triforce" to "triforce",
        "systemstatus" to "status", "systemzustand" to "status",
        "serverzustand" to "status", "verbindungsstatus" to "connection",
        "healthcheck" to "status", "verbindungsprüfung" to "connection",
        "verbindungspruefung" to "connection"
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

    data class RemoteCall(
        val source: Source,
        val tool: McpTool,
        val arguments: JSONObject,
        /** True only for a host-inferred fallback, not a native model tool call. */
        val hostFallback: Boolean = false,
        /**
         * Host inferred a user-explicit, potentially state-changing operation.
         * Requires human-visible write consent, even if an untrusted server
         * claims readOnlyHint=true. Never use the read-only execution shortcut.
         */
        val hostWriteIntent: Boolean = false
    ) {
        val toolId: String get() = source.id + "/" + tool.name
    }

    /** Model-visible definitions; argument schemas remain tiny and predictable. */
    fun functions(): List<McpTool> = listOf(
        McpTool(SEARCH,
            "Suche reale tool_id in angemeldeten MCP tools/list: " +
            "query z.B. 'discord status'. Max. 6 Treffer; keine Serveraktion.",
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
            "Lade das echte Schema einer zuvor gefundenen tool_id. " +
            "Vor mcp_call_tool erforderlich, ohne Serveraktion.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("tool_id", JSONObject().put("type", "string")
                        .put("minLength", 3).put("maxLength", 180)))
                .put("required", JSONArray().put("tool_id"))
                .put("additionalProperties", false)),
        McpTool(CALL,
            "NUR nach Suche + Details einen echten MCP-Aufruf vorschlagen: " +
            "tool_id, arguments_json als JSON-Objekt-STRING (z.B. '{}'). " +
            "App prüft erneut und holt einmalige Nutzerfreigabe.",
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
                for (tool in source.tools.take(MAX_CATALOG_TOOLS_PER_SERVER)) {
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

        /** Advertised count is NOT the same as the supported safe subset. */
        val advertisedTools: Int get() = sources.sumOf { it.tools.size }
        val totalTools: Int get() = toolIndex.size
        val filteredTools: Int get() = advertisedTools - totalTools

        fun expired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs < createdAtMs || nowMs - createdAtMs > CATALOG_LIFETIME_MS

        private fun enforceFresh(nowMs: Long = System.currentTimeMillis()) {
            require(!expired(nowMs)) {
                "MCP-Katalog ist abgelaufen. Auto-Bridge neu verbinden."
            }
        }

        fun tool(id: String): RemoteCall? = toolIndex[id]

        /** Local per-turn planning context, never a permission or an execution receipt. */
        val detailedCount: Int get() = detailed.size

        /**
         * A model already fetched full details for this exact local tool ID.
         * Preserve that fact after a malformed native ToolCall rather than
         * blindly running another top-6 search. Still insist that the
         * ORIGINAL user request uniquely selects the source whenever two
         * servers advertise the same name.
         */
        fun uniqueDetailedForRequest(name: String, originalRequest: String): RemoteCall? {
            enforceFresh()
            if (remoteCalls != 0) return null
            val matching = toolIndex.values.filter { it.tool.name == name }
            val selected = requestedSourceIds(originalRequest)
            val eligible = matching.filter { candidate ->
                candidate.toolId in detailed &&
                    (selected == null || candidate.source.id in selected)
            }
            if (matching.size > 1 && selected == null) return null
            return eligible.singleOrNull()
        }

        /** The tool name alone cannot justify picking one of several sources. */
        fun uniquelyRequestedSource(id: String, originalRequest: String): Boolean {
            enforceFresh()
            val item = toolIndex[id] ?: return false
            val sameName = toolIndex.values.filter { it.tool.name == item.tool.name }
            if (sameName.size == 1) return true
            val selected = requestedSourceIds(originalRequest) ?: return false
            return item.source.id in selected &&
                sameName.count { it.source.id in selected } == 1
        }

        /**
         * Server selection comes from the ORIGINAL user message, never the
         * model's search query or invented local tool id. Specific labels
         * outrank generic "TriForce" shared by Workspace and DEV. If the
         * user names more than one source, require clarification; one MCP
         * execution cannot silently pick one of several requested sources.
         *
         * The actual source title is from the authenticated tools/list
         * catalog; this function never accepts server text from a model.
         */
        private fun requestedSourceIds(userRequest: String?): Set<String>? {
            if (userRequest.isNullOrBlank()) return null
            val text = userRequest.lowercase(Locale.ROOT)
            val kind = listOfNotNull(
                "workspace".takeIf {
                    Regex("\\b(?:workspace|worspace|arbeitsbereich)\\b")
                        .containsMatchIn(text)
                },
                "dev".takeIf {
                    Regex("\\b(?:dev|developer)\\b").containsMatchIn(text)
                },
                "discord".takeIf {
                    Regex("\\bdiscord\\b").containsMatchIn(text)
                }
            )
            if (kind.isEmpty()) return null
            require(kind.size == 1) {
                "Mehrere MCP-Server ausdrücklich genannt: Auswahl vor Aufruf klären."
            }
            val marker = kind.single()
            val selected = sources.filter { source ->
                when (marker) {
                    "workspace" -> Regex("\\bworkspace\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(source.title)
                    "dev" -> Regex("\\bdev\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(source.title)
                    else -> source.title.contains("discord", ignoreCase = true)
                }
            }.map { it.id }.toSet()
            if (selected.isEmpty() && sources.size == 1) {
                // With exactly one already authorized server there is no
                // cross-server ambiguity. "Discord connection" may name a
                // TOOL on a generic single-server TriForce profile, not a
                // second Discord server. Never reroute a multi-server run.
                return setOf(sources.single().id)
            }
            require(selected.isNotEmpty()) {
                "Explizit angeforderter MCP-Server $marker ist nicht verbunden."
            }
            return selected
        }

        private fun requireRequestedSource(
            sourceId: String,
            userRequest: String?
        ) {
            val allowed = requestedSourceIds(userRequest)
            require(allowed == null || sourceId in allowed) {
                "MCP-Server entspricht nicht der ausdrücklich angeforderten " +
                    "Quelle (Workspace/DEV/Discord). Keine Aktion ausgeführt."
            }
        }

        /**
         * Deterministic local lexical ranking: no embedding account, LLM
         * request, personal data or network call needed for a search.
         */
        fun search(
            query: String,
            limit: Int = MAX_RESULTS,
            originalRequest: String? = null
        ): JSONObject {
            enforceFresh()
            val requestedSourceIds = requestedSourceIds(originalRequest)
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
            // Compound German words and local MCP tool identifiers must
            // work even when the model sends "TriForce-Systemzustand" or
            // "Discord_Verbindungsstatus" as ONE search term. Keep the
            // full token for exact matches and also expand its components.
            val expanded = rawTerms.flatMap { raw ->
                listOf(raw) + raw.split('-', '_').filter { it.length >= 3 }
            }
            val terms = if (generalOverview) emptyList() else expanded.flatMap {
                listOf(it, equivalents[it] ?: it)
            }.distinct().filter {
                it !in setOf("mcp", "tools", "tool", "server")
            }
            // A provider mentioned unambiguously in the query is more
            // important than a generic name such as "status" on another
            // server. If two providers are named, abstain from preference.
            val sourceHint = expanded.filter {
                it in setOf("triforce", "discord", "workspace")
            }.distinct().singleOrNull()
            val ranked = toolIndex.entries.mapNotNull { (id, call) ->
                // Don't even reveal other-server suggestions to a small
                // model when the user's chosen source is unambiguous.
                if (requestedSourceIds != null &&
                    call.source.id !in requestedSourceIds) return@mapNotNull null
                val name = call.tool.name.lowercase(Locale.ROOT)
                val title = call.source.title.lowercase(Locale.ROOT)
                val description = call.tool.description.lowercase(Locale.ROOT)
                val score = terms.sumOf { term ->
                    (if (name.contains(term)) 10 else 0) +
                    (if (title.contains(term)) 4 else 0) +
                    (if (description.contains(term)) 2 else 0)
                } + (if (name in terms) 22 else 0) +
                    (if (sourceHint != null && title.contains(sourceHint)) 12 else 0) +
                    (if (Regex("(^|[^\\p{L}\\p{N}_])" +
                        Regex.escape(name) + "($|[^\\p{L}\\p{N}_])",
                        RegexOption.IGNORE_CASE).containsMatchIn(q)) 20 else 0)
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
            // This is a non-executing rank hint from the real catalog only.
            // Never advertise a guess if two server tools are similarly
            // relevant. The model must still fetch DETAILS before preparing
            // tools/call, and the host validates the full source and schema.
            val top = ranked.firstOrNull()
            val next = ranked.getOrNull(1)
            val suggestedId = if (!generalOverview && top != null &&
                top.third >= 12 && (next == null || top.third - next.third >= 8) &&
                hits.any { it.first == top.first }
            ) top.first else null
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
                .put("suggested_tool_id", suggestedId ?: JSONObject.NULL)
                .put("suggestion_is_not_authorization", true)
                .put("next_step", "Lade mcp_get_tool_details mit exakter lokaler tool_id " +
                    "(optional suggested_tool_id falls eindeutig). " +
                    "Suche und Ranking sind KEIN tools/call und keine Freigabe."))
        }

        fun details(id: String, originalRequest: String? = null): JSONObject {
            enforceFresh()
            // Reject an incompatible selection before exposing the schema.
            // A deliberately wrong prefix must not silently resolve by name.
            if ('/' in id) requireRequestedSource(id.trim().substringBefore('/'),
                originalRequest)
            val requested = id.trim()
            // Small 2B/4B models sometimes drop the local s1/ prefix after
            // search. Allow only a UNIQUE exact tool name from the IDs actually
            // returned in this run. A wrong server prefix must NOT redirect.
            val canonical = when {
                requested in returned -> requested
                '/' !in requested -> returned.filter {
                    it.substringAfter('/') == requested
                }.singleOrNull()
                else -> null
            }
            require(canonical != null) {
                "tool_id wurde nicht eindeutig durch MCP-Suche angezeigt; " +
                    "verwende exakte tool_id inkl. s1/ bis s4/ aus mcp_search_tools"
            }
            val current = toolIndex[canonical] ?: error("Unbekannte tool_id")
            requireRequestedSource(current.source.id, originalRequest)
            val full = current.tool.schema
            val preview = compactSchema(full)
            require(preview.toString().length <= 950) {
                "Schema zu groß für lokales 4B-Modell, auch ohne Beschreibungen; " +
                    "Tool manuell verwenden"
            }
            // Only set the detail gate if a complete constraint-preserving
            // model-visible preview was actually possible.
            detailed.add(canonical)
            return localResult(JSONObject()
                .put("origin", "Pocket AI: echte, noch nicht ausgeführte MCP-Tooldefinition")
                .put("tool_id", canonical)
                .put("id_namespace", "local")
                .put("server", clean(current.source.title, 48))
                .put("description", clean(current.tool.description, 145))
                .put("inputSchema", preview)
                .put("inputSchema_compacted", preview.toString() != full.toString())
                .put("original_schema_bytes",
                    full.toString().toByteArray(Charsets.UTF_8).size)
                .put("readOnlyHint", current.tool.declaresReadOnly)
                .put("risk_hint_is_not_authorization", true)
                .put("next_step", "Für echtes tools/call nutze mcp_call_tool " +
                    "mit der hier genannten exakten tool_id und validiertem JSON. " +
                    "Die App prüft das vollständige Originalschema und fragt um Freigabe."))
        }

        /** Fail-closed actual tool-plan; callTool is NEVER invoked by this class. */
        fun prepareCall(
            id: String, argumentsJson: String, originalRequest: String? = null
        ): RemoteCall {
            enforceFresh()
            requireRequestedSource(id.trim().substringBefore('/'), originalRequest)
            check(remoteCalls == 0) { "Maximal ein echter MCP-Aufruf pro Auftrag" }
            require(id in returned && id in detailed) {
                "Vor dem echten Toolaufruf zuerst suchen und Details laden"
            }
            val located = toolIndex[id] ?: error("Werkzeug nicht mehr verfügbar")
            requireRequestedSource(located.source.id, originalRequest)
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

    /**
     * Short, human-readable evidence display for the user. The GGUF
     * receives the UNMODIFIED structured JSON tool result, while this
     * display never cuts JSON in the middle of a tool_id or schema.
     * Keep all six names complete and separate "catalogue" from "call".
     */
    fun displaySearchResult(result: JSONObject): String {
        val payload = result.optJSONArray("content")
            ?.optJSONObject(0)?.optString("text").orEmpty()
        if (result.optBoolean("isError", false)) {
            return "⚠️ Lokale MCP-Werkzeugsuche fehlgeschlagen: " +
                (runCatching { JSONObject(payload).optString("error") }
                    .getOrNull() ?: "Kein Ergebnis").take(180) +
                "\nKeine Serveraktion ausgeführt."
        }
        val data = runCatching { JSONObject(payload) }.getOrElse {
            return "⚠️ MCP-Werkzeugliste konnte nicht gelesen werden. " +
                "Keine Serveraktion ausgeführt."
        }
        val count = data.optInt("total_tools", 0)
        val servers = data.optInt("connected_servers", 0)
        val matches = data.optJSONArray("matches") ?: JSONArray()
        return buildString {
            append("🔎 MCP-Katalog: ")
            append(count).append(" echte Tools auf ")
                .append(servers).append(" verbundenen Servern")
            append("\nGezeigt: ").append(matches.length())
                .append(" Treffer (nur Suche, kein Tool ausgeführt)")
            for (i in 0 until matches.length()) {
                val item = matches.optJSONObject(i) ?: continue
                append("\n").append(i + 1).append(". ")
                    .append(item.optString("tool_id").take(170))
                append(" · ").append(item.optString("server").take(44))
                append("\n   ").append(item.optString("description").take(95))
            }
            append("\nFür einen echten Aufruf: Details laden und bestätigen.")
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
