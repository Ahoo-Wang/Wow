/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.benchmark.load

import java.util.SplittableRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Which of the [accounts] seeded accounts a request targets: `uniform`, `zipf` (rank `k` drawn with a
 * probability proportional to `1 / k^s`, `s` = 1.1 by default) or `hot` (a share [hotFraction] of the requests to the
 * first [hotKeys] accounts, the rest uniform over all). Draws come from a seeded generator, so a run's sequence of keys
 * is the same on both versions.
 */
class KeyChooser(
    val distribution: String,
    val accounts: Int,
    seed: Long,
    private val zipfExponent: Double = DEFAULT_ZIPF_EXPONENT,
    private val hotFraction: Double = DEFAULT_HOT_FRACTION,
    private val hotKeys: Int = 1,
) {
    private val random = SplittableRandom(seed)
    private val zipfCumulative: DoubleArray? =
        if (distribution == ZIPF) sharedZipfCumulative(accounts, zipfExponent) else null

    init {
        require(accounts > 0) { "At least one account." }
        require(distribution in DISTRIBUTIONS) { "Unknown distribution '$distribution': one of $DISTRIBUTIONS." }
        require(hotKeys in 1..accounts) { "The hot keys are 1 to $accounts." }
    }

    /** The next account's index, 0 to [accounts] - 1. Not thread-safe: the scheduler draws on its own thread. */
    fun next(): Int = when (distribution) {
        UNIFORM -> random.nextInt(accounts)
        ZIPF -> zipfIndex(random.nextDouble())
        else -> if (random.nextDouble() < hotFraction) random.nextInt(hotKeys) else random.nextInt(accounts)
    }

    /** An account other than [index], drawn the same way: a transfer's target. */
    fun nextOtherThan(index: Int): Int {
        if (accounts == 1) return index
        var other = next()
        while (other == index) other = next()
        return other
    }

    /** What describes the distribution in the results. */
    fun describe(): Map<String, Any> = buildMap {
        put("distribution", distribution)
        put("accounts", accounts)
        if (distribution == ZIPF) put("zipfExponent", zipfExponent)
        if (distribution == HOT) {
            put("hotFraction", hotFraction)
            put("hotKeys", hotKeys)
        }
    }

    private fun zipfIndex(u: Double): Int {
        val cumulative = checkNotNull(zipfCumulative)
        var low = 0
        var high = cumulative.size - 1
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cumulative[middle] < u) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        const val UNIFORM = "uniform"
        const val ZIPF = "zipf"
        const val HOT = "hot"
        val DISTRIBUTIONS = setOf(UNIFORM, ZIPF, HOT)
        const val DEFAULT_ZIPF_EXPONENT = 1.1
        const val DEFAULT_HOT_FRACTION = 0.5

        // A sweep's every client has a chooser of its own: each a table of its own would be 800 KB a client at 100,000
        // accounts, 3.3 GB at 4,096 clients, past the load generator's heap. Read-only once built, so one serves all.
        private val zipfTables = ConcurrentHashMap<Pair<Int, Double>, DoubleArray>()

        /** [zipfCumulative] of [n] and [s], built once and shared by every chooser that draws from it. */
        fun sharedZipfCumulative(n: Int, s: Double): DoubleArray =
            zipfTables.computeIfAbsent(n to s) { zipfCumulative(n, s) }

        /** The cumulative probabilities of the ranks 1 to [n] under Zipf's law with exponent [s]. */
        fun zipfCumulative(n: Int, s: Double): DoubleArray {
            val weights = DoubleArray(n) { 1.0 / Math.pow((it + 1).toDouble(), s) }
            val total = weights.sum()
            var running = 0.0
            return DoubleArray(n) { index ->
                running += weights[index] / total
                if (index == n - 1) 1.0 else running
            }
        }
    }
}
