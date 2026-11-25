package me.ailinux.pocket

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufProjectorPreflightTest {
    private fun le32(n: Int) =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun le64(n: Long) =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
    private fun str(s: String) = le64(s.toByteArray().size.toLong()) + s.toByteArray()

    private fun fixture(architecture: String, name: String): File {
        val file = File.createTempFile(name, ".gguf")
        val b = ByteArrayOutputStream()
        b.write("GGUF".toByteArray(Charsets.US_ASCII))
        b.write(le32(3)) // v3
        b.write(le64(0L)) // no tensor data required for metadata preflight
        b.write(le64(1L)) // ONLY architecture: regression for sparse metadata
        b.write(str("general.architecture"))
        b.write(le32(8)) // GGUF string
        b.write(str(architecture))
        file.writeBytes(b.toByteArray())
        return file
    }

    @Test fun sparseMetadataArchitectureMustStillBeRetained() = runBlocking {
        val clip = fixture("clip", "unnamed-encoder")
        val qwen = fixture("qwen3", "unnamed-llm")
        try {
            val clipInspection = ModelCompatibility.inspect(clip)
            assertEquals("clip", clipInspection.architecture)
            assertTrue(clipInspection.projectorOnly)
            assertFalse(clipInspection.canAttemptTextInference)
            val qwenInspection = ModelCompatibility.inspect(qwen)
            assertEquals("qwen3", qwenInspection.architecture)
            assertFalse(qwenInspection.projectorOnly)
            assertTrue(qwenInspection.canAttemptTextInference)
        } finally {
            clip.delete()
            qwen.delete()
        }
    }

    @Test fun projectorsAreRejectedBeforeUnloadingPreviouslyLoadedModels() {
        val code = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val library = code.substringAfter("private fun refreshLibrary()")
            .substringBefore("private fun loadModel(")
        assertTrue(library.contains("val projectorAsset = ModelCompatibility.isProjectorFilename"))
        assertTrue(library.contains("!completedMismatch && !projectorAsset"))
        val load = code.substringAfter("private fun loadModel(")
            .substringBefore("private fun loadLiteRtModel(")
        assertTrue(load.indexOf("if (ModelCompatibility.isProjectorFilename(file.name))") <
            load.indexOf("previous.shutdown()"))
        assertTrue(load.indexOf("if (inspection.projectorOnly)") <
            load.indexOf("previous.shutdown()"))
        assertTrue(load.contains("ModelMemoryGuardException("))
        assertTrue(load.contains("afterUnload.lowMemory"))
        assertTrue(load.indexOf("GgufLoadFailure.Phase.MEMORY_PREFLIGHT") <
            load.indexOf("engine.loadModel(file.absolutePath)"))
    }
}
