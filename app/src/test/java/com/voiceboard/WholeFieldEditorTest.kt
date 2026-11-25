package com.voiceboard

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeFieldEditorTest {

    @Test
    fun readsTheWholeFieldIncludingASelection() {
        val editable = SpannableStringBuilder("привет мир")
        val connection = connection(editable)
        Selection.setSelection(editable, 3, 7)

        assertEquals("привет мир", WholeFieldEditor.read(connection))
    }

    @Test
    fun replacementCoversTheWholeFieldAndPlacesCursorAtTheEnd() {
        val editable = SpannableStringBuilder("привет мир")
        val connection = connection(editable)
        Selection.setSelection(editable, 2, 5)

        assertTrue(WholeFieldEditor.replace(connection, "привет мир", "Привет, мир."))

        assertEquals("Привет, мир.", editable.toString())
        assertEquals(editable.length, Selection.getSelectionStart(editable))
        assertEquals(editable.length, Selection.getSelectionEnd(editable))
    }

    @Test
    fun clearingTheWholeFieldLeavesAnEmptyEditor() {
        val editable = SpannableStringBuilder("привет мир")
        val connection = connection(editable)

        assertTrue(WholeFieldEditor.replace(connection, "привет мир", ""))
        assertEquals("", editable.toString())
    }

    @Test
    fun clearingThenRestoringTheWholeFieldRoundTrips() {
        val editable = SpannableStringBuilder("привет мир")
        val connection = connection(editable)

        assertTrue(WholeFieldEditor.replace(connection, "привет мир", ""))
        assertTrue(WholeFieldEditor.replace(connection, "", "привет мир"))
        assertEquals("привет мир", editable.toString())
    }

    @Test
    fun replacementFailsWithoutChangingTextWhenSelectAllIsUnsupported() {
        val editable = SpannableStringBuilder("привет мир")
        val connection = object : BaseInputConnection(targetView(), true) {
            override fun getEditable(): Editable = editable
            override fun setSelection(start: Int, end: Int): Boolean = false
            override fun performContextMenuAction(id: Int): Boolean = false
        }

        assertFalse(WholeFieldEditor.replace(connection, "привет мир", "Привет, мир."))
        assertEquals("привет мир", editable.toString())
    }

    private fun connection(editable: Editable): BaseInputConnection =
        object : BaseInputConnection(targetView(), true) {
            override fun getEditable(): Editable = editable
        }

    private fun targetView() = View(RuntimeEnvironment.getApplication())
}
