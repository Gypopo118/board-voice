package com.voiceboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictionEngineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files = ArrayList<File>()

    @After
    fun tearDown() {
        scope.cancel()
        for (file in files) file.delete()
    }

    private fun engine(): PredictionEngine {
        val file = File(
            RuntimeEnvironment.getApplication().filesDir,
            "user_words_test_" + COUNTER.incrementAndGet() + ".tsv"
        )
        file.delete()
        files.add(file)
        val engine = PredictionEngine(RuntimeEnvironment.getApplication(), scope, file)
        engine.load()
        val deadline = System.currentTimeMillis() + 30_000
        while (!engine.isReady && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("dictionary did not load", engine.isReady)
        return engine
    }

    private fun suggest(
        engine: PredictionEngine,
        prefix: String,
        russian: Boolean = true,
        previous: String? = null
    ): List<String> = runBlocking { engine.suggestions(prefix, russian, previous) }

    @Test
    fun correctsMoscowTypo() {
        val engine = engine()
        val result = suggest(engine, "Масква")
        println("Масква -> $result")
        assertEquals("Москва", result.firstOrNull())

        // One inserted, one missing and one transposed letter are all distance-1 typos.
        val transposed = suggest(engine, "моксва")
        val missing = suggest(engine, "мсква")
        val extra = suggest(engine, "москваа")
        println("моксва -> $transposed, мсква -> $missing, москваа -> $extra")
        assertEquals("Москва", transposed.firstOrNull())
        assertEquals("Москва", missing.firstOrNull())
        assertEquals("Москва", extra.firstOrNull())

        val hello = suggest(engine, "превет")
        println("превет -> $hello")
        assertEquals("привет", hello.firstOrNull())
    }

    @Test
    fun findsYoWordsWithoutPenalty() {
        val engine = engine()
        val result = suggest(engine, "елка")
        println("елка -> $result")
        assertEquals("ёлка", result.firstOrNull())
    }

    @Test
    fun completesPrefixes() {
        val engine = engine()
        val moscow = suggest(engine, "моск")
        println("моск -> $moscow")
        assertTrue(moscow.contains("Москва"))
        assertFalse("the already typed word must not be suggested", moscow.contains("моск"))

        val adjective = suggest(engine, "москов")
        println("москов -> $adjective")
        assertTrue("московский not suggested: $adjective", adjective.contains("московский"))

        val keyboard = suggest(engine, "keyb", russian = false)
        println("keyb -> $keyboard")
        assertTrue(keyboard.contains("keyboard"))

        val hello = suggest(engine, "hell", russian = false)
        println("hell -> $hello")
        assertTrue(hello.contains("hello"))
    }

    @Test
    fun bigramsPickTheCaseForm() {
        val engine = engine()
        val inMoscow = suggest(engine, "мос", previous = "в")
        val fromMoscow = suggest(engine, "мос", previous = "из")
        println("в мос -> $inMoscow")
        println("из мос -> $fromMoscow")
        assertEquals("Москве", inMoscow.firstOrNull())
        assertEquals("Москвы", fromMoscow.firstOrNull())
    }

    @Test
    fun suggestsHyphenatedWordsAndDropsGluedSpellings() {
        val engine = engine()
        for (prefix in listOf("что", "где", "как", "почему", "что-ни")) {
            val result = suggest(engine, prefix)
            println("$prefix -> $result")
            val expected = when (prefix) {
                "что" -> "что-то"
                "где" -> "где-то"
                "как" -> "как-то"
                "почему" -> "почему-то"
                else -> "что-нибудь"
            }
            assertTrue("$expected missing for $prefix: $result", result.contains(expected))
        }
        assertTrue(suggest(engine, "все-т").contains("всё-таки"))
        assertFalse(suggest(engine, "что").contains("чтото"))
        assertFalse(suggest(engine, "всета").contains("всетаки"))
    }

    @Test
    fun capitalisesProperNounsInSuggestions() {
        val engine = engine()
        val city = suggest(engine, "орен")
        println("орен -> $city")
        assertTrue("Оренбург missing: $city", city.contains("Оренбург"))

        val moscow = suggest(engine, "мос")
        println("мос -> $moscow")
        assertEquals("Москве", moscow.firstOrNull())
        assertTrue(moscow.all { it.first().isUpperCase() })

        // A fully typed city still offers its capitalised form as the first suggestion.
        assertEquals("Оренбург", suggest(engine, "оренбург").firstOrNull())
        assertEquals("Москва", suggest(engine, "москва").firstOrNull())

        // An adjective is not a proper name and must stay lowercase.
        val adjective = suggest(engine, "оренбургск")
        println("оренбургск -> $adjective")
        assertTrue(adjective.isNotEmpty())
        assertTrue(adjective.all { it.first().isLowerCase() })
    }

    @Test
    fun displayWordKeepsCompoundCapitalisation() {
        val engine = engine()
        assertEquals("Москва", engine.displayWord("москва", true))
        assertEquals("Санкт-Петербург", engine.displayWord("санкт-петербург", true))
        assertEquals("московский", engine.displayWord("московский", true))
        assertEquals("hello", engine.displayWord("hello", false))
    }

    @Test
    fun learnsNewWordsAndBoostsSelectedSuggestions() {
        val engine = engine()
        repeat(3) { engine.learnWord("зузузу", true) }
        val learned = suggest(engine, "зуз")
        println("зуз -> $learned")
        assertTrue("learned word missing: $learned", learned.contains("зузузу"))

        repeat(20) { engine.recordSelection("маску", true) }
        val boosted = suggest(engine, "маск")
        println("маск after selecting маску -> $boosted")
        assertEquals("маску", boosted.firstOrNull())
        assertTrue(engine.userWordCount() >= 2)
    }

    @Test
    fun personalDictionarySurvivesReload() {
        val first = engine()
        repeat(2) { first.learnWord("квазиморф", true) }
        first.flushUserWords()

        val second = PredictionEngine(
            RuntimeEnvironment.getApplication(),
            scope,
            files.last()
        )
        second.load()
        val deadline = System.currentTimeMillis() + 30_000
        while (!second.isReady && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(second.userWordCount() >= 1)
        assertTrue(suggest(second, "квази").contains("квазиморф"))
    }

    @Test
    fun suggestionLatencyStaysWithinBudget() {
        val engine = engine()
        val prefixes = listOf(
            "м", "мо", "мос", "моск", "москв", "маск", "маскв", "масква",
            "ел", "елк", "елка", "пр", "при", "прив", "приве", "привет",
            "сегод", "пожал", "здравств", "чело"
        )
        // Warm up the JIT and the DPs.
        repeat(15) {
            for (prefix in prefixes) suggest(engine, prefix)
        }
        val rounds = 30
        val started = System.nanoTime()
        repeat(rounds) {
            for (prefix in prefixes) suggest(engine, prefix)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val average = elapsedMs / (rounds * prefixes.size)
        println("suggestion average: %.2f ms".format(average))
        assertTrue("average %.2f ms exceeds budget".format(average), average < 30.0)
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}
