package com.voiceboard

/**
 * Word boundaries around the caret for the T9/gesture state.
 *
 * Hyphenated words have to stay one token: after «что-то» one Backspace removes the whole word,
 * the T9 panel keeps the swipe alternatives, and typing «что-» continues the same prefix so the
 * trie can complete it to «что-то». A hyphen belongs to a word only when letters follow it in
 * both directions, so a standalone separator (`слово -`) still ends the token.
 */
object WordTokens {

    /** Index where the word that ends [text] starts. */
    fun trailingStart(text: CharSequence): Int {
        var start = text.length
        while (start > 0) {
            val previous = text[start - 1]
            if (previous.isLetter()) {
                start--
                continue
            }
            if (previous == '-' && start >= 2 && text[start - 2].isLetter()) {
                start--
                continue
            }
            break
        }
        return start
    }

    /** Word that ends [text], including internal hyphens. */
    fun trailing(text: CharSequence): String = text.substring(trailingStart(text))

    /**
     * Word directly before [current], which must be the trailing token of [text].
     * Returns null at the beginning of the text or when only punctuation precedes the caret.
     */
    fun before(text: CharSequence, current: String): String? {
        var end = text.length - current.length
        if (end <= 0) return null
        while (end > 0 && !text[end - 1].isLetter()) end--
        if (end == 0) return null
        return trailing(text.substring(0, end)).takeIf { it.isNotEmpty() }
    }
}
