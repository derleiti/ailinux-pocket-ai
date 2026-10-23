package me.ailinux.pocket

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Bounded, dependency-free reader for Android's debuggerd Tombstone protobuf.
 * Schema: AOSP system/core/debuggerd/proto/tombstone.proto.
 *
 * Only abort/signal/faulting thread backtrace and a few relevant log lines are
 * inspected. Memory dumps, memory maps, command lines and heap data are skipped.
 */
object NativeTombstoneDecoder {
    private const val MAX_PROTO_SIZE = 8 * 1024 * 1024

    internal data class Frame(
        val file: String = "",
        val function: String = "",
        val pc: Long = 0,
        val offset: Long = 0
    )
    private data class ThreadTrace(val id: Int, val name: String, val frames: List<Frame>, val notes: List<String>)
    private data class SignalInfo(val number: Int, val name: String, val codeName: String)

    fun read(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (out.size() < MAX_PROTO_SIZE) {
            val n = input.read(chunk, 0, minOf(chunk.size, MAX_PROTO_SIZE - out.size()))
            if (n < 0) break
            if (n == 0) continue
            out.write(chunk, 0, n)
        }
        return if (out.size() == 0) "Tombstone-Datei ist leer."
        else try {
            decode(out.toByteArray())
        } catch (e: Exception) {
            "Tombstone konnte nicht vollständig dekodiert werden: " +
                (e.message ?: e.javaClass.simpleName) +
                ". Format/Protobuf möglicherweise zu groß oder unvollständig."
        }
    }

    internal fun decode(bytes: ByteArray): String {
        val reader = Cursor(bytes, 0, bytes.size)
        var signal: SignalInfo? = null
        var abort = ""
        var timestamp = ""
        var crashTid = 0
        val causes = ArrayList<String>()
        val threads = LinkedHashMap<Int, ThreadTrace>()
        val relevantLogs = ArrayList<String>()
        var parseWarning: String? = null
        try {
        while (true) {
            val field = reader.field() ?: break
            when (field.first) {
                4 -> timestamp = reader.text(field.second, 150)
                6 -> crashTid = reader.number(field.second).toInt()
                10 -> signal = parseSignal(reader.child(field.second))
                14 -> abort = reader.text(field.second, 3500)
                15 -> {
                    if (causes.size < 3) parseCause(reader.child(field.second))?.let { causes.add(it) }
                    else reader.skip(field.second)
                }
                16 -> {
                    if (threads.size < 256) {
                        parseThreadMap(reader.child(field.second))?.let { threads[it.id] = it }
                    } else reader.skip(field.second)
                }
                18 -> {
                    if (relevantLogs.size < 12) parseLogBuffer(reader.child(field.second), relevantLogs)
                    else reader.skip(field.second)
                }
                else -> reader.skip(field.second)
            }
        }
        } catch (e: IllegalArgumentException) {
            // The platform may have evicted or truncated a tombstone. Keep
            // already parsed signal/abort/frames instead of discarding them.
            parseWarning = e.message ?: "Unvollstaendiger Protobuf-Datensatz"
        }
        val lines = mutableListOf<String>()
        if (timestamp.isNotEmpty()) lines.add("Tombstone: " + timestamp)
        if (signal != null) {
            lines.add("Signal: " + signal.name + " (" + signal.number + "), Code: " + signal.codeName)
        }
        if (abort.isNotBlank()) lines.add("Abort: " + abort.take(3500))
        causes.forEach { lines.add("Ursache: " + it.take(1000)) }
        val faulting = threads[crashTid] ?: threads.values.firstOrNull()
        if (faulting != null) {
            lines.add("Absturz-Thread: " + faulting.id + " (" + faulting.name + ")")
            faulting.notes.take(3).forEach { lines.add("Hinweis: " + it.take(350)) }
            if (faulting.frames.isEmpty()) {
                lines.add("Keine Stackframes fuer den Absturz-Thread gespeichert.")
            } else {
                faulting.frames.take(18).forEachIndexed { index, frame ->
                    val file = frame.file.substringAfterLast('/').ifEmpty { "?" }
                    val func = frame.function.ifEmpty { "??" }.take(240)
                    val offset = if (frame.offset > 0) " +" + frame.offset else ""
                    lines.add("#" + index.toString().padStart(2, '0') +
                        " " + file + "  " + func + offset + "  pc 0x" +
                        java.lang.Long.toHexString(frame.pc))
                }
            }
        } else {
            lines.add("Absturz-Thread ist in diesem Tombstone nicht enthalten.")
        }
        if (relevantLogs.isNotEmpty()) {
            lines.add("Native Log-Auszuege:")
            lines.addAll(relevantLogs.takeLast(10))
        }
        if (parseWarning != null) {
            lines.add("Hinweis: Tombstone nicht vollständig lesbar (" + parseWarning.take(200) + ")")
        }
        return lines.joinToString("\n").take(10500)
    }

