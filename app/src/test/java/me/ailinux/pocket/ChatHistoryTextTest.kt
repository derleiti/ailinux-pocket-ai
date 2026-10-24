package me.ailinux.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTextTest {
    @Test fun titleIsCompactAndDoesNotContainControlCharacters() {
        assertEquals("Wichtiges Thema mit MCP", ChatHistoryText.title(" Wichtiges\nThema\tmit   MCP "))
        assertEquals("Neuer Chat", ChatHistoryText.title(" \n\t"))
        assertTrue(ChatHistoryText.title("A".repeat(400)).length <= 80)
    }
}
