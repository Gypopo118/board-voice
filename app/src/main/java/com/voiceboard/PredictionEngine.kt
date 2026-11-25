package com.voiceboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min

/**
 * On-device next-word completion over the compact frequency dictionaries in `assets/`.
 *
 * Pipeline:
 *  - a compact integer trie gives exact prefix completions in one walk;
 *  - a weighted Damerau-Levenshtein walk over the same trie recovers typos one or two keys away
 *    (substitutions use the physical key neighbourhood, and adjacent transpositions cost less);
 *  - `ё` is folded to `е` in lookup keys, so «елка» finds «ёлка» without an edit;
 *  - a small bigram table reranks case forms after prepositions («в москве», «из москвы»);
 *  - locally learned words and picked suggestions add a personal boost.
 *
 * The trie is built from integer arrays instead of one object per node: the Russian dictionary
 * produces roughly 280k prefix nodes, and object-per-node tries on that scale are a multi-megabyte
 * allocation storm on low-end phones. Every node keeps the three most frequent words of its whole
 * subtree, so an exact completion is a single walk plus three array reads.
 */
class PredictionEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val userFile: File = File(context.filesDir, USER_WORDS)
) {

    private val russian = CompactTrie()
    private val english = CompactTrie()
    private val russianCosts = LayoutCosts(RU_NEIGHBOURS)
    private val englishCosts = LayoutCosts(EN_NEIGHBOURS)

    @Volatile
    private var russianBigrams: BigramTable? = null

    @Volatile
    private var russianSwipe: SwipeLexicon? = null

    @Volatile
    private var englishSwipe: SwipeLexicon? = null

    /** Lowercase spelling -> proper display form for proper nouns such as city names. */
    @Volatile
    private var russianProper: Map<String, String> = emptyMap()

    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready

    private class UserEntry(val word: String, val key: String) {
        var typed = 0
        var selected = 0
    }

    private val userLock = Any()
    /** Keyed by the ё-folded spelling, so «ёлка» and «елка» share one personal entry. */
    private val userEntries = HashMap<String, UserEntry>()
    @Volatile
    private var userDirty = false
    @Volatile
    private var userLoadStarted = false
    private var userSaveJob: Job? = null
    private val userWriteLock = Any()

    /** Returns the first/last-letter swipe index for the active layout, or null while loading. */
    fun swipeLexicon(russianLayout: Boolean): SwipeLexicon? =
        if (ready) (if (russianLayout) russianSwipe else englishSwipe) else null

    /**
     * Display form of a dictionary word. The frequency list is case-neutral, so city names and
     * other proper nouns are capitalised here, once, for both the T9 panel and gesture typing.
     */
    fun displayWord(word: String, russianLayout: Boolean): String {
        if (!russianLayout || word.isEmpty()) return word
        return russianProper[foldYo(word.lowercase(Locale.ROOT))] ?: word
    }

    /** Loads both dictionaries off the UI thread and reports back when suggestions become usable. */
    fun load(onReady: () -> Unit = {}) = scope.launch(Dispatchers.IO) {
        try {
            val started = System.nanoTime()
            readUserWords()
            loadAsset(RU_ASSET, russian, russianLayout = true)
            loadAsset(EN_ASSET, english, russianLayout = false)
            russianProper = loadProperNouns(RU_PROPER)
            russianBigrams = loadBigrams(RU_BIGRAMS)
            russianSwipe = russian.toSwipeLexicon(true)
            englishSwipe = english.toSwipeLexicon(false)
            ready = true
            Log.i(
                TAG,
                "dictionaries ready in " + (System.nanoTime() - started) / 1_000_000 + "ms: ru=" +
                    russian.size + ", en=" + english.size + ", bigrams=" +
                    (russianBigrams?.size ?: 0) + ", user=" + userWordCount() + ", swipe ru=" +
                    (russianSwipe?.size ?: 0) + ", en=" + (englishSwipe?.size ?: 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "dictionary load failed", e)
        }
        scope.launch { onReady() }
    }

    /** Personal dictionary entry; exposed for tests and diagnostics. */
    data class UserWord(val word: String, val key: String, val typed: Int, val selected: Int)

    fun userWords(): List<UserWord> = synchronized(userLock) {
        userEntries.values.map { UserWord(it.word, it.key, it.typed, it.selected) }
    }

    fun userWordCount(): Int = synchronized(userLock) { userEntries.size }

    /**
     * Remembers a word the user committed. Words unknown to the shipped dictionary become
     * completions on their own; known words only receive a small personal boost.
     */
    fun learnWord(word: String, russianLayout: Boolean) {
        val display = sanitize(word) ?: return
        if (!sameScript(display, russianLayout)) return
        // Known words do not need a personal copy: the shipped frequency list already ranks
        // them, and selecting them is what raises their weight (recordSelection).
        if (containsInDictionary(display, russianLayout)) return
        synchronized(userLock) {
            val entry = userEntryFor(display)
            entry.typed++
            userDirty = true
        }
        scheduleUserSave()
    }

    /** Raises the rank of a suggestion the user actually picked. */
    fun recordSelection(word: String, russianLayout: Boolean) {
        val display = sanitize(word) ?: return
        if (!sameScript(display, russianLayout)) return
        synchronized(userLock) {
            val entry = userEntryFor(display)
            entry.selected++
            userDirty = true
        }
        scheduleUserSave()
    }

    /** True when the shipped dictionary already contains [word] in the active layout. */
    private fun containsInDictionary(word: String, russianLayout: Boolean): Boolean {
        if (!ready) return false
        val trie = if (russianLayout) russian else english
        return trie.contains(normalize(word, russianLayout))
    }

    /** Returns the personal entry for [display], keeping the correct ё spelling when seen. */
    private fun userEntryFor(display: String): UserEntry {
        val key = foldYo(display)
        val existing = userEntries[key]
        if (existing == null) {
            val entry = UserEntry(display, key)
            userEntries[key] = entry
            return entry
        }
        if (display.indexOf('ё') >= 0 && existing.word.indexOf('ё') < 0) {
            val upgraded = UserEntry(display, key)
            upgraded.typed = existing.typed
            upgraded.selected = existing.selected
            userEntries[key] = upgraded
            return upgraded
        }
        return existing
    }

    /** Writes the personal dictionary synchronously; used when the IME goes away. */
    fun flushUserWords() {
        userSaveJob?.cancel()
        writeUserWords()
    }

    /**
     * Returns up to three completions for [prefix], ranked by frequency, typo distance,
     * the previous word ([previousWord]) and the personal dictionary.
     */
    suspend fun suggestions(
        prefix: String,
        russianLayout: Boolean,
        previousWord: String? = null
    ): List<String> = withContext(Dispatchers.Default) {
        if (!ready) return@withContext emptyList()
        val query = normalize(prefix, russianLayout)
        if (query.isEmpty() || query.length > MAX_PREFIX) return@withContext emptyList()

        val trie = if (russianLayout) russian else english
        val costs = if (russianLayout) russianCosts else englishCosts
        val words = trie.words ?: return@withContext emptyList()
        val frequencies = trie.frequencies ?: return@withContext emptyList()
        val logFrequencies = trie.logFrequencies ?: return@withContext emptyList()

        val ids = LinkedHashSet<Int>(48)
        // Exact prefix completions always win: fuzzy candidates only fill in when the typed
        // prefix itself is unknown, which is what a typo looks like. Otherwise a frequent word
        // two adjacent keys away («пока» for «елка») would crowd out the real completion.
        val hasExactPrefix = trie.exactIds(query, ids)
        if (!hasExactPrefix) trie.fuzzyIds(query, costs, ids)

        val previousKey = previousWord?.let { normalize(it, russianLayout) }?.takeIf { it.isNotEmpty() }
        val bigramNext = if (russianLayout) previousKey?.let { russianBigrams?.next(it) } else null

        val rowA = IntArray(MAX_WORD_LENGTH + 1)
        val rowB = IntArray(MAX_WORD_LENGTH + 1)
        val rowC = IntArray(MAX_WORD_LENGTH + 1)

        // First pass: typo cost and bigram count for every candidate.
        val costOf = IntArray(ids.size)
        val bigramOf = IntArray(ids.size)
        var minCost = Int.MAX_VALUE
        var position = 0
        for (id in ids) {
            val key = if (russianLayout) foldYo(words[id]) else words[id]
            val cost = if (key.startsWith(query)) 0 else prefixCost(query, key, costs, rowA, rowB, rowC)
            costOf[position] = cost
            val count = bigramCount(bigramNext, key)
            bigramOf[position] = count
            if (cost < minCost) minCost = cost
            position++
        }
        // The bigram bonus is scaled by the strongest pair among the *closest* candidates. Noise
        // from fuzzy candidates at a higher edit cost must not dilute it.
        var maxBigram = 0
        for (index in costOf.indices) {
            if (costOf[index] == minCost && bigramOf[index] > maxBigram) {
                maxBigram = bigramOf[index]
            }
        }

        val scored = ArrayList<Scored>(ids.size + 8)
        val knownKeys = HashSet<String>(ids.size + 8)
        position = 0
        for (id in ids) {
            val word = words[id]
            val cost = costOf[position]
            val bigram = bigramOf[position]
            position++
            if (cost > MAX_COST) continue
            // Keep the panel precise: once a cheap correction exists, candidates another full
            // edit step away are noise rather than suggestions.
            if (cost > minCost + 1) continue
            val display = displayWord(word, russianLayout)
            val exactTyped = word.equals(prefix, ignoreCase = true)
            // A suggestion that repeats the typed spelling exactly adds nothing, unless the
            // display form is different: a fully typed «оренбург» should still offer «Оренбург».
            // The ё twin of the typed word is different text and stays: «елка» -> «ёлка».
            if (exactTyped && display == word) continue
            val key = if (russianLayout) foldYo(word) else word
            knownKeys.add(key)
            var score = logFrequencies[id] - COST_PENALTY[cost]
            if (exactTyped) score += EXACT_PROPER_BONUS
            // The first letter is a strong anchor: «привет» is a far better correction for
            // «превет» than the much closer by keys but unrelated «креветки».
            if (key.firstOrNull() != query.firstOrNull()) score -= FIRST_LETTER_PENALTY
            score += personalBoost(key)
            if (cost == minCost && bigram > 0 && maxBigram > 0) {
                score += BIGRAM_WEIGHT * bigram / maxBigram
            }
            if (russianLayout && word.indexOf('ё') >= 0) score += YO_BONUS
            scored.add(Scored(word, score, frequencies[id]))
        }

        addUserCandidates(query, prefix, russianLayout, bigramNext, maxBigram, knownKeys, scored)
        scored.sortWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.frequency }
                .thenBy { it.word }
        )
        scored.take(SUGGESTION_LIMIT).map { displayWord(it.word, russianLayout) }
    }

    /** User words that complete the prefix; they are not part of the shipped trie. */
    private fun addUserCandidates(
        query: String,
        prefix: String,
        russianLayout: Boolean,
        bigramNext: Array<BigramTable.Next>?,
        maxBigram: Int,
        knownKeys: MutableSet<String>,
        out: MutableList<Scored>
    ) {
        val candidates = ArrayList<UserWord>(16)
        synchronized(userLock) {
            for (entry in userEntries.values) {
                if (!sameScript(entry.word, russianLayout)) continue
                if (!entry.key.startsWith(query)) continue
                candidates.add(UserWord(entry.word, entry.key, entry.typed, entry.selected))
            }
        }
        for (candidate in candidates) {
            if (candidate.key == query && candidate.word.equals(prefix, ignoreCase = true)) continue
            if (knownKeys.contains(candidate.key)) continue
            var score = USER_BASE_ZIPF +
                0.6f * ln(1f + candidate.typed) +
                0.7f * ln(1f + candidate.selected)
            val bigram = bigramCount(bigramNext, candidate.key)
            if (bigram > 0 && maxBigram > 0) score += BIGRAM_WEIGHT * bigram / maxBigram
            out.add(Scored(candidate.word, score, 0))
        }
    }

    private fun bigramCount(next: Array<BigramTable.Next>?, key: String): Int {
        if (next == null) return 0
        for (item in next) if (item.word == key) return item.count
        return 0
    }

    private fun personalBoost(key: String): Float = synchronized(userLock) {
        val entry = userEntries[key] ?: return@synchronized 0f
        min(
            MAX_PERSONAL_BOOST,
            0.12f * ln(1f + entry.selected) + 0.02f * ln(1f + entry.typed)
        )
    }

    /**
     * Weighted edit distance between [query] and the closest prefix of [word]. Substituting a
     * physical neighbour on the active layout costs less than an arbitrary substitution, and
     * adjacent transpositions are cheap.
     */
    private fun prefixCost(
        query: String,
        word: String,
        costs: LayoutCosts,
        rowA: IntArray,
        rowB: IntArray,
        rowC: IntArray
    ): Int {
        val n = query.length
        val m = min(word.length, rowA.size - 1)
        val rows = arrayOf(rowA, rowB, rowC)
        val first = rows[0]
        for (j in 0..n) first[j] = j * INDEL_COST
        var best = Int.MAX_VALUE
        for (i in 1..m) {
            val current = rows[i % 3]
            val previous = rows[(i - 1) % 3]
            val beforePrevious = rows[(i - 2 + 3) % 3]
            current[0] = i * INDEL_COST
            for (j in 1..n) {
                val queryChar = query[j - 1]
                val wordChar = word[i - 1]
                val substitution = if (wordChar == queryChar) {
                    0
                } else {
                    costs.substitution(queryChar, wordChar)
                }
                var value = min(
                    min(previous[j] + INDEL_COST, current[j - 1] + INDEL_COST),
                    previous[j - 1] + substitution
                )
                if (i >= 2 && j >= 2 &&
                    word[i - 1] == query[j - 2] && word[i - 2] == query[j - 1]
                ) {
                    value = min(value, beforePrevious[j - 2] + TRANSPOSE_COST)
                }
                current[j] = value
            }
            if (current[n] < best) best = current[n]
        }
        return best
    }

    private fun loadAsset(asset: String, trie: CompactTrie, russianLayout: Boolean) {
        context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val split = line.lastIndexOf('\t')
                if (split <= 0 || split == line.lastIndex) return@forEach
                val frequency = line.substring(split + 1).toIntOrNull() ?: return@forEach
                if (frequency <= 0) return@forEach
                val word = line.substring(0, split).lowercase(Locale.ROOT)
                if (word.length > MAX_WORD_LENGTH) return@forEach
                val path = if (russianLayout) foldYo(word) else word
                trie.add(word, path, frequency)
            }
        }
        trie.freeze()
    }

    /**
     * Reads the curated proper-noun display table. Keys are lowercase (ё-folded) spellings; the
     * value is the form to show, so compound names such as «Санкт-Петербург» keep their internal
     * capitalisation.
     */
    private fun loadProperNouns(asset: String): Map<String, String> = try {
        context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
            val result = HashMap<String, String>(1024)
            lines.forEach { line ->
                val split = line.indexOf('\t')
                if (split <= 0 || split == line.lastIndex) return@forEach
                val key = foldYo(line.substring(0, split).lowercase(Locale.ROOT))
                val display = line.substring(split + 1)
                if (key.isNotEmpty() && display.isNotEmpty()) result[key] = display
            }
            result
        }
    } catch (e: Exception) {
        Log.w(TAG, "proper noun table unavailable", e)
        emptyMap()
    }

    private fun loadBigrams(asset: String): BigramTable? = try {
        context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
            val grouped = HashMap<String, ArrayList<BigramTable.Next>>(4096)
            lines.forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@forEach
                val count = parts[2].toIntOrNull() ?: return@forEach
                if (count <= 0) return@forEach
                grouped.getOrPut(parts[0]) { ArrayList(64) }
                    .add(BigramTable.Next(parts[1], count))
            }
            val table = HashMap<String, Array<BigramTable.Next>>(grouped.size * 2)
            for ((previous, next) in grouped) {
                next.sortWith(
                    compareByDescending<BigramTable.Next> { it.count }.thenBy { it.word }
                )
                table[previous] = if (next.size > MAX_BIGRAM_NEXT) {
                    next.subList(0, MAX_BIGRAM_NEXT).toTypedArray()
                } else {
                    next.toTypedArray()
                }
            }
            BigramTable(table)
        }
    } catch (e: Exception) {
        Log.w(TAG, "bigram table unavailable", e)
        null
    }

    private fun readUserWords() {
        if (userLoadStarted) return
        userLoadStarted = true
        if (!userFile.exists()) return
        try {
            userFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@forEach
                    val display = sanitize(parts[0]) ?: return@forEach
                    val typedCount = parts[1].toIntOrNull() ?: 0
                    val selectedCount = parts[2].toIntOrNull() ?: 0
                    if (typedCount <= 0 && selectedCount <= 0) return@forEach
                    val key = foldYo(display)
                    synchronized(userLock) {
                        val entry = userEntries.getOrPut(key) { UserEntry(display, key) }
                        entry.typed += typedCount
                        entry.selected += selectedCount
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "personal dictionary read failed", e)
        }
    }

    private fun scheduleUserSave() {
        userSaveJob?.cancel()
        userSaveJob = scope.launch(Dispatchers.IO) {
            delay(USER_SAVE_DELAY_MILLIS)
            writeUserWords()
        }
    }

    private fun writeUserWords() {
        if (!userDirty) return
        val snapshot = synchronized(userLock) {
            userEntries.values
                .sortedWith(
                    compareByDescending<UserEntry> { it.typed + it.selected }
                        .thenBy { it.word }
                )
                .take(MAX_USER_WORDS)
                .map { Triple(it.word, it.typed, it.selected) }
        }
        synchronized(userWriteLock) {
            try {
                userFile.parentFile?.mkdirs()
                userFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for ((word, typed, selected) in snapshot) {
                        writer.write(word)
                        writer.write("\t")
                        writer.write(typed.toString())
                        writer.write("\t")
                        writer.write(selected.toString())
                        writer.write("\n")
                    }
                }
                userDirty = false
            } catch (e: Exception) {
                Log.w(TAG, "personal dictionary write failed", e)
            }
        }
    }

    /** Lowercase, letter-only word or null when it is not worth learning. */
    private fun sanitize(word: String): String? {
        val value = word.trim().lowercase(Locale.ROOT)
        if (value.length < 2 || value.length > MAX_WORD_LENGTH) return null
        for (letter in value) if (!letter.isLetter()) return null
        return value
    }

    /** The engine stores Cyrillic and Latin words; each layout only sees its own script. */
    private fun sameScript(word: String, russianLayout: Boolean): Boolean {
        val letter = word.firstOrNull() ?: return false
        val cyrillic = letter.code in 0x0400..0x04FF
        return if (russianLayout) cyrillic else !cyrillic
    }

    private fun normalize(word: String, russianLayout: Boolean): String {
        val lower = word.lowercase(Locale.ROOT)
        return if (russianLayout) foldYo(lower) else lower
    }

    private fun foldYo(word: String): String = word.replace('ё', 'е')

    private data class Scored(
        val word: String,
        val score: Float,
        val frequency: Int
    )

    private class BigramTable(private val map: HashMap<String, Array<Next>>) {
        class Next(val word: String, val count: Int)

        val size: Int get() = map.size

        fun next(previous: String): Array<Next>? = map[previous]
    }

    /**
     * Physical key adjacency converted into integer substitution costs. The DP works on integers
     * for speed: an adjacent-key typo costs 1, any other substitution 2, an insertion/deletion 2,
     * and a transposition 1.
     */
    private class LayoutCosts(neighbours: Map<Char, String>) {
        private val positionOf = IntArray(CHAR_INDEX_SIZE) { -1 }
        private val letterCount: Int
        private val adjacent: BooleanArray

        init {
            val letters = LinkedHashSet<Char>()
            for ((letter, neighboursOfLetter) in neighbours) {
                letters.add(letter)
                for (neighbour in neighboursOfLetter) letters.add(neighbour)
            }
            for ((position, letter) in letters.withIndex()) {
                if (letter.code < CHAR_INDEX_SIZE) positionOf[letter.code] = position
            }
            letterCount = letters.size
            adjacent = BooleanArray(letterCount * letterCount)
            for ((letter, neighboursOfLetter) in neighbours) {
                for (neighbour in neighboursOfLetter) {
                    mark(letter, neighbour)
                    mark(neighbour, letter)
                }
            }
        }

        fun substitution(a: Char, b: Char): Int {
            if (a == b) return 0
            val first = if (a.code < CHAR_INDEX_SIZE) positionOf[a.code] else -1
            val second = if (b.code < CHAR_INDEX_SIZE) positionOf[b.code] else -1
            if (first < 0 || second < 0) return SUBSTITUTE_COST
            return if (adjacent[first * letterCount + second]) ADJACENT_COST else SUBSTITUTE_COST
        }

        private fun mark(a: Char, b: Char) {
            val first = if (a.code < CHAR_INDEX_SIZE) positionOf[a.code] else -1
            val second = if (b.code < CHAR_INDEX_SIZE) positionOf[b.code] else -1
            if (first < 0 || second < 0) return
            adjacent[first * letterCount + second] = true
        }
    }

    private class CompactTrie {
        private var chars = CharArray(INITIAL_NODES)
        private var firstChild = IntArray(INITIAL_NODES) { -1 }
        private var nextSibling = IntArray(INITIAL_NODES) { -1 }
        private var top1 = IntArray(INITIAL_NODES) { -1 }
        private var top2 = IntArray(INITIAL_NODES) { -1 }
        private var top3 = IntArray(INITIAL_NODES) { -1 }
        private var terminal = BooleanArray(INITIAL_NODES)
        private var nodes = 1

        private var pendingWords: ArrayList<String>? = ArrayList(1024)
        private var pendingFrequencies: ArrayList<Int>? = ArrayList(1024)
        private var wordCount = 0

        var words: Array<String>? = null
            private set
        var frequencies: IntArray? = null
            private set
        var logFrequencies: FloatArray? = null
            private set

        val size: Int get() = wordCount

        fun add(word: String, path: String, frequency: Int) {
            val pending = pendingWords ?: return
            val pendingFrequency = pendingFrequencies ?: return
            val id = wordCount++
            pending.add(word)
            pendingFrequency.add(frequency)

            var node = 0
            offer(node, id, frequency)
            for (letter in path) {
                var child = firstChild[node]
                while (child != -1 && chars[child] != letter) child = nextSibling[child]
                if (child == -1) {
                    child = newNode(letter)
                    nextSibling[child] = firstChild[node]
                    firstChild[node] = child
                }
                node = child
                offer(node, id, frequency)
            }
            terminal[node] = true
        }

        /** Publishes the built arrays and drops the temporary builders. */
        fun freeze() {
            val pending = pendingWords ?: return
            val pendingFrequency = pendingFrequencies ?: return
            val publishedWords = pending.toTypedArray()
            val publishedFrequencies = pendingFrequency.toIntArray()
            words = publishedWords
            frequencies = publishedFrequencies
            logFrequencies = FloatArray(publishedFrequencies.size) {
                log10(publishedFrequencies[it].toDouble()).toFloat()
            }
            pendingWords = null
            pendingFrequencies = null

            chars = chars.copyOf(nodes)
            firstChild = firstChild.copyOf(nodes)
            nextSibling = nextSibling.copyOf(nodes)
            top1 = top1.copyOf(nodes)
            top2 = top2.copyOf(nodes)
            top3 = top3.copyOf(nodes)
            terminal = terminal.copyOf(nodes)
        }

        /** Exact word lookup (the path is already ё-folded for Russian). */
        fun contains(path: String): Boolean {
            val node = find(path) ?: return false
            return terminal[node]
        }

        fun toSwipeLexicon(russian: Boolean): SwipeLexicon {
            val allWords = words ?: emptyArray()
            val allFrequencies = frequencies ?: IntArray(0)
            return SwipeLexicon.build(allWords, allFrequencies, russian)
        }

        /** Adds the three most frequent words of the subtree rooted at the prefix. */
        fun exactIds(prefix: String, out: MutableCollection<Int>): Boolean {
            val node = find(prefix) ?: return false
            appendTop(node, out)
            return true
        }

        /**
         * Adds the top words of every trie node whose path is within [MAX_COST] of the query.
         * The DP row for each node is computed once and pruned when no continuation can recover.
         */
        fun fuzzyIds(query: String, costs: LayoutCosts, out: MutableCollection<Int>) {
            val length = query.length
            if (length < MIN_FUZZY_LENGTH) return
            val maxDepth = min(MAX_WORD_LENGTH, length + MAX_COST / INDEL_COST)
            val rows = Array(maxDepth + 1) { IntArray(length + 1) }
            val root = rows[0]
            for (j in 0..length) root[j] = j * INDEL_COST
            walk(0, 0, '\u0000', rows, query, costs, maxDepth, out)
        }

        private fun walk(
            node: Int,
            depth: Int,
            previousChar: Char,
            rows: Array<IntArray>,
            query: String,
            costs: LayoutCosts,
            maxDepth: Int,
            out: MutableCollection<Int>
        ) {
            var child = firstChild[node]
            while (child != -1) {
                val letter = chars[child]
                val newDepth = depth + 1
                val row = rows[depth]
                val newRow = rows[newDepth]
                newRow[0] = row[0] + INDEL_COST
                var rowMinimum = newRow[0]
                val previousRow = if (depth >= 1) rows[depth - 1] else null
                for (j in 1..query.length) {
                    val queryChar = query[j - 1]
                    val substitution = if (queryChar == letter) {
                        0
                    } else {
                        costs.substitution(queryChar, letter)
                    }
                    var value = min(
                        min(row[j] + INDEL_COST, newRow[j - 1] + INDEL_COST),
                        row[j - 1] + substitution
                    )
                    if (depth >= 1 && j >= 2 && queryChar == previousChar && query[j - 2] == letter) {
                        val transposed = previousRow?.get(j - 2) ?: Int.MAX_VALUE
                        value = min(value, transposed + TRANSPOSE_COST)
                    }
                    newRow[j] = value
                    if (value < rowMinimum) rowMinimum = value
                }
                if (newDepth >= query.length - MAX_EXTRA_SHORT &&
                    newRow[query.length] <= MAX_COST
                ) {
                    appendTop(child, out)
                }
                if (rowMinimum <= MAX_COST && newDepth < maxDepth) {
                    walk(child, newDepth, letter, rows, query, costs, maxDepth, out)
                }
                child = nextSibling[child]
            }
        }

        private fun appendTop(node: Int, out: MutableCollection<Int>) {
            if (top1[node] >= 0) out.add(top1[node])
            if (top2[node] >= 0) out.add(top2[node])
            if (top3[node] >= 0) out.add(top3[node])
        }

        private fun find(prefix: String): Int? {
            var node = 0
            for (letter in prefix) {
                var child = firstChild[node]
                while (child != -1 && chars[child] != letter) child = nextSibling[child]
                if (child == -1) return null
                node = child
            }
            return node
        }

        private fun offer(node: Int, id: Int, frequency: Int) {
            val current1 = top1[node]
            if (current1 == -1 || frequency > frequencyOf(current1)) {
                top3[node] = top2[node]
                top2[node] = top1[node]
                top1[node] = id
                return
            }
            val current2 = top2[node]
            if (current2 == -1 || frequency > frequencyOf(current2)) {
                top3[node] = top2[node]
                top2[node] = id
                return
            }
            val current3 = top3[node]
            if (current3 == -1 || frequency > frequencyOf(current3)) top3[node] = id
        }

        private fun frequencyOf(id: Int): Int = pendingFrequencies?.get(id) ?: 0

        private fun newNode(letter: Char): Int {
            if (nodes == chars.size) grow()
            val index = nodes++
            chars[index] = letter
            firstChild[index] = -1
            nextSibling[index] = -1
            top1[index] = -1
            top2[index] = -1
            top3[index] = -1
            return index
        }

        private fun grow() {
            val size = chars.size * 2
            chars = chars.copyOf(size)
            firstChild = firstChild.copyOf(size)
            nextSibling = nextSibling.copyOf(size)
            top1 = top1.copyOf(size)
            top2 = top2.copyOf(size)
            top3 = top3.copyOf(size)
            terminal = terminal.copyOf(size)
        }

        private companion object {
            const val INITIAL_NODES = 16_384
        }
    }

    private companion object {
        const val TAG = "VoiceBoard/T9"
        const val RU_ASSET = "ru_dict.tsv"
        const val EN_ASSET = "en_dict.tsv"
        const val RU_BIGRAMS = "ru_bigrams.tsv"
        const val RU_PROPER = "ru_proper.tsv"
        const val USER_WORDS = "user_words.tsv"
        const val MAX_PREFIX = 24
        const val MAX_WORD_LENGTH = 32
        const val MAX_USER_WORDS = 2000
        const val MAX_BIGRAM_NEXT = 240
        const val SUGGESTION_LIMIT = 3
        const val MAX_PERSONAL_BOOST = 1.3f
        const val USER_BASE_ZIPF = 3.2f
        const val BIGRAM_WEIGHT = 0.7f
        const val YO_BONUS = 0.02f
        const val FIRST_LETTER_PENALTY = 1.2f
        const val EXACT_PROPER_BONUS = 1.5f
        const val USER_SAVE_DELAY_MILLIS = 700L

        const val MAX_COST = 4
        const val MAX_EXTRA_SHORT = 2
        const val MIN_FUZZY_LENGTH = 2
        const val INDEL_COST = 2
        const val SUBSTITUTE_COST = 2
        const val ADJACENT_COST = 1
        const val TRANSPOSE_COST = 1
        const val CHAR_INDEX_SIZE = 0x0500

        val COST_PENALTY = floatArrayOf(0f, 1.1f, 2.0f, 2.8f, 3.4f)

        /** Physical neighbours on ЙЦУКЕН; the last row is offset one column to the right. */
        val RU_NEIGHBOURS = mapOf(
            'а' to "укевпчсм", 'б' to "лджью", 'в' to "цукыаячс", 'г' to "ншрол",
            'д' to "шщзлжьбю", 'е' to "кнапрё", 'ё' to "кнапр", 'ж' to "щзхдэбю",
            'з' to "щхджэ", 'и' to "промт", 'й' to "цфы", 'к' to "уевап",
            'л' to "гшщодтьб", 'м' to "апрси", 'н' to "егпро", 'о' to "нгшрлить",
            'п' to "кенарсми", 'р' to "енгпомит", 'с' to "вапчм", 'т' to "ролиь",
            'у' to "цкыва", 'ф' to "йцыя", 'х' to "зжэ", 'ц' to "йуфыв",
            'ч' to "ываяс", 'ш' to "гщолд", 'щ' to "шзлдж", 'ъ' to "ь",
            'ы' to "йцуфвяч", 'ь' to "олдтбъ", 'э' to "зхжю", 'ю' to "джэб",
            'я' to "фывч"
        )

        val EN_NEIGHBOURS = mapOf(
            'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
            'y' to "tugh", 'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol",
            'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc",
            'g' to "ftyhbv", 'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm",
            'l' to "kop", 'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb",
            'b' to "vghn", 'n' to "bhjm", 'm' to "njk"
        )
    }
}
