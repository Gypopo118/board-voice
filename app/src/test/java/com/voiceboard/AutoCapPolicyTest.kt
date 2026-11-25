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
    fun sentenceContextLooksPastSpacesAndClosingPunctuation() {
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter(null))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter(""))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("."))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("!"))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("?"))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("\n"))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter(" "))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("привет. "))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("привет!  "))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("«Привет!» "))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("Привет. «"))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("«"))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("привет\n«"))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("а"))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("Привет «"))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("привет. Как"))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter(","))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("привет, "))
    }

    @Test
    fun ellipsisEndsASentenceOnlyForTheGreeting() {
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("Ну… "))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("Ну… ", ellipsisEndsSentence = true))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("Ну…", ellipsisEndsSentence = true))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("«Ну…» ", ellipsisEndsSentence = true))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("привет, ", ellipsisEndsSentence = true))
    }

    @Test
    fun abbreviationsWithDotsDoNotStartNewSentences() {
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("т.д."))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("т.д. "))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("и т.д.  "))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("т.п. "))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("т.е. "))
        assertFalse(AutoCapPolicy.shouldCapitalizeAfter("«т.д.» "))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("привет. "))
        assertTrue(AutoCapPolicy.shouldCapitalizeAfter("фт.д."))
    }

    @Test
    fun abbreviationContinuationRecognisesOnlyTheInternalDot() {
        assertTrue(AutoCapPolicy.isAbbreviationContinuation("т.", 'д'))
        assertTrue(AutoCapPolicy.isAbbreviationContinuation("т.", 'п'))
        assertTrue(AutoCapPolicy.isAbbreviationContinuation("т.", 'е'))
        assertTrue(AutoCapPolicy.isAbbreviationContinuation("и т.", 'Д'))
        assertTrue(AutoCapPolicy.isAbbreviationContinuation("Т.", 'д'))

        assertFalse(AutoCapPolicy.isAbbreviationContinuation("т.", 'к'))
        assertFalse(AutoCapPolicy.isAbbreviationContinuation("привет.", 'д'))
        assertFalse(AutoCapPolicy.isAbbreviationContinuation("фт.", 'д'))
        assertFalse(AutoCapPolicy.isAbbreviationContinuation("т.д.", 'с'))
        assertFalse(AutoCapPolicy.isAbbreviationContinuation("т", 'д'))
    }
}
