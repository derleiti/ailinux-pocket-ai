package me.ailinux.pocket

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded streaming model copy for Android's user-selected SAF document.
 * This never buffers a full multi-gigabyte GGUF in RAM. It is independent
 * of the Android URI API so interrupted copies are unit-testable on the JVM.
 */
object ModelExportCopy {
    fun copy(
        source: InputStream,
        target: OutputStream,
        total: Long,
        cancelled: AtomicBoolean,
        progress: (TransferProgress) -> Unit
    ): Long {
        require(total >= 0L) { "Unknown model length" }
        val buffer = ByteArray(128 * 1024)
        var written = 0L
        var sinceUpdate = 0L
        while (true) {
            if (cancelled.get()) throw TransferCancelled()
            val bytes = source.read(buffer)
            if (bytes < 0) break
            if (bytes == 0) continue
            target.write(buffer, 0, bytes)
            written += bytes
            sinceUpdate += bytes
            if (sinceUpdate >= 1024 * 1024) {
                progress(TransferProgress(written, total))
                sinceUpdate = 0L
            }
        }
        target.flush()
        require(written == total) { "Modelldatei hat sich während des Exports geändert" }
        progress(TransferProgress(written, total))
        return written
    }
}
