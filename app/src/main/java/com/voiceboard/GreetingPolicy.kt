package com.voiceboard

/**
 * Case rule for the greeting button. The phrases themselves come from string resources; only the
 * first letter depends on the context.
 *
 * The rule mirrors the keyboard's auto-cap logic (start of the field or after `. ! ?`, looking
 * past trailing spaces and quotes), and also treats an ellipsis as a sentence end: the greeting
 * spec lists `…` among the marks after which the phrase starts with a capital letter.
 */
object GreetingPolicy {

    private val OPENING = setOf('(', '[', '{', '«')

    /** [phrase] with its first letter capitalised when the caret starts a sentence. */
    fun forCursor(phrase: String, textBeforeCursor: String?): String {
        if (phrase.isEmpty()) return phrase
        return if (AutoCapPolicy.shouldCapitalizeAfter(
                textBeforeCursor,
                ellipsisEndsSentence = true
            )
        ) {
            phrase.replaceFirstChar { it.uppercaseChar() }
        } else {
            phrase
        }
    }

    /**
     * The greeting is a separate token: it needs a leading space when the caret stands right
     * after another token, but not at the start of the field/line or right after an opening
     * bracket. The inserted text then reads `слово Привет.`, never `словоПривет.`.
     */
    fun needsLeadingSpace(textBeforeCursor: String?, phrase: String): Boolean {
        if (phrase.isEmpty() || phrase.first().isWhitespace()) return false
        val previous = textBeforeCursor?.lastOrNull() ?: return false
        return !previous.isWhitespace() && previous !in OPENING
    }
}
