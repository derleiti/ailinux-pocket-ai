package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
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
