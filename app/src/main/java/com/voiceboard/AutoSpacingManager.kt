package com.voiceboard

import android.text.InputType

/**
 * AOSP LatinIME/Gboard style automatic spacing.
 *
 * Words are committed without a trailing space. Instead the manager remembers what the next
 * insertion needs:
 *  - [SpaceState.PHANTOM] — no physical space is in the editor yet, but the next suitable token
 *    must be prefixed with one;
 *  - [SpaceState.WEAK] — the user just typed a manual space; a sticky punctuation mark may remove
 *    it again.
 *
 * All text access goes through [Editor], so the state machine is testable without an
 * [android.view.inputmethod.InputConnection].
 */
class AutoSpacingManager(private val editor: Editor) {

    enum class SpaceState { NONE, PHANTOM, WEAK }

    enum class CharacterClass { STICKY_PUNCTUATION, OPENING_PUNCTUATION, WORD_SEPARATOR, TYPED }

    data class WordInsertion(val leadingSpace: Boolean)

    interface Editor {
        fun textBeforeCursor(maxChars: Int): String?
        fun commitText(text: String)
        fun deleteBeforeCursor(count: Int)

        /** True when replacing a selection, where smart spaces must not be inserted. */
        fun hasSelection(): Boolean = false
    }

    var state = SpaceState.NONE
        private set

    /** Automatic spacing is off for URIs, e-mail, password and numeric fields. */
    var enabled = true
        set(value) {
            field = value
            if (!value) state = SpaceState.NONE
        }

    private var editingDepth = 0
    private var lastEditNanos = 0L

    /** Last sticky mark, used to distinguish a sentence end from `10,5`, `14:30`, `v1.2`. */
    private var lastStickyMark: Char? = null

    /**
     * Cursor movement produced by our own commits/deletions that the editor has not reported yet.
     * Selection updates cross the IME/editor process boundary asynchronously, so matching this
     * delta lets us distinguish them from a real cursor move even after the settle window.
     */
    private var pendingCursorDelta = 0

    fun setInputType(inputType: Int?) {
        enabled = inputType != null && allowsAutoSpacing(inputType)
        reset()
    }

    fun reset() {
        state = SpaceState.NONE
        pendingCursorDelta = 0
        lastEditNanos = 0L
        lastStickyMark = null
    }

    fun commitWord(word: String): WordInsertion = edit {
        commitWordLocked(word)
    }

    /**
     * Replaces text that belongs to the current token (a swipe starter letter or a typed word)
     * with [word]. [stateBeforeDelete] is used by gesture typing: the starter letter was already
     * committed and moved the state to NONE, but the swipe result must be spaced as if the letter
     * had never been typed.
     */
    fun replaceWord(
        deleteCount: Int,
        word: String,
        stateBeforeDelete: SpaceState? = null
    ): WordInsertion = edit {
        if (deleteCount > 0) erase(deleteCount)
        if (stateBeforeDelete != null && enabled) state = stateBeforeDelete
        commitWordLocked(word)
    }

    /**
     * `. , ! ? : ; … ) ] } »` never get a space before them; a WEAK space is withdrawn.
     * [dropLeadingSpace] handles a smart space that was inserted together with a long-pressed
     * primary character and is removed together with that character.
     */
    fun commitStickyPunctuation(mark: String, dropLeadingSpace: Boolean = false) = edit {
        if (!enabled) {
            write(mark)
            state = SpaceState.NONE
            lastStickyMark = null
        } else if (editor.hasSelection()) {
            write(mark)
            state = SpaceState.PHANTOM
            lastStickyMark = mark.firstOrNull()
        } else {
            if ((state == SpaceState.WEAK || dropLeadingSpace) && previousChar() == ' ') {
                erase(1)
            }
            write(mark)
            state = SpaceState.PHANTOM
            lastStickyMark = mark.firstOrNull()
        }
    }

    /** `( [ { «` start a new group: the following word sticks to the bracket. */
    fun commitOpening(mark: String) = edit {
        if (!enabled || editor.hasSelection()) {
            write(mark)
            state = SpaceState.NONE
            lastStickyMark = null
        } else {
            val previous = previousChar()
            val leading = previous != null &&
                !previous.isWhitespace() &&
                previous !in OPENING_PUNCTUATION &&
                (state == SpaceState.PHANTOM ||
                    previous.isLetterOrDigit() ||
                    previous in CLOSING_PUNCTUATION)
            write(if (leading) " $mark" else mark)
            state = SpaceState.NONE
            lastStickyMark = null
        }
    }

    /**
     * Typing a single character. PHANTOM becomes a real space before a new letter, including after
     * sentence-ending `. ! ?`; after comma/colon it stays attached, and a following digit is never
     * separated, which keeps `14:30`, `10,5` and `v1.2` intact.
     *
     * @return true when a space was inserted before [text].
     */
    fun commitTyped(text: String): Boolean = edit {
        if (text.isEmpty()) return@edit false
        if (!enabled || editor.hasSelection()) {
            write(text)
            state = SpaceState.NONE
            lastStickyMark = null
            return@edit false
        }
        val previous = previousChar()
        val sentenceBreak = state == SpaceState.PHANTOM &&
            lastStickyMark in SENTENCE_END &&
            text.firstOrNull()?.isLetter() == true
        val leading = sentenceBreak || (
            state == SpaceState.PHANTOM &&
                previous != null &&
                !previous.isWhitespace() &&
                previous !in OPENING_PUNCTUATION &&
                !isPunctuation(previous)
            )
        write(if (leading) " $text" else text)
        state = SpaceState.NONE
        lastStickyMark = null
        leading
    }

