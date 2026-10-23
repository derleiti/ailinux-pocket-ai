package me.ailinux.pocket

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class TransferProgress(val done: Long, val total: Long) {
    val percent: Int get() = if (total > 0) ((done * 100) / total).coerceIn(0, 100).toInt() else -1
}

class ModelStore(private val context: Context) {
    val dir = File(context.filesDir, "models").also { it.mkdirs() }

    fun list(): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".gguf", true) && it.length() > 32 }
        ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        ?: emptyList()

    fun partials(): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".gguf.part", true) }
        ?.sortedBy { it.name } ?: emptyList()

    fun sourceName(uri: Uri): String {
        var displayName: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) displayName = it.getString(0) }
        } catch (_: Exception) { }
        return sanitize(displayName ?: uri.lastPathSegment ?: "import.gguf")
    }

    fun import(uri: Uri, cancelled: AtomicBoolean, progress: (TransferProgress) -> Unit): File {
        val name = sourceName(uri)
        require(name.endsWith(".gguf", true)) { "Nur .gguf-Dateien werden unterstützt." }
        val target = uniqueTarget(name)
        val temp = File.createTempFile("pocket-import-", ".part", dir)
        try {
            val length = try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
            } catch (_: Exception) { -1L }
            if (length > 0) checkStorage(length)
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Datei konnte nicht geöffnet werden.")
            var written = 0L
            input.use {
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled.get()) throw TransferCancelled()
                        val n = it.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (written % (1024 * 1024) < buffer.size) progress(TransferProgress(written, length))
                    }
                    output.fd.sync()
                }
            }
            validate(temp)
            check(temp.renameTo(target)) { "Modell konnte nicht gespeichert werden." }
            return target
        } finally {
            temp.delete()
        }
    }

    fun download(
        address: String, displayName: String, sha256: String?,
        cancelled: AtomicBoolean, progress: (TransferProgress) -> Unit
    ): File {
        val url = URL(address)
        require(url.protocol == "https") { "Nur sichere HTTPS-Downloads sind erlaubt." }
        val name = sanitize(displayName)
        require(name.endsWith(".gguf", true)) { "Der Download muss eine .gguf-Datei sein." }
        val target = File(dir, name)
        require(!target.exists()) { "Das Modell ist bereits installiert." }
        val temp = File(dir, "$name.part")
        var connection: HttpURLConnection? = null
        try {
            var offset = temp.length()
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AILinux-Pocket-AI/0.1")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            require(code == 200 || code == 206) { "HTTP $code – Download nicht verfügbar." }
            if (code == 206) {
                val range = connection.getHeaderField("Content-Range")
                require(range?.startsWith("bytes $offset-") == true) {
                    "Server liefert einen unerwarteten Download-Bereich."
                }
            } else if (offset > 0) {
                offset = 0L  // The server did not honor the resume Range header.
            }
            val total = connection.contentLengthLong.let { if (it >= 0) offset + it else -1L }
            if (total > 0) checkStorage((total - offset).coerceAtLeast(0))
            var written = offset
            connection.inputStream.use { input ->
                FileOutputStream(temp, offset > 0).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var sinceUpdate = 0
                    while (true) {
                        if (cancelled.get()) throw TransferCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        sinceUpdate += n
                        if (sinceUpdate >= 1024 * 1024) {
                            progress(TransferProgress(written, total))
                            sinceUpdate = 0
                        }
                    }
                    output.fd.sync()
                }
            }
            if (total >= 0 && total != written) error("Download unvollständig.")
            try {
                validate(temp)
                if (sha256 != null) {
                    require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Ungültige SHA-256 Prüfsumme." }
                    require(sha256(temp).equals(sha256, true)) { "SHA-256 stimmt nicht überein." }
                }
            } catch (error: Exception) {
                temp.delete()  // Never offer to resume corrupt content or a checksum mismatch.
                throw error
            }
            check(temp.renameTo(target)) { "Datei konnte nicht fertiggestellt werden." }
            progress(TransferProgress(target.length(), target.length()))
            return target
        } finally {
            connection?.disconnect()
            if (temp.exists() && temp.length() in 1..32) temp.delete()
        }
    }

    fun delete(file: File) {
        require(file.parentFile?.canonicalFile == dir.canonicalFile) { "Ungültiger Modellpfad." }
        require(file.name.endsWith(".gguf", true) || file.name.endsWith(".gguf.part", true))
        check(file.delete()) { "Datei konnte nicht gelöscht werden." }
    }

    private fun uniqueTarget(input: String): File {
        val name = sanitize(input)
        val stem = name.substring(0, name.length - 5)
        var target = File(dir, name)
        var number = 2
        while (target.exists() || File(dir, target.name + ".part").exists()) {
            target = File(dir, "$stem-$number.gguf")
            number++
        }
        return target
    }

    private fun checkStorage(additionalBytes: Long) {
        check(additionalBytes < dir.usableSpace - 128L * 1024 * 1024) {
            "Nicht genügend freier Speicher."
        }
    }

    companion object {
        fun sanitize(raw: String): String {
            val clean = raw.substringAfterLast('/').substringBefore('?')
                .replace(Regex("[^A-Za-z0-9._()-]"), "_").trim('.', '_').take(160)
            return clean.ifBlank { "model.gguf" }
        }
        fun displaySize(bytes: Long): String = when {
            bytes >= 1073741824L -> String.format(Locale.GERMAN, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1048576L -> String.format(Locale.GERMAN, "%.1f MB", bytes / 1048576.0)
            else -> String.format(Locale.GERMAN, "%.1f KB", bytes / 1024.0)
        }
        fun validate(file: File) {
            require(file.length() > 32) { "Datei ist zu klein für GGUF." }
            RandomAccessFile(file, "r").use {
                val magic = ByteArray(4)
                it.readFully(magic)
                require(String(magic, Charsets.US_ASCII) == "GGUF") {
                    "Kein gültiger GGUF-Header."
                }
            }
        }
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class TransferCancelled : Exception("Download unterbrochen – Fortschritt für später gespeichert.")
