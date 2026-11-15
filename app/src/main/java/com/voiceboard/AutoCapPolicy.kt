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
}
