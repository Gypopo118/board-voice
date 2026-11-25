package com.voiceboard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Moves the caret for the spacebar cursor gesture.
 *
 * The drag used to send `DPAD_LEFT`/`DPAD_RIGHT` key events through the `InputConnection`. A text
 * field consumes those only while the caret can actually move: at the first or last character the
 * event is left unhandled and Android performs focus navigation with it. The field then lost focus
 * — the caret disappeared and every key stopped typing until the user tapped the field again.
 *
 * The caret is moved with `InputConnection.setSelection()` instead, bounded by the text that
 * actually surrounds it, so no key event reaches the application. Arrow keys remain only as a
 * fallback for editors that reject direct selection changes, and even there a step is skipped as
 * soon as the caret reaches the edge of the text.
 */
class CaretMover(private val editor: Editor) {

    interface Editor {
        /** Selection start reported by the editor, or -1 while the position is unknown. */
        fun selectionStart(): Int

        /** Selection end reported by the editor, or -1 while the position is unknown. */
        fun selectionEnd(): Int

        /** Replaces the selection; false when the editor rejects direct selection changes. */
        fun applySelection(start: Int, end: Int): Boolean

        fun textBeforeCursor(maxChars: Int): String?

        fun textAfterCursor(maxChars: Int): String?

        /** Legacy fallback for editors whose [applySelection] does not work. */
        fun sendArrowKey(forward: Boolean)
    }

    /**
     * Moves the caret [steps] characters to the right (negative values move it left), never past
     * the first or the last character of the field.
     *
     * @return the offset the caret now holds, or -1 when arrow keys were used and the editor
     *  reports the new offset asynchronously through its selection callback.
     */
    fun move(steps: Int): Int {
        if (steps == 0) return -1
        val forward = steps > 0
        var remaining = abs(steps)

        val start = editor.selectionStart()
        val end = editor.selectionEnd()
        if (start < 0 || end < 0) {
            pressArrows(forward, remaining)
            return -1
        }

        var caret = start
        if (start != end) {
            // The first arrow press collapses an active selection to its left or right edge.
            caret = if (forward) max(start, end) else min(start, end)
            if (!editor.applySelection(caret, caret)) {
                pressArrows(forward, remaining)
                return -1
            }
            remaining--
            if (remaining == 0) return caret
        }

        // Two UTF-16 units per step cover surrogate pairs; two extra units let the walker see a
        // whole pair at the far edge of the requested window.
        val window = remaining * 2 + 2
        val text = if (forward) editor.textAfterCursor(window) else editor.textBeforeCursor(window)
        if (text == null) return -1
        val shift = if (forward) advance(text, remaining) else retreat(text, remaining)
        if (shift == 0) return caret
        val target = if (forward) caret + shift else caret - shift
        if (!editor.applySelection(target, target)) {
            pressArrows(forward, remaining)
            return -1
        }
        return target
    }

    /** Arrow fallback. Stops before a key that the field cannot consume leaves the editor. */
    private fun pressArrows(forward: Boolean, steps: Int) {
        repeat(steps) {
            val available = if (forward) editor.textAfterCursor(2) else editor.textBeforeCursor(2)
            if (available.isNullOrEmpty()) return
            editor.sendArrowKey(forward)
        }
    }

    /** UTF-16 chars the caret advances across [text], at most [steps] characters. */
    private fun advance(text: String, steps: Int): Int {
        var index = 0
        var remaining = steps
        while (remaining > 0 && index < text.length) {
            index += characterLength(text, index)
            remaining--
        }
        return index
    }

    /** UTF-16 chars the caret retreats across [text], which ends at the caret. */
    private fun retreat(text: String, steps: Int): Int {
        var index = text.length
        var remaining = steps
        while (remaining > 0 && index > 0) {
            index -= if (index >= 2 &&
                Character.isHighSurrogate(text[index - 2]) &&
                Character.isLowSurrogate(text[index - 1])
            ) {
                2
            } else {
                1
            }
            remaining--
        }
        return text.length - index
    }

    private fun characterLength(text: String, index: Int): Int =
        if (index + 1 < text.length &&
            Character.isHighSurrogate(text[index]) &&
            Character.isLowSurrogate(text[index + 1])
        ) {
            2
        } else {
            1
        }
}
