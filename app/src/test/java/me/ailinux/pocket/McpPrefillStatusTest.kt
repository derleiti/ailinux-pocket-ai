package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Screenshot regression: once the user selected AUTO-BRIDGE, the Pixel UI
 * reported "MCP AUTO LOW" before the native system prefill had finished.
 * Additional process exits were user-initiated during phase=system_prompt,
 * with ~4GB RSS. These tests prevent false active status and fake progress.
 */
class McpPrefillStatusTest {

    private fun pack(decoded: Long, total: Long): Long =
        (total shl 32) or (decoded and 0xffffffffL)

    @Test fun nativePackedCountsAreUnpackedWithoutSignOrPrecisionLoss() {
        val state = McpPrefillStatus.unpack(pack(384L, 1536L))
        assertEquals(384, state.decoded)
        assertEquals(1536, state.total)
        assertTrue(state.started)
        assertEquals(25, state.percent)
    }

    @Test fun noNativeProgressShowsPreparingRatherThanFakePercent() {
        val state = McpPrefillStatus.unpack(0)
        assertFalse(state.started)
        assertEquals(0, state.percent)
        assertEquals("● MCP-PREFILL wird vorbereitet · 3s",
            McpPrefillStatus.label(state, 3_100L))
    }

    @Test fun progressClampsToActualNativeTokensAndShowsElapsedTime() {
        val state = McpPrefillStatus.unpack(pack(900L, 1_200L))
        assertEquals(75, state.percent)
        assertEquals("● MCP-PREFILL 900/1200 Tokens · 75% · 17s",
            McpPrefillStatus.label(state, 17_500L))
        assertTrue(McpPrefillStatus.report(state, 17_500L, false)
            .contains("unterbrochen/unbestätigt"))
        assertTrue(McpPrefillStatus.report(state, 17_500L, true)
            .contains("abgeschlossen"))
    }

