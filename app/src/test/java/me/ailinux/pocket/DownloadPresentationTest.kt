package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class DownloadPresentationTest {
    @Test fun inProgressPartIsNotInterrupted() {
        val file = "Spark-X2.6-4B-Q8_0.gguf"
        assertTrue(DownloadPresentation.isActivePartial("$file.part", file, true))
        assertFalse(DownloadPresentation.isActivePartial("$file.part", file, false))
        assertFalse(DownloadPresentation.isActivePartial("$file.part", "other.gguf", true))
    }

    @Test fun activePartialShowsRunningLabel() {
        val message = DownloadPresentation.partialStatus("Spark-X2.6-4B-Q8_0.gguf", 2_330_000_000, true)
        assertTrue(message.startsWith("↓ Läuft gerade:"))
        assertTrue(message.contains("Spark-X2.6"))
        assertFalse(message.contains("Unterbrochen"))
    }

    @Test fun pausedPartShowsInterrupted() {
        val message = DownloadPresentation.partialStatus("test.gguf", 100L, false)
        assertTrue(message.contains("Unterbrochen"))
    }

    @Test fun progressShowsModelNameAndPercentage() {
        val label = DownloadPresentation.progress(
            "Spark-X2.6-4B-Q8_0.gguf", TransferProgress(2_650_000_000, 4_070_000_000)
        )
        assertTrue(label.contains("Spark-X2.6-4B-Q8_0.gguf"))
        assertTrue(label.contains("65%"))
    }

    @Test fun initialProgressHandlesUnknownSize() {
        val label = DownloadPresentation.progress("test.gguf", TransferProgress(1024L, -1))
        assertTrue(label.contains("Größe unbekannt"))
        assertFalse(label.contains("%"))
    }
}
