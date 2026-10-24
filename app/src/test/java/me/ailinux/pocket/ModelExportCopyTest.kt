package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class ModelExportCopyTest {
    @Test fun copiesLargeModelInBoundedChunksAndReportsFinalSize() {
        val content = "GGUF".toByteArray() + ByteArray(2 * 1024 * 1024 + 313) { (it % 251).toByte() }
        val result = ByteArrayOutputStream()
        val progress = mutableListOf<TransferProgress>()
        val done = ModelExportCopy.copy(
            ByteArrayInputStream(content), result, content.size.toLong(),
            AtomicBoolean(false), progress::add
        )
        assertEquals(content.size.toLong(), done)
        assertArrayEquals(content, result.toByteArray())
        assertEquals(content.size.toLong(), progress.last().done)
        assertEquals(100, progress.last().percent)
        assertTrue(progress.size >= 2)
    }

    @Test fun cancelBeforeCopyDoesNotWriteAnyBytes() {
        val result = ByteArrayOutputStream()
        assertThrows(TransferCancelled::class.java) {
            ModelExportCopy.copy(ByteArrayInputStream(ByteArray(64)), result, 64,
                AtomicBoolean(true)) {}
        }
        assertEquals(0, result.size())
    }

    @Test fun cancellationDuringExportFailsClosedWithPartialOutput() {
        val result = ByteArrayOutputStream()
        val cancelled = AtomicBoolean(false)
        val content = ByteArray(4 * 1024 * 1024)
        assertThrows(TransferCancelled::class.java) {
            ModelExportCopy.copy(
                ByteArrayInputStream(content), result, content.size.toLong(),
                cancelled
            ) {
                if (it.done >= 1024 * 1024) cancelled.set(true)
            }
        }
        assertTrue(result.size() < content.size)
    }

    @Test fun mismatchedLengthMustNotBePresentedAsCompleteBackup() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelExportCopy.copy(
                ByteArrayInputStream(ByteArray(37)), ByteArrayOutputStream(),
                38L, AtomicBoolean(false)
            ) {}
        }
    }

    @Test fun androidUsesSafCreateDocumentNotBroadStoragePermissions() {
        val activity = java.io.File("src/main/java/me/ailinux/pocket/MainActivity.kt")
            .readText()
        val store = java.io.File("src/main/java/me/ailinux/pocket/ModelStore.kt")
            .readText()
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(activity.contains(
            "ActivityResultContracts.CreateDocument(\"application/octet-stream\")"
        ))
        assertTrue(activity.contains("modelExportPicker.launch(file.name)"))
        assertTrue(activity.contains("override fun onSaveInstanceState(outState: Bundle)"))
        assertTrue(activity.contains("pocket_pending_export_model"))
        assertTrue(activity.contains("private fun exportModel(file: File, destination: Uri)"))
        assertTrue(activity.contains("store.export(file, destination, cancelTransfer)"))
        assertTrue(store.contains("contentResolver.openOutputStream(destination, \"w\")"))
        assertTrue(store.contains("ModelExportCopy.copy(input, target, length"))
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"))
    }

    @Test fun outputStreamFailurePropagatesInsteadOfClaimingSuccess() {
        val failed = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("storage full")
            }
        }
        val ex = assertThrows(IOException::class.java) {
            ModelExportCopy.copy(
                ByteArrayInputStream(ByteArray(200)), failed,
                200L, AtomicBoolean(false)
            ) {}
        }
        assertEquals("storage full", ex.message)
    }
}
