package com.voiceboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordTokensTest {

    @Test
    fun keepsInternalHyphensInTheTrailingWord() {
        assertEquals("что-то", WordTokens.trailing("я думаю что-то"))
        assertEquals("как-нибудь", WordTokens.trailing("сделай как-нибудь"))
        assertEquals("что-", WordTokens.trailing("я думаю что-"))
        assertEquals("Санкт-Петербург", WordTokens.trailing("город Санкт-Петербург"))
    }

    @Test
    fun aStandaloneSeparatorDoesNotStartAWord() {
        assertEquals("", WordTokens.trailing("слово -"))
        assertEquals("", WordTokens.trailing("слово- "))
        assertEquals("то", WordTokens.trailing("что--то"))
        assertEquals("", WordTokens.trailing("-"))
    }

    @Test
    fun findsThePreviousHyphenatedWord() {
        assertEquals("что-то", WordTokens.before("что-то ", ""))
        assertEquals("что-то", WordTokens.before("ну что-то дальше", "дальше"))
        assertEquals("Санкт-Петербурге", WordTokens.before("в Санкт-Петербурге ", ""))
        assertNull(WordTokens.before("слово", "слово"))
        assertNull(WordTokens.before(", ", ""))
    }

    @Test
    fun splitsPunctuationButNotHyphens() {
        assertEquals("мир", WordTokens.trailing("привет, мир"))
        assertEquals("привет", WordTokens.before("привет, мир", "мир"))
    }
}
