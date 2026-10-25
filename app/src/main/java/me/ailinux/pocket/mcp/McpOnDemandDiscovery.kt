package me.ailinux.pocket.mcp

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Engine-independent fallback for models that reply with prose instead of tool calls.
 * Uses only live authorized tools/list snapshots. No side effects; the caller must
 * seek fresh consent and use the existing revalidated tools/call dispatcher.
 */
object McpOnDemandDiscovery {
    private val words = Regex("[\\p{L}\\p{N}_-]{3,}")
    private val stop = setOf("bitte","einen","einem","einer","diese","deine","deinen",
        "meine","meinen","mcp","tool","tools","verwende","benutze","nutze","zeige",
        "check","checke","prüfe","pruefe","über","ueber","with","using","the","und",
        "eine","einen","server","status","www","https")
    private val webUrl = Regex("https://[^\\s<>\"\']+", RegexOption.IGNORE_CASE)

    /** Crawl is a server-side job creation, NOT a read-only page fetch.
     * The host may offer a human-reviewed plan only for a verbatim explicit
     * crawl request with exactly one safe HTTPS URL. The server still checks
     * authorization, schema and network policy before creating a job.
     */
    private val crawlVerb = Regex(
        "\\b(?:crawl|crawle|crawlen|crawlt|crawler)\\b", RegexOption.IGNORE_CASE)
    private fun explicitCrawlUrl(request: String): String? {
        if (!crawlVerb.containsMatchIn(request)) return null
        val urls = webUrl.findAll(request).map {
            it.value.trimEnd('.', ',', ';', ')', ']', '}')
        }.toList()
        if (urls.size != 1) return null
        val url = urls.single()
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.userInfo != null ||
            parsed.host.isNullOrBlank() || parsed.port !in setOf(-1,443)) return null
        val host = parsed.host.lowercase(Locale.ROOT)
        if (host in setOf("localhost", "127.0.0.1", "::1", "[::1]") ||
            host.endsWith(".local") || host.endsWith(".localhost")) return null
        if (host.matches(Regex("[0-9.]+"))) {
            // Do not propose requests to numeric private/loopback hosts.
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return null
            if (octets[0] in setOf(0,10,127) || octets[0] >= 224 ||
                octets[0] == 192 && octets[1] == 168 ||
                octets[0] == 172 && octets[1] in 16..31 ||
                octets[0] == 169 && octets[1] == 254) return null
        }
        return url
    }

    /** Run immediately before dispatch as well as during planning. */
    fun isVerifiedExplicitCrawlPlan(plan: McpProgressiveBridge.RemoteCall): Boolean {
        if (!plan.hostFallback || !plan.hostWriteIntent ||
            plan.tool.name != "crawl" || plan.arguments.length() != 1) return false
        val required = plan.tool.schema.optJSONArray("required") ?: return false
        if (required.length() != 1 || required.optString(0) != "url") return false
        if (plan.tool.schema.optJSONObject("properties")?.
                optJSONObject("url")?.optString("type") != "string") return false
        val url = plan.arguments.optString("url")
        return explicitCrawlUrl("crawl " + url) == url &&
            McpToolSchema.validate(plan.tool.schema, plan.arguments) == null
    }

    /** Server hints do not turn the server-side crawler job into a read. */
    fun isVerifiedReadOnlyHostPlan(plan: McpProgressiveBridge.RemoteCall): Boolean =
        plan.hostFallback && !plan.hostWriteIntent &&
            plan.tool.declaresReadOnly &&
            plan.tool.name in setOf("status", "discord_connection_status",
                "current_time", "models", "device_info")

    private val explicitTool = Regex("(?:tool|werkzeug|function)[\\s:='\"\\u0060]+([a-z][a-z0-9_.-]{2,})", RegexOption.IGNORE_CASE)
    private val mentionsExternal = Regex(
        "\\b(?:mcp|tool|werkzeug|triforce|discord|workspace|worspace|" +
            "arbeitsbereich|crawl|status|api|server)\\b", RegexOption.IGNORE_CASE)
    // "per MCP tool call" describes the protocol, NOT an explicit tool
    // called "call". Keep explicit function names only when meaningful.
    private val genericActionWords = setOf(
        "call", "calling", "aufruf", "ausführen", "ausfuehren",
        "ausführung", "ausfuehrung", "request", "invoke", "toolcall",
        "aktion", "action", "method",
        // Grammar immediately following "status-Werkzeug und frage ..." is
        // NOT an explicitly named tool. A real exact function identifier
        // (including underscores, such as discord_connection_status) stays.
        "und", "and", "oder", "or", "dann", "danach", "frage",
        "nach", "bitte", "wieder", "erneut", "jetzt", "vor",
        "mit", "per", "über", "ueber", "auf", "an", "bei",
        "für", "fuer", "von", "dem", "den", "die", "das",
        "ein", "einem", "einen", "einer", "eines"
    )
    private val titleTokens = Regex("[\\p{L}\\p{N}]{3,}")
    private val genericServerWords = setOf("ailinux", "mcp", "server",
        "endpoint", "api", "tool", "tools", "service", "services")

    /**
     * When multiple authorized MCP servers publish "status", a free-text
     * request must identify a provider through a distinctive title token.
     * Never pick the first status tool from an arbitrary source.
     * The server field is from the authentic catalog, not model prose.
     */
    private fun disambiguateServer(
        candidates: List<JSONObject>, request: String
    ): JSONObject? {
        val requestedTokens = titleTokens.findAll(request.lowercase(Locale.ROOT))
            // Single observed typo, not fuzzy matching: no source selection
            // based on distant spellings or arbitrary similar server names.
            .map { if (it.value == "worspace") "workspace" else it.value }
            .toSet()
        fun ownTokens(candidate: JSONObject) =
            titleTokens.findAll(candidate.optString("server").lowercase(Locale.ROOT))
                .map { it.value }.filter { it !in genericServerWords }.toSet()
        val matches = candidates.filter { candidate ->
            val others = candidates.filterNot { it === candidate }
                .flatMap { ownTokens(it) }.toSet()
            ownTokens(candidate).any { token ->
                token in requestedTokens && token !in others
            }
        }
        return matches.singleOrNull()
    }


    /** Safe, credential-free reason why host planning cannot suggest a tool. */
    enum class Rejection {
        EXPLANATION_ONLY, NO_EXTERNAL_TASK, NO_CATALOG_MATCH,
        AMBIGUOUS_TOOL, UNVERIFIED_READONLY, NO_SAFE_MATCH, ARGUMENTS_UNAVAILABLE,
        TOOL_DETAILS_UNAVAILABLE
    }

    // The host knows only a small audited set of read-only contracts.
    // This selects a candidate name, NOT a permission to execute it.
    private fun intendedSafeTool(request: String): String? {
        val lower = request.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(?:crawl|crawle|crawlen)\\b").containsMatchIn(lower) &&
                webUrl.containsMatchIn(request) -> "crawl"
            Regex("\\bdiscord\\b").containsMatchIn(lower) &&
                Regex("\\b(?:status|verbindung|verbindungsstatus|verbunden|connection|connectionstatus|connected|linked)\\b")
                    .containsMatchIn(lower) -> "discord_connection_status"
            Regex("\\b(?:status|systemstatus|systemzustand|serverstatus|serverzustand)\\b")
                .containsMatchIn(lower) &&
                Regex("\\b(?:mcp|triforce|tri-force|ailinux|workspace|" +
                    "worspace|arbeitsbereich|server)\\b")
                    .containsMatchIn(lower) -> "status"
            else -> null
        }
    }

    /**
     * Tiny LiteRT models can produce prose instead of native function calls
     * after a previous tool turn. The host may prepare a proposal from the
     * actual manually selected tools, but MUST NOT dispatch it automatically.
     *
     * Synthetic s1 is a local index, not a server-issued tool identifier.
     * All risk/schema checks reuse the conservative progressive planner.
     */
    fun proposeSelected(
        request: String,
        active: McpAgentSession
    ): McpAgentBridge.Decision.Proposed? {
        if (McpRequestRouting.isExplanationOnly(request)) return null
        if (active.progressiveSources.isNotEmpty() || active.tools.isEmpty()) return null
        if (active.tools.map { it.name }.distinct().size != active.tools.size) return null
        val source = McpProgressiveBridge.Source(
            id = "s1", profileId = active.profileId,
            endpoint = active.endpoint, title = active.serverName,
            tools = active.tools
        )
        val proposal = runCatching {
            propose(request, McpProgressiveBridge.Run(listOf(source)))
        }.getOrNull() ?: return null
        // Selected-mode fallback has a separate read-only-only executor.
        // A crawler job MUST go through the progressive one-shot write dialog.
        if (!proposal.hostFallback || proposal.hostWriteIntent) return null
        return McpAgentBridge.Decision.Proposed(
            proposal.tool, JSONObject(proposal.arguments.toString())
        )
    }

    /**
     * Use only an already detailed local ID after a malformed native call.
     * Never accept guessed arguments, model prose, a stale catalog, or a
     * source chosen by the model instead of the user's original instruction.
     */
    fun proposeAfterDetails(
        request: String,
        run: McpProgressiveBridge.Run,
        onRejected: (Rejection) -> Unit = {}
    ): McpProgressiveBridge.RemoteCall? {
        if (McpRequestRouting.isExplanationOnly(request)) {
            onRejected(Rejection.EXPLANATION_ONLY)
            return null
        }
        if (!mentionsExternal.containsMatchIn(request)) {
            onRejected(Rejection.NO_EXTERNAL_TASK)
            return null
        }
        val intent = intendedSafeTool(request)
        if (intent == null) {
            onRejected(Rejection.NO_SAFE_MATCH)
            return null
        }
        val selected = try {
            run.uniqueDetailedForRequest(intent, request)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (selected == null) {
            onRejected(Rejection.AMBIGUOUS_TOOL)
            return null
        }
        return safeProposal(request, run, selected.toolId, onRejected)
    }

    private fun safeProposal(
        request: String,
        run: McpProgressiveBridge.Run,
        id: String,
        onRejected: (Rejection) -> Unit
    ): McpProgressiveBridge.RemoteCall? {
        fun reject(why: Rejection): McpProgressiveBridge.RemoteCall? {
            onRejected(why)
            return null
        }
        val chosen = run.tool(id) ?: return reject(Rejection.NO_CATALOG_MATCH)
        if (!run.uniquelyRequestedSource(id, request))
            return reject(Rejection.AMBIGUOUS_TOOL)
        val tool = chosen.tool
        val schema = tool.schema
        val args = JSONObject()
        val writeCrawl = tool.name == "crawl"
        if (writeCrawl) {
            // A real TriForce crawl creates a persistent crawler job. Never
            // reinterpret "readOnlyHint" as authorization for the job.
            val exactUrl = explicitCrawlUrl(request)
                ?: return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            val required = schema.optJSONArray("required")
                ?: return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            if (required.length() != 1 || required.optString(0) != "url" ||
                schema.optJSONObject("properties")?.optJSONObject("url")?.
                    optString("type") != "string")
                return reject(Rejection.ARGUMENTS_UNAVAILABLE)
            args.put("url", exactUrl)
        } else {
            // The host is not permitted to infer arbitrary writes. Even
            // read-only candidates require fresh user approval and a live
            // schema/authorization check before the network call.
            if (tool.name !in setOf("status", "discord_connection_status",
                    "current_time", "models", "device_info") ||
                !tool.declaresReadOnly)
                return reject(Rejection.UNVERIFIED_READONLY)
            val mandatory = schema.optJSONArray("required")
            if (mandatory != null && mandatory.length() > 0) {
                if (mandatory.length() != 1)
                    return reject(Rejection.ARGUMENTS_UNAVAILABLE)
                val field = mandatory.optString(0)
                if (field !in setOf("url", "uri", "target_url"))
                    return reject(Rejection.ARGUMENTS_UNAVAILABLE)
                val prop = schema.optJSONObject("properties")?.optJSONObject(field)
                    ?: return reject(Rejection.ARGUMENTS_UNAVAILABLE)
                if (prop.optString("type") != "string")
                    return reject(Rejection.ARGUMENTS_UNAVAILABLE)
                val url = explicitCrawlUrl(request)
                    ?: return reject(Rejection.ARGUMENTS_UNAVAILABLE)
                args.put(field, url)
            }
        }
        // Enforces the previous search/details gate, original source
        // selection, exact full schema and per-turn single-dispatch state.
        return try {
            run.prepareCall(id, args.toString(), request).copy(
                hostFallback = true, hostWriteIntent = writeCrawl)
        } catch (_: Exception) {
            reject(Rejection.ARGUMENTS_UNAVAILABLE)
        }
    }

    fun propose(
        request: String,
        run: McpProgressiveBridge.Run,
        onRejected: (Rejection) -> Unit = {}
    ): McpProgressiveBridge.RemoteCall? {
        fun reject(why: Rejection): McpProgressiveBridge.RemoteCall? {
            onRejected(why)
            return null
        }
        if (McpRequestRouting.isExplanationOnly(request))
            return reject(Rejection.EXPLANATION_ONLY)
        if (!mentionsExternal.containsMatchIn(request))
            return reject(Rejection.NO_EXTERNAL_TASK)
        // A natural-language crawl task necessarily needs a URL. Malformed
        // text such as "httpsailinux.me" must produce a parameter error,
        // NOT an ambiguous-tool hint and NEVER a guessed remote URL.
        if (Regex("\\b(?:crawl|crawle|crawlen)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(request) && !webUrl.containsMatchIn(request))
            return reject(Rejection.ARGUMENTS_UNAVAILABLE)
        val terms = words.findAll(request.lowercase(Locale.ROOT)).map { it.value }
            .filter { it !in stop && !it.all(Char::isDigit) }.distinct().take(10).toList()
        val namedAlternatives = explicitTool.findAll(request)
            .map { it.groupValues[1].lowercase(Locale.ROOT) }
            .filterNot {
                it in setOf("https", "http", "url", "website") ||
                    it in genericActionWords
            }
            .distinct().take(2).toList()
        // An instruction mentioning two explicit tool alternatives is NOT
        // permission for the host to arbitrarily choose the first one.
        if (namedAlternatives.size > 1)
            return reject(Rejection.AMBIGUOUS_TOOL)
        val requestedName = namedAlternatives.singleOrNull()
            ?: intendedSafeTool(request)
        val query = requestedName ?: terms.joinToString(" ").take(120)
        if (query.isBlank()) return reject(Rejection.NO_CATALOG_MATCH)
        val response = run.search(query, originalRequest = request)
        if (response.optBoolean("isError"))
            return reject(Rejection.NO_CATALOG_MATCH)
        val payload = response.optJSONArray("content")?.optJSONObject(0)?.optString("text")
            ?: return reject(Rejection.NO_CATALOG_MATCH)
        val matches = JSONObject(payload).optJSONArray("matches")
            ?: return reject(Rejection.NO_CATALOG_MATCH)
        val candidates = (0 until matches.length()).mapNotNull { matches.optJSONObject(it) }
        if (candidates.isEmpty())
            return reject(Rejection.NO_CATALOG_MATCH)
        // Explicit function name or unambiguous single result only.
        val chosen = when {
            requestedName != null -> {
                val exact = candidates.filter {
                    it.optString("name").equals(requestedName, ignoreCase = true)
                }
                when (exact.size) {
                    0 -> null
                    1 -> exact.single()
                    else -> {
                        // Don't infer a unique tool when search(limit=6)
                        // might have omitted another server's same-name tool.
                        val totalExact = run.sources.sumOf { source ->
                            source.tools.count {
                                it.name.equals(requestedName, ignoreCase = true)
                            }
                        }
                        if (totalExact == exact.size)
                            disambiguateServer(exact, request)
                        else null
                    }
                }
            }
            candidates.size == 1 -> candidates.single()
            else -> candidates.filter { candidate ->
                val name = candidate.optString("name")
                val escaped = Regex.escape(name)
                Regex("(?i)(?:^|[^a-z0-9_])" + escaped + "(?:$|[^a-z0-9_])")
                    .containsMatchIn(request)
            }.singleOrNull()
        } ?: return reject(Rejection.AMBIGUOUS_TOOL)
        val id = chosen.optString("tool_id")
        if (!run.uniquelyRequestedSource(id, request))
            return reject(Rejection.AMBIGUOUS_TOOL)
        val detail = run.details(id, request)
        if (detail.optBoolean("isError"))
            return reject(Rejection.TOOL_DETAILS_UNAVAILABLE)
        return safeProposal(request, run, id, onRejected)
    }
}
