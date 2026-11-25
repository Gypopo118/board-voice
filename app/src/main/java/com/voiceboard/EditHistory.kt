package com.voiceboard

/**
 * Bounded undo/redo history for text edits made by the keyboard.
 *
 * Every [Entry] is a replacement of [Entry.removed] with [Entry.inserted] directly before the
 * cursor, so one typed letter, a whole clipboard paste, a swiped word or a T9 substitution is one
 * entry. [Entry.context] is the unchanged text immediately before that replacement; it lets the
 * service reject an undo after the user moved the cursor or another app changed the field.
 */
class EditHistory(private val limit: Int = DEFAULT_LIMIT) {

    data class Entry(
        val context: String,
        val removed: String,
        val inserted: String,
        val before: AutoSpacingManager.UndoState? = null,
        val after: AutoSpacingManager.UndoState? = null,
        val oneShotShiftBefore: Boolean = false,
        val capsBefore: Boolean = false,
        val oneShotShiftAfter: Boolean = false,
        val capsAfter: Boolean = false
    )

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    internal val undoSize: Int get() = undoStack.size
    internal val redoSize: Int get() = redoStack.size

    /** Records a new user edit; like every editor, this drops the redo branch. */
    fun record(entry: Entry): Entry? {
        if (entry.removed.isEmpty() && entry.inserted.isEmpty()) return null
        undoStack.addLast(entry)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        return entry
    }

    fun peekUndo(): Entry? = undoStack.lastOrNull()

    fun peekRedo(): Entry? = redoStack.lastOrNull()

    /** Moves the newest entry to the redo stack after it was applied backwards. */
    fun undoApplied(): Entry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(entry)
        return entry
    }

    /** Moves the newest redo entry back to the undo stack after it was applied forwards. */
    fun redoApplied(): Entry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        return entry
    }

    /** Swaps [expected] for a merged entry while it is still the newest undo step. */
    fun replaceLast(expected: Entry, combined: Entry?): Boolean {
        if (combined == null) return false
        if (undoStack.lastOrNull() !== expected) return false
        undoStack.removeLast()
        return record(combined) != null
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    companion object {
        private const val DEFAULT_LIMIT = 100

        /**
         * Folds two consecutive edits into one. The second edit must consume the tail of the text
         * inserted by the first one; that is exactly the gesture-typing case where the swipe word
         * replaces its starter letter, so undo removes the whole word.
         */
        fun combine(first: Entry, second: Entry): Entry? {
            if (second.removed.length > first.inserted.length) return null
            if (!first.inserted.endsWith(second.removed)) return null
            val leftover = first.inserted.dropLast(second.removed.length)
            return first.copy(
                inserted = leftover + second.inserted,
                after = second.after,
                oneShotShiftAfter = second.oneShotShiftAfter,
                capsAfter = second.capsAfter
            )
        }
    }
}
