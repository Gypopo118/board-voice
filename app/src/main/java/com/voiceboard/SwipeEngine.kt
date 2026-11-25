package com.voiceboard

import android.graphics.PointF
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Immutable snapshot of the letter-key geometry for one layout. A swipe keeps the snapshot it
 * started on, so changing the language/symbol page with another finger cannot corrupt decoding.
 */
class SwipeLayout(
    val letters: Map<Char, PointF>,
    val keyWidth: Float,
    val keyHeight: Float,
    val russian: Boolean
) {
    private val entries = letters.entries.toList()

    /**
     * Up to [limit] keys around a point, measured in key widths/heights. The nearest key is always
     * returned, even when the finger ended just outside the keyboard.
     */
    fun nearestLetters(x: Float, y: Float, limit: Int = 3): List<Char> {
        if (entries.isEmpty() || keyWidth <= 0f || keyHeight <= 0f || limit <= 0) return emptyList()
        val scored = ArrayList<ScoredLetter>(entries.size)
        for (entry in entries) {
            val dx = (x - entry.value.x) / keyWidth
            val dy = (y - entry.value.y) / keyHeight
            scored.add(ScoredLetter(entry.key, sqrt(dx * dx + dy * dy)))
        }
        scored.sortBy { it.distance }
        val result = ArrayList<Char>(limit)
        for (item in scored) {
            if (result.size >= limit) break
            if (result.isEmpty() || item.distance <= 1.25f) result.add(item.letter)
        }
        return result
    }

    private class ScoredLetter(val letter: Char, val distance: Float)
}

/**
 * Compact first/last-letter index over the same word arrays that feed the prediction trie.
 * Every word is stored once; buckets only contain integer indices, so the extra memory is tiny.
 */
class SwipeLexicon private constructor(
    val words: Array<String>,
    val frequencies: IntArray,
    val russian: Boolean,
    private val buckets: HashMap<Int, IntArray>,
    val logMaxFrequency: Float
) {
    val size: Int get() = words.size

    /** All word indices whose first and last letters are in the supplied sets. */
    fun candidateIndices(first: Collection<Char>, last: Collection<Char>): IntArray {
        val uniqueFirst = LinkedHashSet<Char>(first)
        val uniqueLast = LinkedHashSet<Char>(last)
        var total = 0
        for (a in uniqueFirst) {
            for (b in uniqueLast) total += buckets[bucketKey(a, b)]?.size ?: 0
        }
        if (total == 0) return EMPTY
        val result = IntArray(total)
        var position = 0
        for (a in uniqueFirst) {
            for (b in uniqueLast) {
                val bucket = buckets[bucketKey(a, b)] ?: continue
                System.arraycopy(bucket, 0, result, position, bucket.size)
                position += bucket.size
            }
        }
        return result
    }

    private fun bucketKey(first: Char, last: Char): Int =
        (normalize(first).code shl 16) or normalize(last).code

    private fun normalize(letter: Char): Char =
        if (russian && letter == 'ё') 'е' else letter

    companion object {
        const val MAX_WORD_LENGTH = 32

        private val EMPTY = IntArray(0)

        fun build(words: Array<String>, frequencies: IntArray, russian: Boolean): SwipeLexicon {
            val counts = HashMap<Int, Int>(4096)
            var maxFrequency = 0
            for (index in words.indices) {
                val word = words[index]
                if (!usable(word)) continue
                val key = bucketKey(word[0], word[word.length - 1], russian)
                counts[key] = (counts[key] ?: 0) + 1
                val frequency = frequencies[index]
                if (frequency > maxFrequency) maxFrequency = frequency
            }

            val buckets = HashMap<Int, IntArray>(counts.size * 2)
            for ((key, count) in counts) buckets[key] = IntArray(count)
            val positions = HashMap<Int, Int>(counts.size * 2)
            for (index in words.indices) {
                val word = words[index]
                if (!usable(word)) continue
                val key = bucketKey(word[0], word[word.length - 1], russian)
                val bucket = buckets[key] ?: continue
                val position = positions[key] ?: 0
                bucket[position] = index
                positions[key] = position + 1
            }

            val logMax = ln(1.0 + maxFrequency).toFloat().coerceAtLeast(1f)
            return SwipeLexicon(words, frequencies, russian, buckets, logMax)
        }

        private fun bucketKey(first: Char, last: Char, russian: Boolean): Int {
            val a = if (russian && first == 'ё') 'е' else first
            val b = if (russian && last == 'ё') 'е' else last
            return (a.code shl 16) or b.code
        }

        /**
         * Hyphens are allowed only inside a word. The layout has no key for them, so the decoder
         * treats them as silent separators: a finger swiping «что-то» passes through the letters
         * of both components.
         */
        private fun usable(word: String): Boolean {
            if (word.length < 2 || word.length > MAX_WORD_LENGTH) return false
            var letters = 0
            for (index in word.indices) {
                val char = word[index]
                if (char.isLetter()) {
                    letters++
                    continue
                }
                val internalHyphen = char == '-' &&
                    index > 0 && index < word.lastIndex &&
                    word[index - 1].isLetter() && word[index + 1].isLetter()
                if (!internalHyphen) return false
            }
            return letters >= 2
        }
    }
}

