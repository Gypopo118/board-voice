package com.voiceboard

import android.view.inputmethod.InputConnection

/**
 * Groups the edits of one logical insertion into a single `beginBatchEdit()`/`endBatchEdit()`
 * transaction on one [InputConnection] snapshot.
 *
 * Reactive fields (Jetpack Compose `BasicTextField`, e.g. the Gemini composer) rebuild their
 * input session while they animate an inserted word. Two separate edits — delete the draft,
 * commit the chosen word — can then straddle that restart: the delete lands on the old session,
 * the commit on the new one, and the field ends up empty. Pinning one connection and one batch
 * keeps the insertion atomic, and [tailMatches] lets the caller verify that the word really landed
 * on the fresh connection afterwards.
 */
class AtomicTextEditor(private val connectionProvider: () -> InputConnection?) {

    /** Connection every edit of the current compound operation must use. */
    private var pinned: InputConnection? = null

    /** The live connection, or the one pinned by the current [run] block. */
    fun active(): InputConnection? = pinned ?: connectionProvider()

    /** True while a compound edit is open. */
    val inBatch: Boolean get() = pinned != null

    /**
     * Runs [block] inside one batch on a single connection snapshot. Nested calls join the outer
     * batch instead of opening a second one. Returns null when the connection is gone or the block
     * threw; the caller can then verify and retry on the fresh connection.
     */
    fun <T> run(block: (InputConnection) -> T): T? {
        pinned?.let { return block(it) }
        val connection = connectionProvider() ?: return null
        pinned = connection
        var started = false
        try {
            connection.beginBatchEdit()
            started = true
            return block(connection)
        } catch (_: Exception) {
            return null
        } finally {
            if (started) {
                try {
                    connection.endBatchEdit()
                } catch (_: Exception) {
                    // A session that died mid-batch is recovered by the caller's verification.
                }
            }
            pinned = null
        }
    }

    /** Text before the caret, read from [connection] or the active connection. */
    fun textBefore(
        maxChars: Int,
        connection: InputConnection? = active()
    ): String {
        if (connection == null || maxChars < 0) return ""
        return try {
            connection.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** True when the text before the caret ends with [expected], ignoring case. */
    fun tailMatches(
        expected: String,
        connection: InputConnection? = active()
    ): Boolean {
        if (expected.isEmpty()) return true
        val tail = textBefore(expected.length, connection)
        return tail.length == expected.length && tail.equals(expected, ignoreCase = true)
    }
}
