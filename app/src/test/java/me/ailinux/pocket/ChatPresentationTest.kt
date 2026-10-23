package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChatPresentationTest {
    @Test fun showsOnlyLoadedModelName() {
        assertEquals("Spark-X2.6-4B-Q8_0",
            ChatPresentation.modelLabel("Spark-X2.6-4B-Q8_0.gguf"))
    }

    @Test fun showsPlaceholderWithoutActiveModel() {
        assertEquals("Kein Modell geladen", ChatPresentation.modelLabel(null))
        assertEquals("Kein Modell geladen", ChatPresentation.modelLabel(""))
        assertTrue(ChatPresentation.emptyHint(false).contains("MODELLE"))
    }

    @Test fun promptsForChatAfterModelLoads() {
        assertTrue(ChatPresentation.emptyHint(true).contains("bereit"))
        assertTrue(ChatPresentation.inputHint(true).contains("Frag"))
    }

    @Test fun modelSelectorIsAbsentFromChatLayout() {
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        val chat = xml.substringAfter("android:id=\"@+id/chatPage\"")
            .substringBefore("android:id=\"@+id/libraryPage\"")
        assertTrue(chat.contains("@+id/chatModelName"))
        assertTrue(chat.contains("@+id/chatMessages"))
        assertFalse(chat.contains("@+id/heroAction"))
        assertFalse(chat.contains("Modell auswählen"))
        assertFalse(chat.contains("Modell wechseln"))
        val models = xml.substringAfter("android:id=\"@+id/libraryPage\"")
            .substringBefore("android:id=\"@+id/settingsPage\"")
        assertTrue(models.contains("@+id/modelsContainer"))
        assertTrue(models.contains("@+id/importButton"))
        assertTrue(models.contains("@+id/browseButton"))
    }
}