/**
 * SHARK2-inspired decoder with a weighted DTW shape channel.
 *
 * The lifted finger path is resampled, compared to the normalized key-centre polyline of each
 * candidate, combined with soft start/end anchor probabilities, velocity/dwell and curvature
 * weights, a length prior and the dictionary frequency prior.
 *
 * Consecutive doubled letters are still collapsed on the reference path (a finger cannot produce a
 * second corner for "сс" in "класс"), but the length prior allows extra path length for candidates
 * that contain doubles. Russian ё is mapped to е on both sides of the match.
 */
class SwipeEngine {

    data class Result(val word: String, val frequency: Int, val score: Float)

    /** Geometry-only entry point kept for tests and old callers. */
    fun decode(
        path: FloatArray,
        layout: SwipeLayout,
        lexicon: SwipeLexicon,
        limit: Int = 3
    ): List<Result> = decode(SwipeGesture(path), layout, lexicon, limit)

    fun decode(
        gesture: SwipeGesture,
        layout: SwipeLayout,
        lexicon: SwipeLexicon,
        limit: Int = 3
    ): List<Result> {
        if (gesture.pointCount < 2 || limit <= 0) return emptyList()
        val rawPath = gesture.path
        val rawCount = gesture.pointCount

        val userRaw = FloatArray(POINT_COUNT * 2)
        val userTimes = if (gesture.hasTiming) LongArray(POINT_COUNT) else null
        if (!resample(rawPath, rawCount, userRaw, POINT_COUNT, gesture.times, userTimes)) {
            return emptyList()
        }
        val userNorm = FloatArray(POINT_COUNT * 2)
        if (normalize(userRaw, POINT_COUNT, userNorm) == null) return emptyList()
        val userTangents = Tangents()
        fillTangents(userRaw, POINT_COUNT, userTangents)
        val userWeights = pointWeights(userRaw, POINT_COUNT, userTimes, userTangents)
        val userLength = polylineLength(rawPath, rawCount)
        val letterEvidence = letterEvidence(gesture, layout)

        val startX = rawPath[0]
        val startY = rawPath[1]
        val endX = rawPath[rawPath.size - 2]
        val endY = rawPath[rawPath.size - 1]
        val firstLetters = layout.nearestLetters(startX, startY, 3)
        val lastLetters = layout.nearestLetters(endX, endY, 3)
        if (firstLetters.isEmpty() || lastLetters.isEmpty()) return emptyList()

        val candidates = lexicon.candidateIndices(firstLetters, lastLetters)
        if (candidates.isEmpty()) return emptyList()
        val visitedKeyCount = letterEvidence.values.count { it >= KEY_EVIDENCE_THRESHOLD }

        val idealKeys = FloatArray(SwipeLexicon.MAX_WORD_LENGTH * 2)
        val idealRaw = FloatArray(POINT_COUNT * 2)
        val idealNorm = FloatArray(POINT_COUNT * 2)
        val idealTangents = Tangents()
        val idealWeights = idealPointWeights()

        // Pass 1: cheap mean-distance prefilter over every candidate. It keeps the expensive DTW
        // pass bounded even when first/last buckets contain several thousand word forms.
        val cheapScores = FloatArray(candidates.size)
        val order = ArrayList<Int>(candidates.size)
        for (position in candidates.indices) {
            val candidate = candidates[position]
            val word = lexicon.words[candidate]
            val coverage = coveragePenalty(word, letterEvidence, lexicon.russian)
            // Cheap letter-lattice prefilter: a word whose letters mostly lie far from the path,
            // or which is much longer than the number of keys the finger actually passed near,
            // cannot be a good match and should not pay for buildIdeal/resample.
            if (coverage > COVERAGE_SKIP_THRESHOLD ||
                dedupedLength(word, lexicon.russian) > visitedKeyCount + LENGTH_SLACK
            ) {
                continue
            }
            val keyCount = buildIdeal(word, layout, idealKeys)
            if (keyCount < 2) continue
            if (!resample(idealKeys, keyCount, idealRaw, POINT_COUNT)) continue
            if (normalize(idealRaw, POINT_COUNT, idealNorm) == null) continue

            val cheapShape = meanDistance(userNorm, idealNorm)
            val location = anchorLocation(
                startX, startY, endX, endY,
                idealKeys[0], idealKeys[1],
                idealKeys[(keyCount - 1) * 2], idealKeys[(keyCount - 1) * 2 + 1],
                layout
            )
            val frequencyPrior = frequencyPrior(lexicon, candidate)
            val lengthPenalty = lengthPenalty(
                userLength, polylineLength(idealKeys, keyCount), word, lexicon.russian
            )
            cheapScores[position] = cheapShape +
                LOCATION_WEIGHT * location -
                FREQUENCY_WEIGHT * frequencyPrior +
                COVERAGE_WEIGHT * coverage +
                lengthPenalty
            order.add(position)
        }
        if (order.isEmpty()) return emptyList()
        order.sortBy { cheapScores[it] }

        // Pass 2: DTW with anchor/dwell/curvature weights for the best cheap candidates.
        val topIndices = IntArray(limit) { -1 }
        val topScores = FloatArray(limit) { Float.MAX_VALUE }
        val rescoreCount = min(order.size, DTW_CANDIDATE_LIMIT)
        for (rank in 0 until rescoreCount) {
            val candidate = candidates[order[rank]]
            val word = lexicon.words[candidate]
            val keyCount = buildIdeal(word, layout, idealKeys)
            if (keyCount < 2) continue
            if (!resample(idealKeys, keyCount, idealRaw, POINT_COUNT)) continue
            if (normalize(idealRaw, POINT_COUNT, idealNorm) == null) continue
            fillTangents(idealRaw, POINT_COUNT, idealTangents)

            val shape = dtwDistance(
                userNorm, idealNorm, userWeights, userTangents, idealWeights, idealTangents
            )
            val location = anchorLocation(
                startX, startY, endX, endY,
                idealKeys[0], idealKeys[1],
                idealKeys[(keyCount - 1) * 2], idealKeys[(keyCount - 1) * 2 + 1],
                layout
            )
            val frequencyPrior = frequencyPrior(lexicon, candidate)
            val lengthPenalty = lengthPenalty(
                userLength, polylineLength(idealKeys, keyCount), word, lexicon.russian
            )
            val coverage = coveragePenalty(word, letterEvidence, lexicon.russian)
            val score = shape +
                LOCATION_WEIGHT * location -
                FREQUENCY_WEIGHT * frequencyPrior +
                COVERAGE_WEIGHT * coverage +
                lengthPenalty

            var slot = -1
            for (index in 0 until limit) {
                if (topIndices[index] == -1 || score < topScores[index]) {
                    slot = index
                    break
                }
            }
            if (slot >= 0) {
                for (index in limit - 1 downTo slot + 1) {
                    topIndices[index] = topIndices[index - 1]
                    topScores[index] = topScores[index - 1]
                }
                topIndices[slot] = candidate
                topScores[slot] = score
            }
        }

        val result = ArrayList<Result>(limit)
        for (index in 0 until limit) {
            val candidate = topIndices[index]
            if (candidate < 0) continue
            result.add(Result(lexicon.words[candidate], lexicon.frequencies[candidate], topScores[index]))
        }
        return result
    }

