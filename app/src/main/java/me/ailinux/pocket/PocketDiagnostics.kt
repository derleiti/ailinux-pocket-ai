package me.ailinux.pocket

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured host-side event trace, NOT a log of raw MCP/model content.
 * Records stages, duration, tool fingerprints, error categories and result
 * certainty, never arguments, user messages, server payloads, URLs or tokens.
 */
internal object PocketDiagnostics {
    enum class Domain { MCP, GGUF, LITERT, SYSTEM }
    enum class Step {
        REQUEST, MODEL_START, MODEL_DONE, MODEL_ERROR, MODEL_RESET, MODEL_LOAD,
        DISCOVERY_START, DISCOVERY_DONE, CATALOG_FILTERED, SEARCH, DETAILS, DETAIL_REUSE, PLAN, HOST_FALLBACK,
        HOST_WRITE_INTENT, PROPOSAL,
        APPROVAL_REQUEST, APPROVAL_ACCEPT, APPROVAL_DENY,
        AUTH_CHECK, SCHEMA_CHECK, META_ARGS_NORMALIZED, DISPATCH, SERVER_RESULT, REMOTE_ERROR,
        MODEL_FOLLOWUP, MODEL_TOOL_MISSING, NATIVE_RESPONSE_REJECTED, INPUT_REQUIRED, CANCELLED,
        ENGINE_ERROR, DIAGNOSTIC
    }
    enum class State { START, OK, INFO, REJECTED, FAIL, UNCONFIRMED }
    enum class ErrorCode {
        NONE, TIMEOUT, HTTP_AUTH, HTTP_DENIED, HTTP_FAILURE, NETWORK,
        MODEL_KV, TENSOR_BACKEND, MEMORY, CANCELLED, SCHEMA, MCP_PROTOCOL,
        NOT_FOUND, CATALOG_EXPIRED, SCHEMA_TOO_LARGE,
        AMBIGUOUS_TOOL, NO_SAFE_MATCH, UNVERIFIED_READONLY,
        ARGUMENTS_UNAVAILABLE, NATIVE_CRASH,
        INVALID_NATIVE_TOOL_NAME, INVALID_NATIVE_ARGUMENTS_JSON,
        INVALID_NATIVE_ARGUMENTS_SCHEMA, INVALID_NATIVE_ARGUMENTS_SIZE,
        INVALID_NATIVE_TOOL_COUNT, NATIVE_RESPONSE_REJECTED,
        NATIVE_SCHEMA_MISSING_REQUIRED, NATIVE_SCHEMA_UNKNOWN_FIELD,
        NATIVE_SCHEMA_WRONG_TYPE, NATIVE_SCHEMA_INVALID_VALUE,
        NATIVE_SCHEMA_OTHER, MODEL_FOLLOWUP_EMPTY, MODEL_RUNTIME, UNKNOWN
    }
    data class Event(
        val time: Long, val id: String, val trace: String,
        val domain: Domain, val step: Step, val state: State,
        val backend: String = "", val toolHash: String = "",
        val count: Int = -1, val durationMs: Long = -1,
        val code: ErrorCode = ErrorCode.NONE
    ) {
        fun json(): JSONObject = JSONObject()
            .put("ts", time).put("id", id).put("trace", trace)
            .put("domain", domain.name).put("step", step.name).put("state", state.name)
            .put("backend", backend.takeIf { it in setOf("GGUF","LITERT","CPU","GPU","TPU") }.orEmpty())
            .put("toolHash", toolHash.takeIf { it.matches(Regex("[0-9a-f]{10}")) }.orEmpty())
            .put("count", count.coerceIn(-1, 100_000))
            .put("durationMs", durationMs.coerceIn(-1L, 86_400_000L))
            .put("code", code.name)

        fun line(): String {
            val stamp = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.ROOT).format(Date(time))
            val parts = mutableListOf(
                stamp, "#" + id, trace, domain.name + "/" + step.name, state.name
            )
            if (backend.isNotBlank()) parts.add("backend=" + backend)
            if (toolHash.isNotBlank()) parts.add("tool#" + toolHash)
            if (count >= 0) parts.add("count=" + count)
            if (durationMs >= 0) parts.add("ms=" + durationMs)
            if (code != ErrorCode.NONE) parts.add("error=" + code.name)
            return parts.joinToString(" · ")
        }
    }

    fun newTrace(): String = UUID.randomUUID().toString().take(8)
    private fun newEventId() = UUID.randomUUID().toString().take(8)

    /** Deterministic IDs to correlate tools without logging server-controlled text. */
    fun fingerprint(toolName: String): String =
        MessageDigest.getInstance("SHA-256").digest(toolName.toByteArray(Charsets.UTF_8))
            .take(5).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Original exception message is used ONLY to classify then discarded. */
    fun classify(error: Throwable): ErrorCode {
        // A wrapper marks the uncertain dispatch, but the original root
        // exception is still useful for classification without raw logging.
        if (error is me.ailinux.pocket.mcp.McpExecutionUncertain &&
            error.cause != null) return classify(error.cause!!)
        if (error is kotlinx.coroutines.CancellationException) return ErrorCode.CANCELLED
        if (error is OutOfMemoryError || error is ModelMemoryGuardException)
            return ErrorCode.MEMORY
        val klass = error.javaClass.simpleName.lowercase(Locale.ROOT)
        val message = error.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            "katalog ist abgelaufen" in message -> ErrorCode.CATALOG_EXPIRED
            "schema zu groß" in message || "schema too large" in message ->
                ErrorCode.SCHEMA_TOO_LARGE
            "tool_id wurde noch nicht" in message || "unbekannte tool_id" in message ->
                ErrorCode.NOT_FOUND
            error is org.json.JSONException -> ErrorCode.SCHEMA
            "no kv cache inputs found" in message -> ErrorCode.MODEL_KV
            ("google_tensor" in message || "tensor" in message) &&
                ("not_found" in message || "create engine" in message) -> ErrorCode.TENSOR_BACKEND
            "outofmemory" in klass || "memory allocation" in message ||
                "low memory" in message || "cannot allocate memory" in message -> ErrorCode.MEMORY
            "timeout" in klass || "timed out" in message || "timeout" in message -> ErrorCode.TIMEOUT
            Regex("""http\s*401|(^|[^0-9])401([^0-9]|$)""").containsMatchIn(message) -> ErrorCode.HTTP_AUTH
            Regex("""http\s*403|(^|[^0-9])403([^0-9]|$)""").containsMatchIn(message) -> ErrorCode.HTTP_DENIED
            Regex("""http\s*[45][0-9]{2}""").containsMatchIn(message) -> ErrorCode.HTTP_FAILURE
            "ssl" in klass || "socket" in klass || "unknownhost" in klass ||
                "connection" in message -> ErrorCode.NETWORK
            "schema" in message || "parameter" in message || "argument" in message -> ErrorCode.SCHEMA
            "jsonrpc" in message || "tools/list" in message || "mcp" in message -> ErrorCode.MCP_PROTOCOL
            "not found" in message || "existiert nicht" in message -> ErrorCode.NOT_FOUND
            "signal 9" in message || "sigabrt" in message || "jni" in message -> ErrorCode.NATIVE_CRASH
            "litert" in message || "inference" in message ||
                "generate" in message -> ErrorCode.MODEL_RUNTIME
            else -> ErrorCode.UNKNOWN
        }
    }

    /** Modern MCP resultType=complete/input_required, legacy isError. */
    fun resultPhase(result: JSONObject): Pair<Step, State> {
        val type = result.optString("resultType")
        return when {
            type == "input_required" -> Step.INPUT_REQUIRED to State.INFO
            type.isNotBlank() && type != "complete" -> Step.REMOTE_ERROR to State.FAIL
            result.optBoolean("isError", false) -> Step.REMOTE_ERROR to State.FAIL
            else -> Step.SERVER_RESULT to State.OK
        }
    }

    fun publicError(error: Throwable): String =
        error.javaClass.simpleName.take(45) + " / " + classify(error).name

    fun parse(json: JSONObject): Event? = runCatching {
        val id = json.getString("id").takeIf { it.matches(Regex("[a-f0-9]{8}")) }
            ?: return null
        val trace = json.getString("trace").takeIf { it.matches(Regex("[a-f0-9]{8}")) }
            ?: return null
        val time = json.getLong("ts").takeIf { it > 0 } ?: return null
        Event(time, id, trace,
            Domain.valueOf(json.getString("domain")),
            Step.valueOf(json.getString("step")),
            State.valueOf(json.getString("state")),
            json.optString("backend").takeIf { it in setOf("GGUF","LITERT","CPU","GPU","TPU") }.orEmpty(),
            json.optString("toolHash").takeIf { it.matches(Regex("[0-9a-f]{10}")) }.orEmpty(),
            json.optInt("count", -1).coerceIn(-1,100_000),
            json.optLong("durationMs",-1).coerceIn(-1L,86_400_000L),
            ErrorCode.valueOf(json.optString("code",ErrorCode.NONE.name))
        )
    }.getOrNull()

    private val io = Dispatchers.IO.limitedParallelism(1)
    // Persisted events may complete out of scheduling order. Each newly
    // observed event gets a distinct monotonic timestamp, even when many
    // events happen during the same millisecond (Android device clocks).
    private val clock = AtomicLong(0L)
    internal fun nextTimestamp(): Long {
        while (true) {
            val old = clock.get()
            val next = maxOf(System.currentTimeMillis(), old + 1)
            if (clock.compareAndSet(old, next)) return next
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lock = Any()
    private const val MAX_EVENTS = 240
    private const val MAX_BYTES = 180_000
    private var cache: MutableList<Event>? = null
    private fun file(context: Context) =
        AtomicFile(File(context.applicationContext.filesDir, "pocket_diagnostic_events.json"))

    private fun readLocked(context: Context): MutableList<Event> {
        cache?.let { return it }
        val result = mutableListOf<Event>()
        try {
            val bytes = file(context).openRead().use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size <= MAX_BYTES) {
                val json = JSONArray(bytes.toString(Charsets.UTF_8))
                for (i in maxOf(0, json.length()-MAX_EVENTS) until json.length()) {
                    json.optJSONObject(i)?.let { item -> parse(item)?.let(result::add) }
                }
            }
        } catch (_: FileNotFoundException) {
            // First run.
        } catch (_: Exception) {
            // Corrupt private file must not crash inference or expose contents.
        }
        result.maxOfOrNull { it.time }?.let { latest ->
            clock.getAndUpdate { maxOf(it, latest) }
        }
        cache = result
        return result
    }

    private fun persistLocked(context: Context, events: List<Event>) {
        val atomic = file(context)
        val bytes = JSONArray().apply { events.forEach { put(it.json()) } }
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun appendLocked(context: Context, event: Event) {
        val events = readLocked(context)
        events.add(event)
        events.sortWith(compareBy<Event> { it.time }.thenBy { it.id })
        while (events.size > MAX_EVENTS) events.removeAt(0)
        persistLocked(context, events)
    }

    /**
     * Durable pre-dispatch marker: MUST be invoked on a background thread
     * just before a possible side-effecting tools/call.
     * When Android kills the process after transmission, the trace still
     * shows that the call was attempted, even without a server receipt.
     */
    fun markDispatch(context: Context, trace: String, tool: String): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "Never flush diagnostic files on Android UI thread"
        }
        val event = Event(nextTimestamp(), newEventId(),
            trace.takeIf { it.matches(Regex("[0-9a-f]{8}")) } ?: newTrace(),
            Domain.MCP, Step.DISPATCH, State.START,
            toolHash = fingerprint(tool))
        synchronized(lock) { appendLocked(context, event) }
        return event.id
    }

    /**
     * Nonblocking on main thread. Events are bounded and atomically written
     * in private app storage. No logcat, clipboard or network exfiltration.
     */
    fun log(
        context: Context, trace: String, domain: Domain, step: Step,
        state: State = State.INFO, backend: String = "", tool: String = "",
        count: Int = -1, durationMs: Long = -1, code: ErrorCode = ErrorCode.NONE
    ): String {
        val id = newEventId()
        val event = Event(nextTimestamp(), id,
            trace.takeIf { it.matches(Regex("[0-9a-f]{8}")) } ?: newTrace(),
            domain, step, state, backend,
            if (tool.isNotBlank()) fingerprint(tool) else "",
            count, durationMs, code)
        scope.launch {
            synchronized(lock) { runCatching { appendLocked(context, event) } }
        }
        return id
    }

    fun failure(context: Context, trace: String, domain: Domain, step: Step,
                error: Throwable, uncertain: Boolean = false): String =
        log(context, trace, domain, step,
            if (uncertain) State.UNCONFIRMED else State.FAIL, code = classify(error))

    /**
     * Newest evidence comes first. Android accessibility often returns only
     * the first part of long TextViews, so chronological rendering hid the
     * most important DISPATCH / SERVER_RESULT evidence during live Pixel QA.
     * Limiting the prefix (not the suffix) preserves the newest events.
     */
    internal fun recentLines(events: List<Event>, maxChars: Int = 25_000): String =
        events.asReversed().joinToString("\n") { it.line() }
            .take(maxChars.coerceIn(0, 25_000))

    suspend fun report(context: Context): String = withContext(io) {
        synchronized(lock) {
            val events = readLocked(context).takeLast(MAX_EVENTS)
            val header = "Pocket AI · MCP/MODELL-DIAGNOSE (lokal)\n" +
                "SEARCH/DETAILS sind keine Serveraktion. Nur SERVER_RESULT nach " +
                "DISPATCH zeigt eine empfangene tools/call-Antwort. " +
                "UNCONFIRMED ist kein bestätigter Erfolg.\n" +
                "Keine Prompts, Tokens, Endpunkte, Parameter, Toolresultat-Texte oder " +
                "Stacktraces gespeichert.\nNeueste Ereignisse zuerst. Ereignisse: " +
                events.size + "/" + MAX_EVENTS + "\n\n"
            header + if (events.isEmpty()) "Keine Ereignisse erfasst."
                else recentLines(events)
        }
    }

    suspend fun clear(context: Context) = withContext(io) {
        synchronized(lock) {
            cache = mutableListOf()
            file(context).delete()
        }
    }
}
