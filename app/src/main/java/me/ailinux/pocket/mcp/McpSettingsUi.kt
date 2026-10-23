package me.ailinux.pocket.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

/**
 * MCP settings and explicit manual tools. No automatic tool execution from
 * model-generated text: calls can have real-world side effects.
 */
class McpSettingsUi(
    private val activity: AppCompatActivity,
    private val rows: LinearLayout,
    private val add: MaterialButton,
    private val onChatResult: (String) -> Unit = {}
) {
    private val vault = McpVault(activity.applicationContext)
    private val oauth = McpOAuth(vault)
    private var loopback: McpLoopback? = null
    private var working = false

    init {
        add.setOnClickListener { addServerChooser() }
        render()
    }

    private fun addServerChooser() {
        AlertDialog.Builder(activity)
            .setTitle("MCP-Server hinzufügen")
            .setItems(arrayOf(
                "AILinux TriForce · api.ailinux.me/v1/mcp",
                "Anderen MCP-Server konfigurieren"
            )) { _, index ->
                if (index == 0) {
                    // An endpoint preset, never automatic login or privileged tools.
                    edit(McpProfile(
                        title = "AILinux TriForce",
                        endpoint = "https://api.ailinux.me/v1/mcp",
                        transport = McpTransport.STREAMABLE_HTTP,
                        auth = McpAuth.NONE
                    ), adding = true)
                } else edit(null)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun label(text: String, size: Float = 13f): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setPadding(dp(2), dp(6), dp(2), dp(7))
        }

    private fun button(text: String, onClick: () -> Unit) =
        MaterialButton(activity).apply {
            isAllCaps = false
            this.text = text
            textSize = 11f
            minHeight = dp(40)
            minimumHeight = dp(40)
            setOnClickListener { onClick() }
        }

    private fun horizontal(vararg buttons: MaterialButton) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { b ->
                addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginEnd = dp(5)
                })
            }
        }

    fun render() {
        rows.removeAllViews()
        val servers = try {
            vault.list()
        } catch (e: Exception) {
            rows.addView(label("Verschlüsselter MCP-Tresor nicht lesbar: " +
                (e.message ?: "Keystore-Fehler"), 12f))
            return
        }
        if (servers.isEmpty()) {
            rows.addView(label("Noch kein MCP-Server eingerichtet. " +
                "Füge eine HTTPS-Adresse hinzu und teste anschließend die Verbindung."))
        }
        servers.forEach { p ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(13))
                setBackgroundResource(me.ailinux.pocket.R.drawable.bg_panel)
            }
            val box = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            rows.addView(card, box)
            card.addView(label(p.title, 16f))
            card.addView(label(p.endpoint + "\n" + p.auth.title +
                " · " + p.transport.title, 11f))
            if (p.auth == McpAuth.OAUTH) {
                val state = when {
                    p.oauthAccessToken.isBlank() -> "OAuth: nicht angemeldet"
                    p.oauthExpiresAt < System.currentTimeMillis() ->
                        "OAuth: Token abgelaufen (Refresh beim Test)"
                    else -> "OAuth: Token gespeichert"
                }
                card.addView(label(state, 12f))
            }
            card.addView(horizontal(
                button("Bearbeiten") { edit(p) },
                button("Testen") { inspect(p, false) },
                button("Tools") { inspect(p, true) }
            ))
            card.addView(horizontal(
                if (p.auth == McpAuth.OAUTH) button("OAuth Login") { authorize(p) }
                else button("Details") { showInfo(p) },
                if (p.auth == McpAuth.OAUTH) button("Trennen") { disconnect(p) }
                else button("Redirects") { showInfo(p) },
                button("Löschen") { delete(p) }
            ))
        }
    }

    private fun showInfo(profile: McpProfile) {
        val redirect = if (profile.oauthRedirect == McpRedirect.LOOPBACK)
            McpSecurity.LOOPBACK_REDIRECT else McpSecurity.APP_REDIRECT
        AlertDialog.Builder(activity).setTitle("MCP-Verbindung: " + profile.title)
            .setMessage("Endpoint:\n" + profile.endpoint +
                "\n\nAuthentifizierung: " + profile.auth.title +
                "\n\nOAuth Loopback-Redirect:\n" + McpSecurity.LOOPBACK_REDIRECT +
                "\n\nAlternativer App-Redirect:\n" + McpSecurity.APP_REDIRECT +
                "\n\nGewählter Redirect:\n" + redirect +
                "\n\nNur HTTPS-Server werden akzeptiert, außer HTTP auf 127.0.0.1. " +
                "Tokens bleiben im Android Keystore verschlüsselt.")
            .setNeutralButton("Redirect kopieren") { _, _ -> copy(redirect) }
            .setPositiveButton("OK", null).show()
    }

    private fun edit(existing: McpProfile?, adding: Boolean = false) {
        val base = existing ?: McpProfile()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
        }
        fun field(caption: String, value: String = "", secret: Boolean = false): EditText {
            val group = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(caption))
            }
            val input = EditText(activity).apply {
                setSingleLine(true)
                textSize = 14f
                if (secret) inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(if (secret) "" else value)
                if (secret && value.isNotBlank()) hint = "Vorhanden – leer lassen zum Behalten"
            }
            group.addView(input)
            form.addView(group)
            return input
        }
        val name = field("Servername", base.title)
        val endpoint = field("MCP-Endpoint (HTTPS)", base.endpoint)
        form.addView(label("MCP-Transport"))
        val transports = McpTransport.entries
        val transport = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                transports.map { it.title })
            setSelection(transports.indexOf(base.transport))
        }
        form.addView(transport)
        form.addView(label("Authentifizierung"))
        val modes = McpAuth.entries
        val mode = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                modes.map { it.title })
            setSelection(modes.indexOf(base.auth))
        }
        form.addView(mode)
        val bearer = field("Bearer-Token", base.bearer, true)
        val keyHeader = field("API-Key Header (z.B. X-API-Key)", base.apiKeyName)
        val apiKey = field("API-Key", base.apiKey, true)
        val username = field("Benutzername", base.username)
        val password = field("Passwort", base.password, true)
        val clientId = field("OAuth Client-ID (leer = Auto-Registrierung)", base.oauthClientId)
        val issuer = field("OAuth Issuer (optional; auto via MCP-Metadaten)", base.oauthIssuer)
        val scopes = field("OAuth Scopes (optional, getrennt durch Leerzeichen)", base.oauthScopes)
        val redirectGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("OAuth Redirect-Verfahren"))
        }
        val redirects = McpRedirect.entries
        val redirect = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                redirects.map { it.title })
            setSelection(redirects.indexOf(base.oauthRedirect))
        }
        redirectGroup.addView(redirect)
        val redirectHint = label(
            "Loopback: " + McpSecurity.LOOPBACK_REDIRECT +
            "\nApp-Callback: " + McpSecurity.APP_REDIRECT, 11f
        )
        redirectGroup.addView(redirectHint)
        form.addView(redirectGroup)
        fun visibility() {
            val selected = modes[mode.selectedItemPosition]
            (bearer.parent as View).visibility = if (selected == McpAuth.BEARER) View.VISIBLE else View.GONE
            (keyHeader.parent as View).visibility = if (selected == McpAuth.API_KEY) View.VISIBLE else View.GONE
            (apiKey.parent as View).visibility = if (selected == McpAuth.API_KEY) View.VISIBLE else View.GONE
            (username.parent as View).visibility = if (selected == McpAuth.BASIC) View.VISIBLE else View.GONE
            (password.parent as View).visibility = if (selected == McpAuth.BASIC) View.VISIBLE else View.GONE
            (clientId.parent as View).visibility = if (selected == McpAuth.OAUTH) View.VISIBLE else View.GONE
            (issuer.parent as View).visibility = if (selected == McpAuth.OAUTH) View.VISIBLE else View.GONE
            (scopes.parent as View).visibility = if (selected == McpAuth.OAUTH) View.VISIBLE else View.GONE
            redirectGroup.visibility = if (selected == McpAuth.OAUTH) View.VISIBLE else View.GONE
        }
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long
            ) { visibility() }
        }
        visibility()
        val content = ScrollView(activity).apply { addView(form) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null || adding) "MCP-Server hinzufügen" else "MCP-Server bearbeiten")
            .setView(content)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    fun secret(field: EditText, fallback: String): String =
                        field.text.toString().ifBlank { fallback }
                    val selected = modes[mode.selectedItemPosition]
                    val edited = base.copy(
                        title = name.text.toString(),
                        endpoint = endpoint.text.toString(),
                        transport = transports[transport.selectedItemPosition],
                        auth = selected,
                        bearer = secret(bearer, base.bearer),
                        apiKeyName = keyHeader.text.toString(),
                        apiKey = secret(apiKey, base.apiKey),
                        username = username.text.toString(),
                        password = secret(password, base.password),
                        oauthClientId = clientId.text.toString(),
                        oauthIssuer = issuer.text.toString().trim(),
                        oauthScopes = McpSecurity.scope(scopes.text.toString()),
                        oauthRedirect = redirects[redirect.selectedItemPosition]
                    )
                    val validated = McpSecurity.validate(edited)
                    val changed = McpSecurity.credentialChanged(base, validated)
                    val cleared = if (changed) validated.copy(
                        oauthTokenEndpoint = "", oauthAuthorizationEndpoint = "",
                        oauthAccessToken = "", oauthRefreshToken = "",
                        oauthExpiresAt = 0L, oauthRedirectRegistered = ""
                    ) else validated
                    vault.upsert(cleared.copy(
                        bearer = if (selected == McpAuth.BEARER) cleared.bearer else "",
                        apiKey = if (selected == McpAuth.API_KEY) cleared.apiKey else "",
                        username = if (selected == McpAuth.BASIC) cleared.username else "",
                        password = if (selected == McpAuth.BASIC) cleared.password else "",
                        oauthAccessToken = if (selected == McpAuth.OAUTH) cleared.oauthAccessToken else "",
                        oauthRefreshToken = if (selected == McpAuth.OAUTH) cleared.oauthRefreshToken else ""
                    ))
                    dialog.dismiss()
                    render()
                } catch (e: Exception) {
                    showError("MCP-Einstellungen", e)
                }
            }
        }
        dialog.show()
    }

    private fun delete(p: McpProfile) {
        AlertDialog.Builder(activity).setTitle("MCP-Server löschen?")
            .setMessage("Server „" + p.title + "“ inklusive gespeicherter Zugangsdaten löschen?")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Löschen") { _, _ ->
                try {
                    vault.delete(p.id)
                    render()
                } catch (e: Exception) { showError("Löschen fehlgeschlagen", e) }
            }.show()
    }

    private fun disconnect(p: McpProfile) {
        try {
            vault.upsert(p.copy(
                oauthAccessToken = "", oauthRefreshToken = "", oauthExpiresAt = 0L
            ))
            render()
        } catch (e: Exception) { showError("Trennen fehlgeschlagen", e) }
    }

    private fun inspect(p: McpProfile, showTools: Boolean) {
        execute("MCP-Verbindung") {
            val fresh = oauth.refreshIfNeeded(
                vault.get(p.id) ?: throw IllegalStateException("Server nicht gefunden")
            )
            val info = McpConnection.inspect(fresh)
            activity.runOnUiThread {
                render()
                if (showTools) toolPicker(fresh, info)
                else AlertDialog.Builder(activity)
                    .setTitle("MCP verbunden: " + info.title)
                    .setMessage("Protokoll: " + info.protocol +
                        "\nTools gefunden: " + info.tools.size +
                        "\n\n" + info.tools.take(12).joinToString("\n") { "• " + it.name })
                    .setPositiveButton("Tools") { _, _ -> toolPicker(fresh, info) }
                    .setNegativeButton("Schließen", null).show()
            }
        }
    }

    /** Short MCP shortcut used from CHAT, without adding model selectors there. */
    fun showToolsChooser() {
        val profiles = runCatching { vault.list() }.getOrElse {
            showError("MCP-Tresor", Exception(it)); return
        }
        if (profiles.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("MCP-Tools")
                .setMessage("Noch kein MCP-Server vorhanden. Unter SETUP zuerst einen Server hinzufügen.")
                .setPositiveButton("Zu Setup") { _, _ ->
                    activity.findViewById<View>(me.ailinux.pocket.R.id.navSettings).performClick()
                }.setNegativeButton("Schließen", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("MCP-Tools · Server wählen")
            .setItems(profiles.map { it.title }.toTypedArray()) { _, index ->
                inspect(profiles[index], true)
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun toolPicker(p: McpProfile, info: McpServerInfo) {
        if (info.tools.isEmpty()) {
            AlertDialog.Builder(activity).setMessage("MCP-Verbindung steht, aber keine Tools gemeldet.")
                .setPositiveButton("OK", null).show()
            return
        }
        val items = info.tools.map { it.name + "\n" + it.description.take(90) }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("MCP-Tools: " + p.title)
            .setItems(items) { _, index -> toolArguments(p, info.tools[index]) }
            .setNegativeButton("Schließen", null).show()
    }

    private fun toolArguments(profile: McpProfile, tool: McpTool) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        form.addView(label(tool.description.ifBlank { tool.name }, 13f))
        form.addView(label("Parameter-Schema:", 12f))
        form.addView(label(tool.schema.toString(2).take(2500), 11f))
        form.addView(label("JSON-Argumente (z.B. {})"))
        val args = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 9
            setText("{}")
        }
        form.addView(args)
        val scroller = ScrollView(activity).apply { addView(form) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Tool: " + tool.name)
            .setView(scroller)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Ausführen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val arguments = try {
                    JSONObject(args.text.toString())
                } catch (e: Exception) {
                    showError("JSON-Argumente ungültig", e)
                    return@setOnClickListener
                }
                dialog.dismiss()
                AlertDialog.Builder(activity).setTitle("MCP-Tool wirklich ausführen?")
                    .setMessage("„" + tool.name + "“ auf „" + profile.title +
                        "“ kann Dateien ändern oder andere Aktionen ausführen. " +
                        "Nur starten, wenn du den Server und die Parameter kennst.")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Tool starten") { _, _ ->
                        execute("MCP-Tool") {
                            val p = oauth.refreshIfNeeded(
                                vault.get(profile.id) ?: throw IllegalStateException("Server entfernt")
                            )
                            val response = McpConnection.callTool(p, tool, arguments)
                            activity.runOnUiThread { showResult(tool.name, response.toString(2)) }
                        }
                    }.show()
            }
        }
        dialog.show()
    }

    private fun showResult(title: String, content: String) {
        AlertDialog.Builder(activity).setTitle("Tool-Ergebnis: " + title)
            .setMessage(content.take(7500))
            .setNeutralButton("Kopieren") { _, _ -> copy(content.take(20_000)) }
            .setNegativeButton("Schließen", null)
            .setPositiveButton("Im Chat anzeigen") { _, _ ->
                // External tool output is untrusted; visible only after this tap.
                onChatResult("MCP · " + title + "\n" + content.take(20_000))
            }.show()
    }

    private fun authorize(p: McpProfile) {
        if (working) return
        loopback?.stop()
        loopback = null
        if (p.oauthRedirect == McpRedirect.LOOPBACK) {
            try {
                loopback = McpLoopback { callback ->
                    activity.runOnUiThread {
                        onOAuthCallback(URI(callback))
                    }
                }.also { it.start() }
            } catch (e: Exception) {
                showError("Lokaler OAuth-Redirect-Port " +
                    McpSecurity.LOOPBACK_PORT + " nicht verfügbar", e)
                return
            }
        }
        execute("OAuth-Vorbereitung", onError = { loopback?.stop() }) {
            val actual = vault.get(p.id) ?: throw IllegalStateException("Server entfernt")
            val request = oauth.start(actual)
            activity.runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.browserUrl))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    activity.startActivity(intent)
                    if (p.oauthRedirect == McpRedirect.LOOPBACK) {
                        showResult("OAuth-Weiterleitung",
                            "Nach der Anmeldung leitet der Browser zu " +
                            McpSecurity.LOOPBACK_REDIRECT + " zurück. " +
                            "Anschließend wieder Pocket AI öffnen.")
                    }
                } catch (e: Exception) {
                    loopback?.stop()
                    showError("OAuth-Browser konnte nicht geöffnet werden", e)
                }
            }
        }
    }

    fun onOAuthCallback(uri: URI) {
        execute("OAuth-Abschluss") {
            val updated = oauth.finish(uri)
            activity.runOnUiThread {
                loopback?.stop()
                loopback = null
                render()
                AlertDialog.Builder(activity).setTitle("MCP OAuth verbunden")
                    .setMessage("OAuth-Anmeldung für „" + updated.title +
                        "“ abgeschlossen. Mit TESTEN wird die MCP-Toolliste geprüft.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun execute(
        title: String,
        onError: (() -> Unit)? = null,
        block: () -> Unit
    ) {
        if (working) {
            showError(title, IllegalStateException("Ein MCP-Vorgang läuft bereits."))
            return
        }
        working = true
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                onError?.invoke()
                if (!activity.isFinishing && !activity.isDestroyed) showError(title, e)
            } finally {
                working = false
            }
        }
    }

    private fun copy(value: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MCP", value))
    }

    private fun showError(title: String, error: Exception) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle(title)
            .setMessage((error.message ?: "Unbekannter Fehler").take(1500))
            .setPositiveButton("OK", null).show()
    }

    fun close() {
        loopback?.stop()
        loopback = null
    }
}
