package com.voiceboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One canvas owns rendering, hit-testing and every raw touch stream.
 *
 * Geometry is rebuilt only when the layout, orientation or size changes; [onDraw] never allocates
 * per-key rectangles, which keeps repaints cheap while the microphone pulse is animating. All
 * pointers are tracked by id, so a second finger types immediately while the first one is still
 * holding a key.
 */
class KeyboardCanvasView(context: Context) : View(context) {

    enum class Action {
        LETTER, SHIFT, BACKSPACE, SPACE, SYMBOLS, LANGUAGE, ENTER,
        AC, UNDO, REDO, CLIPBOARD, MAGIC, GREETING, MIC, COMMA, PERIOD
    }

    data class Key(
        val label: String,
        val secondary: String = "",
        val action: Action = Action.LETTER,
        val weight: Float = 1f
    ) {
        val lowerLabel: String = label.lowercase(Locale.ROOT)
        val upperLabel: String = label.uppercase(Locale.ROOT)
    }

    class Hit(val key: Key, val rect: RectF, val center: PointF)

    interface Listener {
        /** Fired on touch-down for fast keys (letters, punctuation, backspace, shift). */
        fun onKeyDown(key: Key)

        /** Fired on touch-up for deferred keys (space, enter, layout, AC, history, clipboard, mic). */
        fun onKeyAction(key: Key)

        fun onKeyLongPress(key: Key)

        fun onKeyUp(key: Key)

        fun onSpaceDragStart()

        fun onSpaceDrag(totalDx: Float)

        fun onSpaceDragEnd()

        fun onSuggestion(word: String)

        fun onClipboard(text: String)

        /** A gesture-typing swipe was completed; return true when it was consumed. */
        fun onSwipe(path: FloatArray, startKey: Key, layout: SwipeLayout): Boolean

        /** Timed swipe trace; the default keeps existing listeners source-compatible. */
        fun onSwipe(gesture: SwipeGesture, startKey: Key, layout: SwipeLayout): Boolean =
            onSwipe(gesture.path, startKey, layout)

        /** Stops recording/processing without inserting a transcript. */
        fun onVoiceCancel()
    }

    var listener: Listener? = null

    var russian = true
        private set
    var symbols = false
        private set
    var shift = false
        private set
    var caps = false
        private set
    var swipeEnabled = true

    /** Geometry of the current letter keys; the service may disable gesture typing for this field. */
    var swipeLayout: SwipeLayout? = null
        private set

    /** Copy of the current cancel-recording hit target; exposed for tests and accessibility tools. */
    fun voiceCancelHitBounds(): RectF = RectF(voiceCancelHitRect)

    /** Bounds of one suggestion slot; exposed for tests that tap the real layout. */
    internal fun suggestionHitBounds(index: Int): RectF =
        if (index in suggestionRects.indices) RectF(suggestionRects[index]) else RectF()

    /** Bounds of one toolbar button; exposed for the layout tests. */
    internal fun toolbarHitBounds(action: Action): RectF {
        for (hit in toolbarHits) {
            if (hit.key.action == action) return RectF(hit.rect)
        }
        return RectF()
    }

    /** Number of points in all live swipe trails; exposed so the TTL can be tested. */
    internal fun activeTrailPointCount(): Int = trails.values.sumOf { it.size }

    /** Number of keys still fading after release; exposed for the touch-feedback test. */
    internal fun activePressFadeCount(): Int = pressFadeStarts.size

    /**
     * Bounds of the glyph exactly as [drawKey] would render it. Used by the typography tests to
     * compare the lowercase and capital type scales.
     */
    internal fun letterDrawBounds(upperMode: Boolean, letter: Char): RectF {
        val paint = Paint(keyTextPaint)
        paint.textSize = if (upperMode) uppercaseLetterTextSize else lowercaseLetterTextSize
        paint.textScaleX = if (upperMode) 1f else lowercaseWidthScale(letter)
        paint.typeface = Typeface.DEFAULT
        val text = if (upperMode) letter.uppercaseChar().toString() else letter.lowercaseChar().toString()
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return RectF(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat()
        )
    }

    /** Baseline used for a letter at [centerY]; capitals sit lower than lowercase. */
    internal fun letterBaselineY(centerY: Float, upperMode: Boolean): Float =
        if (upperMode) uppercaseLetterBaseline(centerY) else letterBaseline(centerY)

    var voiceActive = false
        set(value) {
            if (field == value) return
            field = value
            updatePulse()
            invalidate()
        }

