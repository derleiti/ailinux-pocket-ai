package me.ailinux.pocket

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

data class TransferProgress(val done: Long, val total: Long) {
    val percent: Int get() = if (total > 0) ((done * 100) / total).coerceIn(0, 100).toInt() else -1
}

class ModelStore(private val context: Context) {
    val dir = File(context.filesDir, "models").also { it.mkdirs() }

    fun list(): List<File> = dir.listFiles()
        ?.filter { it.isFile && isModelName(it.name) && it.length() > 32 }
        ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        ?: emptyList()

    fun partials(): List<File> = dir.listFiles()
        ?.filter { it.isFile && (it.name.endsWith(".gguf.part", true) ||
            it.name.endsWith(".litertlm.part", true)) }
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
        require(isModelName(name)) { "Nur .gguf oder .litertlm-Dateien werden unterstützt." }
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
            validate(temp, name)
            // Import target was picked with a unique filename, but still
            // avoid overwrite if concurrent import took that name.
            AtomicModelInstall.publish(temp, target)
            return target
        } finally {
            temp.delete()
        }
    }

    fun download(
        address: String, displayName: String, sha256: String?,
        cancelled: AtomicBoolean, onVerifying: () -> Unit = {},
        progress: (TransferProgress) -> Unit
    ): File {
        val url = URL(address)
        require(url.protocol == "https") { "Nur sichere HTTPS-Downloads sind erlaubt." }
        val name = sanitize(displayName)
        require(isModelName(name)) { "Nur GGUF- oder LiteRT-LM-Downloads sind erlaubt." }
        val target = File(dir, name)
        require(!target.exists()) { "Das Modell ist bereits installiert." }
        val temp = File(dir, "$name.part")
        // The OS may restart a previously stopped UIDT while its old executor
        // thread is still unwinding a blocking stream. Never allow two threads
        // to append/replace the same model .part, even after a manual requeue.
        val lease = claimTransfer(dir, name)
        // Strong ETag or Last-Modified protects HTTP Range resume from a
        // replaced remote object. Legacy .part files without a validator
        // remain usable, but only an expected SHA can authenticate them.
        val validatorFile = File(dir, "$name.part.validator")
        var connection: HttpURLConnection? = null
        try {
            var offset = temp.length()
            val validator = validatorFile.takeIf { offset > 0 && it.isFile }
                ?.readText()?.trim()?.takeIf { it.length in 4..220 &&
                    !it.contains('\n') && !it.contains('\r') }
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AILinux-Pocket-AI/2.1")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) {
                    setRequestProperty("Range", "bytes=$offset-")
                    if (validator != null) setRequestProperty("If-Range", validator)
                }
            }
            val code = connection.responseCode
            require(connection.url.protocol.equals("https", ignoreCase = true)) {
                "Unsicheres Download-Redirect auf HTTP verweigert"
            }
            // A resume Range starting at EOF legitimately returns HTTP 416.
            // This happens when Android stopped us *after* the final byte but
            // *before* verification/publish. Never discard those bytes.
            if (code == 416 && offset > 32) {
                // Hugging Face Xet can omit Content-Range and ETag at EOF.
                // A bare HTTP 416 never authorizes publishing a .part file.
                val rangeSize = completedRangeLength(
                    connection.getHeaderField("Content-Range"))
                val knownHash = sha256?.takeIf { isSha256(it) }
                val head = if (knownHash == null)
                    completionHead(url, cancelled) else null
                val linkedHash = head?.sha256
                if (knownHash != null && linkedHash != null &&
                    !knownHash.equals(linkedHash, true))
                    throw IOException("HTTP 416: Quell-Hash wurde geändert; Teil-Datei bleibt erhalten.")
                val hashForVerification = knownHash ?: linkedHash
                require(canCompleteRange(offset, rangeSize, head?.size,
                    validator, connection.getHeaderField("ETag")
                        ?: head?.strongEtag, hashForVerification != null)) {
                    "HTTP 416: Remote-Länge oder Validator nicht sicher nachgewiesen. " +
                        "Teil-Datei bleibt erhalten."
                }
                progress(TransferProgress(offset, offset))
                return verifyAndPublish(temp, target, name, validatorFile,
                    hashForVerification, cancelled, onVerifying, progress)
            }
            if (code == 429 || code in 500..599)
                throw java.io.IOException("HTTP " + code + " – Server vorübergehend nicht erreichbar")
            require(code == 200 || code == 206) { "HTTP $code – Download nicht verfügbar." }
            if (code == 206) {
                // Without a stable remote validator or an expected file hash,
                // resuming an old partial could quietly join two different models.
                require(validator != null || sha256?.matches(
                    Regex("[a-fA-F0-9]{64}")) == true) {
                    "Partial-Download ohne ETag/If-Range oder SHA-256 kann nicht sicher fortgesetzt werden"
                }
                val range = connection.getHeaderField("Content-Range")
                require(offset > 0 && range?.startsWith("bytes $offset-") == true) {
                    "Server liefert einen unerwarteten Download-Bereich."
                }
                val newTag = connection.getHeaderField("ETag")
                if (validator?.startsWith('"') == true && newTag != null) {
                    require(validator == newTag) {
                        "Remote-Modell wurde geändert. Alte Teildatei nicht überschrieben."
                    }
                } else if (validator != null && !validator.startsWith('"')) {
                    val newer = connection.getHeaderField("Last-Modified")
                    require(newer == null || newer == validator) {
                        "Remote-Modell hat anderes Last-Modified; alte Teildatei bewahrt."
                    }
                }
            } else if (offset > 0) {
                offset = 0L // A 200 reply replaces the old incomplete .part, never a model.
            }
            val newValidator = connection.getHeaderField("ETag")
                ?.takeIf { it.startsWith('"') && it.endsWith('"') && it.length < 200 }
                ?: connection.getHeaderField("Last-Modified")?.takeIf { it.length in 4..180 }
            if (newValidator != null && (offset == 0L || validator == null)) {
                val marker = File(dir, "$name.part.validator.new")
                marker.writeText(newValidator)
                check(marker.renameTo(validatorFile)) {
                    "Downloadvalidator konnte nicht gespeichert werden"
                }
            } else if (offset == 0L && newValidator == null) {
                // A server that sends HTTP 200 after If-Range restarted the
                // incomplete download. Never reuse an old validator with the
                // new bytes after a later network interruption.
                validatorFile.delete()
            }
            val total = connection.contentLengthLong.let { if (it >= 0) offset + it else -1L }
            if (total > 0) checkStorage((total - offset).coerceAtLeast(0))
            progress(TransferProgress(offset, total))
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
            if (total >= 0 && total != written)
                throw java.io.IOException("Download unvollständig: " + written + "/" + total)
            return verifyAndPublish(temp, target, name, validatorFile,
                sha256, cancelled, onVerifying, progress)
        } finally {
            connection?.disconnect()
            if (temp.exists() && temp.length() in 1..32) temp.delete()
            lease.close()
        }
    }

    private fun verifyAndPublish(
        temp: File, target: File, name: String, validatorFile: File,
        expectedHash: String?, cancelled: AtomicBoolean,
        onVerifying: () -> Unit, progress: (TransferProgress) -> Unit
    ): File {
        if (cancelled.get()) throw TransferCancelled()
        onVerifying()
        try {
            validate(temp, name)
            if (expectedHash != null) {
                require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) {
                    "Ungültige SHA-256 Prüfsumme."
                }
                require(sha256(temp, cancelled).equals(expectedHash, true)) {
                    "SHA-256 stimmt nicht überein."
                }
            }
        } catch (error: Exception) {
            // Preserve a rejected partial until the user explicitly discards
            // it. Never publish on header/hash mismatch or cancellation.
            throw error
        }
        if (cancelled.get()) throw TransferCancelled()
        AtomicModelInstall.publish(temp, target)
        validatorFile.delete()
        progress(TransferProgress(target.length(), target.length()))
        return target
    }

    /**
     * Export an existing private GGUF to a user-created Android SAF document.
     * No broad filesystem permission and no temporary copy are required.
     * The destination is chosen by the user; an interrupted external document
     * may be partial and must not be used as a migration backup.
     */
    fun export(
        source: File,
        destination: Uri,
        cancelled: AtomicBoolean,
        progress: (TransferProgress) -> Unit
    ): Long {
        val canonical = source.canonicalFile
        require(canonical.parentFile == dir.canonicalFile &&
            canonical.isFile && isModelName(canonical.name)) {
            "Nur eigene GGUF-Modelle dürfen exportiert werden."
        }
        validate(canonical)
        val length = canonical.length()
        val output = context.contentResolver.openOutputStream(destination, "w")
            ?: error("Gewählte Zieldatei kann nicht beschrieben werden.")
        return canonical.inputStream().buffered().use { input ->
            output.use { target ->
                ModelExportCopy.copy(input, target, length, cancelled, progress)
            }
        }
    }

    fun delete(file: File) {
        require(file.parentFile?.canonicalFile == dir.canonicalFile) { "Ungültiger Modellpfad." }
        require(isModelName(file.name) || file.name.endsWith(".gguf.part", true) ||
            file.name.endsWith(".litertlm.part", true))
        check(file.delete()) { "Datei konnte nicht gelöscht werden." }
        // Never let a stale Range validator attach to a future download.
        if (file.name.endsWith(".part", true))
            File(dir, file.name + ".validator").delete()
    }

    private fun uniqueTarget(input: String): File {
        val name = sanitize(input)
        val extension = if (name.endsWith(".litertlm", true)) ".litertlm" else ".gguf"
        val stem = name.dropLast(extension.length)
        var target = File(dir, name)
        var number = 2
        while (target.exists() || File(dir, target.name + ".part").exists()) {
            target = File(dir, "$stem-$number$extension")
            number++
        }
        return target
    }

    private fun checkStorage(additionalBytes: Long) {
        check(additionalBytes < dir.usableSpace - 128L * 1024 * 1024) {
            "Nicht genügend freier Speicher."
        }
    }

    /**
     * HEAD-only recovery for a complete .part at EOF. Trust Hugging Face's
     * X-Linked-Etag as SHA-256 only with its exact X-Linked-Size from the
     * official source host; otherwise compare a strong CDN HEAD ETag.
     */
    private fun completionHead(url: URL, cancelled: AtomicBoolean): CompletionEvidence? {
        if (cancelled.get()) throw TransferCancelled()
        fun fetch(followRedirects: Boolean): CompletionEvidence? {
            val head = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 9_000
                readTimeout = 12_000
                instanceFollowRedirects = followRedirects
                setRequestProperty("User-Agent", "AILinux-Pocket-AI/2.1")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val status = head.responseCode
                require(head.url.protocol.equals("https", ignoreCase = true)) {
                    "Unsicheres Redirect auf HTTP verweigert"
                }
                if (cancelled.get()) throw TransferCancelled()
                if (status != 200 && (followRedirects ||
                    status !in setOf(301, 302, 303, 307, 308))) return null
                val linkedSize = if (!followRedirects &&
                    url.host.equals("huggingface.co", ignoreCase = true))
                    head.getHeaderField("X-Linked-Size")?.toLongOrNull()
                        ?.takeIf { it > 0 }
                else null
                val linkedHash = linkedSize?.let {
                    head.getHeaderField("X-Linked-Etag")?.trim()?.trim('"')
                        ?.takeIf { hash -> isSha256(hash) }
                }
                val size = linkedSize ?:
                    head.contentLengthLong.takeIf { status == 200 && it > 0 }
                val tag = head.getHeaderField("ETag")
                    ?.takeIf { isStrongEtag(it) }
                return CompletionEvidence(size, tag, linkedHash)
            } finally { head.disconnect() }
        }
        val origin = fetch(false)
        if (origin?.size != null && origin.sha256 != null) return origin
        val resolved = fetch(true)
        return when {
            resolved == null -> origin
            origin?.sha256 == null -> resolved
            else -> resolved.copy(sha256 = origin.sha256)
        }
    }

    companion object {
        private val activeDownloads = ConcurrentHashMap.newKeySet<String>()
        internal fun claimTransfer(directory: File, name: String): AutoCloseable {
            val key = File(directory, sanitize(name)).canonicalPath
            if (!activeDownloads.add(key))
                throw IOException("Dieser Modelltransfer beendet gerade einen früheren " +
                    "Downloadversuch. Android kann danach erneut starten.")
            return AutoCloseable { activeDownloads.remove(key) }
        }

        internal data class CompletionEvidence(
            val size: Long?, val strongEtag: String?, val sha256: String?
        )

        internal fun isSha256(value: String): Boolean =
            value.matches(Regex("[a-fA-F0-9]{64}"))

        internal fun isStrongEtag(value: String): Boolean =
            value.length in 3..220 && value.startsWith('"') &&
                value.endsWith('"') && !value.contains('\n') &&
                !value.contains('\r')

        /**
         * A bare HTTP 416 or rounded UI size cannot authorize publishing.
         * A verified SHA-256 is enough unless server size contradicts it.
         * Without a checksum require exact size plus current strong ETag.
         */
        internal fun canCompleteRange(
            localBytes: Long, rangeBytes: Long?, headBytes: Long?,
            savedTag: String?, currentTag: String?, hasExpectedSha: Boolean
        ): Boolean {
            if (localBytes <= 32) return false
            if (rangeBytes != null && rangeBytes != localBytes) return false
            if (headBytes != null && headBytes != localBytes) return false
            if (hasExpectedSha) return true
            return (rangeBytes == localBytes || headBytes == localBytes) &&
                savedTag != null && isStrongEtag(savedTag) &&
                currentTag == savedTag
        }

        /** RFC 9110 unsatisfied-range total, never parsed from model-supplied data. */
        internal fun completedRangeLength(value: String?): Long? {
            val match = Regex("^bytes \\*/([0-9]{1,19})$").matchEntire(
                value?.trim() ?: return null) ?: return null
            return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
        }

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
        fun isModelName(name: String): Boolean =
            name.endsWith(".gguf", true) || name.endsWith(".litertlm", true)

        /** Verify the binary container header before exposing a downloaded file. */
        fun validate(file: File, logicalName: String = file.name) {
            require(isModelName(logicalName)) { "GGUF- oder LiteRT-LM-Datei erwartet." }
            require(file.length() > 32) { "Modell-Datei ist zu klein." }
            RandomAccessFile(file, "r").use {
                val magic = ByteArray(8)
                it.readFully(magic)
                val expected = if (logicalName.endsWith(".litertlm", true))
                    "LITERTLM" else "GGUF"
                require(String(magic.copyOfRange(0, expected.length), Charsets.US_ASCII) == expected) {
                    "Kein gültiger " + expected + "-Header."
                }
            }
        }
        fun sha256(file: File, cancelled: AtomicBoolean? = null): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (cancelled?.get() == true) throw TransferCancelled()
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
