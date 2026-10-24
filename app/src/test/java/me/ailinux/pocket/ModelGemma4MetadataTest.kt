package me.ailinux.pocket

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelGemma4MetadataTest {
    @Test fun gemma4WithoutContextIsRejectedBeforeNativeModelLoad() {
        val initial = ModelCompatibility.classify("gemma4", "gemna-4-tiny.gguf")
        val checked = ModelCompatibility.verifyEssentialMetadata(initial, null)
        assertFalse(checked.canAttemptTextInference)
        assertTrue(checked.incompatibleReason.orEmpty().contains("gemma4.context_length"))
        assertTrue(checked.incompatibleReason!!.contains("nicht automatisch"))
        assertFalse(ModelCompatibility.verifyEssentialMetadata(initial, 0)
            .canAttemptTextInference)
    }

    @Test fun validContextMetadataIsNotRejectedByThisSpecificRule() {
        val original = ModelCompatibility.classify("gemma4")
        for (ctx in listOf(8192, 131072, 262144)) {
            assertTrue(ModelCompatibility.verifyEssentialMetadata(original, ctx)
                .canAttemptTextInference)
        }
        assertTrue(ModelCompatibility.verifyEssentialMetadata(
            ModelCompatibility.classify("qwen3"), null
        ).canAttemptTextInference)
    }

    @Test fun nativeErrorGetsActionableNotGuessedExplanation() {
        val exact = "error loading model hyperparameters: key not found in model: gemma4.context_length"
        val diagnosis = ModelCompatibility.explainMissingGemma4Context(exact)
        assertNotNull(diagnosis)
        assertTrue(diagnosis!!.contains("kein Thinking-, RAM- oder MCP-Fehler"))
        assertFalse(diagnosis.contains("262144"))
        assertNull(ModelCompatibility.explainMissingGemma4Context(
            "llama_load_model_from_file: out of memory"))
    }

    private fun i32(n: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun i64(n: Long): ByteArray = ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
    private fun str(s: String): ByteArray =
        i64(s.toByteArray().size.toLong()) + s.toByteArray()

    private fun fixture(includeContext: Boolean): File {
        val path = File.createTempFile("gemma4-metadata-test-", ".gguf")
        path.deleteOnExit()
        val output = ByteArrayOutputStream()
        output.write("GGUF".toByteArray(Charsets.US_ASCII))
        output.write(i32(3))
        output.write(i64(0))
        output.write(i64(if (includeContext) 3 else 2))
        output.write(str("general.architecture"))
        output.write(i32(8))
        output.write(str("gemma4"))
        // The structured GGUF reader includes ArchitectureInfo only if
        // general.file_type (or another architecture parameter) is present.
        output.write(str("general.file_type"))
        output.write(i32(4))
        output.write(i32(1))
        if (includeContext) {
            output.write(str("gemma4.context_length"))
            output.write(i32(4))
            output.write(i32(131072))
        }
        path.writeBytes(output.toByteArray())
        return path
    }

    @Test fun actualGgufMetadataParserDetectsMissingContextFromHeader() = runBlocking {
        val result = ModelCompatibility.inspect(fixture(false))
        assertEquals("gemma4", result.architecture)
        assertFalse(result.canAttemptTextInference)
        assertTrue(result.incompatibleReason.orEmpty().contains("gemma4.context_length"))
    }

    @Test fun actualGgufMetadataParserAcceptsContextKeyForLaterNativeValidation() = runBlocking {
        val result = ModelCompatibility.inspect(fixture(true))
        assertEquals("gemma4", result.architecture)
        assertTrue(result.canAttemptTextInference)
    }
}
