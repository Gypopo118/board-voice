package com.voiceboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    @Test
    fun typedLettersUndoAndRedoOneAtATime() {
        val history = EditHistory()
        history.record(letter("", "П"))
        history.record(letter("П", "р"))
        history.record(letter("Пр", "и"))

        assertEquals("и", history.peekUndo()?.inserted)
        history.undoApplied()
        assertEquals("р", history.peekUndo()?.inserted)
        history.undoApplied()
        assertEquals("П", history.peekUndo()?.inserted)
        history.undoApplied()
        assertFalse(history.canUndo)

        assertEquals("П", history.peekRedo()?.inserted)
        history.redoApplied()
        history.redoApplied()
        history.redoApplied()
        assertFalse(history.canRedo)
    }

    @Test
    fun aNewEditDropsTheRedoBranch() {
        val history = EditHistory()
        history.record(letter("", "а"))
        history.undoApplied()
        assertTrue(history.canRedo)

        history.record(letter("", "б"))

        assertFalse(history.canRedo)
        assertEquals("б", history.peekUndo()?.inserted)
    }

    @Test
    fun clipboardPasteIsOneWholeEntry() {
        val history = EditHistory()
        val entry = history.record(
            EditHistory.Entry(context = "Привет", removed = "", inserted = " огромный текст")
        )

        assertSame(entry, history.peekUndo())
        assertEquals(1, history.undoSize)
    }

    @Test
    fun swipeWordMergesWithItsStarterLetter() {
        val history = EditHistory()
        val starter = requireNotNull(history.record(letter("Привет", " п")))
        val replacement = requireNotNull(
            history.record(
                EditHistory.Entry(context = "Привет", removed = "п", inserted = "привет")
            )
        )

        val combined = EditHistory.combine(starter, replacement)

        assertEquals("", combined?.removed)
        assertEquals(" привет", combined?.inserted)
        assertEquals("Привет", combined?.context)
    }

    @Test
    fun swipeAfterPunctuationKeepsTheNewSpaceInTheUndoEntry() {
        val starter = EditHistory.Entry(context = "слово,", removed = "", inserted = "п")
        val replacement = EditHistory.Entry(context = "слово,", removed = "п", inserted = " привет")

        val combined = EditHistory.combine(starter, replacement)

        assertEquals("", combined?.removed)
        assertEquals(" привет", combined?.inserted)
    }

    @Test
    fun combineRejectsUnrelatedEdits() {
        val first = EditHistory.Entry("", "", "а")
        val second = EditHistory.Entry("", "б", "в")

        assertNull(EditHistory.combine(first, second))
    }

    @Test
    fun replaceLastOnlyWorksForTheNewestEntry() {
        val history = EditHistory()
        val first = requireNotNull(history.record(letter("", "а")))
        val second = requireNotNull(history.record(letter("а", "б")))
        val merged = EditHistory.Entry("", "", "аб")

        assertFalse(history.replaceLast(first, merged))
        assertTrue(history.replaceLast(second, merged))
        assertEquals("аб", history.peekUndo()?.inserted)
        assertEquals(2, history.undoSize)
    }

    @Test
    fun historyIsBounded() {
        val history = EditHistory(limit = 3)
        repeat(5) { history.record(letter("", "а")) }

        assertEquals(3, history.undoSize)
    }

    @Test
    fun emptyEditsAreIgnored() {
        val history = EditHistory()

        assertNull(history.record(EditHistory.Entry("", "", "")))
        assertFalse(history.canUndo)
    }

    private fun letter(context: String, inserted: String) =
        EditHistory.Entry(context = context, removed = "", inserted = inserted)
}
