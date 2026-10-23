package me.ailinux.pocket.mcp

import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Session-scoped TriForce-like agent mode. No credentials or permissions
 * are persisted as "allowed forever". Each tool call needs a fresh approval.
 */
data class McpAgentSession(
    val profileId: String,
    val endpoint: String,
    val serverName: String,
    val tools: List<McpTool>,
    // Native Spark GGUF template thinking mode; default LOW for MCP planning.
    val thinkingMode: String = "low"
)

class McpAgentUi(
    private val activity: AppCompatActivity,
    private val onActiveChanged: (McpAgentSession?) -> Unit
) {
    private val vault = McpVault(activity.applicationContext)
    private val oauth = McpOAuth(vault)
    @Volatile private var session: McpAgentSession? = null
    private var selecting = false

    fun current(): McpAgentSession? = session

    /** Called after a native Jinja binding error; a stale selection must not
     * be represented as having active tool access in the Android chat UI. */
    fun clearSessionSilently() { session = null }

    fun showMenu(openManualTools: () -> Unit, openHealthCheck: () -> Unit) {
        val options = if (session != null)
            arrayOf("KI + MCP neu verbinden", "MCP-Tools manuell",
                "MCP Health-Check (echte Serverprüfung)", "KI + MCP ausschalten")
        else arrayOf("KI + MCP aktivieren", "MCP-Tools manuell",
            "MCP Health-Check (echte Serverprüfung)")
        val name = session?.let { "\nAktiv: " + it.serverName + " · " + it.tools.size + " Tools" }
            ?: "\nDein Modell arbeitet aktuell ohne automatisches MCP-Planning."
        AlertDialog.Builder(activity)
            .setTitle("Pocket AI · MCP-Bridge")
            .setMessage("Das Modell darf ein Tool vorschlagen. Jeder Aufruf muss von dir bestätigt werden." + name)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> chooseServer()
                    1 -> openManualTools()
                    2 -> openHealthCheck()
                    3 -> {
                        session = null
                        onActiveChanged(null)
                        toast("KI + MCP ausgeschaltet")
                    }
                }
            }
            .setNegativeButton("Schließen", null).show()
    }

    private fun chooseServer() {
        val profiles = runCatching { vault.list() }.getOrElse {
            error("MCP-Tresor", it); return
        }
        if (profiles.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("Noch kein MCP-Server")
                .setMessage("Unter SETUP zuerst einen MCP-Server hinzufügen und verbinden.")
                .setPositiveButton("Setup öffnen") { _, _ ->
                    activity.findViewById<android.view.View>(me.ailinux.pocket.R.id.navSettings)
                        .performClick()
                }.setNegativeButton("Abbrechen", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("KI + MCP: Server auswählen")
            .setItems(profiles.map { it.title + " · " + it.transport.title }.toTypedArray()) { _, index ->
                val original = profiles[index]
                if (selecting) { toast("MCP-Verbindungsprüfung läuft"); return@setItems }
                selecting = true
                activity.lifecycleScope.launch {
                    try {
                        val pair = withContext(Dispatchers.IO) {
                            val fresh = vault.get(original.id)
                                ?: throw IllegalStateException("MCP-Profil entfernt")
                            val authorized = oauth.refreshIfNeeded(fresh)
                            authorized to McpConnection.inspect(authorized)
                        }
                        selectTools(pair.first, pair.second)
                    } catch (problem: Exception) {
                        error("MCP-Verbindung konnte nicht geprüft werden", problem)
                    } finally {
                        selecting = false
                    }
                }
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun selectTools(profile: McpProfile, info: McpServerInfo) {
        // TriForce may expose 100+ tools. Search without losing the selected
        // tool identities when the visible list is filtered or re-ordered.
        val allowed = info.tools
            .filter { it.name.matches(Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$")) }
            .distinctBy { it.name }.take(250)
        if (allowed.isEmpty()) {
            error("MCP-Server hat keine geeigneten Tools", IllegalStateException(info.protocol))
            return
        }
        val selected = linkedSetOf<String>()
        val visible = ArrayList<McpTool>(allowed)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(6))
        }
        val intro = TextView(activity).apply {
            text = profile.title + " · " + allowed.size +
                " verfügbare Tools. Jeder Aufruf benötigt deine Zustimmung."
            textSize = 12f
        }
        root.addView(intro)
        val thinkingLabel = TextView(activity).apply {
            text = "Spark Werkzeug-Planning: Thinking-Modus"
            textSize = 12f
            setPadding(0, dp(7), 0, dp(2))
        }
        root.addView(thinkingLabel)
        val thinkingChoices = arrayOf(
            "LOW · empfohlen für MCP",
            "AUS · schnell, weniger Planung",
            "HIGH · gründlicher, braucht mehr Tokens"
        )
        val thinkingPicker = android.widget.Spinner(activity).apply {
            adapter = ArrayAdapter(activity,
                android.R.layout.simple_spinner_dropdown_item, thinkingChoices)
            setSelection(0)
        }
        root.addView(thinkingPicker, LinearLayout.LayoutParams(-1, dp(43)))
        val search = EditText(activity).apply {
            hint = "Tools suchen (z. B. discord, status …)"
            setSingleLine(true)
            textSize = 14f
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(51)))
        val count = TextView(activity).apply {
            setPadding(0, dp(5), 0, dp(7))
            textSize = 12f
        }
        root.addView(count)
        val list = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
        }
        root.addView(list, LinearLayout.LayoutParams(-1, dp(330)))
        val labels = mutableListOf<String>()
        val adapter = ArrayAdapter(activity,
            android.R.layout.simple_list_item_multiple_choice, labels)
        list.adapter = adapter

        fun refresh(query: String) {
            visible.clear()
            visible.addAll(allowed.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            })
            labels.clear()
            labels.addAll(visible.map { it.name + "  ·  " + it.description.take(46) })
            adapter.notifyDataSetChanged()
            list.clearChoices()
            visible.forEachIndexed { index, tool ->
                if (tool.name in selected) list.setItemChecked(index, true)
            }
            count.text = "Ausgewählt: " + selected.size + "/" + McpAgentBridge.MAX_TOOLS +
                "  ·  Treffer: " + visible.size
        }
        list.setOnItemClickListener { _, _, index, _ ->
            val name = visible[index].name
            if (name in selected) {
                selected.remove(name)
            } else if (selected.size >= McpAgentBridge.MAX_TOOLS) {
                list.setItemChecked(index, false)
                toast("Maximal fünf Tools für den lokalen Kontext")
            } else selected.add(name)
            count.text = "Ausgewählt: " + selected.size + "/" + McpAgentBridge.MAX_TOOLS +
                "  ·  Treffer: " + visible.size
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refresh(s?.toString().orEmpty().trim())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        refresh("")

        val dialog = AlertDialog.Builder(activity)
            .setTitle("KI + MCP · Tool-Auswahl")
            .setView(root)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("MCP-Chat aktivieren", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tools = allowed.filter { it.name in selected }
                if (tools.isEmpty()) {
                    toast("Bitte mindestens ein Tool markieren")
                } else if (runCatching { McpAgentBridge.nativeToolsJson(tools) }.onFailure {
                        error("MCP-Werkzeugliste kann nicht eingebunden werden", it)
                    }.isFailure) {
                    // Keep the old session; never enable an unbound tool list.
                } else {
                    val mode = when (thinkingPicker.selectedItemPosition) {
                        1 -> "off"
                        2 -> "high"
                        else -> "low"
                    }
                    session = McpAgentSession(
                        profile.id, profile.endpoint, profile.title, tools, mode
                    )
                    onActiveChanged(session)
                    toast("MCP-Werkzeuge mit Thinking " + mode.uppercase() +
                        " werden in Spark eingebunden.")
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    /** The user must see and approve the exact server, tool and arguments. */
    suspend fun requestApproval(active: McpAgentSession, proposal: McpAgentBridge.Decision.Proposed): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                if (session != active || activity.isFinishing || activity.isDestroyed) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("MCP-Werkzeug wirklich ausführen?")
                    .setMessage("Server: " + active.serverName +
                        "\nAdresse: " + active.endpoint +
                        "\nTool: " + proposal.tool.name +
                        "\n\nArgumente:\n" + proposal.args.toString() +
                        "\n\nDas Tool kann Dateien, Voice-Channels oder Serverdaten verändern.")
                    .setNegativeButton("Ablehnen") { _, _ ->
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .setPositiveButton("Einmal ausführen") { _, _ ->
                        if (continuation.isActive) continuation.resume(true)
                    }
                    .setOnCancelListener {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .create()
                continuation.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
        }

    suspend fun execute(
        active: McpAgentSession,
        proposal: McpAgentBridge.Decision.Proposed
    ): JSONObject = withContext(Dispatchers.IO) {
        check(session == active) { "MCP-Modus wurde gewechselt" }
        require(active.tools.any { it.name == proposal.tool.name }) { "Tool nicht freigegeben" }
        val profile = vault.get(active.profileId)
            ?: throw IllegalStateException("MCP-Profil wurde gelöscht")
        require(profile.endpoint == active.endpoint) {
            "MCP-Serveradresse hat sich geändert. Bitte neu verbinden."
        }
        val authorized = oauth.refreshIfNeeded(profile)
        // No automatic retry after tool side effects. The result may be
        // a failure even when HTTP returned 200.
        McpConnection.callTool(authorized, proposal.tool, proposal.args)
    }

    private fun error(title: String, problem: Throwable) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle(title)
            .setMessage(problem.message?.take(1500) ?: "Unbekannter Fehler")
            .setPositiveButton("OK", null).show()
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
