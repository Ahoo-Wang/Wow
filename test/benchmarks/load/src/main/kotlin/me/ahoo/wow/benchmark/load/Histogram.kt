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

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * A log-linear histogram of non-negative values (microseconds here), the recorder of the load generator: the same
 * bucketing HdrHistogram uses, without the dependency.
 *
 * Values below `2^(SUB_BITS + 1)` each have a bucket of their own. Above, each power of two is split into
 * `2^SUB_BITS` equal buckets, so a bucket is at most `1 / 2^SUB_BITS` of its values wide: with 7 bits, a percentile is
 * within 0.8% of the value recorded. Values above [MAX_VALUE] are counted in the last bucket; the exact [max] is kept
 * beside the buckets. Recording is lock-free and safe from any thread.
 */
class Histogram {
    private val counts = AtomicLongArray(BUCKETS)
    private val total = AtomicLong()
    private val sum = AtomicLong()
    private val maxValue = AtomicLong()

    val count: Long get() = total.get()
    val max: Long get() = maxValue.get()
    val mean: Double get() = if (count == 0L) 0.0 else sum.get().toDouble() / count

    fun record(value: Long) {
        val v = value.coerceIn(0, MAX_VALUE)
        counts.incrementAndGet(indexOf(v))
        total.incrementAndGet()
        sum.addAndGet(v)
        maxValue.accumulateAndGet(v, ::maxOf)
    }

    /** Adds every value of [other] to this histogram. */
    fun add(other: Histogram) {
        for (index in 0 until BUCKETS) {
            val n = other.counts.get(index)
            if (n != 0L) counts.addAndGet(index, n)
        }
        total.addAndGet(other.count)
        sum.addAndGet(other.sum.get())
        maxValue.accumulateAndGet(other.max, ::maxOf)
    }

    /**
     * The value at [percentile] (0 to 100): the highest value of the bucket that holds the value of that rank, at
     * most [max]; 0 when nothing was recorded.
     */
    fun percentile(percentile: Double): Long {
        val n = count
        if (n == 0L) return 0
        val rank = maxOf(1L, kotlin.math.ceil(percentile / PERCENT * n).toLong())
        var seen = 0L
        for (index in 0 until BUCKETS) {
            seen += counts.get(index)
            if (seen >= rank) return minOf(highestOf(index), max)
        }
        return max
    }

    /** The non-empty buckets as `[lowest value, highest value, count]`, for the raw results. */
    fun buckets(): List<LongArray> = (0 until BUCKETS).mapNotNull { index ->
        val n = counts.get(index)
        if (n == 0L) null else longArrayOf(lowestOf(index), highestOf(index), n)
    }

    companion object {
        /** The bits of the buckets within a power of two: 128 buckets, a resolution of 1/128. */
        const val SUB_BITS = 7
        private const val SUB = 1 shl SUB_BITS
        private const val EXACT = SUB shl 1

        /** The highest power of two kept: about 12.7 days in microseconds. */
        private const val MAX_EXPONENT = 40
        const val MAX_VALUE: Long = (1L shl (MAX_EXPONENT + 1)) - 1
        private const val BUCKETS = (MAX_EXPONENT - SUB_BITS + 1) * SUB + SUB
        private const val PERCENT = 100.0

        /** The bucket of [value]. */
        fun indexOf(value: Long): Int {
            if (value < EXACT) return value.toInt()
            val exponent = 63 - java.lang.Long.numberOfLeadingZeros(value)
            val shift = exponent - SUB_BITS
            val mantissa = (value ushr shift).toInt()
            return (shift + 1) * SUB + (mantissa - SUB)
        }

        /** The lowest value of the bucket [index]. */
        fun lowestOf(index: Int): Long {
            if (index < EXACT) return index.toLong()
            val shift = index / SUB - 1
            val mantissa = SUB + index % SUB
            return mantissa.toLong() shl shift
        }

        /** The highest value of the bucket [index]. */
        fun highestOf(index: Int): Long {
            if (index < EXACT) return index.toLong()
            val shift = index / SUB - 1
            return lowestOf(index) + (1L shl shift) - 1
        }
    }
}
