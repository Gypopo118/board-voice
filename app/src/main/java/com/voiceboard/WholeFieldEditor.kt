package com.voiceboard

import android.view.inputmethod.InputConnection

/**
 * Whole-field text access for features that operate on the complete editor content rather than on
 * text around the cursor (the magic-wand correction).
 *
 * [read] keeps a selection in the result: Android's [InputConnection] reports text before the
 * selection start and after the selection end, so the selected fragment must be inserted between
 * them. [replace] selects the field and commits over it, then verifies the result so that a
 * misbehaving editor cannot be left with duplicated text.
 */
object WholeFieldEditor {

    private const val READ_LIMIT = Int.MAX_VALUE

    /** Full field text, including the selected fragment when there is a selection. */
    fun read(connection: InputConnection): String? = try {
        val before = connection.getTextBeforeCursor(READ_LIMIT, 0)?.toString()
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(READ_LIMIT, 0)?.toString()
        if (before == null || after == null) null else before + selected + after
    } catch (_: Exception) {
        null
    }

    /** Replaces the whole field with [replacement], leaving the cursor at the end. */
    fun replace(
        connection: InputConnection,
        original: String,
        replacement: String
    ): Boolean {
        val replaced = try {
            connection.beginBatchEdit()
            try {
                if (!selectWholeField(connection, original)) {
                    false
                } else {
                    connection.commitText(replacement, 1)
                    true
                }
            } finally {
                connection.endBatchEdit()
            }
        } catch (_: Exception) {
            false
        }
        if (replaced && read(connection) == replacement) return true

        // Best-effort rollback: a few editors keep the old text and append the commit instead of
        // replacing the selection.
        try {
            val current = read(connection)
            if (current != null && current != original &&
                connection.setSelection(0, current.length)
            ) {
                connection.commitText(original, 1)
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun selectWholeField(connection: InputConnection, original: String): Boolean {
        if (connection.setSelection(0, original.length)) return true
        if (!connection.performContextMenuAction(android.R.id.selectAll)) return false
        val selected = try {
            connection.getSelectedText(0)?.toString()
        } catch (_: Exception) {
            null
        }
        return selected == original
    }
}
