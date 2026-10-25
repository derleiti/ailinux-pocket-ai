package me.ailinux.pocket

import java.io.File

/**
 * Pure presentation of Android scheduler/journal state. No Android APIs are
 * called here; OS reasons are mapped by BackgroundModelDownloads.snapshot().
 * This does not infer a crash, network failure or successful transfer.
 */
internal object DownloadSchedulerState {
    enum class Reason {
        NETWORK, BACKGROUND_RESTRICTION, DEVICE_STATE,
        STORAGE, OPTIMIZATION, USER, EXECUTING, UNKNOWN
    }

    /** Only booleans: no SSID, IP, domain, interface name or network ID. */
    data class NetworkInfo(
        val validated: Boolean,
        val hasInternet: Boolean,
        val wifi: Boolean,
        val vpn: Boolean,
        val metered: Boolean
    ) {
        fun diagnostic(): String =
            "validiert=" + validated + ", Internet-Fähigkeit=" + hasInternet +
            ", WLAN=" + wifi + ", VPN=" + vpn + ", getaktet=" + metered
    }

    data class View(
        val label: String,
        val canResume: Boolean = false,
        val requiresConfirmation: Boolean = false,
        val waitingForNetwork: Boolean = false,
        val active: Boolean = false
    )

    fun describe(
        name: String,
        state: String,
        wifiOnly: Boolean,
        hasJob: Boolean?,
        reasons: Set<Reason>,
        elapsedSinceUpdateMs: Long,
        done: Long,
        total: Long,
        error: String,
        network: NetworkInfo? = null,
        completedFilePresent: Boolean? = null
    ): View {
        val suffix = if (done > 0)
            " · " + ModelStore.displaySize(done) +
                (if (total > 0) " / " + ModelStore.displaySize(total) else "")
        else ""
        return when (state) {
            "paused" -> View("⏸ Pausiert: " + name + suffix, canResume = true)
            "failed" -> View("⚠ Fehlgeschlagen: " + name + " · " +
                    error.ifBlank { "Ursache nicht protokolliert" }.take(125),
                canResume = true)
            "complete" -> when (completedFilePresent) {
                true -> View("✓ Download abgeschlossen · Modelldatei vorhanden " +
                    "(Größe geprüft, kein erneuter SHA-256-Test): " + name)
                false -> View("⚠ Downloadjournal abgeschlossen, aber Modelldatei fehlt " +
                    "oder Größe weicht ab: " + name)
                null -> View("ⓘ Downloadjournal abgeschlossen; Dateibestand ungeprüft: " + name)
            }
            "verifying", "running" -> if (hasJob == false &&
                elapsedSinceUpdateMs >= 15_000L)
                View("⚠ Android-Job verloren (z. B. nach App-Update): " + name + suffix,
                    canResume = true)
            else if (state == "verifying") View("Prüfung läuft: " + name + suffix, active = true)
            else View("Download läuft: " + name + suffix, active = true)
            "queued" -> when {
                hasJob == false -> View(
                    "⚠ Android-Job verloren (z. B. nach App-Update): " + name + suffix,
                    canResume = true)
                Reason.NETWORK in reasons && wifiOnly -> View(
                    "Wartet auf WLAN: " + name + suffix, waitingForNetwork = true, active = true)
                Reason.NETWORK in reasons && network?.validated == true ->
                    View("Wartet auf Netzwerk-Anforderung trotz aktiver Internetverbindung: " +
                        name + suffix, waitingForNetwork = true, active = true)
                Reason.NETWORK in reasons -> View(
                    "Wartet auf Netzwerk: " + name + suffix, waitingForNetwork = true, active = true)
                Reason.EXECUTING in reasons -> View(
                    "Android-Job gestartet, Status wird aktualisiert: " + name + suffix,
                    active = true)
                Reason.STORAGE in reasons -> View(
                    "Android wartet auf genügend freien Speicher: " + name + suffix,
                    active = true)
                Reason.BACKGROUND_RESTRICTION in reasons ||
                    Reason.DEVICE_STATE in reasons || Reason.USER in reasons ->
                    View("Android hält den Download zurück: " + name + suffix, active = true)
                Reason.OPTIMIZATION in reasons -> View(
                    "Android verschiebt den Download: " + name + suffix,
                    canResume = elapsedSinceUpdateMs >= 60_000L,
                    requiresConfirmation = true, active = true)
                else -> View("Wartet auf Android-Scheduler: " + name + suffix,
                    canResume = elapsedSinceUpdateMs >= 60_000L && hasJob == true,
                    requiresConfirmation = hasJob == true, active = true)
            }
            else -> View("Downloadzustand unbekannt: " + name + suffix)
        }
    }
}

/** Read-only, path-safe disk evidence; historical complete is not proof of
 * present installation. Never removes models, journals, or partial downloads.
 */
internal object CompletedModelEvidence {
    fun present(directory: File, name: String, completedBytes: Long): Boolean {
        if (name != ModelStore.sanitize(name) || !ModelStore.isModelName(name)) return false
        val file = File(directory, name)
        return file.isFile && file.length() > 32 &&
            (completedBytes <= 0 || file.length() == completedBytes)
    }
}
