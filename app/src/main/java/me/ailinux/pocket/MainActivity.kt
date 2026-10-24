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
import me.ailinux.pocket.mcp.McpHealthCheck
import me.ailinux.pocket.mcp.McpLogCheck
import me.ailinux.pocket.mcp.McpQuickActions
import me.ailinux.pocket.mcp.McpToolFeedback
import me.ailinux.pocket.mcp.McpServerGuidance
import me.ailinux.pocket.mcp.McpProgressiveBridge
import me.ailinux.pocket.mcp.McpTool
import me.ailinux.pocket.mcp.McpVault
import me.ailinux.pocket.mcp.McpProfile
import me.ailinux.pocket.mcp.McpAuth
import me.ailinux.pocket.mcp.McpOAuth
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
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
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
    private var activeModel: File? = null
    /** Alternative LiteRT-LM runtime, only for .litertlm models. */
    private var liteRtRuntime: LiteRtRuntime? = null
    private var generationJob: Job? = null
    private var busy = false
    private var transferBusy = false
    private var activeTransferName: String? = null
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
            if (activeModel?.name?.endsWith(".litertlm", true) == true) {
                // Never claim native MCP tool calling for the LiteRT-LM path.
                if (active != null) {
                    mcpAgent.clearSessionSilently()
                    toast("KI + MCP-Agent benötigt derzeit GGUF. MCP-Schnellbefehle bleiben verfügbar.")
                }
                updateMcpBadge(null)
            } else if (activeModel != null) rebindNativeMcpTools(active)
            else updateMcpBadge(active)
        }
        // No agent session does not mean the device, its saved MCP profiles,
        // or the remote server is offline. Show exactly what is disabled.
        updateMcpBadge(null)
        handleMcpOAuthIntent(intent)

        messagesView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesView.adapter = chatAdapter

        findViewById<TextView>(R.id.navChat).setOnClickListener { showPage(0) }
        findViewById<TextView>(R.id.navModels).setOnClickListener { showPage(1) }
        findViewById<TextView>(R.id.navSettings).setOnClickListener { showPage(2) }
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
        cancelDownload.setOnClickListener { cancelTransfer.set(true) }

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

    private fun showPage(which: Int) {
        currentPage = which
        chatPage.visibility = if (which == 0) View.VISIBLE else View.GONE
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
        chatModelName.text = ChatPresentation.modelLabel(activeModel?.name)
        emptyHint.text = ChatPresentation.emptyHint(loaded)
        messageInput.hint = ChatPresentation.inputHint(loaded)
        // Native prefill can take time on 4B CPU models. Keep the text
        // editor and navigation responsive while preventing a premature send.
        val canInteract = !busy
        messageInput.isEnabled = canInteract || mcpBindingInProgress
        sendButton.isEnabled = canInteract || generationJob?.isActive == true
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
        store.partials().forEach { part ->
            val name = part.name.removeSuffix(".part")
            val source = prefs.getString("pending_url_" + name, null)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_panel)
            }
            val runningNow = DownloadPresentation.isActivePartial(
                part.name, activeTransferName, transferBusy
            )
            val partialLabel = simpleLabel(DownloadPresentation.partialStatus(
                name, part.length(), runningNow
            ))
            line.addView(partialLabel)
            if (runningNow) {
                activePartialLabel = partialLabel
            } else {
                if (source != null) {
                    val resume = smallButton("Fortsetzen", false)
                    resume.isEnabled = !transferBusy
                    resume.setOnClickListener {
                        startDownload(RemoteModel(
                            name, source, -1, prefs.getString("pending_sha_" + name, null)
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
        if (busy) return
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
                    liteRtRuntime?.let {
                        previousModelUnloaded = true
                        it.close()
                        liteRtRuntime = null
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
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                setStatus("● BEREIT")
                refreshLibrary()
                showPage(0)
            } catch (error: Exception) {
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
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    ModelStore.validate(file)
                    check(prefs.edit().putString(PREF_LOADING_MODEL, file.name).commit()) {
                        "Prozessdiagnose-Marker konnte nicht gespeichert werden."
                    }
                    // Never hold two multi-GB models in memory simultaneously.
                    if (::engine.isInitialized && (engine.state.value is InferenceEngine.State.ModelReady ||
                            engine.state.value is InferenceEngine.State.Error)) {
                        engine.cleanUp()
                    }
                    liteRtRuntime?.close()
                    liteRtRuntime = null
                    val prompt = BrumoSystemPrompt.resolve(prefs.getString("system_prompt", null))
                    LiteRtRuntime.load(applicationContext, file, prompt)
                }
                liteRtRuntime = loaded
                activeModel = file
                // GGUF Jinja tools cannot be injected into a different engine.
                mcpAgent.clearSessionSilently()
                updateMcpBadge(null)
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                setStatus("● LITERT BEREIT · " + loaded.backendName)
                refreshLibrary()
                showPage(0)
            } catch (error: Exception) {
                activeModel = null
                setStatus("LiteRT-LM konnte nicht gestartet werden")
                showError("LiteRT-Modell/Backend fehlgeschlagen", error)
            } catch (error: LinkageError) {
                activeModel = null
                setStatus("LiteRT-LM Bibliothek nicht verfügbar")
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
        if (activeModel?.name?.endsWith(".litertlm", true) == true) {
            toast("LiteRT-LM: Modell-MCP noch nicht freigegeben. MCP-Schnellbefehle sind separat verfügbar.")
            return
        }
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

    /** Reset only user/assistant history; NEVER reload model or rerun MCP tools. */
    private fun resetLocalConversation() {
        if (busy || mcpDispatchInFlight || mcpBindingInProgress ||
                generationJob?.isActive == true) {
            toast("Bitte laufende Modell- oder MCP-Aktion zuerst beenden.")
            return
        }
        if (activeModel == null) {
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
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                toast("Chat zurückgesetzt · Modell nicht neu geladen")
            } catch (problem: Exception) {
                setStatus("Chat-Reset fehlgeschlagen · Modell bleibt unverändert")
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
                " · GGUF unter MODELLE laden, danach wird die Bridge eingebunden."
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
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                // The MCP badge becomes ACTIVE only after the complete
                // synchronous native KV prefill returns ModelReady.
                finishMcpBindingProgress(completed = true)
                updateMcpBadge(active)
                setStatus("● BEREIT")
                toast(
                    if (active == null) "MCP ausgeschaltet · Chatkontext neu gestartet"
                    else "Spark hat " + active.tools.size +
                        " native MCP-Tools eingebunden, Thinking " +
                        active.thinkingMode.uppercase() + " · Chatkontext neu gestartet"
                )
            } catch (problem: Exception) {
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
        messageInput.setText("")
        messages.add(ChatMessage(true, question))
        messages.add(ChatMessage(false,
            "🔎 Prüfe MCP-Verbindung und tools/list am echten Server …"))
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
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
                        McpHealthCheck.success(authorized, server, duration)
                    }
                }
                messages[index].content = result
                setStatus("● MCP PRÜFUNG BEENDET")
            } catch (problem: Exception) {
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
        messageInput.setText("")
        messages.add(ChatMessage(true, question))
        messages.add(ChatMessage(false, "🔎 Prüfe reale Log-Werkzeugfreigaben mit tools/list …"))
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
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
                        McpLogCheck.capabilityReport(authorized, info)
                    }
                }
                messages[index].content = reply
                setStatus("● LOG-TOOLLISTE BESTÄTIGT")
            } catch (problem: Exception) {
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
                    "⚠️ MCP-Toolliste konnte nicht live abgefragt werden.\n" +
                        "Server: " + profile.title + "\n" +
                        "Grund: " + (problem.message ?: "Unbekannter Netzwerkfehler").take(900) +
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
                        val proposal = McpQuickActions.validatedProposal(request, info)
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
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val latest = McpVault(applicationContext).get(profile.id)
                                    ?: error("MCP-Profil wurde entfernt")
                                check(latest.endpoint == authorized.endpoint &&
                                      latest.auth == authorized.auth) {
                                    "MCP-Profil wurde während der Freigabe geändert"
                                }
                                McpConnection.callTool(authorized, proposal.tool, proposal.args)
                            }
                            messages[answerIndex].content =
                                McpQuickActions.displayReceipt(proposal.tool, result)
                            setStatus("● MCP SERVERANTWORT")
                        } catch (problem: Exception) {
                            messages[answerIndex].content =
                                "⚠️ MCP-Leseabfrage fehlgeschlagen / Ergebnis unbekannt.\n" +
                                "Werkzeug: " + proposal.tool.name + "\n" +
                                (problem.message ?: "Netzwerk- oder Serverfehler").take(550) +
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
                messages[answerIndex].content =
                    "⚠️ MCP-Werkzeug nicht ausgeführt.\n" +
                    "Profil: " + profile.title + "\nGrund: " + detail +
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
        val runtime = McpProgressiveBridge.Run(
            active.progressiveSources, active.progressiveCreatedAtMs
        )
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
                    answer.content = "⚠️ Auto-Bridge: Kein gültiger Toolaufruf. " +
                        decision.reason + "\nKeine ungeprüfte MCP-Aktion ausgeführt."
                    chatAdapter.notifyItemChanged(answerIndex)
                    return false
                }
                is McpAgentBridge.Decision.Answer -> {
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
                        outcome = try {
                            runtime.search(
                                args.getString("query"),
                                args.optInt("limit", McpProgressiveBridge.MAX_RESULTS)
                            )
                        } catch (issue: Exception) {
                            McpProgressiveBridge.localFailure(issue.message.orEmpty())
                        }
                        if (!outcome.optBoolean("isError", false)) {
                            hadSearch = true
                        }
                        showEvidence(
                            McpProgressiveBridge.displaySearchResult(outcome)
                        )
                    } else if (meta == McpProgressiveBridge.DETAILS) {
                        outcome = try {
                            runtime.details(args.getString("tool_id"))
                        } catch (issue: Exception) {
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
                        try {
                            outcome = withContext(Dispatchers.IO) {
                                mcpAgent.executeAuto(active, plan)
                            }
                        } catch (issue: Exception) {
                            showEvidence(
                                "⚠️ MCP-Aufruf fehlgeschlagen / Ergebnis unbekannt: " +
                                (issue.message ?: "Netzwerk-/Serverfehler").take(700) +
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
        if (busy) { toast("Pocket AI ist beschäftigt."); return }
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        // Log inspection is NOT a health-check and must be handled before
        // generic TriForce status detection or ungrounded model generation.
        if (McpLogCheck.requested(text)) {
            showLiveMcpLogCapability(text)
            return
        }
        McpHealthCheck.requestedTarget(text)?.let {
            showLiveMcpHealth(text, it)
            return
        }
        McpToolCatalog.requestedFilter(text)?.let {
            val autoActive = mcpAgent.current()?.progressiveSources?.isNotEmpty() == true
            if (!autoActive || text.startsWith("/mcp", ignoreCase = true)) {
                showLiveMcpCatalog(text, it)
                return
            }
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

        messageInput.setText("")
        val answer = ChatMessage(false, "")
        messages.add(ChatMessage(true, text))
        messages.add(answer)
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
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
                                val toolResult = try {
                                    mcpAgent.execute(agentSession, proposal)
                                } catch (problem: Exception) {
                                    answer.content = "⚠️ MCP-Aufruf fehlgeschlagen/Erfolg unbekannt: " +
                                        (problem.message ?: "Netzwerkfehler").take(1000) +
                                        "\nDer Server könnte den Auftrag bereits erhalten haben. Kein automatischer Retry."
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
                                        } catch (_: Exception) {
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
                setStatus("● BEREIT")
            } catch (_: CancellationException) {
                setStatus("● GESTOPPT")
            } catch (error: Exception) {
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
                    answer.content += "\n[Fehler: " + (error.message ?: "Unbekannt") + "]"
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
    /** LiteRT-LM chat is local and independent from the GGUF MCP tool bridge. */
    private fun sendLiteRtMessage(text: String) {
        val runtime = liteRtRuntime
        if (runtime == null) {
            toast("LiteRT-Modell zuerst laden")
            return
        }
        messageInput.setText("")
        val answer = ChatMessage(false, "")
        messages.add(ChatMessage(true, text))
        messages.add(answer)
        val index = messages.lastIndex
        chatAdapter.notifyItemRangeInserted(index - 1, 2)
        findViewById<View>(R.id.emptyState).visibility = View.GONE
        messagesView.scrollToPosition(index)
        busy = true
        sendButton.text = "■"
        messageInput.isEnabled = false
        setStatus("● LITERT · " + runtime.backendName + " …")
        generationJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runtime.send(text).collect { delta ->
                        withContext(Dispatchers.Main) {
                            answer.content += delta
                            chatAdapter.notifyItemChanged(index)
                            messagesView.scrollToPosition(index)
                        }
                    }
                }
                setStatus("● LITERT BEREIT · " + runtime.backendName)
            } catch (_: CancellationException) {
                setStatus("● LITERT GESTOPPT")
            } catch (error: Exception) {
                answer.content += "\n[LiteRT-Fehler: " +
                    (error.message ?: error.javaClass.simpleName).take(450) + "]"
                chatAdapter.notifyItemChanged(index)
                setStatus("● LITERT FEHLER")
            } finally {
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

    private fun startDownload(model: RemoteModel) {
        if (transferBusy) { toast("Ein Transfer läuft bereits."); return }
        val previousUrl = prefs.getString("pending_url_" + model.name, null)
        if (previousUrl != null && previousUrl != model.url &&
            File(store.dir, model.name + ".part").exists()) {
            toast("Teil-Download gehört zu einer anderen Quelle. Bitte zuerst löschen.")
            return
        }
        transferBusy = true
        activeTransferName = model.name
        cancelTransfer = AtomicBoolean(false)
        prefs.edit().putString("pending_url_" + model.name, model.url)
            .putString("pending_sha_" + model.name, model.sha256).apply()
        showPage(1)
        setDownloadUi("Lade " + model.name, true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    store.download(model.url, model.name, model.sha256, cancelTransfer) { p ->
                        runOnUiThread { updateProgress(p) }
                    }
                }
                prefs.edit().remove("pending_url_" + model.name)
                    .remove("pending_sha_" + model.name).apply()
                setDownloadUi("✓ " + model.name + " installiert", false)
                toast("Modell heruntergeladen!")
                refreshLibrary()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Modell bereit")
                    .setMessage("Jetzt " + result.name + " in die Engine laden?")
                    .setNegativeButton("Später", null)
                    .setPositiveButton("Laden") { _, _ -> loadModel(result) }
                    .show()
            } catch (error: Exception) {
                setDownloadUi(error.message ?: "Download fehlgeschlagen", false)
                if (error !is TransferCancelled) showError("Download fehlgeschlagen", error)
            } finally {
                transferBusy = false
                activeTransferName = null
                activePartialLabel = null
                refreshLibrary()
            }
        }
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
        AlertDialog.Builder(this)
            .setTitle("Modell herunterladen?")
            .setMessage(model.name + "\n" +
                (if (model.bytes > 0) ModelStore.displaySize(model.bytes) else "Größe unbekannt") +
                "\nQuelle: " + model.url + "\n\nDie Datei wird im privaten App-Speicher abgelegt.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Download starten") { _, _ -> startDownload(model) }
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
        val bindReport = lastMcpBindingReport
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
                        "\n\n---\n\n" + historical
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
        if (::mcpUi.isInitialized) mcpUi.close()
        liteRtRuntime?.close()
        liteRtRuntime = null
        super.onDestroy()
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun showError(title: String, error: Throwable) {
        val details = (error.message ?: error.javaClass.simpleName).take(15000)
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
        uiPulseHandler.removeCallbacks(uiPulse)
        uiPulseEnabled = true
        uiResponsiveness.arm(SystemClock.uptimeMillis())
        uiPulseHandler.postDelayed(uiPulse, UiResponsiveness.INTERVAL_MS)
        if (mcpBindingInProgress) {
            mcpBindPoll?.let { uiPulseHandler.post(it) }
        }
    }

    override fun onStop() {
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
