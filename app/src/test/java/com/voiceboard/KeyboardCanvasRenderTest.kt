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
    fun lowercaseAndCapitalGlyphsShareVisualBounds() {
        val view = createView()
        for (letter in listOf('о', 'а', 'м')) {
            val lowercase = view.letterDrawBounds(false, letter)
            val capital = view.letterDrawBounds(true, letter)
            println(
                "glyph $letter: capital ${capital.width()}x${capital.height()}, " +
                    "lowercase ${lowercase.width()}x${lowercase.height()}"
            )
            assertEquals(
                "height of $letter",
                capital.height(),
                lowercase.height(),
                3f
            )
            assertEquals(
                "width of $letter",
                capital.width(),
                lowercase.width(),
                3f
            )
        }
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

        val x = 300f
        val y = 60f
        down(view, x, y)
        assertTrue(log.isEmpty())
        up(view, x, y)
        assertEquals(listOf("suggestion:привет"), log)
    }

    private fun createView(): KeyboardCanvasView {
        val view = KeyboardCanvasView(RuntimeEnvironment.getApplication())
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun render(name: String, configure: (KeyboardCanvasView) -> Unit) {
        val view = KeyboardCanvasView(RuntimeEnvironment.getApplication())
        configure(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
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
            log.add("action:" + key.label)
        }

        override fun onKeyLongPress(key: KeyboardCanvasView.Key) {
            log.add("long:" + key.label)
        }

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
    }
}
