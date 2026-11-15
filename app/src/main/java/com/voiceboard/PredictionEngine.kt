package com.voiceboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * On-device next-word completion over the compact frequency dictionaries in `assets/`.
 *
 * The trie is deliberately built from integer arrays instead of one object per node: the Russian
 * dictionary alone produces roughly 280k prefix nodes, and object-per-node tries on that scale are
 * a multi-megabyte allocation storm on low-end phones. Every node keeps the three most frequent
 * words of its whole subtree, so completing one prefix is a single walk plus three array reads.
 */
class PredictionEngine(private val context: Context, private val scope: CoroutineScope) {

    data class Candidate(val word: String, val frequency: Int)

    private val russian = CompactTrie()
    private val english = CompactTrie()

    @Volatile
    private var russianSwipe: SwipeLexicon? = null

    @Volatile
    private var englishSwipe: SwipeLexicon? = null

    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready

    /** Returns the first/last-letter swipe index for the active layout, or null while loading. */
    fun swipeLexicon(russianLayout: Boolean): SwipeLexicon? =
        if (ready) (if (russianLayout) russianSwipe else englishSwipe) else null

    /** Loads both dictionaries off the UI thread and reports back when suggestions become usable. */
    fun load(onReady: () -> Unit = {}) = scope.launch(Dispatchers.IO) {
        try {
            loadAsset(RU_ASSET, russian)
            loadAsset(EN_ASSET, english)
            russianSwipe = russian.toSwipeLexicon(true)
            englishSwipe = english.toSwipeLexicon(false)
            ready = true
            Log.i(
                TAG,
                "dictionaries ready: ru=" + russian.size + ", en=" + english.size +
                    ", swipe ru=" + (russianSwipe?.size ?: 0) + ", en=" + (englishSwipe?.size ?: 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "dictionary load failed", e)
        }
        scope.launch { onReady() }
    }

    /**
     * Returns up to three completions for [prefix], ranked by frequency. Exact completions are
     * collected first; while there are fewer than three, words reachable by substituting one
     * letter with a physical neighbour on the active layout are added as cheap typo recovery.
     */
    suspend fun suggestions(prefix: String, russianLayout: Boolean): List<String> =
        withContext(Dispatchers.Default) {
            if (!ready) return@withContext emptyList()
            val normalized = prefix.lowercase(Locale.ROOT)
            if (normalized.isEmpty() || normalized.length > MAX_PREFIX) return@withContext emptyList()

            val trie = if (russianLayout) russian else english
            val exact = trie.complete(normalized, 3)
            if (exact.size >= 3) return@withContext exact.map { it.word }

            val pool = LinkedHashMap<String, Int>(16)
            exact.forEach { pool[it.word] = it.frequency }

            if (normalized.length >= 3) {
                val neighbours = if (russianLayout) RU_NEIGHBOURS else EN_NEIGHBOURS
                // Try every position, not just the last one: "превет" should still find "привет".
                for (index in normalized.indices) {
                    val alternatives = neighbours[normalized[index]] ?: continue
                    for (alternative in alternatives) {
                        val corrected = normalized.substring(0, index) + alternative +
                            normalized.substring(index + 1)
                        trie.complete(corrected, 1).forEach { candidate ->
                            if (!pool.containsKey(candidate.word)) pool[candidate.word] = candidate.frequency
                        }
                    }
                }
            }

            pool.entries.sortedByDescending { it.value }.take(3).map { it.key }
        }

    private fun loadAsset(asset: String, trie: CompactTrie) {
        context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val split = line.lastIndexOf('\t')
                if (split <= 0 || split == line.lastIndex) return@forEach
                val frequency = line.substring(split + 1).toIntOrNull() ?: return@forEach
                if (frequency <= 0) return@forEach
                trie.add(line.substring(0, split), frequency)
            }
        }
        trie.freeze()
    }

    private class CompactTrie {
        private var chars = CharArray(INITIAL_NODES)
        private var firstChild = IntArray(INITIAL_NODES) { -1 }
        private var nextSibling = IntArray(INITIAL_NODES) { -1 }
        private var top1 = IntArray(INITIAL_NODES) { -1 }
        private var top2 = IntArray(INITIAL_NODES) { -1 }
        private var top3 = IntArray(INITIAL_NODES) { -1 }
        private var nodes = 1

        private var pendingWords: ArrayList<String>? = ArrayList(1024)
        private var pendingFrequencies: ArrayList<Int>? = ArrayList(1024)
        private var words: Array<String>? = null
        private var frequencies: IntArray? = null
        private var wordCount = 0

        val size: Int get() = wordCount

        fun add(word: String, frequency: Int) {
            val pending = pendingWords ?: return
            val pendingFrequency = pendingFrequencies ?: return
            val id = wordCount++
            pending.add(word)
            pendingFrequency.add(frequency)

            var node = 0
            offer(node, id, frequency)
            for (letter in word) {
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
        }

        /** Publishes the built arrays and drops the temporary builders. */
        fun freeze() {
            val pending = pendingWords ?: return
            val pendingFrequency = pendingFrequencies ?: return
            words = pending.toTypedArray()
            frequencies = pendingFrequency.toIntArray()
            pendingWords = null
            pendingFrequencies = null

            chars = chars.copyOf(nodes)
            firstChild = firstChild.copyOf(nodes)
            nextSibling = nextSibling.copyOf(nodes)
            top1 = top1.copyOf(nodes)
            top2 = top2.copyOf(nodes)
            top3 = top3.copyOf(nodes)
        }

        fun toSwipeLexicon(russian: Boolean): SwipeLexicon {
            val allWords = words ?: emptyArray()
            val allFrequencies = frequencies ?: IntArray(0)
            return SwipeLexicon.build(allWords, allFrequencies, russian)
        }

        fun complete(prefix: String, limit: Int): List<Candidate> {
            val node = find(prefix) ?: return emptyList()
            val allWords = words ?: return emptyList()
            val allFrequencies = frequencies ?: return emptyList()
            val result = ArrayList<Candidate>(limit.coerceAtMost(3))
            append(result, allWords, allFrequencies, top1[node], limit)
            append(result, allWords, allFrequencies, top2[node], limit)
            append(result, allWords, allFrequencies, top3[node], limit)
            return result
        }

        private fun append(
            result: ArrayList<Candidate>,
            allWords: Array<String>,
            allFrequencies: IntArray,
            index: Int,
            limit: Int
        ) {
            if (result.size >= limit || index < 0) return
            result.add(Candidate(allWords[index], allFrequencies[index]))
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
        }

        private companion object {
            const val INITIAL_NODES = 16_384
        }
    }

    private companion object {
        const val TAG = "VoiceBoard/T9"
        const val RU_ASSET = "ru_dict.tsv"
        const val EN_ASSET = "en_dict.tsv"
        const val MAX_PREFIX = 24

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
