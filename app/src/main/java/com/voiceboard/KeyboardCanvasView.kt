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

/**
 * One canvas owns rendering, hit-testing and every raw touch stream.
 *
 * Geometry is rebuilt only when the layout, orientation or size changes; [onDraw] never allocates
 * per-key rectangles, which keeps repaints cheap while the microphone pulse is animating. All
 * pointers are tracked by id, so a second finger types immediately while the first one is still
 * holding a key.
 */
class KeyboardCanvasView(context: Context) : View(context) {

    enum class Action { LETTER, SHIFT, BACKSPACE, SPACE, SYMBOLS, LANGUAGE, ENTER, AC, CLIPBOARD, MIC, COMMA, PERIOD }

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

        /** Fired on touch-up for deferred keys (space, enter, layout, clipboard, mic, AC). */
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

    /** Number of points in all live swipe trails; exposed so the TTL can be tested. */
    internal fun activeTrailPointCount(): Int = trails.values.sumOf { it.size }

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
    private val toolbarHits = ArrayList<Hit>(3)
    private val suggestionRects = arrayOf(RectF(), RectF(), RectF())
    private var suggestionWords: List<String> = emptyList()
    private var suggestionLabels: List<String> = emptyList()

    private val pointerHits = HashMap<Int, Hit>()
    private val pressedHits = HashSet<Hit>()
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
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 107, 0) }
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
     * Fixed metrics used to place every letter on the same baseline. Bold and regular glyphs can
     * report slightly different font metrics, which would otherwise shift the Shift mode letters.
     */
    private val letterMetricsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = dp(LETTER_TEXT_SIZE_DP)
        isSubpixelText = true
    }
    private val suggestionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
        isSubpixelText = true
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SECONDARY
        textAlign = Paint.Align.RIGHT
        textSize = dp(11f)
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

        val side = dp(4f)
        val gap = dp(4f)
        val top = metrics.top

        val acRect = RectF(side, top, side + dp(58f), top + metrics.toolbar)
        toolbarHits.add(Hit(Key("AC", action = Action.AC), acRect, PointF(acRect.centerX(), acRect.centerY())))

        val micSize = metrics.toolbar
        val micRect = RectF(width - side - micSize, top, width - side, top + metrics.toolbar)
        val clipRect = RectF(micRect.left - gap - micSize, top, micRect.left - gap, top + metrics.toolbar)
        toolbarHits.add(Hit(Key("", action = Action.CLIPBOARD), clipRect, PointF(clipRect.centerX(), clipRect.centerY())))
        toolbarHits.add(Hit(Key("", action = Action.MIC), micRect, PointF(micRect.centerX(), micRect.centerY())))

        val suggestionLeft = acRect.right + gap
        val suggestionRight = clipRect.left - gap
        val slotWidth = (suggestionRight - suggestionLeft) / 3f
        for (index in 0 until 3) {
            val left = suggestionLeft + slotWidth * index
            suggestionRects[index].set(left, top, left + slotWidth, top + metrics.toolbar)
        }
        var y = top + metrics.toolbar + metrics.gap
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
        clipboardHeader.set(side, top, width - side, top + metrics.toolbar)
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
            return
        }
        val maxWidth = suggestionRects[0].width() - dp(12f)
        suggestionLabels = words.map { fitSuggestion(it, maxWidth) }
    }

    private fun fitSuggestion(value: String, maxWidth: Float): String {
        suggestionPaint.textSize = dp(15f)
        if (suggestionPaint.measureText(value) <= maxWidth) return value
        val ellipsis = "…"
        var end = value.length
        while (end > 1 &&
            suggestionPaint.measureText(value, 0, end) + suggestionPaint.measureText(ellipsis) > maxWidth
        ) {
            end--
        }
        return value.substring(0, end) + ellipsis
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
        for (index in keyHits.indices) drawKey(canvas, keyHits[index])
        drawSwipeTrails(canvas)
        drawPreview(canvas)
    }

    private fun drawToolbar(canvas: Canvas) {
        val ac = toolbarHits[0]
        val clip = toolbarHits[1]
        val mic = toolbarHits[2]

        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = dp(17f)
        labelPaint.color = if (pressedHits.contains(ac)) COLOR_MUTED else COLOR_TEXT
        canvas.drawText(
            "AC",
            ac.rect.left + dp(2f),
            baseline(ac.center.y, labelPaint.textSize),
            labelPaint
        )

        toolbarGlyphBounds(clip.rect, toolGlyphRect)
        drawClipboardGlyph(
            canvas,
            toolGlyphRect,
            if (pressedHits.contains(clip)) COLOR_MUTED else COLOR_TEXT
        )

        if (voiceActive) {
            val fraction = (pulse?.animatedValue as? Float) ?: 0f
            val alpha = (72 - 44 * fraction).toInt().coerceIn(0, 255)
            haloPaint.color = Color.argb(alpha, 255, 107, 0)
            canvas.drawCircle(mic.center.x, mic.center.y, dp(16f) + dp(6f) * fraction, haloPaint)
        }
        toolbarGlyphBounds(mic.rect, toolGlyphRect)
        val micColor = when {
            voiceActive -> COLOR_ACCENT
            pressedHits.contains(mic) -> COLOR_MUTED
            else -> COLOR_TEXT
        }
        drawMicGlyph(canvas, toolGlyphRect, micColor)

        if (statusText != null || voiceActive) drawStatus(canvas) else drawSuggestions(canvas)
    }

    private fun drawSuggestions(canvas: Canvas) {
        if (suggestionWords.isEmpty()) return
        suggestionPaint.textAlign = Paint.Align.CENTER
        suggestionPaint.textSize = dp(15f)
        for (index in suggestionWords.indices) {
            val rect = suggestionRects[index]
            val label = if (index < suggestionLabels.size) suggestionLabels[index] else suggestionWords[index]
            suggestionPaint.color = if (index == 0) COLOR_TEXT else COLOR_MUTED
            canvas.save()
            canvas.clipRect(rect.left + dp(3f), rect.top, rect.right - dp(3f), rect.bottom)
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
        val iconTop = suggestionRects[0].top + (metrics.toolbar - iconSize) / 2f
        voiceCancelIconRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

        val centerX = voiceCancelIconRect.centerX()
        val hitHalf = dp(20f)
        voiceCancelHitRect.set(
            (centerX - hitHalf).coerceAtLeast(areaLeft),
            metrics.top,
            (centerX + hitHalf).coerceAtMost(areaRight),
            metrics.top + metrics.toolbar
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

    private fun drawKey(canvas: Canvas, hit: Hit) {
        val key = hit.key
        val rect = hit.rect
        val background = when {
            pressedHits.contains(hit) -> pressedPaint
            key.action == Action.SHIFT && caps -> capsPaint
            isFunctionKey(key) -> functionPaint
            else -> keyPaint
        }
        canvas.drawRoundRect(rect, dp(8f), dp(8f), background)

        val iconColor = if (key.action == Action.SHIFT && (shift || caps)) COLOR_ACCENT else COLOR_TEXT
        when (key.action) {
            Action.SHIFT -> drawShiftGlyph(canvas, rect, iconColor)
            Action.BACKSPACE -> drawBackspaceGlyph(canvas, rect, COLOR_TEXT)
            Action.ENTER -> drawEnterGlyph(canvas, rect, COLOR_TEXT)
            Action.LANGUAGE -> drawGlobeGlyph(canvas, rect, COLOR_TEXT)
            else -> {
                val letterKey = key.action == Action.LETTER &&
                    key.label.length == 1 && key.label[0].isLetter()
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
                    if (letterKey) {
                        // Lower and upper case deliberately share one text size; only the weight
                        // distinguishes the normal state from Shift/Caps Lock.
                        keyTextPaint.textSize = dp(LETTER_TEXT_SIZE_DP)
                        keyTextPaint.typeface = if (upperMode) {
                            Typeface.DEFAULT_BOLD
                        } else {
                            Typeface.DEFAULT
                        }
                        labelBaseline = letterBaseline(rect.centerY())
                    } else {
                        keyTextPaint.typeface = Typeface.DEFAULT
                        keyTextPaint.textSize = when {
                            key.action == Action.SPACE -> dp(15f)
                            key.label.length > 2 -> dp(13f)
                            else -> dp(21f)
                        }
                        labelBaseline = baseline(rect.centerY(), keyTextPaint.textSize)
                    }
                    // Keep the larger letters clear of the superscript secondary symbol.
                    val labelX = if (letterKey && key.secondary.isNotEmpty()) {
                        rect.centerX() - dp(2f)
                    } else {
                        rect.centerX()
                    }
                    canvas.drawText(label, labelX, labelBaseline, keyTextPaint)
                }
            }
        }

        if (key.secondary.isNotEmpty()) {
            secondaryPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(key.secondary, rect.right - dp(7f), rect.top + dp(14f), secondaryPaint)
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

    private fun drawSwipeTrails(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
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
            val alpha = ((18f + 160f * fraction) * trail.alpha * ageAlpha)
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

    /** Shared square used by the mic and clipboard icons so their visual bounds match exactly. */
    private fun toolbarGlyphBounds(slot: RectF, out: RectF) {
        val size = min(slot.width(), slot.height()) * 0.60f
        out.set(
            slot.centerX() - size / 2f,
            slot.centerY() - size / 2f,
            slot.centerX() + size / 2f,
            slot.centerY() + size / 2f
        )
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

    private fun drawClipboardGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val w = rect.width()
        val h = rect.height()
        val cx = rect.centerX()
        val inset = iconPaint.strokeWidth / 2f
        val bodyWidth = w * 0.60f
        val left = cx - bodyWidth / 2f
        val right = cx + bodyWidth / 2f
        val bodyTop = rect.top + h * 0.12f
        val bodyBottom = rect.bottom - inset

        iconPath.rewind()
        iconRect.set(left, bodyTop, right, bodyBottom)
        iconPath.addRoundRect(iconRect, dp(3f), dp(3f), Path.Direction.CW)
        val tabWidth = w * 0.34f
        iconRect.set(
            cx - tabWidth / 2f, rect.top + inset,
            cx + tabWidth / 2f, rect.top + h * 0.24f
        )
        iconPath.addRoundRect(iconRect, dp(2f), dp(2f), Path.Direction.CW)
        val lineLeft = left + bodyWidth * 0.18f
        val lineRight = right - bodyWidth * 0.18f
        val lineTop = bodyTop + (bodyBottom - bodyTop) * 0.24f
        val lineSpan = (bodyBottom - bodyTop) * 0.52f
        for (index in 0..2) {
            val y = lineTop + lineSpan * index / 2f
            iconPath.moveTo(lineLeft, y)
            iconPath.lineTo(lineRight, y)
        }
        iconPaint.color = color
        canvas.drawPath(iconPath, iconPaint)
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

    private fun drawShiftGlyph(canvas: Canvas, rect: RectF, color: Int) {
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
        iconFillPaint.color = color
        canvas.drawPath(iconPath, iconFillPaint)
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val allowSwipe = swipeEnabled && !symbols && !clipboardOpen
        router.route(
            event,
            if (allowSwipe) keyHits else emptyList(),
            if (allowSwipe) swipeLayout else null,
            touchSlop
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

            if (hit.key.action == Action.SPACE) {
                val startX = spaceStartX[id]
                if (spaceDragging.remove(id)) {
                    if (startX != null) listener?.onSpaceDrag(x - startX)
                    listener?.onSpaceDragEnd()
                } else if (!wasLongPressed && !wasCancelled && hit.rect.contains(x, y)) {
                    listener?.onKeyAction(hit.key)
                }
            } else if (!wasLongPressed && !wasCancelled && !isInstant(hit.key) && hit.rect.contains(x, y)) {
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
            key.action == Action.LANGUAGE
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

    private fun hitAt(x: Float, y: Float): Hit? {
        for (index in 0 until toolbarHits.size) {
            val hit = toolbarHits[index]
            if (hit.rect.contains(x, y)) return hit
        }
        for (index in 0 until keyHits.size) {
            val hit = keyHits[index]
            if (hit.rect.contains(x, y)) return hit
        }
        return null
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
                3f * density, 34f * density, 4f * density,
                34f * density, 3f * density, 3f * density + bottom
            )
        } else {
            Metrics(
                5f * density, 44f * density, 5f * density,
                50f * density, 4f * density, 5f * density + bottom
            )
        }
    }

    private fun baseline(centerY: Float, textSize: Float): Float = centerY + textSize * 0.35f

    /**
     * Centres the font box (ascent + descent) rather than relying on a fraction of the text size.
     * This keeps lowercase letters with ascenders/descenders (`б`, `р`, `у`, `д`, `g`, `p`, `y`)
     * inside the key and on the same optical line as the capitalised Shift glyphs.
     */
    private fun letterBaseline(centerY: Float): Float {
        val metrics = letterMetricsPaint.fontMetrics
        return centerY - (metrics.ascent + metrics.descent) / 2f
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
            return (1f - age.toFloat() / TRAIL_TTL_MILLIS).coerceIn(0f, 1f)
        }
    }

    private class Metrics(
        val top: Float,
        val toolbar: Float,
        val gap: Float,
        val keyHeight: Float,
        val rowGap: Float,
        val bottom: Float
    ) {
        fun height(rows: Int): Float =
            top + toolbar + gap + rows * keyHeight + (rows - 1) * rowGap + bottom
    }

    private companion object {
        const val ROW_COUNT = 5
        const val MAX_CLIPBOARD_ITEMS = 20
        const val CLIPBOARD_ROW_STEP_DP = 46f
        const val CLIPBOARD_ROW_HEIGHT_DP = 40f
        const val LONG_PRESS_MILLIS = 330L
        const val MAX_TRAIL_POINTS = 128
        const val TRAIL_TTL_MILLIS = 200L
        const val TRAIL_FRAME_MILLIS = 16L
        const val TRAIL_FADE_MILLIS = 180L
        // Lower and upper case share one text size; Shift only changes case and weight.
        const val LETTER_TEXT_SIZE_DP = 24.5f

        val COLOR_BACKGROUND = Color.rgb(28, 28, 30)
        val COLOR_KEY = Color.rgb(44, 44, 46)
        val COLOR_FUNCTION = Color.rgb(36, 36, 38)
        val COLOR_PRESSED = Color.rgb(58, 58, 60)
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
