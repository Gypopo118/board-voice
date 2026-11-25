package com.voiceboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoSpacingManagerTest {

    private lateinit var editor: FakeEditor
    private lateinit var spacing: AutoSpacingManager

    @Before
    fun setUp() {
        editor = FakeEditor()
        spacing = AutoSpacingManager(editor)
    }

    // ------------------------------------------------------------------ required scenarios

    @Test
    fun swipeThenSwipeProducesSingleSpaceAndNoTrailingSpace() {
        spacing.commitWord("слово")
        assertState(AutoSpacingManager.SpaceState.PHANTOM)

        spacing.commitWord("слово")

        assertEquals("слово слово", editor.text)
        assertFalse(editor.text.endsWith(" "))
        assertState(AutoSpacingManager.SpaceState.PHANTOM)
    }

    @Test
    fun swipeCommaSwipeBecomesWordCommaSpaceWord() {
        spacing.commitWord("слово")
        spacing.commitStickyPunctuation(",")
        spacing.commitWord("слово")

        assertEquals("слово, слово", editor.text)
    }

    @Test
    fun stickyPunctuationMarksCanFollowEachOtherWithoutSpaces() {
        spacing.commitWord("слово")
        spacing.commitStickyPunctuation("?")
        spacing.commitStickyPunctuation("!")
        spacing.commitStickyPunctuation(".")

        spacing.commitWord("слово")

        assertEquals("слово?!. слово", editor.text)
    }

    @Test
    fun weakSpaceIsRemovedBeforeComma() {
        type("слово")
        spacing.commitSpace()
        assertState(AutoSpacingManager.SpaceState.WEAK)

        spacing.commitStickyPunctuation(",")
        spacing.commitWord("слово")

        assertEquals("слово, слово", editor.text)
    }

    @Test
    fun manualSpaceAfterSwipeAndNextSwipeKeepExactlyOneSpace() {
        spacing.commitWord("слово")
        spacing.commitSpace()
        spacing.commitWord("слово")

        assertEquals("слово слово", editor.text)
    }

    @Test
    fun bracketsKeepInnerTextAttachedAndSpaceAroundTheGroup() {
        spacing.commitWord("слово")
        spacing.commitOpening("(")
        type("текст")
        spacing.commitStickyPunctuation(")")
        spacing.commitWord("слово")

        assertEquals("слово (текст) слово", editor.text)
    }

    @Test
    fun typedTimeAndDecimalAreNotSplitBySpaces() {
        type("14")
        spacing.commitStickyPunctuation(":")
        type("30")
        assertEquals("14:30", editor.text)

        spacing.onEnter()
        spacing.commitRaw("\n")
        type("10")
        spacing.commitStickyPunctuation(",")
        type("5")
        assertEquals("14:30\n10,5", editor.text)
    }

    // ------------------------------------------------------------------ transitions

    @Test
    fun typedLetterAfterSwipeGetsThePhantomSpace() {
        spacing.commitWord("слово")

        val inserted = spacing.commitTyped("п")

        assertTrue(inserted)
        assertEquals("слово п", editor.text)
        assertState(AutoSpacingManager.SpaceState.NONE)
    }

    @Test
    fun typedLetterAfterCommaGetsASpace() {
        spacing.commitWord("слово")
        spacing.commitStickyPunctuation(",")

        val inserted = spacing.commitTyped("п")

        assertTrue(inserted)
        assertEquals("слово, п", editor.text)
    }

    @Test
    fun typedLetterAfterColonOrSemicolonGetsASpace() {
        type("слово")
        spacing.commitStickyPunctuation(":")
        assertTrue(spacing.commitTyped("п"))
        assertEquals("слово: п", editor.text)

        spacing.commitStickyPunctuation(";")
        assertTrue(spacing.commitTyped("с"))
        assertEquals("слово: п; с", editor.text)
    }

    @Test
    fun abbreviationDotsStayInsideTheAbbreviation() {
        type("т")
        spacing.commitStickyPunctuation(".")
        assertFalse(spacing.commitTyped("д"))
        spacing.commitStickyPunctuation(".")
        assertTrue(spacing.commitTyped("с"))

        assertEquals("т.д. с", editor.text)
    }

    @Test
    fun typedLetterAfterClosingBracketGetsASpace() {
        type("слово")
        spacing.commitSpace()
        spacing.commitOpening("(")
        type("текст")
        spacing.commitStickyPunctuation(")")

        val inserted = spacing.commitTyped("д")

        assertTrue(inserted)
        assertEquals("слово (текст) д", editor.text)
    }

    @Test
    fun typedLetterAfterSentenceMarkGetsASpace() {
        spacing.commitWord("слово")
        spacing.commitStickyPunctuation(".")
        assertState(AutoSpacingManager.SpaceState.PHANTOM)

        val inserted = spacing.commitTyped("П")

        assertTrue(inserted)
        assertEquals("слово. П", editor.text)
        assertState(AutoSpacingManager.SpaceState.NONE)
    }

    @Test
    fun typedLetterAfterQuestionOrExclamationGetsASpace() {
        spacing.commitWord("слово")
        spacing.commitStickyPunctuation("?")
        spacing.commitTyped("П")
        spacing.commitStickyPunctuation("!")
        spacing.commitTyped("Д")

        assertEquals("слово? П! Д", editor.text)
    }

    @Test
    fun typedDigitAfterSentenceMarkStaysAttached() {
        spacing.commitWord("version")
        spacing.commitStickyPunctuation(".")

        val inserted = spacing.commitTyped("2")

        assertFalse(inserted)
        assertEquals("version.2", editor.text)
    }

    @Test
    fun typedDigitAfterQuestionMarkGetsASpace() {
        type("сколько")
        spacing.commitStickyPunctuation("?")

        val inserted = spacing.commitTyped("5")

        assertTrue(inserted)
        assertEquals("сколько? 5", editor.text)
    }

    @Test
    fun openingAtTheStartOfTheFieldHasNoLeadingSpace() {
        spacing.commitOpening("(")
        type("текст")

        assertEquals("(текст", editor.text)
    }

    @Test
    fun openingAfterAWordGetsOneLeadingSpace() {
        spacing.commitWord("слово")
        spacing.commitOpening("(")

        assertEquals("слово (", editor.text)
        assertState(AutoSpacingManager.SpaceState.NONE)
    }

    @Test
    fun secondManualSpaceIsStrongAndSurvivesPunctuation() {
        spacing.commitWord("слово")
        spacing.commitSpace()
        assertState(AutoSpacingManager.SpaceState.WEAK)

        spacing.commitSpace()
        assertState(AutoSpacingManager.SpaceState.NONE)

        spacing.commitStickyPunctuation(",")

        assertEquals("слово  ,", editor.text)
    }

    @Test
    fun wordSeparatorsDoNotAddOrRemoveSpaces() {
        type("слово")
        spacing.commitSpace()
        assertState(AutoSpacingManager.SpaceState.WEAK)

        spacing.commitSeparator("-")

        assertEquals("слово -", editor.text)
        assertState(AutoSpacingManager.SpaceState.NONE)
    }

    @Test
    fun stickyPunctuationDropsSmartSpaceLeftByLongPressedPrimary() {
        spacing.commitWord("слово")
        spacing.commitTyped("т")
        // The service removes the primary letter when the long-press secondary wins.
        spacing.replaceWord(1, "")
        assertEquals("слово ", editor.text)

        spacing.commitStickyPunctuation("!", dropLeadingSpace = true)

        assertEquals("слово!", editor.text)
    }

    @Test
    fun swipeReplacementUsesStateFromBeforeStarterLetter() {
        spacing.commitWord("слово")
        spacing.commitStickyPunctuation(",")

        val beforeStarter = spacing.state
        spacing.commitTyped("п")
        spacing.replaceWord(1, "привет", beforeStarter)

        assertEquals("слово, привет", editor.text)
    }

    @Test
    fun swipeReplacementKeepsAnAlreadyPresentSpace() {
        spacing.commitWord("слово")

        val beforeStarter = spacing.state
        spacing.commitTyped("п")
        spacing.replaceWord(1, "привет", beforeStarter)

        assertEquals("слово привет", editor.text)
        assertFalse(editor.text.contains("  "))
    }

    @Test
    fun swipeAfterLetterTypingGetsASpaceWithoutPhantomState() {
        type("слово")
        assertState(AutoSpacingManager.SpaceState.NONE)

        val beforeStarter = spacing.state
        spacing.commitTyped("м")
        spacing.replaceWord(1, "мир", beforeStarter)

        assertEquals("слово мир", editor.text)
        assertFalse(editor.text.contains("  "))
    }

    @Test
    fun wholeWordCommitSeparatesFromTypedLetters() {
        type("слово")

        spacing.commitWord("мир")

        assertEquals("слово мир", editor.text)
        assertState(AutoSpacingManager.SpaceState.PHANTOM)
    }

    @Test
    fun backspaceAndEnterResetTheState() {
        spacing.commitWord("слово")
        assertState(AutoSpacingManager.SpaceState.PHANTOM)
        spacing.onBackspace()
        assertState(AutoSpacingManager.SpaceState.NONE)

        spacing.commitWord("слово")
        assertState(AutoSpacingManager.SpaceState.PHANTOM)
        spacing.onEnter()
        assertState(AutoSpacingManager.SpaceState.NONE)
    }

    @Test
    fun deletingSentenceWordKeepsTheSpaceAfterPunctuation() {
        val editor = FakeEditor("привет. Как")
        val spacing = AutoSpacingManager(editor)
        editor.deleteBeforeCursor(3)
        spacing.onTextDeleted("Как")

        assertFalse(spacing.commitTyped("Н"))
        assertEquals("привет. Н", editor.text)
    }

    @Test
    fun deletingSentenceWordAndItsSpaceRestoresTheSpace() {
        val editor = FakeEditor("привет? Как")
        val spacing = AutoSpacingManager(editor)
        editor.deleteBeforeCursor(4)
        spacing.onTextDeleted(" Как")

        assertTrue(spacing.commitTyped("С"))
        assertEquals("привет? С", editor.text)
    }

    @Test
    fun deletionSelectionCallbackDoesNotWipeTheRestoredState() {
        val editor = FakeEditor("привет мир")
        val spacing = AutoSpacingManager(editor)
        editor.deleteBeforeCursor(4)
        spacing.onTextDeleted(" мир")
        assertEquals(AutoSpacingManager.SpaceState.PHANTOM, spacing.state)

        // The editor reports the direct InputConnection deletion asynchronously.
        spacing.onSelectionChanged(10, 10, 6, 6)

        assertEquals(AutoSpacingManager.SpaceState.PHANTOM, spacing.state)
    }

    @Test
    fun deletingAWordAndItsSeparatorRestoresTheNextSpace() {
        val editor = FakeEditor("привет мир")
        val spacing = AutoSpacingManager(editor)
        editor.deleteBeforeCursor(4)
        spacing.onTextDeleted(" мир")

        assertTrue(spacing.commitTyped("новое"))
        assertEquals("привет новое", editor.text)
    }

    @Test
    fun deletingOnlyASpaceAllowsJoiningWords() {
        val editor = FakeEditor("привет ")
        val spacing = AutoSpacingManager(editor)
        editor.deleteBeforeCursor(1)
        spacing.onTextDeleted(" ")

        assertFalse(spacing.commitTyped("мир"))
        assertEquals("приветмир", editor.text)
    }

    @Test
    fun deletingLettersInsideAWordDoesNotAddASpace() {
        val editor = FakeEditor("привет")
        val spacing = AutoSpacingManager(editor)
        editor.deleteBeforeCursor(1)
        spacing.onTextDeleted("т")

        assertFalse(spacing.commitTyped("и"))
        assertEquals("привеи", editor.text)
    }

    @Test
    fun ownSelectionCallbackDoesNotResetStateButManualMoveDoes() {
        spacing.commitWord("слово")
        assertFalse(spacing.onSelectionChanged(0, 0, 5, 5))
        assertState(AutoSpacingManager.SpaceState.PHANTOM)

        Thread.sleep(180L)
        assertTrue(spacing.onSelectionChanged(5, 5, 0, 0))
        assertState(AutoSpacingManager.SpaceState.NONE)
    }

    @Test
    fun delayedOwnSelectionCallbackStillKeepsState() {
        spacing.commitWord("слово")

        Thread.sleep(200L)
        spacing.onSelectionChanged(0, 0, 5, 5)

        assertState(AutoSpacingManager.SpaceState.PHANTOM)
    }

    @Test
    fun unchangedSelectionAfterSpaceToPunctuationReplacementKeepsState() {
        type("слово")
        spacing.commitSpace()
        spacing.commitStickyPunctuation(",")
        assertEquals("слово,", editor.text)

        Thread.sleep(500L)
        spacing.onSelectionChanged(6, 6, 6, 6)

        assertState(AutoSpacingManager.SpaceState.PHANTOM)
    }

    @Test
    fun disabledFieldNeverAddsSmartSpaces() {
        spacing.setInputType(InputType.TYPE_CLASS_NUMBER)
        assertFalse(spacing.enabled)

        spacing.commitWord("12")
        spacing.commitWord("34")
        spacing.commitStickyPunctuation(",")
        spacing.commitSpace()

        assertEquals("1234, ", editor.text)
        assertState(AutoSpacingManager.SpaceState.NONE)
    }

    @Test
    fun inputTypePolicyDisablesSpacingOnlyForFreeFormSeparatedFields() {
        assertTrue(AutoSpacingManager.allowsAutoSpacing(InputType.TYPE_CLASS_TEXT))
        assertTrue(
            AutoSpacingManager.allowsAutoSpacing(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            )
        )
        assertFalse(
            AutoSpacingManager.allowsAutoSpacing(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
        assertFalse(
            AutoSpacingManager.allowsAutoSpacing(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
        )
        assertFalse(
            AutoSpacingManager.allowsAutoSpacing(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
        assertFalse(
            AutoSpacingManager.allowsAutoSpacing(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        )
        assertFalse(AutoSpacingManager.allowsAutoSpacing(InputType.TYPE_CLASS_NUMBER))
        assertFalse(AutoSpacingManager.allowsAutoSpacing(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun punctuationAndSeparatorsAreClassifiedForTheService() {
        assertEquals(
            AutoSpacingManager.CharacterClass.STICKY_PUNCTUATION,
            AutoSpacingManager.classify(".")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.STICKY_PUNCTUATION,
            AutoSpacingManager.classify("…")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.STICKY_PUNCTUATION,
            AutoSpacingManager.classify("»")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.OPENING_PUNCTUATION,
            AutoSpacingManager.classify("«")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.OPENING_PUNCTUATION,
            AutoSpacingManager.classify("(")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.WORD_SEPARATOR,
            AutoSpacingManager.classify("-")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.WORD_SEPARATOR,
            AutoSpacingManager.classify("\\")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.TYPED,
            AutoSpacingManager.classify("а")
        )
        assertEquals(
            AutoSpacingManager.CharacterClass.TYPED,
            AutoSpacingManager.classify("7")
        )
    }

    // ------------------------------------------------------------------ undo recording

    @Test
    fun recordingCapturesTheSmartSpaceInsertedWithALetter() {
        spacing.commitWord("слово")

        spacing.beginRecording()
        spacing.commitTyped("п")
        val edit = spacing.endRecording()

        assertEquals("" to " п", edit)
        assertEquals("слово п", editor.text)
    }

    @Test
    fun recordingCapturesASpaceRemovedByStickyPunctuation() {
        spacing.commitWord("слово")
        spacing.commitStickyPunctuation("!")
        spacing.commitSpace()

        spacing.beginRecording()
        spacing.commitStickyPunctuation(",")
        val edit = spacing.endRecording()

        assertEquals(" " to ",", edit)
        assertEquals("слово!,", editor.text)
    }

    @Test
    fun recordingCapturesTheStarterLetterReplacedByASwipeWord() {
        spacing.commitWord("слово")
        val stateBeforeStarter = spacing.state

        spacing.beginRecording()
        spacing.commitTyped("п")
        val starter = spacing.endRecording()

        spacing.beginRecording()
        spacing.replaceWord(1, "привет", stateBeforeStarter)
        val replacement = spacing.endRecording()

        assertEquals("" to " п", starter)
        assertEquals("п" to "привет", replacement)
        assertEquals("слово привет", editor.text)
    }

    @Test
    fun replaceRawAppliesTextAndKeepsTheSavedUndoStateRestorable() {
        spacing.commitWord("слово")
        val before = spacing.undoState()

        spacing.replaceRaw(0, " привет")
        assertEquals("слово привет", editor.text)

        spacing.restoreUndoState(before)
        assertState(AutoSpacingManager.SpaceState.PHANTOM)
    }

    // ------------------------------------------------------------------ helpers

    private fun type(text: String) {
        for (char in text) spacing.commitTyped(char.toString())
    }

    private fun assertState(expected: AutoSpacingManager.SpaceState) {
        assertEquals(expected, spacing.state)
    }

    private class FakeEditor(initial: String = "") : AutoSpacingManager.Editor {
        private val buffer = StringBuilder(initial)
        private var cursor = initial.length
        private var selection = false

        val text: String get() = buffer.toString()

        override fun textBeforeCursor(maxChars: Int): String {
            val start = (cursor - maxChars).coerceAtLeast(0)
            return buffer.substring(start, cursor)
        }

        override fun commitText(text: String) {
            if (selection) {
                buffer.clear()
                cursor = 0
                selection = false
            }
            buffer.insert(cursor, text)
            cursor += text.length
        }

        override fun deleteBeforeCursor(count: Int) {
            val start = (cursor - count).coerceAtLeast(0)
            buffer.delete(start, cursor)
            cursor = start
        }

        override fun hasSelection(): Boolean = selection
    }
}
