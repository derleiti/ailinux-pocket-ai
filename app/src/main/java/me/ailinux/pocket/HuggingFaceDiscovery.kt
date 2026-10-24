package me.ailinux.pocket

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public HF search through TriForce with official Hugging Face API fallback.
 * No credentials, prompts, files or chat history are sent.
 */
data class HuggingFaceRepository(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val suggestedFile: String? = null,
    val suggestedBytes: Long = -1L,
    val from: String = "Hugging Face",
    val format: String = "gguf"
)

object HuggingFaceDiscovery {
    private const val PROXY = "https://api.ailinux.me/v1/pocket/huggingface"
    private const val HF_API = "https://huggingface.co/api/models"
    private val validRepo = Regex("^[A-Za-z0-9_.-]{1,96}/[A-Za-z0-9_.-]{1,128}$")
    private val fallbackSmall = listOf(
        "bartowski/SmolLM2-360M-Instruct-GGUF",
        "bartowski/Qwen2.5-0.5B-Instruct-GGUF",
        "unsloth/Qwen3.5-0.8B-GGUF",
        "unsloth/gemma-3-1b-it-GGUF"
    )

    /** Strictly interpret a TriForce catalogue response. */
    fun parseProxyResponse(json: String): List<HuggingFaceRepository> {
        val data = JSONObject(json)
        require(data.optString("origin") == "huggingface.co/api/models") {
            "Falsche Katalogquelle"
        }
        val entries = data.getJSONArray("results")
        val models = mutableListOf<HuggingFaceRepository>()
        for (i in 0 until minOf(entries.length(), 30)) {
            val item = entries.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (!validRepo.matches(id) || ".." in id) continue
            val format = if (item.optString("format") == "LiteRT-LM") "litertlm" else "gguf"
            val recommendation = item.optJSONObject("recommended")
            val file = recommendation?.optString("filename")?.takeIf {
                ModelStore.isModelName(it) && '/' !in it && '\\' !in it
            }
            models += HuggingFaceRepository(
                id, item.optLong("downloads").coerceAtLeast(0),
                item.optLong("likes").coerceAtLeast(0),
                file, if (file == null) -1 else recommendation.optLong("bytes", -1),
                "TriForce · Hugging Face", format
            )
        }
        return models.distinctBy { it.id.lowercase() }
    }

    /** Official Hub fallback, public GGUF text-generation only. */
    fun parseHubSearch(json: String, limit: Int, format: String = "gguf"): List<HuggingFaceRepository> {
        require(format in setOf("gguf", "litertlm"))
        val arr = JSONArray(json)
        val result = mutableListOf<HuggingFaceRepository>()
        for (i in 0 until minOf(arr.length(), 100)) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (!validRepo.matches(id) || ".." in id ||
                item.optBoolean("private") ||
                (if (item.isNull("pipeline_tag")) "" else item.optString("pipeline_tag")) !in (
                    if (format == "litertlm")
                    setOf("", "text-generation", "image-text-to-text")
                    else setOf("", "text-generation"))
            ) continue
            val gated = item.opt("gated")
            if (gated != null && gated != JSONObject.NULL && gated != false) continue
            val tags = item.optJSONArray("tags") ?: JSONArray()
            if ((0 until tags.length()).none {
                    val tag = tags.optString(it)
                    if (format == "litertlm") tag.equals("litert-lm", true) ||
                        tag.equals("litertlm", true)
                    else tag.equals("gguf", true)
                }) continue
            result += HuggingFaceRepository(
                id, item.optLong("downloads").coerceAtLeast(0),
                item.optLong("likes").coerceAtLeast(0), format = format
            )
            if (result.size >= limit) break
        }
        return result.distinctBy { it.id.lowercase() }
    }

    /** Query is sent only after user explicitly starts search. */
    fun search(text: String, limit: Int = 16, format: String = "gguf"): List<HuggingFaceRepository> {
        require(format in setOf("gguf", "litertlm")) { "Unbekanntes Modellformat" }
        val q = text.trim().take(96)
        require(q.isNotBlank() && q.none { it.code < 32 }) { "Suchbegriff fehlt oder ist ungültig." }
        val safeLimit = limit.coerceIn(1, 25)
        val encoded = URLEncoder.encode(q, "UTF-8")
        return try {
            parseProxyResponse(fetch("$PROXY/search?q=$encoded&limit=$safeLimit&format=$format"))
        } catch (_: Exception) {
            parseHubSearch(
                fetch("$HF_API?search=$encoded&filter=" +
                    (if (format == "litertlm") "litert-lm" else "gguf") +
                    "&sort=downloads&direction=-1&limit=60"),
                safeLimit, format
            )
        }
    }

    /** Verify candidate file sizes live instead of guessing. */
    fun recommended(): List<HuggingFaceRepository> = try {
        val result = parseProxyResponse(fetch("$PROXY/recommended"))
        if (result.isEmpty()) throw IllegalStateException("Keine Empfehlungen gefunden")
        result
    } catch (_: Exception) {
        fallbackSmall.mapNotNull { id ->
            try {
                val verified = SourceCatalog.list(RepositoryRef(RepositoryHost.HUGGING_FACE, id))
                    .filter { it.bytes in 100_000_000..1_500_000_000 }
                    .filter { it.name.contains("Q4_K_M", ignoreCase = true) }
                    .minByOrNull { it.bytes } ?: return@mapNotNull null
                HuggingFaceRepository(id, 0, 0, verified.name, verified.bytes, "Hugging Face · direkt")
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.suggestedBytes }.ifEmpty {
            error("Weder TriForce noch Hugging Face erreichbar. Ein HTTPS-GGUF-Link funktioniert weiterhin.")
        }
    }

    private fun fetch(address: String): String {
        require(address.startsWith("$PROXY/") || address.startsWith(HF_API)) {
            "Katalogziel nicht erlaubt"
        }
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 12000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AILinux-Pocket-AI/0.2.0")
        }
        try {
            check(connection.responseCode == 200) {
                "Katalogabfrage fehlgeschlagen: HTTP " + connection.responseCode
            }
            val data = connection.inputStream.use { it.readNBytes(1_500_001) }
            require(data.size <= 1_500_000) { "Katalogantwort zu groß" }
            return data.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
