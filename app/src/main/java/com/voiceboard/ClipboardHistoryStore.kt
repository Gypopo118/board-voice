package com.voiceboard

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Clipboard history that survives the IME process being unloaded.
 *
 * The previous implementation kept only an in-memory deque, so Android reclaiming the keyboard
 * process silently wiped everything the user had copied. The list now lives in the service's
 * private files directory. Base64 keeps arbitrary clipboard text (newlines, tabs, emoji) on one
 * line per item, and the newest entry stays first.
 *
 * Writes are small (at most [limit] short strings) and happen only when the history actually
 * changes, so doing them synchronously on the clipboard callback is cheaper than losing an item.
 */
class ClipboardHistoryStore(
    private val file: File,
    private val limit: Int = DEFAULT_LIMIT
) {

    private val items = ArrayDeque<String>()

    init {
        read()
    }

    /** Newest first. */
    fun snapshot(): List<String> = synchronized(this) { items.toList() }

    /** Adds [text] to the front; returns true when the stored history changed. */
    fun remember(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        synchronized(this) {
            if (items.firstOrNull() == value) return false
            items.remove(value)
            items.addFirst(value)
            while (items.size > limit) items.removeLast()
            write()
            return true
        }
    }

    private fun read() {
        if (!file.exists()) return
        try {
            for (line in file.readLines(StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue
                val decoded = try {
                    String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val value = decoded.trim()
                if (value.isNotEmpty() && !items.contains(value)) items.addLast(value)
            }
            while (items.size > limit) items.removeLast()
        } catch (_: Exception) {
            // A corrupt or unreadable history must never stop the keyboard from starting.
            items.clear()
        }
    }

    private fun write() {
        try {
            file.parentFile?.mkdirs()
            val encoded = items.joinToString("\n") {
                Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
            }
            file.writeText(encoded, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // The in-memory copy remains usable for this session.
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}