    /** `- @ _ / \` stay inside the current token and never add or remove spaces. */
    fun commitSeparator(text: String) = edit {
        write(text)
        state = SpaceState.NONE
        lastStickyMark = null
    }

    /** A manual space; a second consecutive manual space is no longer "weak". */
    fun commitSpace() = edit {
        write(" ")
        state = if (!enabled) {
            SpaceState.NONE
        } else {
            when (state) {
                SpaceState.NONE, SpaceState.PHANTOM -> SpaceState.WEAK
                SpaceState.WEAK -> SpaceState.NONE
            }
        }
        lastStickyMark = null
    }

    /** Pasted/transcribed text: commit verbatim and start a fresh spacing sequence. */
    fun commitRaw(text: String) = edit {
        write(text)
        state = SpaceState.NONE
        lastStickyMark = null
    }

    fun onBackspace() {
        reset()
    }

    fun onEnter() {
        reset()
    }

    /**
     * A cursor move that we did not cause invalidates PHANTOM/WEAK. Callbacks caused by our own
     * commits are recognised by [pendingCursorDelta] (asynchronous delivery) and by a short settle
     * window for editors that report the update before the delta is consumed.
     */
    fun onSelectionChanged(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int
    ) {
        if (editingDepth > 0) return
        // Replacing a space with a punctuation mark keeps the cursor at the same offset. An
        // editor may still report a selection update for it; the cursor did not actually move,
        // so PHANTOM must survive.
        if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return
        if (oldSelStart >= 0 && oldSelStart == oldSelEnd &&
            newSelStart >= 0 && newSelStart == newSelEnd
        ) {
            val delta = newSelStart - oldSelStart
            if (pendingCursorDelta != 0 && delta == pendingCursorDelta) {
                pendingCursorDelta = 0
                lastEditNanos = System.nanoTime()
                return
            }
        }
        if (System.nanoTime() - lastEditNanos < SELECTION_SETTLE_NANOS) return
        pendingCursorDelta = 0
        state = SpaceState.NONE
    }

    private fun write(text: String) {
        editor.commitText(text)
        pendingCursorDelta += text.length
    }

    private fun erase(count: Int) {
        if (count <= 0) return
        editor.deleteBeforeCursor(count)
        pendingCursorDelta -= count
    }

    private fun commitWordLocked(word: String): WordInsertion {
        if (word.isEmpty()) return WordInsertion(false)
        if (!enabled || editor.hasSelection()) {
            write(word)
            state = if (enabled) SpaceState.PHANTOM else SpaceState.NONE
            lastStickyMark = null
            return WordInsertion(false)
        }
        val previous = previousChar()
        val leading = previous != null &&
            !previous.isWhitespace() &&
            previous !in OPENING_PUNCTUATION &&
            (state == SpaceState.PHANTOM ||
                previous.isLetterOrDigit() ||
                previous in CLOSING_PUNCTUATION)
        write(if (leading) " $word" else word)
        state = SpaceState.PHANTOM
        lastStickyMark = null
        return WordInsertion(leading)
    }

    private fun previousChar(): Char? = editor.textBeforeCursor(1)?.firstOrNull()

    private fun isPunctuation(ch: Char): Boolean = !ch.isLetterOrDigit() && !ch.isWhitespace()

    private inline fun <T> edit(block: () -> T): T {
        editingDepth++
        try {
            return block()
        } finally {
            editingDepth--
            lastEditNanos = System.nanoTime()
        }
    }

    companion object {
        private val STICKY_PUNCTUATION = setOf('.', ',', '!', '?', ':', ';', '…', ')', ']', '}', '»')
        private val OPENING_PUNCTUATION = setOf('(', '[', '{', '«')
        private val CLOSING_PUNCTUATION = setOf(')', ']', '}', '»')
        private val WORD_SEPARATORS = setOf('-', '@', '_', '/', '\\')
        private val SENTENCE_END = setOf('.', '!', '?')

        private const val SELECTION_SETTLE_NANOS = 150_000_000L

        fun classify(text: String): CharacterClass {
            val first = text.firstOrNull() ?: return CharacterClass.TYPED
            return when {
                first in STICKY_PUNCTUATION -> CharacterClass.STICKY_PUNCTUATION
                first in OPENING_PUNCTUATION -> CharacterClass.OPENING_PUNCTUATION
                first in WORD_SEPARATORS -> CharacterClass.WORD_SEPARATOR
                else -> CharacterClass.TYPED
            }
        }

        fun allowsAutoSpacing(inputType: Int): Boolean {
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            if (inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE
            ) {
                return false
            }
            if (inputClass != InputType.TYPE_CLASS_TEXT) return true
            return when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
                else -> true
            }
        }
    }
}
