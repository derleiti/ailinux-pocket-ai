package com.arm.aichat.internal

import android.content.Context
import android.app.ActivityManager
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper for the llama.cpp library providing Android-friendly access to large language models.
 *
 * This class implements a singleton pattern for managing the lifecycle of a single LLM instance.
 * All operations are executed on a dedicated single-threaded dispatcher to ensure thread safety
 * with the underlying C++ native code.
 *
 * The typical usage flow is:
 * 1. Get instance via [getInstance]
 * 2. Load a model with [loadModel]
 * 3. Send prompts with [sendUserPrompt]
 * 4. Generate responses as token streams
 * 5. Perform [cleanUp] when done with a model
 * 6. Properly [destroy] when completely done
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String,
    private val context: Context
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         * @throws UnsatisfiedLinkError if library failed to load
         */
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(nativeLibDir, context.applicationContext).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    /**
     * JNI methods
     * @see ai_chat.cpp
     */
    private external fun init(nativeLibDir: String): Int

    private external fun load(modelPath: String): Int

    @FastNative
    private external fun lastLoadError(): String

    private external fun prepare(): Int

    @FastNative
    private external fun systemInfo(): String

    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    private external fun setThinkingEnabledNative(enabled: Boolean)

    private external fun processSystemPrompt(systemPrompt: String): Int

    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun generateNextToken(): String?

    private external fun unload()

    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    // Included in Android's historical process exit record if the native code
    // aborts before Kotlin can catch an error. Do not include prompts or paths.
    private fun recordNativeStage(phase: String) {
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.setProcessStateSummary(("PocketAI phase=" + phase).toByteArray(Charsets.UTF_8))
        } catch (ignored: Exception) {
            Log.w(TAG, "Process-exit state summary unavailable", ignored)
        }
    }

    private var _readyForSystemPrompt = false
    @Volatile
    private var _cancelGeneration = false

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                recordNativeStage("backend_init")
                System.loadLibrary("ai-chat")
                val backendCount = init(nativeLibDir)
                check(backendCount > 0) {
                    "Kein GGML-CPU-Backend geladen. Bitte die JNI-Bibliotheken als echte " +
                    ".so-Dateien installieren (extractNativeLibs=true). Verzeichnis: $nativeLibDir"
                }
                _state.value = InferenceEngine.State.Initialized
                recordNativeStage("backend_ready")
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize native inference backend", e)
                _state.value = InferenceEngine.State.Error(e)
            }
        }
    }

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                recordNativeStage("model_load")
                load(pathToModel).let { code ->
                    if (code != 0) {
                        val details = lastLoadError().trim().takeLast(3500)
                            .ifBlank { "Keine weiteren nativen Fehlermeldungen vorhanden." }
                        throw IOException("llama.cpp-Modellladefehler (Code $code):\n$details")
                    }
                }
                recordNativeStage("context_prepare")
                prepare().let { code ->
                    if (code != 0) {
                        val details = lastLoadError().trim().takeLast(3500)
                            .ifBlank { "Kontext konnte nicht erstellt werden (Arbeitsspeicher?)." }
                        throw IOException("llama.cpp-Kontextfehler (Code $code):\n$details")
                    }
                }
                Log.i(TAG, "Model loaded!")
                recordNativeStage("model_ready")
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                // Release partially initialized native model/context, not just
                // state. Otherwise repeated attempts accumulate mapped weights.
                try {
                    unload()
                } catch (cleanupFailure: Exception) {
                    Log.e(TAG, "Native cleanup after failed load also failed", cleanupFailure)
                }
                _readyForSystemPrompt = false
                recordNativeStage("model_load_failed")
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    override suspend fun setThinkingMode(enabled: Boolean) = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Denkmodus kann nur bei geladenem und bereitem Modell konfiguriert werden."
        }
        check(_readyForSystemPrompt) {
            "Denkmodus kann nur vor dem ersten System-Prompt eingestellt werden. Modell neu laden."
        }
        setThinkingEnabledNative(enabled)
        Log.i(TAG, "GGUF thinking mode configured for new chat: $enabled")
        Unit
    }

    /**
     * Process the plain text system prompt
     *
     * TODO-han.yin: return error code if system prompt not correct processed?
     */
    override suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            recordNativeStage("system_prompt")
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    val nativeDetail = lastLoadError().trim().takeLast(3000)
                        .ifEmpty { "Kein weiterer nativer Fehlertext vorhanden." }
                    val error = IOException(
                        "Chat-Template/System-Prompt fehlgeschlagen (Code $result):\n$nativeDetail"
                    )
                    _state.value = InferenceEngine.State.Error(error)
                    throw error
                }
            }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            _state.value = InferenceEngine.State.ModelReady
            recordNativeStage("prompt_ready")
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            recordNativeStage("user_prompt")
            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    val nativeDetail = lastLoadError().trim().takeLast(3000)
                        .ifEmpty { "Kein weiterer nativer Fehlertext vorhanden." }
                    throw IOException("Chat-Template/Benutzer-Prompt fehlgeschlagen (Code $result):\n$nativeDetail")
                }
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            recordNativeStage("generating")
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            // A native std::exception from token generation is translated to a
            // Java IOException. Expose the last safe diagnostics to the user.
            val detail = runCatching { lastLoadError().trim().takeLast(2500) }.getOrNull().orEmpty()
            val error = if (e is IOException && detail.isNotEmpty()) {
                IOException((e.message ?: "Native Generierung fehlgeschlagen") + "\n" + detail, e)
            } else e
            _state.value = InferenceEngine.State.Error(error)
            throw error
        }
    }.flowOn(llamaDispatcher)

    /**
     * Benchmark the model
     */
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Cleaning up after native inference error...")
                    unload()
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Native resources released and state reset.")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cancel all ongoing coroutines and free GGML backends
     */
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
