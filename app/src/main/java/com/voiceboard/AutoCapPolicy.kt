package com.voiceboard

import android.text.InputType

/** Pure rules for automatic capitalisation, kept testable without an InputConnection. */
object AutoCapPolicy {

    private val SENTENCE_END = setOf('.', '!', '?')

    fun allows(inputType: Int): Boolean {
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI -> false
            else -> true
        }
    }

    fun endsSentence(mark: String): Boolean =
        mark.length == 1 && mark[0] in SENTENCE_END

    /**
     * After Backspace the cursor may return to the start of a word, sentence or line. In those
     * positions the next letter must be capitalised again, even though the previous Shift state
     * was already consumed by the deleted letter.
     */
    fun shouldCapitalizeAfterDeletion(beforeCursor: String?): Boolean {
        val last = beforeCursor?.lastOrNull() ?: return true
        return last == '\n' || endsSentence(last.toString())
    }
}
