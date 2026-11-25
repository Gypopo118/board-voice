package com.voiceboard

import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtomicTextEditorTest {

    /** Minimal text field: a buffer, a caret and batch/commit counters. */
    private class FakeConnection(
        var text: String,
        var caret: Int = text.length,
        var dropCommits: Int = 0
    ) : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {

        var beginCount = 0
        var endCount = 0
        var commits = 0

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            text.substring(maxOf(0, caret - n), caret)

        override fun getSelectedText(flags: Int): CharSequence = ""

        override fun beginBatchEdit(): Boolean {
            beginCount++
            return true
        }

        override fun endBatchEdit(): Boolean {
            endCount++
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            commits++
            if (dropCommits > 0) {
                // A reactive field dropped the edit while it restarted its input session.
                dropCommits--
                return true
            }
            val value = text.toString()
            this.text = this.text.substring(0, caret) + value + this.text.substring(caret)
            caret += value.length
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val start = maxOf(0, caret - beforeLength)
            val end = minOf(this.text.length, caret + afterLength)
            text = this.text.removeRange(start, end)
            caret = start
            return true
        }
    }

    @Test
    fun pinsOneConnectionForTheWholeBatch() {
        val first = FakeConnection("а")
        val restarted = FakeConnection("")
        var current: FakeConnection = first
        val editor = AtomicTextEditor { current }

        var activeInside: FakeConnection? = null
        editor.run { connection ->
            // The field restarts its session in the middle of the logical edit.
            current = restarted
            activeInside = editor.active() as FakeConnection
            connection.deleteSurroundingText(1, 0)
            connection.commitText("москва", 1)
        }

        assertSame(first, activeInside)
        assertEquals("москва", first.text)
        assertEquals(1, first.beginCount)
        assertEquals(1, first.endCount)
        assertEquals(0, restarted.commits)
        assertEquals("", restarted.text)
    }

    @Test
    fun nestedEditsJoinTheOuterBatch() {
        val connection = FakeConnection("")
        val editor = AtomicTextEditor { connection }

        editor.run {
            editor.run {
                editor.run { inner -> inner.commitText("а", 1) }
            }
        }

        assertEquals(1, connection.beginCount)
        assertEquals(1, connection.endCount)
        assertEquals("а", connection.text)
    }

    @Test
    fun aThrowingEditStillClosesTheBatch() {
        val connection = FakeConnection("")
        val editor = AtomicTextEditor { connection }

        val result = editor.run<Int> { throw IllegalStateException("session died") }

        assertNull(result)
        assertEquals(1, connection.beginCount)
        assertEquals(1, connection.endCount)
        assertFalse(editor.inBatch)
    }

    @Test
    fun tailMatchesChecksOnlyTheTextBeforeTheCaret() {
        val connection = FakeConnection("в москве")
        val editor = AtomicTextEditor { connection }

        assertTrue(editor.tailMatches("москве"))
        assertTrue(editor.tailMatches("МОСКВЕ"))
        assertFalse(editor.tailMatches("москва"))
        assertFalse(editor.tailMatches("в москве "))

        connection.caret = 3
        assertFalse(editor.tailMatches("москве"))
        assertTrue(editor.tailMatches("в м"))
    }

    @Test
    fun aDroppedCommitIsRetriedOnceOnTheRestartedConnection() {
        val original = FakeConnection("к", dropCommits = 1)
        val restarted = FakeConnection("")
        var current: FakeConnection = original
        val editor = AtomicTextEditor { current }
        val word = "москва"

        // Swipe/suggestion replace: delete the draft, commit the word, all in one batch.
        editor.run { connection ->
            connection.deleteSurroundingText(1, 0)
            connection.commitText(word, 1)
        }
        // The commit was swallowed and the field came back with a fresh session.
        current = restarted
        assertFalse(editor.tailMatches(word))

        // The same recovery the keyboard uses: retry only if the word is really absent.
        editor.run { connection ->
            if (!editor.tailMatches(word, connection)) connection.commitText(word, 1)
        }

        assertTrue(editor.tailMatches(word))
        assertEquals(word, restarted.text)
        assertEquals(1, original.beginCount)
        assertEquals(1, original.endCount)
        assertEquals(1, restarted.beginCount)
        assertEquals(1, restarted.endCount)
        assertEquals(1, restarted.commits)
    }

    @Test
    fun aSuccessfulCommitIsNotRetried() {
        val connection = FakeConnection("")
        val editor = AtomicTextEditor { connection }

        editor.run { it.commitText("москва", 1) }
        editor.run { it ->
            if (!editor.tailMatches("москва", it)) it.commitText("москва", 1)
        }

        assertEquals("москва", connection.text)
        assertEquals(1, connection.commits)
        assertEquals(2, connection.beginCount)
    }

    @Test
    fun missingConnectionIsReportedAsFailure() {
        val editor = AtomicTextEditor { null }
        assertNull(editor.run { 1 })
        assertFalse(editor.tailMatches("москва"))
    }
}
