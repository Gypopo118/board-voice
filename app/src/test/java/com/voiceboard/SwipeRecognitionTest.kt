package com.voiceboard

import android.graphics.PointF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import java.util.Random

/**
 * Accuracy tests against the real frequency dictionaries and the real ЙЦУКЕН/QWERTY geometry.
 * Paths are synthesized from key centres, with a slight human-like bow, then decoded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
class SwipeRecognitionTest {

    @Test
    fun recognizesRussianWords() {
        val engine = loadEngine()
        val layout = layout(true)
        val lexicon = engine.swipeLexicon(true)
        assertTrue(lexicon != null)
        val decoder = SwipeEngine()
        val words = listOf(
            "привет", "класс", "длинный", "машина", "молоко", "программа",
            "телефон", "клавиатура", "пожалуйста", "сегодня", "словарь",
            "траектория", "здравствуйте", "человек", "россия"
        )
        var top1 = 0
        for (word in words) {
            val results = decoder.decode(pathFor(word, layout), layout, lexicon!!, 3)
            val rendered = results.map { it.word }
            println("RU $word -> $rendered")
            assertTrue("$word not in top3: $rendered", rendered.contains(word))
            if (rendered.firstOrNull() == word) top1++
        }
        println("RU top1=$top1/${words.size}")
    }

    @Test
    fun recognizesEnglishWords() {
        val engine = loadEngine()
        val layout = layout(false)
        val lexicon = engine.swipeLexicon(false)
        assertTrue(lexicon != null)
        val decoder = SwipeEngine()
        val words = listOf(
            "hello", "world", "keyboard", "typing", "message", "please",
            "android", "gesture", "swipe", "computer", "coffee", "letter"
        )
        var top1 = 0
        for (word in words) {
            val results = decoder.decode(pathFor(word, layout), layout, lexicon!!, 3)
            val rendered = results.map { it.word }
            println("EN $word -> $rendered")
            assertTrue("$word not in top3: $rendered", rendered.contains(word))
            if (rendered.firstOrNull() == word) top1++
        }
        println("EN top1=$top1/${words.size}")
    }

    @Test
    fun doubledLettersAndEyoAreEquivalent() {
        val engine = loadEngine()
        val layout = layout(true)
        val lexicon = engine.swipeLexicon(true)!!
        val decoder = SwipeEngine()

        val classResults = decoder.decode(pathFor("класс", layout), layout, lexicon, 3).map { it.word }
        assertTrue("doubled с lost: $classResults", classResults.contains("класс"))

        val longResults = decoder.decode(pathFor("длинный", layout), layout, lexicon, 3).map { it.word }
        assertTrue("doubled н lost: $longResults", longResults.contains("длинный"))

        val treeResults = decoder.decode(pathFor("ёлка", layout), layout, lexicon, 3).map { it.word }
        println("RU ёлка -> $treeResults")
        assertTrue(
            "е/ё equivalence failed: $treeResults",
            treeResults.any { it.replace('ё', 'е') == "елка" }
        )
    }

    @Test
    fun noisyRussianPathsStayInTopThree() {
        val engine = loadEngine()
        val layout = layout(true)
        val lexicon = engine.swipeLexicon(true)!!
        val decoder = SwipeEngine()
        val words = listOf(
            "привет", "класс", "длинный", "машина", "молоко", "программа",
            "телефон", "клавиатура", "пожалуйста", "сегодня", "словарь",
            "траектория", "здравствуйте", "человек", "россия"
        )
        var top1 = 0
        for ((index, word) in words.withIndex()) {
            val gesture = noisyPathFor(word, layout, 1000 + index)
            val rendered = decoder.decode(gesture, layout, lexicon, 3).map { it.word }
            println("NOISY RU $word -> $rendered")
            assertTrue("$word not in top3 on noisy path: $rendered", rendered.contains(word))
            if (rendered.firstOrNull() == word) top1++
        }
        println("NOISY RU top1=$top1/${words.size}")
        assertTrue("noisy top1 too low: $top1/${words.size}", top1 >= 11)
    }

    @Test
    fun noisyEnglishPathsStayInTopThree() {
        val engine = loadEngine()
        val layout = layout(false)
        val lexicon = engine.swipeLexicon(false)!!
        val decoder = SwipeEngine()
        val words = listOf(
            "hello", "world", "keyboard", "typing", "message", "please",
            "android", "gesture", "swipe", "computer", "coffee", "letter"
        )
        var top1 = 0
        for ((index, word) in words.withIndex()) {
            val gesture = noisyPathFor(word, layout, 5000 + index)
            val rendered = decoder.decode(gesture, layout, lexicon, 3).map { it.word }
            println("NOISY EN $word -> $rendered")
            assertTrue("$word not in top3 on noisy path: $rendered", rendered.contains(word))
            if (rendered.firstOrNull() == word) top1++
        }
        println("NOISY EN top1=$top1/${words.size}")
        assertTrue("noisy top1 too low: $top1/${words.size}", top1 >= 8)
    }

    @Test
    fun decodingIsFastAndReturnsThreeAlternatives() {
        val engine = loadEngine()
        val layout = layout(true)
        val lexicon = engine.swipeLexicon(true)!!
        val decoder = SwipeEngine()
        val paths = listOf("привет", "клавиатура", "пожалуйста", "траектория", "молоко")
            .map { pathFor(it, layout) }

        // Warm up the JIT and the candidate arrays.
        decoder.decode(paths[0], layout, lexicon, 3)
        val started = System.nanoTime()
        var decoded = 0
        for (path in paths) {
            val results = decoder.decode(path, layout, lexicon, 3)
            if (results.isNotEmpty()) decoded++
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        println("swipe decode avg ms=" + (elapsedMs / paths.size))
        assertEquals(paths.size, decoded)
        assertTrue("decode too slow: $elapsedMs ms", elapsedMs / paths.size < 250.0)
    }

    /**
     * Adds deterministic human-like noise: small perpendicular jitter, a small end overshoot and
     * variable sample timing (including extra dwell at key centres). The same seed always produces
     * the same path, so accuracy changes are comparable between runs.
     */
    private fun noisyPathFor(word: String, layout: SwipeLayout, seed: Int): SwipeGesture {
        val clean = pathFor(word, layout)
        val random = Random(seed.toLong())
        val count = clean.size / 2
        val points = FloatArray(clean.size)
        val jitter = 4.5f
        for (index in 0 until count) {
            points[index * 2] = clean[index * 2] + (random.nextFloat() * 2f - 1f) * jitter
            points[index * 2 + 1] = clean[index * 2 + 1] + (random.nextFloat() * 2f - 1f) * jitter
        }
        // Overshoot the final segment a little, as a finger often does at the end of a swipe.
        if (count >= 2) {
            val dx = points[(count - 1) * 2] - points[(count - 2) * 2]
            val dy = points[(count - 1) * 2 + 1] - points[(count - 2) * 2 + 1]
            points[(count - 1) * 2] += dx * 0.10f
            points[(count - 1) * 2 + 1] += dy * 0.10f
        }
        val times = LongArray(count)
        var time = 1_000L
        for (index in 0 until count) {
            time += if (index % 8 == 0) 24L else 9L + random.nextInt(7)
            times[index] = time
        }
        return SwipeGesture(points, times)
    }

    private fun loadEngine(): PredictionEngine {
        val engine = PredictionEngine(
            RuntimeEnvironment.getApplication(),
            CoroutineScope(Dispatchers.Default)
        )
        engine.load()
        val deadline = System.currentTimeMillis() + 20_000
        while (!engine.isReady && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("dictionaries did not load", engine.isReady)
        return engine
    }

    private fun layout(russian: Boolean): SwipeLayout {
        val view = KeyboardCanvasView(RuntimeEnvironment.getApplication())
        view.setLanguage(russian)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1225, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.AT_MOST)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return requireNotNull(view.swipeLayout)
    }

    /**
     * Straight key-centre polyline with a small perpendicular bow, sampled densely. Consecutive
     * duplicates are collapsed exactly like the decoder's reference path (doubled letters).
     */
    private fun pathFor(word: String, layout: SwipeLayout): FloatArray {
        val centers = ArrayList<PointF>()
        var previous = '\u0000'
        for (raw in word.lowercase()) {
            var letter = raw
            if (layout.russian && letter == 'ё') letter = 'е'
            if (letter == previous) continue
            val center = requireNotNull(layout.letters[letter]) { "no key for $letter in $word" }
            centers.add(center)
            previous = letter
        }
        require(centers.size >= 2)

        val points = ArrayList<Float>(centers.size * 16)
        val samples = 8
        for (index in 0 until centers.size - 1) {
            val a = centers[index]
            val b = centers[index + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            val nx = if (length <= 0f) 0f else -dy / length
            val ny = if (length <= 0f) 0f else dx / length
            val bow = min(6f, length * 0.06f)
            for (sample in 0 until samples) {
                val t = sample / samples.toFloat()
                val arc = sin(PI * t).toFloat() * bow
                points.add(a.x + dx * t + nx * arc)
                points.add(a.y + dy * t + ny * arc)
            }
        }
        val last = centers.last()
        points.add(last.x)
        points.add(last.y)
        return points.toFloatArray()
    }
}
