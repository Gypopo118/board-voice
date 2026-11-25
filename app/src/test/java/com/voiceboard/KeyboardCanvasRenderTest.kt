package com.voiceboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Renders the real canvas to PNGs and exercises single/multi-touch hit-testing.
 * These are JVM tests (Robolectric native graphics), no emulator required.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class KeyboardCanvasRenderTest {

    @Test
    fun renderLayouts() {
        render("keyboard-ru") {
            it.setLanguage(true)
            it.suggestions = listOf("привет", "приветик", "приветствие")
        }
        render("keyboard-en") {
            it.setLanguage(false)
            it.suggestions = listOf("hello", "help", "hell")
        }
        render("keyboard-symbols") {
            it.setLanguage(true)
            it.toggleSymbols()
        }
        render("keyboard-caps-voice") {
            it.setLanguage(true)
            it.setShift(false, true)
            it.voiceActive = true
            it.showStatus("Идет запись 0:05", false, 0L)
        }
        render("keyboard-recording") {
            it.setLanguage(true)
            it.voiceActive = true
            it.showStatus("Идет запись 0:07", false, 0L)
        }
        render("keyboard-magic") {
            it.setLanguage(true)
            it.showStatus("Правлю текст…", false, 0L)
        }
        render("keyboard-shift") {
            it.setLanguage(true)
            it.setShift(true, false)
            it.suggestions = listOf("Привет", "Приветик", "Приветствие")
        }
        render("keyboard-clipboard") {
            it.setLanguage(true)
            it.setClipboard(
                listOf(
                    "Первый скопированный фрагмент",
                    "Второй фрагмент текста для проверки",
                    "https://example.com/very/long/address/for/the/clipboard/panel",
                    "Четвёртый фрагмент",
                    "Пятый фрагмент",
                    "Шестой фрагмент",
                    "Седьмой фрагмент",
                    "Восьмой фрагмент",
                    "Девятый фрагмент",
                    "Десятый фрагмент",
                    "Одиннадцатый фрагмент",
                    "Двенадцатый фрагмент"
                )
            )
            it.toggleClipboard()
        }
    }

    @Test
    fun renderNarrowPhoneToolbar() {
        val view = KeyboardCanvasView(RuntimeEnvironment.getApplication())
        view.setLanguage(true)
        view.suggestions = listOf("привет", "приветик", "приветствие")
        view.measure(
            View.MeasureSpec.makeMeasureSpec(NARROW_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        draw(view, "keyboard-narrow")
    }

    @Test
    fun tapsLettersAndSupportsRollover() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)

        val first = key(view, "Й")
        tap(view, first.center.x, first.center.y)
        assertEquals(listOf("down:Й"), log)

        log.clear()
        val second = key(view, "Ц")
        val third = key(view, "У")
        down(view, second.center.x, second.center.y)
        secondDown(view, second.center.x, second.center.y, third.center.x, third.center.y)
        secondUp(view, second.center.x, second.center.y, third.center.x, third.center.y)
        up(view, second.center.x, second.center.y)
        assertEquals(listOf("down:Ц", "down:У"), log)
    }

    @Test
    fun longPressShowsSecondaryPreviewAndFiresOnce() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)

        val e = key(view, "Е")
        down(view, e.center.x, e.center.y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(listOf("down:Е", "long:Е"), log)
        draw(view, "keyboard-longpress-preview")
        up(view, e.center.x, e.center.y)
        assertEquals(listOf("down:Е", "long:Е"), log)
    }

    @Test
    fun spaceDragReportsStartDeltaAndEnd() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val space = view.keyMap.first { it.key.action == KeyboardCanvasView.Action.SPACE }
        val startX = space.center.x
        val y = space.center.y

        down(view, startX, y)
        assertTrue(log.none { it.startsWith("space-") })
        log.clear()
        view.dispatchTouchEvent(single(MotionEvent.ACTION_MOVE, startX + 120f, y))
        up(view, startX + 120f, y)

        assertEquals("space-start", log.first())
        assertEquals("space-end", log.last())
        assertTrue(log.any { it.startsWith("space-drag:") })
    }

    @Test
    fun spaceDragContinuesOverTheLetterKeys() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val space = view.keyMap.first { it.key.action == KeyboardCanvasView.Action.SPACE }
        val letter = key(view, "Й")
        val startX = space.center.x
        val startY = space.center.y

        down(view, startX, startY)
        log.clear()
        move(view, startX + 60f, startY)
        // The finger leaves the spacebar and keeps moving above the letter row: the drag must stay
        // attached to the space pointer instead of being cancelled halfway.
        move(view, startX + 90f, letter.center.y)
        move(view, startX + 130f, letter.center.y)
        up(view, startX + 130f, letter.center.y)

        assertEquals("space-start", log.first())
        assertEquals("space-end", log.last())
        assertTrue(
            log.any { it.startsWith("space-drag:") && it.substringAfter(':').toFloat() >= 120f }
        )
    }

    @Test
    fun swipeStartsFromALetterAndDrawsTrail() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val start = key(view, "Й")
        val finish = key(view, "Ц")

        down(view, start.center.x, start.center.y)
        move(view, finish.center.x, finish.center.y)
        draw(view, "keyboard-swipe-trail")
        up(view, finish.center.x, finish.center.y)

        assertEquals(listOf("down:Й", "swipe:Й"), log)
    }

    @Test
    fun rendersWordSwipeTrail() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val letters = listOf("П", "Р", "И", "В", "Е", "Т")
        val first = key(view, letters.first())
        val last = key(view, letters.last())

        down(view, first.center.x, first.center.y)
        for (label in letters.drop(1)) {
            val hit = key(view, label)
            move(view, hit.center.x, hit.center.y)
        }
        draw(view, "keyboard-swipe-word")
        up(view, last.center.x, last.center.y)

        assertEquals(listOf("down:П", "swipe:П"), log)
    }

    @Test
    fun swipeTrailExpiresWhileFingerIsStillDown() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val start = key(view, "Й")
        val next = key(view, "Ц")

        down(view, start.center.x, start.center.y)
        move(view, next.center.x, next.center.y)
        assertTrue(view.activeTrailPointCount() > 0)

        // TTL is 250 ms; while the finger is still down the old tail must disappear.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(0, view.activeTrailPointCount())

        up(view, next.center.x, next.center.y)
    }

    @Test
    fun swipeTraceCarriesTimestamps() {
        val view = createView()
        val listener = RecordingListener(mutableListOf())
        view.listener = listener
        val start = key(view, "Й")
        val next = key(view, "Ц")

        down(view, start.center.x, start.center.y)
        move(view, next.center.x, next.center.y)
        up(view, next.center.x, next.center.y)

        val gesture = requireNotNull(listener.lastGesture)
        assertEquals(gesture.pointCount, gesture.times.size)
        assertTrue(gesture.hasTiming)
        assertTrue(gesture.durationMillis() >= 0L)
    }

    @Test
    fun swipeStartingJustAboveTheTopRowIsStillRecognised() {
        val view = createView()
        val listener = RecordingListener(mutableListOf())
        view.listener = listener
        val start = key(view, "К")
        val next = key(view, "Е")
        val end = key(view, "Н")
        // Tap handling resolves a 1 px near-miss above the key; the swipe router must use the
        // same resolver, otherwise only the starter letter is typed and the rest is ignored.
        val y = start.rect.top - 1f

        down(view, start.center.x, y)
        move(view, next.center.x, next.center.y)
        move(view, end.center.x, end.center.y)
        up(view, end.center.x, end.center.y)

        assertTrue("no swipe was produced from an edge hit", listener.lastGesture != null)
    }

    @Test
    fun swipeStartingJustBelowTheBottomRowIsStillRecognised() {
        val view = createView()
        val listener = RecordingListener(mutableListOf())
        view.listener = listener
        val start = key(view, "М")
        val next = key(view, "И")
        val end = key(view, "Т")
        val y = start.rect.bottom + 1f

        down(view, start.center.x, y)
        move(view, next.center.x, next.center.y)
        move(view, end.center.x, end.center.y)
        up(view, end.center.x, end.center.y)

        assertTrue("no swipe was produced from an edge hit", listener.lastGesture != null)
    }

    @Test
    fun greetingPhrasesEndWithAFullStop() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("привет.", app.getString(R.string.greeting_short))
        assertEquals("добрый день.", app.getString(R.string.greeting_long))
    }

    @Test
    fun capitalsAreLargerAndShiftedDown() {
        val view = createView()
        val density = view.resources.displayMetrics.density
        val key = view.keyMap.first { it.key.label == "О" }
        for (letter in listOf('о', 'а', 'м')) {
            val lowercase = view.letterDrawBounds(false, letter)
            val capital = view.letterDrawBounds(true, letter)
            val lowercaseBaseline = view.letterBaselineY(key.center.y, false)
            val capitalBaseline = view.letterBaselineY(key.center.y, true)
            val lowercaseTop = lowercaseBaseline + lowercase.top
            val capitalTop = capitalBaseline + capital.top
            println(
                "glyph $letter: capital ${capital.width() / density}x${capital.height() / density} " +
                    "top=${capitalTop / density}, lowercase " +
                    "${lowercase.width() / density}x${lowercase.height() / density} " +
                    "top=${lowercaseTop / density}, baselines " +
                    "${capitalBaseline / density}/${lowercaseBaseline / density}"
            )

            assertTrue("capital $letter is taller", capital.height() > lowercase.height() + 2f * density)
            assertTrue("capital $letter is wider", capital.width() > lowercase.width())
            assertTrue("capital $letter sits lower", capitalBaseline > lowercaseBaseline + 2f * density)
            assertTrue(
                "capital $letter top stays clear of the secondary symbol",
                capitalTop >= key.rect.top + 15f * density
            )
            assertTrue(
                "capital $letter does not grow above the lowercase glyph",
                capitalTop >= lowercaseTop - 0.5f * density
            )
            assertTrue(
                "capital $letter bottom stays inside the key",
                capitalBaseline + capital.bottom <= key.rect.bottom - 6f * density
            )
        }
    }

    @Test
    fun lowercaseBeKeepsItsFullUpperTail() {
        val view = createView()
        val density = view.resources.displayMetrics.density
        val bounds = view.letterDrawBounds(false, 'б')

        assertTrue(
            "lowercase б should keep the tall ascender with its upper tail",
            -bounds.top > 22f * density && bounds.height() > 22f * density
        )
    }

    @Test
    fun rapidSequentialTapsAreAllDelivered() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val letters = listOf("Й", "Ц", "У", "К", "Е", "Н")

        for (letter in letters) {
            val hit = key(view, letter)
            tap(view, hit.center.x, hit.center.y)
        }

        assertEquals(letters.map { "down:$it" }, log)
    }

    @Test
    fun gapBetweenKeysMapsToNearestKey() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val left = key(view, "Й")
        val right = key(view, "Ц")
        val gapX = (left.rect.right + right.rect.left) / 2f

        tap(view, gapX, left.center.y)

        assertEquals(1, log.size)
        assertTrue(log.first().startsWith("down:"))
    }

    @Test
    fun gapTapStillFiresADeferredKey() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val space = view.keyMap.first { it.key.action == KeyboardCanvasView.Action.SPACE }
        val period = key(view, ".")
        val gapX = space.rect.right + (period.rect.left - space.rect.right) * 0.25f

        tap(view, gapX, space.center.y)

        assertTrue("space action not delivered: $log", log.contains("action:" + space.key.label))
    }

    @Test
    fun keyHighlightFadesAfterRelease() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val hit = key(view, "Й")

        tap(view, hit.center.x, hit.center.y)
        assertTrue(view.activePressFadeCount() > 0)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        draw(view, "keyboard-press-fade")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(0, view.activePressFadeCount())
    }

    @Test
    fun swipeDoesNotStartOnSymbolsOrWhenDisabled() {
        val symbolView = createView()
        val symbolLog = mutableListOf<String>()
        symbolView.listener = RecordingListener(symbolLog)
        symbolView.toggleSymbols()
        val symbolKey = key(symbolView, "!")
        down(symbolView, symbolKey.center.x, symbolKey.center.y)
        move(symbolView, symbolKey.center.x + 120f, symbolKey.center.y)
        up(symbolView, symbolKey.center.x + 120f, symbolKey.center.y)
        assertTrue(symbolLog.none { it.startsWith("swipe:") })

        val disabledView = createView()
        val disabledLog = mutableListOf<String>()
        disabledView.listener = RecordingListener(disabledLog)
        disabledView.swipeEnabled = false
        val start = key(disabledView, "Й")
        val finish = key(disabledView, "Ц")
        down(disabledView, start.center.x, start.center.y)
        move(disabledView, finish.center.x, finish.center.y)
        up(disabledView, finish.center.x, finish.center.y)
        assertTrue(disabledLog.none { it.startsWith("swipe:") })
    }

    @Test
    fun voiceCancelButtonIsClickableDuringRecording() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        view.voiceActive = true
        view.showStatus("Идет запись 0:00", false, 0L)
        draw(view, "keyboard-recording-cancel")

        val bounds = view.voiceCancelHitBounds()
        tap(view, bounds.centerX(), bounds.centerY())
        assertEquals(listOf("voice-cancel"), log)
    }

    @Test
    fun suggestionTapIsDeferredUntilUp() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        view.suggestions = listOf("привет", "приветик", "приветствие")

        val slot = view.suggestionHitBounds(0)
        val x = slot.centerX()
        val y = slot.centerY()
        down(view, x, y)
        assertTrue(log.isEmpty())
        up(view, x, y)
        assertEquals(listOf("suggestion:привет"), log)
    }

    @Test
    @Config(qualifiers = "land-xxhdpi")
    fun landscapePanelKeepsTheStripAboveTheToolbar() {
        val view = createView()
        val ac = view.toolbarHitBounds(KeyboardCanvasView.Action.AC)
        val mic = view.toolbarHitBounds(KeyboardCanvasView.Action.MIC)
        val slot = view.suggestionHitBounds(0)

        assertTrue(slot.top >= 0f)
        assertTrue(slot.bottom <= ac.top)
        assertTrue(ac.bottom <= view.keyMap.first().rect.top)
        assertTrue(mic.right <= view.measuredWidth)
        assertTrue(view.measuredHeight > 0)
        draw(view, "keyboard-landscape")
    }

    @Test
    fun suggestionsUseTheFullStripAboveTheToolbar() {
        val view = createView()
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density

        val first = view.suggestionHitBounds(0)
        val second = view.suggestionHitBounds(1)
        val third = view.suggestionHitBounds(2)
        assertEquals(6f * density, first.left, 0.5f)
        assertEquals(view.measuredWidth - 6f * density, third.right, 0.5f)
        assertEquals(first.right, second.left, 0.01f)
        assertEquals(second.right, third.left, 0.01f)
        // The strip sits above the toolbar, and the toolbar above the first key row.
        assertEquals(first.bottom, view.toolbarHitBounds(KeyboardCanvasView.Action.AC).top, 0.01f)
        assertTrue(first.bottom <= view.keyMap.first().rect.top)
    }

    @Test
    fun toolbarButtonsAreEvenlySpacedWithSymmetricInsets() {
        val view = createView()
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val edge = 10f * density
        val order = listOf(
            KeyboardCanvasView.Action.AC,
            KeyboardCanvasView.Action.UNDO,
            KeyboardCanvasView.Action.REDO,
            KeyboardCanvasView.Action.CLIPBOARD,
            KeyboardCanvasView.Action.MAGIC,
            KeyboardCanvasView.Action.GREETING,
            KeyboardCanvasView.Action.MIC
        )
        val bounds = order.map { view.toolbarHitBounds(it) }

        // AC starts at the same inset the microphone ring leaves on the right edge.
        assertEquals(edge, bounds.first().left, 0.5f)
        assertEquals(view.measuredWidth - edge, bounds.last().right, 0.5f)
        for (index in 1 until bounds.size) {
            assertTrue(
                "${order[index]} must be right of ${order[index - 1]}",
                bounds[index].left > bounds[index - 1].right
            )
        }
        val gaps = bounds.zipWithNext { left, right -> right.left - left.right }
        for (gap in gaps) assertEquals(gaps.first(), gap, 0.5f)
    }

    @Test
    fun greetingFiresOnTapAndLongPress() {
        val view = createView()
        val log = mutableListOf<String>()
        view.listener = RecordingListener(log)
        val bounds = view.toolbarHitBounds(KeyboardCanvasView.Action.GREETING)

        down(view, bounds.centerX(), bounds.centerY())
        log.clear()
        up(view, bounds.centerX(), bounds.centerY())
        assertEquals(listOf("action:GREETING"), log)

        down(view, bounds.centerX(), bounds.centerY())
        log.clear()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(listOf("long:GREETING"), log)
        up(view, bounds.centerX(), bounds.centerY())
        assertEquals("tap action must not fire after a long press", listOf("long:GREETING"), log)
    }

    private fun createView(): KeyboardCanvasView {
        val view = KeyboardCanvasView(RuntimeEnvironment.getApplication())
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun render(name: String, configure: (KeyboardCanvasView) -> Unit) {
        val view = KeyboardCanvasView(RuntimeEnvironment.getApplication())
        configure(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        draw(view, name)
    }

    private fun draw(view: KeyboardCanvasView, name: String) {
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/robolectric/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("rendered " + file.absolutePath + " " + bitmap.width + "x" + bitmap.height)
    }

    private fun key(view: KeyboardCanvasView, label: String): KeyboardCanvasView.Hit =
        view.keyMap.first { it.key.label == label }

    private fun tap(view: KeyboardCanvasView, x: Float, y: Float) {
        down(view, x, y)
        up(view, x, y)
    }

    private fun down(view: KeyboardCanvasView, x: Float, y: Float) {
        view.dispatchTouchEvent(single(MotionEvent.ACTION_DOWN, x, y))
    }

    private fun move(view: KeyboardCanvasView, x: Float, y: Float) {
        view.dispatchTouchEvent(single(MotionEvent.ACTION_MOVE, x, y))
    }

    private fun up(view: KeyboardCanvasView, x: Float, y: Float) {
        view.dispatchTouchEvent(single(MotionEvent.ACTION_UP, x, y))
    }

    private fun secondDown(view: KeyboardCanvasView, x0: Float, y0: Float, x1: Float, y1: Float) {
        view.dispatchTouchEvent(
            pair(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), x0, y0, x1, y1)
        )
    }

    private fun secondUp(view: KeyboardCanvasView, x0: Float, y0: Float, x1: Float, y1: Float) {
        view.dispatchTouchEvent(
            pair(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), x0, y0, x1, y1)
        )
    }

    private fun single(action: Int, x: Float, y: Float): MotionEvent {
        val now = System.currentTimeMillis()
        val properties = arrayOf(pointerProperties(0))
        val coordinates = arrayOf(pointerCoords(x, y))
        return MotionEvent.obtain(
            now, now, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    private fun pair(action: Int, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent {
        val now = System.currentTimeMillis()
        val properties = arrayOf(pointerProperties(0), pointerProperties(1))
        val coordinates = arrayOf(pointerCoords(x0, y0), pointerCoords(x1, y1))
        return MotionEvent.obtain(
            now, now, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }

    private fun pointerProperties(id: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun pointerCoords(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }

    private class RecordingListener(private val log: MutableList<String>) : KeyboardCanvasView.Listener {
        var lastGesture: SwipeGesture? = null
            private set

        override fun onKeyDown(key: KeyboardCanvasView.Key) {
            log.add("down:" + key.label)
        }

        override fun onKeyAction(key: KeyboardCanvasView.Key) {
            log.add("action:" + keyName(key))
        }

        override fun onKeyLongPress(key: KeyboardCanvasView.Key) {
            log.add("long:" + keyName(key))
        }

        private fun keyName(key: KeyboardCanvasView.Key): String =
            if (key.label.isEmpty()) key.action.name else key.label

        override fun onKeyUp(key: KeyboardCanvasView.Key) {
            log.add("up:" + key.label)
        }

        override fun onSpaceDragStart() {
            log.add("space-start")
        }

        override fun onSpaceDrag(totalDx: Float) {
            log.add("space-drag:" + totalDx)
        }

        override fun onSpaceDragEnd() {
            log.add("space-end")
        }

        override fun onSuggestion(word: String) {
            log.add("suggestion:" + word)
        }

        override fun onClipboard(text: String) {
            log.add("clipboard:" + text)
        }

        override fun onSwipe(
            path: FloatArray,
            startKey: KeyboardCanvasView.Key,
            layout: SwipeLayout
        ): Boolean {
            log.add("swipe:" + startKey.label)
            return true
        }

        override fun onSwipe(
            gesture: SwipeGesture,
            startKey: KeyboardCanvasView.Key,
            layout: SwipeLayout
        ): Boolean {
            lastGesture = gesture
            return onSwipe(gesture.path, startKey, layout)
        }

        override fun onVoiceCancel() {
            log.add("voice-cancel")
        }
    }

    private companion object {
        const val WIDTH_PX = 1225
        const val NARROW_WIDTH_PX = 1080
        const val HEIGHT_PX = 1400
    }
}
