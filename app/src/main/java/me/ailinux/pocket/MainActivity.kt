package me.ailinux.pocket

import android.content.res.ColorStateList
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val messages = mutableListOf<ChatMessage>()
    private val chatAdapter = ChatAdapter(messages)
    private var activeModel: File? = null
    private var generationJob: Job? = null
    private var busy = false
    private var transferBusy = false
    private var activeTransferName: String? = null
    private var activePartialLabel: TextView? = null
    private var cancelTransfer = AtomicBoolean(false)
    private var currentPage = 0
    private val prefs by lazy { getSharedPreferences("pocket_settings", MODE_PRIVATE) }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModel(it) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unbekannt"
        findViewById<TextView>(R.id.appVersion).text = "AILinux Pocket AI  •  $installedVersion"
        store = ModelStore(this)

        messagesView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesView.adapter = chatAdapter

        findViewById<TextView>(R.id.navChat).setOnClickListener { showPage(0) }
        findViewById<TextView>(R.id.navModels).setOnClickListener { showPage(1) }
        findViewById<TextView>(R.id.navSettings).setOnClickListener { showPage(2) }
        findViewById<MaterialButton>(R.id.importButton).setOnClickListener {
            if (!transferBusy) modelPicker.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.browseButton).setOnClickListener { browseRepository() }
        findViewById<MaterialButton>(R.id.quickStartButton).setOnClickListener { quickStart() }
        findViewById<MaterialButton>(R.id.urlButton).setOnClickListener { promptDirectUrl() }
        cancelDownload.setOnClickListener { cancelTransfer.set(true) }

        sendButton.setOnClickListener {
            if (generationJob?.isActive == true) generationJob?.cancel() else sendMessage()
        }
        messageInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        systemPrompt.setText(prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT))
        val choices = listOf("128 Tokens", "256 Tokens", "512 Tokens", "1024 Tokens")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, choices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        tokenSpinner.adapter = adapter
        val selected = listOf(128, 256, 512, 1024).indexOf(prefs.getInt("max_tokens", 512))
        tokenSpinner.setSelection(selected.coerceAtLeast(0))
        findViewById<MaterialButton>(R.id.saveSettingsButton).setOnClickListener {
            val count = listOf(128, 256, 512, 1024)[tokenSpinner.selectedItemPosition]
            prefs.edit().putString("system_prompt", systemPrompt.text.toString())
                .putInt("max_tokens", count).apply()
            toast("Gespeichert. Der System-Prompt gilt ab dem nächsten Modell-Laden.")
        }
        findViewById<MaterialButton>(R.id.clearChatButton).setOnClickListener {
            if (!busy) {
                activeModel?.let { loadModel(it) } ?: run {
                    messages.clear()
                    chatAdapter.notifyDataSetChanged()
                    findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                }
            }
        }
        findViewById<MaterialButton>(R.id.crashDiagnosticsButton).setOnClickListener {
            showProcessExitInfo()
        }

        // This preference is written synchronously before native load and only
        // cleared on a normal outcome. It survives abrupt Android process kills.
        val interruptedModel = prefs.getString(PREF_LOADING_MODEL, null)
        if (interruptedModel != null) {
            prefs.edit().remove(PREF_LOADING_MODEL).apply()
            findViewById<View>(android.R.id.content).post {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle("Vorheriger Modell-Start abgebrochen")
                        .setMessage("Android hat Pocket AI beim Laden von " +
                            interruptedModel + " unerwartet beendet. " +
                            "Prüfe unter Setup die Systemdiagnose. " +
                            "Das Modell wurde nicht gelöscht.")
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
        // During generation the send button must remain usable as STOP.
        val canInteract = loaded && !busy
        messageInput.isEnabled = canInteract
        sendButton.isEnabled = canInteract || generationJob?.isActive == true
    }

    private fun refreshLibrary() {
        activePartialLabel = null
        modelsContainer.removeAllViews()
        val files = store.list()
        findViewById<TextView>(R.id.storageLabel).text =
            files.size.toString() + " Modelle • " +
            ModelStore.displaySize(files.sumOf { it.length() })
        if (files.isEmpty()) {
            modelsContainer.addView(simpleLabel("Hier ist noch Platz für dein erstes GGUF-Modell."))
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
            load.isEnabled = !busy && !isActive
            load.setOnClickListener { loadModel(file) }
            buttons.addView(load)
            val remove = smallButton("Löschen", false)
            remove.isEnabled = !busy && !isActive
            remove.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Modell löschen?")
                    .setMessage(file.name + "\nDie lokale GGUF-Datei wird entfernt.")
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
                // commit(), not apply(): we want the marker durable before JNI starts.
                prefs.edit().putString(PREF_LOADING_MODEL, file.name).commit()
                withContext(Dispatchers.IO) {
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
                            (error.message ?: "Natives Modellladen fehlgeschlagen."),
                            error
                        )
                    }
                    val instruction = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT).orEmpty()
                    if (instruction.isNotBlank()) engine.setSystemPrompt(instruction)
                }
                activeModel = file
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

    private fun sendMessage() {
        if (busy || !::engine.isInitialized || activeModel == null) {
            toast("Bitte zuerst ein Modell laden.")
            return
        }
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
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
        setStatus("● GENERIERT …")
        generationJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    engine.sendUserPrompt(text, prefs.getInt("max_tokens", 512)).collect { token ->
                        withContext(Dispatchers.Main) {
                            answer.content += token
                            chatAdapter.notifyItemChanged(index)
                            messagesView.scrollToPosition(index)
                        }
                    }
                }
                setStatus("● BEREIT")
            } catch (_: CancellationException) {
                setStatus("● GESTOPPT")
            } catch (error: Exception) {
                answer.content += "\n[Fehler: " + (error.message ?: "Unbekannt") + "]"
                chatAdapter.notifyItemChanged(index)
                setStatus("Generierung fehlgeschlagen")
            } finally {
                busy = false
                sendButton.text = "➤"
                renderChatState()
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
            .setMessage("GitHub-Releases und öffentliche Hugging-Face-GGUF-Dateien.")
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
                            toast("Keine GGUF-Dateien gefunden.")
                            catalogStatus("Keine GGUF-Dateien gefunden")
                        } else {
                            val visible = found.take(100)
                            val options = visible.map {
                                it.name + "\n" +
                                    (if (it.bytes > 0) ModelStore.displaySize(it.bytes) else "Größe unbekannt") +
                                    " · " + it.source
                            }.toTypedArray()
                            catalogStatus(found.size.toString() + " GGUF-Dateien gefunden")
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

    private fun quickStart() {
        if (transferBusy) { toast("Ein Download läuft. Bitte erst abschließen oder abbrechen."); return }
        data class Starter(val title: String, val repo: String, val filename: String)
        val models = listOf(
            Starter("SmolLM2 · 360M · 258 MB", "bartowski/SmolLM2-360M-Instruct-GGUF", "SmolLM2-360M-Instruct-Q4_K_M.gguf"),
            Starter("Qwen 2.5 · 0.5B · 379 MB", "bartowski/Qwen2.5-0.5B-Instruct-GGUF", "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"),
            Starter("Qwen 3.5 · 0.8B · 508 MB", "unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf"),
            Starter("Gemma 3 · 1B · 769 MB", "unsloth/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf")
        )
        AlertDialog.Builder(this)
            .setTitle("Leichte Offline-Modelle")
            .setMessage("Öffentliche GGUFs im Format Q4_K_M. Die echten Dateien werden vor dem Download beim Anbieter abgefragt.")
            .setItems(models.map { it.title }.toTypedArray()) { _, index ->
                val starter = models[index]
                catalogStatus("Prüfe " + starter.title + " …")
                lifecycleScope.launch {
                    try {
                        val found = withContext(Dispatchers.IO) {
                            SourceCatalog.list(RepositoryRef(RepositoryHost.HUGGING_FACE, starter.repo))
                        }
                        val model = found.firstOrNull { it.name.equals(starter.filename, true) }
                            ?: error("Ausgewähltes Modell nicht mehr verfügbar.")
                        catalogStatus("Bereit zum Download: " + model.name)
                        confirmDownload(model)
                    } catch (error: Exception) {
                        showError("Modell konnte nicht gefunden werden", error)
                    }
                }
            }.setNegativeButton("Schließen", null).show()
    }

    private fun promptDirectUrl() {
        if (transferBusy) { toast("Ein Download läuft. Bitte erst abschließen oder abbrechen."); return }
        val field = EditText(this).apply {
            hint = "https://.../model.Q4_K_M.gguf"
            setSingleLine(false)
            minLines = 2
            setPadding(dp(17), dp(15), dp(17), dp(15))
        }
        AlertDialog.Builder(this)
            .setTitle("GGUF-Link")
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
                "Native CPU-Inferenz wird vorbereitet. Große Modelle können dauern."
        }
    }

    private fun showProcessExitInfo() {
        val button = findViewById<MaterialButton>(R.id.crashDiagnosticsButton)
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
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native Crash (C++/JNI)"
                            ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin Crash"
                            ApplicationExitInfo.REASON_ANR -> "App reagiert nicht (ANR)"
                            ApplicationExitInfo.REASON_SIGNALED -> "Signalbedingter Abbruch"
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
                            if (summary.isNotEmpty()) append("\nPhase: ").append(summary)
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
                    if (entries.isEmpty()) {
                        "Android hat keine früheren Prozessabbrüche für Pocket AI erfasst."
                    } else {
                        "NATIVE CRASH DIAGNOSE – keine automatische Übertragung\n\n" +
                            entries.joinToString("\n\n---\n\n")
                    }
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

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    companion object {
        private const val PREF_LOADING_MODEL = "pocket_loading_model"
        private const val DEFAULT_SYSTEM_PROMPT =
            "Du bist Pocket AI, ein hilfreicher, direkter, datenschutzfreundlicher Offline-Assistent. Antworte in der Sprache des Benutzers."
    }
}
