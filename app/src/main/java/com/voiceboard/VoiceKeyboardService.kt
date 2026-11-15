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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

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
    private val clipboardHistory = ArrayDeque<String>()
    private val clipboardWatcher =
        ClipboardManager.OnPrimaryClipChangedListener { rememberCurrentClipboard() }

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

    /** State before a swipe's starter letter was typed; restored when the swipe result arrives. */
    private var spacingBeforeLetter = AutoSpacingManager.SpaceState.NONE
    private var starterLetterInsertedSpace = false

    private var currentWord = ""
    private var wordRaw = ""
    private var russianLayout = true
    private var caps = false
    private var oneShotShift = false
    private var lastShiftAt = 0L

    private var suggestionJob: Job? = null
    private var backspaceHeld = false
    private var deleteRepeatStarted = 0L
    private var spaceDragApplied = 0
    private var wakeLock: PowerManager.WakeLock? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prediction = PredictionEngine(this, scope).also { engine ->
            engine.load { if (::keyboard.isInitialized) updateSuggestions() }
        }
        spacing = AutoSpacingManager(object : AutoSpacingManager.Editor {
            override fun textBeforeCursor(maxChars: Int): String? =
                currentInputConnection?.getTextBeforeCursor(maxChars, 0)?.toString()

            override fun commitText(text: String) {
                currentInputConnection?.commitText(text, 1)
            }

            override fun deleteBeforeCursor(count: Int) {
                if (count > 0) currentInputConnection?.deleteSurroundingText(count, 0)
            }

            override fun hasSelection(): Boolean =
                currentInputConnection?.getSelectedText(0).isNullOrEmpty() == false
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
        if (!::keyboard.isInitialized) return
        inputSession++
        keyboard.swipeEnabled = gestureTypingAllowed(info)
        spacing.setInputType(info?.inputType)
        if (!caps) {
            val empty = try {
                currentInputConnection?.getTextBeforeCursor(1, 0).isNullOrEmpty()
            } catch (_: Exception) {
                false
            }
            oneShotShift = empty && autoCapAllowed(info)
            lastShiftAt = 0L
        }
        keyboard.setShift(oneShotShift, caps)
        clearWordState()
        refreshWord()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputSession++
        stopBackspaceRepeat()
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
        if (!::keyboard.isInitialized || newSelStart < 0 || newSelEnd < 0) return
        spacing.onSelectionChanged(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
        if (newSelStart != newSelEnd) {
            spacing.reset()
            clearWordState()
        } else {
            refreshWord()
        }
    }

    // ------------------------------------------------------------------ keyboard callbacks

    override fun onKeyDown(key: KeyboardCanvasView.Key) {
        when (key.action) {
            KeyboardCanvasView.Action.LETTER -> {
                spacingBeforeLetter = spacing.state
                starterLetterInsertedSpace = commitCharacter(key.label)
            }
            KeyboardCanvasView.Action.COMMA -> insertPunctuation(",")
            KeyboardCanvasView.Action.PERIOD -> insertPunctuation(".")
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
            KeyboardCanvasView.Action.SPACE -> commitSpace()
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
            KeyboardCanvasView.Action.ENTER -> pressEnter()
            KeyboardCanvasView.Action.AC -> clearAll()
            KeyboardCanvasView.Action.CLIPBOARD -> {
                keyboard.setClipboard(readClipboard())
                keyboard.toggleClipboard()
            }
            KeyboardCanvasView.Action.MIC -> voice.toggle()
            else -> Unit
        }
    }

    override fun onKeyLongPress(key: KeyboardCanvasView.Key) {
        when {
            key.action == KeyboardCanvasView.Action.AC -> openSettings()
            key.action == KeyboardCanvasView.Action.LANGUAGE -> showInputMethodPicker()
            key.action == KeyboardCanvasView.Action.BACKSPACE -> Unit
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
        val difference = (desired - spaceDragApplied).coerceIn(-40, 40)
        if (difference == 0) return
        val connection = currentInputConnection
        if (connection == null) {
            spaceDragApplied = desired
            return
        }
        val keyCode = if (difference > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(abs(difference)) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        spaceDragApplied += difference
        refreshWord()
    }

    override fun onSpaceDragEnd() {
        spaceDragApplied = 0
        refreshWord()
    }

    override fun onSuggestion(word: String) {
        val connection = currentInputConnection ?: return
        if (!connection.getSelectedText(0).isNullOrEmpty()) return

        val swipe = currentSwipeContext()
        if (swipe != null) {
            val chosen = caseSuggestion(word, swipe.raw)
            val insertion = spacing.replaceWord(swipe.raw.length, chosen)
            consumeOneShotShift()

            wordRaw = chosen
            currentWord = chosen.lowercase(Locale.ROOT)
            swipeWord = chosen.lowercase(Locale.ROOT)
            swipeLeadingSpace = swipe.leadingSpace || insertion.leadingSpace
            lastSwipeAt = SystemClock.uptimeMillis()
            if (::keyboard.isInitialized) keyboard.suggestions = swipeCandidates
            return
        }

        val typed = readTrailingWord()
        val chosen = caseSuggestion(word, typed)
        spacing.replaceWord(typed.length, chosen)
        consumeOneShotShift()
        // Keep the committed word as the current token: the selection callback that follows the
        // commit must not immediately reopen T9 suggestions for the word the user just chose.
        suggestionJob?.cancel()
        clearSwipeState()
        wordRaw = chosen
        currentWord = chosen.lowercase(Locale.ROOT)
        if (::keyboard.isInitialized) keyboard.suggestions = emptyList()
    }

    override fun onClipboard(text: String) {
        if (currentInputConnection == null) return
        spacing.commitRaw(text)
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
        val session = inputSession
        scope.launch {
            val results = withContext(Dispatchers.Default) {
                swipeEngine.decode(gesture, layout, lexicon, 3)
            }
            if (session != inputSession || !::keyboard.isInitialized ||
                !keyboard.swipeEnabled || keyboard.symbols || keyboard.russian != language
            ) {
                return@launch
            }
            if (results.isEmpty()) {
                keyboard.showStatus(getString(R.string.swipe_not_recognized), false, 1_400L)
                return@launch
            }
            try {
                insertSwipeResult(results, startKey)
            } catch (_: Exception) {
                // A dying InputConnection must not crash the keyboard; the starter letter stays.
            }
        }
        return true
    }

    private fun insertSwipeResult(
        results: List<SwipeEngine.Result>,
        startKey: KeyboardCanvasView.Key
    ) {
        val connection = currentInputConnection ?: return
        val startLetter = startKey.label[0].lowercaseChar()
        val firstRaw = connection.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (firstRaw.length != 1 || firstRaw[0].lowercaseChar() != startLetter) return
        if (!connection.getSelectedText(0).isNullOrEmpty()) return

        val firstIsUpper = firstRaw[0].isUpperCase()
        val topWord = results.first().word
        val cased = SwipeTextPolicy.caseWord(topWord, caps, firstIsUpper)
        val stateBeforeStarter = spacingBeforeLetter
        val starterSpace = starterLetterInsertedSpace

        connection.beginBatchEdit()
        val insertion = try {
            spacing.replaceWord(1, cased, stateBeforeStarter)
        } finally {
            connection.endBatchEdit()
        }

        suggestionJob?.cancel()
        wordRaw = cased
        currentWord = cased.lowercase(Locale.ROOT)
        swipeWord = topWord.lowercase(Locale.ROOT)
        swipeCandidates = results.map {
            SwipeTextPolicy.caseWord(it.word, caps, firstIsUpper)
        }
        swipeLeadingSpace = insertion.leadingSpace || starterSpace
        lastSwipeAt = SystemClock.uptimeMillis()
        if (::keyboard.isInitialized) keyboard.suggestions = swipeCandidates
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
                consumeOneShotShift()
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
        if (currentInputConnection == null) return
        spacing.commitStickyPunctuation(mark, dropLeadingSpace)
        clearWordState()
        if (AutoCapPolicy.endsSentence(mark)) enableAutoShift()
    }

    private fun enableAutoShift() {
        if (caps || !autoCapAllowed(currentInputEditorInfo)) return
        oneShotShift = true
        lastShiftAt = 0L
        if (::keyboard.isInitialized) keyboard.setShift(true, false)
    }

    private fun autoCapAllowed(info: EditorInfo?): Boolean =
        info != null && AutoCapPolicy.allows(info.inputType)

    private fun commitSpace() {
        if (currentInputConnection == null) return
        spacing.commitSpace()
        clearWordState()
    }

    private fun pressEnter() {
        val connection = currentInputConnection ?: return
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
            enableAutoShift()
            return
        }
        connection.performEditorAction(action)
        clearWordState()
    }

    private fun clearAll() {
        val connection = currentInputConnection ?: return
        var cleared = false
        try {
            if (connection.performContextMenuAction(android.R.id.selectAll)) {
                val selected = connection.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    connection.commitText("", 1)
                    cleared = true
                }
            }
        } catch (_: Exception) {
        }
        if (!cleared) {
            val before = connection.getTextBeforeCursor(Int.MAX_VALUE, 0)
            val after = connection.getTextAfterCursor(Int.MAX_VALUE, 0)
            if (!before.isNullOrEmpty() || !after.isNullOrEmpty()) {
                try {
                    connection.deleteSurroundingText(before?.length ?: 0, after?.length ?: 0)
                } catch (_: Exception) {
                }
            }
        }
        clearWordState()
        spacing.reset()
        refreshAutoShiftAfterDeletion()
    }

    /**
     * Backspace can return the cursor to an empty field, a new line or the end of a sentence.
     * The Shift that produced the deleted capital was consumed, so the next letter needs it again.
     */
    private fun refreshAutoShiftAfterDeletion() {
        if (caps) return
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString()
        if (AutoCapPolicy.shouldCapitalizeAfterDeletion(before)) enableAutoShift()
    }

    private fun deleteOnce() {
        val connection = currentInputConnection ?: return
        spacing.onBackspace()
        if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
            refreshWord()
            refreshAutoShiftAfterDeletion()
            return
        }
        if (deleteSwipeWordIfAny(connection)) return
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val count = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
        connection.deleteSurroundingText(count, 0)
        refreshWord()
        refreshAutoShiftAfterDeletion()
    }

    /**
     * A single Backspace immediately after gesture typing removes the whole swiped word (and the
     * smart space we inserted before it), not just the last letter.
     */
    private fun deleteSwipeWordIfAny(connection: InputConnection): Boolean {
        val target = swipeWord ?: return false
        if (SystemClock.uptimeMillis() - lastSwipeAt > SWIPE_BACKSPACE_WINDOW_MS) {
            clearSwipeState()
            return false
        }
        val context = currentSwipeContext() ?: return false
        val count = SwipeTextPolicy.backspaceDeleteCount(
            context.raw, target, context.leadingSpace
        ) ?: return false
        connection.deleteSurroundingText(count, 0)
        clearWordState()
        refreshWord()
        refreshAutoShiftAfterDeletion()
        return true
    }

    private fun deleteWord() {
        val connection = currentInputConnection ?: return
        spacing.onBackspace()
        if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
            refreshWord()
            refreshAutoShiftAfterDeletion()
            return
        }
        val before = connection.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val count = TRAILING_WORD.find(before)?.value?.length ?: 1
        connection.deleteSurroundingText(count, 0)
        refreshWord()
        refreshAutoShiftAfterDeletion()
    }

    private fun commitSecondary(key: KeyboardCanvasView.Key) {
        val secondary = key.secondary
        if (secondary.isEmpty()) return
        val connection = currentInputConnection ?: return
        // The primary character was already inserted on touch-down. Its case tells us whether the
        // user was holding Shift, which one-shot Shift has already consumed by now.
        val primary = connection.getTextBeforeCursor(1, 0)?.toString().orEmpty()
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
        commitComposed(text, dropLeadingSpace)
    }

    // ------------------------------------------------------------------ word tracking / T9

    private fun readTrailingWord(): String {
        val connection = currentInputConnection ?: return ""
        val before = connection.getTextBeforeCursor(WORD_LOOKBACK, 0)?.toString().orEmpty()
        var start = before.length
        while (start > 0 && before[start - 1].isLetter()) start--
        return before.substring(start)
    }

    /** Word and smart space that belong to the last gesture-typed insertion. */
    private data class SwipeContext(
        val raw: String,
        val leadingSpace: Boolean
    )

    private fun currentSwipeContext(): SwipeContext? {
        val target = swipeWord ?: return null
        val connection = currentInputConnection ?: return null
        val before = connection.getTextBeforeCursor(SWIPE_LOOKBACK, 0)?.toString() ?: return null
        var start = before.length
        while (start > 0 && before[start - 1].isLetter()) start--
        if (start == before.length) return null
        val raw = before.substring(start)
        if (raw.length < 2 || !raw.equals(target, ignoreCase = true)) return null
        val leading = swipeLeadingSpace && start > 0 && before[start - 1] == ' '
        return SwipeContext(raw, leading)
    }

    private fun refreshWord() {
        val raw = readTrailingWord()
        if (raw == wordRaw) return
        wordRaw = raw
        currentWord = raw.lowercase(Locale.ROOT)
        updateSuggestions()
    }

    private fun clearWordState() {
        currentWord = ""
        wordRaw = ""
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
        suggestionJob?.cancel()
        suggestionJob = scope.launch {
            val result = prediction.suggestions(prefix, language)
            val swipePanel = swipeCandidates.isNotEmpty()
            if (::keyboard.isInitialized && currentWord == prefix &&
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
        clearWordState()
        spacing.reset()
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString()
        if (before != null && AutoCapPolicy.endsSentence(before)) enableAutoShift()
    }

    // ------------------------------------------------------------------ clipboard

    private fun readClipboard(): List<String> {
        rememberCurrentClipboard()
        return clipboardHistory.toList()
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
        val value = text.trim()
        if (value.isEmpty()) return
        clipboardHistory.remove(value)
        clipboardHistory.addFirst(value)
        while (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) clipboardHistory.removeLast()
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
        const val SWIPE_LOOKBACK = 64
        const val MAX_CLIPBOARD_HISTORY = 20
        const val DOUBLE_TAP_MILLIS = 350L
        const val REPEAT_START_MILLIS = 350L
        const val WORD_DELETE_AFTER_MILLIS = 1_500L
        const val STATUS_MESSAGE_MILLIS = 2_600L
        const val SWIPE_BACKSPACE_WINDOW_MS = 5_000L

        val TRAILING_WORD = Regex("\\s*\\S+\\s*$")
    }
}
