package me.ailinux.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrumoSystemPromptTest {
    @Test fun newInstallGetsBrumo() {
        val prompt = BrumoSystemPrompt.resolve(null)
        assertTrue(prompt.startsWith("Du bist Brumo"))
        assertTrue(prompt.contains("Ursache → Lösung → Test"))
        assertTrue(prompt.contains("MCP-Werkzeuge"))
    }

    @Test fun stockRcPromptGetsBrumoWithoutClobberingPreferences() {
        assertEquals(BrumoSystemPrompt.DEFAULT,
            BrumoSystemPrompt.resolve(BrumoSystemPrompt.LEGACY_DEFAULT))
    }

    @Test fun keepCustomSystemPrompts() {
        val custom = "Du bist mein lokaler Entwicklerassistent."
        assertEquals(custom, BrumoSystemPrompt.resolve(custom))
        assertEquals("", BrumoSystemPrompt.resolve(""))
    }
}