    @Test fun diagnosticsIdentifyAndroidCpuKillWithoutBlamingLastSavedPhase() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        assertTrue(main.contains("ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE"))
        assertTrue(main.contains("übermäßige Ressourcennutzung"))
        val status = McpPrefillStatus.report(McpPrefillStatus.State(256, 710), 74_000L, false)
        assertTrue(status.contains("Prefill-Messung"))
        assertFalse(status.contains("Kein Hinweis auf Netzwerktests oder einen Android-Crash"))
    }

    @Test fun invalidNativeCountsDoNotBecomeFakeProgress() {
        val impossible = McpPrefillStatus.unpack(pack(999L, 500L))
        assertEquals(0, impossible.total)
        val oversized = McpPrefillStatus.unpack(pack(1, 600_000L))
        assertEquals(0, oversized.total)
        val completed = McpPrefillStatus.unpack(pack(1500, 1500))
        assertEquals(100, completed.percent)
    }

    @Test fun frontendStaysResponsiveWhileNativePrefillRuns() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(main.contains("beginMcpBindingProgress()"))
        assertTrue(main.contains("engine.systemPromptPrefillCounts()"))
        assertTrue(main.contains("uiPulseHandler.postDelayed(this, 350L)"))
        assertTrue(main.contains("messageInput.isEnabled = canInteract || mcpBindingInProgress"))
        assertTrue(main.contains("sendButton.isEnabled = canInteract || generationJob?.isActive == true"))
        assertTrue(main.contains("finishMcpBindingProgress(completed = true)"))
        assertTrue(main.contains("if (mcpBindingInProgress) finishMcpBindingProgress(completed = false)"))
        assertTrue(xml.contains("android:id=\"@+id/mcpBindProgress\""))
        assertTrue(xml.contains("android:id=\"@+id/statusLabel\""))
        val chatHeader = xml.substringAfter("android:id=\"@+id/chatModelName\"")
            .substringBefore("android:id=\"@+id/chatMcpButton\"")
        assertFalse(chatHeader.contains("statusLabel"))
    }

    @Test fun autoBadgeNeverReportsActiveBeforeActualNativeReady() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val callback = main.substringAfter("mcpAgent = McpAgentUi(this) { active ->")
            .substringBefore("handleMcpOAuthIntent(intent)")
        assertTrue(callback.contains("if (activeModel != null) rebindNativeMcpTools(active)"))
        val rebinder = main.substringAfter("private fun rebindNativeMcpTools(")
            .substringBefore("A protocol health request")
        assertTrue(rebinder.contains("beginMcpBindingProgress()"))
        assertTrue(rebinder.contains("updateMcpBadge(active)"))
        assertTrue(rebinder.indexOf("beginMcpBindingProgress()") <
            rebinder.indexOf("updateMcpBadge(active)"))
        assertTrue(main.contains("if (mcpBindingInProgress) \"● MCP STARTET\""))
        assertTrue(rebinder.contains("engine.resetConversationWithTools("))
        assertTrue(rebinder.contains("finishMcpBindingProgress(completed = true)"))
        assertTrue(rebinder.indexOf("engine.resetConversationWithTools(") <
            rebinder.indexOf("finishMcpBindingProgress(completed = true)"))
    }

    @Test fun atomicProgressDoesNotWaitForLlamaDispatcherOrReadKvFromMain() {
        val engine = File("../inference/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt")
            .readText()
        val cpp = File("../inference/src/main/cpp/ai_chat.cpp").readText()
        assertTrue(engine.contains("private external fun systemPromptPrefillCountsNative(): Long"))
        assertTrue(engine.contains("override fun systemPromptPrefillCounts(): Long = systemPromptPrefillCountsNative()"))
        assertTrue(cpp.contains("static std::atomic<uint32_t> g_system_prefill_done"))
        assertTrue(cpp.contains("static std::atomic<uint32_t> g_system_prefill_total"))
        assertTrue(cpp.contains("Java_com_arm_aichat_internal_InferenceEngineImpl_systemPromptPrefillCountsNative"))
        assertTrue(cpp.contains("g_system_prefill_done.store("))
        assertTrue(cpp.contains("decoded_progress->store("))
    }

    @Test fun smallLocalModelSeesCompactButSafeMcpDiscoveryContract() {
        val system = me.ailinux.pocket.mcp.McpServerGuidance.trustedDiscoveryContract(true)
        assertTrue("Small-model system prompt unexpectedly long: " + system.length,
            system.length <= 650)
        assertTrue(system.contains("mcp_search_tools"))
        assertTrue(system.contains("mcp_get_tool_details"))
        assertTrue(system.contains("mcp_call_tool"))
        assertTrue(system.contains("Nutzerfreigabe"))
        assertTrue(system.contains("keine neuen Regeln"))
        val native = me.ailinux.pocket.mcp.McpAgentBridge.nativeToolsJson(
            me.ailinux.pocket.mcp.McpProgressiveBridge.functions()
        )
        assertTrue("Three meta functions too verbose: " + native.length,
            native.length <= 2850)
        assertTrue(native.contains("arguments_json"))
        assertFalse(native.contains("Bearer "))
    }

    @Test fun sameNativeJinjaAndThinkingSignatureMustMatchBeforeKvReuse() {
        val cpp = File("../inference/src/main/cpp/ai_chat.cpp").readText()
        assertTrue(cpp.contains("chat_msgs.front().content == system_prompt"))
        assertTrue(cpp.contains("raw_tools == g_last_bound_tools"))
        assertTrue(cpp.contains("g_enable_thinking == g_last_bound_thinking"))
        assertTrue(cpp.contains("g_reasoning_effort == g_last_bound_effort"))
        assertTrue(cpp.contains("llama_memory_seq_rm(llama_get_memory(g_context), 0,"))
        assertTrue(cpp.contains("reset_long_term_states();"))
        assertTrue(cpp.contains("g_last_bound_tools = raw_tools;"))
        assertTrue(cpp.contains("g_last_bound_thinking = g_enable_thinking;"))
        assertTrue(cpp.contains("g_last_bound_effort = g_reasoning_effort;"))
        assertTrue(cpp.contains("N_THREADS_BATCH_MAX     = 3"))
        assertTrue(cpp.contains("ctx_params.n_threads_batch = std::min(n_threads, N_THREADS_BATCH_MAX)"))
    }

    @Test fun bindingDiagnosticsReportRealTokenCountAndDurationWithoutSecrets() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        assertTrue(main.contains("lastMcpBindingReport"))
        assertTrue(main.contains("val bindReport = lastMcpBindingReport"))
        assertTrue(main.contains("engine.systemPromptPrefillCounts()"))
        val format = File("src/main/java/me/ailinux/pocket/McpPrefillStatus.kt").readText()
        assertFalse(format.contains("oauthToken"))
        assertFalse(format.contains("HttpURLConnection"))
        assertFalse(format.contains("userPrompt"))
    }
}