    private fun buildIdeal(word: String, layout: SwipeLayout, out: FloatArray): Int {
        var count = 0
        var previous = '\u0000'
        for (raw in word) {
            var letter = raw.lowercaseChar()
            // A hyphen has no key: the finger moves straight from one component to the next.
            if (letter == '-') continue
            if (layout.russian && letter == 'ё') letter = 'е'
            val center = layout.letters[letter] ?: return -1
            if (letter == previous) continue
            if (count >= SwipeLexicon.MAX_WORD_LENGTH) break
            out[count * 2] = center.x
            out[count * 2 + 1] = center.y
            count++
            previous = letter
        }
        return if (count >= 2) count else -1
    }

    /**
     * Mean point distance of two paths that already share the same normalized scale/centroid.
     * Used only as the cheap pass-1 filter; the final score uses [dtwDistance].
     */
    private fun meanDistance(user: FloatArray, ideal: FloatArray): Float {
        var sum = 0f
        for (index in 0 until POINT_COUNT) {
            val dx = user[index * 2] - ideal[index * 2]
            val dy = user[index * 2 + 1] - ideal[index * 2 + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / POINT_COUNT
    }

    /**
     * Banded DTW with per-point weights. The anchors get a much larger weight, slow/curved users
     * points are emphasized, and a small tangent term discourages paths that visit the same keys
     * in a different order.
     */
    private fun dtwDistance(
        user: FloatArray,
        ideal: FloatArray,
        userWeights: FloatArray,
        userTangents: Tangents,
        idealWeights: FloatArray,
        idealTangents: Tangents
    ): Float {
        val n = POINT_COUNT
        val infinity = Float.MAX_VALUE / 4f
        var previous = FloatArray(n + 1) { infinity }
        var current = FloatArray(n + 1) { infinity }
        previous[0] = 0f

        for (i in 1..n) {
            java.util.Arrays.fill(current, infinity)
            val from = (i - DTW_BAND).coerceAtLeast(1)
            val to = (i + DTW_BAND).coerceAtMost(n)
            for (j in from..to) {
                val ui = i - 1
                val ij = j - 1
                val dx = user[ui * 2] - ideal[ij * 2]
                val dy = user[ui * 2 + 1] - ideal[ij * 2 + 1]
                val spatial = sqrt(dx * dx + dy * dy)
                val weight = (userWeights[ui] + idealWeights[ij]) * 0.5f
                val angle = (
                    1f - (userTangents.cos[ui] * idealTangents.cos[ij] +
                        userTangents.sin[ui] * idealTangents.sin[ij])
                    ) * 0.5f
                val local = spatial * weight + ANGLE_WEIGHT * angle
                val best = min(min(previous[j], current[j - 1]), previous[j - 1])
                current[j] = best + local
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[n] / (2f * n)
    }

    /** Gaussian anchor score: 0 at the key centre, approaching 1 far away. */
    private fun anchorLocation(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        firstKeyX: Float,
        firstKeyY: Float,
        lastKeyX: Float,
        lastKeyY: Float,
        layout: SwipeLayout
    ): Float {
        val startDistance = normalizedDistance(startX, startY, firstKeyX, firstKeyY, layout)
        val endDistance = normalizedDistance(endX, endY, lastKeyX, lastKeyY, layout)
        val startProbability = exp(
            -0.5f * (startDistance / ANCHOR_SIGMA) * (startDistance / ANCHOR_SIGMA)
        ).toFloat()
        val endProbability = exp(
            -0.5f * (endDistance / ANCHOR_SIGMA) * (endDistance / ANCHOR_SIGMA)
        ).toFloat()
        return 1f - (startProbability + endProbability) * 0.5f
    }

    private fun frequencyPrior(lexicon: SwipeLexicon, candidate: Int): Float =
        if (lexicon.logMaxFrequency <= 0f) 0f
        else (ln(1.0 + lexicon.frequencies[candidate]) / lexicon.logMaxFrequency).toFloat()

    /**
     * Soft per-letter evidence: for every key it keeps the Gaussian probability that the finger
     * passed near its centre. Unlike a hard crossed-letter stream, a fast transit through a key gets
     * a low but non-zero vote, and a deliberate letter gets a strong one.
     */
    private fun letterEvidence(gesture: SwipeGesture, layout: SwipeLayout): HashMap<Char, Float> {
        val evidence = HashMap<Char, Float>(40)
        val path = gesture.path
        val count = gesture.pointCount
        val sigmaSquared = KEY_SIGMA * KEY_SIGMA
        for ((letter, center) in layout.letters) {
            var bestSquared = Float.MAX_VALUE
            for (index in 0 until count) {
                val dx = (path[index * 2] - center.x) / layout.keyWidth.coerceAtLeast(1f)
                val dy = (path[index * 2 + 1] - center.y) / layout.keyHeight.coerceAtLeast(1f)
                val squared = dx * dx + dy * dy
                if (squared < bestSquared) bestSquared = squared
            }
            val probability = if (bestSquared == Float.MAX_VALUE) 0f else
                exp(-0.5f * bestSquared / sigmaSquared).toFloat()
            val normalized = normalizeLetter(letter, layout.russian)
            val previous = evidence[normalized]
            if (previous == null || probability > previous) evidence[normalized] = probability
        }
        return evidence
    }

    private fun coveragePenalty(
        word: String,
        evidence: Map<Char, Float>,
        russian: Boolean
    ): Float {
        var sum = 0f
        var count = 0
        var previous = '\u0000'
        for (raw in word) {
            if (raw == '-') continue
            val letter = normalizeLetter(raw.lowercaseChar(), russian)
            if (letter == previous) continue
            previous = letter
            sum += evidence[letter] ?: 0f
            count++
        }
        return if (count == 0) 1f else (1f - sum / count).coerceIn(0f, 1f)
    }

    private fun normalizeLetter(letter: Char, russian: Boolean): Char =
        if (russian && letter == 'ё') 'е' else letter

    private fun dedupedLength(word: String, russian: Boolean): Int {
        var count = 0
        var previous = '\u0000'
        for (raw in word) {
            if (raw == '-') continue
            val letter = normalizeLetter(raw.lowercaseChar(), russian)
            if (letter == previous) continue
            previous = letter
            count++
        }
        return count
    }

    /**
     * Discourages a short word from matching a long gesture. Extra path length is tolerated for
     * candidates with consecutive doubles, and ordinary bow/overshoot gets a small dead zone.
     */
    private fun lengthPenalty(
        userLength: Float,
        idealLength: Float,
        word: String,
        russian: Boolean
    ): Float {
        val ratio = (userLength + 1f) / (idealLength + 1f)
        val doubles = countDoubles(word, russian)
        val maxRatio = 1.25f + 0.15f * doubles
        val excess = when {
            ratio > maxRatio -> ratio / maxRatio
            ratio < 0.8f -> 0.8f / ratio
            else -> 1f
        }
        return LENGTH_WEIGHT * ln(excess)
    }

    private fun countDoubles(word: String, russian: Boolean): Int {
        var doubles = 0
        var previous = '\u0000'
        for (raw in word) {
            if (raw == '-') continue
            var letter = raw.lowercaseChar()
            if (russian && letter == 'ё') letter = 'е'
            if (letter == previous) doubles++
            previous = letter
        }
        return doubles
    }

    private fun normalizedDistance(
        x: Float,
        y: Float,
        keyX: Float,
        keyY: Float,
        layout: SwipeLayout
    ): Float {
        val dx = (x - keyX) / layout.keyWidth.coerceAtLeast(1f)
        val dy = (y - keyY) / layout.keyHeight.coerceAtLeast(1f)
        return sqrt(dx * dx + dy * dy)
    }

    private fun idealPointWeights(): FloatArray = FloatArray(POINT_COUNT) { index ->
        when (index) {
            0, POINT_COUNT - 1 -> ANCHOR_WEIGHT
            1, POINT_COUNT - 2 -> 1.35f
            else -> 1f
        }
    }

    private fun pointWeights(
        points: FloatArray,
        count: Int,
        times: LongArray?,
        tangents: Tangents
    ): FloatArray {
        val weights = FloatArray(count) { 1f }
        if (times != null && times.size == count) {
            val speeds = FloatArray(count)
            for (index in 0 until count) {
                val from = (index - 1).coerceAtLeast(0)
                val to = (index + 1).coerceAtMost(count - 1)
                val dt = (times[to] - times[from]).coerceAtLeast(1L)
                speeds[index] = segmentLength(points, from, to) / dt.toFloat()
            }
            var sum = 0f
            for (value in speeds) sum += value
            val average = (sum / count).coerceAtLeast(1e-3f)
            for (index in 0 until count) {
                val relative = (speeds[index] / average).coerceIn(0f, 1f)
                weights[index] *= 1f + DWELL_WEIGHT * (1f - relative)
            }
        }
        for (index in 0 until count) {
            val previous = (index - 1).coerceAtLeast(0)
            val next = (index + 1).coerceAtMost(count - 1)
            val dot = tangents.cos[previous] * tangents.cos[next] +
                tangents.sin[previous] * tangents.sin[next]
            val turn = ((1f - dot) * 0.5f).coerceIn(0f, 1f)
            weights[index] *= 1f + CURVATURE_WEIGHT * turn
        }
        weights[0] *= ANCHOR_WEIGHT
        weights[count - 1] *= ANCHOR_WEIGHT
        if (count > 2) {
            weights[1] *= 1.35f
            weights[count - 2] *= 1.35f
        }
        return weights
    }

    private class Tangents {
        val cos = FloatArray(POINT_COUNT)
        val sin = FloatArray(POINT_COUNT)
    }

    private fun fillTangents(points: FloatArray, count: Int, out: Tangents) {
        for (index in 0 until count) {
            val from = if (index == 0) 0 else index - 1
            val to = if (index == count - 1) count - 1 else index + 1
            val dx = points[to * 2] - points[from * 2]
            val dy = points[to * 2 + 1] - points[from * 2 + 1]
            val length = hypot(dx, dy)
            if (length < 1e-4f) {
                if (index > 0) {
                    out.cos[index] = out.cos[index - 1]
                    out.sin[index] = out.sin[index - 1]
                } else {
                    out.cos[index] = 1f
                    out.sin[index] = 0f
                }
            } else {
                out.cos[index] = dx / length
                out.sin[index] = dy / length
            }
        }
    }

    /**
     * Arc-length resampling to a fixed number of points, optionally carrying event times so the
     * decoder can estimate dwell. Returns false for a degenerate path.
     */
    private fun resample(
        source: FloatArray,
        sourceCount: Int,
        out: FloatArray,
        outCount: Int,
        sourceTimes: LongArray? = null,
        outTimes: LongArray? = null
    ): Boolean {
        if (sourceCount < 2 || outCount < 2) return false
        var total = 0f
        for (index in 1 until sourceCount) {
            total += segmentLength(source, index - 1, index)
        }
        if (total < 0.5f) return false

        val step = total / (outCount - 1)
        out[0] = source[0]
        out[1] = source[1]
        if (outTimes != null && sourceTimes != null) outTimes[0] = sourceTimes[0]
        var segment = 1
        var segmentStart = 0f
        var segmentSize = segmentLength(source, 0, 1)
        var target = step
        for (sample in 1 until outCount) {
            while (segment < sourceCount && segmentStart + segmentSize < target) {
                segmentStart += segmentSize
                segment++
                if (segment < sourceCount) segmentSize = segmentLength(source, segment - 1, segment)
            }
            if (segment >= sourceCount) {
                out[sample * 2] = source[(sourceCount - 1) * 2]
                out[sample * 2 + 1] = source[(sourceCount - 1) * 2 + 1]
                if (outTimes != null && sourceTimes != null) {
                    outTimes[sample] = sourceTimes[sourceCount - 1]
                }
            } else {
                val fraction = if (segmentSize <= 1e-6f) 0f
                else (target - segmentStart) / segmentSize
                val ax = source[(segment - 1) * 2]
                val ay = source[(segment - 1) * 2 + 1]
                val bx = source[segment * 2]
                val by = source[segment * 2 + 1]
                out[sample * 2] = ax + (bx - ax) * fraction
                out[sample * 2 + 1] = ay + (by - ay) * fraction
                if (outTimes != null && sourceTimes != null) {
                    val fromTime = sourceTimes[segment - 1]
                    val toTime = sourceTimes[segment]
                    val delta = (toTime - fromTime).coerceAtLeast(0L)
                    outTimes[sample] = fromTime + (delta * fraction).toLong()
                }
            }
            target += step
        }
        return true
    }

    /** Centres and scales a path into [out]; returns null when it collapses to a point. */
    private fun normalize(points: FloatArray, count: Int, out: FloatArray): Float? {
        var cx = 0f
        var cy = 0f
        for (index in 0 until count) {
            cx += points[index * 2]
            cy += points[index * 2 + 1]
        }
        cx /= count
        cy /= count
        var sumSquares = 0f
        for (index in 0 until count) {
            val dx = points[index * 2] - cx
            val dy = points[index * 2 + 1] - cy
            sumSquares += dx * dx + dy * dy
        }
        val scale = sqrt(sumSquares / count)
        if (scale < 1e-3f) return null
        for (index in 0 until count) {
            out[index * 2] = (points[index * 2] - cx) / scale
            out[index * 2 + 1] = (points[index * 2 + 1] - cy) / scale
        }
        return scale
    }

    private fun polylineLength(points: FloatArray, count: Int): Float {
        var total = 0f
        for (index in 1 until count) total += segmentLength(points, index - 1, index)
        return total
    }

    private fun segmentLength(points: FloatArray, from: Int, to: Int): Float {
        val dx = points[to * 2] - points[from * 2]
        val dy = points[to * 2 + 1] - points[from * 2 + 1]
        return hypot(dx, dy)
    }

    private companion object {
        const val POINT_COUNT = 32
        const val LOCATION_WEIGHT = 0.42f
        const val FREQUENCY_WEIGHT = 0.20f

        /** First/last samples get a strictly larger vote than the middle of the stroke. */
        const val ANCHOR_WEIGHT = 2.4f
        const val ANCHOR_SIGMA = 0.9f
        const val KEY_SIGMA = 0.9f
        const val KEY_EVIDENCE_THRESHOLD = 0.15f
        const val LENGTH_SLACK = 4
        const val COVERAGE_SKIP_THRESHOLD = 0.9f
        const val DWELL_WEIGHT = 0.8f
        const val CURVATURE_WEIGHT = 0.5f
        const val ANGLE_WEIGHT = 0.08f
        const val LENGTH_WEIGHT = 0.06f
        const val COVERAGE_WEIGHT = 0.12f

        const val DTW_BAND = 6
        const val DTW_CANDIDATE_LIMIT = 512
    }
}
