package me.ailinux.pocket

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Separate LiteRT-LM engine for .litertlm models. Never accepts GGUF.
 * All hardware attempts are *real* engine.initialize() attempts. Never claim
 * a TPU was used based on device name alone.
 *
 * Native tool dispatch is deliberately not wired into this engine until
 * LiteRT's tool-calling flow is integrated with Pocket's approval firewall.
 */
internal class LiteRtRuntime private constructor(
    private val engine: Engine,
    private var conversation: Conversation,
    private val systemPrompt: String,
    val backendName: String
) : AutoCloseable {

    fun send(text: String): Flow<String> {
        require(text.isNotBlank()) { "Leere Modellanfrage" }
        return conversation.sendMessageAsync(text).map { it.toString() }
    }

    /** Keep the initialized TPU/GPU/CPU engine and weights; only renew chat. */
    suspend fun clearConversation() = withContext(Dispatchers.IO) {
        val newConversation = engine.createConversation(
            ConversationConfig(systemInstruction = Contents.of(systemPrompt))
        )
        val old = conversation
        conversation = newConversation
        old.close()
        Unit
    }

    override fun close() {
        try { conversation.close() } finally { engine.close() }
    }

    companion object {
        suspend fun load(context: Context, file: File, prompt: String): LiteRtRuntime =
            withContext(Dispatchers.IO) {
                require(file.isFile && file.name.endsWith(".litertlm", true)) {
                    "Nur lokal importierte LiteRT-LM-Modelle"
                }
                val errors = mutableListOf<String>()
                val attempts = RuntimeHardware.candidatesOnDevice()
                for (attempt in attempts) {
                    var candidate: Engine? = null
                    try {
                        val backend = when (attempt) {
                            RuntimeHardware.Attempt.GOOGLE_TENSOR -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
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
                        val convo = candidate.createConversation(
                            ConversationConfig(systemInstruction = Contents.of(prompt))
                        )
                        return@withContext LiteRtRuntime(candidate, convo, prompt, attempt.name)
                    } catch (problem: Exception) {
                        errors += attempt.name + ": " +
                            (problem.message ?: problem.javaClass.simpleName).take(180)
                        try { candidate?.close() } catch (_: Exception) {}
                    } catch (problem: LinkageError) {
                        errors += attempt.name + ": Native Bibliothek nicht verfügbar"
                        try { candidate?.close() } catch (_: Exception) {}
                    }
                }
                throw IOException(
                    "LiteRT-LM konnte kein kompatibles Backend initialisieren. " +
                        "Das .litertlm-Modell kann hardwaregebunden sein; ein " +
                        "G5-NPU-Bundle ist nicht zwangsläufig GPU/CPU-kompatibel. " +
                        errors.joinToString("; ").take(700)
                )
            }
    }
}
