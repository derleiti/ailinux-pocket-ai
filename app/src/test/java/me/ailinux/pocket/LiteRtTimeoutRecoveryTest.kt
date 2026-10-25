package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class LiteRtTimeoutRecoveryTest {
    private val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt")
        .readText()

    @Test fun timeoutIsAnErrorNotNativeCrashNorMcpServerSuccess() {
        assertEquals(PocketDiagnostics.ErrorCode.TIMEOUT,
            PocketDiagnostics.classify(IOException("LiteRT timed out")))
        assertNotEquals(PocketDiagnostics.ErrorCode.TIMEOUT,
            PocketDiagnostics.classify(IOException("GOOGLE_TENSOR NOT_FOUND create engine")))
    }

    @Test fun timedOutTurnClearsOnlyNativeConversationWithoutRepeatingToolCall() {
        val session = activity.substringAfter("private fun sendLiteRtMessage(")
            .substringBefore("private fun exportModel(")
        val recovery = session.substringAfter("if (PocketDiagnostics.classify(error) ==")
            .substringBefore("} else {")
        assertTrue(recovery.contains("PocketDiagnostics.ErrorCode.TIMEOUT"))
        assertTrue(recovery.contains("runtime.clearConversation()"))
        assertTrue(recovery.contains("PocketDiagnostics.Step.MODEL_RESET"))
        assertTrue(recovery.contains("Kein automatischer MCP-Retry"))
        assertTrue(recovery.contains("Serverquittungen bleiben gültig"))
        assertFalse(recovery.contains("mcpAgent.execute"))
        assertFalse(recovery.contains("mcpAgent.executeAuto"))
        assertFalse(recovery.contains("runtime.askMcp"))
        assertFalse(recovery.contains("runtime.toolFeedback"))
        assertFalse(recovery.contains("startDownload"))
    }
}
