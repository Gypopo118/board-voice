package com.voiceboard

/**
 * One completed gesture-typing trace.
 *
 * [path] is the flat `x0, y0, x1, y1, …` sequence used by the visual trail and the decoder.
 * [times] contains `SystemClock.uptimeMillis()`-based timestamps, one per point. It may be empty
 * for tests or callers that only have geometry; the decoder then falls back to uniform weights.
 */
class SwipeGesture(
    val path: FloatArray,
    val times: LongArray = LongArray(0)
) {
    val pointCount: Int get() = path.size / 2

    val hasTiming: Boolean get() = pointCount >= 2 && times.size == pointCount

    fun durationMillis(): Long =
        if (hasTiming) (times[times.size - 1] - times[0]).coerceAtLeast(0L) else 0L
}
