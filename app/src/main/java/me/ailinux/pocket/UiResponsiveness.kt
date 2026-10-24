package me.ailinux.pocket

/**
 * In-memory main-looper response indicator, not an ANR/crash diagnosis.
 * A late scheduled callback can indicate UI contention. Background
 * intervals are excluded. No prompts, tokens, model names, or personal
 * data are sampled or transmitted.
 */
class UiResponsiveness {
    companion object {
        const val INTERVAL_MS = 1200L
        const val LONG_DELAY_MS = 650L
    }

    private var nextExpectedAtMs: Long = -1L
    private var sampled = 0
    private var delayed = 0
    private var maxDelayMs = 0L
    private var lastDelayMs = 0L

    fun arm(nowMs: Long) {
        nextExpectedAtMs = nowMs + INTERVAL_MS
    }

    /** Called on the main looper after scheduled Handler dispatch. */
    fun pulse(nowMs: Long): Long {
        val due = nextExpectedAtMs
        if (due < 0) return 0
        nextExpectedAtMs = nowMs + INTERVAL_MS
        val behind = nowMs - due
        if (behind < 0) return 0
        sampled++
        if (behind >= LONG_DELAY_MS) {
            delayed++
            lastDelayMs = behind
            maxDelayMs = maxOf(maxDelayMs, behind)
        }
        return behind
    }

    fun disarm() {
        // Sleep, backgrounding and opening OAuth in the browser cannot
        // create false-positive delay samples.
        nextExpectedAtMs = -1L
    }

    fun report(): String = "UI-RESPONSE-DIAGNOSE (nur laufender App-Prozess): " +
        "$delayed auffällige Verzögerungen ab $LONG_DELAY_MS ms " +
        "bei $sampled Vordergrund-Messpunkten; " +
        "größte Verzögerung $maxDelayMs ms; letzte $lastDelayMs ms. " +
        "Dies ist kein ANR-/Crash-Beweis und enthält keine Nutzerdaten."
}
