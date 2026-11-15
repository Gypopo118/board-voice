package com.voiceboard

import java.util.Locale

/**
 * Pure decisions around committing a gesture-typed word: casing and the amount a single
 * Backspace should remove. Smart space placement itself lives in [AutoSpacingManager].
 */
object SwipeTextPolicy {

    fun caseWord(word: String, caps: Boolean, firstIsUpper: Boolean): String = when {
        caps -> word.uppercase(Locale.ROOT)
        firstIsUpper -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    /**
     * Null means this Backspace is not the "delete the just-swiped word" case. A positive result is
     * the number of characters to remove, including the smart space inserted before the word.
     */
    fun backspaceDeleteCount(
        rawWord: String,
        target: String,
        leadingSpace: Boolean
    ): Int? {
        if (rawWord.length < 2 || !rawWord.equals(target, ignoreCase = true)) return null
        return rawWord.length + if (leadingSpace) 1 else 0
    }
}
