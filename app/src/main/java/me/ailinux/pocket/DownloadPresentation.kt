package me.ailinux.pocket

/**
 * Pure formatting helpers: never call Android View APIs here.
 * Partial files may still be actively growing, so .part != interrupted.
 */
object DownloadPresentation {
    fun isActivePartial(partName: String, activeModel: String?, transferring: Boolean) =
        transferring && activeModel != null && partName == "$activeModel.part"

    fun partialStatus(name: String, bytes: Long, active: Boolean): String =
        (if (active) "↓ Läuft gerade: " else "↓ Unterbrochen: ") + name +
            " (" + ModelStore.displaySize(bytes) + ")"

    fun progress(name: String?, progress: TransferProgress): String {
        val display = ModelStore.displaySize(progress.done) + " / " +
            (if (progress.total > 0) ModelStore.displaySize(progress.total) else "Größe unbekannt") +
            (if (progress.percent >= 0) "  •  " + progress.percent + "%" else "")
        return if (name.isNullOrEmpty()) display else name + "\n" + display
    }
}
