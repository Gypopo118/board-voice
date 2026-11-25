package com.voiceboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaretMoverTest {

    /**
     * Text-field model: text, caret position and a log of the arrow keys the mover sent. A key
     * pressed at either edge of the text would be forwarded by Android to focus navigation, so
     * the model fails the test instead of moving.
     */
    private class FakeEditor(
        var text: String,
        var caret: Int,
        var selectionEnd: Int = caret
    ) : CaretMover.Editor {

        /** When false, the editor keeps working but reports the selection as unknown. */
        var reportsSelection = true
        var rejectSelection = false
        var hideText = false
        val arrows = mutableListOf<Boolean>()

        override fun selectionStart(): Int = if (reportsSelection) caret else -1

        override fun selectionEnd(): Int = if (reportsSelection) selectionEnd else -1

        override fun applySelection(start: Int, end: Int): Boolean {
            if (rejectSelection) return false
            require(start in 0..text.length && end in 0..text.length) {
                "selection $start..$end is outside the text"
            }
            caret = start
            selectionEnd = end
            return true
        }

        override fun textBeforeCursor(maxChars: Int): String? {
            if (hideText) return null
            if (caret <= 0) return ""
            return text.substring((caret - maxChars).coerceAtLeast(0), caret)
        }

        override fun textAfterCursor(maxChars: Int): String? {
            if (hideText) return null
            if (selectionEnd >= text.length) return ""
            return text.substring(selectionEnd, (selectionEnd + maxChars).coerceAtMost(text.length))
        }

        override fun sendArrowKey(forward: Boolean) {
            if (forward) {
                check(selectionEnd < text.length) {
                    "DPAD_RIGHT at the end of the text would move focus away"
                }
                caret = selectionEnd + characterAt(selectionEnd)
            } else {
                check(caret > 0) {
                    "DPAD_LEFT at the start of the text would move focus away"
                }
                caret -= characterBefore(caret)
            }
            selectionEnd = caret
            arrows.add(forward)
        }

        private fun characterAt(index: Int): Int =
            if (index + 1 < text.length &&
                Character.isHighSurrogate(text[index]) &&
                Character.isLowSurrogate(text[index + 1])
            ) 2 else 1

        private fun characterBefore(index: Int): Int =
            if (index >= 2 &&
                Character.isHighSurrogate(text[index - 2]) &&
                Character.isLowSurrogate(text[index - 1])
            ) 2 else 1
    }

    @Test
    fun movesCaretRightAndLeft() {
        val editor = FakeEditor("abcdef", caret = 0)
        val mover = CaretMover(editor)

        assertEquals(4, mover.move(4))
        assertEquals(4, editor.caret)
        assertEquals(2, mover.move(-2))
        assertEquals(2, editor.caret)
        assertTrue("selection changes must not dispatch key events", editor.arrows.isEmpty())
    }

    @Test
    fun stopsAtTheEndOfTheText() {
        val editor = FakeEditor("abc", caret = 3)
        val mover = CaretMover(editor)

        assertEquals(3, mover.move(5))
        assertEquals(3, editor.caret)
        assertTrue(editor.arrows.isEmpty())
    }

    @Test
    fun stopsAtTheStartOfTheText() {
        val editor = FakeEditor("abc", caret = 0)
        val mover = CaretMover(editor)

        assertEquals(0, mover.move(-5))
        assertEquals(0, editor.caret)
        assertTrue(editor.arrows.isEmpty())
    }

    @Test
    fun clampsBothDirectionsInsideTheText() {
        val editor = FakeEditor("abcdef", caret = 2)
        val mover = CaretMover(editor)

        assertEquals(6, mover.move(20))
        assertEquals(0, mover.move(-20))
        assertTrue(editor.arrows.isEmpty())
    }

    @Test
    fun countsASurrogatePairAsOneStep() {
        val editor = FakeEditor("a\uD83D\uDE00b", caret = 1)
        val mover = CaretMover(editor)

        assertEquals(3, mover.move(1))
        assertEquals(4, mover.move(1))
        assertEquals(4, mover.move(1))
        assertEquals(3, mover.move(-1))
        assertEquals(1, mover.move(-1))
        assertTrue(editor.arrows.isEmpty())
    }

    @Test
    fun collapsesSelectionToTheEdgeBeforeMoving() {
        val forward = FakeEditor("hello world", caret = 0, selectionEnd = 5)
        assertEquals(6, CaretMover(forward).move(2))
        assertEquals(6, forward.caret)

        val backward = FakeEditor("hello world", caret = 2, selectionEnd = 7)
        assertEquals(0, CaretMover(backward).move(-3))
        assertEquals(0, backward.caret)

        val single = FakeEditor("hello world", caret = 0, selectionEnd = 5)
        assertEquals(5, CaretMover(single).move(1))
        assertEquals(5, single.caret)
    }

    @Test
    fun fallsBackToArrowKeysWhenSelectionIsRejected() {
        val editor = FakeEditor("abc", caret = 1)
        editor.rejectSelection = true

        assertEquals(-1, CaretMover(editor).move(2))
        assertEquals(3, editor.caret)
        assertEquals(listOf(true, true), editor.arrows)
    }

    @Test
    fun arrowFallbackStopsAtTheTextEdges() {
        val atEnd = FakeEditor("abc", caret = 3)
        atEnd.rejectSelection = true
        assertEquals(3, CaretMover(atEnd).move(2))
        assertTrue(atEnd.arrows.isEmpty())

        val atStart = FakeEditor("abc", caret = 0)
        atStart.rejectSelection = true
        assertEquals(0, CaretMover(atStart).move(-2))
        assertTrue(atStart.arrows.isEmpty())
    }

    @Test
    fun unknownSelectionUsesArrowKeys() {
        val editor = FakeEditor("abc", caret = 1)
        editor.reportsSelection = false

        assertEquals(-1, CaretMover(editor).move(2))
        assertEquals(3, editor.caret)
        assertEquals(listOf(true, true), editor.arrows)
    }

    @Test
    fun missingTextStopsTheMoveInsteadOfGuessing() {
        val editor = FakeEditor("abc", caret = 1)
        editor.hideText = true

        assertEquals(-1, CaretMover(editor).move(3))
        assertEquals(1, editor.caret)
        assertTrue(editor.arrows.isEmpty())
    }
}
