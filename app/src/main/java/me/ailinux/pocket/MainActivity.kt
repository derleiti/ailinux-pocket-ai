package me.ailinux.pocket

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.net.Uri
import android.content.Intent
import me.ailinux.pocket.mcp.McpSettingsUi
import me.ailinux.pocket.mcp.McpSecurity
import me.ailinux.pocket.mcp.McpAgentBridge
import me.ailinux.pocket.mcp.McpAgentUi
import me.ailinux.pocket.mcp.McpAgentSession
import me.ailinux.pocket.mcp.McpToolCatalog
import me.ailinux.pocket.mcp.McpRequestRouting
import me.ailinux.pocket.mcp.McpHealthCheck
import me.ailinux.pocket.mcp.McpLogCheck
import me.ailinux.pocket.mcp.McpQuickActions
import me.ailinux.pocket.mcp.McpToolFeedback
import me.ailinux.pocket.mcp.McpServerGuidance
import me.ailinux.pocket.mcp.McpProgressiveBridge
import me.ailinux.pocket.mcp.McpOnDemandDiscovery
import me.ailinux.pocket.mcp.McpTool
import me.ailinux.pocket.mcp.McpVault
import me.ailinux.pocket.mcp.McpProfile
import me.ailinux.pocket.mcp.McpAuth
import me.ailinux.pocket.mcp.McpOAuth
import me.ailinux.pocket.mcp.McpExecutionUncertain
import me.ailinux.pocket.mcp.McpDispatchState
import me.ailinux.pocket.mcp.McpConnection
import me.ailinux.pocket.mcp.McpHttpFailure
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import android.text.Editable
import android.text.TextWatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.IncompleteThinkingTurnException
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var store: ModelStore
    private lateinit var engine: InferenceEngine
    private lateinit var mcpUi: McpSettingsUi
    private lateinit var mcpAgent: McpAgentUi
    @Volatile private var mcpDispatchInFlight = false
    private var mcpBindingInProgress = false
    private var mcpBindPoll: Runnable? = null
    private var mcpBindStartedAtMs = 0L
    private var mcpBindLastState = McpPrefillStatus.State(0, 0)
    private var lastMcpBindingReport = "MCP-BINDUNG: Noch keine Einbindung in diesem App-Prozess."
    private val messages = mutableListOf<ChatMessage>()
    private val chatAdapter = ChatAdapter(messages)
    private var currentConversationId = PocketChatHistory.newId()
    private var chatReady = false
    private var showArchivedHistory = false
    private var historyQueryGeneration = 0
    private val chatPersistHandler = Handler(Looper.getMainLooper())
    private val chatWriteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var chatPersistQueued = false
    private val chatPersistCallback = Runnable {
        chatPersistQueued = false
        persistChatNow()
    }
    private val historyDrawer by lazy {
        findViewById<DrawerLayout>(R.id.chatDrawerLayout)
    }
    private var activeModel: File? = null
    /** Alternative LiteRT-LM runtime, only for .litertlm models. */
    private var liteRtRuntime: LiteRtRuntime? = null
    @Volatile private var diagnosticTrace = PocketDiagnostics.newTrace()
    private fun trace(
        domain: PocketDiagnostics.Domain, step: PocketDiagnostics.Step,
        state: PocketDiagnostics.State = PocketDiagnostics.State.INFO,
        tool: String = "", count: Int = -1, durationMs: Long = -1,
        code: PocketDiagnostics.ErrorCode = PocketDiagnostics.ErrorCode.NONE
    ): String = PocketDiagnostics.log(
        applicationContext, diagnosticTrace, domain, step, state,
        backend = if (activeModel?.name?.endsWith(".litertlm", true) == true) "LITERT"
            else if (activeModel != null) "GGUF" else "",
        tool = tool, count = count, durationMs = durationMs, code = code
    )
    private fun failure(
        domain: PocketDiagnostics.Domain, step: PocketDiagnostics.Step,
        error: Throwable, uncertain: Boolean = false
    ): String = PocketDiagnostics.failure(
        applicationContext, diagnosticTrace, domain, step, error, uncertain
    )

    private var generationJob: Job? = null
    private var busy = false
    private var transferBusy = false
    private var activeTransferName: String? = null
    private var backgroundTransferName: String? = null
    private val downloadUiHandler = Handler(Looper.getMainLooper())
    private val downloadUiPoll = object : Runnable {
        override fun run() {
            updateBackgroundTransferUi()
            downloadUiHandler.postDelayed(this, 1300L)
        }
    }
    private var activePartialLabel: TextView? = null
    private var cancelTransfer = AtomicBoolean(false)
    private var pendingExportModel: File? = null
    private var activeExportFileName: String? = null
    private var currentPage = 0
    private val prefs by lazy { getSharedPreferences("pocket_settings", MODE_PRIVATE) }

    // In-memory main-looper response measurement, foreground-only.
    private val uiResponsiveness = UiResponsiveness()
    private val uiPulseHandler = Handler(Looper.getMainLooper())
    private var uiPulseEnabled = false
    private val uiPulse = object : Runnable {
        override fun run() {
            if (!uiPulseEnabled) return
            uiResponsiveness.pulse(SystemClock.uptimeMillis())
            uiPulseHandler.postDelayed(this, UiResponsiveness.INTERVAL_MS)
        }
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModel(it) }
    }

    private val modelExportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val source = pendingExportModel
        pendingExportModel = null
        if (destination != null && source != null) exportModel(source, destination)
    }

    private val chatPage by lazy { findViewById<View>(R.id.chatPage) }
    private val libraryPage by lazy { findViewById<View>(R.id.libraryPage) }
    private val settingsPage by lazy { findViewById<View>(R.id.settingsPage) }
    private val chatModelName by lazy { findViewById<TextView>(R.id.chatModelName) }
    private val emptyHint by lazy { findViewById<TextView>(R.id.emptyHint) }
    private val statusLabel by lazy { findViewById<TextView>(R.id.statusLabel) }
    private val messageInput by lazy { findViewById<EditText>(R.id.messageInput) }
    private val sendButton by lazy { findViewById<MaterialButton>(R.id.sendButton) }
    private val messagesView by lazy { findViewById<RecyclerView>(R.id.chatMessages) }
    private val modelsContainer by lazy { findViewById<LinearLayout>(R.id.modelsContainer) }
    private val downloadProgress by lazy { findViewById<ProgressBar>(R.id.downloadProgress) }
    private val downloadStatus by lazy { findViewById<TextView>(R.id.downloadStatus) }
    private val cancelDownload by lazy { findViewById<MaterialButton>(R.id.cancelDownload) }
    private val tokenSpinner by lazy { findViewById<Spinner>(R.id.maxTokensSpinner) }
    private val systemPrompt by lazy { findViewById<EditText>(R.id.systemPromptInput) }
    private val thinkingModeSwitch by lazy { findViewById<SwitchCompat>(R.id.thinkingModeSwitch) }

    private val samplingFields by lazy { listOf(
        R.id.temperatureInput, R.id.topKInput, R.id.topPInput,
        R.id.minPInput, R.id.repeatPenaltyInput, R.id.repeatLastNInput
    ) }

    private fun samplingValues() = samplingFields.map {
        findViewById<EditText>(it).text.toString().trim().replace(',', '.')
    }

    private fun showSampling(values: List<String>) {
        samplingFields.zip(values).forEach { (id, value) ->
            findViewById<EditText>(id).setText(value)
        }
    }

    private suspend fun applySampling() {
        val settings = SamplingSettings.fromPrefs(prefs)
        engine.setSampling(settings.temperature, settings.topK, settings.topP,
            settings.minP, settings.repeatPenalty, settings.repeatLastN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unbekannt"
        findViewById<TextView>(R.id.appVersion).text = "AILinux Pocket AI  •  $installedVersion"
        store = ModelStore(this)
        pendingExportModel = savedInstanceState
            ?.getString("pocket_pending_export_model")
            ?.let { File(store.dir, it) }
        mcpUi = McpSettingsUi(this,
            findViewById(R.id.mcpServerList),
            findViewById(R.id.addMcpServerButton)) { result ->
            displayMcpToolResult(result)
        }
        mcpAgent = McpAgentUi(this) { active ->
            if (activeModel?.name?.endsWith(".litertlm", true) == true)
                rebindLiteRtMcp(active)
            else if (activeModel != null) rebindNativeMcpTools(active)
            else updateMcpBadge(active)
        }
        mcpAgent.bindDiagnosticsTrace { diagnosticTrace }
        // No agent session does not mean the device, its saved MCP profiles,
        // or the remote server is offline. Show exactly what is disabled.
        updateMcpBadge(null)
        handleMcpOAuthIntent(intent)

        messagesView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesView.adapter = chatAdapter
        setupChatHistory()
        restoreChatHistory()

        findViewById<MaterialButton>(R.id.appLanguageButton).setOnClickListener {
            showAppLanguagePicker()
        }
        updateAppLanguageLabel()

        findViewById<TextView>(R.id.navChat).setOnClickListener { showPage(0) }
        findViewById<TextView>(R.id.navModels).setOnClickListener { showPage(1) }
        findViewById<TextView>(R.id.navSettings).setOnClickListener { showPage(2) }
        findViewById<View>(R.id.chatDebugButton).setOnClickListener {
            showDiagnosticTimeline(inChat = true)
        }
        findViewById<MaterialButton>(R.id.chatMcpButton).setOnClickListener {
            showMcpAgentMenu()
        }
        // RC12: make the model MCP agent discoverable from SETUP as well.
        // The same menu is shared, so there is exactly one approval/agent
        // implementation and no duplicate settings or auth handling.
        findViewById<MaterialButton>(R.id.mcpAgentSettingsButton).setOnClickListener {
            showMcpAgentMenu()
        }
        findViewById<MaterialButton>(R.id.mcpToolListSettingsButton).setOnClickListener {
            if (busy) toast("Pocket AI arbeitet bereits.")
            else mcpUi.showToolsChooser()
        }
        findViewById<MaterialButton>(R.id.mcpServerInstructionsButton).setOnClickListener {
            if (busy) toast("Pocket AI arbeitet bereits.")
            else mcpUi.showServerGuidanceChooser()
        }
        findViewById<MaterialButton>(R.id.importButton).setOnClickListener {
            if (!transferBusy) modelPicker.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.browseButton).setOnClickListener { browseRepository() }
        findViewById<MaterialButton>(R.id.hfSearchButton).setOnClickListener { searchHuggingFace() }
        findViewById<MaterialButton>(R.id.quickStartButton).setOnClickListener { quickStart() }
        findViewById<MaterialButton>(R.id.urlButton).setOnClickListener { promptDirectUrl() }
        cancelDownload.setOnClickListener {
            val name = backgroundTransferName
            if (name == null) { cancelTransfer.set(true); return@setOnClickListener }
            val entry = ModelDownloadJournal.read(this, name) ?: return@setOnClickListener
            val status = BackgroundModelDownloads.snapshot(this, entry)
            if (status.canResume) {
                if (status.requiresConfirmation) {
                    AlertDialog.Builder(this)
                        .setTitle("Downloadjob neu einplanen?")
                        .setMessage("Android verwaltet diesen Auftrag noch. " +
                            "Nur den wartenden Job neu einplanen? Die vorhandene " +
                            "Teildatei und ihr Validator bleiben erhalten.")
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Neu einplanen") { _, _ ->
                            resumeBackgroundDownload(name, true)
                        }.show()
                } else resumeBackgroundDownload(name, false)
            } else {
                BackgroundModelDownloads.pause(this, name)
                toast("Download pausiert. Teil-Datei bleibt erhalten.")
                updateBackgroundTransferUi()
                refreshLibrary()
            }
        }

        sendButton.setOnClickListener {
            if (generationJob?.isActive == true) {
                if (mcpDispatchInFlight) {
                    toast("Der MCP-Aufruf läuft bereits. Status nach Serverantwort prüfen.")
                } else {
                    generationJob?.cancel()
                }
            } else sendMessage()
        }
        messageInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        systemPrompt.setText(BrumoSystemPrompt.resolve(prefs.getString("system_prompt", null)))
        thinkingModeSwitch.isChecked = prefs.getBoolean(PREF_ENABLE_THINKING, false)
        val choices = listOf("128 Tokens", "256 Tokens", "512 Tokens", "1024 Tokens")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, choices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        tokenSpinner.adapter = adapter
        val selected = listOf(128, 256, 512, 1024).indexOf(prefs.getInt("max_tokens", 512))
        tokenSpinner.setSelection(selected.coerceAtLeast(0))
        showSampling(SamplingSettings.fromPrefs(prefs).asStrings())
        val samplingPreset = findViewById<Spinner>(R.id.samplingPresetSpinner)
        val samplingNames = listOf("Standard · ausgewogen", "Präzise", "Kreativ", "Eigene Werte")
        samplingPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            samplingNames).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        samplingPreset.setSelection(3)
        samplingPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?,
                view: View?, position: Int, id: Long) {
                if (position < 3) showSampling(SamplingSettings.presets[position].asStrings())
            }
        }
        findViewById<MaterialButton>(R.id.saveSettingsButton).setOnClickListener {
            val count = listOf(128, 256, 512, 1024)[tokenSpinner.selectedItemPosition]
            val sampling = try {
                SamplingSettings.parse(samplingValues())
            } catch (invalid: IllegalArgumentException) {
                toast(invalid.message ?: "Sampling-Werte ungültig")
                return@setOnClickListener
            }
            sampling.save(prefs)
            prefs.edit().putString("system_prompt", systemPrompt.text.toString())
                .putInt("max_tokens", count)
                .putBoolean(PREF_ENABLE_THINKING, thinkingModeSwitch.isChecked)
                .apply()
            toast("Sampling ab nächster Antwort · Thinking nach Modell-Neuladen; MCP-Agent separat aktivieren.")
        }
        findViewById<MaterialButton>(R.id.clearChatButton).setOnClickListener {
            resetLocalConversation()
        }
        findViewById<MaterialButton>(R.id.crashDiagnosticsButton).setOnClickListener {
            showProcessExitInfo()
        }
        findViewById<MaterialButton>(R.id.pocketDiagnosticsButton).setOnClickListener {
            showDiagnosticTimeline(inChat = false)
        }
        findViewById<MaterialButton>(R.id.hardwareProbeButton).setOnClickListener {
            val live = liteRtRuntime?.backendName
            val report = RuntimeHardware.diagnostics(this) +
                (if (live != null) "\n\nAktiv initialisiertes LiteRT-Backend: " + live
                 else "\n\nAktuelles LiteRT-Backend: nicht gestartet.")
            AlertDialog.Builder(this)
                .setTitle("Android KI-Hardware / LiteRT-LM")
                .setMessage(report)
                .setPositiveButton("OK", null)
                .show()
        }

        // This preference is written synchronously before native load and only
        // cleared on a normal outcome. It survives abrupt Android process kills.
        val interruptedModel = prefs.getString(PREF_LOADING_MODEL, null)
        if (interruptedModel != null) {
            prefs.edit().remove(PREF_LOADING_MODEL).apply()
            val wasPackageUpdate = runCatching {
                val mgr = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                mgr.getHistoricalProcessExitReasons(packageName, 0, 1)
                    .firstOrNull()?.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED
            }.getOrDefault(false)
            findViewById<View>(android.R.id.content).post {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle(
                            if (wasPackageUpdate) "Modellstart durch App-Update unterbrochen"
                            else "Vorheriger Modellstart wurde unterbrochen"
                        )
                        .setMessage(
                            if (wasPackageUpdate) {
                                "Android hat die vorherige Pocket-AI-Version während des " +
                                    "APK-Updates beendet. Das ist kein neuer Absturz. " +
                                    "Versuche das Modell " + interruptedModel + " erneut zu laden. " +
                                    "Die heruntergeladene GGUF-Datei bleibt gespeichert."
                            } else {
                                "Der Start von " + interruptedModel +
                                    " wurde vor Abschluss beendet. Unter SETUP kannst du " +
                                    "die Android-Prozessdiagnose prüfen. Das Modell wurde nicht gelöscht."
                            }
                        )
                        .setNegativeButton("OK", null)
                        .setPositiveButton("Systemdiagnose") { _, _ -> showProcessExitInfo() }
                        .show()
                }
            }
        }

        refreshLibrary()
        renderChatState()
        setStatus("Engine wird initialisiert …")
        lifecycleScope.launch {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                val state = engine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }
                if (state is InferenceEngine.State.Error) throw state.exception
                setStatus("Bereit für dein GGUF")
            } catch (error: Exception) {
                setStatus("Engine-Fehler")
                showError("Native Engine konnte nicht gestartet werden", error)
            }
        }
    }

    /**
     * Room owns durable history. Native GGUF/LiteRT conversations remain separate:
     * opening an older transcript never claims to restore a vanished KV cache.
     * Only bounded copies of currently visible message text are queued for IO.
     */
    private fun setupChatHistory() {
        findViewById<MaterialButton>(R.id.chatHistoryButton).setOnClickListener {
            loadChatHistory()
            historyDrawer.openDrawer(GravityCompat.END)
        }
        findViewById<MaterialButton>(R.id.newConversationButton).setOnClickListener {
            historyDrawer.closeDrawer(GravityCompat.END)
            resetLocalConversation()
        }
        findViewById<MaterialButton>(R.id.showArchivedChatsButton).setOnClickListener {
            showArchivedHistory = !showArchivedHistory
            findViewById<MaterialButton>(R.id.showArchivedChatsButton).text =
                if (showArchivedHistory) "Aktive Chats anzeigen" else "Archivierte Chats"
            loadChatHistory()
        }
        findViewById<EditText>(R.id.chatHistorySearch).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int,
                    count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int,
                    before: Int, count: Int) = loadChatHistory()
                override fun afterTextChanged(s: Editable?) = Unit
            }
        )
    }

    private fun restoreChatHistory() {
        chatReady = false
        lifecycleScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val dao = PocketChatHistory.dao(applicationContext)
                    val selected = prefs.getString("current_chat_id", null)
                    val metadata = selected?.let { dao.getConversation(it) }
                        ?: dao.recent().firstOrNull()
                    Pair(metadata, metadata?.let { dao.lines(it.id) }.orEmpty())
                }
                if (!isFinishing && !isDestroyed) {
                    currentConversationId = restored.first?.id ?: PocketChatHistory.newId()
                    prefs.edit().putString("current_chat_id", currentConversationId).apply()
                    // This is a transcript, not a reconstructed LLM KV cache.
                    messages.clear()
                    messages.addAll(restored.second.map { ChatMessage(it.isUser, it.content) })
                    chatAdapter.notifyDataSetChanged()
                    findViewById<View>(R.id.emptyState).visibility =
                        if (messages.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed)
                    showError("Chatdatenbank konnte nicht geladen werden", error)
            } finally {
                chatReady = true
                if (!isFinishing && !isDestroyed) renderChatState()
            }
        }
    }

    /** Debounced and serialized so 2B/4B streaming does not fsync every token. */
    private fun requestChatPersist() {
        if (!chatReady || chatPersistQueued) return
        chatPersistQueued = true
        chatPersistHandler.postDelayed(chatPersistCallback, 1100L)
    }

    private fun persistChatNow() {
        if (!chatReady || chatWriteExecutor.isShutdown) return
        chatPersistHandler.removeCallbacks(chatPersistCallback)
        chatPersistQueued = false
        val id = currentConversationId
        val snapshot = messages.map { ChatMessage(it.isUser, it.content) }
        val model = activeModel?.name.orEmpty()
        val application = applicationContext
        chatWriteExecutor.execute {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    PocketChatHistory.dao(application).saveSnapshot(id, model, snapshot)
                }
            }.onFailure {
                android.util.Log.e("PocketChatHistory", "Unable to persist conversation metadata")
            }
        }
    }

    private fun beginFreshConversation() {
        persistChatNow()
        currentConversationId = PocketChatHistory.newId()
        prefs.edit().putString("current_chat_id", currentConversationId).apply()
    }

    private fun loadChatHistory() {
        val generation = ++historyQueryGeneration
        val term = findViewById<EditText>(R.id.chatHistorySearch).text.toString().trim()
        val archived = showArchivedHistory
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val dao = PocketChatHistory.dao(applicationContext)
                    if (archived) dao.archived() else if (term.isBlank()) dao.recent()
                    else dao.search(term)
                }
                if (generation == historyQueryGeneration && !isDestroyed)
                    renderChatHistoryEntries(list)
            } catch (error: Exception) {
                if (!isDestroyed) android.util.Log.e("PocketChatHistory",
                    "Unable to load conversation list")
            }
        }
    }

    private fun renderChatHistoryEntries(conversations: List<PocketConversation>) {
        val container = findViewById<LinearLayout>(R.id.chatHistoryEntries)
        container.removeAllViews()
        if (conversations.isEmpty()) {
            container.addView(simpleLabel(getString(R.string.history_empty)))
            return
        }
        conversations.forEach { conversation ->
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(11), dp(9), dp(9))
                setBackgroundResource(R.drawable.bg_panel)
            }
            val title = TextView(this).apply {
                text = (if (conversation.pinned) "★ " else "") +
                    conversation.title.take(75)
                textSize = 15f
                setTextColor(getColor(R.color.ink))
                setOnClickListener { openConversation(conversation) }
                setPadding(dp(5), dp(5), dp(5), dp(9))
            }
            section.addView(title)
            section.addView(TextView(this).apply {
                text = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(Date(conversation.updatedAt)) + " · " +
                    conversation.modelName.ifBlank { "Modell nicht angegeben" }.take(45)
                textSize = 11f
                setTextColor(getColor(R.color.muted))
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun action(label: String, callback: () -> Unit) {
                actions.addView(TextView(this).apply {
                    text = label
                    setPadding(dp(7), dp(13), dp(7), dp(13))
                    textSize = 11f
                    setTextColor(getColor(R.color.primary))
                    contentDescription = label + ": " + conversation.title.take(55)
                    setOnClickListener { callback() }
                })
            }
            action(getString(R.string.history_open_action)) { openConversation(conversation) }
            action(getString(R.string.history_rename_action)) { renameConversation(conversation) }
            action(if (conversation.pinned)
                getString(R.string.history_unpin_action) else getString(R.string.history_pin_action)) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        PocketChatHistory.dao(applicationContext)
                            .pin(conversation.id, !conversation.pinned)
                    }
                    loadChatHistory()
                }
            }
            action(if (conversation.archived)
                getString(R.string.history_activate_action) else getString(R.string.history_archive_action)) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        PocketChatHistory.dao(applicationContext)
                            .archive(conversation.id, !conversation.archived)
                    }
                    loadChatHistory()
                }
            }
            action(getString(R.string.history_delete_action)) { deleteConversation(conversation) }
            section.addView(actions)
            container.addView(section, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })
        }
    }

    private fun renameConversation(conversation: PocketConversation) {
        val field = EditText(this).apply {
            setSingleLine(true)
            setText(conversation.title)
            selectAll()
        }
        AlertDialog.Builder(this).setTitle(R.string.history_rename_title).setView(field)
            .setNegativeButton(R.string.language_cancel, null)
            .setPositiveButton(R.string.confirm_save) { _, _ ->
                val title = field.text.toString().trim().take(80)
                if (title.isNotBlank()) lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        PocketChatHistory.dao(applicationContext).rename(conversation.id, title)
                    }
                    loadChatHistory()
                }
            }.show()
    }

    private fun deleteConversation(conversation: PocketConversation) {
        if (conversation.id == currentConversationId) {
            toast(getString(R.string.history_current_delete_warning))
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.history_delete_title)
            .setMessage(getString(R.string.history_delete_message, conversation.title.take(65)))
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Löschen") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        PocketChatHistory.dao(applicationContext).permanentlyDelete(conversation.id)
                    }
                    loadChatHistory()
                }
            }.show()
    }

    private fun openConversation(conversation: PocketConversation) {
        if (!chatReady || busy || mcpDispatchInFlight || mcpBindingInProgress) {
            toast(getString(R.string.history_select_busy))
            return
        }
        if (conversation.id == currentConversationId) {
            historyDrawer.closeDrawer(GravityCompat.END)
            return
        }
        persistChatNow()
        busy = true
        renderChatState()
        lifecycleScope.launch {
            try {
                // The previous native conversation has different message history.
                // Do NOT imply an old KV cache or native session was restored.
                if (activeModel?.name?.endsWith(".litertlm", true) == true) {
                    liteRtRuntime?.clearConversation()
                } else if (activeModel != null && ::engine.isInitialized) {
                    engine.clearConversationKeepingSystem()
                }
                val history = withContext(Dispatchers.IO) {
                    PocketChatHistory.dao(applicationContext).lines(conversation.id)
                }
                currentConversationId = conversation.id
                prefs.edit().putString("current_chat_id", conversation.id).apply()
                messages.clear()
                messages.addAll(history.map { ChatMessage(it.isUser, it.content) })
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility =
                    if (messages.isEmpty()) View.VISIBLE else View.GONE
                historyDrawer.closeDrawer(GravityCompat.END)
                setStatus(getString(R.string.history_loaded_status))
            } catch (error: Exception) {
                showError("Chatwechsel nicht möglich", error)
            } finally {
                busy = false
                renderChatState()
            }
        }
    }

    /**
     * Android 13+ owns application locales. Never save a competing locale in
     * preferences; setApplicationLocales synchronizes with system Settings.
     * Changing configuration recreates the Activity and invalidates native
     * GGUF/LiteRT objects, so block busy actions and explain the reset.
     */
    private fun updateAppLanguageLabel() {
        val current = AppLanguageCatalog.selectedIndex(
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        )
        val selected = AppLanguageCatalog.choices[current]
        val name = if (selected.tag.isEmpty()) getString(R.string.language_system_default)
            else selected.nativeName +
                (if (selected.preview) " · " + getString(R.string.language_preview_suffix)
                 else "")
        findViewById<MaterialButton>(R.id.appLanguageButton).text =
            getString(R.string.language_button, name)
    }

    private fun showAppLanguagePicker() {
        val options = AppLanguageCatalog.choices
        val current = AppLanguageCatalog.selectedIndex(
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        )
        val choices = options.map { option ->
            if (option.tag.isEmpty()) getString(R.string.language_system_default)
            else option.nativeName + (if (option.preview)
                " · " + getString(R.string.language_preview_suffix) else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(choices, current) { dialog, which ->
                dialog.dismiss()
                if (which == current) return@setSingleChoiceItems
                if (busy || mcpDispatchInFlight || mcpBindingInProgress ||
                    generationJob?.isActive == true) {
                    toast(getString(R.string.language_busy_message))
                    return@setSingleChoiceItems
                }
                val selected = options[which]
                val warnings = mutableListOf<String>()
                if (activeModel != null)
                    warnings.add(getString(R.string.language_restart_message))
                if (selected.preview)
                    warnings.add(getString(R.string.language_beta_notice))
                fun switchLocale() {
                    if (busy || generationJob?.isActive == true || mcpDispatchInFlight)
                        return
                    persistChatNow()
                    AppCompatDelegate.setApplicationLocales(
                        if (selected.tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(selected.tag)
                    )
                }
                if (warnings.isEmpty()) switchLocale()
                else AlertDialog.Builder(this)
                    .setTitle(R.string.language_restart_title)
                    .setMessage(warnings.joinToString("\n\n"))
                    .setNegativeButton(R.string.language_cancel, null)
                    .setPositiveButton(R.string.language_apply) { _, _ -> switchLocale() }
                    .show()
            }
            .setNegativeButton(R.string.language_cancel, null)
            .show()
    }

    private fun showPage(which: Int) {
        currentPage = which
        historyDrawer.visibility = if (which == 0) View.VISIBLE else View.GONE
        libraryPage.visibility = if (which == 1) View.VISIBLE else View.GONE
        settingsPage.visibility = if (which == 2) View.VISIBLE else View.GONE
        val ids = intArrayOf(R.id.navChat, R.id.navModels, R.id.navSettings)
        ids.forEachIndexed { index, id ->
            findViewById<TextView>(id).setTextColor(getColor(
                if (index == which) R.color.primary else R.color.muted
            ))
        }
        if (which == 1) refreshLibrary()
        if (which == 0) renderChatState()
    }

    /**
     * Keep the chat area free of model selection controls: display only a
     * read-only model name, the inference status and the message composer.
     */
    private fun renderChatState() {
        val loaded = activeModel != null
        chatModelName.text = if (loaded)
            ChatPresentation.modelLabel(activeModel?.name)
            else getString(R.string.no_model_loaded)
        emptyHint.text = getString(if (loaded) R.string.chat_ready_hint
            else R.string.chat_missing_hint)
        messageInput.hint = getString(if (loaded) R.string.input_chat_hint
            else R.string.input_missing_hint)
        // Native prefill can take time on 4B CPU models. Keep the text
        // editor and navigation responsive while preventing a premature send.
        val canInteract = !busy
        messageInput.isEnabled = chatReady && (canInteract || mcpBindingInProgress)
        sendButton.isEnabled = chatReady && (canInteract || generationJob?.isActive == true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Android SAF may recreate the Activity while CreateDocument is open.
        // Persist only the private GGUF filename, never a URI/token/credential.
        pendingExportModel?.let {
            outState.putString("pocket_pending_export_model", it.name)
        }
        super.onSaveInstanceState(outState)
    }

    private fun refreshLibrary() {
        activePartialLabel = null
        modelsContainer.removeAllViews()
        val files = store.list()
        findViewById<TextView>(R.id.storageLabel).text =
            files.size.toString() + " Modelle • " +
            ModelStore.displaySize(files.sumOf { it.length() })
        if (files.isEmpty()) {
            modelsContainer.addView(simpleLabel("Hier ist noch Platz für dein erstes Offline-Modell."))
        }
        files.forEach { file ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.line)
                setCardBackgroundColor(getColor(R.color.surface))
                val params = LinearLayout.LayoutParams(-1, -2)
                params.bottomMargin = dp(11)
                layoutParams = params
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(17), dp(15), dp(17), dp(10))
            }
            val title = simpleLabel(file.name).apply {
                setTextColor(getColor(R.color.ink))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 2
            }
            content.addView(title)
            val isActive = activeModel?.absolutePath == file.absolutePath
            content.addView(simpleLabel(
                ModelStore.displaySize(file.length()) + if (isActive) "  •  AKTIV" else ""
            ))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val load = smallButton(if (isActive) "✓ Aktiv" else "▶ Laden", isActive)
            load.isEnabled = !busy && !transferBusy && !isActive
            load.setOnClickListener { loadModel(file) }
            buttons.addView(load)
            val export = smallButton("Export", false)
            export.isEnabled = !busy && !transferBusy
            export.setOnClickListener {
                if (!busy && !transferBusy) {
                    pendingExportModel = file
                    modelExportPicker.launch(file.name)
                }
            }
            buttons.addView(export)
            val remove = smallButton("Löschen", false)
            remove.isEnabled = !busy && !transferBusy && !isActive
            remove.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Modell löschen?")
                    .setMessage(file.name + "\nDie lokale Modelldatei wird entfernt.")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Löschen") { _, _ ->
                        try { store.delete(file); refreshLibrary() }
                        catch (error: Exception) { showError("Löschen fehlgeschlagen", error) }
                    }.show()
            }
            buttons.addView(remove)
            content.addView(buttons)
            card.addView(content)
            modelsContainer.addView(card)
        }
        val partNames = store.partials().map { it.name.removeSuffix(".part") }.toSet()
        ModelDownloadJournal.all(this).filter {
            it.state in setOf("paused", "failed") &&
                it.name !in partNames && it.url.isNotBlank() &&
                !File(store.dir, it.name).exists()
        }.forEach { entry ->
            val label = simpleLabel(BackgroundModelDownloads.snapshot(this, entry).label)
            modelsContainer.addView(label)
            val resume = smallButton("Download fortsetzen", false)
            resume.isEnabled = !busy && !transferBusy
            resume.setOnClickListener { resumeBackgroundDownload(entry.name) }
            modelsContainer.addView(resume)
        }
        store.partials().forEach { part ->
            val name = part.name.removeSuffix(".part")
            val journal = ModelDownloadJournal.read(this, name)
            val source = journal?.url?.takeIf { it.isNotBlank() }
                ?: prefs.getString("pending_url_" + name, null)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_panel)
            }
            val runningNow = journal?.state in setOf("running", "verifying") ||
                (journal == null && DownloadPresentation.isActivePartial(
                    part.name, activeTransferName, transferBusy
                ))
            val partialLabel = simpleLabel(
                if (journal?.state == "queued") "↓ " +
                    BackgroundModelDownloads.snapshot(this, journal).label
                else DownloadPresentation.partialStatus(name, part.length(), runningNow)
            )
            line.addView(partialLabel)
            if (runningNow) {
                activePartialLabel = partialLabel
            } else {
                if (source != null) {
                    val resume = smallButton("Fortsetzen", false)
                    resume.isEnabled = !transferBusy
                    resume.setOnClickListener {
                        if (journal != null) resumeBackgroundDownload(name)
                        else startDownload(RemoteModel(
                            name, source, -1,
                            prefs.getString("pending_sha_" + name, null)
                        ))
                    }
                    line.addView(resume)
                }
                val remove = smallButton("Teil-Download verwerfen", false)
                remove.isEnabled = !transferBusy
                remove.setOnClickListener {
                    if (!transferBusy) {
                        store.delete(part)
                        prefs.edit().remove("pending_url_" + name).remove("pending_sha_" + name).apply()
                        refreshLibrary()
                    }
                }
                line.addView(remove)
            }
            modelsContainer.addView(line)
        }
    }

    private fun loadModel(file: File, acknowledgedLargeModel: Boolean = false) {
        if (busy || transferBusy) return
        if (activeModel?.canonicalPath == file.canonicalPath) {
            toast("Dieses Modell ist bereits geladen.")
            return
        }
        if (file.name.endsWith(".litertlm", true)) {
            loadLiteRtModel(file, acknowledgedLargeModel)
            return
        }
        if (!::engine.isInitialized) { toast("Engine initialisiert noch."); return }
        val memory = ActivityManager.MemoryInfo().also {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        if (!acknowledgedLargeModel && ModelLoadSafety.requiresConfirmation(file.length(), memory.availMem)) {
            AlertDialog.Builder(this)
                .setTitle("Hoher RAM-Bedarf")
                .setMessage(ModelLoadSafety.explain(file.length(), memory.availMem))
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Trotzdem laden") { _, _ ->
                    loadModel(file, acknowledgedLargeModel = true)
                }.show()
            return
        }
        busy = true
        renderChatState()
        setModelLoading(true, file.name)
        setStatus("Prüfe GGUF-Architektur …")
        diagnosticTrace = PocketDiagnostics.newTrace()
        val modelLoadStarted = SystemClock.elapsedRealtime()
        trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_LOAD,
            PocketDiagnostics.State.START)
        lifecycleScope.launch {
            var previousModelUnloaded = false
            try {
                val inspection = withContext(Dispatchers.IO) { ModelCompatibility.inspect(file) }
                inspection.incompatibleReason?.let { throw IllegalArgumentException(it) }
                setStatus("Lade " + (inspection.architecture ?: "GGUF") + " …")
                // Persist before JNI starts, but commit() fsync can take
                // hundreds of ms on a busy device: do it off the UI thread.
                withContext(Dispatchers.IO) {
                    check(prefs.edit().putString(PREF_LOADING_MODEL, file.name).commit()) {
                        "Prozessdiagnose-Marker konnte nicht gespeichert werden."
                    }
                    liteRtRuntime?.let { previous ->
                        previousModelUnloaded = true
                        // Publish invalidation BEFORE a potentially failing
                        // native shutdown. Never retain a stale released engine.
                        activeModel = null
                        liteRtRuntime = null
                        previous.shutdown()
                    }
                    when (engine.state.value) {
                        is InferenceEngine.State.ModelReady,
                        is InferenceEngine.State.Error -> {
                            previousModelUnloaded = true
                            engine.cleanUp()
                        }
                        else -> Unit
                    }
                    previousModelUnloaded = true
                    try {
                        engine.loadModel(file.absolutePath)
                    } catch (error: IOException) {
                        throw IOException(
                            "Modell: " + file.name + "\n" +
                            "Architektur: " + (inspection.architecture ?: "unbekannt") + "\n" +
                            (ModelCompatibility.explainMissingGemma4Context(
                                error.message.orEmpty()
                            )?.let { it + "\n\n" } ?: "") +
                            (error.message ?: "Natives Modellladen fehlgeschlagen."),
                            error
                        )
                    }
                    // The mode must be set BEFORE the system prompt enters
                    // the KV cache; changing it within an active chat corrupts
                    // incremental Jinja template prefixes.
                    val activeMcp = mcpAgent.current()
                    val thinkingMode = activeMcp?.thinkingMode ?:
                        if (prefs.getBoolean(PREF_ENABLE_THINKING, false)) "high" else "off"
                    engine.setThinkingMode(thinkingMode != "off",
                        if (thinkingMode == "low") "low" else "high")
                    val nativeTools = activeMcp?.let {
                        McpAgentBridge.nativeToolsJson(it.tools)
                    } ?: "[]"
                    engine.setSystemPrompt(groundedSystemPrompt(activeMcp), nativeTools)
                    applySampling()
                }
                activeModel = file
                // A bridge configured in SETUP before model download is now
                // actually bound to the loaded GGUF; update the visible badge.
                updateMcpBadge(mcpAgent.current())
                beginFreshConversation()
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_LOAD,
                    PocketDiagnostics.State.OK,
                    durationMs = SystemClock.elapsedRealtime() - modelLoadStarted)
                setStatus("● BEREIT")
                refreshLibrary()
                showPage(0)
            } catch (error: Exception) {
                failure(PocketDiagnostics.Domain.GGUF,
                    PocketDiagnostics.Step.MODEL_LOAD, error)
                // Preserve the previously loaded model if the preflight is rejected.
                if (previousModelUnloaded) activeModel = null
                setStatus(if (activeModel != null) "● BEREIT" else "Ladefehler")
                showError("Modell/Chat-Template konnte nicht gestartet werden", error)
            } finally {
                prefs.edit().remove(PREF_LOADING_MODEL).apply()
                setModelLoading(false)
                busy = false
                renderChatState()
            }
        }
    }

    /** Separate LiteRT-LM binary model path: try NPU, then GPU, then CPU. */
    private fun loadLiteRtModel(file: File, acknowledgedLargeModel: Boolean = false) {
        if (busy || transferBusy) return
        if (activeModel?.canonicalPath == file.canonicalPath &&
            liteRtRuntime?.hasConversation == true) {
            toast("Dieses LiteRT-Modell ist bereits geladen.")
            return
        }
        val memory = ActivityManager.MemoryInfo().also {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        if (!acknowledgedLargeModel &&
            ModelLoadSafety.requiresConfirmation(file.length(), memory.availMem)) {
            AlertDialog.Builder(this)
                .setTitle("Hoher RAM-Bedarf")
                .setMessage(ModelLoadSafety.explain(file.length(), memory.availMem))
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Trotzdem laden") { _, _ ->
                    loadLiteRtModel(file, acknowledgedLargeModel = true)
                }.show()
            return
        }
        busy = true
        renderChatState()
        setModelLoading(true, file.name)
        setStatus("● LITERT · PRÜFE NPU / GPU / CPU …")
        diagnosticTrace = PocketDiagnostics.newTrace()
        val modelLoadStarted = SystemClock.elapsedRealtime()
        trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_LOAD,
            PocketDiagnostics.State.START)
        lifecycleScope.launch {
            var previousModelUnloaded = false
            try {
                val loaded = withContext(Dispatchers.IO) {
                    ModelStore.validate(file)
                    check(prefs.edit().putString(PREF_LOADING_MODEL, file.name).commit()) {
                        "Prozessdiagnose-Marker konnte nicht gespeichert werden."
                    }
                    // Only now alter runtime ownership. Invalid import files
                    // must not make the already loaded model disappear.
                    // Release the old engine BEFORE initialising the new
                    // one. Mark stale model handles unusable immediately.
                    previousModelUnloaded = true
                    val previousLiteRt = liteRtRuntime
                    liteRtRuntime = null
                    activeModel = null
                    if (previousLiteRt != null) {
                        previousLiteRt.shutdown()
                    }
                    if (::engine.isInitialized && (engine.state.value is InferenceEngine.State.ModelReady ||
                            engine.state.value is InferenceEngine.State.Error)) {
                        engine.cleanUp()
                    }
                    val currentMemory = ActivityManager.MemoryInfo().also {
                        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
                    }
                    if (ModelLoadSafety.insufficientAfterUnload(file.length(), currentMemory.availMem)) {
                        throw IOException(
                            "Nach dem Modellwechsel sind nur " +
                                ModelStore.displaySize(currentMemory.availMem) +
                                " RAM verfügbar. Neues Modell zur Vermeidung eines Android-Low-Memory-Abbruchs " +
                                "nicht gestartet. Andere Apps schließen oder kleineres Modell wählen."
                        )
                    }
                    val prompt = BrumoSystemPrompt.resolve(prefs.getString("system_prompt", null))
                    LiteRtRuntime.load(applicationContext, file, prompt,
                        mcpAgent.current()) { phase ->
                        // Diagnostics are best effort; never break a valid model start.
                        runCatching {
                            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                                .setProcessStateSummary(("PocketAI load=" + file.name.take(45) +
                                    " phase=" + phase).toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                liteRtRuntime = loaded
                activeModel = file
                // LiteRT has its own native function declarations; NEVER
                // invoke these directly without Pocket's existing approval.
                updateMcpBadge(mcpAgent.current())
                beginFreshConversation()
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_LOAD,
                    PocketDiagnostics.State.OK,
                    durationMs = SystemClock.elapsedRealtime() - modelLoadStarted)
                setStatus("● LITERT BEREIT · " + loaded.backendName)
                refreshLibrary()
                showPage(0)
            } catch (error: Exception) {
                failure(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_LOAD, error)
                if (previousModelUnloaded) activeModel = null
                setStatus(if (activeModel != null) "● BEREIT"
                    else "LiteRT-LM konnte nicht gestartet werden")
                showError("LiteRT-Modell/Backend fehlgeschlagen", error)
            } catch (error: LinkageError) {
                failure(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_LOAD, error)
                if (previousModelUnloaded) activeModel = null
                setStatus(if (activeModel != null) "● BEREIT"
                    else "LiteRT-LM Bibliothek nicht verfügbar")
                showError("LiteRT-LM Bibliothek konnte nicht geladen werden", error)
            } finally {
                prefs.edit().remove(PREF_LOADING_MODEL).apply()
                setModelLoading(false)
                busy = false
                renderChatState()
            }
        }
    }

    /** Both CHAT and SETUP open the SAME model bridge configuration.
     * A model does not need to be loaded yet: the optional agent session
     * can be prepared and bound when the GGUF is next loaded. */
    private fun showMcpAgentMenu() {
        if (busy) {
            toast("MCP-Modus erst nach der aktuellen Aktion wechseln.")
            return
        }
        mcpAgent.showMenu(
            openManualTools = { mcpUi.showToolsChooser() },
            openHealthCheck = { showLiveMcpHealth("/mcp health", "") },
            openQuickHelp = { showQuickActionPicker() }
        )
    }

    /** Rebind only the LiteRT conversation and the three short meta tools.
     * Never load a multi-GB model or call a remote MCP tool here.
     */
    private fun rebindLiteRtMcp(active: McpAgentSession?) {
        val runtime = liteRtRuntime
        if (busy || runtime == null) {
            mcpAgent.clearSessionSilently()
            updateMcpBadge(null)
            toast("MCP umschalten erst bei bereitem LiteRT-Modell.")
            return
        }
        busy = true
        renderChatState()
        setStatus("● LITERT MCP · WERKZEUGE WERDEN EINGEBUNDEN …")
        lifecycleScope.launch {
            try {
                runtime.bindMcp(active)
                beginFreshConversation()
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                updateMcpBadge(active)
                setStatus("● LITERT " + runtime.backendName +
                    if (active == null) " · MCP AUS" else " · MCP BEREIT")
            } catch (error: Exception) {
                mcpAgent.clearSessionSilently()
                updateMcpBadge(null)
                setStatus("● LITERT MCP BINDUNG FEHLGESCHLAGEN")
                showError("LiteRT MCP-Funktionen nicht bereit", error)
            } finally {
                busy = false
                renderChatState()
            }
        }
    }

    /** Reset only user/assistant history; NEVER reload model or rerun MCP tools. */
    private fun resetLocalConversation() {
        if (busy || mcpDispatchInFlight || mcpBindingInProgress ||
                generationJob?.isActive == true) {
            toast("Bitte laufende Modell- oder MCP-Aktion zuerst beenden.")
            return
        }
        if (activeModel == null) {
            beginFreshConversation()
            messages.clear()
            chatAdapter.notifyDataSetChanged()
            findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
            setStatus("● CHAT GELEERT")
            return
        }
        busy = true
        renderChatState()
        setStatus("● CHAT WIRD ZURÜCKGESETZT · MODELL BLEIBT GELADEN")
        lifecycleScope.launch {
            try {
                if (activeModel?.name?.endsWith(".litertlm", true) == true) {
                    val runtime = liteRtRuntime ?: error("LiteRT-Modell nicht bereit")
                    runtime.clearConversation()
                    setStatus("● LITERT BEREIT · " + runtime.backendName)
                } else {
                    check(::engine.isInitialized) { "GGUF-Modell nicht bereit" }
                    engine.clearConversationKeepingSystem()
                    setStatus("● BEREIT · MCP-SYSTEMKONTEXT ERHALTEN")
                }
                beginFreshConversation()
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                toast("Chat zurückgesetzt · Modell nicht neu geladen")
            } catch (problem: Exception) {
                // A failed LiteRT conversation re-open may leave a live Engine
                // with no session. Do not advertise a usable model.
                if (activeModel?.name?.endsWith(".litertlm", true) == true &&
                        liteRtRuntime?.hasConversation == false) {
                    withContext(Dispatchers.IO) {
                        try { liteRtRuntime?.shutdown() } catch (_: Exception) {}
                        liteRtRuntime = null
                    }
                    activeModel = null
                    setStatus("LiteRT-Konversation fehlgeschlagen · Modell neu laden")
                } else setStatus("Chat-Reset fehlgeschlagen · Modell bleibt unverändert")
                showError("Schneller Konversationsreset nicht möglich", problem)
            } finally {
                busy = false
                renderChatState()
            }
        }
    }

    private fun groundedSystemPrompt(active: McpAgentSession?): String {
        val configured = BrumoSystemPrompt.resolve(prefs.getString("system_prompt", null))
        val root = configured.trim() + "\n" +
            "Du darfst externe Aktionen nur als abgeschlossen bezeichnen, wenn " +
            "ein echtes Werkzeugresultat ihren Erfolg bestätigt. " +
            "Eine reine Modellantwort beweist niemals Discord- oder Serverzugriff."
        if (active == null) return root
        // Trusted client-owned behaviour is separate from REMOTE,
        // optionally quoted server guidance. No server may change the
        // app-controlled tools/list, approvals or execution limits.
        return root + "\n" +
            McpServerGuidance.trustedDiscoveryContract(
                autoBridge = active.progressiveSources.isNotEmpty()
            ) + McpServerGuidance.optionalPromptBlock(active)
    }

    /** Reset a denied, failed or incomplete native tool turn without
     * any network retry or hidden tool execution. */
    private suspend fun resetUnresolvedNativeToolTurn(active: McpAgentSession): Boolean {
        return try {
            engine.resetConversationWithTools(
                groundedSystemPrompt(active),
                McpAgentBridge.nativeToolsJson(active.tools),
                active.thinkingMode
            )
            true
        } catch (_: Exception) {
            mcpAgent.clearSessionSilently()
            updateMcpBadge(null)
            false
        }
    }

    private fun updateMcpBadge(active: McpAgentSession?) {
        val chip = findViewById<TextView>(R.id.onlineChip)
        val button = findViewById<MaterialButton>(R.id.chatMcpButton)
        val prepared = active != null && activeModel == null
        chip.text = if (mcpBindingInProgress) "● MCP STARTET" else
            if (active == null) "●  AGENT AUS" else
            if (prepared) "●  MCP VORBEREITET"
            else if (active.progressiveSources.isNotEmpty())
                "●  MCP AUTO " + active.thinkingMode.uppercase()
            else "●  MCP " + active.thinkingMode.uppercase()
        chip.contentDescription = if (active == null) {
            "MCP-Agentenmodus ausgeschaltet. Direkte MCP-Abfragen sind weiterhin möglich."
        } else {
            "MCP-Agentenmodus aktiviert mit Thinking " +
                active.thinkingMode.uppercase() + " und ausgewählten Werkzeugen."
        }
        button.text = if (active == null) "MCP" else "MCP ✓"
        chip.setTextColor(getColor(if (active == null) R.color.muted else R.color.secondary))
        val effectiveHints = McpServerGuidance.effectiveHintCount(active)
        val hintStatus = if (effectiveHints > 0)
            " · " + effectiveHints + " sichere Serverhinweise"
        else " · Pocket-AI-Discovery-Regeln"
        findViewById<TextView>(R.id.mcpAgentSettingsStatus).text = when {
            mcpBindingInProgress ->
                "MCP-Systemprompt wird nativ verarbeitet. Werkzeuge sind " +
                "NOCH NICHT aktiv. Fortschritt unter CHAT sichtbar."
            active == null ->
                "AGENT AUS · Unter KI + MCP / AUTO-BRIDGE konfigurieren aktivieren."
            prepared ->
                "MCP-BRIDGE VORBEREITET · " + active.serverName +
                " · GGUF oder LiteRT-LM unter MODELLE laden."
            active.progressiveSources.isNotEmpty() ->
                "MCP AUTO " + active.thinkingMode.uppercase() + " · " +
                active.progressiveSources.size + " ausgewählte Server · " +
                "echte Toolaufrufe nur nach Freigabe." + hintStatus
            else ->
                "MCP " + active.thinkingMode.uppercase() + " · " +
                active.tools.size + " ausgewählte Werkzeuge · " +
                "echte Toolaufrufe nur nach Freigabe." + hintStatus
        }
    }

    /**
     * A system prompt is synchronously decoded by llama.cpp on its own
     * dispatcher. Read-only JNI progress uses atomic counters and NEVER
     * waits for the busy llama dispatcher. The Android UI remains free
     * to navigate or type (sending is disabled until ModelReady).
     */
    private fun beginMcpBindingProgress() {
        mcpBindingInProgress = true
        mcpBindStartedAtMs = SystemClock.uptimeMillis()
        mcpBindLastState = McpPrefillStatus.State(0, 0)
        val progress = findViewById<ProgressBar>(R.id.mcpBindProgress)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        mcpBindPoll?.let { uiPulseHandler.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (!mcpBindingInProgress || !::engine.isInitialized) return
                mcpBindLastState = runCatching {
                    McpPrefillStatus.unpack(engine.systemPromptPrefillCounts())
                }.getOrElse { McpPrefillStatus.State(0, 0) }
                val elapsed = SystemClock.uptimeMillis() - mcpBindStartedAtMs
                setStatus(McpPrefillStatus.label(mcpBindLastState, elapsed))
                if (mcpBindLastState.started) {
                    progress.isIndeterminate = false
                    progress.max = mcpBindLastState.total
                    progress.progress = mcpBindLastState.decoded
                } else progress.isIndeterminate = true
                // Stop polling when Activity is in the background, resume
                // when it is visible again. Never create extra UI work.
                if (uiPulseEnabled) uiPulseHandler.postDelayed(this, 350L)
            }
        }
        mcpBindPoll = poll
        if (uiPulseEnabled) uiPulseHandler.post(poll)
    }

    private fun finishMcpBindingProgress(completed: Boolean) {
        val elapsed = SystemClock.uptimeMillis() - mcpBindStartedAtMs
        // Capture final native counters after the synchronous reset returns.
        // Otherwise a fast batch might finish between 350ms UI samples.
        if (::engine.isInitialized) {
            mcpBindLastState = runCatching {
                McpPrefillStatus.unpack(engine.systemPromptPrefillCounts())
            }.getOrElse { mcpBindLastState }
        }
        if (mcpBindingInProgress) {
            lastMcpBindingReport = McpPrefillStatus.report(
                mcpBindLastState, elapsed, completed
            )
        }
        mcpBindingInProgress = false
        mcpBindPoll?.let { uiPulseHandler.removeCallbacks(it) }
        mcpBindPoll = null
        findViewById<ProgressBar>(R.id.mcpBindProgress).visibility = View.GONE
    }

    /**
     * The Jinja tool block is part of the pinned SYSTEM prefix. Do not mutate
     * it while the KV cache contains older turns. Reset the local conversation
     * atomically on the native inference dispatcher before chat can resume.
     */
    private fun rebindNativeMcpTools(active: McpAgentSession?) {
        if (busy || !::engine.isInitialized || activeModel == null) {
            mcpAgent.clearSessionSilently()
            updateMcpBadge(null)
            toast("MCP-Werkzeugwechsel nur bei bereitstehendem Modell möglich.")
            return
        }
        val json = try {
            active?.let { McpAgentBridge.nativeToolsJson(it.tools) } ?: "[]"
        } catch (problem: Exception) {
            mcpAgent.clearSessionSilently()
            updateMcpBadge(null)
            showError("MCP-Funktionsschema ungültig", problem)
            return
        }
        busy = true
        diagnosticTrace = PocketDiagnostics.newTrace()
        trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_RESET,
            PocketDiagnostics.State.START)
        beginMcpBindingProgress()
        updateMcpBadge(active)
        renderChatState()
        setStatus("● MCP-PREFILL wird vorbereitet …")
        lifecycleScope.launch {
            try {
                val thinkingMode = active?.thinkingMode ?:
                    if (prefs.getBoolean(PREF_ENABLE_THINKING, false)) "high" else "off"
                engine.resetConversationWithTools(
                    groundedSystemPrompt(active), json, thinkingMode
                )
                beginFreshConversation()
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                // The MCP badge becomes ACTIVE only after the complete
                // synchronous native KV prefill returns ModelReady.
                finishMcpBindingProgress(completed = true)
                trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_RESET,
                    PocketDiagnostics.State.OK,
                    durationMs = SystemClock.uptimeMillis() - mcpBindStartedAtMs)
                updateMcpBadge(active)
                setStatus("● BEREIT")
                toast(
                    if (active == null) "MCP ausgeschaltet · Chatkontext neu gestartet"
                    else "Spark hat " + active.tools.size +
                        " native MCP-Tools eingebunden, Thinking " +
                        active.thinkingMode.uppercase() + " · Chatkontext neu gestartet"
                )
            } catch (problem: Exception) {
                failure(PocketDiagnostics.Domain.GGUF,
                    PocketDiagnostics.Step.MODEL_RESET, problem)
                mcpAgent.clearSessionSilently()
                setStatus("MCP-Template-Fehler · Modell bitte neu laden")
                showError("Native MCP-Funktionen konnten nicht eingebunden werden", problem)
            } finally {
                if (mcpBindingInProgress) finishMcpBindingProgress(completed = false)
                updateMcpBadge(mcpAgent.current())
                busy = false
                renderChatState()
            }
        }
    }

    /**
     * A protocol health request is not a modelling problem: directly probe
     * the selected MCP endpoint and return a bounded, evidence-backed receipt.
     */
    private fun showLiveMcpHealth(question: String, target: String) {
        val profiles = try {
            McpVault(applicationContext).list()
        } catch (problem: Exception) {
            showError("MCP-Tresor konnte nicht gelesen werden", problem)
            return
        }
        val candidates = profiles.filter { McpHealthCheck.relevantProfile(it, target) }
        if (candidates.isEmpty()) {
            messageInput.setText("")
            appendReadOnlyChat(question, McpHealthCheck.missingProfile(target))
            return
        }
        val activeId = mcpAgent.current()?.profileId
        val preferred = candidates.firstOrNull { it.id == activeId }
        if (preferred != null || candidates.size == 1) {
            runMcpHealth(question, preferred ?: candidates.single())
        } else {
            AlertDialog.Builder(this)
                .setTitle("MCP-Health-Check: Server auswählen")
                .setItems(candidates.map { it.title + " · " + it.endpoint }.toTypedArray()) { _, index ->
                    runMcpHealth(question, candidates[index])
                }
                .setNegativeButton("Abbrechen", null).show()
        }
    }

    private fun runMcpHealth(question: String, profile: McpProfile) {
        if (busy) { toast("Pocket AI arbeitet bereits."); return }
        diagnosticTrace = PocketDiagnostics.newTrace()
        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_START,
            PocketDiagnostics.State.START)
        messageInput.setText("")
        messages.add(ChatMessage(true, question))
        messages.add(ChatMessage(false,
            "🔎 Prüfe MCP-Verbindung und tools/list am echten Server …"))
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
        requestChatPersist()
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        showPage(0)
        messagesView.scrollToPosition(index)
        busy = true
        renderChatState()
        setStatus("● MCP HEALTH-CHECK …")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val vault = McpVault(applicationContext)
                    val fresh = vault.get(profile.id)
                        ?: throw IllegalStateException("MCP-Profil wurde entfernt")
                    if (fresh.auth == McpAuth.OAUTH &&
                        fresh.oauthAccessToken.isBlank() &&
                        fresh.oauthRefreshToken.isBlank()) {
                        McpHealthCheck.loginRequired(fresh)
                    } else {
                        val authorized = McpOAuth(vault).refreshIfNeeded(fresh)
                        val started = SystemClock.elapsedRealtime()
                        val server = McpConnection.inspect(authorized)
                        val duration = SystemClock.elapsedRealtime() - started
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_DONE,
                            PocketDiagnostics.State.OK, count = server.tools.size,
                            durationMs = duration)
                        McpHealthCheck.success(authorized, server, duration)
                    }
                }
                messages[index].content = result
                setStatus("● MCP PRÜFUNG BEENDET")
            } catch (problem: Exception) {
                failure(PocketDiagnostics.Domain.MCP,
                    PocketDiagnostics.Step.DISCOVERY_DONE, problem)
                messages[index].content = McpHealthCheck.failure(profile, problem)
                setStatus("● MCP NICHT BESTÄTIGT")
            } finally {
                chatAdapter.notifyItemChanged(index)
                messagesView.scrollToPosition(index)
                busy = false
                renderChatState()
            }
        }
    }

    /**
     * Read-only capability probe for TriForce logs. This does NOT access host
     * logs: when tools/list contains no log reader, stop before Spark can
     * pretend it inspected anything. A real log reader requires a separately
     * verified server tool and explicit user approval.
     */
    private fun showLiveMcpLogCapability(question: String) {
        val profiles = try {
            McpVault(applicationContext).list()
        } catch (problem: Exception) {
            showError("MCP-Tresor konnte nicht gelesen werden", problem)
            return
        }
        val choices = profiles.filter { McpLogCheck.relevantProfile(it) }
        if (choices.isEmpty()) {
            messageInput.setText("")
            appendReadOnlyChat(question, McpLogCheck.missingProfile())
            return
        }
        val activeId = mcpAgent.current()?.profileId
        val preferred = choices.firstOrNull { it.id == activeId }
        if (preferred != null || choices.size == 1) {
            runMcpLogCapability(question, preferred ?: choices.single())
        } else {
            AlertDialog.Builder(this)
                .setTitle("TriForce-Logzugriff · Online-MCP wählen")
                .setItems(choices.map { it.title + " · " + it.endpoint }.toTypedArray()) { _, i ->
                    runMcpLogCapability(question, choices[i])
                }
                .setNegativeButton("Abbrechen", null).show()
        }
    }

    private fun runMcpLogCapability(question: String, profile: McpProfile) {
        if (busy) { toast("Pocket AI arbeitet bereits."); return }
        diagnosticTrace = PocketDiagnostics.newTrace()
        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_START,
            PocketDiagnostics.State.START)
        messageInput.setText("")
        messages.add(ChatMessage(true, question))
        messages.add(ChatMessage(false, "🔎 Prüfe reale Log-Werkzeugfreigaben mit tools/list …"))
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
        requestChatPersist()
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        showPage(0)
        messagesView.scrollToPosition(index)
        busy = true
        renderChatState()
        setStatus("● LOG-ZUGRIFF PRÜFEN …")
        lifecycleScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    val vault = McpVault(applicationContext)
                    val fresh = vault.get(profile.id)
                        ?: error("MCP-Profil wurde entfernt")
                    require(McpLogCheck.relevantProfile(fresh) &&
                        fresh.endpoint == profile.endpoint && fresh.auth == profile.auth) {
                        "MCP-Profil hat sich geändert; bitte neu auswählen."
                    }
                    if (fresh.auth == McpAuth.OAUTH &&
                        fresh.oauthAccessToken.isBlank() && fresh.oauthRefreshToken.isBlank()
                    ) {
                        McpLogCheck.failure(fresh,
                            IllegalStateException("OAuth-Anmeldung fehlt"))
                    } else {
                        val authorized = McpOAuth(vault).refreshIfNeeded(fresh)
                        val info = McpConnection.inspect(authorized)
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_DONE,
                            PocketDiagnostics.State.OK, count = info.tools.size)
                        McpLogCheck.capabilityReport(authorized, info)
                    }
                }
                messages[index].content = reply
                setStatus("● LOG-TOOLLISTE BESTÄTIGT")
            } catch (problem: Exception) {
                failure(PocketDiagnostics.Domain.MCP,
                    PocketDiagnostics.Step.DISCOVERY_DONE, problem)
                messages[index].content = McpLogCheck.failure(profile, problem)
                setStatus("● LOG-ZUGRIFF UNBESTÄTIGT")
            } finally {
                chatAdapter.notifyItemChanged(index)
                messagesView.scrollToPosition(index)
                busy = false
                renderChatState()
            }
        }
    }

    /** A tool inventory is a real authenticated tools/list call, never LLM prose. */
    private fun showLiveMcpCatalog(question: String, filter: String) {
        val profiles = try {
            McpVault(applicationContext).list()
        } catch (problem: Exception) {
            showError("MCP-Tresor konnte nicht gelesen werden", problem)
            return
        }
        val candidates = if (filter.equals("discord", true)) {
            profiles.filter { McpToolCatalog.isDiscordServer(it) }
        } else profiles
        if (candidates.isEmpty()) {
            messageInput.setText("")
            appendReadOnlyChat(question, McpToolCatalog.missingProfile(filter))
            return
        }
        val activeId = mcpAgent.current()?.profileId
        val preferred = candidates.firstOrNull { it.id == activeId }
        if (preferred != null || candidates.size == 1) {
            readMcpToolCatalog(question, filter, preferred ?: candidates.single())
        } else {
            AlertDialog.Builder(this)
                .setTitle("Welche MCP-Verbindung abfragen?")
                .setItems(candidates.map { it.title + " · " + it.endpoint }.toTypedArray()) { _, index ->
                    readMcpToolCatalog(question, filter, candidates[index])
                }
                .setNegativeButton("Abbrechen", null).show()
        }
    }

    private fun appendReadOnlyChat(question: String, answer: String) {
        messages.add(ChatMessage(true, question))
        messages.add(ChatMessage(false, answer))
        chatAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        showPage(0)
        messagesView.scrollToPosition(messages.lastIndex)
    }

    private fun readMcpToolCatalog(question: String, filter: String, profile: McpProfile) {
        if (busy) { toast("Pocket AI arbeitet bereits."); return }
        diagnosticTrace = PocketDiagnostics.newTrace()
        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_START,
            PocketDiagnostics.State.START)
        messageInput.setText("")
        messages.add(ChatMessage(true, question))
        messages.add(ChatMessage(false, "🔎 MCP tools/list wird direkt am Server abgefragt …"))
        val answerIndex = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(answerIndex - 1, 2)
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        showPage(0)
        messagesView.scrollToPosition(answerIndex)
        busy = true
        renderChatState()
        setStatus("● MCP TOOLLISTE …")
        lifecycleScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    val vault = McpVault(applicationContext)
                    val fresh = vault.get(profile.id) ?: error("MCP-Server wurde entfernt.")
                    if (fresh.auth == McpAuth.OAUTH && fresh.oauthAccessToken.isBlank()) {
                        McpToolCatalog.loginRequired(fresh)
                    } else {
                        val authorized = McpOAuth(vault).refreshIfNeeded(fresh)
                        val actual = McpConnection.inspect(authorized)
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_DONE,
                            PocketDiagnostics.State.OK, count = actual.tools.size)
                        McpToolCatalog.format(authorized, actual, filter)
                    }
                }
                messages[answerIndex].content = reply
                setStatus("● BEREIT")
            } catch (problem: Exception) {
                val reason = if (problem is McpHttpFailure &&
                    problem.code in setOf(401, 403)) {
                    McpToolCatalog.loginRequired(profile)
                } else {
                    val id = failure(PocketDiagnostics.Domain.MCP,
                        PocketDiagnostics.Step.DISCOVERY_DONE, problem)
                    "⚠️ MCP-Toolliste konnte nicht live abgefragt werden.\n" +
                        "Grund: " + getString(R.string.diagnostics_error,
                            PocketDiagnostics.publicError(problem), id) +
                        "\nKeine Toolfunktionen erfunden oder ausgeführt."
                }
                messages[answerIndex].content = reason
                setStatus("● MCP NICHT VERBUNDEN")
            } finally {
                chatAdapter.notifyItemChanged(answerIndex)
                messagesView.scrollToPosition(answerIndex)
                busy = false
                renderChatState()
            }
        }
    }

    /**
     * Expose discoverable quick actions in the MCP menu. Picking a command
     * only fills the composer; user presses Send and approves the tool once.
     */
    private fun showQuickActionPicker() {
        val actions = listOf(
            "Discord: Kontoverbindung prüfen" to "/mcp quick discord-status",
            "Discord: freigegebene Server" to "/mcp quick discord-guilds",
            "Discord: Nova-Voice-Status" to "/mcp quick discord-voice",
            "TriForce: tatsächlicher Systemstatus" to "/mcp quick triforce-status",
            "TriForce: echten Log-Zugriff prüfen (ohne Spark)" to "/mcp logs triforce",
            "TriForce: Benachrichtigungen lesen" to "/mcp quick notifications",
            "TriForce: Websuche" to "/mcp search ",
            "TriForce: Code durchsuchen" to "/mcp code-search ",
            "TriForce: Speicher durchsuchen" to "/mcp memory-search "
        )
        AlertDialog.Builder(this)
            // Android AlertDialog cannot show setMessage and setItems
            // together reliably. The list itself must be the content.
            .setTitle("MCP-Schnellbefehle · vor Ausführung freigeben")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                messageInput.setText(actions[index].second)
                messageInput.setSelection(messageInput.text.length)
                showPage(0)
            }
            .setNegativeButton("Schließen", null).show()
    }

    /** Direct, one-shot read tools bypass Spark and never infer tool names. */
    private fun showQuickMcpAction(
        question: String, request: McpQuickActions.Selection.Request
    ) {
        val profiles = try {
            McpVault(applicationContext).list()
        } catch (problem: Exception) {
            showError("MCP-Tresor konnte nicht gelesen werden", problem)
            return
        }
        val candidates = profiles.filter {
            McpQuickActions.matchesProfile(it, request.target)
        }
        if (candidates.isEmpty()) {
            messageInput.setText("")
            appendReadOnlyChat(question, McpQuickActions.unavailable(request.target))
            return
        }
        val current = mcpAgent.current()?.profileId
        val preferred = candidates.firstOrNull { it.id == current }
        if (preferred != null || candidates.size == 1) {
            runQuickMcpAction(question, request, preferred ?: candidates.single())
        } else {
            AlertDialog.Builder(this)
                .setTitle("Direkte MCP-Leseabfrage: Server auswählen")
                .setItems(candidates.map { it.title + " · " + it.endpoint }.toTypedArray()) { _, index ->
                    runQuickMcpAction(question, request, candidates[index])
                }
                .setNegativeButton("Abbrechen", null).show()
        }
    }

    /**
     * Discover -> validate -> ask consent -> single tool call -> raw receipt.
     * OAuth refresh and all MCP traffic stay off the Android UI thread.
     * No retry is safe after a possibly executed call.
     */
    private fun runQuickMcpAction(
        question: String,
        request: McpQuickActions.Selection.Request,
        profile: McpProfile
    ) {
        if (busy) { toast("Pocket AI arbeitet bereits."); return }
        diagnosticTrace = PocketDiagnostics.newTrace()
        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_START,
            PocketDiagnostics.State.START)
        messageInput.setText("")
        messages.add(ChatMessage(true, question))
        messages.add(ChatMessage(false, "🔎 Prüfe verfügbares MCP-Lesewerkzeug direkt am Server …"))
        val answerIndex = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(answerIndex - 1, 2)
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        showPage(0)
        messagesView.scrollToPosition(answerIndex)
        busy = true
        renderChatState()
        setStatus("● MCP WERKZEUG PRÜFEN …")
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val vault = McpVault(applicationContext)
                    val fresh = vault.get(profile.id)
                        ?: error("MCP-Profil wurde entfernt")
                    require(
                        fresh.endpoint == profile.endpoint &&
                        fresh.auth == profile.auth &&
                        McpQuickActions.matchesProfile(fresh, request.target)
                    ) { "MCP-Profil hat sich geändert. Bitte erneut auswählen." }
                    if (fresh.auth == McpAuth.OAUTH &&
                        fresh.oauthAccessToken.isBlank() &&
                        fresh.oauthRefreshToken.isBlank()) {
                        null
                    } else {
                        val authorized = McpOAuth(vault).refreshIfNeeded(fresh)
                        val info = McpConnection.inspect(authorized)
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_DONE,
                            PocketDiagnostics.State.OK, count = info.tools.size)
                        val proposal = McpQuickActions.validatedProposal(request, info)
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PROPOSAL,
                            PocketDiagnostics.State.OK, tool = proposal.tool.name)
                        Pair(authorized, proposal)
                    }
                }
                if (prepared == null) {
                    messages[answerIndex].content = McpQuickActions.noOAuth(profile)
                    setStatus("● MCP LOGIN NÖTIG")
                } else {
                    val (authorized, proposal) = prepared
                    messages[answerIndex].content =
                        "🔎 Werkzeug bestätigt: " + proposal.tool.name +
                        "\nServer: " + authorized.title +
                        "\nAufruf benötigt deine ausdrückliche Freigabe."
                    chatAdapter.notifyItemChanged(answerIndex)
                    setStatus("● MCP FREIGABE …")
                    val approved = mcpAgent.requestQuickReadApproval(authorized, proposal)
                    if (!approved) {
                        messages[answerIndex].content =
                            "⛔ MCP-Leseabfrage abgelehnt. Kein Werkzeug aufgerufen."
                        setStatus("● MCP ABGELEHNT")
                    } else {
                        messages[answerIndex].content =
                            "🔧 Freigegeben: Anfrage an den echten MCP-Server wird gesendet …"
                        chatAdapter.notifyItemChanged(answerIndex)
                        setStatus("● MCP LESEABFRAGE …")
                        mcpDispatchInFlight = true
                        val started = SystemClock.elapsedRealtime()
                        val dispatchState = McpDispatchState()
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val latest = McpVault(applicationContext).get(profile.id)
                                    ?: error("MCP-Profil wurde entfernt")
                                check(latest.endpoint == authorized.endpoint &&
                                      latest.auth == authorized.auth) {
                                    "MCP-Profil wurde während der Freigabe geändert"
                                }
                                trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.AUTH_CHECK,
                                    PocketDiagnostics.State.OK, tool = proposal.tool.name)
                                PocketDiagnostics.markDispatch(applicationContext,
                                    diagnosticTrace, proposal.tool.name)
                                dispatchState.markAttempted()
                                try {
                                    McpConnection.callTool(authorized, proposal.tool, proposal.args)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    throw McpExecutionUncertain(error)
                                }
                            }
                            val phase = PocketDiagnostics.resultPhase(result)
                            trace(PocketDiagnostics.Domain.MCP, phase.first, phase.second,
                                tool = proposal.tool.name,
                                durationMs = SystemClock.elapsedRealtime() - started)
                            messages[answerIndex].content =
                                McpQuickActions.displayReceipt(proposal.tool, result)
                            setStatus("● MCP SERVERANTWORT")
                        } catch (problem: Exception) {
                            val id = failure(PocketDiagnostics.Domain.MCP,
                                PocketDiagnostics.Step.REMOTE_ERROR, problem,
                                uncertain = dispatchState.unconfirmed(problem))
                            messages[answerIndex].content =
                                (if (dispatchState.unconfirmed(problem))
                                    "⚠️ MCP-Ergebnis unbekannt (Anfrage möglicherweise ausgeführt).\n"
                                else "⚠️ MCP-Aufruf vor dem Senden abgebrochen.\n") +
                                "Werkzeug: " + proposal.tool.name + "\n" +
                                getString(R.string.diagnostics_error,
                                    PocketDiagnostics.publicError(problem), id) +
                                "\nKein automatischer Wiederholungsversuch."
                            setStatus("● MCP UNBESTÄTIGT")
                        } finally {
                            mcpDispatchInFlight = false
                        }
                    }
                }
            } catch (problem: Exception) {
                val detail = if (problem is McpHttpFailure &&
                    problem.code in setOf(401, 403)) {
                    "HTTP " + problem.code + ": Zugriff verweigert oder Login erforderlich."
                } else {
                    (problem.message ?: "Keine MCP-Serverantwort").take(550)
                }
                val id = failure(PocketDiagnostics.Domain.MCP,
                    PocketDiagnostics.Step.AUTH_CHECK, problem)
                messages[answerIndex].content =
                    "⚠️ MCP-Werkzeug nicht ausgeführt.\n" +
                    getString(R.string.diagnostics_error,
                        PocketDiagnostics.publicError(problem), id) +
                    "\nKein Erfolg bestätigt."
                setStatus("● MCP UNBESTÄTIGT")
            } finally {
                chatAdapter.notifyItemChanged(answerIndex)
                messagesView.scrollToPosition(answerIndex)
                busy = false
                renderChatState()
            }
        }
    }

    /**
     * RC11 native progressive MCP orchestration: three local, bounded
     * model-visible meta-functions and at most ONE real server tools/call.
     *
     * All terminal data and failures are shown as separate evidence.
     * The native llama.cpp tool-role is continued only from a genuine
     * assistant <tool_call>. JSON fallback cannot spoof a native turn.
     */
    private suspend fun runProgressiveToolbridge(
        active: McpAgentSession,
        firstModelOutput: String,
        request: String,
        answer: ChatMessage,
        answerIndex: Int
    ): Boolean {
        val runtime = withContext(Dispatchers.Default) {
            McpProgressiveBridge.Run(active.progressiveSources, System.currentTimeMillis())
        }
        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_DONE,
            PocketDiagnostics.State.OK, count = runtime.totalTools)
        var modelOutput = firstModelOutput
        var serverResult: JSONObject? = null
        var hadSearch = false
        var hadRealCall = false

        fun showEvidence(message: String) {
            messages.add(ChatMessage(false, message))
            chatAdapter.notifyItemInserted(messages.lastIndex)
            messagesView.scrollToPosition(messages.lastIndex)
        }

        suspend fun deliver(tool: McpTool, result: JSONObject): String {
            val payload = McpToolFeedback.nativePayload(tool, result)
            val continuation = StringBuilder()
            setStatus("● AUTO-BRIDGE · NATIVES TOOLERGEBNIS …")
            withContext(Dispatchers.Default) {
                engine.sendToolResult(tool.name, payload, 256).collect {
                    continuation.append(it)
                }
            }
            return continuation.toString()
        }

        for (step in 0 until McpProgressiveBridge.MAX_STEPS) {
            check(mcpAgent.current() == active) {
                "MCP-Agent wurde während der Auto-Bridge-Sitzung deaktiviert."
            }
            when (val decision = McpAgentBridge.parse(
                modelOutput, active.tools,
                thinkingEnabled = active.thinkingMode != "off"
            )) {
                is McpAgentBridge.Decision.Invalid -> {
                    trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PLAN,
                        PocketDiagnostics.State.FAIL)
                    answer.content = "⚠️ Auto-Bridge: Kein gültiger Toolaufruf. " +
                        decision.reason + "\nKeine ungeprüfte MCP-Aktion ausgeführt."
                    chatAdapter.notifyItemChanged(answerIndex)
                    return false
                }
                is McpAgentBridge.Decision.Answer -> {
                    trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PLAN,
                        if (hadRealCall) PocketDiagnostics.State.OK
                        else PocketDiagnostics.State.INFO)
                    if (!hadRealCall && McpRequestRouting.expectsToolEvidence(request))
                        trace(PocketDiagnostics.Domain.GGUF,
                            PocketDiagnostics.Step.MODEL_TOOL_MISSING,
                            PocketDiagnostics.State.INFO)
                    // Native discovery may already have searched or loaded details.
                    // If a real call did not follow, continue the SAME
                    // authorized plan through the approved host fallback.
                    if (!hadRealCall && McpRequestRouting.expectsToolEvidence(request) &&
                        tryHostDiscovery(active, request, runtime, answer, answerIndex)) {
                        return true
                    }
                    val response = decision.text.ifBlank { "Keine Modellantwort erzeugt." }
                    val unverifiedLogs = McpLogCheck.modelOnlyAuditClaim(request, response)
                    val unverifiedHealth = McpHealthCheck.modelOnlyClaim(response)
                    val misleadingError = serverResult?.let {
                        McpToolFeedback.misleadingSummary(it, response)
                    } == true
                    if (unverifiedLogs || unverifiedHealth || misleadingError) {
                        showEvidence(
                            "⚠️ Modellbehauptung nicht durch einen entsprechenden " +
                            "MCP-Aufruf bestätigt. Die tatsächlichen " +
                            "Serverdaten stehen oben.")
                    } else {
                        showEvidence(
                            if (hadRealCall)
                                "ⓘ Modellzusammenfassung des echten MCP-Ergebnisses:\n" + response
                            else if (hadSearch)
                                "ⓘ Modellantwort nach lokaler MCP-Werkzeugsuche " +
                                "(keine Serveraktion ausgeführt):\n" + response
                            else "ⓘ Nur Modellantwort; keine MCP-Werkzeuge benutzt:\n" + response
                        )
                    }
                    answer.content = when {
                        hadRealCall -> "🔧 Auto-Bridge: Echter Serveraufruf abgeschlossen; " +
                            "Originalergebnis separat angezeigt."
                        hadSearch -> "🔎 Auto-Bridge: Werkzeugkatalog aus " +
                            "authentifizierter tools/list geprüft (keine Aktion)."
                        else -> "ⓘ Das Modell hat keinen MCP-Aufruf angefordert."
                    }
                    chatAdapter.notifyItemChanged(answerIndex)
                    return true
                }
                is McpAgentBridge.Decision.Proposed -> {
                    // JSON fallback can propose an actual tool manually, but
                    // NEVER insert a forged native tool result into its KV.
                    if (!decision.nativeToolCall ||
                        !McpProgressiveBridge.isMetaTool(decision.tool.name)) {
                        answer.content = "⚠️ Auto-Bridge: Kein nativer, freigegebener " +
                            "Meta-Toolaufruf. Keine Aktion ausgeführt."
                        chatAdapter.notifyItemChanged(answerIndex)
                        return false
                    }
                    if (hadRealCall) {
                        showEvidence(McpToolFeedback.noMoreCalls())
                        return false
                    }

                    val meta = decision.tool.name
                    val args = decision.args
                    val outcome: JSONObject
                    if (meta == McpProgressiveBridge.SEARCH) {
                        val searchStart = SystemClock.elapsedRealtime()
                        outcome = try {
                            runtime.search(
                                args.getString("query"),
                                args.optInt("limit", McpProgressiveBridge.MAX_RESULTS)
                            )
                        } catch (issue: Exception) {
                            McpProgressiveBridge.localFailure(issue.message.orEmpty())
                        }
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.SEARCH,
                            if (outcome.optBoolean("isError", false))
                                PocketDiagnostics.State.FAIL else PocketDiagnostics.State.OK,
                            durationMs = SystemClock.elapsedRealtime() - searchStart)
                        if (!outcome.optBoolean("isError", false)) {
                            hadSearch = true
                        }
                        showEvidence(
                            McpProgressiveBridge.displaySearchResult(outcome)
                        )
                    } else if (meta == McpProgressiveBridge.DETAILS) {
                        val detailsStart = SystemClock.elapsedRealtime()
                        outcome = try {
                            runtime.details(args.getString("tool_id")).also { result ->
                                trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DETAILS,
                                    if (result.optBoolean("isError", false))
                                        PocketDiagnostics.State.FAIL
                                    else PocketDiagnostics.State.OK,
                                    durationMs = SystemClock.elapsedRealtime() - detailsStart)
                            }
                        } catch (issue: Exception) {
                            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DETAILS,
                                PocketDiagnostics.State.FAIL,
                                durationMs = SystemClock.elapsedRealtime() - detailsStart,
                                code = PocketDiagnostics.classify(issue))
                            McpProgressiveBridge.localFailure(issue.message.orEmpty())
                        }
                        showEvidence(
                            "📋 MCP-Tooldetails aus dem geprüften Katalog " +
                            "(nicht ausgeführt):\n" +
                            outcome.optJSONArray("content")
                                ?.optJSONObject(0)?.optString("text")?.take(1250).orEmpty()
                        )
                    } else {
                        // Last meta tool = exact real server dispatch, gated
                        // through explicit consent and live schema recheck.
                        val plan = try {
                            runtime.prepareCall(
                                args.getString("tool_id"),
                                args.getString("arguments_json")
                            )
                        } catch (issue: Exception) {
                            failure(PocketDiagnostics.Domain.MCP,
                                PocketDiagnostics.Step.SCHEMA_CHECK, issue)
                            val localError = McpProgressiveBridge.localFailure(
                                issue.message.orEmpty()
                            )
                            showEvidence(
                                "⚠️ Kein externer MCP-Aufruf: " +
                                issue.message.orEmpty().take(260)
                            )
                            modelOutput = deliver(decision.tool, localError)
                            continue
                        }
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PROPOSAL,
                            PocketDiagnostics.State.OK, tool = plan.tool.name)
                        answer.content = "🔐 Auto-Bridge schlägt vor: " +
                            plan.source.title + " → " + plan.tool.name +
                            "\nEchter MCP-Aufruf noch NICHT ausgeführt."
                        chatAdapter.notifyItemChanged(answerIndex)
                        setStatus("● AUTO-BRIDGE · FREIGABE …")
                        val approved = mcpAgent.requestAutoApproval(active, plan)
                        if (!approved) {
                            showEvidence(
                                "⛔ Echter MCP-Aufruf abgelehnt. Keine Serveraktion ausgeführt."
                            )
                            return false
                        }
                        // Mark before any potentially side-effecting request,
                        // so a timeout can never trigger a retry in this run.
                        runtime.markDispatched(plan)
                        setStatus("● AUTO-BRIDGE · EIN MCP-AUFRUF …")
                        mcpDispatchInFlight = true
                        val dispatchState = McpDispatchState()
                        try {
                            outcome = withContext(Dispatchers.IO) {
                                mcpAgent.executeAuto(active, plan, dispatchState)
                            }
                        } catch (issue: Exception) {
                            val eventId = failure(PocketDiagnostics.Domain.MCP,
                                PocketDiagnostics.Step.REMOTE_ERROR, issue,
                                uncertain = dispatchState.unconfirmed(issue))
                            showEvidence(
                                (if (dispatchState.unconfirmed(issue))
                                    "⚠️ MCP-Ergebnis unbekannt: " else
                                    "⚠️ MCP-Aufruf vor dem Senden abgebrochen: ") +
                                getString(R.string.diagnostics_error,
                                    PocketDiagnostics.publicError(issue), eventId) +
                                "\nKEIN automatischer Wiederholungsversuch."
                            )
                            return false
                        } finally {
                            mcpDispatchInFlight = false
                        }
                        hadRealCall = true
                        serverResult = outcome
                        showEvidence(
                            "🔧 ECHTE MCP-SERVERANTWORT · " +
                            plan.source.title + " / " + plan.tool.name + "\n" +
                            McpAgentBridge.receipt(plan.tool, outcome)
                        )
                        val terminal = McpToolFeedback.terminalMessage(outcome)
                        if (terminal != null) {
                            showEvidence(terminal)
                            return false
                        }
                    }
                    // Local meta tool results and the single remote result
                    // both become real native Jinja tool-role messages.
                    modelOutput = deliver(decision.tool, outcome)
                    trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_FOLLOWUP,
                        PocketDiagnostics.State.OK, tool = decision.tool.name)
                    if (step == McpProgressiveBridge.MAX_STEPS - 1) {
                        showEvidence(
                            "⚠️ Maximale Anzahl Bridge-Schritte erreicht; " +
                            "keine weitere MCP-Aktion erlaubt.")
                        return false
                    }
                }
            }
        }
        return false
    }

    private fun sendMessage() {
        if (!chatReady) { toast("Lade Chatverlauf ..."); return }
        if (busy) { toast("Pocket AI ist beschäftigt."); return }
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        if (text.equals("/debug", true) || text.equals("/mcp debug", true) ||
            text.equals("/mcp diagnose", true)) {
            messageInput.setText("")
            showDiagnosticTimeline(inChat = true)
            return
        }
        // When an MCP agent is active, plain-language work belongs to the
        // model's native ToolCall planner. Only explicit app diagnostics,
        // slash commands and unambiguous inventory requests bypass it.
        val modelToolRequest = McpRequestRouting.useModelAgent(
            text, mcpAgent.current() != null && activeModel != null
        )
        if (!modelToolRequest) {
            // Log inspection is NOT a health-check. Preserve the direct
            // diagnostic for explicitly requested app commands or no agent.
            if (McpLogCheck.requested(text)) {
                showLiveMcpLogCapability(text)
                return
            }
            McpHealthCheck.requestedTarget(text)?.let {
                showLiveMcpHealth(text, it)
                return
            }
            McpToolCatalog.requestedFilter(text)?.let {
                showLiveMcpCatalog(text, it)
                return
            }
            when (val direct = McpQuickActions.requestedAction(text)) {
                is McpQuickActions.Selection.Request -> {
                    showQuickMcpAction(text, direct)
                    return
                }
                is McpQuickActions.Selection.Invalid -> {
                    messageInput.setText("")
                    appendReadOnlyChat(text, "⚠️ " + direct.reason)
                    return
                }
                null -> Unit
            }
        }
        if (!::engine.isInitialized || activeModel == null) {
            toast("Bitte zuerst ein Modell laden. Für MCP geht auch /mcp tools.")
            return
        }
        if (activeModel?.name?.endsWith(".litertlm", true) == true) {
            if (text.length > 16_000) {
                toast("Nachricht zu lang")
                return
            }
            sendLiteRtMessage(text)
            return
        }
        val agentSession = mcpAgent.current()
        if (text.length > 16_000) {
            toast("Nachricht zu lang")
            return
        }
        // The user-selected function schema is ALREADY bound to the actual
        // Spark Jinja system block via resetConversationWithTools(). Do not
        // duplicate it in untrusted user text as RC2 did.
        val request = text
        diagnosticTrace = PocketDiagnostics.newTrace()
        trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_START,
            PocketDiagnostics.State.START, count = agentSession?.tools?.size ?: 0)
        if (agentSession != null)
            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.REQUEST,
                PocketDiagnostics.State.START, count = agentSession.tools.size)

        messageInput.setText("")
        val answer = ChatMessage(false, "")
        messages.add(ChatMessage(true, text))
        messages.add(answer)
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
        requestChatPersist()
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        messagesView.scrollToPosition(index)
        busy = true
        sendButton.text = "■"
        messageInput.isEnabled = false
        setStatus(if (agentSession == null) "● GENERIERT …" else "● MCP PLANT …")
        generationJob = lifecycleScope.launch {
            var pendingNativeToolTurn: McpAgentSession? = null
            try {
                val fullReply = StringBuilder()
                withContext(Dispatchers.Default) {
                    applySampling()
                    engine.sendUserPrompt(request, prefs.getInt("max_tokens", 512)).collect { token ->
                        if (agentSession != null) {
                            // Withhold unverified model tool syntax from the chat.
                            fullReply.append(token)
                        } else withContext(Dispatchers.Main) {
                            answer.content += token
                            requestChatPersist()
                            chatAdapter.notifyItemChanged(index)
                            messagesView.scrollToPosition(index)
                        }
                    }
                }
                if (agentSession == null) {
                    val logClaim = McpLogCheck.modelOnlyAuditClaim(request, answer.content)
                    if (logClaim || McpHealthCheck.modelOnlyClaim(answer.content)) {
                        answer.content = if (logClaim) McpLogCheck.unverifiedModelClaim()
                            else McpHealthCheck.unverifiedModelClaim()
                        chatAdapter.notifyItemChanged(index)
                    }
                }
                if (agentSession != null) {
                    if (agentSession.progressiveSources.isNotEmpty()) {
                        // Any incomplete native assistant tool call must be
                        // cleared in the outer finally block.
                        pendingNativeToolTurn = agentSession
                        if (runProgressiveToolbridge(
                                agentSession, fullReply.toString(), request,
                                answer, index
                            )) {
                            pendingNativeToolTurn = null
                        }
                    } else when (val proposal = McpAgentBridge.parse(
                        fullReply.toString(), agentSession.tools,
                        thinkingEnabled = agentSession.thinkingMode != "off"
                    )) {
                        is McpAgentBridge.Decision.Answer -> {
                            // Explicitly refuse to present an unsupported health
                            // check or claimed external change as a successful action.
                            val logClaim = McpLogCheck.modelOnlyAuditClaim(request, proposal.text)
                            answer.content = if (logClaim) {
                                McpLogCheck.unverifiedModelClaim()
                            } else if (McpHealthCheck.modelOnlyClaim(proposal.text)) {
                                McpHealthCheck.unverifiedModelClaim()
                            } else {
                                "ⓘ Nur Modellantwort · kein MCP-Aufruf ausgeführt.\n\n" +
                                    proposal.text.ifBlank { "Keine Antwort erzeugt." }
                            }
                            chatAdapter.notifyItemChanged(index)
                        }
                        is McpAgentBridge.Decision.Invalid -> {
                            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PLAN,
                                PocketDiagnostics.State.FAIL)
                            answer.content = "⚠️ Kein MCP-Aufruf ausgeführt: " + proposal.reason +
                                "\nBitte die Anfrage präzisieren oder das Tool manuell wählen."
                            // An unterminated Spark thinking prefix cannot be
                            // faithfully replayed in native KV history.
                            if (agentSession.thinkingMode != "off" &&
                                (proposal.reason.contains("Thinking") ||
                                 proposal.reason.contains("Denkschleife"))) {
                                try {
                                    engine.resetConversationWithTools(
                                        groundedSystemPrompt(agentSession),
                                        McpAgentBridge.nativeToolsJson(agentSession.tools),
                                        agentSession.thinkingMode
                                    )
                                    answer.content +=
                                        "\nDer lokale Chatkontext wurde sicher neu gestartet."
                                } catch (resetFailure: Exception) {
                                    answer.content += "\nKontextreset fehlgeschlagen: " +
                                        (resetFailure.message ?: "Modell neu laden").take(160)
                                }
                            }
                            chatAdapter.notifyItemChanged(index)
                        }
                        is McpAgentBridge.Decision.Proposed -> {
                            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PROPOSAL,
                                PocketDiagnostics.State.OK, tool = proposal.tool.name)
                            if (proposal.nativeToolCall) {
                                // Must receive a real TOOL role + final assistant,
                                // or be rolled back in the coroutine finally.
                                pendingNativeToolTurn = agentSession
                            }
                            answer.content = "🔎 Das Modell schlägt " + proposal.tool.name +
                                " vor. Ausführung noch nicht bestätigt."
                            chatAdapter.notifyItemChanged(index)
                            setStatus("● MCP FREIGABE …")
                            val approved = mcpAgent.requestApproval(agentSession, proposal)
                            if (!approved) {
                                answer.content = "⛔ MCP-Aufruf abgelehnt. Keine Aktion ausgeführt."
                                chatAdapter.notifyItemChanged(index)
                            } else {
                                answer.content = "🔧 Aufruf bestätigt. Warte auf MCP-Server …"
                                chatAdapter.notifyItemChanged(index)
                                setStatus("● MCP TOOL …")
                                mcpDispatchInFlight = true
                                val dispatchState = McpDispatchState()
                                val toolResult = try {
                                    mcpAgent.execute(agentSession, proposal, dispatchState)
                                } catch (problem: Exception) {
                                    val id = failure(PocketDiagnostics.Domain.MCP,
                                        PocketDiagnostics.Step.REMOTE_ERROR, problem,
                                        uncertain = dispatchState.unconfirmed(problem))
                                    answer.content = (if (dispatchState.unconfirmed(problem))
                                        "⚠️ MCP-Ergebnis unbekannt, Aufruf möglicherweise ausgeführt: "
                                        else "⚠️ MCP-Aufruf vor dem Senden abgebrochen: ") +
                                        getString(R.string.diagnostics_error,
                                            PocketDiagnostics.publicError(problem), id) +
                                        "\nKein automatischer Retry."
                                    chatAdapter.notifyItemChanged(index)
                                    null
                                } finally {
                                    mcpDispatchInFlight = false
                                }
                                if (toolResult != null) {
                                    // Always expose the actual server receipt as evidence.
                                    // A model's second-turn prose is not a second tool result.
                                    answer.content = McpAgentBridge.receipt(proposal.tool, toolResult)
                                    chatAdapter.notifyItemChanged(index)
                                    val terminal = McpToolFeedback.terminalMessage(toolResult)
                                    if (terminal != null) {
                                        // 2026-07-28 input_required needs an explicit
                                        // MRTR interaction, not an implicit tool retry.
                                        messages.add(ChatMessage(false, terminal))
                                        chatAdapter.notifyItemInserted(messages.lastIndex)
                                        messagesView.scrollToPosition(messages.lastIndex)
                                    } else if (!proposal.nativeToolCall) {
                                        // A JSON fallback was executed after consent, but
                                        // it was not a canonical native Spark <tool_call>.
                                        // Never corrupt Jinja KV by inserting a tool
                                        // response without the matching assistant turn.
                                        messages.add(ChatMessage(false,
                                            "ⓘ Echt ausgeführter MCP-Aufruf (JSON-Fallback). " +
                                            "Das Serverergebnis steht oben. Keine native " +
                                            "Modellfortsetzung ohne passenden Tool-Turn."))
                                        chatAdapter.notifyItemInserted(messages.lastIndex)
                                        messagesView.scrollToPosition(messages.lastIndex)
                                    } else {
                                        val summary = StringBuilder()
                                        setStatus("● NATIVES TOOLERGEBNIS …")
                                        try {
                                            val payload = McpToolFeedback.nativePayload(
                                                proposal.tool, toolResult
                                            )
                                            // Genuine GGUF/Jinja role=tool response (not a
                                            // second USER message with remote instructions).
                                            // C++ verifies one pending matching assistant call.
                                            withContext(Dispatchers.Default) {
                                                engine.sendToolResult(
                                                    proposal.tool.name, payload, 160
                                                ).collect { summary.append(it) }
                                            }
                                            val reply = McpAgentBridge.parse(
                                                summary.toString(), agentSession.tools,
                                                thinkingEnabled = agentSession.thinkingMode != "off"
                                            )
                                            val clean = when (reply) {
                                                is McpAgentBridge.Decision.Answer -> {
                                                    // Native tool reply completed; the
                                                    // next user turn may reuse its context.
                                                    pendingNativeToolTurn = null
                                                    if (McpToolFeedback.misleadingSummary(
                                                            toolResult, reply.text)) {
                                                        "Modellbehauptung widerspricht dem " +
                                                            "MCP-Serverergebnis. Die belegte " +
                                                            "Antwort steht oben."
                                                    } else reply.text.ifBlank {
                                                        "Keine weitere Modellantwort erzeugt."
                                                    }
                                                }
                                                else -> McpToolFeedback.noMoreCalls()
                                            }
                                            messages.add(ChatMessage(false,
                                                McpToolFeedback.summaryBanner(toolResult) + clean))
                                            chatAdapter.notifyItemInserted(messages.lastIndex)
                                            messagesView.scrollToPosition(messages.lastIndex)
                                        } catch (summaryError: Exception) {
                                            failure(PocketDiagnostics.Domain.GGUF,
                                                PocketDiagnostics.Step.MODEL_FOLLOWUP, summaryError)
                                            // Never replace or hide the real server receipt.
                                            messages.add(ChatMessage(false,
                                                "Das echte MCP-Ergebnis steht oben. " +
                                                "Die native Modellzusammenfassung konnte " +
                                                "nicht abgeschlossen werden. " +
                                                "Ggf. Modell neu laden."))
                                            chatAdapter.notifyItemInserted(messages.lastIndex)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.MODEL_DONE,
                    PocketDiagnostics.State.OK)
                setStatus("● BEREIT")
            } catch (_: CancellationException) {
                trace(PocketDiagnostics.Domain.GGUF, PocketDiagnostics.Step.CANCELLED,
                    PocketDiagnostics.State.REJECTED)
                setStatus("● GESTOPPT")
            } catch (error: Exception) {
                val eventId = failure(PocketDiagnostics.Domain.GGUF,
                    PocketDiagnostics.Step.MODEL_ERROR, error)
                if (error is IncompleteThinkingTurnException) {
                    // Native SYSTEM/MCP KV already recovered; avoid 171s re-prefill
                    // and never retry a potentially side-effecting remote call.
                    pendingNativeToolTurn = null
                    answer.content = "⚠️ Spark Thinking LOW nicht abgeschlossen. " +
                        "Unfertigen lokalen Turn verworfen; MCP weiterhin bereit. " +
                        "Frage erneut stellen oder Thinking OFF wählen. " +
                        "Keine externe Aktion automatisch wiederholt."
                    setStatus("● BEREIT · THINKING RESET")
                } else {
                    answer.content += "\n⚠ " + getString(R.string.diagnostics_error,
                        PocketDiagnostics.publicError(error), eventId)
                    setStatus("Generierung fehlgeschlagen")
                }
                chatAdapter.notifyItemChanged(index)
            } finally {
                val unresolved = pendingNativeToolTurn
                if (unresolved != null) {
                    // Cancellation is possible during the approval dialog
                    // or network dispatch; cleanup must still run.
                    val restored = withContext(NonCancellable) {
                        resetUnresolvedNativeToolTurn(unresolved)
                    }
                    val notice = if (restored) {
                        "ⓘ Unvollständiger lokaler Tool-Turn entfernt. " +
                            "Keine weitere MCP-Aktion ausgeführt."
                    } else {
                        "⚠️ MCP-Agent deaktiviert: Nativer Modellkontext " +
                            "konnte nicht wiederhergestellt werden. Modell neu laden. " +
                            "Ein möglicherweise ausgeführter MCP-Aufruf " +
                            "wurde NICHT wiederholt."
                    }
                    messages.add(ChatMessage(false, notice))
                    chatAdapter.notifyItemInserted(messages.lastIndex)
                    messagesView.scrollToPosition(messages.lastIndex)
                }
                requestChatPersist()
                busy = false
                sendButton.text = "➤"
                renderChatState()
            }
        }
    }

    /**
     * Stream GGUF to a document the user explicitly created with Android's
     * Storage Access Framework. Progress and cancellation share the existing
     * model transfer controls; no broad storage permission is requested.
     */
    /** Host-controlled LiteRT native ToolCall -> explicit permission -> real
     * server receipt -> genuine role=tool message. Same auth and schema
     * revalidation as the existing GGUF progressive bridge.
     */
    /** Shared for GGUF and LiteRT: model-independent, fail-closed fallback.
     * Never fabricates a native model tool call or forwards remote output as user text.
     */
    private suspend fun tryHostDiscovery(
        active: McpAgentSession,
        request: String,
        run: McpProgressiveBridge.Run,
        answer: ChatMessage,
        answerIndex: Int
    ): Boolean {
        var blocked: McpOnDemandDiscovery.Rejection? = null
        val plan = try {
            McpOnDemandDiscovery.propose(request, run) { blocked = it }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (problem: Exception) {
            val id = failure(PocketDiagnostics.Domain.MCP,
                PocketDiagnostics.Step.HOST_FALLBACK, problem)
            answer.content = "⚠️ AUTO-MCP-Planung fehlgeschlagen: " +
                getString(R.string.diagnostics_error,
                    PocketDiagnostics.publicError(problem), id) +
                "\nKeine Serveraktion ausgeführt."
            chatAdapter.notifyItemChanged(answerIndex)
            return true
        }
        if (plan == null) {
            val reason = blocked ?: McpOnDemandDiscovery.Rejection.NO_CATALOG_MATCH
            val code = when (reason) {
                McpOnDemandDiscovery.Rejection.AMBIGUOUS_TOOL ->
                    PocketDiagnostics.ErrorCode.AMBIGUOUS_TOOL
                McpOnDemandDiscovery.Rejection.UNVERIFIED_READONLY ->
                    PocketDiagnostics.ErrorCode.UNVERIFIED_READONLY
                McpOnDemandDiscovery.Rejection.ARGUMENTS_UNAVAILABLE ->
                    PocketDiagnostics.ErrorCode.ARGUMENTS_UNAVAILABLE
                else -> PocketDiagnostics.ErrorCode.NO_SAFE_MATCH
            }
            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.HOST_FALLBACK,
                PocketDiagnostics.State.REJECTED, code = code)
            answer.content = when (reason) {
                McpOnDemandDiscovery.Rejection.AMBIGUOUS_TOOL ->
                    "⚠️ AUTO-MCP: mehrere mögliche Werkzeuge oder Server. " +
                        "Bitte Server und Werkzeug eindeutig nennen."
                McpOnDemandDiscovery.Rejection.UNVERIFIED_READONLY ->
                    "⚠️ AUTO-MCP: keine verifizierte lesende Werkzeugfreigabe " +
                        "für den sicheren App-Fallback vorhanden."
                McpOnDemandDiscovery.Rejection.ARGUMENTS_UNAVAILABLE ->
                    "⚠️ AUTO-MCP: erforderliche Werkzeugparameter fehlen oder " +
                        "können nicht sicher abgeleitet werden."
                McpOnDemandDiscovery.Rejection.TOOL_DETAILS_UNAVAILABLE ->
                    "⚠️ AUTO-MCP: Werkzeugdetails konnten nicht vollständig " +
                        "und sicher geladen werden."
                else -> "⚠️ AUTO-MCP: kein eindeutig passendes, geprüftes " +
                    "Werkzeug für diesen Auftrag gefunden."
            } + "\nKein tools/call ausgeführt. Diagnose: HOST_FALLBACK / " + code.name
            chatAdapter.notifyItemChanged(answerIndex)
            return true
        }
        if (mcpAgent.current() != active) return false
        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PROPOSAL,
            PocketDiagnostics.State.OK, tool = plan.tool.name)
        answer.content = "🔎 App-gestützter Werkzeugvorschlag (kein Modell-ToolCall): " +
            plan.source.title + " / " + plan.tool.name + "\nNoch nicht ausgeführt."
        chatAdapter.notifyItemChanged(answerIndex)
        if (!mcpAgent.requestAutoApproval(active, plan)) {
            answer.content = "⛔ MCP-Aufruf abgelehnt. Keine Serveraktion."
            chatAdapter.notifyItemChanged(answerIndex)
            return true
        }
        // Mark before dispatch; unknown execution is never retried.
        run.markDispatched(plan)
        mcpDispatchInFlight = true
        val dispatchState = McpDispatchState()
        try {
            val result = withContext(Dispatchers.IO) {
                mcpAgent.executeAuto(active, plan, dispatchState)
            }
            answer.content = "🔧 ECHTE MCP-SERVERANTWORT (App-gestützt):\n" +
                McpAgentBridge.receipt(plan.tool, result) +
                "\nKeine native Modell-Toolfortsetzung behauptet."
        } catch (error: Exception) {
            val eventId = failure(PocketDiagnostics.Domain.MCP,
                PocketDiagnostics.Step.REMOTE_ERROR, error,
                uncertain = dispatchState.unconfirmed(error))
            answer.content = (if (dispatchState.unconfirmed(error))
                "⚠️ MCP-Ausführung unklar: " else
                "⚠️ MCP-Aufruf vor dem Senden abgebrochen: ") +
                getString(R.string.diagnostics_error,
                    PocketDiagnostics.publicError(error), eventId) +
                "\nKein automatischer Wiederholungsversuch."
        } finally {
            mcpDispatchInFlight = false
            chatAdapter.notifyItemChanged(answerIndex)
        }
        return true
    }

    /**
     * Host-only fallback for selected LiteRT tools when a small model emits
     * prose instead of a native function call. One explicit proposal, fresh
     * one-shot consent, live authorization and schema/risk revalidation.
     * Never claim a native LiteRT tool response or call twice.
     */
    private suspend fun trySelectedHostFallback(
        active: McpAgentSession,
        request: String,
        answer: ChatMessage,
        answerIndex: Int
    ): Boolean {
        val proposal = McpOnDemandDiscovery.proposeSelected(request, active) ?: return false
        if (mcpAgent.current() != active) return false
        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PROPOSAL,
            PocketDiagnostics.State.INFO, tool = proposal.tool.name)
        answer.content = "🔎 App-gestützter Werkzeugvorschlag (kein nativer " +
            "LiteRT-ToolCall): " + active.serverName + " / " + proposal.tool.name +
            "\nParameter: " + proposal.args.toString() + "\nNoch nichts ausgeführt."
        chatAdapter.notifyItemChanged(answerIndex)
        if (!mcpAgent.requestApproval(active, proposal)) {
            answer.content = "⛔ App-Werkzeugvorschlag abgelehnt. Keine Serveraktion."
            chatAdapter.notifyItemChanged(answerIndex)
            return true
        }
        mcpDispatchInFlight = true
        val dispatchState = McpDispatchState()
        try {
            val result = withContext(Dispatchers.IO) {
                mcpAgent.execute(active, proposal, dispatchState, hostFallback = true)
            }
            answer.content = "🔧 ECHTE MCP-SERVERANTWORT (App-gestützt; " +
                "LiteRT erzeugte keinen nativen ToolCall):\n" +
                McpAgentBridge.receipt(proposal.tool, result) +
                "\nKeine native Modell-Toolfortsetzung behauptet."
        } catch (error: Exception) {
            val eventId = failure(PocketDiagnostics.Domain.MCP,
                PocketDiagnostics.Step.REMOTE_ERROR, error,
                uncertain = dispatchState.unconfirmed(error))
            answer.content = (if (dispatchState.unconfirmed(error))
                "⚠️ MCP-Ausführung unklar: " else
                "⚠️ MCP-Aufruf vor dem Senden abgebrochen: ") +
                getString(R.string.diagnostics_error,
                    PocketDiagnostics.publicError(error), eventId) +
                "\nKein automatischer Wiederholungsversuch."
        } finally {
            mcpDispatchInFlight = false
            chatAdapter.notifyItemChanged(answerIndex)
        }
        return true
    }

    private suspend fun runLiteRtToolbridge(
        active: McpAgentSession,
        runtime: LiteRtRuntime,
        request: String,
        answer: ChatMessage,
        answerIndex: Int
    ) {
        trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.PLAN,
            PocketDiagnostics.State.START)
        var turn = runtime.askMcp(request)
        var hadRealCall = false
        var lastServerResult: JSONObject? = null
        val progressive = active.progressiveSources.isNotEmpty()
        val catalog = if (progressive) withContext(Dispatchers.Default) {
            McpProgressiveBridge.Run(active.progressiveSources, System.currentTimeMillis())
        } else null
        if (catalog != null)
            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISCOVERY_DONE,
                PocketDiagnostics.State.OK, count = catalog.totalTools)
        fun evidence(text: String) {
            messages.add(ChatMessage(false, text))
            chatAdapter.notifyItemInserted(messages.lastIndex)
            messagesView.scrollToPosition(messages.lastIndex)
        }
        fun setAnswer(text: String) {
            answer.content = text
            chatAdapter.notifyItemChanged(answerIndex)
        }
        for (step in 0 until McpProgressiveBridge.MAX_STEPS) {
            check(mcpAgent.current() == active) {
                "MCP-Konfiguration wurde während des LiteRT-Turns geändert."
            }
            if (turn.calls.isEmpty()) {
                trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.PLAN,
                    if (hadRealCall) PocketDiagnostics.State.OK
                    else PocketDiagnostics.State.INFO)
                if (!hadRealCall && McpRequestRouting.expectsToolEvidence(request))
                    trace(PocketDiagnostics.Domain.LITERT,
                        PocketDiagnostics.Step.MODEL_TOOL_MISSING,
                        PocketDiagnostics.State.INFO)
                val summary = turn.text.ifBlank { "Keine Modellantwort erzeugt." }
                val invalidClaim = McpLogCheck.modelOnlyAuditClaim(request, summary) ||
                    McpHealthCheck.modelOnlyClaim(summary) ||
                    (lastServerResult?.let {
                        McpToolFeedback.misleadingSummary(it, summary)
                    } == true)
                // Even after mcp_search_tools / mcp_get_tool_details, a small
                // model may stop with prose. Reuse this per-turn catalog and
                // require explicit task intent before proposing a host call.
                if (!hadRealCall && progressive &&
                    McpRequestRouting.expectsToolEvidence(request)) {
                    if (tryHostDiscovery(active, request, checkNotNull(catalog),
                            answer, answerIndex)) return
                }
                if (!hadRealCall && !progressive &&
                    McpRequestRouting.expectsToolEvidence(request) &&
                    trySelectedHostFallback(active, request, answer, answerIndex)) {
                    return
                }
                if (!hadRealCall && McpRequestRouting.expectsToolEvidence(request)) {
                    val searched = (catalog?.searchCount ?: 0) > 0
                    setAnswer(if (searched)
                        "⚠️ LiteRT hat MCP-Werkzeuge gesucht, aber keinen echten " +
                            "tools/call ausgeführt. Kein externer Status bestätigt. " +
                            "Die Werkzeugliste oben beweist keine Ausführung."
                    else "⚠️ LiteRT hat keinen nativen MCP-ToolCall angefordert. " +
                        "Kein externes Werkzeug ausgeführt und kein Systemstatus bestätigt. " +
                        "Bitte KI + MCP / AUTO-BRIDGE und die Modellkompatibilität prüfen.")
                } else if (invalidClaim) {
                    setAnswer("⚠️ Modellbehauptung nicht durch das echte MCP-Resultat belegt. " +
                        "Verlässliche Serverquittung steht separat oben.")
                } else {
                    setAnswer((if (hadRealCall)
                        "ⓘ LiteRT-Zusammenfassung der echten MCP-Serverantwort:\n"
                    else "ⓘ LiteRT-Modellantwort (keine externe MCP-Aktion):\n") + summary)
                }
                return
            }
            require(turn.calls.size == 1) {
                "Mehrere gleichzeitige MCP-Toolaufrufe nicht erlaubt."
            }
            if (hadRealCall) {
                // Never dispatch a second external call after a real receipt.
                // Keep the first receipt visible instead of reporting a generic
                // model exception or clearing the prior verified result.
                setAnswer("🔧 MCP-Serverquittung oben. Weiterer Modell-Toolvorschlag " +
                    "blockiert: maximal ein externer Aufruf pro Anfrage.")
                return
            }
            val (tool, args) = LiteRtMcpProtocol.validate(
                turn.calls.single(), active.tools
            )
            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.PROPOSAL,
                PocketDiagnostics.State.OK, tool = tool.name)
            val outcome: JSONObject
            if (progressive) {
                val run = checkNotNull(catalog)
                outcome = when (tool.name) {
                    McpProgressiveBridge.SEARCH -> {
                        val result = try {
                            run.search(args.getString("query"),
                                args.optInt("limit", McpProgressiveBridge.MAX_RESULTS))
                        } catch (e: Exception) {
                            McpProgressiveBridge.localFailure(e.message.orEmpty())
                        }
                        trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.SEARCH,
                            if (result.optBoolean("isError",false))
                                PocketDiagnostics.State.FAIL else PocketDiagnostics.State.OK)
                        evidence(McpProgressiveBridge.displaySearchResult(result))
                        result
                    }
                    McpProgressiveBridge.DETAILS -> {
                        val detailsStart = SystemClock.elapsedRealtime()
                        val result = try {
                            run.details(args.getString("tool_id")).also { found ->
                                trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DETAILS,
                                    if (found.optBoolean("isError", false))
                                        PocketDiagnostics.State.FAIL
                                    else PocketDiagnostics.State.OK,
                                    durationMs = SystemClock.elapsedRealtime() - detailsStart)
                            }
                        } catch (e: Exception) {
                            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DETAILS,
                                PocketDiagnostics.State.FAIL,
                                durationMs = SystemClock.elapsedRealtime() - detailsStart,
                                code = PocketDiagnostics.classify(e))
                            McpProgressiveBridge.localFailure(e.message.orEmpty())
                        }
                        evidence("📋 Werkzeugdefinition (nicht ausgeführt):\n" +
                            result.toString().take(1500))
                        result
                    }
                    McpProgressiveBridge.CALL -> {
                        val plan = try {
                            run.prepareCall(args.getString("tool_id"),
                                args.getString("arguments_json"))
                        } catch (e: Exception) {
                            failure(PocketDiagnostics.Domain.MCP,
                                PocketDiagnostics.Step.SCHEMA_CHECK, e)
                            val error = McpProgressiveBridge.localFailure(e.message.orEmpty())
                            evidence("⚠️ Werkzeugvorschlag verworfen: " +
                                e.message.orEmpty().take(320))
                            turn = runtime.toolFeedback(tool.name, error.toString())
                            continue
                        }
                        setAnswer("🔐 LiteRT schlägt " + plan.source.title + " / " +
                            plan.tool.name + " vor. Noch nichts ausgeführt.")
                        val approved = mcpAgent.requestAutoApproval(active, plan)
                        if (!approved) {
                            evidence("⛔ MCP-Aufruf abgelehnt. Keine Serveraktion ausgeführt.")
                            setAnswer("⛔ Werkzeugaufruf abgelehnt.")
                            return
                        }
                        // Mark BEFORE network dispatch. Never retry after timeout
                        // because remote side effects may already exist.
                        run.markDispatched(plan)
                        mcpDispatchInFlight = true
                        val dispatchState = McpDispatchState()
                        val result = try {
                            withContext(Dispatchers.IO) {
                                mcpAgent.executeAuto(active, plan, dispatchState)
                            }
                        } catch (e: Exception) {
                            val id = failure(PocketDiagnostics.Domain.MCP,
                                PocketDiagnostics.Step.REMOTE_ERROR, e,
                                uncertain = dispatchState.unconfirmed(e))
                            evidence((if (dispatchState.unconfirmed(e))
                                "⚠️ MCP-Ergebnis unbekannt: " else
                                "⚠️ MCP-Aufruf vor dem Senden abgebrochen: ") +
                                getString(R.string.diagnostics_error,
                                    PocketDiagnostics.publicError(e), id) +
                                "\nKein automatischer Wiederholungsversuch.")
                            setAnswer("⚠️ MCP-Aufrufstatus nicht bestätigt.")
                            return
                        } finally {
                            mcpDispatchInFlight = false
                        }
                        hadRealCall = true
                        lastServerResult = result
                        evidence("🔧 ECHTE MCP-SERVERANTWORT:\n" +
                            McpAgentBridge.receipt(plan.tool, result))
                        result
                    }
                    else -> error("Unbekanntes lokales MCP-Meta-Tool")
                }
            } else {
                val proposal = McpAgentBridge.Decision.Proposed(tool, args)
                setAnswer("🔐 LiteRT schlägt " + tool.name +
                    " vor. Nutzerfreigabe erforderlich.")
                if (!mcpAgent.requestApproval(active, proposal)) {
                    setAnswer("⛔ MCP-Werkzeug abgelehnt. Nichts ausgeführt.")
                    return
                }
                mcpDispatchInFlight = true
                val dispatchState = McpDispatchState()
                outcome = try {
                    withContext(Dispatchers.IO) {
                        mcpAgent.execute(active, proposal, dispatchState)
                    }
                } catch (e: Exception) {
                    val id = failure(PocketDiagnostics.Domain.MCP,
                        PocketDiagnostics.Step.REMOTE_ERROR, e,
                        uncertain = dispatchState.unconfirmed(e))
                    evidence((if (dispatchState.unconfirmed(e))
                        "⚠️ MCP-Ergebnis unbekannt; kein Retry: " else
                        "⚠️ MCP-Aufruf vor dem Senden abgebrochen: ") +
                        getString(R.string.diagnostics_error,
                            PocketDiagnostics.publicError(e), id))
                    setAnswer("⚠️ MCP-Aufrufstatus nicht bestätigt.")
                    return
                } finally {
                    mcpDispatchInFlight = false
                }
                hadRealCall = true
                lastServerResult = outcome
                evidence("🔧 ECHTE MCP-SERVERANTWORT:\n" +
                    McpAgentBridge.receipt(tool, outcome))
            }
            if (hadRealCall) {
                val terminal = McpToolFeedback.terminalMessage(outcome)
                if (terminal != null) {
                    evidence(terminal)
                    setAnswer("🔧 MCP-Serverquittung oben. Keine weitere Aktion.")
                    return
                }
            }
            // Real LiteRT role=tool for the function that was actually
            // proposed. The payload is untrusted data and contains NO token.
            if (step == McpProgressiveBridge.MAX_STEPS - 1) {
                setAnswer("⚠️ Höchstzahl lokaler Tool-Planungsschritte erreicht.")
                return
            }
            // Do not feed raw, arbitrarily large server JSON to a local
            // model; it may contain injected role delimiters or credentials.
            val boundedFeedback = if (hadRealCall)
                McpToolFeedback.nativePayload(tool, outcome)
            else outcome.toString().take(12_000)
            turn = runtime.toolFeedback(tool.name, boundedFeedback)
            trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_FOLLOWUP,
                PocketDiagnostics.State.OK, tool = tool.name)
        }
    }

    /** LiteRT-LM chat is local and independent from the GGUF MCP tool bridge. */
    private fun sendLiteRtMessage(text: String) {
        val runtime = liteRtRuntime
        if (runtime == null) {
            toast("LiteRT-Modell zuerst laden")
            return
        }
        diagnosticTrace = PocketDiagnostics.newTrace()
        trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_START,
            PocketDiagnostics.State.START)
        if (mcpAgent.current() != null)
            trace(PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.REQUEST,
                PocketDiagnostics.State.START, count = mcpAgent.current()?.tools?.size ?: 0)
        messageInput.setText("")
        val answer = ChatMessage(false, "")
        messages.add(ChatMessage(true, text))
        messages.add(answer)
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
        requestChatPersist()
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        messagesView.scrollToPosition(index)
        busy = true
        sendButton.text = "■"
        messageInput.isEnabled = false
        setStatus("● LITERT · " + runtime.backendName + " …")
        generationJob = lifecycleScope.launch {
            try {
                val activeMcp = mcpAgent.current()
                if (activeMcp != null) {
                    setStatus("● LITERT MCP · PLANUNG …")
                    runLiteRtToolbridge(activeMcp, runtime, text, answer, index)
                    setStatus("● LITERT MCP · BEREIT")
                } else withContext(Dispatchers.IO) {
                    runtime.send(text).collect { delta ->
                        withContext(Dispatchers.Main) {
                            answer.content += delta
                            requestChatPersist()
                            chatAdapter.notifyItemChanged(index)
                            messagesView.scrollToPosition(index)
                        }
                    }
                }
                trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_DONE,
                    PocketDiagnostics.State.OK)
                setStatus("● LITERT BEREIT · " + runtime.backendName)
            } catch (_: CancellationException) {
                trace(PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.CANCELLED,
                    PocketDiagnostics.State.REJECTED)
                withContext(NonCancellable) {
                    try { runtime.cancelResponse() } catch (_: Exception) {}
                }
                if (!isDestroyed) setStatus("● LITERT GESTOPPT")
            } catch (error: Exception) {
                val eventId = failure(PocketDiagnostics.Domain.LITERT,
                    PocketDiagnostics.Step.MODEL_ERROR, error)
                answer.content += "\n⚠ " + getString(R.string.diagnostics_error,
                    PocketDiagnostics.publicError(error), eventId)
                chatAdapter.notifyItemChanged(index)
                setStatus("● LITERT FEHLER")
            } finally {
                requestChatPersist()
                busy = false
                sendButton.text = "➤"
                renderChatState()
            }
        }
    }

    private fun exportModel(file: File, destination: Uri) {
        if (busy || transferBusy) {
            toast("Bitte die laufende Aufgabe zuerst abschließen.")
            return
        }
        transferBusy = true
        activeExportFileName = file.name
        cancelTransfer = AtomicBoolean(false)
        setDownloadUi("Exportiere " + file.name + " …", true)
        showPage(1)
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    store.export(file, destination, cancelTransfer) { progress ->
                        runOnUiThread {
                            if (transferBusy && activeExportFileName == file.name) {
                                downloadProgress.isIndeterminate = progress.percent < 0
                                if (progress.percent >= 0) {
                                    downloadProgress.progress = progress.percent
                                }
                                downloadStatus.text = "Exportiere " + file.name +
                                    " · " + ModelStore.displaySize(progress.done) +
                                    " / " + ModelStore.displaySize(progress.total)
                            }
                        }
                    }
                }
                setDownloadUi(
                    "✓ GGUF exportiert: " + file.name + " · " +
                        ModelStore.displaySize(bytes) +
                        ". Vor dem Löschen der App die Zieldatei prüfen.", false
                )
                toast("GGUF in ausgewählten Ordner exportiert.")
            } catch (_: TransferCancelled) {
                setDownloadUi(
                    "Export abgebrochen. Eine unvollständige Datei kann im Zielordner liegen.",
                    false
                )
            } catch (problem: Exception) {
                setDownloadUi(
                    "Export fehlgeschlagen. Möglicherweise liegt eine unvollständige Zieldatei vor.",
                    false
                )
                showError("GGUF konnte nicht exportiert werden", problem)
            } finally {
                activeExportFileName = null
                transferBusy = false
                refreshLibrary()
            }
        }
    }

    private fun importModel(uri: Uri) {
        if (transferBusy) { toast("Ein Transfer läuft bereits."); return }
        transferBusy = true
        activeTransferName = store.sourceName(uri)
        cancelTransfer = AtomicBoolean(false)
        setDownloadUi("Importiere " + activeTransferName, true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    store.import(uri, cancelTransfer) { p -> runOnUiThread { updateProgress(p) } }
                }
                toast("Importiert: " + result.name)
                setDownloadUi("✓ " + result.name, false)
                refreshLibrary()
            } catch (error: Exception) {
                setDownloadUi("Import fehlgeschlagen", false)
                showError("Import fehlgeschlagen", error)
            } finally {
                transferBusy = false
                activeTransferName = null
                activePartialLabel = null
            }
        }
    }

    /**
     * Schedule only from an explicitly confirmed user action. UI lifetime is
     * deliberately disconnected from Android JobScheduler / WorkManager.
     */
    private fun startDownload(model: RemoteModel, wifiOnly: Boolean =
        prefs.getBoolean("download_wifi_only", false)) {
        if (transferBusy) { toast("Ein Transfer läuft bereits."); return }
        val previousUrl = prefs.getString("pending_url_" + model.name, null)
        if (previousUrl != null && previousUrl != model.url &&
            File(store.dir, ModelStore.sanitize(model.name) + ".part").exists()) {
            toast("Teil-Download gehört zu einer anderen Quelle. Bitte zuerst löschen.")
            return
        }
        try {
            BackgroundModelDownloads.start(this, model, wifiOnly)
            prefs.edit().putString("pending_url_" + model.name, model.url)
                .putString("pending_sha_" + model.name, model.sha256).apply()
            backgroundTransferName = ModelStore.sanitize(model.name)
            activeTransferName = backgroundTransferName
            transferBusy = true
            showPage(1)
            setDownloadUi("Hintergrunddownload gestartet: " + model.name, true)
            refreshLibrary()
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 510)
            }
        } catch (problem: Exception) {
            showError("Download konnte nicht geplant werden", problem)
        }
    }

    private fun resumeBackgroundDownload(name: String, requeue: Boolean = false) {
        try {
            BackgroundModelDownloads.resume(this, name, requeue)
            backgroundTransferName = name
            activeTransferName = name
            transferBusy = true
            toast("Android-Downloadjob neu eingeplant; bestehende Teildatei bleibt erhalten.")
            updateBackgroundTransferUi()
            refreshLibrary()
        } catch (error: Exception) {
            showError("Download konnte nicht fortgesetzt werden", error)
            updateBackgroundTransferUi()
        }
    }

    /** Display a persisted OS job state; do not cancel work on onStop(). */
    private fun updateBackgroundTransferUi() {
        val name = backgroundTransferName
            ?: BackgroundModelDownloads.latestActive(this)?.name ?: return
        val entry = ModelDownloadJournal.read(this, name) ?: return
        val snapshot = BackgroundModelDownloads.snapshot(this, entry)
        if (entry.state in setOf("queued", "running", "verifying", "paused", "failed")) {
            backgroundTransferName = name
            activeTransferName = name
            // A stale, *unscheduled* queue is recoverable, not an active
            // download. An Android-held scheduled job remains an active task.
            transferBusy = snapshot.active && !(
                snapshot.canResume && !snapshot.requiresConfirmation
            )
            setDownloadUi(snapshot.label, snapshot.active || snapshot.canResume)
            cancelDownload.text = when {
                snapshot.canResume && snapshot.requiresConfirmation -> "Job neu einplanen"
                snapshot.canResume -> "Download fortsetzen"
                entry.state == "verifying" -> "Dateiprüfung läuft"
                else -> "Download pausieren"
            }
            cancelDownload.isEnabled = snapshot.canResume ||
                entry.state in setOf("running", "queued")
            downloadProgress.visibility = if (snapshot.active) View.VISIBLE else View.GONE
            downloadProgress.isIndeterminate = entry.total <= 0
            if (entry.total > 0) downloadProgress.progress =
                ((entry.done * 100 / entry.total).coerceIn(0, 100)).toInt()
            return
        }
        if (name != backgroundTransferName) return
        backgroundTransferName = null
        activeTransferName = null
        transferBusy = false
        setDownloadUi(snapshot.label, false)
        if (entry.state == "complete") {
            prefs.edit().remove("pending_url_" + name).remove("pending_sha_" + name).apply()
        }
        refreshLibrary()
    }

    private fun browseRepository() {
        if (transferBusy) { toast("Ein Download läuft. Bitte erst abschließen oder abbrechen."); return }
        val field = EditText(this).apply {
            hint = "github.com/owner/repo oder huggingface.co/owner/repo"
            setSingleLine(true)
            setText(prefs.getString("recent_repo", ""))
            setPadding(dp(17), dp(20), dp(17), dp(20))
        }
        AlertDialog.Builder(this)
            .setTitle("Repository durchsuchen")
            .setMessage("GitHub-Releases sowie öffentliche Hugging-Face-GGUF- und LiteRT-LM-Dateien.")
            .setView(field)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Suchen") { _, _ ->
                val text = field.text.toString().trim()
                lifecycleScope.launch {
                    catalogStatus("Suche Modelle …")
                    try {
                        val ref = SourceCatalog.parseRepository(text)
                        val found = withContext(Dispatchers.IO) { SourceCatalog.list(ref) }
                        prefs.edit().putString("recent_repo", text).apply()
                        if (found.isEmpty()) {
                            toast("Keine kompatiblen Modelldateien gefunden.")
                            catalogStatus("Keine kompatiblen Modelldateien gefunden")
                        } else {
                            val visible = found.take(100)
                            val options = visible.map {
                                it.name + "\n" +
                                    (if (it.bytes > 0) ModelStore.displaySize(it.bytes) else "Größe unbekannt") +
                                    " · " + it.source
                            }.toTypedArray()
                            catalogStatus(found.size.toString() + " Modelldateien gefunden")
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Modell auswählen")
                                .setItems(options) { _, index -> confirmDownload(visible[index]) }
                                .setNegativeButton("Schließen", null)
                                .show()
                        }
                    } catch (error: Exception) {
                        showError("Repository konnte nicht gelesen werden", error)
                    }
                }
            }.show()
    }

    /** Live Hub search from TriForce, with direct official-Hub fallback. */
    private fun searchHuggingFace() {
        if (transferBusy) {
            toast("Ein Download läuft. Bitte erst abschließen oder abbrechen.")
            return
        }
        val field = EditText(this).apply {
            hint = "z. B. SmolLM, Qwen, Gemma, Deutsch"
            setSingleLine(true)
            setText(prefs.getString("last_hf_search", "SmolLM"))
            setPadding(dp(17), dp(19), dp(17), dp(19))
        }
        val format = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("GGUF · llama.cpp", "LiteRT-LM · TPU / GPU / CPU")
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(6))
            addView(field)
            addView(format)
        }
        AlertDialog.Builder(this)
            .setTitle("Hugging Face · Modell suchen")
            .setView(form)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Suchen") { _, _ ->
                val query = field.text.toString().trim()
                val requestedFormat =
                    if (format.selectedItemPosition == 1) "litertlm" else "gguf"
                lifecycleScope.launch {
                    catalogStatus("Hugging Face Suche läuft …")
                    try {
                        val models = withContext(Dispatchers.IO) {
                            HuggingFaceDiscovery.search(query, format = requestedFormat)
                        }
                        prefs.edit().putString("last_hf_search", query).apply()
                        showHuggingFaceResults(models, "Suchergebnisse für: " + query)
                    } catch (error: Exception) {
                        showError("Hugging Face Suche fehlgeschlagen", error)
                    }
                }
            }.show()
    }

    /** Verify the requested repo and GGUF files before allowing download. */
    private fun showHuggingFaceResults(
        found: List<HuggingFaceRepository>, label: String
    ) {
        if (found.isEmpty()) {
            catalogStatus("Keine passenden öffentlichen Modelle gefunden")
            toast("Keine passenden öffentlichen Modelle.")
            return
        }
        catalogStatus(found.size.toString() + " öffentliche Modell-Repositories gefunden")
        val descriptions = found.map {
            it.id + "\n" + if (it.suggestedBytes > 0) {
                "Empfohlen: " + ModelStore.displaySize(it.suggestedBytes) + " · " + it.suggestedFile
            } else {
                "Downloads: " + it.downloads + " · Likes: " + it.likes
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(descriptions) { _, position ->
                val selected = found[position]
                lifecycleScope.launch {
                    catalogStatus("Prüfe " + selected.id + " …")
                    try {
                        val files = withContext(Dispatchers.IO) {
                            SourceCatalog.list(RepositoryRef(
                                RepositoryHost.HUGGING_FACE, selected.id
                            )).filter { it.name.endsWith("." + selected.format, true) }
                        }
                        if (files.isEmpty()) {
                            catalogStatus("Keine kompatiblen Modelldateien in " + selected.id)
                            toast("Keine passenden Modelldateien gefunden.")
                            return@launch
                        }
                        val preferred = selected.suggestedFile?.let { suggested ->
                            files.firstOrNull { it.name.equals(suggested, true) }
                        }
                        if (preferred != null) {
                            confirmDownload(preferred)
                        } else {
                            val options = files.take(80).map {
                                it.name + "\n" +
                                    (if (it.bytes > 0) ModelStore.displaySize(it.bytes)
                                     else "Größe unbekannt")
                            }.toTypedArray()
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Modell-Datei auswählen · " + selected.id)
                                .setItems(options) { _, index -> confirmDownload(files[index]) }
                                .setNegativeButton("Schließen", null)
                                .show()
                        }
                    } catch (error: Exception) {
                        showError("Modell-Repository nicht erreichbar", error)
                    }
                }
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun quickStart() {
        if (transferBusy) {
            toast("Ein Download läuft. Bitte erst abschließen oder abbrechen.")
            return
        }
        lifecycleScope.launch {
            catalogStatus("Prüfe kleine Hugging-Face-Modelle live …")
            try {
                val models = withContext(Dispatchers.IO) {
                    HuggingFaceDiscovery.recommended()
                }
                showHuggingFaceResults(models, "Kleine Offline-Modelle · Q4_K_M")
            } catch (error: Exception) {
                showError("Kleine Modelle nicht erreichbar", error)
            }
        }
    }

    private fun promptDirectUrl() {
        if (transferBusy) { toast("Ein Download läuft. Bitte erst abschließen oder abbrechen."); return }
        val field = EditText(this).apply {
            hint = "https://.../model.Q4_K_M.gguf oder model.litertlm"
            setSingleLine(false)
            minLines = 2
            setPadding(dp(17), dp(15), dp(17), dp(15))
        }
        AlertDialog.Builder(this)
            .setTitle("Modell-Link · GGUF / LiteRT-LM")
            .setView(field)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Übernehmen") { _, _ ->
                try { confirmDownload(SourceCatalog.directUrl(field.text.toString())) }
                catch (error: Exception) { showError("Ungültiger Downloadlink", error) }
            }.show()
    }

    private fun confirmDownload(model: RemoteModel) {
        if (transferBusy) { toast("Ein Transfer läuft bereits."); return }
        val wifiOnly = SwitchCompat(this).apply {
            text = "Nur WLAN (keine mobilen Daten)"
            isChecked = prefs.getBoolean("download_wifi_only", false)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Modell herunterladen?")
            .setMessage(model.name + "\n" +
                (if (model.bytes > 0) ModelStore.displaySize(model.bytes) else "Größe unbekannt") +
                "\nQuelle: " + model.url +
                "\n\nAndroid führt den Download bei minimierter App fort, soweit erlaubt.")
            .setView(wifiOnly)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Download starten") { _, _ ->
                prefs.edit().putBoolean("download_wifi_only", wifiOnly.isChecked).apply()
                startDownload(model, wifiOnly.isChecked)
            }
            .show()
    }

    // Catalog queries must not remove the ongoing download's progress/cancel controls.
    private fun catalogStatus(label: String) {
        if (!transferBusy) setDownloadUi(label, false)
    }

    private fun setDownloadUi(label: String, running: Boolean) {
        downloadStatus.text = label
        downloadProgress.visibility = if (running) View.VISIBLE else View.GONE
        downloadProgress.isIndeterminate = running
        cancelDownload.visibility = if (running) View.VISIBLE else View.GONE
        cancelDownload.isEnabled = running
    }

    private fun updateProgress(p: TransferProgress) {
        // A completed transfer can leave progress callbacks queued on the main thread.
        if (!transferBusy) return
        downloadProgress.isIndeterminate = p.percent < 0
        if (p.percent >= 0) downloadProgress.progress = p.percent
        downloadStatus.text = DownloadPresentation.progress(activeTransferName, p)
        val activeName = activeTransferName
        if (activeName != null) {
            // The part file may be created AFTER the library was first rendered.
            if (activePartialLabel == null && currentPage == 1 &&
                File(store.dir, "$activeName.part").isFile) {
                refreshLibrary()
            }
            activePartialLabel?.text = DownloadPresentation.partialStatus(activeName, p.done, true)
        }
    }

    private fun simpleLabel(content: String): TextView = TextView(this).apply {
        text = content
        textSize = 12f
        setTextColor(getColor(R.color.muted))
        setPadding(0, dp(7), 0, dp(7))
    }

    private fun smallButton(title: String, active: Boolean): MaterialButton =
        MaterialButton(this).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(12)
            setTextColor(getColor(if (active) R.color.background else R.color.ink))
            backgroundTintList = ColorStateList.valueOf(
                getColor(if (active) R.color.primary else R.color.surface_raised)
            )
        }

    private fun setStatus(value: String) { statusLabel.text = value }

    private fun setModelLoading(active: Boolean, modelName: String = "") {
        findViewById<View>(R.id.modelLoadingPanel).visibility =
            if (active) View.VISIBLE else View.GONE
        if (active) {
            findViewById<TextView>(R.id.modelLoadingLabel).text =
                "Lade " + modelName + "\n" +
                "Lokale GGUF- oder LiteRT-LM-Inferenz wird vorbereitet. Große Modelle können dauern."
        }
    }

    private fun showProcessExitInfo() {
        val button = findViewById<MaterialButton>(R.id.crashDiagnosticsButton)
        val uiDelayReport = uiResponsiveness.report()
        // GGUF native prefill progress is independent of a live LiteRT MCP
        // function session. Show both instead of a misleading "no binding".
        val activeMcp = mcpAgent.current()
        val liteRtBinding = when {
            liteRtRuntime == null || activeModel?.name?.endsWith(".litertlm", true) != true ->
                "LiteRT-MCP: kein geladenes LiteRT-Modell in diesem Prozess."
            activeMcp == null ->
                "LiteRT-MCP: Modell geladen, Agent ausgeschaltet."
            activeMcp.progressiveSources.isNotEmpty() ->
                "LiteRT-MCP: aktiver Progressive-Katalog, " +
                    activeMcp.progressiveSources.size + " verbundene Quellen, " +
                    activeMcp.progressiveSources.sumOf { it.tools.size } +
                    " echte Tooldefinitionen (lokale Meta-Tools: " +
                    activeMcp.tools.size + ")."
            else ->
                "LiteRT-MCP: aktiver, direkt ausgewählter Toolkontext mit " +
                    activeMcp.tools.size + " Werkzeugen."
        }
        val bindReport = "GGUF-MCP (nativer Prefill): " +
            lastMcpBindingReport.removePrefix("MCP-BINDUNG: ") +
            "\n" + liteRtBinding
        val downloadReport = ModelDownloadJournal.all(applicationContext)
            .sortedByDescending { it.updatedAtMs }
            .take(4).joinToString("\n") { entry ->
                val view = BackgroundModelDownloads.snapshot(this, entry)
                "Job-ID " + entry.id + " · " + view.label +
                    "\nJournal: " + entry.state +
                    ", Versuche: " + entry.attempts +
                    ", Stop-Reason-Code: " + entry.lastStopReason +
                    ", wiederaufnehmbar: " + view.canResume +
                    ", Bestätigung notwendig: " + view.requiresConfirmation +
                    "\nNetzwerktransport: " +
                    (BackgroundModelDownloads.networkInfo(this)?.diagnostic()
                        ?: "nicht verfügbar")
            }.ifBlank { "Keine gespeicherten Modell-Downloads." }
        button.isEnabled = false
        button.text = "Analysiere Android-Tombstones …"
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val history = manager.getHistoricalProcessExitReasons(packageName, 0, 10)
                    var tracesRead = 0
                    val entries = history.take(6).map { exit ->
                        val reasonName = when (exit.reason) {
                            ApplicationExitInfo.REASON_LOW_MEMORY -> "Speichermangel (Low Memory)"
                            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                                "Android beendet: übermäßige Ressourcennutzung (CPU/GPU etc.)"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native Crash (C++/JNI)"
                            ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin Crash"
                            ApplicationExitInfo.REASON_ANR -> "App reagiert nicht (ANR)"
                            ApplicationExitInfo.REASON_SIGNALED -> "Signalbedingter Abbruch"
                            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "App-Update (kein Crash)"
                            ApplicationExitInfo.REASON_USER_REQUESTED ->
                                "Vom Nutzer beendet / Aufgabe entfernt (kein Crash)"
                            else -> "Android-Code " + exit.reason
                        }
                        val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
                            .format(Date(exit.timestamp))
                        val summary = exit.processStateSummary
                            ?.toString(Charsets.UTF_8)?.take(125).orEmpty()
                        val entry = StringBuilder().apply {
                            append(date).append(" • ").append(reasonName)
                            append(" (Status ").append(exit.status).append(")")
                            append("\nRSS: ").append(exit.rss).append(" kB, PSS: ")
                            append(exit.pss).append(" kB")
                            if (summary.isNotEmpty()) {
                                append("\nPhase: ").append(summary)
                                if (exit.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED ||
                                    exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED) {
                                    append("\nHinweis: Die Phase ist der letzte gespeicherte " +
                                        "Prozesszustand, nicht die Ursache der Beendigung.")
                                }
                            }
                            exit.description?.takeIf { it.isNotBlank() }?.let {
                                append("\n").append(it.take(200))
                            }
                        }
                        // Android 12+ exposes native tombstones for the calling app
                        // as a protobuf. Only inspect the two most recent native
                        // crashes to keep UI responsive and clipboard size bounded.
                        if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && tracesRead < 2) {
                            tracesRead++
                            val traceText = try {
                                exit.traceInputStream?.use { stream ->
                                    NativeTombstoneDecoder.read(stream)
                                } ?: "Kein Tombstone mehr im Android-Ringpuffer vorhanden."
                            } catch (error: Exception) {
                                "Tombstone-Lesefehler: " +
                                    (error.message ?: error.javaClass.simpleName)
                            }
                            entry.append("\n\n").append(traceText.take(8500))
                        }
                        entry.toString()
                    }
                    val historical = if (entries.isEmpty()) {
                        "Android hat keine früheren Prozessabbrüche für Pocket AI erfasst."
                    } else entries.joinToString("\n\n---\n\n")
                    "ANDROID-PROZESSDIAGNOSE – normale Updates sind keine Abstürze; keine Übertragung\n\n" +
                        uiDelayReport + "\n\n" + bindReport +
                        "\n\n--- MODELL-DOWNLOAD / JOBSCHEDULER ---\n" +
                        downloadReport +
                        "\n\n---\n\n" + historical +
                        "\n\n--- MCP/MODELL-EVENTS ---\n\n" +
                        PocketDiagnostics.report(applicationContext).take(12_000)
                }
                if (!isFinishing && !isDestroyed) {
                    showError("Android-Prozessdiagnose", IllegalStateException(report))
                }
            } catch (error: Exception) {
                showError("Systemdiagnose konnte nicht gelesen werden", error)
            } finally {
                button.isEnabled = true
                button.text = "Letzte App-Abstürze prüfen"
            }
        }
    }

    private fun showDiagnosticTimeline(inChat: Boolean) {
        lifecycleScope.launch {
            val report = PocketDiagnostics.report(applicationContext)
            if (inChat) {
                // Host-side record, NOT model-generated diagnostic evidence.
                displayMcpToolResult("⌁ LOKALE MCP/MODELL-DIAGNOSE\n" +
                    report.take(12_000))
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.diagnostics_title)
                .setMessage(report)
                .setNeutralButton(R.string.diagnostics_copy) { _, _ ->
                    val clipboard =
                        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Pocket AI diagnostics", report))
                    toast(getString(R.string.diagnostics_copy))
                }
                .setNegativeButton(R.string.diagnostics_clear) { _, _ ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.diagnostics_clear)
                        .setMessage(R.string.diagnostics_hint)
                        .setNegativeButton(R.string.language_cancel, null)
                        .setPositiveButton(R.string.diagnostics_clear) { _, _ ->
                            lifecycleScope.launch {
                                PocketDiagnostics.clear(applicationContext)
                                toast(getString(R.string.diagnostics_cleared))
                            }
                        }.show()
                }
                .setPositiveButton(R.string.diagnostics_close, null)
                .show()
        }
    }

    private fun displayMcpToolResult(result: String) {
        messages.add(ChatMessage(false, result))
        chatAdapter.notifyItemInserted(messages.lastIndex)
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        showPage(0)
        messagesView.scrollToPosition(messages.lastIndex)
    }

    private fun handleMcpOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "me.ailinux.pocket" && uri.host == "oauth" &&
            uri.path == "/callback") {
            try {
                mcpUi.onOAuthCallback(java.net.URI(uri.toString()))
            } catch (e: Exception) {
                showError("MCP OAuth Rückleitung", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMcpOAuthIntent(intent)
    }

    override fun onDestroy() {
        persistChatNow()
        chatWriteExecutor.shutdown()
        if (::mcpUi.isInitialized) mcpUi.close()
        liteRtRuntime?.shutdownAsync()
        liteRtRuntime = null
        super.onDestroy()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun showError(title: String, error: Throwable) {
        val domain = when {
            title.contains("MCP", true) || title.contains("OAuth", true) ->
                PocketDiagnostics.Domain.MCP
            title.contains("LiteRT", true) -> PocketDiagnostics.Domain.LITERT
            title.contains("Modell", true) || title.contains("Engine", true) ||
                title.contains("GGUF", true) -> PocketDiagnostics.Domain.GGUF
            else -> PocketDiagnostics.Domain.SYSTEM
        }
        // This path also renders a successful, host-constructed process report;
        // opening it must not fabricate SYSTEM/DIAGNOSTIC FAIL events.
        val isReport = title == "Android-Prozessdiagnose"
        val id = if (isReport) "" else
            failure(domain, PocketDiagnostics.Step.DIAGNOSTIC, error)
        // Arbitrary exception messages can contain signed URLs and auth headers.
        val details = if (isReport)
            error.message.orEmpty().take(15000)
        else getString(R.string.diagnostics_error, PocketDiagnostics.publicError(error), id)
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(details)
            .setNeutralButton("Diagnose kopieren") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val version = packageManager.getPackageInfo(packageName, 0).versionName
                val report = "Pocket AI $version\n$title\n$details"
                clipboard.setPrimaryClip(ClipData.newPlainText("Pocket AI Diagnose", report))
                toast("Diagnose in Zwischenablage kopiert")
            }
            .setPositiveButton("OK", null).show()
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()

    override fun onStart() {
        super.onStart()
        downloadUiHandler.removeCallbacks(downloadUiPoll)
        downloadUiHandler.post(downloadUiPoll)
        uiPulseHandler.removeCallbacks(uiPulse)
        uiPulseEnabled = true
        uiResponsiveness.arm(SystemClock.uptimeMillis())
        uiPulseHandler.postDelayed(uiPulse, UiResponsiveness.INTERVAL_MS)
        if (mcpBindingInProgress) {
            mcpBindPoll?.let { uiPulseHandler.post(it) }
        }
    }

    override fun onStop() {
        persistChatNow()
        downloadUiHandler.removeCallbacks(downloadUiPoll)
        mcpBindPoll?.let { uiPulseHandler.removeCallbacks(it) }
        uiPulseEnabled = false
        uiPulseHandler.removeCallbacks(uiPulse)
        uiResponsiveness.disarm()
        generationJob?.cancel()
        super.onStop()
    }

    companion object {
        private const val PREF_ENABLE_THINKING = "enable_thinking"
        private const val PREF_LOADING_MODEL = "pocket_loading_model"
    }
}
