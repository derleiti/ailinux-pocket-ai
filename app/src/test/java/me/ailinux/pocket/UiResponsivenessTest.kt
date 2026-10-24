package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UiResponsivenessTest {
    @Test fun detectsDelayedMainLooperCallbacksWithoutClaimingCrash() {
        val watch = UiResponsiveness()
        watch.arm(1_000L)
        assertEquals(0, watch.pulse(2_200L))
        assertEquals(900, watch.pulse(4_300L))
        assertTrue(watch.report().contains("1 auffällige Verzögerungen"))
        assertTrue(watch.report().contains("größte Verzögerung 900 ms"))
        assertTrue(watch.report().contains("kein ANR-/Crash-Beweis"))
    }

    @Test fun correctlyCountsOnlyForegroundCallbackDelays() {
        val watch = UiResponsiveness()
        watch.arm(10_000L)
        watch.disarm()
        assertEquals(0, watch.pulse(100_000L))
        assertTrue(watch.report().contains("0 auffällige Verzögerungen"))
        watch.arm(101_000L)
        assertEquals(0, watch.pulse(102_200L))
        assertEquals(900, watch.pulse(104_300L))
        assertTrue(watch.report().contains("1 auffällige Verzögerungen"))
    }

    @Test fun callbackSequenceDoesNotTreatHighCpuSlowdownAsAnAndroidCrash() {
        val watch = UiResponsiveness()
        watch.arm(0)
        assertEquals(100, watch.pulse(1_300))
        assertEquals(700, watch.pulse(3_200))
        assertEquals(0, watch.pulse(4_400))
        assertTrue(watch.report().contains("bei 3 Vordergrund-Messpunkten"))
        assertFalse(watch.report().contains("crash reason: ANR"))
    }

    @Test fun appHooksMonitorOnlyInVisibleLifecycleAndNeverExportsTelemetry() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val monitor = File("src/main/java/me/ailinux/pocket/UiResponsiveness.kt").readText()
        assertTrue(main.contains("uiPulseHandler.postDelayed(uiPulse"))
        assertTrue(main.contains("uiPulseHandler.removeCallbacks(uiPulse)"))
        assertTrue(main.contains("uiResponsiveness.arm(SystemClock.uptimeMillis())"))
        assertTrue(main.contains("uiResponsiveness.disarm()"))
        assertTrue(main.contains("val uiDelayReport = uiResponsiveness.report()"))
        assertFalse(monitor.contains("HttpURLConnection"))
        assertFalse(monitor.contains("sendUserPrompt"))
        assertFalse(monitor.contains("OAuthToken"))
    }
}
