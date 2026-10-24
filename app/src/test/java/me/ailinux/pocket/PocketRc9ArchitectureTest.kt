package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Keep durable transfers and chat history independent of Activity cancellation. */
class PocketRc9ArchitectureTest {
    private val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
    private val downloads = File("src/main/java/me/ailinux/pocket/ModelBackgroundDownloads.kt")
        .readText()
    private val database = File("src/main/java/me/ailinux/pocket/PocketChatHistory.kt")
        .readText()
    private val layout = File("src/main/res/layout/activity_main.xml").readText()

    @Test fun modelDownloadDoesNotUseActivityCoroutineScope() {
        val method = main.substringAfter("private fun startDownload(model: RemoteModel")
            .substringBefore("private fun browseRepository()")
        assertTrue(method.contains("BackgroundModelDownloads.start(this, model, wifiOnly)"))
        assertFalse(method.contains("lifecycleScope.launch"))
        assertFalse(method.contains("store.download("))
        assertTrue(downloads.contains(".setUserInitiated(true)"))
        assertTrue(downloads.contains("OneTimeWorkRequestBuilder<ModelTransferWorker>()"))
        assertTrue(downloads.contains("JOB_END_NOTIFICATION_POLICY_REMOVE"))
        assertTrue(downloads.contains("ExistingWorkPolicy.REPLACE"))
    }

    @Test fun cancelledGenerationNeverCancelsModelTransfers() {
        val stop = main.substringAfter("override fun onStop()").substringBefore("companion object")
        assertTrue(stop.contains("generationJob?.cancel()"))
        assertFalse(stop.contains("BackgroundModelDownloads.pause"))
        assertTrue(downloads.contains("ModelDownloadJournal.change"))
        assertTrue(downloads.contains("TransferNotification"))
    }

    @Test fun durableChatsHaveSeparateMetadataAndMessages() {
        assertTrue(database.contains("@Database(entities"))
        assertTrue(database.contains("onDelete = ForeignKey.CASCADE"))
        assertTrue(database.contains("suspend fun saveSnapshot"))
        assertTrue(database.contains("suspend fun archive"))
        assertTrue(database.contains("suspend fun pin"))
        assertTrue(database.contains("suspend fun search"))
        assertTrue(main.contains("private fun persistChatNow()"))
        assertTrue(main.contains("private fun restoreChatHistory()"))
        assertTrue(main.contains("private fun beginFreshConversation()"))
    }

    @Test fun chatDrawerHasVisibleButtonAndSwipeCapableRightEdge() {
        assertTrue(layout.contains("androidx.drawerlayout.widget.DrawerLayout"))
        assertTrue(layout.contains("android:id=\"@+id/chatHistoryButton\""))
        assertTrue(layout.contains("android:id=\"@+id/newConversationButton\""))
        assertTrue(layout.contains("android:id=\"@+id/chatHistorySearch\""))
        assertTrue(layout.contains("android:layout_gravity=\"end\""))
        assertTrue(main.contains("historyDrawer.openDrawer(GravityCompat.END)"))
    }

    @Test fun resumableDownloadsNeverOverwriteExistingModel() {
        val store = File("src/main/java/me/ailinux/pocket/ModelStore.kt").readText()
        assertTrue(store.contains("require(!target.exists())"))
        assertTrue(store.contains("If-Range"))
        assertTrue(store.contains("validator != null || sha256?"))
        assertTrue(database.contains("PocketDeletedConversation"))
        assertTrue(database.contains("if (isDeleted(id)) return"))
        assertTrue(store.contains("Content-Range"))
        assertTrue(store.contains("AtomicModelInstall.publish(temp, target)"))
        val atomic = File("src/main/java/me/ailinux/pocket/AtomicModelInstall.kt").readText()
        assertTrue(atomic.contains("Files.createLink(target.toPath(), verifiedPart.toPath())"))
        assertFalse(store.contains("check(temp.renameTo(target))"))
        assertTrue(store.contains("validatorFile.delete()"))
        val worker = File("src/main/java/me/ailinux/pocket/ModelBackgroundDownloads.kt")
            .readText()
        assertTrue(worker.contains("if (it.state == \"running\") it.copy(done = p.done, total = p.total)"))
    }
}
