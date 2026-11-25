package com.voiceboard

import android.view.MotionEvent
import kotlin.math.sqrt

/**
 * Single entry point for every raw pointer stream the keyboard receives.
 *
 * Besides recording traces for the future, it detects a gesture-typing swipe: the touch must start
 * on a single-letter key, travel past the system touch slop, and reach at least one different letter
 * key. The latter keeps a tiny jitter inside one key from producing a word. A swipe keeps the
 * geometry snapshot it started on, so changing the layout with another finger cannot corrupt it.
 *
 * The space-bar cursor gesture reads [totalDx] from the same traces; ordinary multi-touch taps are
 * untouched because each pointer has its own state.
 */
class InputRouter {

    interface SwipeSink {
        /**
         * @param gesture completed trace with per-point timestamps.
         * @param startKey the letter key where the touch began.
         * @param layout geometry snapshot captured on touch-down.
         * @return true when the swipe was consumed and tap handling must be skipped.
         */
        fun onSwipeCompleted(
            gesture: SwipeGesture,
            startKey: KeyboardCanvasView.Key,
            layout: SwipeLayout
        ): Boolean
    }

    var swipeSink: SwipeSink? = null

    private class Trace(val startX: Float, val startY: Float) {
        val points = ArrayList<Float>(64)
        val times = ArrayList<Long>(32)
        var lastX = startX
        var lastY = startY
        var totalDx = 0f
        var totalDy = 0f
        var travel = 0f

        var startKey: KeyboardCanvasView.Key? = null
        var layout: SwipeLayout? = null
        var startLetter: Char = '\u0000'
        var visitedOtherLetter = false
        var swipe = false
        var eligible = false

        /** Adds a point unless it is practically identical to the previous one. */
        fun addPoint(x: Float, y: Float, time: Long) {
            if (points.isNotEmpty()) {
                val dx = x - lastX
                val dy = y - lastY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < 1f) return
                totalDx += dx
                totalDy += dy
                travel += distance
            }
            points.add(x)
            points.add(y)
            times.add(time)
            lastX = x
            lastY = y
        }
    }

    private val traces = HashMap<Int, Trace>()

    /**
     * @param keyAt the view's own hit resolver. It also maps a near-miss in the small gap between
     *   keys (and a point just outside the top/bottom row) to the nearest key, exactly like tap
     *   handling. Without it a swipe that started one or two dp off the key was typed as a single
     *   letter but never became eligible for gesture decoding.
     * @return true when this event was the end of a swipe consumed by [swipeSink].
     */
    fun route(
        event: MotionEvent,
        keys: List<KeyboardCanvasView.Hit>,
        layout: SwipeLayout?,
        touchSlop: Float,
        keyAt: ((Float, Float) -> KeyboardCanvasView.Key?)? = null
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                traces[id] = Trace(x, y).apply {
                    addPoint(x, y, event.eventTime)
                    if (layout != null) {
                        val key = keyAt?.invoke(x, y) ?: hitAt(keys, x, y)?.key
                        if (isLetterKey(key)) {
                            startKey = key
                            this.layout = layout
                            startLetter = key!!.label[0].lowercaseChar()
                            eligible = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val trace = traces[id] ?: continue

                    // MotionEvent batches several samples between frames. Historical points are
                    // much denser than the frame-rate stream and are essential for velocity and
                    // dwell estimates on a quick swipe.
                    for (history in 0 until event.historySize) {
                        val hx = event.getHistoricalX(index, history)
                        val hy = event.getHistoricalY(index, history)
                        trace.addPoint(hx, hy, event.getHistoricalEventTime(history))
                        updateSwipeState(trace, keys, touchSlop, hx, hy)
                    }

                    val x = event.getX(index)
                    val y = event.getY(index)
                    trace.addPoint(x, y, event.eventTime)
                    updateSwipeState(trace, keys, touchSlop, x, y)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val trace = traces.remove(id) ?: return false
                val x = event.getX(index)
                val y = event.getY(index)
                trace.addPoint(x, y, event.eventTime)
                updateSwipeState(trace, keys, touchSlop, x, y)
                val startKey = trace.startKey
                val startLayout = trace.layout
                if (trace.swipe && trace.visitedOtherLetter &&
                    startKey != null && startLayout != null
                ) {
                    return swipeSink?.onSwipeCompleted(
                        SwipeGesture(trace.points.toFloatArray(), trace.times.toLongArray()),
                        startKey,
                        startLayout
                    ) ?: false
                }
            }

            MotionEvent.ACTION_CANCEL -> traces.clear()
        }
        return false
    }

    private fun updateSwipeState(
        trace: Trace,
        keys: List<KeyboardCanvasView.Hit>,
        touchSlop: Float,
        x: Float,
        y: Float
    ) {
        if (!trace.eligible) return
        if (!trace.visitedOtherLetter) {
            val key = hitAt(keys, x, y)?.key
            if (isLetterKey(key) && key!!.label[0].lowercaseChar() != trace.startLetter) {
                trace.visitedOtherLetter = true
            }
        }
        if (!trace.swipe && trace.travel > touchSlop.coerceAtLeast(1f)) {
            trace.swipe = true
        }
    }

    fun isSwipeActive(pointerId: Int): Boolean = traces[pointerId]?.swipe == true

    fun startX(pointerId: Int): Float = traces[pointerId]?.startX ?: 0f

    fun startY(pointerId: Int): Float = traces[pointerId]?.startY ?: 0f

    fun totalDx(pointerId: Int): Float = traces[pointerId]?.totalDx ?: 0f

    fun totalDy(pointerId: Int): Float = traces[pointerId]?.totalDy ?: 0f

    /** Stops a swipe that turned into a long press, without disturbing the pointer's tap state. */
    fun abortGesture(pointerId: Int) {
        traces[pointerId]?.let {
            it.eligible = false
            it.swipe = false
        }
    }

    fun reset() = traces.clear()

    private fun hitAt(keys: List<KeyboardCanvasView.Hit>, x: Float, y: Float): KeyboardCanvasView.Hit? {
        for (index in keys.indices) {
            val hit = keys[index]
            if (hit.rect.contains(x, y)) return hit
        }
        return null
    }

    private fun isLetterKey(key: KeyboardCanvasView.Key?): Boolean =
        key != null &&
            key.action == KeyboardCanvasView.Action.LETTER &&
            key.label.length == 1 &&
            key.label[0].isLetter()
}