    private fun parseSignal(r: Cursor): SignalInfo {
        var number = 0
        var name = "?"
        var code = "?"
        while (true) {
            val f = r.field() ?: break
            when (f.first) {
                1 -> number = r.number(f.second).toInt()
                2 -> name = r.text(f.second, 80)
                4 -> code = r.text(f.second, 120)
                else -> r.skip(f.second)
            }
        }
        return SignalInfo(number, name, code)
    }

    private fun parseCause(r: Cursor): String? {
        var cause: String? = null
        while (true) {
            val f = r.field() ?: break
            if (f.first == 1) cause = r.text(f.second, 1000) else r.skip(f.second)
        }
        return cause
    }

    private fun parseThreadMap(r: Cursor): ThreadTrace? {
        var key = 0
        var thread: ThreadTrace? = null
        while (true) {
            val f = r.field() ?: break
            when (f.first) {
                1 -> key = r.number(f.second).toInt()
                2 -> thread = parseThread(r.child(f.second))
                else -> r.skip(f.second)
            }
        }
        return thread?.copy(id = if (thread.id != 0) thread.id else key)
    }

    private fun parseThread(r: Cursor): ThreadTrace {
        var id = 0
        var name = "?"
        val frames = ArrayList<Frame>()
        val notes = ArrayList<String>()
        while (true) {
            val f = r.field() ?: break
            when (f.first) {
                1 -> id = r.number(f.second).toInt()
                2 -> name = r.text(f.second, 140)
                4 -> if (frames.size < 64) frames.add(parseFrame(r.child(f.second)))
                     else r.skip(f.second)
                7 -> if (notes.size < 4) notes.add(r.text(f.second, 350))
                     else r.skip(f.second)
                else -> r.skip(f.second)
            }
        }
        return ThreadTrace(id, name, frames, notes)
    }

    private fun parseFrame(r: Cursor): Frame {
        var pc = 0L
        var file = ""
        var function = ""
        var offset = 0L
        while (true) {
            val f = r.field() ?: break
            when (f.first) {
                1 -> pc = r.number(f.second)
                4 -> function = r.text(f.second, 250)
                5 -> offset = r.number(f.second)
                6 -> file = r.text(f.second, 300)
                else -> r.skip(f.second)
            }
        }
        return Frame(file, function, pc, offset)
    }

    private fun parseLogBuffer(r: Cursor, logs: MutableList<String>) {
        while (true) {
            val f = r.field() ?: break
            if (f.first == 2) {
                val log = parseLogMessage(r.child(f.second))
                if (log != null && logs.size < 12) logs.add(log)
            } else r.skip(f.second)
        }
    }

    private fun parseLogMessage(r: Cursor): String? {
        var tag = ""
        var message = ""
        while (true) {
            val f = r.field() ?: break
            when (f.first) {
                5 -> tag = r.text(f.second, 90)
                6 -> message = r.text(f.second, 650)
                else -> r.skip(f.second)
            }
        }
        // Only error-related messages: generic llama.cpp debug logs may
        // contain user prompts or local paths and must not enter reports.
        if (!Regex("(?i)(abort|assert|fatal|sigabrt|failure|failed|error|out.of.memory|scudo|fortify)")
                .containsMatchIn(message)) return null
        // Only copy bounded diagnostic snippets. Never include full heap, file
        // descriptor inventory, command-line or memory contents from tombstone.
        return tag.take(55) + ": " + message.take(450)
    }

    private class Cursor(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
        fun field(): Pair<Int, Int>? {
            if (pos >= end) return null
            val tag = varint()
            val id = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            require(id > 0 && wire in listOf(0, 1, 2, 5)) { "Ungueltiges Protobuf-Feld" }
            return id to wire
        }

        fun number(wire: Int): Long {
            require(wire == 0) { "Zahl mit falschem Protobuf-Wiretyp" }
            return varint()
        }

        fun text(wire: Int, maxChars: Int): String {
            val length = size(wire)
            val len = minOf(length, maxChars * 4)
            val value = String(bytes, pos, len, StandardCharsets.UTF_8)
            pos += length
            return value.take(maxChars)
        }

        fun child(wire: Int): Cursor {
            val length = size(wire)
            val child = Cursor(bytes, pos, pos + length)
            pos += length
            return child
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> move(8)
                2 -> move(size(wire))
                5 -> move(4)
                else -> throw IllegalArgumentException("Unbekannter Wiretyp " + wire)
            }
        }

        private fun size(wire: Int): Int {
            require(wire == 2) { "Laengenfeld hat falschen Wiretyp" }
            val size = varint()
            require(size in 0..(end - pos).toLong()) { "Ungueltige Protobuf-Laenge" }
            return size.toInt()
        }

        private fun move(length: Int) {
            require(length >= 0 && length <= end - pos) { "Tombstone zu kurz" }
            pos += length
        }

        private fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                require(pos < end) { "Tombstone unerwartet beendet" }
                val byte = bytes[pos++].toInt() and 0xff
                if (i == 9) require(byte <= 1) { "Ungueltiger Varint-Wert" }
                value = value or ((byte and 0x7f).toLong() shl (7 * i))
                if (byte and 0x80 == 0) return value
            }
            throw IllegalArgumentException("Varint zu lang")
        }
    }
}
