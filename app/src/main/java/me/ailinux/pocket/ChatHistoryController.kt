package me.ailinux.pocket

import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.Editable
import android.text.TextWatcher
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.abs

/**
 * Per-activity history presenter. Durable private SQLite is intentionally
 * independent from the native GGUF/LiteRT session. Reopening a chat never
 * claims to restore destroyed model KV or already-executed MCP calls.
 */
internal class ChatHistoryController(
    private val activity: AppCompatActivity,
    private val items: MutableList<ChatMessage>,
    private val adapter: ChatAdapter,
    private val currentModel: () -> String,
    private val canSwitch: () -> Boolean,
    private val resetNative: suspend () -> Unit,
    private val onNewChat: () -> Unit,
    private val onOpenChat: () -> Unit
) {
    private val db = ChatHistoryRepository(activity.applicationContext)
    private val prefs = activity.getSharedPreferences("pocket_settings", android.content.Context.MODE_PRIVATE)
    private var currentId = prefs.getString("history_active_conversation", null)
        ?: UUID.randomUUID().toString()
    private var saveJob: Job? = null
    private var searchJob: Job? = null
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var touchX = 0f
    private var touchY = 0f
    /** Original SQLite position of the first loaded message (paging safe). */
    private var basePosition = 0
    private val list: LinearLayout get() = activity.findViewById(R.id.historyList)

    fun start() {
        prefs.edit().putString("history_active_conversation", currentId).apply()
        val initial = currentId
        activity.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                db.ensure(initial)
                db.messages(initial)
            }
            if (currentId == initial && items.isEmpty() && saved.isNotEmpty()) {
                basePosition = saved.first().index
                items.addAll(saved.map { ChatMessage(it.isUser, it.content) })
                adapter.notifyDataSetChanged()
                activity.findViewById<View>(R.id.emptyState).visibility = View.GONE
            }
        }
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = save(true)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = save()
        })
        activity.findViewById<View>(R.id.chatHistoryButton).setOnClickListener { drawer(true) }
        activity.findViewById<View>(R.id.closeHistoryButton).setOnClickListener { drawer(false) }
        activity.findViewById<View>(R.id.historyScrim).setOnClickListener { drawer(false) }
        activity.findViewById<View>(R.id.newHistoryChatButton).setOnClickListener {
            if (!canSwitch()) {
                message("Bitte laufende Modellaktion zuerst beenden")
            } else {
                drawer(false)
                onNewChat()
            }
        }
        activity.findViewById<EditText>(R.id.historySearch).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(s: Editable?) = Unit
            }
        )
        activity.findViewById<RecyclerView>(R.id.chatMessages).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchX = e.x; touchY = e.y }
                MotionEvent.ACTION_UP -> {
                    val deltaX = e.x - touchX
                    val deltaY = e.y - touchY
                    val rtl = activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                    val min = dp(90)
                    // Do not hijack Android Back from the physical screen edge.
                    if (abs(deltaX) > min && abs(deltaY) < dp(45) &&
                        (if (rtl) deltaX > 0 else deltaX < 0) &&
                        touchX > dp(27) && touchX < v.width - dp(27)) {
                        drawer(true)
                    }
                }
            }
            false
        }
    }

    fun save(immediate: Boolean = false) {
        val selected = currentId
        val snapshot = ChatHistoryRepository.Snapshot(selected, currentModel(),
            items.mapIndexed { i, message ->
                ChatHistoryRepository.Message(basePosition + i, message.isUser, message.content)
            })
        // Never cancel a committed, urgent write when a model switch clears
        // the visible list. Only the debounced streaming update may be canceled.
        saveJob?.cancel()
        if (immediate) {
            writeScope.launch { db.persist(snapshot) }
        } else {
            saveJob = writeScope.launch {
                delay(550)
                db.persist(snapshot)
            }
        }
    }

    fun beginNew() {
        // Complete the previous conversation; never replace existing chat rows.
        save(true)
        currentId = UUID.randomUUID().toString()
        basePosition = 0
        prefs.edit().putString("history_active_conversation", currentId).apply()
    }

    private fun drawer(visible: Boolean) {
        activity.findViewById<View>(R.id.historyScrim).visibility =
            if (visible) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.historyDrawer).visibility =
            if (visible) View.VISIBLE else View.GONE
        if (visible) refresh()
    }

    private fun refresh() {
        if (activity.findViewById<View>(R.id.historyDrawer).visibility != View.VISIBLE) return
        val search = activity.findViewById<EditText>(R.id.historySearch).text.toString()
        searchJob?.cancel()
        searchJob = activity.lifecycleScope.launch {
            val conversations = withContext(Dispatchers.IO) { db.list(search, limit = 100) }
            list.removeAllViews()
            if (conversations.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = "Keine Chats gefunden"; setPadding(dp(12), dp(18), 0, dp(18))
                })
            }
            for (entry in conversations) {
                val row = TextView(activity).apply {
                    text = (if (entry.pinned) "📌 " else "") + entry.title + "\n" +
                        entry.model.ifBlank { "Kein Modell" } + " · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(entry.updatedAt))
                    setTextColor(activity.getColor(R.color.ink))
                    setTextSize(13f)
                    setPadding(dp(12), dp(14), dp(12), dp(14))
                    setBackgroundResource(R.drawable.bg_panel)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                        it.bottomMargin = dp(8)
                    }
                    contentDescription = "Chat " + entry.title
                    setOnClickListener { select(entry) }
                    setOnLongClickListener { actions(entry); true }
                }
                list.addView(row)
            }
        }
    }

    private fun actions(entry: ChatHistoryRepository.Conversation) {
        val values = arrayOf("Öffnen", "Umbenennen",
            if (entry.pinned) "Pin lösen" else "Anpinnen", "Archivieren", "Löschen")
        AlertDialog.Builder(activity).setTitle(entry.title)
            .setItems(values) { _, i ->
                when (i) {
                    0 -> select(entry)
                    1 -> {
                        val field = EditText(activity).apply { setText(entry.title); selectAll() }
                        AlertDialog.Builder(activity).setTitle("Chat umbenennen")
                            .setView(field).setNegativeButton("Abbrechen", null)
                            .setPositiveButton("Speichern") { _, _ ->
                                activity.lifecycleScope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.rename(entry.id, field.text.toString())
                                    }
                                    refresh()
                                }
                            }.show()
                    }
                    2, 3 -> activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            if (i == 2) db.setPinned(entry.id, !entry.pinned)
                            else db.setArchived(entry.id, true)
                        }
                        refresh()
                    }
                    4 -> AlertDialog.Builder(activity)
                        .setTitle("Chat endgültig löschen?")
                        .setMessage("Der lokale Verlauf wird erst nach Bestätigung gelöscht.")
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Löschen") { _, _ ->
                            if (entry.id == currentId && !canSwitch()) {
                                message("Laufende Aktion zuerst beenden")
                            } else activity.lifecycleScope.launch {
                                withContext(Dispatchers.IO) { db.delete(entry.id) }
                                if (entry.id == currentId) {
                                    // Do not recreate a deleted chat via a pending
                                    // transcript flush from the old in-memory list.
                                    saveJob?.cancel()
                                    currentId = UUID.randomUUID().toString()
                                    basePosition = 0
                                    prefs.edit().putString("history_active_conversation", currentId).apply()
                                    items.clear()
                                    adapter.notifyDataSetChanged()
                                    drawer(false)
                                    onNewChat()
                                } else refresh()
                            }
                        }.show()
                }
            }.show()
    }

    private fun select(entry: ChatHistoryRepository.Conversation) {
        if (!canSwitch()) { message("Laufende Aktion zuerst beenden"); return }
        activity.lifecycleScope.launch {
            try {
                save(true)
                val history = withContext(Dispatchers.IO) { db.messages(entry.id) }
                // Native context is intentionally reset, never silently replay tools.
                resetNative()
                currentId = entry.id
                basePosition = history.firstOrNull()?.index ?: 0
                prefs.edit().putString("history_active_conversation", currentId).apply()
                items.clear()
                items.addAll(history.map { ChatMessage(it.isUser, it.content) })
                adapter.notifyDataSetChanged()
                activity.findViewById<View>(R.id.emptyState).visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
                drawer(false)
                onOpenChat()
                message(if (entry.model.isNotBlank() && entry.model != currentModel())
                    "Verlauf geladen. Originalmodell: " + entry.model
                else "Verlauf geladen. Nativer KV-Kontext wurde neu gestartet.")
            } catch (failure: Exception) {
                AlertDialog.Builder(activity).setTitle("Chat nicht geöffnet")
                    .setMessage(failure.message ?: "Unbekannter Fehler")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun message(s: String) =
        android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (activity.resources.displayMetrics.density * v + .5f).toInt()
}
