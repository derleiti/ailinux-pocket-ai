package me.ailinux.pocket.mcp

import android.graphics.Typeface
import android.widget.CheckBox
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
    val thinkingMode: String = "low",
    val progressiveSources: List<McpProgressiveBridge.Source> = emptyList(),
    val progressiveCreatedAtMs: Long = System.currentTimeMillis(),
    val serverInstructions: String = "",
    /** Explicit one-session opt-in; never blindly trust remote instructions. */
    val includeServerGuidance: Boolean = false
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

    /**
     * On Android AppCompat AlertDialog, setMessage() and setItems() (or
     * setMultiChoiceItems()) compete for the same content panel: if a message
     * exists, the list can silently disappear. Put the short explanation
     * in the custom header, leaving the dialog content panel for its list.
     */
    private fun listDialogHeader(title: String, explanation: String): LinearLayout {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(17), dp(22), dp(7))
            addView(TextView(activity).apply {
                text = title
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = explanation
                textSize = 13f
                setPadding(0, dp(7), 0, dp(4))
            })
        }
    }

    /** Called after a native Jinja binding error; a stale selection must not
     * be represented as having active tool access in the Android chat UI. */
    fun clearSessionSilently() { session = null }

    fun showMenu(
        openManualTools: () -> Unit,
        openHealthCheck: () -> Unit,
        openQuickHelp: () -> Unit
    ) {
        val options = if (session != null)
            arrayOf("KI + MCP manuell neu verbinden",
                "AUTO-BRIDGE · Tools automatisch finden (RC11)",
                "MCP-Tools manuell", "MCP Health-Check (echte Serverprüfung)",
                "MCP Schnellbefehle (ohne Spark)", "KI + MCP ausschalten")
        else arrayOf("KI + MCP manuell aktivieren",
            "AUTO-BRIDGE · Tools automatisch finden (RC11)",
            "MCP-Tools manuell", "MCP Health-Check (echte Serverprüfung)",
            "MCP Schnellbefehle (ohne Spark)")
        val name = session?.let {
            "\nAktiv: " + it.serverName + " · " +
                if (it.progressiveSources.isNotEmpty())
                    "Auto-Discovery über " + it.progressiveSources.size + " Server"
                else it.tools.size.toString() + " ausgewählte Tools"
        } ?: "\nDein Modell arbeitet aktuell ohne automatisches MCP-Planning."
        AlertDialog.Builder(activity)
            .setCustomTitle(listDialogHeader(
                "Pocket AI · MCP-Bridge",
                "Das Modell darf nur nach deiner Freigabe echte MCP-Tools aufrufen." + name
            ))
            // Never combine setMessage with setItems: the latter would be
            // hidden on Android (confirmed by Pixel RC11 screenshot).
            .setItems(options) { _, index ->
                when (index) {
                    0 -> chooseServer()
                    1 -> chooseAutoServers()
                    2 -> openManualTools()
                    3 -> openHealthCheck()
                    4 -> openQuickHelp()
                    5 -> {
                        session = null
                        onActiveChanged(null)
                        toast("KI + MCP ausgeschaltet")
                    }
                }
            }
            .setNegativeButton("Schließen", null).show()
    }

    /**
     * Explicit opt-in to one or several credential-scoped tool catalogues.
     * No intermediary cloud MCP server is needed; the phone uses the saved
     * OAuth credentials for each original server.
     */
    private fun chooseAutoServers() {
        loadProfilesWithoutBlockingUi { profiles -> showAutoServerChooser(profiles) }
    }

    /** Android Keystore decrypt must never happen on the UI thread. */
    private fun loadProfilesWithoutBlockingUi(
        show: (List<McpProfile>) -> Unit
    ) {
        if (selecting) { toast("MCP-Verbindung läuft bereits"); return }
        selecting = true
        activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { vault.list() }
            }
            selecting = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val profiles = found.getOrElse {
                error("MCP-Tresor", it)
                return@launch
            }
            show(profiles)
        }
    }

    private fun showAutoServerChooser(profiles: List<McpProfile>) {
        if (profiles.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Kein MCP-Profil")
                .setMessage("Unter SETUP zuerst TriForce oder Discord MCP hinzufügen und anmelden.")
                .setPositiveButton("OK", null).show()
            return
        }
        val checked = BooleanArray(profiles.size) { index ->
            val p = profiles[index]
            p.auth != McpAuth.OAUTH ||
                p.oauthAccessToken.isNotBlank() || p.oauthRefreshToken.isNotBlank()
        }
        val allowServerGuidance = CheckBox(activity).apply {
            text = "Optionale MCP-Serverhinweise für Spark (nicht vertrauenswürdig)"
            textSize = 12f
            isChecked = false
            contentDescription = "Server-Instructions nach Vorschau optional im " +
                "Modell-Systemprompt als untrusted data verwenden"
        }
        AlertDialog.Builder(activity)
            .setCustomTitle(listDialogHeader(
                "MCP AUTO-BRIDGE · Server",
                "Bis zu 4 Server wählen. Spark bekommt 3 lokale Meta-Tools; " +
                    "jede externe Aktion braucht deine Freigabe. " +
                    "OAuth-Tokens bleiben im Android-Tresor."
            ).apply {
                addView(allowServerGuidance)
            })
            // setMessage + setMultiChoiceItems hides the server choices on
            // many Android/AppCompat versions; customTitle preserves them.
            .setMultiChoiceItems(
                profiles.map { it.title + " · " + it.endpoint }.toTypedArray(),
                checked
            ) { _, which, value -> checked[which] = value }
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Online-Werkzeuge verbinden") { _, _ ->
                val chosen = profiles.filterIndexed { index, _ -> checked[index] }
                if (chosen.isEmpty() || chosen.size > McpProgressiveBridge.MAX_SERVERS) {
                    error("Auto-Bridge", IllegalArgumentException(
                        "Bitte mindestens einen und höchstens vier Server auswählen."))
                    return@setPositiveButton
                }
                selecting = true
                activity.lifecycleScope.launch {
                    try {
                        val sources = withContext(Dispatchers.IO) {
                            chosen.mapIndexed { index, old ->
                                val fresh = vault.get(old.id)
                                    ?: error("MCP-Profil wurde entfernt")
                                require(fresh.endpoint == old.endpoint &&
                                    fresh.auth == old.auth &&
                                    fresh.oauthClientId == old.oauthClientId) {
                                    "MCP-Profil während der Serverauswahl geändert."
                                }
                                require(fresh.auth != McpAuth.OAUTH ||
                                    fresh.oauthAccessToken.isNotBlank() ||
                                    fresh.oauthRefreshToken.isNotBlank()) {
                                    "OAuth-Anmeldung fehlt für " + fresh.title
                                }
                                val authorized = oauth.refreshIfNeeded(fresh)
                                val live = McpConnection.inspect(authorized)
                                McpProgressiveBridge.Source(
                                    id = "s" + (index + 1),
                                    profileId = fresh.id,
                                    endpoint = fresh.endpoint,
                                    title = fresh.title,
                                    tools = live.tools.distinctBy { it.name }.take(McpProgressiveBridge.MAX_CATALOG_TOOLS_PER_SERVER),
                                    auth = fresh.auth,
                                    publicOauthClientId = fresh.oauthClientId,
                                    instructions = live.instructions
                                )
                            }
                        }
                        // A 1,000+ tool authenticated catalogue can take time
                        // to index; never block the Android UI thread.
                        val runtime = withContext(Dispatchers.Default) {
                            McpProgressiveBridge.Run(sources)
                        }
                        require(runtime.totalTools > 0) {
                            "Keine geeigneten, aktuell zugelassenen MCP-Werkzeuge gefunden."
                        }
                        val meta = McpProgressiveBridge.functions()
                        McpAgentBridge.nativeToolsJson(meta)
                        session = McpAgentSession(
                            profileId = sources.first().profileId,
                            endpoint = sources.first().endpoint,
                            serverName = "Auto-Bridge · " + sources.size + " Server",
                            tools = meta,
                            thinkingMode = "low",
                            progressiveSources = sources,
                            includeServerGuidance = allowServerGuidance.isChecked
                        )
                        onActiveChanged(session)
                        toast("AUTO-Bridge: " + runtime.totalTools +
                            " Tools entdeckt; 3 Meta-Werkzeuge an Spark gebunden.")
                    } catch (problem: Exception) {
                        error("Auto-Bridge konnte Server nicht prüfen", problem)
                    } finally {
                        selecting = false
                    }
                }
            }
            .show()
    }

    private fun chooseServer() {
        loadProfilesWithoutBlockingUi { profiles -> showManualServerChooser(profiles) }
    }

    private fun showManualServerChooser(profiles: List<McpProfile>) {
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
            .distinctBy { it.name }.take(McpProgressiveBridge.MAX_CATALOG_TOOLS_PER_SERVER)
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
        val safeRemoteHintAvailable =
            McpServerGuidance.promptExcerpt(info.instructions).isNotBlank()
        val allowServerGuidance = CheckBox(activity).apply {
            text = when {
                info.instructions.isBlank() ->
                    "Server liefert keine MCP instructions (Pocket-AI-Regeln gelten weiter)"
                !safeRemoteHintAvailable ->
                    "Serverhinweise enthalten riskante Anweisungen (nicht übernehmbar)"
                else ->
                    "Serverhinweise optional in Spark übernehmen (nur Referenzdaten)"
            }
            textSize = 12f
            isChecked = false
            isEnabled = safeRemoteHintAvailable
        }
        root.addView(allowServerGuidance)
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
                        profile.id, profile.endpoint, profile.title, tools, mode,
                        serverInstructions = info.instructions,
                        includeServerGuidance = allowServerGuidance.isChecked
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

    /**
     * Consent shows the actual tool, endpoint and exact arguments, not the
     * synthetic mcp_call_tool name. Never remember approvals.
     */
    suspend fun requestAutoApproval(
        active: McpAgentSession, plan: McpProgressiveBridge.RemoteCall
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val source = active.progressiveSources.singleOrNull {
                it.id == plan.source.id && it.profileId == plan.source.profileId &&
                    it.endpoint == plan.source.endpoint
            }
            if (session != active || source == null ||
                activity.isFinishing || activity.isDestroyed) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Echten MCP-Aufruf einmal freigeben?")
                .setMessage(
                    "Server: " + source.title +
                    "\nEndpunkt: " + source.endpoint +
                    "\nWerkzeug: " + plan.tool.name +
                    "\nParameter: " + plan.arguments.toString(2).take(3200) +
                    "\n\nDieses Werkzeug kann Serverdaten ändern. " +
                    "Pocket AI führt höchstens einen externen Aufruf aus " +
                    "und prüft vorher noch einmal das Live-Schema."
                )
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

    /**
     * Single dispatch after consent: live tools/list and auth are rechecked.
     * No automatic retries on failure or ambiguous network results.
     */
    suspend fun executeAuto(
        active: McpAgentSession, plan: McpProgressiveBridge.RemoteCall
    ): JSONObject = withContext(Dispatchers.IO) {
        check(session == active) { "Auto-Bridge nicht mehr aktiv" }
        val source = active.progressiveSources.singleOrNull {
            it.id == plan.source.id && it.profileId == plan.source.profileId &&
                it.endpoint == plan.source.endpoint
        } ?: throw IllegalStateException("Unbekannter Auto-Bridge-Server")
        val fresh = vault.get(source.profileId)
            ?: error("MCP-Profil wurde entfernt")
        check(fresh.endpoint == source.endpoint &&
            fresh.auth == source.auth &&
            fresh.oauthClientId == source.publicOauthClientId) {
            "MCP-Server/Anmeldung hat sich nach Freigabe geändert"
        }
        val authorized = oauth.refreshIfNeeded(fresh)
        val current = McpConnection.inspect(authorized).tools
            .singleOrNull { it.name == plan.tool.name }
            ?: error("MCP-Werkzeug existiert nicht mehr")
        check(current.description == plan.tool.description &&
            McpToolSchema.equivalent(current.schema, plan.tool.schema) &&
            McpToolSchema.equivalent(current.annotations, plan.tool.annotations)) {
            "MCP-Werkzeugdefinition oder Risikohinweis geändert. Auto-Bridge bitte neu verbinden."
        }
        // App-inferred tool selection must stay strictly read-only even if
        // server annotations change between user approval and actual dispatch.
        // Annotations are untrusted hints, so explicit consent is still required.
        if (plan.hostFallback) {
            check(current.declaresReadOnly &&
                current.name in setOf("crawl", "status", "discord_connection_status",
                    "current_time", "models", "device_info")) {
                "Host-Fallback darf kein schreibendes oder unbekanntes Werkzeug aufrufen."
            }
        }
        McpToolSchema.validate(current.schema, plan.arguments)?.let {
            throw IllegalArgumentException("Aktuelle MCP-Argumentprüfung: " + it)
        }
        // No second try, downgrade or automatic fallback after dispatch.
        McpConnection.callTool(authorized, current, plan.arguments)
    }

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

    /**
     * Direct quick actions have NO model agent session, so they cannot reuse
     * a fake McpAgentSession. Confirm the exact live-discovered tool and args
     * on their own. Read-only is a local allowlist claim, not a server grant.
     */
    suspend fun requestQuickReadApproval(
        profile: McpProfile,
        proposal: McpAgentBridge.Decision.Proposed
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            if (activity.isFinishing || activity.isDestroyed) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("MCP-Leseabfrage einmal erlauben?")
                .setMessage(
                    "Profil: " + profile.title +
                    "\nEndpunkt: " + profile.endpoint +
                    "\nWerkzeug: " + proposal.tool.name +
                    "\nParameter: " + proposal.args.toString() +
                    "\n\nPocket AI hält dieses Werkzeug lokal für lesend. " +
                    "Welche Daten wirklich zurückgegeben werden, bestimmt der " +
                    "Server und dein Konto. Ohne diese Freigabe erfolgt kein Aufruf."
                )
                .setNegativeButton("Ablehnen") { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setPositiveButton("Einmal lesen") { _, _ ->
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
        // Re-discover live tools/list AFTER consent. A tool may disappear,
        // become restricted or change its schema after initial selection.
        // Do not send a possibly mutating tools/call with a stale signature.
        val advertised = McpConnection.inspect(authorized).tools
        val current = advertised.singleOrNull { it.name == proposal.tool.name }
            ?: throw IllegalStateException(
                "MCP-Tool wurde seit der Auswahl entfernt: " + proposal.tool.name)
        check(current.description == proposal.tool.description &&
            McpToolSchema.equivalent(current.schema, proposal.tool.schema)) {
            "MCP-Tooldefinition hat sich geändert. Bitte neu verbinden und bestätigen."
        }
        // Revalidate the exact approved arguments against the CURRENT schema.
        val proposedAgain = McpAgentBridge.parse(JSONObject()
            .put("mcp_tool", current.name)
            .put("arguments", proposal.args).toString(), listOf(current))
        require(proposedAgain is McpAgentBridge.Decision.Proposed) {
            "MCP-Argumente entsprechen nicht mehr dem aktuellen Tool-Schema."
        }
        // Exactly one dispatch. An HTTP error may occur after side effects;
        // NEVER retry through another transport automatically.
        McpConnection.callTool(authorized, current, proposal.args)
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