    var suggestions: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            buildSuggestionLabels()
            invalidate()
        }

    var clipboardOpen = false
        private set

    /** Snapshot handed to [InputRouter] and to a future swipe decoder. */
    val keyMap: List<Hit> get() = keyHits

    private var navigationInset = 0
    private var metrics = buildMetrics()
    private val keyHits = ArrayList<Hit>(64)
    private val toolbarHits = ArrayList<Hit>(8)
    private val suggestionStripRect = RectF()
    private val suggestionRects = arrayOf(RectF(), RectF(), RectF())
    private var suggestionWords: List<String> = emptyList()
    private var suggestionLabels: List<String> = emptyList()
    private var suggestionTextSizes: List<Float> = emptyList()

    private val pointerHits = HashMap<Int, Hit>()
    private val pressedHits = HashSet<Hit>()
    private val pressFadeStarts = HashMap<Hit, Long>()
    private var pressFadeTickerRunning = false
    private val longPressTasks = HashMap<Int, Runnable>()
    private val longPressedPointers = HashSet<Int>()
    private val cancelledPointers = HashSet<Int>()
    private val spaceStartX = HashMap<Int, Float>()
    private val spaceStartY = HashMap<Int, Float>()
    private val spaceDragging = HashSet<Int>()
    private val pendingSuggestions = HashMap<Int, Int>()
    private val pendingSuggestionStart = HashMap<Int, FloatArray>()

    private var previewLabel: String? = null
    private var previewX = 0f
    private var previewY = 0f

    private var clipboardItems: List<String> = emptyList()
    private var clipboardLabels: List<String> = emptyList()
    private var clipboardScroll = 0f
    private var maxClipboardScroll = 0f
    private var clipboardPointer = -1
    private var clipboardDownY = 0f
    private var clipboardDownScroll = 0f
    private var clipboardScrolling = false

    private var statusText: String? = null
    private var statusError = false

    private val handler = Handler(Looper.getMainLooper())
    private val router = InputRouter()
    private var pulse: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val trails = HashMap<Int, SwipeTrail>()
    private val fadingTrails = ArrayList<SwipeTrail>()
    private var trailTickerRunning = false

    private val clipboardBack = context.getString(R.string.clipboard_back)
    private val clipboardEmpty = context.getString(R.string.clipboard_empty)
    private val spaceRu = context.getString(R.string.key_space_ru)
    private val spaceEn = context.getString(R.string.key_space_en)
    private val symbolsRu = context.getString(R.string.key_symbols_ru)
    private val symbolsEn = context.getString(R.string.key_symbols_en)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_KEY }
    private val functionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_FUNCTION }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_PRESSED }
    private val capsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CAPS }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ACCENT }
    private val voiceCancelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(36, 255, 140, 0)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 107, 0)
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
    }
    private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val scrollThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255) }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(4.5f)
        color = COLOR_ACCENT
    }
    private val trailGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(10f)
        color = Color.argb(50, 255, 107, 0)
    }
    private val trailTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(170, 255, 170, 40)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    /**
     * Base font metrics for lowercase centring and for measuring the capital size/baseline
     * difference. Capitals get their own larger size and a lower baseline.
     */
    private val letterMetricsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = dp(LETTER_TEXT_SIZE_DP)
        isSubpixelText = true
    }

    /**
     * Lowercase is scaled up so its x-height matches the old capital size; this keeps the normal
     * (Shift off) keyboard unchanged. Capitals get their own larger size and a lower baseline, so
     * the extra height grows into the free space at the bottom instead of towards the secondary
     * symbol in the top-right corner.
     */
    private val lowercaseLetterScale: Float = run {
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = 100f
            isSubpixelText = true
        }
        val capitalBounds = Rect()
        val lowercaseBounds = Rect()
        probe.typeface = Typeface.DEFAULT
        probe.getTextBounds("О", 0, 1, capitalBounds)
        probe.typeface = Typeface.DEFAULT
        probe.getTextBounds("о", 0, 1, lowercaseBounds)
        if (lowercaseBounds.height() <= 0) {
            1f
        } else {
            (capitalBounds.height().toFloat() / lowercaseBounds.height())
                .coerceIn(1f, 1.6f)
        }
    }
    private val lowercaseLetterTextSize: Float =
        letterMetricsPaint.textSize * lowercaseLetterScale

    private val uppercaseLetterTextSize: Float =
        letterMetricsPaint.textSize * UPPERCASE_LETTER_SCALE

    /**
     * Keeps the top of a capital at the same height as before while the larger font grows down.
     * The extra dp adds a little more clearance below the superscript secondary symbols.
     */
    private val uppercaseBaselineShift: Float = run {
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            isSubpixelText = true
        }
        val baseBounds = Rect()
        val uppercaseBounds = Rect()
        probe.textSize = letterMetricsPaint.textSize
        probe.getTextBounds("О", 0, 1, baseBounds)
        probe.textSize = uppercaseLetterTextSize
        probe.getTextBounds("О", 0, 1, uppercaseBounds)
        max(0f, (uppercaseBounds.height() - baseBounds.height()).toFloat()) +
            dp(UPPERCASE_EXTRA_SHIFT_DP)
    }

    private val letterProbePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        isSubpixelText = true
    }
    private val lowercaseWidthScales = HashMap<Char, Float>(40)
    private val suggestionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
        isSubpixelText = true
    }
    private val suggestionStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SUGGESTION_STRIP
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The superscript symbols are part of the key legend, not a muted hint: the same white
        // as the primary letter keeps «:», «;» and «ё» readable at a glance.
        color = COLOR_TEXT
        textAlign = Paint.Align.RIGHT
        textSize = dp(SECONDARY_TEXT_SIZE_DP)
        isSubpixelText = true
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = COLOR_TEXT
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEXT
    }

    private val iconPath = Path()
    private val iconRect = RectF()
    private val previewRect = RectF()
    private val statusRect = RectF()
    private val voiceCancelIconRect = RectF()
    private val voiceCancelHitRect = RectF()
    private val toolGlyphRect = RectF()
    private val clipboardHeader = RectF()
    private val clipboardViewport = RectF()
    private val clipboardRowRect = RectF()
    private val clearStatus = Runnable {
        statusText = null
        invalidate()
    }

    /**
     * Keeps an active swipe trail alive between touch events: old points expire and their alpha
     * falls continuously, so a long or tangled gesture looks like a short comet tail instead of a
     * growing knot. It stops as soon as the last active pointer is lifted or cancelled.
     */
    private val trailTicker = object : Runnable {
        override fun run() {
            if (trails.isEmpty()) {
                trailTickerRunning = false
                return
            }
            val now = SystemClock.uptimeMillis()
            for (trail in trails.values) trail.prune(now)
            invalidate()
            handler.postDelayed(this, TRAIL_FRAME_MILLIS)
        }
    }

    /**
     * Drives the short light-to-gray fade after a key is released. Only released keys are tracked,
     * so holding a key does not schedule frames.
     */
    private val pressFadeTicker = object : Runnable {
        override fun run() {
            if (pressFadeStarts.isEmpty()) {
                pressFadeTickerRunning = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val iterator = pressFadeStarts.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value >= PRESS_FADE_MILLIS) iterator.remove()
            }
            invalidate()
            if (pressFadeStarts.isNotEmpty()) {
                handler.postDelayed(this, PRESS_FADE_FRAME_MILLIS)
            } else {
                pressFadeTickerRunning = false
            }
        }
    }

    init {
        isFocusable = false
        setBackgroundColor(COLOR_BACKGROUND)
        router.swipeSink = object : InputRouter.SwipeSink {
            override fun onSwipeCompleted(
                gesture: SwipeGesture,
                startKey: Key,
                layout: SwipeLayout
            ): Boolean = listener?.onSwipe(gesture, startKey, layout) ?: false
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (bottom != navigationInset) {
                navigationInset = bottom
                requestLayout()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulse?.cancel()
        pulse = null
        stopTrailTicker()
        stopPressFadeTicker()
        handler.removeCallbacksAndMessages(null)
        router.reset()
        cancelTouchState()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        metrics = buildMetrics()
        val desiredHeight = metrics.height(ROW_COUNT).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        metrics = buildMetrics()
        rebuildGeometry()
        requestLayout()
    }

    // ------------------------------------------------------------------ public state

    fun setShift(single: Boolean, locked: Boolean) {
        if (shift == single && caps == locked) return
        shift = single
        caps = locked
        invalidate()
    }

    fun setLanguage(russianLayout: Boolean) {
        russian = russianLayout
        symbols = false
        suggestions = emptyList()
        rebuildGeometry()
        invalidate()
    }

    fun toggleSymbols() {
        symbols = !symbols
        suggestions = emptyList()
        rebuildGeometry()
        invalidate()
    }

    fun setClipboard(items: List<String>) {
        val cleaned = ArrayList<String>(min(items.size, MAX_CLIPBOARD_ITEMS))
        for (item in items) {
            val value = item.trim().replace('\n', ' ').replace('\r', ' ')
            if (value.isEmpty() || cleaned.any { it == value }) continue
            cleaned.add(value)
            if (cleaned.size == MAX_CLIPBOARD_ITEMS) break
        }
        clipboardItems = cleaned
        val labels = ArrayList<String>(cleaned.size)
        for (item in cleaned) {
            labels.add(if (item.length > 180) item.take(179) + "…" else item)
        }
        clipboardLabels = labels
        clipboardScroll = 0f
        updateClipboardScrollBounds()
        invalidate()
    }

    fun toggleClipboard() {
        clipboardOpen = !clipboardOpen
        clipboardScroll = 0f
        clipboardScrolling = false
        clipboardPointer = -1
        pendingSuggestions.clear()
        pendingSuggestionStart.clear()
        previewLabel = null
        invalidate()
    }

    /**
     * Shows a short status line in the suggestion strip. [holdMillis] = 0 keeps it until the next
     * call; a positive value auto-clears it, which is how "Готово" and error messages behave.
     */
    fun showStatus(text: String?, error: Boolean = false, holdMillis: Long = 0L) {
        if (statusText == text && statusError == error) return
        statusText = text
        statusError = error
        handler.removeCallbacks(clearStatus)
        if (text != null && holdMillis > 0L) handler.postDelayed(clearStatus, holdMillis)
        invalidate()
    }

    // ------------------------------------------------------------------ geometry

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return
        keyHits.clear()
        toolbarHits.clear()
        pressedHits.clear()
        pressFadeStarts.clear()
        stopPressFadeTicker()

        val side = dp(4f)
        val gap = dp(4f)
        val top = metrics.top
        val toolbarTop = top + metrics.suggestion

        // Suggestions live on their own dark strip above the toolbar and use the whole width, so
        // a long word no longer has to fit between the icon buttons and the microphone.
        suggestionStripRect.set(0f, top, width.toFloat(), toolbarTop)
        val suggestionSide = dp(6f)
        val slotWidth = max(0f, (width - suggestionSide * 2f) / 3f)
        for (index in 0 until 3) {
            val left = suggestionSide + slotWidth * index
            suggestionRects[index].set(left, top, left + slotWidth, toolbarTop)
        }

        // AC, the icon buttons and the microphone share the toolbar evenly: every pair is
        // separated by the same gap, and AC starts at the same inset the microphone ring leaves
        // on the right edge of the screen.
        val edge = dp(10f)
        val available = width - edge * 2f
        val acWidth = dp(30f)
        val micSize = if (metrics.toolbar >= dp(40f)) dp(42f) else metrics.toolbar - dp(2f)
        val iconCount = TOOLBAR_ACTIONS.size
        val gapCount = iconCount + 1
        val minItemGap = dp(2f)
        val iconSlot = min(
            min(dp(36f), metrics.toolbar),
            max(0f, (available - acWidth - micSize - minItemGap * gapCount) / iconCount)
        )
        val totalWidth = acWidth + iconSlot * iconCount + micSize
        val itemGap = max(0f, (available - totalWidth) / gapCount)

        var left = edge
        val acRect = RectF(left, toolbarTop, left + acWidth, toolbarTop + metrics.toolbar)
        toolbarHits.add(
            Hit(Key("AC", action = Action.AC), acRect, PointF(acRect.centerX(), acRect.centerY()))
        )
        left += acWidth + itemGap
        for (action in TOOLBAR_ACTIONS) {
            val rect = RectF(left, toolbarTop, left + iconSlot, toolbarTop + metrics.toolbar)
            toolbarHits.add(Hit(Key("", action = action), rect, PointF(rect.centerX(), rect.centerY())))
            left += iconSlot + itemGap
        }
        val micRect = RectF(
            left,
            toolbarTop + (metrics.toolbar - micSize) / 2f,
            left + micSize,
            toolbarTop + (metrics.toolbar - micSize) / 2f + micSize
        )
        toolbarHits.add(Hit(Key("", action = Action.MIC), micRect, PointF(micRect.centerX(), micRect.centerY())))

        var y = toolbarTop + metrics.toolbar + metrics.gap
        for (row in buildRows()) {
            var totalWeight = 0f
            for (key in row) totalWeight += key.weight
            val usable = width - side * 2f - gap * (row.size - 1)
            var x = side
            for (key in row) {
                val keyWidth = usable * key.weight / totalWeight
                val rect = RectF(x, y, x + keyWidth, y + metrics.keyHeight)
                keyHits.add(Hit(key, rect, PointF(rect.centerX(), rect.centerY())))
                x += keyWidth + gap
            }
            y += metrics.keyHeight + metrics.rowGap
        }

        buildSwipeLayout()
        clipboardHeader.set(side, metrics.top, width - side, metrics.top + metrics.toolbar)
        clipboardViewport.set(side, clipboardHeader.bottom + metrics.gap, width - side, height - metrics.bottom)
        updateClipboardScrollBounds()
        buildSuggestionLabels()
    }

    private fun buildSwipeLayout() {
        val centers = LinkedHashMap<Char, PointF>()
        var totalWidth = 0f
        var keyHeight = 0f
        var count = 0
        for (hit in keyHits) {
            val key = hit.key
            val label = key.label
            if (key.action != Action.LETTER || label.length != 1 || !label[0].isLetter()) continue
            val letter = label[0].lowercaseChar()
            if (!centers.containsKey(letter)) centers[letter] = PointF(hit.center.x, hit.center.y)
            totalWidth += hit.rect.width()
            keyHeight = hit.rect.height()
            count++
        }
        swipeLayout = if (count > 0 && keyHeight > 0f) {
            SwipeLayout(centers, totalWidth / count, keyHeight, russian)
        } else {
            null
        }
    }

    private fun buildSuggestionLabels() {
        val words = suggestions.take(3)
        suggestionWords = words
        if (words.isEmpty() || suggestionRects[0].width() <= 0f) {
            suggestionLabels = emptyList()
            suggestionTextSizes = emptyList()
            return
        }
        val maxWidth = suggestionRects[0].width() - dp(6f)
        val fitted = words.map { fitSuggestion(it, maxWidth) }
        suggestionLabels = fitted.map { it.first }
        suggestionTextSizes = fitted.map { it.second }
    }

    /**
     * The undo/redo group leaves less width for suggestions, so long words shrink slightly before
     * they are truncated. Returns the displayed text and the text size to use for it.
     */
    private fun fitSuggestion(value: String, maxWidth: Float): Pair<String, Float> {
        val minimum = dp(10f)
        var size = dp(15f)
        while (size > minimum) {
            suggestionPaint.textSize = size
            if (suggestionPaint.measureText(value) <= maxWidth) return value to size
            size -= dp(0.5f)
        }
        suggestionPaint.textSize = minimum
        if (suggestionPaint.measureText(value) <= maxWidth) return value to minimum
        val ellipsis = "…"
        var end = value.length
        while (end > 1 &&
            suggestionPaint.measureText(value, 0, end) + suggestionPaint.measureText(ellipsis) > maxWidth
        ) {
            end--
        }
        return value.substring(0, end) + ellipsis to minimum
    }

    private fun updateClipboardScrollBounds() {
        val viewportHeight = clipboardViewport.height()
        val contentHeight = clipboardItems.size * dp(CLIPBOARD_ROW_STEP_DP)
        maxClipboardScroll = max(0f, contentHeight - viewportHeight)
        clipboardScroll = clipboardScroll.coerceIn(0f, maxClipboardScroll)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (clipboardOpen) {
            drawClipboard(canvas)
            return
        }
        drawToolbar(canvas)
        val now = SystemClock.uptimeMillis()
        for (index in keyHits.indices) drawKey(canvas, keyHits[index], now)
        drawSwipeTrails(canvas)
        drawPreview(canvas)
    }

    private fun drawToolbar(canvas: Canvas) {
        // The suggestion strip is a separate band above the toolbar: its own dark surface, full
        // screen width and nothing else competing for the space.
        canvas.drawRect(suggestionStripRect, suggestionStripPaint)

        val ac = toolbarHits[TOOL_AC]
        val undo = toolbarHits[TOOL_UNDO]
        val redo = toolbarHits[TOOL_REDO]
        val clip = toolbarHits[TOOL_CLIPBOARD]
        val magic = toolbarHits[TOOL_MAGIC]
        val greeting = toolbarHits[TOOL_GREETING]
        val mic = toolbarHits[TOOL_MIC]

        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = dp(17f)
        labelPaint.color = toolbarColor(ac)
        canvas.drawText(
            "AC",
            ac.rect.left,
            baseline(ac.center.y, labelPaint.textSize),
            labelPaint
        )

        // Toolbar glyphs use a thinner stroke than the keyboard keys.
        iconPaint.strokeWidth = dp(1.65f)

        toolbarGlyphBounds(undo.rect, toolGlyphRect)
        drawHistoryGlyph(canvas, toolGlyphRect, forward = false, toolbarColor(undo))

        toolbarGlyphBounds(redo.rect, toolGlyphRect)
        drawHistoryGlyph(canvas, toolGlyphRect, forward = true, toolbarColor(redo))

        toolbarGlyphBounds(clip.rect, toolGlyphRect)
        drawClipboardGlyph(canvas, toolGlyphRect, toolbarColor(clip))

        toolbarGlyphBounds(magic.rect, toolGlyphRect)
        drawMagicGlyph(canvas, toolGlyphRect, toolbarColor(magic))

        toolbarGlyphBounds(greeting.rect, toolGlyphRect, WAVE_GLYPH_FRACTION)
        drawWaveGlyph(canvas, toolGlyphRect, toolbarColor(greeting))

        val micColor = when {
            voiceActive -> COLOR_ACCENT
            pressedHits.contains(mic) -> COLOR_MUTED
            else -> COLOR_TEXT
        }
        if (voiceActive) {
            val fraction = (pulse?.animatedValue as? Float) ?: 0f
            val alpha = (72 - 44 * fraction).toInt().coerceIn(0, 255)
            haloPaint.color = Color.argb(alpha, 255, 107, 0)
            canvas.drawCircle(
                mic.center.x,
                mic.center.y,
                mic.rect.width() / 2f + dp(2f) + dp(2f) * fraction,
                haloPaint
            )
        }
        iconPaint.color = micColor
        canvas.drawCircle(
            mic.center.x,
            mic.center.y,
            mic.rect.width() / 2f - iconPaint.strokeWidth / 2f,
            iconPaint
        )
        toolbarGlyphBounds(mic.rect, toolGlyphRect)
        // Keep the microphone glyph the same visual size as the left-hand icons even though its
        // circle is larger.
        val iconSize = min(
            undo.rect.width(),
            undo.rect.height()
        ) * TOOLBAR_GLYPH_FRACTION
        toolGlyphRect.set(
            mic.center.x - iconSize / 2f,
            mic.center.y - iconSize / 2f,
            mic.center.x + iconSize / 2f,
            mic.center.y + iconSize / 2f
        )
        drawMicGlyph(canvas, toolGlyphRect, micColor)

        // Status/recording glyphs keep the original weight.
        iconPaint.strokeWidth = dp(2f)
        if (statusText != null || voiceActive) drawStatus(canvas) else drawSuggestions(canvas)
    }

    private fun toolbarColor(hit: Hit): Int =
        if (pressedHits.contains(hit)) COLOR_MUTED else COLOR_TEXT

    private fun drawSuggestions(canvas: Canvas) {
        if (suggestionWords.isEmpty()) return
        suggestionPaint.textAlign = Paint.Align.CENTER
        for (index in suggestionWords.indices) {
            val rect = suggestionRects[index]
            val label = if (index < suggestionLabels.size) suggestionLabels[index] else suggestionWords[index]
            suggestionPaint.textSize =
                if (index < suggestionTextSizes.size) suggestionTextSizes[index] else dp(15f)
            suggestionPaint.color = if (index == 0) COLOR_TEXT else COLOR_MUTED
            canvas.save()
            canvas.clipRect(rect.left + dp(2f), rect.top, rect.right - dp(2f), rect.bottom)
            canvas.drawText(
                label,
                rect.centerX(),
                baseline(rect.centerY(), suggestionPaint.textSize),
                suggestionPaint
            )
            canvas.restore()
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val text = statusText ?: return
        labelPaint.textSize = dp(14f)
        labelPaint.color = when {
            statusError -> COLOR_ERROR
            voiceActive -> COLOR_ACCENT
            else -> COLOR_MUTED
        }
        if (voiceActive) {
            drawVoiceStatus(canvas, text)
            return
        }
        statusRect.set(
            suggestionRects[0].left, suggestionRects[0].top,
            suggestionRects[2].right, suggestionRects[2].bottom
        )
        // Longer statuses (DeepSeek failures, cancellation notices) get the whole strip, which is
        // no longer shared with the toolbar icons.
        val maxWidth = (statusRect.width() - dp(8f)).coerceAtLeast(1f)
        var textSize = dp(14f)
        while (textSize > dp(10f) && labelPaint.measureText(text) > maxWidth) {
            textSize -= dp(0.5f)
        }
        labelPaint.textSize = textSize
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.clipRect(
            statusRect.left + dp(4f), statusRect.top,
            statusRect.right - dp(4f), statusRect.bottom
        )
        canvas.drawText(text, statusRect.centerX(), baseline(statusRect.centerY(), labelPaint.textSize), labelPaint)
        canvas.restore()
    }

    /** Recording/processing: centres the label and the cancel circle as one adjacent group. */
    private fun drawVoiceStatus(canvas: Canvas, text: String) {
        val areaLeft = suggestionRects[0].left
        val areaRight = suggestionRects[2].right
        val side = dp(6f)
        val gap = dp(10f)
        val iconSize = dp(23f)
        val maxTextWidth = (areaRight - areaLeft - side * 2f - iconSize - gap).coerceAtLeast(1f)
        val textWidth = min(labelPaint.measureText(text), maxTextWidth)
        val groupWidth = textWidth + gap + iconSize
        val groupLeft = areaLeft + ((areaRight - areaLeft) - groupWidth) / 2f

        statusRect.set(
            groupLeft, suggestionRects[0].top,
            groupLeft + textWidth, suggestionRects[2].bottom
        )
        val iconLeft = groupLeft + textWidth + gap
        val iconTop = suggestionRects[0].top + (metrics.suggestion - iconSize) / 2f
        voiceCancelIconRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

        val centerX = voiceCancelIconRect.centerX()
        val hitHalf = dp(20f)
        voiceCancelHitRect.set(
            (centerX - hitHalf).coerceAtLeast(areaLeft),
            metrics.top,
            (centerX + hitHalf).coerceAtMost(areaRight),
            metrics.top + metrics.suggestion
        )

        labelPaint.textAlign = Paint.Align.LEFT
        canvas.save()
        canvas.clipRect(
            statusRect.left, statusRect.top,
            statusRect.right + dp(1f), statusRect.bottom
        )
        canvas.drawText(
            text,
            statusRect.left,
            baseline(statusRect.centerY(), labelPaint.textSize),
            labelPaint
        )
        canvas.restore()
        drawVoiceCancelGlyph(canvas)
    }

    private fun drawKey(canvas: Canvas, hit: Hit, now: Long) {
        val key = hit.key
        val rect = hit.rect
        val background = when {
            key.action == Action.SHIFT && caps -> capsPaint
            isFunctionKey(key) -> functionPaint
            else -> keyPaint
        }
        canvas.drawRoundRect(rect, dp(8f), dp(8f), background)
        val pressAlpha = pressHighlightAlpha(hit, now)
        if (pressAlpha > 0) {
            pressedPaint.alpha = pressAlpha
            canvas.drawRoundRect(rect, dp(8f), dp(8f), pressedPaint)
        }

        when (key.action) {
            // Uppercase mode has a solid arrow; lowercase only an outline with the key showing
            // through. No accent colour: the fill itself signals the active state.
            Action.SHIFT -> drawShiftGlyph(canvas, rect, COLOR_TEXT, filled = shift || caps)
            Action.BACKSPACE -> drawBackspaceGlyph(canvas, rect, COLOR_TEXT)
            Action.ENTER -> drawEnterGlyph(canvas, rect, COLOR_TEXT)
            Action.LANGUAGE -> drawGlobeGlyph(canvas, rect, COLOR_TEXT)
            else -> {
                val letterKey = key.action == Action.LETTER &&
                    key.label.length == 1 && key.label[0].isLetter()
                val digitKey = key.action == Action.LETTER &&
                    key.label.length == 1 && key.label[0].isDigit()
                val upperMode = shift || caps
                val label = when {
                    letterKey && upperMode -> key.upperLabel
                    letterKey -> key.lowerLabel
                    else -> key.label
                }
                if (label.isNotEmpty()) {
                    keyTextPaint.textAlign = Paint.Align.CENTER
                    keyTextPaint.color = if (key.action == Action.SPACE) COLOR_MUTED else COLOR_TEXT
                    val labelBaseline: Float
                    if (letterKey || digitKey) {
                        // Lowercase keeps its normal, centred placement. Capitals use a larger
                        // size and a lower baseline, so their growth goes into the free space at
                        // the bottom of the key instead of covering the secondary symbol.
                        keyTextPaint.textSize = when {
                            letterKey && upperMode -> uppercaseLetterTextSize
                            letterKey -> lowercaseLetterTextSize
                            else -> letterMetricsPaint.textSize
                        }
                        keyTextPaint.textScaleX = if (letterKey && !upperMode) {
                            lowercaseWidthScale(label[0])
                        } else {
                            1f
                        }
                        // Capitals differ from lowercase by size and baseline, not by weight:
                        // the larger glyph already reads as uppercase, so bold is unnecessary.
                        keyTextPaint.typeface = Typeface.DEFAULT
                        labelBaseline = if (letterKey && upperMode) {
                            uppercaseLetterBaseline(rect.centerY())
                        } else {
                            letterBaseline(rect.centerY())
                        }
                    } else {
                        keyTextPaint.typeface = Typeface.DEFAULT
                        keyTextPaint.textScaleX = 1f
                        keyTextPaint.textSize = when {
                            key.action == Action.SPACE -> dp(15f)
                            key.label.length > 2 -> dp(13f)
                            else -> dp(21f)
                        }
                        labelBaseline = baseline(rect.centerY(), keyTextPaint.textSize)
                    }
                    // Keep the larger letters clear of the superscript secondary symbol. The
                    // lowercase б has the tallest right-hand ascender, so it needs a little more
                    // room than the other letters.
                    val labelX = when {
                        letterKey && !upperMode && label == "б" -> rect.centerX() - dp(5f)
                        letterKey && key.secondary.isNotEmpty() -> rect.centerX() - dp(2f)
                        else -> rect.centerX()
                    }
                    canvas.drawText(label, labelX, labelBaseline, keyTextPaint)
                }
            }
        }

        if (key.secondary.isNotEmpty()) {
            secondaryPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                key.secondary,
                rect.right - dp(SECONDARY_RIGHT_INSET_DP),
                rect.top + dp(SECONDARY_BASELINE_DP),
                secondaryPaint
            )
        }
    }

    private fun ensureTrailTicker() {
        if (trailTickerRunning) return
        trailTickerRunning = true
        handler.postDelayed(trailTicker, TRAIL_FRAME_MILLIS)
    }

    private fun stopTrailTicker() {
        if (!trailTickerRunning) return
        trailTickerRunning = false
        handler.removeCallbacks(trailTicker)
    }

    private fun startPressFade(hit: Hit) {
        if (pressedHits.contains(hit)) return
        pressFadeStarts[hit] = SystemClock.uptimeMillis()
        if (!pressFadeTickerRunning) {
            pressFadeTickerRunning = true
            handler.postDelayed(pressFadeTicker, PRESS_FADE_FRAME_MILLIS)
        }
        invalidate()
    }

    private fun stopPressFadeTicker() {
        if (!pressFadeTickerRunning) return
        pressFadeTickerRunning = false
        handler.removeCallbacks(pressFadeTicker)
    }

    private fun pressHighlightAlpha(hit: Hit, now: Long): Int {
        if (pressedHits.contains(hit)) return 255
        val started = pressFadeStarts[hit] ?: return 0
        val elapsed = now - started
        if (elapsed >= PRESS_FADE_MILLIS) return 0
        return ((1f - elapsed.toFloat() / PRESS_FADE_MILLIS) * 255f)
            .toInt()
            .coerceIn(0, 255)
    }

    private fun drawSwipeTrails(canvas: Canvas) {        val now = SystemClock.uptimeMillis()
        for (trail in trails.values) drawTrail(canvas, trail, now)
        for (trail in fadingTrails) drawTrail(canvas, trail, now)
    }

    private fun drawTrail(canvas: Canvas, trail: SwipeTrail, now: Long) {
        val count = trail.size
        if (count < 2 || trail.alpha <= 0.01f) return

        for (index in 0 until count - 1) {
            val fraction = (index + 1f) / count
            val ageAlpha = trail.ageAlpha(index, now)
            if (ageAlpha <= 0.01f) continue
            val alpha = ((35f + 180f * fraction) * trail.alpha * ageAlpha)
                .toInt()
                .coerceIn(0, 255)
            trailGlowPaint.color = Color.argb((alpha * 0.28f).toInt(), 255, 107, 0)
            canvas.drawLine(
                trail.xs[index], trail.ys[index],
                trail.xs[index + 1], trail.ys[index + 1],
                trailGlowPaint
            )
            trailPaint.color = Color.argb(alpha, 255, 140, 0)
            canvas.drawLine(
                trail.xs[index], trail.ys[index],
                trail.xs[index + 1], trail.ys[index + 1],
                trailPaint
            )
        }

        val tipAlpha = trail.alpha * trail.ageAlpha(count - 1, now)
        if (tipAlpha > 0.15f) {
            trailTipPaint.color = Color.argb(
                (180 * tipAlpha).toInt().coerceIn(0, 255),
                255, 170, 40
            )
            canvas.drawCircle(trail.xs[count - 1], trail.ys[count - 1], dp(5f), trailTipPaint)
        }
    }

    private fun fadeTrail(pointerId: Int, trail: SwipeTrail) {
        if (trails[pointerId] !== trail) return
        trails.remove(pointerId)
        trail.animator?.cancel()
        fadingTrails.add(trail)
        if (trails.isEmpty()) stopTrailTicker()
        val animator = ValueAnimator.ofFloat(trail.alpha, 0f).apply {
            duration = TRAIL_FADE_MILLIS
            addUpdateListener {
                trail.alpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadingTrails.remove(trail)
                    trail.animator = null
                    invalidate()
                }
            })
        }
        trail.animator = animator
        animator.start()
    }

    private fun drawPreview(canvas: Canvas) {
        val label = previewLabel ?: return
        val bubbleWidth = dp(58f)
        val bubbleHeight = dp(52f)
        val centerX = previewX.coerceIn(
            bubbleWidth / 2f + dp(4f),
            width - bubbleWidth / 2f - dp(4f)
        )
        val bottom = (previewY - dp(6f)).coerceAtLeast(bubbleHeight + dp(4f))
        previewRect.set(centerX - bubbleWidth / 2f, bottom - bubbleHeight, centerX + bubbleWidth / 2f, bottom)
        canvas.drawRoundRect(previewRect, dp(12f), dp(12f), accentPaint)
        labelPaint.color = Color.WHITE
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = dp(24f)
        canvas.drawText(
            label,
            previewRect.centerX(),
            baseline(previewRect.centerY(), labelPaint.textSize),
            labelPaint
        )
    }

    private fun drawClipboard(canvas: Canvas) {
        canvas.drawRoundRect(clipboardHeader, dp(8f), dp(8f), functionPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = dp(15f)
        labelPaint.color = COLOR_TEXT
        canvas.drawText(
            clipboardBack,
            clipboardHeader.centerX(),
            baseline(clipboardHeader.centerY(), labelPaint.textSize),
            labelPaint
        )

        if (clipboardItems.isEmpty()) {
            labelPaint.color = COLOR_MUTED
            labelPaint.textSize = dp(14f)
            canvas.drawText(
                clipboardEmpty,
                clipboardViewport.centerX(),
                clipboardViewport.centerY(),
                labelPaint
            )
            return
        }

        val rowStep = dp(CLIPBOARD_ROW_STEP_DP)
        val rowHeight = dp(CLIPBOARD_ROW_HEIGHT_DP)
        canvas.save()
        canvas.clipRect(clipboardViewport)
        for (index in clipboardItems.indices) {
            val top = clipboardViewport.top + index * rowStep - clipboardScroll
            if (top > clipboardViewport.bottom) break
            if (top + rowHeight < clipboardViewport.top) continue

            clipboardRowRect.set(clipboardViewport.left, top, clipboardViewport.right, top + rowHeight)
            canvas.drawRoundRect(clipboardRowRect, dp(8f), dp(8f), keyPaint)

            val textLeft = clipboardRowRect.left + dp(12f)
            val textRight = clipboardRowRect.right - dp(12f)
            labelPaint.color = COLOR_TEXT
            labelPaint.textSize = dp(14f)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.save()
            canvas.clipRect(textLeft, clipboardRowRect.top, textRight, clipboardRowRect.bottom)
            canvas.drawText(
                if (index < clipboardLabels.size) clipboardLabels[index] else clipboardItems[index],
                textLeft,
                baseline(clipboardRowRect.centerY(), labelPaint.textSize),
                labelPaint
            )
            canvas.restore()
        }
        canvas.restore()

        if (maxClipboardScroll > 0f) {
            val trackWidth = dp(3f)
            val trackLeft = clipboardViewport.right - trackWidth
            val viewportHeight = clipboardViewport.height()
            val thumbHeight = max(
                dp(28f),
                viewportHeight * viewportHeight / (viewportHeight + maxClipboardScroll)
            )
            val progress = clipboardScroll / maxClipboardScroll
            val thumbTop = clipboardViewport.top + (viewportHeight - thumbHeight) * progress
            scrollTrackPaint.color = Color.argb(40, 255, 255, 255)
            canvas.drawRoundRect(
                trackLeft, clipboardViewport.top,
                clipboardViewport.right - dp(2f), clipboardViewport.bottom,
                trackWidth, trackWidth, scrollTrackPaint
            )
            scrollThumbPaint.color = Color.argb(120, 255, 255, 255)
            canvas.drawRoundRect(
                trackLeft, thumbTop,
                clipboardViewport.right - dp(2f), thumbTop + thumbHeight,
                trackWidth, trackWidth, scrollThumbPaint
            )
        }
    }

    // ------------------------------------------------------------------ vector glyphs

    /** Shared square used by the toolbar icons so their visual bounds match exactly. */
    private fun toolbarGlyphBounds(
        slot: RectF,
        out: RectF,
        fraction: Float = TOOLBAR_GLYPH_FRACTION
    ) {
        val size = min(slot.width(), slot.height()) * fraction
        out.set(
            slot.centerX() - size / 2f,
            slot.centerY() - size / 2f,
            slot.centerX() + size / 2f,
            slot.centerY() + size / 2f
        )
    }

    /**
     * Thin curved undo/redo arrow in the style of the reference panel: the arrowhead sits on the
     * left, the tail curls down to the right. [forward] mirrors it for redo.
     */
    private fun drawHistoryGlyph(canvas: Canvas, rect: RectF, forward: Boolean, color: Int) {
        val w = rect.width()
        val h = rect.height()
        val midX = rect.left + w * 0.40f
        val midY = rect.top + h * 0.30f
        iconPath.rewind()
        iconPath.moveTo(midX, midY)
        iconPath.cubicTo(
            rect.left + w * 0.78f, rect.top + h * 0.12f,
            rect.right - w * 0.02f, rect.top + h * 0.56f,
            rect.left + w * 0.52f, rect.top + h * 0.86f
        )
        val tipX = rect.left + w * 0.13f
        iconPath.moveTo(tipX, midY)
        iconPath.lineTo(midX, midY - h * 0.20f)
        iconPath.moveTo(tipX, midY)
        iconPath.lineTo(midX, midY + h * 0.20f)
        iconPaint.color = color
        canvas.save()
        if (forward) {
            canvas.translate(rect.left + rect.right, 0f)
            canvas.scale(-1f, 1f)
        }
        canvas.drawPath(iconPath, iconPaint)
        canvas.restore()
    }

    /** Light clipboard outline: rounded body, small top tab, three thin content lines. */
    private fun drawClipboardGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        val cx = rect.centerX()
        val inset = iconPaint.strokeWidth / 2f
        val bodyWidth = w * 0.62f
        val left = cx - bodyWidth / 2f
        val right = cx + bodyWidth / 2f
        val bodyTop = rect.top + h * 0.17f
        val bodyBottom = rect.bottom - inset

        iconPath.rewind()
        iconRect.set(left, bodyTop, right, bodyBottom)
        iconPath.addRoundRect(iconRect, dp(2.5f), dp(2.5f), Path.Direction.CW)
        val tabWidth = w * 0.34f
        iconRect.set(
            cx - tabWidth / 2f, rect.top + inset,
            cx + tabWidth / 2f, rect.top + h * 0.25f
        )
        iconPath.addRoundRect(iconRect, dp(1.8f), dp(1.8f), Path.Direction.CW)
        val lineLeft = left + bodyWidth * 0.20f
        val lineRight = right - bodyWidth * 0.20f
        val lineTop = bodyTop + (bodyBottom - bodyTop) * 0.31f
        val lineSpan = (bodyBottom - bodyTop) * 0.38f
        for (index in 0..2) {
            val y = lineTop + lineSpan * index / 2f
            iconPath.moveTo(lineLeft, y)
            iconPath.lineTo(lineRight, y)
        }
        iconPaint.color = color
        canvas.drawPath(iconPath, iconPaint)
    }

    /** Magic wand with a sparkle at the tip; the edit action behind it is not wired up yet. */
    private fun drawMagicGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        iconPath.rewind()
        iconPath.moveTo(rect.left + w * 0.15f, rect.bottom - h * 0.08f)
        iconPath.lineTo(rect.left + w * 0.65f, rect.top + h * 0.37f)
        iconPaint.color = color
        canvas.drawPath(iconPath, iconPaint)

        iconPath.rewind()
        addSparkle(
            iconPath,
            rect.left + w * 0.78f,
            rect.top + h * 0.22f,
            w * 0.17f
        )
        iconFillPaint.color = color
        canvas.drawPath(iconPath, iconFillPaint)

        iconPath.rewind()
        addSparkle(iconPath, rect.left + w * 0.34f, rect.top + h * 0.16f, w * 0.075f)
        addSparkle(iconPath, rect.right - w * 0.03f, rect.top + h * 0.62f, w * 0.055f)
        canvas.drawPath(iconPath, iconFillPaint)
    }

    /** Four-point star with slightly concave sides, used for the wand sparkles. */
    private fun addSparkle(path: Path, cx: Float, cy: Float, radius: Float) {
        val inner = radius * 0.30f
        path.moveTo(cx, cy - radius)
        path.quadTo(cx + inner, cy - inner, cx + radius, cy)
        path.quadTo(cx + inner, cy + inner, cx, cy + radius)
        path.quadTo(cx - inner, cy + inner, cx - radius, cy)
        path.quadTo(cx - inner, cy - inner, cx, cy - radius)
        path.close()
    }

    /**
     * Waving palm for the greeting button: an open hand with four rounded fingers and a thumb on
     * the right, plus two motion arcs on each side. Drawn from the supplied reference icon. The
     * stroke is thinner than the other toolbar glyphs: four finger outlines do not survive the
     * shared 1.65 dp weight at a 19 dp glyph size.
     */
    private fun drawWaveGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        fun x(value: Float): Float = rect.left + w * value
        fun y(value: Float): Float = rect.top + h * value

        val previousStroke = iconPaint.strokeWidth
        iconPaint.strokeWidth = dp(1.2f)
        iconPaint.color = color
        iconPath.rewind()
        // Left edge of the palm, then the pinky with its rounded tip and the first valley.
        iconPath.moveTo(x(0.40f), y(0.90f))
        iconPath.cubicTo(x(0.33f), y(0.87f), x(0.30f), y(0.79f), x(0.30f), y(0.70f))
        iconPath.cubicTo(x(0.29f), y(0.63f), x(0.27f), y(0.55f), x(0.27f), y(0.50f))
        iconPath.cubicTo(x(0.27f), y(0.43f), x(0.33f), y(0.41f), x(0.35f), y(0.46f))
        iconPath.cubicTo(x(0.36f), y(0.50f), x(0.37f), y(0.54f), x(0.38f), y(0.57f))
        // Ring finger.
        iconPath.cubicTo(x(0.37f), y(0.50f), x(0.36f), y(0.38f), x(0.36f), y(0.32f))
        iconPath.cubicTo(x(0.36f), y(0.25f), x(0.44f), y(0.24f), x(0.45f), y(0.32f))
        iconPath.cubicTo(x(0.46f), y(0.39f), x(0.46f), y(0.49f), x(0.47f), y(0.56f))
        // Middle finger, the tallest one.
        iconPath.cubicTo(x(0.47f), y(0.44f), x(0.46f), y(0.28f), x(0.47f), y(0.22f))
        iconPath.cubicTo(x(0.48f), y(0.14f), x(0.56f), y(0.15f), x(0.56f), y(0.23f))
        iconPath.cubicTo(x(0.56f), y(0.34f), x(0.56f), y(0.47f), x(0.57f), y(0.55f))
        // Index finger.
        iconPath.cubicTo(x(0.58f), y(0.45f), x(0.58f), y(0.33f), x(0.59f), y(0.28f))
        iconPath.cubicTo(x(0.59f), y(0.21f), x(0.67f), y(0.22f), x(0.67f), y(0.29f))
        iconPath.cubicTo(x(0.67f), y(0.39f), x(0.66f), y(0.50f), x(0.66f), y(0.56f))
        // Thumb: a short rounded loop on the right side of the palm.
        iconPath.cubicTo(x(0.71f), y(0.53f), x(0.76f), y(0.49f), x(0.79f), y(0.46f))
        iconPath.cubicTo(x(0.83f), y(0.43f), x(0.87f), y(0.48f), x(0.83f), y(0.53f))
        iconPath.cubicTo(x(0.79f), y(0.58f), x(0.75f), y(0.61f), x(0.71f), y(0.65f))
        // Right edge of the palm and the rounded wrist.
        iconPath.cubicTo(x(0.70f), y(0.73f), x(0.69f), y(0.82f), x(0.68f), y(0.89f))
        iconPath.cubicTo(x(0.62f), y(0.94f), x(0.47f), y(0.95f), x(0.40f), y(0.90f))
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)

        // Motion arcs, two on each side of the hand.
        iconPath.rewind()
        iconPath.moveTo(x(0.20f), y(0.53f))
        iconPath.quadTo(x(0.10f), y(0.60f), x(0.17f), y(0.72f))
        iconPath.moveTo(x(0.13f), y(0.48f))
        iconPath.quadTo(x(0.02f), y(0.56f), x(0.09f), y(0.75f))
        iconPath.moveTo(x(0.80f), y(0.18f))
        iconPath.quadTo(x(0.90f), y(0.24f), x(0.85f), y(0.35f))
        iconPath.moveTo(x(0.85f), y(0.12f))
        iconPath.quadTo(x(0.95f), y(0.19f), x(0.90f), y(0.31f))
        canvas.drawPath(iconPath, iconPaint)
        iconPaint.strokeWidth = previousStroke
    }

    private fun drawVoiceCancelGlyph(canvas: Canvas) {
        val size = voiceCancelIconRect.width()
        if (size <= 0f) return
        val cx = voiceCancelIconRect.centerX()
        val cy = voiceCancelIconRect.centerY()
        val radius = size / 2f
        canvas.drawCircle(cx, cy, radius, voiceCancelFillPaint)

        val inset = iconPaint.strokeWidth / 2f
        iconPath.rewind()
        iconPath.addCircle(cx, cy, (radius - inset).coerceAtLeast(1f), Path.Direction.CW)
        val arm = size * 0.17f
        iconPath.moveTo(cx - arm, cy - arm)
        iconPath.lineTo(cx + arm, cy + arm)
        iconPath.moveTo(cx + arm, cy - arm)
        iconPath.lineTo(cx - arm, cy + arm)
        iconPaint.color = COLOR_ACCENT
        canvas.drawPath(iconPath, iconPaint)
    }

    /** Classic microphone from the supplied reference: capsule outline, U cradle, stem and base. */
    private fun drawMicGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        val cx = rect.centerX()
        val inset = iconPaint.strokeWidth / 2f

        // Capsule (head) — outline, with its outer top exactly on the shared glyph bound.
        val bodyWidth = w * 0.27f
        val bodyTop = rect.top + inset
        val bodyBottom = rect.top + h * 0.57f
        iconRect.set(cx - bodyWidth / 2f, bodyTop, cx + bodyWidth / 2f, bodyBottom)
        iconPaint.color = color
        canvas.drawRoundRect(iconRect, bodyWidth / 2f, bodyWidth / 2f, iconPaint)

        // U-shaped cradle, completed as a half-ellipse under the capsule.
        val cradleHalfWidth = w * 0.22f
        val cradleTop = rect.top + h * 0.46f
        val cradleBottom = rect.top + h * 0.78f
        iconRect.set(
            cx - cradleHalfWidth,
            cradleTop - (cradleBottom - cradleTop),
            cx + cradleHalfWidth,
            cradleBottom
        )
        iconPath.rewind()
        iconPath.addArc(iconRect, 0f, 180f)

        // Stem down to the solid base.
        val baseTop = rect.bottom - h * 0.09f
        iconPath.moveTo(cx, cradleBottom)
        iconPath.lineTo(cx, baseTop)
        iconPaint.color = color
        canvas.drawPath(iconPath, iconPaint)

        iconRect.set(cx - w * 0.11f, baseTop, cx + w * 0.11f, rect.bottom)
        iconFillPaint.color = color
        canvas.drawRoundRect(iconRect, h * 0.015f, h * 0.015f, iconFillPaint)
    }

    private fun drawGlobeGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        val radius = min(w, h) * 0.30f
        val cx = rect.centerX()
        val cy = rect.centerY()
        iconPath.rewind()
        iconPath.addCircle(cx, cy, radius, Path.Direction.CW)
        iconRect.set(cx - radius * 0.45f, cy - radius, cx + radius * 0.45f, cy + radius)
        iconPath.addOval(iconRect, Path.Direction.CW)
        iconPath.moveTo(cx - radius, cy)
        iconPath.lineTo(cx + radius, cy)
        iconPaint.color = color
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawBackspaceGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        val top = rect.top + h * 0.25f
        val bottom = rect.bottom - h * 0.25f
        iconPath.rewind()
        iconPath.moveTo(rect.right - w * 0.14f, top)
        iconPath.lineTo(rect.left + w * 0.40f, top)
        iconPath.lineTo(rect.left + w * 0.16f, rect.centerY())
        iconPath.lineTo(rect.left + w * 0.40f, bottom)
        iconPath.lineTo(rect.right - w * 0.14f, bottom)
        iconPath.close()
        val xLeft = rect.left + w * 0.50f
        val xRight = rect.left + w * 0.66f
        iconPath.moveTo(xLeft, rect.centerY() - h * 0.12f)
        iconPath.lineTo(xRight, rect.centerY() + h * 0.12f)
        iconPath.moveTo(xRight, rect.centerY() - h * 0.12f)
        iconPath.lineTo(xLeft, rect.centerY() + h * 0.12f)
        iconPaint.color = color
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawEnterGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        iconPath.rewind()
        iconPath.moveTo(rect.left + w * 0.82f, rect.top + h * 0.24f)
        iconPath.lineTo(rect.left + w * 0.82f, rect.top + h * 0.64f)
        iconPath.lineTo(rect.left + w * 0.28f, rect.top + h * 0.64f)
        iconPath.moveTo(rect.left + w * 0.42f, rect.top + h * 0.46f)
        iconPath.lineTo(rect.left + w * 0.26f, rect.top + h * 0.64f)
        iconPath.lineTo(rect.left + w * 0.42f, rect.top + h * 0.82f)
        iconPaint.color = color
        canvas.drawPath(iconPath, iconPaint)
    }

    private fun drawShiftGlyph(canvas: Canvas, rect: RectF, color: Int, filled: Boolean) {
        val w = rect.width()
        val h = rect.height()
        val top = rect.top + h * 0.24f
        val mid = rect.top + h * 0.52f
        val bottom = rect.top + h * 0.78f
        iconPath.rewind()
        iconPath.moveTo(rect.centerX(), top)
        iconPath.lineTo(rect.left + w * 0.73f, mid)
        iconPath.lineTo(rect.left + w * 0.59f, mid)
        iconPath.lineTo(rect.left + w * 0.59f, bottom)
        iconPath.lineTo(rect.left + w * 0.41f, bottom)
        iconPath.lineTo(rect.left + w * 0.41f, mid)
        iconPath.lineTo(rect.left + w * 0.27f, mid)
        iconPath.close()
        if (filled) {
            iconFillPaint.color = color
            canvas.drawPath(iconPath, iconFillPaint)
        } else {
            iconPaint.color = color
            iconPaint.strokeWidth = dp(2f)
            canvas.drawPath(iconPath, iconPaint)
        }
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val allowSwipe = swipeEnabled && !symbols && !clipboardOpen
        router.route(
            event,
            if (allowSwipe) keyHits else emptyList(),
            if (allowSwipe) swipeLayout else null,
            touchSlop,
            ::hitKeyAt
        )
        if (clipboardOpen) {
            handleClipboardTouch(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                handleDown(event.actionIndex, event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                handleUp(event.actionIndex, event)
            MotionEvent.ACTION_CANCEL -> cancelTouchState()
        }
        return true
    }

    private fun handleDown(index: Int, event: MotionEvent) {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        if (voiceActive && voiceCancelHitRect.contains(x, y)) {
            listener?.onVoiceCancel()
            return
        }

        val suggestion = suggestionIndexAt(x, y)
        if (suggestion >= 0) {
            pendingSuggestions[id] = suggestion
            pendingSuggestionStart[id] = floatArrayOf(x, y)
            return
        }

        val hit = hitAt(x, y) ?: return
        pointerHits[id] = hit
        pressedHits.add(hit)
        pressFadeStarts.remove(hit)
        listener?.onKeyDown(hit.key)
        if (hit.key.action == Action.SPACE) {
            spaceStartX[id] = x
            spaceStartY[id] = y
        }
        scheduleLongPress(id, hit)
        invalidate()
    }

    private fun handleMove(event: MotionEvent) {
        val slop = dp(9f)
        for (index in 0 until event.pointerCount) {
            val id = event.getPointerId(index)
            val x = event.getX(index)
            val y = event.getY(index)

            val start = pendingSuggestionStart[id]
            if (start != null && (abs(x - start[0]) > slop || abs(y - start[1]) > slop)) {
                pendingSuggestions.remove(id)
                pendingSuggestionStart.remove(id)
            }

            if (router.isSwipeActive(id)) {
                // A swipe is not a long press and not a tap: stop both timers immediately.
                longPressTasks.remove(id)?.let(handler::removeCallbacks)
                cancelledPointers.add(id)
                val now = SystemClock.uptimeMillis()
                var trail = trails[id]
                if (trail == null) {
                    trail = SwipeTrail()
                    trail.add(router.startX(id), router.startY(id), now)
                    trails[id] = trail
                    ensureTrailTicker()
                }
                trail.add(x, y, now)
                invalidate()
            }

            val hit = pointerHits[id] ?: continue
            if (hit.key.action == Action.SPACE) {
                val startX = spaceStartX[id] ?: continue
                val startY = spaceStartY[id] ?: continue
                val dx = x - startX
                val dy = y - startY
                if (!spaceDragging.contains(id)) {
                    if (abs(dx) > slop && abs(dx) >= abs(dy)) {
                        spaceDragging.add(id)
                        listener?.onSpaceDragStart()
                    } else if (abs(dy) > slop) {
                        cancelledPointers.add(id)
                    }
                }
                if (spaceDragging.contains(id)) listener?.onSpaceDrag(dx)
            } else if (!cancelledPointers.contains(id) && !hit.rect.contains(x, y)) {
                val margin = dp(8f)
                if (x < hit.rect.left - margin || x > hit.rect.right + margin ||
                    y < hit.rect.top - margin || y > hit.rect.bottom + margin
                ) {
                    cancelledPointers.add(id)
                    longPressTasks.remove(id)?.let(handler::removeCallbacks)
                    if (hit.key.action == Action.BACKSPACE) listener?.onKeyUp(hit.key)
                }
            }
        }
    }

    private fun handleUp(index: Int, event: MotionEvent) {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        trails[id]?.let { trail ->
            trail.add(x, y, SystemClock.uptimeMillis())
            fadeTrail(id, trail)
        }

        longPressTasks.remove(id)?.let(handler::removeCallbacks)
        val wasLongPressed = longPressedPointers.remove(id)
        val wasCancelled = cancelledPointers.remove(id)
        val hit = pointerHits.remove(id)
        val suggestion = pendingSuggestions.remove(id)
        pendingSuggestionStart.remove(id)

        if (hit != null) {
            if (hit.key.action == Action.BACKSPACE) listener?.onKeyUp(hit.key)
            pressedHits.remove(hit)
            if (pointerHits.values.any { it === hit }) pressedHits.add(hit)
            if (!pressedHits.contains(hit)) startPressFade(hit)

            if (hit.key.action == Action.SPACE) {
                val startX = spaceStartX[id]
                if (spaceDragging.remove(id)) {
                    if (startX != null) listener?.onSpaceDrag(x - startX)
                    listener?.onSpaceDragEnd()
                } else if (!wasLongPressed && !wasCancelled && hitAt(x, y) === hit) {
                    listener?.onKeyAction(hit.key)
                }
            } else if (!wasLongPressed && !wasCancelled && !isInstant(hit.key) &&
                hitAt(x, y) === hit
            ) {
                listener?.onKeyAction(hit.key)
            }
        }

        spaceStartX.remove(id)
        spaceStartY.remove(id)
        cancelledPointers.remove(id)

        if (suggestion != null && suggestion in suggestionWords.indices &&
            statusText == null && suggestionRects[suggestion].contains(x, y)
        ) {
            listener?.onSuggestion(suggestionWords[suggestion])
        }

        previewLabel = null
        invalidate()
    }

    private fun cancelTouchState() {
        stopTrailTicker()
        stopPressFadeTicker()
        pressFadeStarts.clear()
        for (task in longPressTasks.values) handler.removeCallbacks(task)
        longPressTasks.clear()
        for (hit in pointerHits.values) {
            if (hit.key.action == Action.BACKSPACE) listener?.onKeyUp(hit.key)
        }
        if (spaceDragging.isNotEmpty()) listener?.onSpaceDragEnd()
        pointerHits.clear()
        pressedHits.clear()
        longPressedPointers.clear()
        cancelledPointers.clear()
        spaceStartX.clear()
        spaceStartY.clear()
        spaceDragging.clear()
        pendingSuggestions.clear()
        pendingSuggestionStart.clear()
        val allTrails = ArrayList<SwipeTrail>(trails.values)
        allTrails.addAll(fadingTrails)
        trails.clear()
        fadingTrails.clear()
        for (trail in allTrails) {
            trail.animator?.removeAllListeners()
            trail.animator?.cancel()
            trail.animator = null
        }
        previewLabel = null
        invalidate()
    }

    private fun scheduleLongPress(pointerId: Int, hit: Hit) {
        val key = hit.key
        val needsLongPress = key.secondary.isNotEmpty() ||
            key.action == Action.BACKSPACE ||
            key.action == Action.AC ||
            key.action == Action.LANGUAGE ||
            key.action == Action.GREETING
        if (!needsLongPress) return

        val task = Runnable {
            val current = pointerHits[pointerId] ?: return@Runnable
            if (current !== hit) return@Runnable
            longPressedPointers.add(pointerId)
            router.abortGesture(pointerId)
            trails.remove(pointerId)?.let { trail ->
                trail.animator?.removeAllListeners()
                trail.animator?.cancel()
            }
            if (trails.isEmpty()) stopTrailTicker()
            if (key.secondary.isNotEmpty() || key.action == Action.BACKSPACE) {
                previewLabel = if (key.secondary.isNotEmpty()) key.secondary else "⌫"
                previewX = hit.center.x
                previewY = hit.rect.top
                invalidate()
            }
            listener?.onKeyLongPress(key)
        }
        longPressTasks[pointerId] = task
        handler.postDelayed(task, LONG_PRESS_MILLIS)
    }

    /** Key under a touch-down point, using the same near-miss mapping as tap handling. */
    private fun hitKeyAt(x: Float, y: Float): Key? = hitAt(x, y)?.key

    private fun hitAt(x: Float, y: Float): Hit? {
        for (index in 0 until toolbarHits.size) {
            val hit = toolbarHits[index]
            if (hit.rect.contains(x, y)) return hit
        }
        for (index in 0 until keyHits.size) {
            val hit = keyHits[index]
            if (hit.rect.contains(x, y)) return hit
        }
        // Small gaps between keys used to swallow taps. Map a near-miss to the closest key so
        // every point inside the key area has a target; the nearest key also wins at a gap centre.
        if (keyHits.isEmpty()) return null
        var keyTop = Float.MAX_VALUE
        var keyBottom = -Float.MAX_VALUE
        for (index in 0 until keyHits.size) {
            val rect = keyHits[index].rect
            if (rect.top < keyTop) keyTop = rect.top
            if (rect.bottom > keyBottom) keyBottom = rect.bottom
        }
        if (y < keyTop - dp(2f) || y > keyBottom + dp(2f)) return null

        val maxDistance = dp(6f)
        val maxSquared = maxDistance * maxDistance
        var best: Hit? = null
        var bestSquared = Float.MAX_VALUE
        for (index in 0 until keyHits.size) {
            val rect = keyHits[index].rect
            val dx = when {
                x < rect.left -> rect.left - x
                x > rect.right -> x - rect.right
                else -> 0f
            }
            val dy = when {
                y < rect.top -> rect.top - y
                y > rect.bottom -> y - rect.bottom
                else -> 0f
            }
            val squared = dx * dx + dy * dy
            if (squared < bestSquared) {
                bestSquared = squared
                best = keyHits[index]
            }
        }
        return if (best != null && bestSquared <= maxSquared) best else null
    }

    private fun suggestionIndexAt(x: Float, y: Float): Int {
        if (statusText != null) return -1
        for (index in suggestionWords.indices) {
            if (suggestionRects[index].contains(x, y)) return index
        }
        return -1
    }

    private fun isInstant(key: Key) = when (key.action) {
        Action.LETTER, Action.COMMA, Action.PERIOD, Action.BACKSPACE, Action.SHIFT -> true
        else -> false
    }

    // ------------------------------------------------------------------ clipboard touch

    private fun handleClipboardTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (clipboardPointer != -1) return
                val index = event.actionIndex
                clipboardPointer = event.getPointerId(index)
                clipboardDownY = event.getY(index)
                clipboardDownScroll = clipboardScroll
                clipboardScrolling = false
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(clipboardPointer)
                if (index < 0) return
                val dy = event.getY(index) - clipboardDownY
                if (!clipboardScrolling && abs(dy) > dp(6f)) clipboardScrolling = true
                if (clipboardScrolling) {
                    clipboardScroll = (clipboardDownScroll - dy).coerceIn(0f, maxClipboardScroll)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) != clipboardPointer) return
                val x = event.getX(index)
                val y = event.getY(index)
                if (!clipboardScrolling) {
                    if (clipboardHeader.contains(x, y)) {
                        toggleClipboard()
                    } else {
                        val item = clipboardItemAt(y)
                        if (item >= 0) listener?.onClipboard(clipboardItems[item])
                    }
                }
                clipboardPointer = -1
                clipboardScrolling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                clipboardPointer = -1
                clipboardScrolling = false
            }
        }
    }

    private fun clipboardItemAt(y: Float): Int {
        if (y < clipboardViewport.top || y > clipboardViewport.bottom) return -1
        val rowStep = dp(CLIPBOARD_ROW_STEP_DP)
        val rowHeight = dp(CLIPBOARD_ROW_HEIGHT_DP)
        val relative = y - clipboardViewport.top + clipboardScroll
        val index = (relative / rowStep).toInt()
        if (index !in clipboardItems.indices) return -1
        val rowTop = clipboardViewport.top + index * rowStep - clipboardScroll
        return if (y >= rowTop && y <= rowTop + rowHeight) index else -1
    }

    // ------------------------------------------------------------------ misc

    private fun buildRows(): List<List<Key>> {
        if (symbols) return listOf(NUMBER_ROW, SYMBOL_ROW_2, SYMBOL_ROW_3, SYMBOL_ROW_4, bottomRow())
        val letters = if (russian) RUSSIAN_ROWS else ENGLISH_ROWS
        return listOf(
            NUMBER_ROW,
            letters[0],
            letters[1],
            listOf(Key("⇧", action = Action.SHIFT, weight = 1.25f)) + letters[2] +
                Key("⌫", action = Action.BACKSPACE, weight = 1.35f),
            bottomRow()
        )
    }

    private fun bottomRow(): List<Key> = listOf(
        Key(
            if (symbols) (if (russian) symbolsRu else symbolsEn) else "#1?",
            action = Action.SYMBOLS,
            weight = 1.3f
        ),
        Key(",", action = Action.COMMA),
        Key(if (russian) spaceRu else spaceEn, action = Action.SPACE, weight = 3.9f),
        Key(".", action = Action.PERIOD),
        Key("", action = Action.LANGUAGE),
        Key("", action = Action.ENTER, weight = 1.3f)
    )

    private fun isFunctionKey(key: Key) = when (key.action) {
        Action.SHIFT, Action.BACKSPACE, Action.ENTER, Action.LANGUAGE -> true
        else -> false
    }

    private fun updatePulse() {
        pulse?.cancel()
        pulse = null
        if (!voiceActive) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun buildMetrics(): Metrics {
        val density = resources.displayMetrics.density
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bottom = navigationInset.toFloat()
        return if (landscape) {
            Metrics(
                3f * density, 28f * density, 34f * density, 4f * density,
                34f * density, 3f * density, 3f * density + bottom
            )
        } else {
            Metrics(
                5f * density, 36f * density, 44f * density, 5f * density,
                50f * density, 4f * density, 5f * density + bottom
            )
        }
    }

    private fun baseline(centerY: Float, textSize: Float): Float = centerY + textSize * 0.35f

    /**
     * Lowercase letters are centred on the key using the font box (ascent + descent). This keeps
     * letters with ascenders/descenders (`б`, `р`, `у`, `д`, `g`, `p`, `y`) on the same optical
     * line as the rest of the lowercase row.
     */
    private fun letterBaseline(centerY: Float): Float {
        val metrics = letterMetricsPaint.fontMetrics
        return centerY - (metrics.ascent + metrics.descent) / 2f
    }

    /** Capitals keep the same top edge as before while their larger body grows downwards. */
    private fun uppercaseLetterBaseline(centerY: Float): Float =
        letterBaseline(centerY) + uppercaseBaselineShift

    /**
     * Horizontal correction per lowercase glyph: the scaled lowercase keeps its own shape, but its
     * bounding width is matched to the regular capital glyph at the base size. Results are cached
     * because this runs during onDraw.
     */
    private fun lowercaseWidthScale(letter: Char): Float {
        lowercaseWidthScales[letter]?.let { return it }
        val capital = letter.uppercaseChar()
        val capitalBounds = Rect()
        val lowercaseBounds = Rect()
        letterProbePaint.typeface = Typeface.DEFAULT
        letterProbePaint.textSize = letterMetricsPaint.textSize
        letterProbePaint.getTextBounds(capital.toString(), 0, 1, capitalBounds)
        letterProbePaint.typeface = Typeface.DEFAULT
        letterProbePaint.textSize = lowercaseLetterTextSize
        letterProbePaint.getTextBounds(letter.toString(), 0, 1, lowercaseBounds)
        val scale = if (lowercaseBounds.width() <= 0) {
            1f
        } else {
            (capitalBounds.width().toFloat() / lowercaseBounds.width()).coerceIn(0.8f, 1.25f)
        }
        lowercaseWidthScales[letter] = scale
        return scale
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private class SwipeTrail {
        var xs = FloatArray(64)
        var ys = FloatArray(64)
        var times = LongArray(64)
        var size = 0
        var alpha = 1f
        var animator: ValueAnimator? = null

        fun add(x: Float, y: Float, now: Long) {
            prune(now)
            if (size > 0) {
                val dx = x - xs[size - 1]
                val dy = y - ys[size - 1]
                if (dx * dx + dy * dy < 1f) return
            }
            if (size == MAX_TRAIL_POINTS) {
                // The TTL usually keeps the tail far below this cap; this is a hard safety bound
                // for very high touch-reporting rates or a stalled frame loop.
                System.arraycopy(xs, 1, xs, 0, size - 1)
                System.arraycopy(ys, 1, ys, 0, size - 1)
                System.arraycopy(times, 1, times, 0, size - 1)
                size--
            }
            if (size == xs.size) {
                xs = xs.copyOf(size * 2)
                ys = ys.copyOf(size * 2)
                times = times.copyOf(size * 2)
            }
            xs[size] = x
            ys[size] = y
            times[size] = now
            size++
        }

        /** Drops points older than the trail TTL. Returns true when the tail shrank. */
        fun prune(now: Long): Boolean {
            var first = 0
            while (first < size && now - times[first] > TRAIL_TTL_MILLIS) first++
            if (first == 0) return false
            if (first >= size) {
                size = 0
                return true
            }
            val remaining = size - first
            System.arraycopy(xs, first, xs, 0, remaining)
            System.arraycopy(ys, first, ys, 0, remaining)
            System.arraycopy(times, first, times, 0, remaining)
            size = remaining
            return true
        }

        /** 1 for the newest point, falling to 0 as the point reaches the TTL. */
        fun ageAlpha(index: Int, now: Long): Float {
            if (index < 0 || index >= size) return 0f
            val age = (now - times[index]).coerceAtLeast(0L)
            val ratio = (age.toFloat() / TRAIL_TTL_MILLIS).coerceIn(0f, 1f)
            // sqrt keeps the older part of the tail visible much longer than a linear fade,
            // which previously made the whole trail look like it vanished instantly.
            return sqrt(1f - ratio)
        }
    }

    private class Metrics(
        val top: Float,
        val suggestion: Float,
        val toolbar: Float,
        val gap: Float,
        val keyHeight: Float,
        val rowGap: Float,
        val bottom: Float
    ) {
        fun height(rows: Int): Float =
            top + suggestion + toolbar + gap + rows * keyHeight + (rows - 1) * rowGap + bottom
    }

    private companion object {
        const val ROW_COUNT = 5
        const val TOOL_AC = 0
        const val TOOL_UNDO = 1
        const val TOOL_REDO = 2
        const val TOOL_CLIPBOARD = 3
        const val TOOL_MAGIC = 4
        const val TOOL_GREETING = 5
        const val TOOL_MIC = 6
        const val MAX_CLIPBOARD_ITEMS = 20
        const val CLIPBOARD_ROW_STEP_DP = 46f
        const val CLIPBOARD_ROW_HEIGHT_DP = 40f
        const val LONG_PRESS_MILLIS = 330L
        const val MAX_TRAIL_POINTS = 160
        const val TRAIL_TTL_MILLIS = 250L
        const val TRAIL_FRAME_MILLIS = 16L
        const val TRAIL_FADE_MILLIS = 220L
        const val PRESS_FADE_MILLIS = 180L
        const val PRESS_FADE_FRAME_MILLIS = 16L
        // Lowercase keeps the base text size; capitals use their own larger scale.
        const val LETTER_TEXT_SIZE_DP = 24.5f
        const val UPPERCASE_LETTER_SCALE = 1.18f
        const val UPPERCASE_EXTRA_SHIFT_DP = 1f
        // Superscript secondary symbols: white, a little larger than before and tucked into the
        // top-right corner so «:», «;» and other thin glyphs stay legible.
        const val SECONDARY_TEXT_SIZE_DP = 12f
        const val SECONDARY_RIGHT_INSET_DP = 5f
        const val SECONDARY_BASELINE_DP = 13f
        const val TOOLBAR_GLYPH_FRACTION = 0.54f
        // The waving palm carries more detail than the other icons, so it gets a little more room.
        const val WAVE_GLYPH_FRACTION = 0.70f

        val TOOLBAR_ACTIONS = listOf(
            Action.UNDO, Action.REDO, Action.CLIPBOARD, Action.MAGIC, Action.GREETING
        )

        val COLOR_BACKGROUND = Color.rgb(28, 28, 30)
        val COLOR_SUGGESTION_STRIP = Color.rgb(32, 32, 34)
        val COLOR_KEY = Color.rgb(44, 44, 46)
        val COLOR_FUNCTION = Color.rgb(36, 36, 38)
        val COLOR_PRESSED = Color.rgb(78, 78, 82)
        val COLOR_CAPS = Color.rgb(58, 58, 60)
        val COLOR_TEXT = Color.rgb(245, 245, 247)
        val COLOR_SECONDARY = Color.rgb(142, 142, 147)
        val COLOR_MUTED = Color.rgb(174, 174, 178)
        val COLOR_ACCENT = Color.rgb(255, 140, 0)
        val COLOR_ERROR = Color.rgb(255, 123, 114)

        val NUMBER_ROW = listOf(
            Key("1"), Key("2"), Key("3"), Key("4"), Key("5"),
            Key("6"), Key("7"), Key("8"), Key("9"), Key("0")
        )

        val RUSSIAN_ROWS = listOf(
            listOf(
                Key("Й"), Key("Ц"), Key("У"), Key("К"), Key("Е", "Ё"), Key("Н"),
                Key("Г"), Key("Ш"), Key("Щ"), Key("З"), Key("Х")
            ),
            listOf(
                Key("Ф", "@"), Key("Ы", "#"), Key("В", "₽"), Key("А", "%"),
                Key("П", "&"), Key("Р", "-"), Key("О", "+"), Key("Л", "("),
                Key("Д", ")"), Key("Ж", "/"), Key("Э", "~")
            ),
            listOf(
                Key("Я", "*"), Key("Ч", "\""), Key("С", "'"), Key("М", ":"),
                Key("И", ";"), Key("Т", "!"), Key("Ь", "ъ"), Key("Б", "?"), Key("Ю")
            )
        )

        val ENGLISH_ROWS = listOf(
            listOf(
                Key("Q", "1"), Key("W", "2"), Key("E", "3"), Key("R", "4"), Key("T", "5"),
                Key("Y", "6"), Key("U", "7"), Key("I", "8"), Key("O", "9"), Key("P", "0")
            ),
            listOf(
                Key("A", "@"), Key("S", "#"), Key("D", "$"), Key("F", "%"), Key("G", "&"),
                Key("H", "-"), Key("J", "+"), Key("K", "("), Key("L", ")")
            ),
            listOf(
                Key("Z", "*"), Key("X", "\""), Key("C", "'"), Key("V", ":"),
                Key("B", ";"), Key("N", "!"), Key("M", "?")
            )
        )

        val SYMBOL_ROW_2 = listOf(
            Key("\\"), Key("<"), Key(">"), Key("«"), Key("»"),
            Key("{"), Key("}"), Key("["), Key("]"), Key("=")
        )

        val SYMBOL_ROW_3 = listOf(
            Key("&"), Key("#"), Key("₽"), Key("%"), Key("_"),
            Key("-"), Key("+"), Key("("), Key(")"), Key("/")
        )

        val SYMBOL_ROW_4 = listOf(
            Key("⇧", action = Action.SHIFT, weight = 1.25f),
            Key("@"), Key("*"), Key("\""), Key("'"), Key(":"),
            Key(";"), Key("!"), Key("?"), Key("~"),
            Key("⌫", action = Action.BACKSPACE, weight = 1.35f)
        )
    }
}
