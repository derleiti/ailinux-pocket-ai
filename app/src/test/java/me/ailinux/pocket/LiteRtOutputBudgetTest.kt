package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiteRtOutputBudgetTest {
    @Test fun modelPlanningUsesBoundedTokensWithoutPretendingToBeAWallClockTimeout() {
        assertEquals(256, LiteRtOutputBudget.forSession(true))
        assertEquals(768, LiteRtOutputBudget.forSession(false))
        assertTrue(LiteRtOutputBudget.MAX_MCP_TOKENS <
            LiteRtOutputBudget.MAX_CHAT_TOKENS)
        assertTrue(LiteRtOutputBudget.MAX_CHAT_TOKENS <= 1024)
    }

    @Test fun nativeRuntimeUsesLimitsForInitialMcpRebindAndReset() {
        val source = File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt").readText()
        assertTrue(source.contains("val outputLimit = LiteRtOutputBudget.forSession(session != null)"))
        assertTrue(source.contains("maxOutputToken = outputLimit"))
        assertTrue(source.contains("val nextOutputLimit = LiteRtOutputBudget.forSession(session != null)"))
        assertTrue(source.contains("maxOutputToken = nextOutputLimit"))
        assertTrue(source.contains("maxOutputToken = currentMaxOutputTokens"))
        assertTrue(source.contains("currentMaxOutputTokens = nextOutputLimit"))
        assertTrue(source.contains("currentMaxOutputTokens = outputLimit"))
        // No coroutine watchdog falsely claims to interrupt blocked JNI.
        assertFalse(source.contains("withTimeout("))
    }
}
