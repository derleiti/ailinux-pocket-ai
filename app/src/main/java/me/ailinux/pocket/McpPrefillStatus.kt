package me.ailinux.pocket

/** Pure, user-visible progress adapter for native SYSTEM token prefill. */
internal object McpPrefillStatus {
    data class State(val decoded: Int, val total: Int) {
        val started: Boolean get() = total > 0
        val percent: Int get() =
            if (total > 0) ((decoded.toLong() * 100L) / total).toInt().coerceIn(0, 100)
            else 0
    }

    /** Native JNI jlong: high 32 bits total, low 32 bits decoded. */
    fun unpack(packed: Long): State {
        val total = packed ushr 32
        val decoded = packed and 0xffffffffL
        if (total > 500_000L || decoded > total) return State(0, 0)
        return State(decoded.toInt(), total.toInt())
    }

    fun label(state: State, elapsedMs: Long): String {
        val seconds = elapsedMs.coerceAtLeast(0) / 1000L
        return if (state.started) {
            "● MCP-PREFILL " + state.decoded + "/" + state.total + " Tokens" +
                " · " + state.percent + "% · " + seconds + "s"
        } else {
            "● MCP-PREFILL wird vorbereitet · " + seconds + "s"
        }
    }

    fun report(state: State, elapsedMs: Long, completed: Boolean): String =
        "MCP-BINDUNG: " +
            (if (completed) "abgeschlossen" else "unterbrochen/unbestätigt") +
            "; " + state.decoded + "/" + state.total + " Systemtokens; " +
            (elapsedMs.coerceAtLeast(0) / 1000L) + "s gemessen. " +
            "Kein Hinweis auf Netzwerktests oder einen Android-Crash."
}
