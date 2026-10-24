package me.ailinux.pocket

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteModel(
    val name: String,
    val url: String,
    val bytes: Long = -1,
    val sha256: String? = null,
    val source: String = ""
)

enum class RepositoryHost { GITHUB, HUGGING_FACE }
data class RepositoryRef(val host: RepositoryHost, val slug: String)

object SourceCatalog {
    fun parseRepository(input: String): RepositoryRef {
        val original = input.trim().trimEnd('/')
        val text = when {
            original.startsWith("github.com/") -> "https://" + original
            original.startsWith("huggingface.co/") -> "https://" + original
            else -> original
        }
        val host: RepositoryHost
        val slug: String
        if (text.startsWith("https://")) {
            val uri = Uri.parse(text)
            val segments = uri.pathSegments
            require(segments.size >= 2) { "Erwarte eine Repo-URL mit Besitzer und Name." }
            host = when (uri.host?.lowercase()) {
                "github.com", "www.github.com" -> RepositoryHost.GITHUB
                "huggingface.co", "www.huggingface.co" -> RepositoryHost.HUGGING_FACE
                else -> error("Unterstützt werden GitHub und Hugging Face.")
            }
            slug = segments[0] + "/" + segments[1]
        } else {
            host = RepositoryHost.GITHUB
            slug = text
        }
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(slug)) {
            "Repo im Format owner/repo angeben."
        }
        require(slug.split('/').none { it == "." || it == ".." }) { "Ungültiger Repo-Pfad." }
        return RepositoryRef(host, slug)
    }

    fun list(repo: RepositoryRef): List<RemoteModel> = when (repo.host) {
        RepositoryHost.GITHUB -> github(repo.slug)
        RepositoryHost.HUGGING_FACE -> huggingFace(repo.slug)
    }

    private fun github(slug: String): List<RemoteModel> {
        val base = "https://api.github.com/repos/$slug/releases"
        val releases = getJsonArray("$base?per_page=10").let { list ->
            (0 until list.length()).map { list.getJSONObject(it) }
        }
        val result = mutableListOf<RemoteModel>()
        releases.forEach { release ->
            val assets = release.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (!ModelStore.isModelName(name)) continue
                val address = asset.optString("browser_download_url")
                if (!address.startsWith("https://")) continue
                val digest = asset.optString("digest").removePrefix("sha256:")
                    .takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                result += RemoteModel(name, address, asset.optLong("size", -1), digest, "GitHub")
            }
        }
        return result.distinctBy { it.url }.sortedBy { it.bytes }
    }

    private fun huggingFace(slug: String): List<RemoteModel> {
        val data = getJson("https://huggingface.co/api/models/$slug?blobs=true")
        val files = data.optJSONArray("siblings") ?: JSONArray()
        val result = mutableListOf<RemoteModel>()
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val path = file.optString("rfilename")
            if (!ModelStore.isModelName(path)) continue
            val lfs = file.optJSONObject("lfs")
            val hash = lfs?.optString("sha256")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            val bytes = lfs?.optLong("size", -1) ?: file.optLong("size", -1)
            result += RemoteModel(
                ModelStore.sanitize(path.substringAfterLast('/')),
                "https://huggingface.co/$slug/resolve/main/" + Uri.encode(path, "/") + "?download=true",
                bytes, hash, "Hugging Face"
            )
        }
        return result.sortedBy { it.bytes }
    }

    fun directUrl(input: String): RemoteModel {
        var url = input.trim()
        val parsed = Uri.parse(url)
        require(parsed.scheme == "https") { "Nur HTTPS-Links erlaubt." }
        val host = parsed.host?.lowercase() ?: error("Ungültige URL.")
        if (host == "github.com" && parsed.pathSegments.size >= 5 &&
            parsed.pathSegments[2] == "blob"
        ) {
            url = "https://raw.githubusercontent.com/" +
                parsed.pathSegments.take(2).joinToString("/") + "/" +
                parsed.pathSegments.drop(3).joinToString("/")
        } else if (host == "huggingface.co" && parsed.pathSegments.size >= 5 &&
            parsed.pathSegments[2] == "blob"
        ) {
            url = "https://huggingface.co/" +
                parsed.pathSegments.take(2).joinToString("/") + "/resolve/" +
                parsed.pathSegments.drop(3).joinToString("/")
        }
        val file = Uri.parse(url).lastPathSegment ?: error("Kein Dateiname in der URL.")
        require(ModelStore.isModelName(file)) { "Bitte einen direkten GGUF- oder .litertlm-Downloadlink angeben." }
        return RemoteModel(ModelStore.sanitize(file), url)
    }

    private fun getJson(address: String): JSONObject = JSONObject(fetch(address))
    private fun getJsonArray(address: String): JSONArray = JSONArray(fetch(address))

    private fun fetch(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "AILinux-Pocket-AI/0.1")
            setRequestProperty("Accept", "application/vnd.github+json, application/json")
        }
        try {
            check(connection.responseCode == 200) {
                "Repository-Abfrage fehlgeschlagen: HTTP " + connection.responseCode
            }
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            require(content.length < 10_000_000) { "Repository-Antwort zu groß." }
            return content
        } finally {
            connection.disconnect()
        }
    }
}
