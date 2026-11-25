package com.voiceboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Full keyboard IME. The canvas owns drawing and raw touch handling; this class translates key
 * intents into [InputConnection] edits. Voice transcription itself lives in
 * [VoiceInputController], untouched by the keyboard work.
 */
class VoiceKeyboardService : InputMethodService(), KeyboardCanvasView.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    /** Persisted across IME process restarts, unlike the old in-memory deque. */
    private val clipboardHistory by lazy {
        ClipboardHistoryStore(
            File(filesDir, CLIPBOARD_HISTORY_FILE),
            MAX_CLIPBOARD_HISTORY
        )
    }
    private val clipboardWatcher =
        ClipboardManager.OnPrimaryClipChangedListener { rememberCurrentClipboard() }
    private val greetingShort by lazy { getString(R.string.greeting_short) }
    private val greetingLong by lazy { getString(R.string.greeting_long) }

    private lateinit var keyboard: KeyboardCanvasView
    private lateinit var voice: VoiceInputController
    private lateinit var prediction: PredictionEngine
    private lateinit var spacing: AutoSpacingManager
    private val swipeEngine = SwipeEngine()

    private var swipeWord: String? = null
    private var swipeCandidates: List<String> = emptyList()
    private var swipeLeadingSpace = false
    private var lastSwipeAt = 0L
    private var inputSession = 0

    /** Selection reported by the editor; the spacebar drag moves the caret inside the text. */
    private var selectionStart = -1
    private var selectionEnd = -1

    /**
     * Connection pinning and batching for one logical insertion. Reactive fields (Compose
     * BasicTextField) may restart the input session while they animate the inserted text; every
     * edit of one insertion must go to the same connection, with nothing landing on a session
     * that is already being replaced.
     */
    private val textEditor = AtomicTextEditor { currentInputConnection }

    /** Text of the last compound insertion; selection callbacks that only confirm it are ours. */
    private var lastInsertedText: String? = null
    private var lastInsertedAt = 0L
    private val caretMover = CaretMover(object : CaretMover.Editor {
        override fun selectionStart(): Int = selectionStart

        override fun selectionEnd(): Int = selectionEnd

        override fun applySelection(start: Int, end: Int): Boolean {
            val connection = currentInputConnection ?: return false
            return try {
                // A direct selection change keeps the field focused: unlike an arrow key it can
                // never reach Android's focus navigation when the caret sits at a text edge.
                connection.setSelection(start, end)
            } catch (_: Exception) {
                false
            }
        }

        override fun textBeforeCursor(maxChars: Int): String? = try {
            currentInputConnection?.getTextBeforeCursor(maxChars, 0)?.toString()
        } catch (_: Exception) {
            null
        }

        override fun textAfterCursor(maxChars: Int): String? = try {
            currentInputConnection?.getTextAfterCursor(maxChars, 0)?.toString()
        } catch (_: Exception) {
            null
        }

        override fun sendArrowKey(forward: Boolean) {
            val connection = currentInputConnection ?: return
            val keyCode = if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            try {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (_: Exception) {
                // A dying InputConnection must not crash the keyboard.
            }
        }
    })

    /** State before a swipe's starter letter was typed; restored when the swipe result arrives. */
    private var spacingBeforeLetter = AutoSpacingManager.SpaceState.NONE
    private var starterLetterInsertedSpace = false

    private var currentWord = ""
    private var wordRaw = ""
    private var currentPreviousWord: String? = null
    private var russianLayout = true
    private var caps = false
    private var oneShotShift = false
    private var lastShiftAt = 0L

    private var suggestionJob: Job? = null
    private var backspaceHeld = false
    private var deleteRepeatStarted = 0L
    private var spaceDragApplied = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val editHistory = EditHistory()
    private var pendingLetterEntry: EditHistory.Entry? = null
    private var magicJob: Job? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prediction = PredictionEngine(this, scope).also { engine ->
            engine.load { if (::keyboard.isInitialized) updateSuggestions() }
        }
        spacing = AutoSpacingManager(object : AutoSpacingManager.Editor {
            override fun textBeforeCursor(maxChars: Int): String? = try {
                activeConnection()?.getTextBeforeCursor(maxChars, 0)?.toString()
            } catch (_: Exception) {
                null
            }

            override fun commitText(text: String) {
                try {
                    activeConnection()?.commitText(text, 1)
                } catch (_: Exception) {
                    // A dying connection is handled by the verification of the compound edit.
                }
            }

            override fun deleteBeforeCursor(count: Int) {
                if (count <= 0) return
                try {
                    activeConnection()?.deleteSurroundingText(count, 0)
                } catch (_: Exception) {
                }
            }

            override fun hasSelection(): Boolean = try {
                activeConnection()?.getSelectedText(0).isNullOrEmpty() == false
            } catch (_: Exception) {
                false
            }
        })
        voice = VoiceInputController(
            this,
            scope,
            { currentInputConnection },
            ::voiceState,
            ::message,
            ::openSettings,
            ::onVoiceCommitted
        )
        clipboardManager.addPrimaryClipChangedListener(clipboardWatcher)
    }

    override fun onCreateInputView(): View {
        keyboard = KeyboardCanvasView(this).apply {
            listener = this@VoiceKeyboardService
            setLanguage(russianLayout)
            setShift(oneShotShift, caps)
            swipeEnabled = gestureTypingAllowed(currentInputEditorInfo)
            voiceActive = voice.state != VoiceInputController.State.IDLE
        }
        configureSystemBars()
        return keyboard
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        selectionStart = info?.initialSelStart ?: -1
        selectionEnd = info?.initialSelEnd ?: -1
        if (!::keyboard.isInitialized) return
        inputSession++
        magicJob?.cancel()
        magicJob = null
        if (voice.state == VoiceInputController.State.IDLE) keyboard.showStatus(null)
        keyboard.swipeEnabled = gestureTypingAllowed(info)
        spacing.setInputType(info?.inputType)
        if (!caps) {
            val before = try {
                currentInputConnection?.getTextBeforeCursor(AUTO_CAP_LOOKBACK, 0)?.toString()
            } catch (_: Exception) {
                null
            }
            oneShotShift = before != null &&
                autoCapAllowed(info) &&
                AutoCapPolicy.shouldCapitalizeAfter(before)
            lastShiftAt = 0L
        }
        keyboard.setShift(oneShotShift, caps)
        editHistory.clear()
        pendingLetterEntry = null
        clearWordState()
        refreshWord()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputSession++
        selectionStart = -1
        selectionEnd = -1
        lastInsertedText = null
        stopBackspaceRepeat()
        magicJob?.cancel()
        magicJob = null
        editHistory.clear()
        pendingLetterEntry = null
        clearWordState()
        spacing.reset()
        voice.cancel(notify = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopBackspaceRepeat()
        voice.dispose()
        try {
            clipboardManager.removePrimaryClipChangedListener(clipboardWatcher)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        releaseWakefulness()
        if (::prediction.isInitialized) prediction.flushUserWords()
        scope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onWindowShown() {
        super.onWindowShown()
        configureSystemBars()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Negative values mean the editor dropped the connection; the next input restores them.
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (!::keyboard.isInitialized || newSelStart < 0 || newSelEnd < 0) return
        val manualMove = spacing.onSelectionChanged(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
        // A reactive field reports the caret moving to the end of the word the keyboard itself
        // just inserted. Treat that callback as our own edit, not as a manual cursor move: resetting
        // the word state here would drop the suggestion panel that belongs to the new word.
        if (isOwnInsertionSelection(newSelStart, newSelEnd)) {
            refreshWord()
            return
        }
        if (newSelStart != newSelEnd) {
            spacing.reset()
            clearWordState()
        } else {
            refreshWord()
            if (manualMove) refreshAutoShiftFromContext()
        }
    }

    // ------------------------------------------------------------------ keyboard callbacks

    override fun onKeyDown(key: KeyboardCanvasView.Key) {
        when (key.action) {
            KeyboardCanvasView.Action.LETTER -> typeLetter(key)
            KeyboardCanvasView.Action.COMMA -> recordEdit { insertPunctuation(",") }
            KeyboardCanvasView.Action.PERIOD -> recordEdit { insertPunctuation(".") }
            KeyboardCanvasView.Action.BACKSPACE -> {
                deleteOnce()
                startBackspaceRepeat()
            }
            KeyboardCanvasView.Action.SHIFT -> pressShift()
            else -> Unit
        }
    }

    override fun onKeyAction(key: KeyboardCanvasView.Key) {
        when (key.action) {
            KeyboardCanvasView.Action.SPACE -> recordEdit { commitSpace() }
            KeyboardCanvasView.Action.SYMBOLS -> {
                keyboard.toggleSymbols()
                if (keyboard.symbols) {
                    clearWordState()
                } else {
                    refreshWord()
                    updateSuggestions()
                }
            }
            KeyboardCanvasView.Action.LANGUAGE -> {
                russianLayout = !russianLayout
                keyboard.setLanguage(russianLayout)
                clearWordState()
            }
            KeyboardCanvasView.Action.ENTER -> recordEdit { pressEnter() }
            KeyboardCanvasView.Action.UNDO -> performUndo()
            KeyboardCanvasView.Action.REDO -> performRedo()
            KeyboardCanvasView.Action.AC -> clearAll()
            KeyboardCanvasView.Action.CLIPBOARD -> {
                keyboard.setClipboard(readClipboard())
                keyboard.toggleClipboard()
            }
            KeyboardCanvasView.Action.MAGIC -> fixTextWithMagic()
            KeyboardCanvasView.Action.GREETING -> insertGreeting(greetingShort)
            KeyboardCanvasView.Action.MIC -> {
                magicJob?.cancel()
                magicJob = null
                voice.toggle()
            }
            else -> Unit
        }
    }

    override fun onKeyLongPress(key: KeyboardCanvasView.Key) {
        when {
            key.action == KeyboardCanvasView.Action.AC -> openSettings()
            key.action == KeyboardCanvasView.Action.LANGUAGE -> showInputMethodPicker()
            key.action == KeyboardCanvasView.Action.BACKSPACE -> Unit
            key.action == KeyboardCanvasView.Action.GREETING -> insertGreeting(greetingLong)
            key.secondary.isNotEmpty() -> commitSecondary(key)
        }
    }

    override fun onKeyUp(key: KeyboardCanvasView.Key) {
        if (key.action == KeyboardCanvasView.Action.BACKSPACE) stopBackspaceRepeat()
    }

    // ------------------------------------------------------------------ cursor drag over space

    override fun onSpaceDragStart() {
        spaceDragApplied = 0
        spacing.reset()
    }

    override fun onSpaceDrag(totalDx: Float) {
        val step = dp(10f)
        if (step <= 0f) return
        val desired = (totalDx / step).toInt()
        val difference = (desired - spaceDragApplied)
            .coerceIn(-MAX_SPACE_DRAG_STEPS, MAX_SPACE_DRAG_STEPS)
        if (difference == 0) return
        // The applied counter follows the finger even when the caret already sits at an edge of
        // the text, so dragging back moves the caret immediately instead of replaying the steps
        // that could not move it.
        spaceDragApplied = desired
        val moved = caretMover.move(difference)
        if (moved >= 0) {
            selectionStart = moved
            selectionEnd = moved
        }
        refreshWord()
    }

    override fun onSpaceDragEnd() {
        spaceDragApplied = 0
        refreshWord()
    }

    override fun onSuggestion(word: String) {
        val swipe = currentSwipeContext()
        val typed = if (swipe != null) swipe.raw else readTrailingWord()
        val chosen = caseSuggestion(word, typed)
        val stateBefore = spacing.state

        // Delete the draft and commit the chosen word as one batch on one connection snapshot.
        // Compose fields may restart the input session while the text animates; a separate
        // deleteSurroundingText() + commitText() was the pair that could straddle that restart.
        val insertion = compoundEdit { connection ->
            if (!connection.getSelectedText(0).isNullOrEmpty()) return@compoundEdit null
            val draft = textBefore(connection, typed.length)
            val draftPresent = typed.isEmpty() || draft.equals(typed, ignoreCase = true)
            val start = beginHistoryEdit()
            try {
                if (draftPresent) {
                    spacing.replaceWord(typed.length, chosen, stateBefore)
                } else {
                    // Compose restarted the session and dropped the draft. The tap is a deliberate
                    // choice: insert the picked word with smart spacing instead of losing it.
                    spacing.commitWord(chosen)
                }
            } finally {
                consumeOneShotShift()
                finishHistoryEdit(start)
            }
        } ?: return

        verifyReplacement(
            expected = (if (insertion.leadingSpace) " " else "") + chosen,
            word = chosen,
            replacedCount = typed.length,
            replacedWord = typed,
            stateBefore = stateBefore
        )
        prediction.recordSelection(chosen, russianLayout)

        if (swipe != null) {
            wordRaw = chosen
            currentWord = chosen.lowercase(Locale.ROOT)
            swipeWord = chosen.lowercase(Locale.ROOT)
            swipeLeadingSpace = swipe.leadingSpace || insertion.leadingSpace
            lastSwipeAt = SystemClock.uptimeMillis()
            if (::keyboard.isInitialized) keyboard.suggestions = swipeCandidates
        } else {
            // Keep the committed word as the current token: the selection callback that follows the
            // commit must not immediately reopen T9 suggestions for the word the user just chose.
            suggestionJob?.cancel()
            clearSwipeState()
            wordRaw = chosen
            currentWord = chosen.lowercase(Locale.ROOT)
            if (::keyboard.isInitialized) keyboard.suggestions = emptyList()
        }
    }

    override fun onClipboard(text: String) {
        if (compoundEdit {
                recordEdit {
                    spacing.commitRaw(text)
                    refreshAutoShiftFromContext()
                }
            } == null
        ) {
            return
        }
        rememberClipboard(text)
        clearWordState()
    }

    override fun onVoiceCancel() {
        voice.cancel(notify = false)
    }

    override fun onSwipe(
        path: FloatArray,
        startKey: KeyboardCanvasView.Key,
        layout: SwipeLayout
    ): Boolean = onSwipe(SwipeGesture(path), startKey, layout)

    override fun onSwipe(
        gesture: SwipeGesture,
        startKey: KeyboardCanvasView.Key,
        layout: SwipeLayout
    ): Boolean {
        if (!::keyboard.isInitialized || !keyboard.swipeEnabled || keyboard.symbols) return false
        val language = layout.russian
        val lexicon = prediction.swipeLexicon(language)
        if (lexicon == null) {
            keyboard.showStatus(getString(R.string.status_dictionary_loading), false, 1_400L)
            return false
        }
        val startSession = inputSession
        val startPackage = currentInputEditorInfo?.packageName
        scope.launch {
            val results = withContext(Dispatchers.Default) {
                swipeEngine.decode(gesture, layout, lexicon, 3)
            }
            // Deliberately no inputSession check here: a Compose field may restart its input
            // session while it animates the text. insertSwipeResult asks the fresh
            // currentInputConnection and verifies the draft (or the same field after a restart).
            if (!::keyboard.isInitialized ||
                !keyboard.swipeEnabled || keyboard.symbols || keyboard.russian != language
            ) {
                return@launch
            }
            if (results.isEmpty()) {
                keyboard.showStatus(getString(R.string.swipe_not_recognized), false, 1_400L)
                return@launch
            }
            try {
                insertSwipeResult(
                    results,
                    startKey,
                    sessionChanged = startSession != inputSession,
                    startPackage = startPackage
                )
            } catch (_: Exception) {
                // A dying InputConnection must not crash the keyboard; the starter letter stays.
            }
        }
        return true
    }

    private data class SwipeInsertion(
        val cased: String,
        val topWord: String,
        val firstIsUpper: Boolean,
        val insertion: AutoSpacingManager.WordInsertion,
        val stateBeforeStarter: AutoSpacingManager.SpaceState,
        val starterSpace: Boolean
    )

    private fun insertSwipeResult(
        results: List<SwipeEngine.Result>,
        startKey: KeyboardCanvasView.Key,
        sessionChanged: Boolean,
        startPackage: String?
    ) {
        val startLetter = startKey.label[0].lowercaseChar()
        val sameField = startPackage != null && currentInputEditorInfo?.packageName == startPackage
        // One batch, one connection snapshot: Compose fields must not observe the starter letter
        // deleted without its replacement already committed.
        val applied = compoundEdit { connection ->
            if (!connection.getSelectedText(0).isNullOrEmpty()) return@compoundEdit null
            val firstRaw = textBefore(connection, 1)
            val starterPresent =
                firstRaw.length == 1 && firstRaw[0].lowercaseChar() == startLetter
            if (!starterPresent) {
                // A reactive field may rebuild its text during the insertion animation and drop
                // even the starter letter. If this is still the same field, insert the decoded
                // word instead of leaving the user with an empty input box.
                if (!sessionChanged || !sameField) return@compoundEdit null
                val context = textBefore(connection, AUTO_CAP_LOOKBACK)
                val capitalize = AutoCapPolicy.shouldCapitalizeAfter(context)
                val topWord = results.first().word
                val cased = SwipeTextPolicy.caseWord(
                    prediction.displayWord(topWord, russianLayout), caps, capitalize
                )
                val stateBefore = spacing.state
                val start = beginHistoryEdit()
                val insertion = try {
                    spacing.commitWord(cased)
                } finally {
                    consumeOneShotShift()
                    finishHistoryEdit(start)
                }
                pendingLetterEntry = null
                return@compoundEdit SwipeInsertion(
                    cased, topWord, capitalize, insertion, stateBefore, false
                )
            }

            val firstIsUpper = firstRaw[0].isUpperCase()
            val topWord = results.first().word
            val cased = SwipeTextPolicy.caseWord(
                prediction.displayWord(topWord, russianLayout), caps, firstIsUpper
            )
            val stateBeforeStarter = spacingBeforeLetter
            val starterSpace = starterLetterInsertedSpace

            val start = beginHistoryEdit()
            val insertion = try {
                spacing.replaceWord(1, cased, stateBeforeStarter)
            } finally {
                val replacementEntry = finishHistoryEdit(start)
                // The starter letter was recorded as a normal key press on touch-down. Merge the
                // swipe replacement into it so one undo removes the whole swiped word.
                val starterEntry = pendingLetterEntry
                if (starterEntry != null && replacementEntry != null) {
                    editHistory.replaceLast(
                        starterEntry,
                        EditHistory.combine(starterEntry, replacementEntry)
                    )
                }
                pendingLetterEntry = null
            }
            SwipeInsertion(
                cased, topWord, firstIsUpper, insertion, stateBeforeStarter, starterSpace
            )
        } ?: return

        val expected = (if (applied.insertion.leadingSpace) " " else "") + applied.cased
        if (!tailMatches(expected)) {
            // The field restarted its input session before the word landed. Retry once on the
            // fresh connection, recovering from whatever is actually in front of the caret.
            compoundEdit { connection ->
                if (tailMatches(expected, connection)) return@compoundEdit
                val tail = textBefore(connection, expected.length + 1)
                if (tail.endsWith(expected, ignoreCase = true)) return@compoundEdit
                val last = textBefore(connection, 1)
                if (last.length == 1 && last[0].lowercaseChar() == startLetter) {
                    spacing.replaceWord(1, applied.cased, applied.stateBeforeStarter)
                } else if (!tail.endsWith(applied.cased, ignoreCase = true)) {
                    spacing.commitWord(applied.cased)
                }
            }
        }
        markInserted(applied.cased)

        suggestionJob?.cancel()
        wordRaw = applied.cased
        currentWord = applied.cased.lowercase(Locale.ROOT)
        swipeWord = applied.topWord.lowercase(Locale.ROOT)
        swipeCandidates = results.map {
            SwipeTextPolicy.caseWord(
                prediction.displayWord(it.word, russianLayout), caps, applied.firstIsUpper
            )
        }
        swipeLeadingSpace = applied.insertion.leadingSpace || applied.starterSpace
        lastSwipeAt = SystemClock.uptimeMillis()
        if (::keyboard.isInitialized) keyboard.suggestions = swipeCandidates
    }

    // ------------------------------------------------------------------ atomic text insertion

    private fun activeConnection(): InputConnection? = textEditor.active()

    /**
     * Runs [block] as one atomic edit on a single connection snapshot inside
     * beginBatchEdit()/endBatchEdit(). See [AtomicTextEditor] for why a reactive field needs it.
     */
    private fun <T> compoundEdit(block: (InputConnection) -> T): T? = textEditor.run(block)

    private fun textBefore(connection: InputConnection?, maxChars: Int): String =
        textEditor.textBefore(maxChars, connection)

    /** True when the text before the caret ends with [expected], ignoring case. */
    private fun tailMatches(
        expected: String,
        connection: InputConnection? = activeConnection()
    ): Boolean = textEditor.tailMatches(expected, connection)

    private fun markInserted(word: String) {
        lastInsertedText = word
        lastInsertedAt = SystemClock.uptimeMillis()
    }

    /**
     * After a swipe/suggestion replace, a reactive field may still report the caret moving to the
     * end of the word we just wrote. Such a callback is ours, not a manual move: it must not reset
     * the word state or the suggestion panel.
     */
    private fun isOwnInsertionSelection(start: Int, end: Int): Boolean {
        if (start != end) return false
        val word = lastInsertedText ?: return false
        if (word.isEmpty()) return false
        if (SystemClock.uptimeMillis() - lastInsertedAt > OWN_SELECTION_WINDOW_MS) return false
        val tail = textBefore(activeConnection(), word.length)
        return tail.length == word.length && tail.equals(word, ignoreCase = true)
    }

    /**
     * Verifies a word replacement and retries once when a reactive field dropped it. The retry
     * checks what is actually in front of the caret first, so a word that did land is never
     * inserted twice.
     */
    private fun verifyReplacement(
        expected: String,
        word: String,
        replacedCount: Int,
        replacedWord: String,
        stateBefore: AutoSpacingManager.SpaceState
    ) {
        if (tailMatches(expected)) {
            markInserted(word)
            return
        }
        compoundEdit { connection ->
            if (tailMatches(expected, connection)) return@compoundEdit
            val tail = textBefore(connection, expected.length + replacedCount)
            if (tail.endsWith(expected, ignoreCase = true)) return@compoundEdit
            val typed = textBefore(connection, replacedCount)
            if (replacedCount > 0 && typed.equals(replacedWord, ignoreCase = true)) {
                // The draft survived but the replacement did not: repeat it on the fresh session.
                spacing.replaceWord(replacedCount, word, stateBefore)
            } else if (!tail.endsWith(word, ignoreCase = true)) {
                // The field dropped the draft entirely: append the word with smart spacing.
                spacing.commitWord(word)
            }
        }
        if (tailMatches(expected)) markInserted(word)
    }

    // ------------------------------------------------------------------ edit history

    private data class HistoryStart(
        val selected: String,
        val context: String,
        val spacing: AutoSpacingManager.UndoState,
        val oneShotShift: Boolean,
        val caps: Boolean
    )

    /** Captures the editor state before one logical edit and starts recording its exact delta. */
    private fun beginHistoryEdit(): HistoryStart? {
        val connection = activeConnection() ?: return null
        val selected = try {
            connection.getSelectedText(0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val context = if (selected.isEmpty()) {
            try {
                connection.getTextBeforeCursor(HISTORY_CONTEXT, 0)?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        spacing.beginRecording()
        return HistoryStart(selected, context, spacing.undoState(), oneShotShift, caps)
    }

    private fun finishHistoryEdit(start: HistoryStart?): EditHistory.Entry? {
        if (start == null) return null
        val (recordedRemoved, inserted) = spacing.endRecording()
        val removed = if (start.selected.isNotEmpty()) start.selected else recordedRemoved
        if (removed.isEmpty() && inserted.isEmpty()) return null
        val context = if (start.selected.isNotEmpty()) {
            ""
        } else {
            start.context.dropLast(recordedRemoved.length).takeLast(HISTORY_CONTEXT)
        }
        return editHistory.record(
            EditHistory.Entry(
                context = context,
                removed = removed,
                inserted = inserted,
                before = start.spacing,
                after = spacing.undoState(),
                oneShotShiftBefore = start.oneShotShift,
                capsBefore = start.caps,
                oneShotShiftAfter = oneShotShift,
                capsAfter = caps
            )
        )
    }

    private inline fun recordEdit(block: () -> Unit) {
        val start = beginHistoryEdit()
        try {
            block()
        } finally {
            finishHistoryEdit(start)
        }
    }

    private fun typeLetter(key: KeyboardCanvasView.Key) {
        val start = beginHistoryEdit()
        spacingBeforeLetter = spacing.state
        val abbreviationContinuation = key.label.length == 1 &&
            key.label[0].isLetter() &&
            start != null &&
            AutoCapPolicy.isAbbreviationContinuation(start.context, key.label[0])
        if (abbreviationContinuation && !caps) {
            // The dot before this letter is internal to т.д./т.п./т.е., not a sentence end.
            oneShotShift = false
            lastShiftAt = 0L
            if (::keyboard.isInitialized) keyboard.setShift(false, false)
        }
        val insertedSpace = try {
            commitCharacter(key.label)
        } finally {
            pendingLetterEntry = finishHistoryEdit(start)
        }
        starterLetterInsertedSpace = insertedSpace
    }

    private fun performUndo() {
        magicJob?.cancel()
        magicJob = null
        if (::keyboard.isInitialized && voice.state == VoiceInputController.State.IDLE) {
            keyboard.showStatus(null)
        }
        val entry = editHistory.peekUndo() ?: return
        if (!applyHistoryStep(entry, undo = true)) return
        editHistory.undoApplied()
        pendingLetterEntry = null
        afterHistoryChange()
    }

    private fun performRedo() {
        magicJob?.cancel()
        magicJob = null
        if (::keyboard.isInitialized && voice.state == VoiceInputController.State.IDLE) {
            keyboard.showStatus(null)
        }
        val entry = editHistory.peekRedo() ?: return
        if (!applyHistoryStep(entry, undo = false)) return
        editHistory.redoApplied()
        pendingLetterEntry = null
        afterHistoryChange()
    }

    /**
     * Replaces [oldText] with [newText] only when the editor still contains the expected tail.
     * This keeps an undo from cutting text after the user moved the cursor or another app edited
     * the field in the meantime.
     */
    private fun applyHistoryStep(entry: EditHistory.Entry, undo: Boolean): Boolean {
        val connection = currentInputConnection ?: return false
        val hasSelection = try {
            !connection.getSelectedText(0).isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
        if (hasSelection) return false

        val oldText = if (undo) entry.inserted else entry.removed
        val newText = if (undo) entry.removed else entry.inserted
        val expected = entry.context + oldText
        if (expected.isNotEmpty()) {
            val current = try {
                connection.getTextBeforeCursor(expected.length, 0)?.toString()
            } catch (_: Exception) {
                null
            }
            if (current != expected) return false
        }
        try {
            spacing.replaceRaw(oldText.length, newText)
        } catch (_: Exception) {
            return false
        }
        val state = if (undo) entry.before else entry.after
        if (state != null) spacing.restoreUndoState(state)
        oneShotShift = if (undo) entry.oneShotShiftBefore else entry.oneShotShiftAfter
        caps = if (undo) entry.capsBefore else entry.capsAfter
        lastShiftAt = 0L
        if (::keyboard.isInitialized) keyboard.setShift(oneShotShift, caps)
        return true
    }

    private fun afterHistoryChange() {
        suggestionJob?.cancel()
        clearWordState()
        refreshWord()
        if (::keyboard.isInitialized && voice.state == VoiceInputController.State.IDLE) {
            keyboard.showStatus(null)
        }
    }


    // ------------------------------------------------------------------ text editing

    /** Routes one key label through the spacing state machine. */
    private fun commitCharacter(raw: String): Boolean =
        when (AutoSpacingManager.classify(raw)) {
            AutoSpacingManager.CharacterClass.STICKY_PUNCTUATION -> {
                insertPunctuation(raw)
                false
            }
            AutoSpacingManager.CharacterClass.OPENING_PUNCTUATION -> {
                spacing.commitOpening(raw)
                refreshWord()
                false
            }
            AutoSpacingManager.CharacterClass.WORD_SEPARATOR -> {
                spacing.commitSeparator(raw)
                consumeOneShotShift()
                refreshWord()
                false
            }
            AutoSpacingManager.CharacterClass.TYPED -> {
                val inserted = spacing.commitTyped(applyCase(raw))
                consumeOneShotShift()
                refreshWord()
                inserted
            }
        }

    /** Commits an already-cased secondary character (long-press) without consuming Shift again. */
    private fun commitComposed(text: String, dropLeadingSpace: Boolean = false) {
        when (AutoSpacingManager.classify(text)) {
            AutoSpacingManager.CharacterClass.STICKY_PUNCTUATION ->
                insertPunctuation(text, dropLeadingSpace)
            AutoSpacingManager.CharacterClass.OPENING_PUNCTUATION -> {
                spacing.commitOpening(text)
                refreshWord()
            }
            AutoSpacingManager.CharacterClass.WORD_SEPARATOR -> {
                spacing.commitSeparator(text)
                refreshWord()
            }
            AutoSpacingManager.CharacterClass.TYPED -> {
                spacing.commitTyped(text)
                refreshWord()
            }
        }
    }

    private fun consumeOneShotShift() {
        if (oneShotShift && !caps) {
            oneShotShift = false
            if (::keyboard.isInitialized) keyboard.setShift(false, false)
        }
    }

    private fun applyCase(value: String): String =
        if (caps || oneShotShift) value.uppercase(Locale.ROOT) else value.lowercase(Locale.ROOT)

    private fun pressShift() {
        val now = SystemClock.uptimeMillis()
        when {
            caps -> {
                caps = false
                oneShotShift = false
                lastShiftAt = 0L
            }
            now - lastShiftAt < DOUBLE_TAP_MILLIS -> {
                caps = true
                oneShotShift = false
                lastShiftAt = now
            }
            else -> {
                oneShotShift = !oneShotShift
                lastShiftAt = now
            }
        }
        if (::keyboard.isInitialized) keyboard.setShift(oneShotShift, caps)
    }

    /**
     * Sticky punctuation never gets a leading space; a manual (weak) space before it is removed.
     * The next word will receive the phantom space instead: `слово, слово`.
     */
    private fun insertPunctuation(mark: String, dropLeadingSpace: Boolean = false) {
        // Sticky punctuation can withdraw the weak space and then write the mark: one batch.
        if (compoundEdit {
                if (activeConnection() == null) return@compoundEdit
                learnCurrentWord()
                spacing.commitStickyPunctuation(mark, dropLeadingSpace)
            } == null
        ) {
            return
        }
        clearWordState()
        refreshAutoShiftFromContext()
    }

    private fun autoCapAllowed(info: EditorInfo?): Boolean =
        info != null && AutoCapPolicy.allows(info.inputType)

    /**
     * Greeting button: inserts a ready-made phrase. The first letter follows the auto-cap context
     * (field start or after `. ! ? …`), the smart spacing decides whether a leading space is
     * needed, and the insertion stays one undoable action.
     */
    private fun insertGreeting(base: String) {
        val expected = compoundEdit { connection ->
            val text = greetingText(base, textBefore(connection, AUTO_CAP_LOOKBACK))
            recordEdit {
                spacing.commitRaw(text)
                // Recompute Shift inside the recorded edit so undo/redo restores the right case state.
                refreshAutoShiftFromContext()
            }
            text
        } ?: return
        if (!tailMatches(expected)) {
            compoundEdit { connection ->
                // Recompute against what is actually in the field after the session restart.
                val text = greetingText(base, textBefore(connection, AUTO_CAP_LOOKBACK))
                if (!tailMatches(text, connection)) spacing.commitRaw(text)
            }
        }
        markInserted(expected.trimStart())
        clearWordState()
    }

    /** The greeting with the case and the optional leading space taken from [context]. */
    private fun greetingText(base: String, context: String): String {
        val phrase = GreetingPolicy.forCursor(base, context)
        val prefix = if (GreetingPolicy.needsLeadingSpace(context, phrase)) " " else ""
        return prefix + phrase
    }

    private fun commitSpace() {
        if (activeConnection() == null) return
        learnCurrentWord()
        spacing.commitSpace()
        clearWordState()
    }

    /**
     * The word just finished (space, punctuation, Enter) joins the personal dictionary. Disabled
     * for e-mail, URI, password and numeric fields where the shared spacing policy is off.
     */
    private fun learnCurrentWord() {
        if (!spacing.enabled) return
        val word = currentWord
        if (word.length < 2) return
        for (letter in word) if (!letter.isLetter()) return
        prediction.learnWord(word, russianLayout)
    }

    private fun pressEnter() {
        val connection = activeConnection() ?: return
        learnCurrentWord()
        spacing.onEnter()
        val info = currentInputEditorInfo
        val multiline = info != null && (
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                (info.inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
            )
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (multiline || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            spacing.commitRaw("\n")
            clearWordState()
            refreshAutoShiftFromContext()
            return
        }
        connection.performEditorAction(action)
        clearWordState()
    }

    /**
     * Recomputes the automatic Shift from the text before the cursor. This replaces the old
     * one-character check: trailing spaces and closing quotes are skipped, abbreviation endings are
     * not treated as sentence ends, and a stale Shift is cleared when the period was deleted.
     */
    private fun refreshAutoShiftFromContext() {
        if (caps) return
        val connection = activeConnection() ?: return
        if (!autoCapAllowed(currentInputEditorInfo)) {
            if (oneShotShift) {
                oneShotShift = false
                lastShiftAt = 0L
                if (::keyboard.isInitialized) keyboard.setShift(false, caps)
            }
            return
        }
        val before = try {
            connection.getTextBeforeCursor(AUTO_CAP_LOOKBACK, 0)?.toString()
        } catch (_: Exception) {
            null
        }
        val desired = AutoCapPolicy.shouldCapitalizeAfter(before)
        if (desired == oneShotShift) return
        oneShotShift = desired
        lastShiftAt = 0L
        if (::keyboard.isInitialized) keyboard.setShift(oneShotShift, caps)
    }

    private data class DeletionWindow(val text: String, val context: String)

    /** Reads exactly what a backspace is about to delete plus the unchanged prefix before it. */
    private fun deletionWindow(connection: InputConnection, count: Int): DeletionWindow {
        if (count <= 0) return DeletionWindow("", "")
        val window = try {
            connection.getTextBeforeCursor(HISTORY_CONTEXT + count, 0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val text = if (window.length <= count) window else window.substring(window.length - count)
        val context = if (window.length <= count) {
            ""
        } else {
            window.substring(0, window.length - count)
        }
        return DeletionWindow(text, context)
    }

    private fun recordDeletion(
        removed: String,
        context: String,
        beforeState: AutoSpacingManager.UndoState,
        shiftBefore: Boolean,
        capsBefore: Boolean
    ) {
        if (removed.isEmpty()) return
        editHistory.record(
            EditHistory.Entry(
                context = context.takeLast(HISTORY_CONTEXT),
                removed = removed,
                inserted = "",
                before = beforeState,
                after = spacing.undoState(),
                oneShotShiftBefore = shiftBefore,
                capsBefore = capsBefore,
                oneShotShiftAfter = oneShotShift,
                capsAfter = caps
            )
        )
    }

    private fun deleteOnce() {
        val connection = activeConnection() ?: return
        val beforeState = spacing.undoState()
        val shiftBefore = oneShotShift
        val capsBefore = caps
        spacing.onBackspace()
        val selection = try {
            connection.getSelectedText(0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (selection.isNotEmpty()) {
            connection.commitText("", 1)
            spacing.onTextDeleted(selection)
            refreshWord()
            refreshAutoShiftFromContext()
            recordDeletion(selection, "", beforeState, shiftBefore, capsBefore)
            return
        }
        if (deleteSwipeWordIfAny(connection, beforeState, shiftBefore, capsBefore)) return
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val count = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
        val window = deletionWindow(connection, count)
        connection.deleteSurroundingText(count, 0)
        spacing.onTextDeleted(window.text)
        refreshWord()
        refreshAutoShiftFromContext()
        recordDeletion(window.text, window.context, beforeState, shiftBefore, capsBefore)
    }

    /**
     * A single Backspace immediately after gesture typing removes the whole swiped word (and the
     * smart space we inserted before it), not just the last letter.
     */
    private fun deleteSwipeWordIfAny(
        connection: InputConnection,
        beforeState: AutoSpacingManager.UndoState,
        shiftBefore: Boolean,
        capsBefore: Boolean
    ): Boolean {
        val target = swipeWord ?: return false
        if (SystemClock.uptimeMillis() - lastSwipeAt > SWIPE_BACKSPACE_WINDOW_MS) {
            clearSwipeState()
            return false
        }
        val context = currentSwipeContext() ?: return false
        val count = SwipeTextPolicy.backspaceDeleteCount(
            context.raw, target, context.leadingSpace
        ) ?: return false
        val window = deletionWindow(connection, count)
        connection.deleteSurroundingText(count, 0)
        spacing.onTextDeleted(window.text)
        clearWordState()
        refreshWord()
        refreshAutoShiftFromContext()
        recordDeletion(window.text, window.context, beforeState, shiftBefore, capsBefore)
        return true
    }

    private fun deleteWord() {
        val connection = activeConnection() ?: return
        val beforeState = spacing.undoState()
        val shiftBefore = oneShotShift
        val capsBefore = caps
        spacing.onBackspace()
        val selection = try {
            connection.getSelectedText(0)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (selection.isNotEmpty()) {
            connection.commitText("", 1)
            spacing.onTextDeleted(selection)
            refreshWord()
            refreshAutoShiftFromContext()
            recordDeletion(selection, "", beforeState, shiftBefore, capsBefore)
            return
        }
        val before = connection.getTextBeforeCursor(WORD_LOOKBACK + HISTORY_CONTEXT, 0)
            ?.toString().orEmpty()
        if (before.isEmpty()) return
        val count = TRAILING_WORD.find(before)?.value?.length ?: 1
        val window = deletionWindow(connection, count)
        connection.deleteSurroundingText(count, 0)
        spacing.onTextDeleted(window.text)
        refreshWord()
        refreshAutoShiftFromContext()
        recordDeletion(window.text, window.context, beforeState, shiftBefore, capsBefore)
    }

    private fun commitSecondary(key: KeyboardCanvasView.Key) {
        val secondary = key.secondary
        if (secondary.isEmpty()) return
        val expected = compoundEdit { connection ->
            // The primary character was already inserted on touch-down. Its case tells us whether
            // the user was holding Shift, which one-shot Shift has already consumed by now.
            val primary = textBefore(connection, 1)
            val upper = caps || (primary.length == 1 && primary[0].isUpperCase())
            deleteOnce()
            val text = if (secondary.first().isLetter()) {
                if (upper) secondary.uppercase(Locale.ROOT) else secondary.lowercase(Locale.ROOT)
            } else {
                secondary
            }
            val dropLeadingSpace = starterLetterInsertedSpace &&
                AutoSpacingManager.classify(text) ==
                AutoSpacingManager.CharacterClass.STICKY_PUNCTUATION
            recordEdit { commitComposed(text, dropLeadingSpace) }
            text
        } ?: return
        if (!tailMatches(expected)) {
            // The field restarted its input session between the delete and the commit. The
            // primary letter may already be gone, so only append what is missing.
            compoundEdit {
                if (!tailMatches(expected, it)) commitComposed(expected)
            }
        }
        markInserted(expected)
    }

    // ------------------------------------------------------------------ word tracking / T9

    private fun readTrailingWord(): String {
        val connection = activeConnection() ?: return ""
        return WordTokens.trailing(textBefore(connection, WORD_LOOKBACK))
    }

    /** Word and smart space that belong to the last gesture-typed insertion. */
    private data class SwipeContext(
        val raw: String,
        val leadingSpace: Boolean
    )

    private fun currentSwipeContext(): SwipeContext? {
        val target = swipeWord ?: return null
        val connection = activeConnection() ?: return null
        val before = try {
            connection.getTextBeforeCursor(SWIPE_LOOKBACK, 0)?.toString()
        } catch (_: Exception) {
            null
        } ?: return null
        val start = WordTokens.trailingStart(before)
        if (start == before.length) return null
        val raw = before.substring(start)
        if (raw.length < 2 || !raw.equals(target, ignoreCase = true)) return null
        val leading = swipeLeadingSpace && start > 0 && before[start - 1] == ' '
        return SwipeContext(raw, leading)
    }

    private fun refreshWord() {
        val context = readContext(WORD_CONTEXT_LOOKBACK)
        if (context == null) {
            updateWordState(readTrailingWord(), null)
            return
        }
        val raw = trailingWord(context)
        updateWordState(raw, wordBefore(context, raw))
    }

    private fun updateWordState(raw: String, previous: String?) {
        if (raw == wordRaw && previous == currentPreviousWord) return
        wordRaw = raw
        currentWord = raw.lowercase(Locale.ROOT)
        currentPreviousWord = previous
        updateSuggestions()
    }

    /** Trailing word before the cursor, including internal hyphens. */
    private fun trailingWord(context: String): String = WordTokens.trailing(context)

    /**
     * Word directly before the current token; used as the bigram context. Returns null at the
     * beginning of the field or when only punctuation precedes the cursor.
     */
    private fun wordBefore(context: String, current: String): String? =
        WordTokens.before(context, current)

    private fun readContext(maxChars: Int): String? = try {
        activeConnection()?.getTextBeforeCursor(maxChars, 0)?.toString()
    } catch (_: Exception) {
        null
    }

    private fun clearWordState() {
        currentWord = ""
        wordRaw = ""
        currentPreviousWord = null
        suggestionJob?.cancel()
        clearSwipeState()
        if (::keyboard.isInitialized) keyboard.suggestions = emptyList()
    }

    private fun clearSwipeState() {
        swipeWord = null
        swipeCandidates = emptyList()
        swipeLeadingSpace = false
        lastSwipeAt = 0L
    }

    private fun updateSuggestions() {
        if (!::keyboard.isInitialized) return
        if (swipeCandidates.isNotEmpty()) {
            if (!keyboard.symbols && currentSwipeContext() != null) {
                suggestionJob?.cancel()
                keyboard.suggestions = swipeCandidates
                return
            }
            clearSwipeState()
        }
        if (keyboard.symbols) {
            keyboard.suggestions = emptyList()
            return
        }
        val prefix = currentWord
        if (prefix.isEmpty()) {
            suggestionJob?.cancel()
            keyboard.suggestions = emptyList()
            return
        }
        val language = keyboard.russian
        val previous = currentPreviousWord
        suggestionJob?.cancel()
        suggestionJob = scope.launch {
            val result = prediction.suggestions(prefix, language, previous)
            val swipePanel = swipeCandidates.isNotEmpty()
            if (::keyboard.isInitialized && currentWord == prefix &&
                currentPreviousWord == previous &&
                keyboard.russian == language && !keyboard.symbols && !swipePanel
            ) {
                keyboard.suggestions = result
            }
        }
    }

    private fun caseSuggestion(value: String, typed: String): String =
        SwipeTextPolicy.caseWord(value, caps, typed.firstOrNull()?.isUpperCase() == true)

    // ------------------------------------------------------------------ backspace repeat

    private val deleteStart = Runnable {
        if (!backspaceHeld) return@Runnable
        deleteOnce()
        handler.postDelayed(deleteTick, 55L)
    }

    private val deleteTick = object : Runnable {
        override fun run() {
            if (!backspaceHeld) return
            val elapsed = SystemClock.uptimeMillis() - deleteRepeatStarted
            val wordMode = elapsed > WORD_DELETE_AFTER_MILLIS
            if (wordMode) deleteWord() else deleteOnce()
            handler.postDelayed(this, if (wordMode) 95L else 55L)
        }
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceHeld = true
        deleteRepeatStarted = SystemClock.uptimeMillis()
        handler.postDelayed(deleteStart, REPEAT_START_MILLIS)
    }

    private fun stopBackspaceRepeat() {
        backspaceHeld = false
        handler.removeCallbacks(deleteStart)
        handler.removeCallbacks(deleteTick)
    }

    // ------------------------------------------------------------------ whole-field edits (AC, magic)

    /** AC: clears the whole active field, keeping the cleared text as one undoable action. */
    private fun clearAll() {
        val connection = currentInputConnection ?: return
        magicJob?.cancel()
        magicJob = null
        if (::keyboard.isInitialized && voice.state == VoiceInputController.State.IDLE) {
            keyboard.showStatus(null)
        }
        val original = WholeFieldEditor.read(connection)
        if (original == null) {
            message(getString(R.string.err_clear_failed), true)
            return
        }
        if (original.isEmpty()) return
        if (!commitWholeFieldEdit(connection, original, "")) {
            message(getString(R.string.err_clear_failed), true)
        }
    }

    /** Replaces the whole field and records the replacement as one undoable action. */
    private fun commitWholeFieldEdit(
        connection: InputConnection,
        original: String,
        replacement: String
    ): Boolean {
        val spacingBefore = spacing.undoState()
        val shiftBefore = oneShotShift
        val capsBefore = caps
        if (!WholeFieldEditor.replace(connection, original, replacement)) return false

        spacing.reset()
        clearWordState()
        refreshAutoShiftFromContext()
        editHistory.record(
            EditHistory.Entry(
                context = "",
                removed = original,
                inserted = replacement,
                before = spacingBefore,
                after = spacing.undoState(),
                oneShotShiftBefore = shiftBefore,
                capsBefore = capsBefore,
                oneShotShiftAfter = oneShotShift,
                capsAfter = caps
            )
        )
        pendingLetterEntry = null
        return true
    }

    /**
     * Magic wand: reads the active field, asks DeepSeek for a light correction and puts the result
     * back. The original text is one history entry, so the Back arrow restores it in one tap.
     */
    private fun fixTextWithMagic() {
        if (voice.state != VoiceInputController.State.IDLE) return
        if (magicJob?.isActive == true) return
        val connection = currentInputConnection ?: return
        val original = WholeFieldEditor.read(connection)
        if (original == null) {
            message(getString(R.string.err_magic_read_failed), true)
            return
        }
        if (original.isBlank()) {
            if (::keyboard.isInitialized) {
                keyboard.showStatus(
                    getString(R.string.status_magic_no_text),
                    false,
                    STATUS_MESSAGE_MILLIS
                )
            }
            return
        }
        val apiKey = Prefs.deepseekKey(this)
        if (apiKey.isEmpty()) {
            message(getString(R.string.err_no_deepseek_key), true)
            openSettings()
            return
        }
        if (!::keyboard.isInitialized) return

        keyboard.showStatus(getString(R.string.status_magic_running), false, 0L)
        val session = inputSession
        val model = Prefs.deepseekModel(this)
        val prompt = getString(R.string.magic_prompt)
        magicJob = scope.launch {
            try {
                val corrected = withContext(Dispatchers.IO) {
                    SpeechApi.polish(original, apiKey, model, prompt, temperature = 0.0)
                }.let { TextPolishPolicy.normalizeDashes(it) }

                if (session != inputSession || !::keyboard.isInitialized) return@launch
                val current = currentInputConnection
                if (current == null) {
                    if (voice.state == VoiceInputController.State.IDLE) keyboard.showStatus(null)
                    return@launch
                }
                if (voice.state != VoiceInputController.State.IDLE) return@launch
                if (corrected.isBlank()) {
                    message(getString(R.string.err_magic_empty), true)
                    return@launch
                }
                if (corrected == original) {
                    keyboard.showStatus(
                        getString(R.string.status_magic_unchanged),
                        false,
                        STATUS_MESSAGE_MILLIS
                    )
                    return@launch
                }
                if (!TextPolishPolicy.isLightEdit(original, corrected)) {
                    message(getString(R.string.status_magic_rewritten), true)
                    return@launch
                }
                val currentText = WholeFieldEditor.read(current)
                if (currentText == null) {
                    message(getString(R.string.err_magic_read_failed), true)
                    return@launch
                }
                if (currentText != original) {
                    keyboard.showStatus(
                        getString(R.string.status_magic_changed),
                        false,
                        STATUS_MESSAGE_MILLIS
                    )
                    return@launch
                }
                if (!commitWholeFieldEdit(current, original, corrected)) {
                    message(getString(R.string.err_magic_replace_failed), true)
                    return@launch
                }
                keyboard.showStatus(
                    getString(R.string.status_magic_done),
                    false,
                    STATUS_MESSAGE_MILLIS
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session == inputSession && ::keyboard.isInitialized) {
                    message(e.message ?: e.javaClass.simpleName, true)
                }
            } finally {
                if (magicJob === coroutineContext[Job]) magicJob = null
            }
        }
    }

    // ------------------------------------------------------------------ voice status

    private fun voiceState(state: VoiceInputController.State) {
        if (::keyboard.isInitialized) {
            keyboard.voiceActive = state != VoiceInputController.State.IDLE
            when (state) {
                VoiceInputController.State.RECORDING ->
                    keyboard.showStatus(getString(R.string.status_recording_short, 0, 0), false, 0L)
                VoiceInputController.State.PROCESSING ->
                    keyboard.showStatus(getString(R.string.status_processing), false, 0L)
                VoiceInputController.State.IDLE ->
                    keyboard.showStatus(null)
            }
        }
        if (state == VoiceInputController.State.IDLE) releaseWakefulness() else acquireWakefulness()
    }

    private fun message(text: String, error: Boolean) {
        if (::keyboard.isInitialized) {
            val sticky = voice.state != VoiceInputController.State.IDLE && !error
            keyboard.showStatus(text, error, if (sticky) 0L else STATUS_MESSAGE_MILLIS)
        }
        if (error) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun onVoiceCommitted() {
        consumeOneShotShift()
        // Voice text is committed outside the keyboard history; starting a fresh history keeps
        // undo from applying an older entry at the wrong cursor position.
        editHistory.clear()
        pendingLetterEntry = null
        clearWordState()
        spacing.reset()
        refreshAutoShiftFromContext()
    }

    // ------------------------------------------------------------------ clipboard

    private fun readClipboard(): List<String> {
        rememberCurrentClipboard()
        return clipboardHistory.snapshot()
    }

    private fun rememberCurrentClipboard() {
        try {
            val clip = clipboardManager.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) rememberClipboard(text)
        } catch (_: Exception) {
        }
    }

    private fun rememberClipboard(text: String) {
        clipboardHistory.remember(text)
    }

    // ------------------------------------------------------------------ system / window

    private fun gestureTypingAllowed(info: EditorInfo?): Boolean {
        if (info == null) return false
        val type = info.inputType
        val inputClass = type and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME
        ) {
            return false
        }
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            val variation = type and InputType.TYPE_MASK_VARIATION
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return false
            }
        }
        return true
    }

    private fun showInputMethodPicker() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.err_switch_ime, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            requestHideSelf(0)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.err_open_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun acquireWakefulness() {
        window?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBoard:dictation")
                .apply {
                    setReferenceCounted(false)
                    acquire(270_000L)
                }
        }
    }

    private fun releaseWakefulness() {
        window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun configureSystemBars() {
        window?.window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            it.navigationBarColor = Color.rgb(28, 28, 30)
            WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightNavigationBars = false
        }
    }

    private fun dp(value: Float): Float = resources.displayMetrics.density * value

    private companion object {
        const val WORD_LOOKBACK = 48
        const val WORD_CONTEXT_LOOKBACK = 80
        const val SWIPE_LOOKBACK = 64
        const val HISTORY_CONTEXT = 24
        const val AUTO_CAP_LOOKBACK = 64
        const val MAX_CLIPBOARD_HISTORY = 20
        const val CLIPBOARD_HISTORY_FILE = "clipboard_history.txt"
        const val DOUBLE_TAP_MILLIS = 350L
        const val REPEAT_START_MILLIS = 350L
        const val WORD_DELETE_AFTER_MILLIS = 1_500L
        const val STATUS_MESSAGE_MILLIS = 2_600L
        const val SWIPE_BACKSPACE_WINDOW_MS = 5_000L
        const val OWN_SELECTION_WINDOW_MS = 800L
        const val MAX_SPACE_DRAG_STEPS = 40

        val TRAILING_WORD = Regex("\\s*\\S+\\s*$")
    }
}
