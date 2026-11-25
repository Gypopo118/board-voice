package com.voiceboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipboardHistoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(limit: Int = 20) =
        ClipboardHistoryStore(folder.newFile("clipboard_history.txt"), limit)

    @Test
    fun historySurvivesANewStoreInstance() {
        val file = folder.newFile("clipboard_history.txt")
        ClipboardHistoryStore(file).apply {
            remember("первый")
            remember("второй")
            remember("третий")
        }

        val restored = ClipboardHistoryStore(file)
        assertEquals(listOf("третий", "второй", "первый"), restored.snapshot())
    }

    @Test
    fun remembersAnItemOnlyOnceAndMovesItToTheFront() {
        val store = store()
        assertTrue(store.remember("один"))
        assertTrue(store.remember("два"))
        assertFalse("the newest item is already first", store.remember("два"))
        assertTrue(store.remember("один"))
        assertEquals(listOf("один", "два"), store.snapshot())
    }

    @Test
    fun keepsTheNewestEntriesWithinTheLimit() {
        val store = store(limit = 3)
        for (item in listOf("a", "b", "c", "d")) store.remember(item)
        assertEquals(listOf("d", "c", "b"), store.snapshot())
    }

    @Test
    fun preservesWhitespaceInsideAnItem() {
        val file = folder.newFile("clipboard_history.txt")
        val text = "первая строка\nвторая строка\tс табом"
        ClipboardHistoryStore(file).remember(text)

        assertEquals(listOf(text), ClipboardHistoryStore(file).snapshot())
    }

    @Test
    fun ignoresBlankText() {
        val store = store()
        assertFalse(store.remember("   "))
        assertFalse(store.remember("\n\t"))
        assertTrue(store.snapshot().isEmpty())
    }
}
