package me.ailinux.pocket

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * One persistent, single-thread owner for each LiteRT-LM Engine + Conversation:
 * initialize, streamed inference, chat reset and shutdown use the SAME thread.
 * A distinct runtime handles GGUF/MCP; this class NEVER invokes MCP tools.
 *
 * Context and model file remain app-private. Only an actually initialized
 * hardware backend may be reported as ready.
 */
internal class LiteRtRuntime private constructor(
    private val engine: Engine,
    private var conversation: Conversation?,
    private val systemPrompt: String,
    val backendName: String,
    private val nativeDispatcher: ExecutorCoroutineDispatcher
) {
    private var disposed = false

    fun send(text: String): Flow<String> = flow {
        require(text.isNotBlank()) { "Leere Modellanfrage" }
        check(!disposed) { "LiteRT-Engine wurde bereits geschlossen" }
        val session = checkNotNull(conversation) {
            "LiteRT-Konversation nicht bereit. Modell bitte neu laden."
        }
        // The cold Flow is created and consumed ON the dedicated LiteRT thread.
        session.sendMessageAsync(text).collect { chunk -> emit(chunk.toString()) }
    }.flowOn(nativeDispatcher)

    /** Release the old native session BEFORE creating the replacement.
     * Do not unload model weights or redo NPU/GPU/CPU initialization.
     */
    suspend fun clearConversation() = withContext(nativeDispatcher) {
        check(!disposed) { "LiteRT-Engine wurde bereits geschlossen" }
        val old = conversation
        conversation = null
        old?.close()
        conversation = engine.createConversation(
            ConversationConfig(systemInstruction = Contents.of(systemPrompt))
        )
        Unit
    }

    /** Ensure a stopped Android chat does not keep a stale native response. */
    suspend fun cancelResponse() = withContext(NonCancellable + nativeDispatcher) {
        if (!disposed) conversation?.cancelProcess()
        Unit
    }

    /** A failed conversation recreation must never appear as successful. */
    val hasConversation: Boolean get() = conversation != null && !disposed

    private fun closeNative() {
        if (disposed) return
        disposed = true
        val old = conversation
        conversation = null
        try {
            // If Android stopped a streaming Flow, ensure native inference
            // winds down before freeing the conversation and engine.
            if (old != null) {
                try { old.cancelProcess() } catch (_: Exception) {}
                old.close()
            }
        } finally {
            engine.close()
        }
    }

    /** Await full model shutdown before opening another multi-GB runtime. */
    suspend fun shutdown() {
        try {
            withContext(NonCancellable + nativeDispatcher) { closeNative() }
        } finally {
            nativeDispatcher.close()
        }
    }

    /** onDestroy is a UI callback: schedule native cleanup without blocking it. */
    fun shutdownAsync() {
        CoroutineScope(SupervisorJob() + nativeDispatcher).launch {
            try { closeNative() } finally { nativeDispatcher.close() }
        }
    }

    companion object {
        suspend fun load(context: Context, file: File, prompt: String): LiteRtRuntime {
            require(file.isFile && file.name.endsWith(".litertlm", true)) {
                "Nur lokal importierte LiteRT-LM-Modelle"
            }
            // The native session/engine belong to this executor for their
            // entire lifetime, never to a multithreaded Dispatchers.IO pool.
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "AILinux-LiteRT-Inference").apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
            try {
                return withContext(dispatcher) {
                    val errors = mutableListOf<String>()
                    for (attempt in RuntimeHardware.candidatesOnDevice()) {
                        var candidate: Engine? = null
                        var initialized = false
                        try {
                            val backend = when (attempt) {
                                RuntimeHardware.Attempt.GOOGLE_TENSOR ->
                                    Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                                RuntimeHardware.Attempt.GPU -> Backend.GPU()
                                RuntimeHardware.Attempt.CPU -> Backend.CPU()
                            }
                            val config = EngineConfig(
                                modelPath = file.absolutePath,
                                backend = backend,
                                cacheDir = context.cacheDir.absolutePath
                            )
                            candidate = Engine(config)
                            candidate.initialize()
                            initialized = true
                            val convo = candidate.createConversation(
                                ConversationConfig(systemInstruction = Contents.of(prompt))
                            )
                            return@withContext LiteRtRuntime(
                                candidate, convo, prompt, attempt.name, dispatcher
                            )
                        } catch (problem: Exception) {
                            errors += attempt.name + ": " +
                                (problem.message ?: problem.javaClass.simpleName).take(180)
                            // The LiteRT API prohibits closing an engine whose
                            // initialize() did not complete successfully.
                            if (initialized) {
                                try { candidate?.close() } catch (_: Exception) {}
                            }
                        } catch (problem: LinkageError) {
                            errors += attempt.name + ": Native Bibliothek nicht verfügbar (" +
                                (problem.message ?: problem.javaClass.simpleName).take(140) + ")"
                            if (initialized) {
                                try { candidate?.close() } catch (_: Exception) {}
                            }
                        }
                    }
                    throw IOException(
                        "LiteRT-LM konnte kein kompatibles Backend initialisieren. " +
                            "Ein chipgebundenes NPU-Modell muss nicht GPU/CPU-kompatibel sein. " +
                            errors.joinToString("; ").take(700)
                    )
                }
            } catch (error: Throwable) {
                dispatcher.close()
                throw error
            }
        }
    }
}
