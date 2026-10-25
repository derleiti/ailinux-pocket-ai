package me.ailinux.pocket

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.tool
import me.ailinux.pocket.mcp.McpAgentSession
import me.ailinux.pocket.mcp.McpAgentBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var activeBridgeKey = ""
    private var currentInstruction = systemPrompt
    private var currentToolProviders: List<com.google.ai.edge.litertlm.ToolProvider> = emptyList()
    private var currentMaxOutputTokens = LiteRtOutputBudget.MAX_CHAT_TOKENS
    data class ToolTurn(val text: String, val calls: List<ToolCall>)

    /** No provider executes a tool. The host intercepts ToolCalls and must
     * explicitly approve and validate any actual remote tools/call.
     */
    suspend fun bindMcp(session: McpAgentSession?) = withContext(nativeDispatcher) {
        check(!disposed)
        val currentKey = session?.let {
            McpAgentBridge.nativeToolsJson(it.tools) + "|" + it.profileId +
                "|" + it.progressiveSources.joinToString { src -> src.id + src.endpoint } +
                "|" + me.ailinux.pocket.mcp.McpServerGuidance.optionalPromptBlock(it)
        }.orEmpty()
        if (currentKey != activeBridgeKey) {
            val safeJson = session?.let { McpAgentBridge.nativeToolsJson(it.tools) }
            val definitions = if (safeJson == null) emptyList() else
                (0 until org.json.JSONArray(safeJson).length()).map { index ->
                    val item = org.json.JSONArray(safeJson).getJSONObject(index).getJSONObject("function")
                    val description = item.toString()
                    tool(object : OpenApiTool {
                        override fun getToolDescriptionJsonString(): String = description
                        override fun execute(paramsJsonString: String): String {
                            throw SecurityException("MCP tools may execute only after explicit user consent")
                        }
                    })
                }
            val instructions = if (session == null) systemPrompt else
                systemPrompt + "\n" + LiteRtMcpProtocol.shortInstructions(
                    session.progressiveSources.isNotEmpty()) +
                me.ailinux.pocket.mcp.McpServerGuidance.optionalPromptBlock(session)
            // LiteRT permits only one conversation per engine; invalidate
            // the old handle before attempting replacement.
            val previous = conversation
            conversation = null
            previous?.close()
            val nextOutputLimit = LiteRtOutputBudget.forSession(session != null)
            val fresh = engine.createConversation(ConversationConfig(
                systemInstruction = Contents.of(instructions),
                tools = definitions,
                automaticToolCalling = false,
                maxOutputToken = nextOutputLimit
            ))
            conversation = fresh
            currentInstruction = instructions
            currentToolProviders = definitions
            currentMaxOutputTokens = nextOutputLimit
            activeBridgeKey = currentKey
        }
        Unit
    }

    suspend fun askMcp(text: String): ToolTurn = withContext(nativeDispatcher) {
        check(!disposed)
        val answer = checkNotNull(conversation).sendMessage(text)
        val plain = answer.contents.contents.filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
        ToolTurn(plain, answer.toolCalls)
    }

    suspend fun toolFeedback(name: String, payload: String): ToolTurn =
        withContext(nativeDispatcher) {
            check(!disposed)
            val response = Message.tool(Contents.of(Content.ToolResponse(name, payload)))
            val reply = checkNotNull(conversation).sendMessage(response)
            val text = reply.contents.contents.filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
            ToolTurn(text, reply.toolCalls)
        }

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
            ConversationConfig(
                systemInstruction = Contents.of(currentInstruction),
                tools = currentToolProviders,
                automaticToolCalling = false,
                maxOutputToken = currentMaxOutputTokens
            )
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
        suspend fun load(context: Context, file: File, prompt: String,
                         session: McpAgentSession? = null,
                         allowedAttempts: List<RuntimeHardware.Attempt>? = null,
                         onStage: (String) -> Unit = {}): LiteRtRuntime {
            require(file.isFile && file.name.endsWith(".litertlm", true)) {
                "Nur lokal importierte LiteRT-LM-Modelle"
            }
            LiteRtModelCompatibility.requireSupported(
                file.name, BuildConfig.LITERT_LM_VERSION)
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
                    // A memory preflight may explicitly exclude unsafe NPU,
                    // while retaining a genuine GPU/CPU candidate. Do not
                    // silently add excluded backends inside the native loader.
                    val safeSet = allowedAttempts?.toSet()
                    val choices = RuntimeHardware.forModelFile(
                        file.name, RuntimeHardware.candidatesOnDevice())
                        .filter { safeSet == null || it in safeSet }
                    require(choices.isNotEmpty()) { "Kein kompatibler Backend-Kandidat für " + file.name }
                    for (attempt in choices) {
                        currentCoroutineContext().ensureActive()
                        onStage("backend_" + attempt.name.lowercase())
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
                            // initialize() can block inside JNI; stop before creating a
                            // conversation if the caller cancelled during that work.
                            currentCoroutineContext().ensureActive()
                            onStage("conversation_" + attempt.name.lowercase())
                            val chosenTools = session?.let { McpAgentBridge.nativeToolsJson(it.tools) }
                            val definitions = if (chosenTools == null) emptyList() else
                                (0 until org.json.JSONArray(chosenTools).length()).map { index ->
                                    val json = org.json.JSONArray(chosenTools)
                                        .getJSONObject(index).getJSONObject("function").toString()
                                    tool(object : OpenApiTool {
                                        override fun getToolDescriptionJsonString() = json
                                        override fun execute(paramsJsonString: String): String =
                                            throw SecurityException("Host approval is required")
                                    })
                                }
                            val instruction = if (session == null) prompt else
                                prompt + "\n" + LiteRtMcpProtocol.shortInstructions(
                                    session.progressiveSources.isNotEmpty()) +
                                    me.ailinux.pocket.mcp.McpServerGuidance.optionalPromptBlock(session)
                            val outputLimit = LiteRtOutputBudget.forSession(session != null)
                            val convo = candidate.createConversation(ConversationConfig(
                                systemInstruction = Contents.of(instruction),
                                tools = definitions, automaticToolCalling = false,
                                maxOutputToken = outputLimit))
                            onStage("ready_" + attempt.name.lowercase())
                            return@withContext LiteRtRuntime(
                                candidate, convo, prompt, attempt.name, dispatcher
                            ).apply {
                                currentInstruction = instruction
                                currentToolProviders = definitions
                                currentMaxOutputTokens = outputLimit
                                activeBridgeKey = session?.let {
                                    McpAgentBridge.nativeToolsJson(it.tools) + "|" + it.profileId +
                                    "|" + it.progressiveSources.joinToString { src -> src.id + src.endpoint } +
                                    "|" + me.ailinux.pocket.mcp.McpServerGuidance.optionalPromptBlock(it)
                                }.orEmpty()
                            }
                        } catch (problem: Exception) {
                            // CancellationException extends IllegalStateException on the JVM.
                            // Never interpret Stop/Home/lifecycle cancellation as a
                            // recoverable backend failure or start another NPU/GPU/CPU.
                            LiteRtNativeLoadGuard.rethrowIfCancelled(problem) {
                                if (initialized) candidate?.close()
                            }
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
                    val likelyCompiledMismatch = errors.any {
                        it.startsWith(RuntimeHardware.Attempt.GOOGLE_TENSOR.name + ": ") &&
                            it.contains("No KV cache inputs found", ignoreCase = true)
                    }
                    val explanation = if (likelyCompiledMismatch)
                        "Das Modell enthält offenbar keine für GPU/CPU verwendbaren KV-Cache-Eingänge. " +
                            "Das ist kein RAM-Beweis: NPU-Kompilierung, Modellpaket und " +
                            "Tensor-G5-Dispatcher müssen zusammenpassen. " +
                            "Bitte exakt für Google Tensor G5 exportierte LiteRT-Datei und " +
                            "vollständig abgeschlossenen Download prüfen. "
                    else "Ein chipgebundenes NPU-Modell muss nicht GPU/CPU-kompatibel sein. "
                    throw IOException(
                        "LiteRT-LM konnte kein kompatibles Backend initialisieren. " +
                            explanation +
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
