package com.voiceboard

import android.text.InputType

/**
 * Pure rules for sentence boundaries, abbreviations and automatic capitalisation.
 */
object AutoCapPolicy {

    private val SENTENCE_END = setOf('.', '!', '?')
    private val OPENING = setOf('(', '[', '{', '«')
    private val CLOSING = setOf(')', ']', '}', '»', '"')
    private val ABBREVIATION_DICTIONARY = listOf("т.д.", "т.п.", "т.е.")
    private const val ABBREVIATION_FIRST = 'т'
    private val ABBREVIATION_CONTINUATIONS = setOf('д', 'п', 'е')

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
     * True when the text before the cursor ends with the internal dot of `т.д.` / `т.п.` / `т.е.`
     * and [next] is the letter that follows it. Such a dot must add neither a space nor a capital
     * letter: it is inside the abbreviation, not a sentence boundary.
     */
    fun isAbbreviationContinuation(textBeforeCursor: String?, next: Char): Boolean {
        val text = textBeforeCursor ?: return false
        if (!text.endsWith('.')) return false
        val lower = text.lowercase()
        val firstIndex = lower.length - 2
        if (firstIndex < 0 || lower[firstIndex] != ABBREVIATION_FIRST) return false
        // "привет." is not an abbreviation; the "т" must start its own token.
        if (firstIndex > 0 && lower[firstIndex - 1].isLetterOrDigit()) return false
        return next.lowercaseChar() in ABBREVIATION_CONTINUATIONS
    }

    /** True when the text ends with a complete `т.д.` / `т.п.` / `т.е.` token. */
    fun isAbbreviationEnd(textBeforeCursor: String?): Boolean {
        val text = textBeforeCursor?.lowercase()?.trimTrailingHorizontalSpace() ?: return false
        for (abbreviation in ABBREVIATION_DICTIONARY) {
            if (!text.endsWith(abbreviation)) continue
            val start = text.length - abbreviation.length
            if (start == 0 || !text[start - 1].isLetterOrDigit()) return true
        }
        return false
    }

    /**
     * Whether a new sentence starts right after [textBeforeCursor].
     *
     * This looks past trailing spaces and closing quotes/brackets, so deleting the word after
     * `"Привет. "` still restores the capital letter. A complete `т.д.` / `т.п.` / `т.е.` does not
     * start a new sentence even though it ends with a dot.
     *
     * [ellipsisEndsSentence] additionally treats `…` as a sentence end. Auto-shift keeps it out of
     * [SENTENCE_END], but the greeting button capitalises its phrase after an ellipsis as well.
     */
    fun shouldCapitalizeAfter(
        textBeforeCursor: String?,
        ellipsisEndsSentence: Boolean = false
    ): Boolean {
        val text = textBeforeCursor ?: return true
        var end = text.length
        while (end > 0 && text[end - 1] != '\n' && text[end - 1].isWhitespace()) end--
        if (end == 0) return true
        val trimmed = text.substring(0, end)
        if (trimmed.last() == '\n') return true

        var index = trimmed.length - 1
        while (index >= 0) {
            val char = trimmed[index]
            if (char == '\n') return true
            if (char.isWhitespace() || char in CLOSING || char in OPENING) {
                index--
                continue
            }
            break
        }
        val mark = trimmed.getOrNull(index) ?: return true
        val sentenceEnd = mark in SENTENCE_END || (ellipsisEndsSentence && mark == '…')
        if (!sentenceEnd) return false
        return !isAbbreviationEnd(trimmed.substring(0, index + 1))
    }

    private fun String.trimTrailingHorizontalSpace(): String {
        var end = length
        while (end > 0 && this[end - 1] != '\n' && this[end - 1].isWhitespace()) end--
        return substring(0, end)
    }
}
