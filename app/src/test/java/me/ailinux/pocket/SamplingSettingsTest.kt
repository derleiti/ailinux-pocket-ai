package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class SamplingSettingsTest {
    @Test fun balancedDefaultsAndPresets() {
        val baseline = SamplingSettings.parse(SamplingSettings().asStrings())
        assertEquals(0.7f, baseline.temperature)
        assertEquals(1.15f, baseline.repeatPenalty)
        assertEquals(128, baseline.repeatLastN)
        assertEquals(3, SamplingSettings.presets.size)
    }

    @Test fun rejectsInvalidAndNonFiniteValues() {
        val source = SamplingSettings().asStrings()
        val cases = listOf(
            0 to "NaN", 0 to "-0.1", 1 to "0",
            2 to "1.1", 3 to "0.9", 4 to "0.9", 5 to "513"
        )
        for ((index, value) in cases) {
            val modified = source.toMutableList()
            modified[index] = value
            try {
                SamplingSettings.parse(modified)
                fail("Expected rejection for index $index and value $value")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun allSamplingControlsReachNativeImplementation() {
        val ui = java.io.File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val bridge = java.io.File("../inference/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt").readText()
        val native = java.io.File("../inference/src/main/cpp/ai_chat.cpp").readText()
        assertTrue(ui.contains("engine.setSampling("))
        assertTrue(ui.contains("applySampling()"))
        assertTrue(bridge.contains("configureSamplingNative("))
        assertTrue(native.contains("Java_com_arm_aichat_internal_InferenceEngineImpl_configureSamplingNative"))
        assertTrue(native.contains("sparams.penalty_repeat = g_repeat_penalty"))
        assertTrue(native.contains("sparams.penalty_last_n = g_repeat_last_n"))
    }
}
