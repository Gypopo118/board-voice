package com.voiceboard

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCapPolicyTest {

    @Test
    fun plainTextAllowsAutoCapitalisation() {
        assertTrue(AutoCapPolicy.allows(InputType.TYPE_CLASS_TEXT))
        assertTrue(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            )
        )
        assertTrue(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            )
        )
        assertTrue(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            )
        )
    }

    @Test
    fun passwordsEmailsUrisAndNumbersAreExcluded() {
        assertFalse(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
        assertFalse(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        )
        assertFalse(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        )
        assertFalse(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
        assertFalse(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        )
        assertFalse(
            AutoCapPolicy.allows(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
        )
        assertFalse(AutoCapPolicy.allows(InputType.TYPE_CLASS_NUMBER))
        assertFalse(AutoCapPolicy.allows(InputType.TYPE_CLASS_PHONE))
        assertFalse(AutoCapPolicy.allows(InputType.TYPE_CLASS_DATETIME))
    }

    @Test
    fun onlySentenceEndingPunctuationTriggersShift() {
        assertTrue(AutoCapPolicy.endsSentence("."))
        assertTrue(AutoCapPolicy.endsSentence("!"))
        assertTrue(AutoCapPolicy.endsSentence("?"))
        assertFalse(AutoCapPolicy.endsSentence(","))
        assertFalse(AutoCapPolicy.endsSentence(":"))
        assertFalse(AutoCapPolicy.endsSentence(";"))
        assertFalse(AutoCapPolicy.endsSentence("…"))
        assertFalse(AutoCapPolicy.endsSentence(".."))
        assertFalse(AutoCapPolicy.endsSentence(""))
    }

    @Test
    fun deletionReturnsShiftAtFieldStartAndSentenceBoundaries() {
        assertTrue(AutoCapPolicy.shouldCapitalizeAfterDeletion(null))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfterDeletion(""))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfterDeletion("."))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfterDeletion("!"))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfterDeletion("?"))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfterDeletion("\n"))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfterDeletion("а"))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfterDeletion(" "))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfterDeletion(","))
    }
}
