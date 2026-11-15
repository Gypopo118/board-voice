package com.voiceboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeTextPolicyTest {

    @Test
    fun casingFollowsShiftAndCaps() {
        assertEquals("привет", SwipeTextPolicy.caseWord("привет", false, false))
        assertEquals("Привет", SwipeTextPolicy.caseWord("привет", false, true))
        assertEquals("ПРИВЕТ", SwipeTextPolicy.caseWord("привет", true, false))
        assertEquals("ПРИВЕТ", SwipeTextPolicy.caseWord("привет", true, true))
        assertEquals("Ёлка", SwipeTextPolicy.caseWord("ёлка", false, true))
        assertEquals("ЁЛКА", SwipeTextPolicy.caseWord("ёлка", true, false))
    }

    @Test
    fun backspaceDeletesWholeSwipedWordAndItsLeadingSpace() {
        assertEquals(
            6,
            SwipeTextPolicy.backspaceDeleteCount("привет", "привет", false)
        )
        assertEquals(
            7,
            SwipeTextPolicy.backspaceDeleteCount("привет", "привет", true)
        )
        assertEquals(
            6,
            SwipeTextPolicy.backspaceDeleteCount("Привет", "привет", false)
        )
        assertNull(SwipeTextPolicy.backspaceDeleteCount("привет", "другой", false))
        assertNull(SwipeTextPolicy.backspaceDeleteCount("п", "п", false))
        assertNull(SwipeTextPolicy.backspaceDeleteCount("", "привет", false))
    }
}
