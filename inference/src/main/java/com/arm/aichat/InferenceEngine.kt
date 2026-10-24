package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws java.io.IOException if the native model loader reports an error
     */
    suspend fun loadModel(pathToModel: String)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String, toolsJson: String = "[]")

    /**
     * Reset the decoded chat prefix and bind user-selected MCP tools to the
     * model's actual Jinja system template. This clears previous chat turns.
     * Tools must be serialized OpenAI-compatible function descriptions.
     */
    suspend fun resetConversationWithTools(
        systemPrompt: String, toolsJson: String, thinkingMode: String = "off"
    )

    /**
     * Non-blocking native SYSTEM-prefill progress, packed as unsigned
     * total-token count in the upper 32 bits and decoded in the lower
     * 32 bits. May be queried while llamaDispatcher is decoding.
     * This is diagnostic-only, not the authoritative ModelReady state.
     */
    fun systemPromptPrefillCounts(): Long

    /**
     * Configure optional GGUF reasoning before the initial system prompt.
     * Use "low" for compact MCP reasoning, or "high" for full reasoning.
     * Changing modes within a chat is only safe through a full decoded
     * conversation reset with resetConversationWithTools().
     */
    suspend fun setThinkingMode(enabled: Boolean, effort: String = "high")

    /** Configure sampling for the next generation; dispatcher-serialized. */
    suspend fun setSampling(temperature: Float, topK: Int, topP: Float,
        minP: Float, repeatPenalty: Float, repeatLastN: Int)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>
    /**
     * Continue a user-approved MCP tools/call through a genuine native GGUF
     * Jinja TOOL-role turn, not through a forged user prompt. Only an exact
     * pending tool name, one result and bounded untrusted data are accepted.
     * No tool invocation occurs here; the Android MCP client does that.
     */
    fun sendToolResult(
        toolName: String, untrustedResult: String, predictLength: Int = 160
    ): Flow<String>


    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
