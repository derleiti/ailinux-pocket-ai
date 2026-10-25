package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiteRtSlowPlanTest {
    @Test fun softNoticeRequiresLongActivePendingNativeTurn() {
        val limit = LiteRtSlowPlan.NOTICE_AFTER_MS
        assertEquals(75_000L, limit)
        assertFalse(LiteRtSlowPlan.shouldWarn(1_000L, 1_000L + limit - 1L, true))
        assertTrue(LiteRtSlowPlan.shouldWarn(1_000L, 1_000L + limit, true))
        assertTrue(LiteRtSlowPlan.shouldWarn(1_000L, 1_000L + limit + 15_000, true))
        assertFalse(LiteRtSlowPlan.shouldWarn(1_000L, 1_000L + limit, false))
        assertFalse(LiteRtSlowPlan.shouldWarn(1_000L, 100L, true))
        assertFalse(LiteRtSlowPlan.shouldWarn(-1L, Long.MAX_VALUE, true))
    }

    @Test fun uiOnlyMonitorsInitialNativePlanAndAlwaysClearsWatcher() {
        val source = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val bridge = source.substringAfter("private suspend fun runLiteRtToolbridge(")
            .substringBefore("private fun sendLiteRtMessage(")
        assertTrue(bridge.contains("LiteRtSlowPlan.NOTICE_AFTER_MS"))
        assertTrue(bridge.contains("slowPlanHandler.postDelayed"))
        assertTrue(bridge.contains("initialNativePlanPending = false"))
        assertTrue(bridge.contains("slowPlanHandler.removeCallbacks(slowPlanNotice)"))
        assertTrue(bridge.contains("PocketDiagnostics.Step.MODEL_SLOW"))
        assertTrue(bridge.contains("runtime.askMcp(request)"))
        val notice = bridge.substringAfter("val slowPlanNotice = Runnable")
            .substringBefore("slowPlanHandler.postDelayed")
        assertFalse(notice.contains("cancelProcess("))
        assertFalse(notice.contains("shutdown("))
        assertFalse(notice.contains("executeAuto("))
        assertFalse(notice.contains("mcpAgent.execute("))
        assertFalse(notice.contains("tools/call"))
    }

    @Test fun diagnosticIsInformationalAndNotAMisleadingTimeoutOrReceipt() {
        val event = PocketDiagnostics.Event(
            1780000000000L, "aa12bb34", "a1b2c3d4",
            PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_SLOW,
            PocketDiagnostics.State.INFO, durationMs = LiteRtSlowPlan.NOTICE_AFTER_MS
        )
        val line = event.line()
        assertTrue(line.contains("MODEL_SLOW"))
        assertTrue(line.contains("INFO"))
        assertFalse(line.contains("TIMEOUT"))
        assertFalse(line.contains("SERVER_RESULT"))
        assertEquals(LiteRtSlowPlan.NOTICE_AFTER_MS,
            PocketDiagnostics.parse(event.json())!!.durationMs)
    }
}
