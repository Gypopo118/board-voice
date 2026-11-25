package com.voiceboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingPolicyTest {

    @Test
    fun capitalisesAtTheStartOfTheFieldAndAfterSentenceMarks() {
        assertEquals("Привет.", GreetingPolicy.forCursor("привет.", null))
        assertEquals("Привет.", GreetingPolicy.forCursor("привет.", ""))
        assertEquals("Привет.", GreetingPolicy.forCursor("привет.", "."))
        assertEquals("Привет.", GreetingPolicy.forCursor("привет.", "Как дела? "))
        assertEquals("Привет.", GreetingPolicy.forCursor("привет.", "Стоп!  "))
        assertEquals("Привет.", GreetingPolicy.forCursor("привет.", "Ну… "))
        assertEquals("Привет.", GreetingPolicy.forCursor("привет.", "«Стоп!» "))
        assertEquals("Добрый день.", GreetingPolicy.forCursor("добрый день.", "Ну… "))
    }

    @Test
    fun keepsLowerCaseInsideASentence() {
        assertEquals("привет.", GreetingPolicy.forCursor("привет.", "Как дела"))
        assertEquals("привет.", GreetingPolicy.forCursor("привет.", "привет, "))
        assertEquals("привет.", GreetingPolicy.forCursor("привет.", "итак: "))
        assertEquals("добрый день.", GreetingPolicy.forCursor("добрый день.", "привет"))
        // An abbreviation dot does not start a new sentence even though it ends with a dot.
        assertEquals("привет.", GreetingPolicy.forCursor("привет.", "и т.д. "))
    }

    @Test
    fun emptyPhraseStaysEmpty() {
        assertEquals("", GreetingPolicy.forCursor("", null))
    }

    @Test
    fun startsANewTokenWithALeadingSpace() {
        assertFalse(GreetingPolicy.needsLeadingSpace(null, "привет."))
        assertFalse(GreetingPolicy.needsLeadingSpace("", "привет."))
        assertFalse(GreetingPolicy.needsLeadingSpace("Привет. ", "привет."))
        assertFalse(GreetingPolicy.needsLeadingSpace("«", "привет."))
        assertTrue(GreetingPolicy.needsLeadingSpace("слово", "привет."))
        assertTrue(GreetingPolicy.needsLeadingSpace("Привет.", "привет."))
        assertTrue(GreetingPolicy.needsLeadingSpace("слово,", "привет."))
    }
}
