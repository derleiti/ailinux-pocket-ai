package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Source/manifest regression guard. The real Spark GGUF Jinja rendering is
 * independently tested by tests/native/chat_template_thinking_test.cpp.
 */
class ThinkingModeIntegrationTest {
    @Test fun thinkingSwitchDefaultsToOffInSettings() {
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        val toggle = xml.substringAfter("android:id=\"@+id/thinkingModeSwitch\"")
            .substringBefore("/>")
        assertTrue(xml.contains("android:text=\"@string/thinking_enable\""))
        assertTrue(File("src/main/res/values/strings.xml").readText()
            .contains("Denkmodus aktivieren"))
        assertTrue(toggle.contains("android:checked=\"false\""))
        assertTrue(toggle.contains("android:layout_width=\"match_parent\""))
    }

    @Test fun modeIsConfiguredBeforeSystemPrompt() {
        val kotlin = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val start = kotlin.indexOf("engine.setThinkingMode(")
        val system = kotlin.indexOf("engine.setSystemPrompt(", start)
        assertTrue("Need to choose mode before system prompt enters KV cache", start >= 0 && system > start)
        assertTrue(kotlin.contains("getBoolean(PREF_ENABLE_THINKING, false)"))
    }

    @Test fun nativeJinjaTemplateReceivesModeExplicitly() {
        val cpp = File("../inference/src/main/cpp/ai_chat.cpp").readText()
        assertTrue(cpp.contains("static bool g_enable_thinking = false;"))
        assertTrue(cpp.contains("inputs.enable_thinking = g_enable_thinking;"))
        assertTrue(cpp.contains("common_chat_templates_apply(g_chat_templates.get(), inputs)"))
        assertFalse(cpp.contains("common_chat_format_single(") &&
            cpp.contains("/* use_jinja */ true);"))
    }

    @Test fun dedicatedTabKeepsThinkingControlOutOfChat() {
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        val chatPage = xml.substringAfter("android:id=\"@+id/chatPage\"")
            .substringBefore("android:id=\"@+id/libraryPage\"")
        val settings = xml.substringAfter("android:id=\"@+id/settingsPage\"")
        assertFalse(chatPage.contains("thinkingModeSwitch"))
        assertTrue(settings.contains("thinkingModeSwitch"))
    }
}
