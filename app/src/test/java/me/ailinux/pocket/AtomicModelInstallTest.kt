package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

class AtomicModelInstallTest {
    @Test fun publishCreatesCompleteFileAndRemovesOnlyPartial() {
        val dir = Files.createTempDirectory("pocket-install-").toFile()
        try {
            val partial = File(dir, "model.gguf.part").apply { writeBytes(ByteArray(256) { 9 }) }
            val target = File(dir, "model.gguf")
            AtomicModelInstall.publish(partial, target)
            assertTrue(target.exists())
            assertFalse(partial.exists())
            assertEquals(256L, target.length())
        } finally { dir.deleteRecursively() }
    }

    @Test fun publishNeverOverwritesExistingModel() {
        val dir = Files.createTempDirectory("pocket-no-overwrite-").toFile()
        try {
            val original = File(dir, "model.gguf").apply { writeBytes(ByteArray(100) { 1 }) }
            val partial = File(dir, "model.gguf.part").apply { writeBytes(ByteArray(256) { 2 }) }
            assertThrows(FileAlreadyExistsException::class.java) {
                AtomicModelInstall.publish(partial, original)
            }
            assertEquals(1, original.readBytes()[0].toInt())
            assertTrue(partial.exists())
            assertEquals(256L, partial.length())
        } finally { dir.deleteRecursively() }
    }

    @Test fun atomicRenameFailurePreservesOriginalPartialAndNeverInstalls() {
        val dir = Files.createTempDirectory("pocket-atomic-unsupported-").toFile()
        try {
            val part = File(dir, "Qwen3_1.7B.litertlm.part")
                .apply { writeBytes(ByteArray(1024) { 42 }) }
            val target = File(dir, "Qwen3_1.7B.litertlm")
            val before = ModelStore.sha256(part)
            val problem = assertThrows(java.io.IOException::class.java) {
                AtomicModelInstall.publish(part, target) { from, to ->
                    throw AtomicMoveNotSupportedException(
                        from.toString(), to.toString(), "Unsupported in test")
                }
            }
            assertTrue(problem.message.orEmpty().contains("Atomarer Modellabschluss"))
            assertFalse(target.exists())
            assertTrue(part.exists())
            assertEquals(before, ModelStore.sha256(part))
        } finally { dir.deleteRecursively() }
    }

    @Test fun transientFilesystemFailureKeepsPartForExplicitRetry() {
        val dir = Files.createTempDirectory("pocket-atomic-ioerror-").toFile()
        try {
            val part = File(dir, "Gemma-4-E2B.litertlm.part")
                .apply { writeBytes(ByteArray(1024) { 19 }) }
            val installed = File(dir, "Gemma-4-E2B.litertlm")
            val hash = ModelStore.sha256(part)
            assertThrows(java.io.IOException::class.java) {
                AtomicModelInstall.publish(part, installed) { _, _ ->
                    throw java.io.IOException("Synthetic storage fault")
                }
            }
            assertFalse(installed.exists())
            assertEquals(hash, ModelStore.sha256(part))
        } finally { dir.deleteRecursively() }
    }

    @Test fun publishesMultiMegabyteModelWithoutHardLinksOrCopies() {
        val dir = Files.createTempDirectory("pocket-atomic-rename-").toFile()
        try {
            val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            val part = File(dir, "Qwen3_1.7B.litertlm.part")
                .apply { writeBytes(bytes) }
            val target = File(dir, "Qwen3_1.7B.litertlm")
            val expected = ModelStore.sha256(part)
            val installed = AtomicModelInstall.publish(part, target)
            assertEquals(target.canonicalFile, installed.canonicalFile)
            assertFalse(part.exists())
            assertEquals(bytes.size.toLong(), installed.length())
            assertEquals(expected, ModelStore.sha256(installed))
        } finally { dir.deleteRecursively() }
    }

    @Test fun partialMustRemainInTheSameDirectory() {
        val src = Files.createTempDirectory("pocket-source-").toFile()
        val dest = Files.createTempDirectory("pocket-dest-").toFile()
        try {
            val part = File(src, "part").apply { writeBytes(ByteArray(200)) }
            assertThrows(IllegalArgumentException::class.java) {
                AtomicModelInstall.publish(part, File(dest, "model.gguf"))
            }
        } finally { src.deleteRecursively(); dest.deleteRecursively() }
    }
}
