package me.ailinux.pocket

import android.content.res.ColorStateList
import android.os.Bundle
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
    private var cancelTransfer = AtomicBoolean(false)
    private var currentPage = 0
    private val prefs by lazy { getSharedPreferences("pocket_settings", MODE_PRIVATE) }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModel(it) }
    }

    private val chatPage by lazy { findViewById<View>(R.id.chatPage) }
    private val libraryPage by lazy { findViewById<View>(R.id.libraryPage) }
    private val settingsPage by lazy { findViewById<View>(R.id.settingsPage) }
    private val heroTitle by lazy { findViewById<TextView>(R.id.heroTitle) }
    private val heroSubtitle by lazy { findViewById<TextView>(R.id.heroSubtitle) }
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
        store = ModelStore(this)

        messagesView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesView.adapter = chatAdapter

        findViewById<MaterialButton>(R.id.heroAction).setOnClickListener { showPage(1) }
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

        refreshLibrary()
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
    }

    private fun refreshLibrary() {
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
            line.addView(simpleLabel("↓ Unterbrochen: " + name + " (" +
                ModelStore.displaySize(part.length()) + ")"))
            if (source != null) {
                val resume = smallButton("Fortsetzen", false)
                resume.setOnClickListener {
                    startDownload(RemoteModel(
                        name, source, -1, prefs.getString("pending_sha_" + name, null)
                    ))
                }
                line.addView(resume)
            }
            val remove = smallButton("Teil-Download verwerfen", false)
            remove.setOnClickListener {
                if (!transferBusy) {
                    store.delete(part)
                    prefs.edit().remove("pending_url_" + name).remove("pending_sha_" + name).apply()
                    refreshLibrary()
                }
            }
            line.addView(remove)
            modelsContainer.addView(line)
        }
    }

    private fun loadModel(file: File) {
        if (busy) return
        if (!::engine.isInitialized) { toast("Engine initialisiert noch."); return }
        busy = true
        setStatus("Lade GGUF …")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (engine.state.value) {
                        is InferenceEngine.State.ModelReady,
                        is InferenceEngine.State.Error -> engine.cleanUp()
                        else -> Unit
                    }
                    engine.loadModel(file.absolutePath)
                    val instruction = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT).orEmpty()
                    if (instruction.isNotBlank()) engine.setSystemPrompt(instruction)
                }
                activeModel = file
                messages.clear()
                chatAdapter.notifyDataSetChanged()
                findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
                heroTitle.text = file.name.removeSuffix(".gguf")
                heroSubtitle.text = ModelStore.displaySize(file.length()) +
                    "  •  Läuft lokal auf deinem Android-Gerät"
                findViewById<MaterialButton>(R.id.heroAction).text = "Modell wechseln  →"
                setStatus("● BEREIT")
                refreshLibrary()
                showPage(0)
            } catch (error: Exception) {
                activeModel = null
                setStatus("Ladefehler")
                showError("GGUF konnte nicht geladen werden", error)
            } finally {
                busy = false
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
            }
        }
    }

    private fun importModel(uri: Uri) {
        if (transferBusy) return
        transferBusy = true
        cancelTransfer = AtomicBoolean(false)
        setDownloadUi("Importiere GGUF …", true)
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
                refreshLibrary()
            }
        }
    }

    private fun browseRepository() {
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
                    setDownloadUi("Suche Modelle …", false)
                    try {
                        val ref = SourceCatalog.parseRepository(text)
                        val found = withContext(Dispatchers.IO) { SourceCatalog.list(ref) }
                        prefs.edit().putString("recent_repo", text).apply()
                        if (found.isEmpty()) {
                            toast("Keine GGUF-Dateien gefunden.")
                            setDownloadUi("Keine GGUF-Dateien gefunden", false)
                        } else {
                            val visible = found.take(100)
                            val options = visible.map {
                                it.name + "\n" +
                                    (if (it.bytes > 0) ModelStore.displaySize(it.bytes) else "Größe unbekannt") +
                                    " · " + it.source
                            }.toTypedArray()
                            setDownloadUi(found.size.toString() + " GGUF-Dateien gefunden", false)
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
                setDownloadUi("Prüfe " + starter.title + " …", false)
                lifecycleScope.launch {
                    try {
                        val found = withContext(Dispatchers.IO) {
                            SourceCatalog.list(RepositoryRef(RepositoryHost.HUGGING_FACE, starter.repo))
                        }
                        val model = found.firstOrNull { it.name.equals(starter.filename, true) }
                            ?: error("Ausgewähltes Modell nicht mehr verfügbar.")
                        setDownloadUi("Bereit zum Download: " + model.name, false)
                        confirmDownload(model)
                    } catch (error: Exception) {
                        showError("Modell konnte nicht gefunden werden", error)
                    }
                }
            }.setNegativeButton("Schließen", null).show()
    }

    private fun promptDirectUrl() {
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
        AlertDialog.Builder(this)
            .setTitle("Modell herunterladen?")
            .setMessage(model.name + "\n" +
                (if (model.bytes > 0) ModelStore.displaySize(model.bytes) else "Größe unbekannt") +
                "\nQuelle: " + model.url + "\n\nDie Datei wird im privaten App-Speicher abgelegt.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Download starten") { _, _ -> startDownload(model) }
            .show()
    }

    private fun setDownloadUi(label: String, running: Boolean) {
        downloadStatus.text = label
        downloadProgress.visibility = if (running) View.VISIBLE else View.GONE
        downloadProgress.isIndeterminate = running
        cancelDownload.visibility = if (running) View.VISIBLE else View.GONE
        cancelDownload.isEnabled = running
    }

    private fun updateProgress(p: TransferProgress) {
        downloadProgress.isIndeterminate = p.percent < 0
        if (p.percent >= 0) downloadProgress.progress = p.percent
        downloadStatus.text = ModelStore.displaySize(p.done) + " / " +
            (if (p.total > 0) ModelStore.displaySize(p.total) else "unbekannt") +
            (if (p.percent >= 0) "  •  " + p.percent + "%" else "")
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
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun showError(title: String, error: Throwable) {
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton("OK", null).show()
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT =
            "Du bist Pocket AI, ein hilfreicher, direkter, datenschutzfreundlicher Offline-Assistent. Antworte in der Sprache des Benutzers."
    }
}
