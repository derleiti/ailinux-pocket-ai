package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelStoreTest {
    @Test fun filenameSanitizationBlocksTraversal() {
        assertEquals("a_B.gguf", ModelStore.sanitize("../../a B.gguf"))
        assertEquals("model.gguf", ModelStore.sanitize("////"))
        assertEquals("llm.Q4_K_M.gguf", ModelStore.sanitize("llm.Q4_K_M.gguf"))
    }
    @Test fun ggufMagicAccepted() {
        val file = File.createTempFile("pocket", ".gguf")
        try {
            file.writeBytes("GGUF".toByteArray() + ByteArray(100))
            ModelStore.validate(file)
        } finally { file.delete() }
    }
    @Test fun rejectsUnrelatedFile() {
        val file = File.createTempFile("pocket", ".gguf")
        try {
            file.writeBytes("NOPE".toByteArray() + ByteArray(100))
            try {
                ModelStore.validate(file)
                fail("Should reject invalid GGUF")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("GGUF"))
            }
        } finally { file.delete() }
    }
    @Test fun litertlmMagicAndExtensionAreChecked() {
        val file = File.createTempFile("pocket", ".litertlm")
        try {
            file.writeBytes("LITERTLM".toByteArray() + ByteArray(128))
            ModelStore.validate(file)
            assertTrue(ModelStore.isModelName("gemma-4-E2B-G5.litertlm"))
            assertFalse(ModelStore.isModelName("download.sh"))
            file.writeBytes("INVALID!".toByteArray() + ByteArray(128))
            assertThrows(IllegalArgumentException::class.java) {
                ModelStore.validate(file)
            }
        } finally { file.delete() }
    }

    @Test fun interruptedSha256VerificationDoesNotClaimSuccessfulInstall() {
        val file = File.createTempFile("pocket-interrupted", ".gguf")
        try {
            file.writeBytes("GGUF".toByteArray() + ByteArray(1024))
            val stopped = java.util.concurrent.atomic.AtomicBoolean(true)
            assertThrows(TransferCancelled::class.java) {
                ModelStore.sha256(file, stopped)
            }
            assertTrue("Cancellation must not delete the usable source file", file.exists())
        } finally { file.delete() }
    }

    @Test fun onlyOneTransferCanOwnTheSamePartialAcrossWorkers() {
        val folder = java.nio.file.Files.createTempDirectory("transfer-lease-").toFile()
        try {
            val first = ModelStore.claimTransfer(folder, "Qwen3_1.7B.litertlm")
            try {
                assertThrows(java.io.IOException::class.java) {
                    ModelStore.claimTransfer(folder, "Qwen3_1.7B.litertlm")
                }
                ModelStore.claimTransfer(folder, "different.gguf").close()
            } finally { first.close() }
            ModelStore.claimTransfer(folder, "Qwen3_1.7B.litertlm").close()
        } finally { folder.deleteRecursively() }
    }

    @Test fun completedRangeIsOnlyAcceptedForExactRfc9110UnsatisfiedRangeTotal() {
        assertEquals(101L, ModelStore.completedRangeLength("bytes */101"))
        assertEquals(9876543210L, ModelStore.completedRangeLength("bytes */9876543210"))
        assertNull(ModelStore.completedRangeLength(null))
        assertNull(ModelStore.completedRangeLength("bytes 0-100/101"))
        assertNull(ModelStore.completedRangeLength("bytes */0"))
        assertNull(ModelStore.completedRangeLength("bytes */-1"))
        assertNull(ModelStore.completedRangeLength("bytes */garbage"))
    }

    @Test fun sha256OfFileIsCorrect() {
        val file = File.createTempFile("pocket", ".txt")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ModelStore.sha256(file))
        } finally { file.delete() }
    }
    @Test fun repositoryParsing() {
        val parsed = SourceCatalog.parseRepository("ggml-org/llama.cpp")
        assertEquals(RepositoryHost.GITHUB, parsed.host)
        assertEquals("ggml-org/llama.cpp", parsed.slug)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadRepository() {
        SourceCatalog.parseRepository("../bad")
    }
}
