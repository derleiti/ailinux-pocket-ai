package me.ailinux.pocket

import android.app.ApplicationExitInfo

/** Classifies OS exit evidence, never a guessed root cause from a stale marker. */
internal object ModelExitEvidence {
    private const val MAX_MARKER_AGE_MS = 6L * 60 * 60 * 1000
    private const val CLOCK_TOLERANCE_MS = 5_000L

    enum class Kind {
        LOW_MEMORY, NATIVE_CRASH, JVM_CRASH, ANR, SIGKILL,
        OTHER_SIGNAL, RESOURCE_LIMIT, INITIALIZATION_FAILURE,
        PACKAGE_UPDATED, USER_REQUESTED, SELF_EXIT, UNKNOWN
    }

    data class Match(val kind: Kind, val status: Int) {
        fun explanation(): String = when (kind) {
            Kind.LOW_MEMORY -> "Android meldet Speichermangel (Low Memory)."
            Kind.NATIVE_CRASH -> "Android meldet einen nativen C++/JNI-Absturz."
            Kind.JVM_CRASH -> "Android meldet einen Java/Kotlin-Absturz."
            Kind.ANR -> "Android meldet einen ANR (App reagierte nicht)."
            Kind.SIGKILL -> "Android meldet SIGKILL (Signal 9). Speicherdruck ist möglich, aber nicht bewiesen."
            Kind.OTHER_SIGNAL -> "Android meldet einen signalbedingten Abbruch (Status $status); Ursache offen."
            Kind.RESOURCE_LIMIT -> "Android meldet übermäßige Ressourcennutzung."
            Kind.INITIALIZATION_FAILURE -> "Android meldet eine Prozess-Initialisierungsstörung."
            Kind.PACKAGE_UPDATED -> "Android meldet ein App-Update; das ist kein Crash."
            Kind.USER_REQUESTED -> "Android meldet eine Nutzeraktion; kein Crash-Nachweis."
            Kind.SELF_EXIT ->
                "Android meldet einen vom App-Prozess selbst ausgelösten Exit (Status $status); " +
                    "kein Crash-Nachweis."
            Kind.UNKNOWN -> "Android kennt keinen eindeutig zuordenbaren Exit-Grund."
        }
    }

    fun classify(reason: Int, status: Int): Kind = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> Kind.LOW_MEMORY
        ApplicationExitInfo.REASON_CRASH_NATIVE -> Kind.NATIVE_CRASH
        ApplicationExitInfo.REASON_CRASH -> Kind.JVM_CRASH
        ApplicationExitInfo.REASON_ANR -> Kind.ANR
        ApplicationExitInfo.REASON_SIGNALED -> if (status == 9) Kind.SIGKILL else Kind.OTHER_SIGNAL
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Kind.RESOURCE_LIMIT
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Kind.INITIALIZATION_FAILURE
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> Kind.PACKAGE_UPDATED
        ApplicationExitInfo.REASON_EXIT_SELF -> Kind.SELF_EXIT
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED -> Kind.USER_REQUESTED
        else -> Kind.UNKNOWN
    }

    /** Timestamp is required to avoid attributing unrelated historical exits.
     * An old RC's marker without a timestamp intentionally remains unclassified.
     */
    fun correlate(
        startedAtMs: Long, exitAtMs: Long, nowMs: Long,
        reason: Int, status: Int
    ): Match? {
        if (startedAtMs <= 0 || nowMs < startedAtMs ||
            nowMs - startedAtMs > MAX_MARKER_AGE_MS ||
            exitAtMs < startedAtMs || exitAtMs > nowMs + CLOCK_TOLERANCE_MS) return null
        return Match(classify(reason, status), status)
    }

    fun newestExitSummary(reason: Int, status: Int): String =
        Match(classify(reason, status), status).explanation() + " [Android-Code $reason, Status $status]"
}
