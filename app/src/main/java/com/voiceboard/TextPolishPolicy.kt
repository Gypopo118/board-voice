package com.voiceboard

import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic post-processing and sanity checks for the magic-wand correction.
 *
 * DeepSeek is asked for a light edit, but models sometimes slip into rewriting. The wand still
 * normalises every long dash to a plain hyphen and refuses a response whose size or word count
 * changed too much for a punctuation/typo pass.
 */
object TextPolishPolicy {

    private val WORDS = Regex("[\\p{L}\\p{N}]+")

    /** The keyboard's correction style uses short hyphens only, even if the model emits "—". */
    fun normalizeDashes(text: String): String {
        if (text.none { isLongDash(it) }) return text
        return buildString(text.length) {
            for (char in text) append(if (isLongDash(char)) '-' else char)
        }
    }

    /**
     * Generous bounds: commas and hyphens barely change length or word count, while a paraphrase,
     * a new sentence or a removed thought does. For longer texts we also require a good share of
     * the original words to survive verbatim, so a polished-sounding rewrite is not accepted.
     */
    fun isLightEdit(original: String, corrected: String): Boolean {
        val lengthTolerance = max(40, original.length / 2)
        if (abs(corrected.length - original.length) > lengthTolerance) return false

        val originalWords = words(original)
        val correctedWords = words(corrected)
        val wordTolerance = max(2, originalWords.size / 10)
        if (abs(correctedWords.size - originalWords.size) > wordTolerance) return false

        if (originalWords.size < SHORT_TEXT_WORDS) return true
        val correctedCounts = correctedWords.groupingBy { it }.eachCount()
        var common = 0
        for ((word, count) in originalWords.groupingBy { it }.eachCount()) {
            common += minOf(count, correctedCounts[word] ?: 0)
        }
        return common >= originalWords.size * MIN_COMMON_WORD_SHARE
    }

    private fun isLongDash(char: Char): Boolean = when (char) {
        '\u2014', '\u2013', '\u2015', '\u2212' -> true
        else -> false
    }

    private fun words(text: String): List<String> =
        WORDS.findAll(text.lowercase()).map { it.value }.toList()

    private const val SHORT_TEXT_WORDS = 6
    private const val MIN_COMMON_WORD_SHARE = 0.4
}
