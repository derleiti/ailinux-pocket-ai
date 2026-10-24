package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source-level guard for native SYSTEM/MCP KV rollback. */
class ThinkingRecoveryRegressionTest {
    private val cpp = File("../inference/src/main/cpp/ai_chat.cpp").readText()
    private val engine = File(
        "../inference/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
    ).readText()
    private val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()

    @Test fun onlyInterruptedReasoningRequiresNativeRecovery() {
        val block = cpp.substringAfter("Java_com_arm_aichat_internal_InferenceEngineImpl_recoverIncompleteThinkingNative(")
            .substringBefore("static int decode_tokens_in_batches(")
        assertTrue(block.contains("if (!g_incomplete_thinking_turn) return 0;"))
        assertTrue(block.contains("llama_memory_seq_rm("))
        assertTrue(block.contains("system_prompt_position, -1"))
        assertTrue(block.contains("chat_msgs.resize(1)"))
        assertTrue(block.contains("current_position = system_prompt_position;"))
        assertTrue(block.contains("g_incomplete_thinking_turn = false;"))
        assertFalse(block.contains("processToolResult("))
    }

    @Test fun conversationResetPreservesBoundSystemMcpKvWithoutReloading() {
        val block = cpp.substringAfter(
            "Java_com_arm_aichat_internal_InferenceEngineImpl_clearConversationNative("
        ).substringBefore("static int decode_tokens_in_batches(")
        assertTrue(block.contains("llama_memory_seq_rm("))
        assertTrue(block.contains("system_prompt_position, -1"))
        assertTrue(block.contains("chat_msgs.resize(1)"))
        assertTrue(block.contains("current_position = system_prompt_position"))
        assertFalse(block.contains("processSystemPrompt("))
        assertFalse(block.contains("llama_model_load_from_file("))
        assertFalse(block.contains("tools/call"))
        val reset = activity.substringAfter("private fun resetLocalConversation()")
            .substringBefore("private fun groundedSystemPrompt(")
        assertTrue(reset.contains("engine.clearConversationKeepingSystem()"))
        assertTrue(reset.contains("runtime.clearConversation()"))
        assertFalse(reset.contains("loadModel("))
        assertFalse(reset.contains("resetConversationWithTools("))
        assertFalse(reset.contains("McpConnection.callTool("))
        assertTrue(activity.contains("R.id.clearChatButton).setOnClickListener {\n            resetLocalConversation()"))
    }

    @Test fun liteRtClosesOldConversationBeforeRecreatingSession() {
        val source = File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt")
            .readText()
        val reset = source.substringAfter("suspend fun clearConversation()")
            .substringBefore("val hasConversation:")
        assertTrue(reset.indexOf("old?.close()") < reset.indexOf("engine.createConversation("))
        assertTrue(reset.contains("conversation = null"))
        assertTrue(source.contains("checkNotNull(conversation)"))
        assertTrue(source.contains("Executors.newSingleThreadExecutor"))
        assertTrue(source.contains("session.sendMessageAsync(text).collect"))
        assertTrue(source.contains("suspend fun cancelResponse()"))
        assertTrue(source.contains("conversation?.cancelProcess()"))
        assertTrue(source.contains(".flowOn(nativeDispatcher)"))
        assertTrue(source.contains("withContext(nativeDispatcher)"))
        assertTrue(source.contains("withContext(NonCancellable + nativeDispatcher)"))
        assertTrue(activity.contains("liteRtRuntime?.shutdownAsync()"))
        assertTrue(source.contains("val hasConversation: Boolean get()"))
    }

    @Test fun liteRtImportOnlyDropsPreviouslyLoadedModelAfterSuccessfulPreflight() {
        val load = activity.substringAfter("private fun loadLiteRtModel(")
            .substringBefore("Both CHAT and SETUP")
        assertTrue(load.indexOf("ModelStore.validate(file)") <
            load.indexOf("previousModelUnloaded = true"))
        assertTrue(load.contains("if (previousModelUnloaded) activeModel = null"))
    }

    @Test fun kotlinRecoversIncompleteAssistantWithoutRetryingTool() {
        assertTrue(engine.contains("private external fun recoverIncompleteThinkingNative(): Int"))
        assertTrue(engine.contains("private fun finishAndRecoverThinking(): Boolean"))
        assertTrue(engine.contains("IncompleteThinkingTurnException("))
        assertTrue(engine.contains("val error = if (e is IncompleteThinkingTurnException) e else"))
        assertTrue(engine.contains("InferenceEngine.State.ModelReady else InferenceEngine.State.Error("))
        assertTrue(activity.contains("if (error is IncompleteThinkingTurnException)"))
        assertTrue(activity.contains("pendingNativeToolTurn = null"))
        assertTrue(activity.contains("Keine externe Aktion automatisch wiederholt."))
    }
}
