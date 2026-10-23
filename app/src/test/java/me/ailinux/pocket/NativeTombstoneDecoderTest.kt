package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

class NativeTombstoneDecoderTest {
    private fun varint(value: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        var n = value
        do {
            val b = (n and 0x7f).toInt()
            n = n ushr 7
            bytes.write(if (n == 0L) b else b or 0x80)
        } while (n > 0L)
        return bytes.toByteArray()
    }

    private fun field(number: Int, value: ByteArray): ByteArray =
        varint((number * 8 + 2).toLong()) + varint(value.size.toLong()) + value

    private fun text(number: Int, value: String) =
        field(number, value.toByteArray(Charsets.UTF_8))

    private fun int(number: Int, value: Long) =
        varint((number * 8).toLong()) + varint(value)

    private fun sample(): ByteArray {
        val signal = int(1, 6) + text(2, "SIGABRT") + text(4, "SI_TKILL")
        val frame1 = int(1, 0x1234) + text(4, "ggml_backend_assert") +
            text(6, "/data/app/libggml.so")
        val frame2 = int(1, 0x4567) + text(4, "llama_model_load_from_file") +
            text(6, "/data/app/libllama.so")
        val thread = int(1, 321) + text(2, "llama-inference") +
            field(4, frame1) + field(4, frame2)
        val threadMap = int(1, 321) + field(2, thread)
        return text(4, "2026-10-23 22:01:04") + int(6, 321) +
            field(10, signal) + text(14, "Check failed: native assert") +
            field(16, threadMap)
    }

    @Test fun parsesSignalAbortAndFaultingThread() {
        val diagnostic = NativeTombstoneDecoder.decode(sample())
        assertTrue(diagnostic.contains("SIGABRT (6)"))
        assertTrue(diagnostic.contains("Check failed: native assert"))
        assertTrue(diagnostic.contains("llama-inference"))
        assertTrue(diagnostic.contains("ggml_backend_assert"))
        assertTrue(diagnostic.contains("libllama.so"))
        assertTrue(diagnostic.contains("#00"))
        assertTrue(diagnostic.contains("#01"))
    }

    @Test fun ignoresUnknownMessageFields() {
        val diagnostic = NativeTombstoneDecoder.decode(field(90, byteArrayOf(1, 2, 3)) + sample())
        assertTrue(diagnostic.contains("SIGABRT"))
    }

    @Test fun handlesEmptyStreamGracefully() {
        assertEquals("Tombstone-Datei ist leer.",
            NativeTombstoneDecoder.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun rejectsCorruptLengthsWithoutCrash() {
        val invalid = byteArrayOf(0x72, 0x80.toByte(), 0x7f)
        val result = NativeTombstoneDecoder.read(ByteArrayInputStream(invalid))
        assertTrue(result.contains("nicht vollständig"))
    }

    @Test fun skipsMemoryDumpAndReturnsDiagnostic() {
        val memory = ByteArray(1200)
        val diagnostic = NativeTombstoneDecoder.decode(sample() + field(17, memory))
        assertTrue(diagnostic.contains("SIGABRT"))
        assertFalse(diagnostic.contains("memory_dump"))
    }
}
