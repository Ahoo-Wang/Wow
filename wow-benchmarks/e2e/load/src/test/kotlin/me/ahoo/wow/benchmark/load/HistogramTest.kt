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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.SplittableRandom

class HistogramTest {
    @Test
    fun `buckets should be contiguous and hold their own values`() {
        var expectedLowest = 0L
        var index = 0
        while (Histogram.lowestOf(index) <= Histogram.MAX_VALUE && index < 4480) {
            Histogram.lowestOf(index).assert().isEqualTo(expectedLowest)
            Histogram.indexOf(Histogram.lowestOf(index)).assert().isEqualTo(index)
            Histogram.indexOf(Histogram.highestOf(index)).assert().isEqualTo(index)
            expectedLowest = Histogram.highestOf(index) + 1
            index++
        }
        Histogram.indexOf(Histogram.MAX_VALUE).assert().isEqualTo(index - 1)
    }

    @Test
    fun `small values should be exact`() {
        val histogram = Histogram()
        (1L..100L).forEach(histogram::record)
        histogram.percentile(50.0).assert().isEqualTo(50)
        histogram.percentile(99.0).assert().isEqualTo(99)
        histogram.percentile(100.0).assert().isEqualTo(100)
        histogram.max.assert().isEqualTo(100)
        histogram.count.assert().isEqualTo(100)
    }

    @Test
    fun `percentiles should be within the resolution of the exact ones`() {
        val random = SplittableRandom(1)
        val values = LongArray(200_000) { (Math.exp(random.nextDouble() * 14)).toLong() }
        val histogram = Histogram()
        values.forEach(histogram::record)
        values.sort()
        listOf(50.0, 90.0, 99.0, 99.9).forEach { percentile ->
            val exact = values[(Math.ceil(percentile / 100 * values.size) - 1).toInt()]
            val recorded = histogram.percentile(percentile)
            (recorded >= exact).assert().isTrue()
            (recorded - exact <= exact / 128 + 1).assert().isTrue()
        }
        histogram.max.assert().isEqualTo(values.last())
    }

    @Test
    fun `add should merge counts, maximum and mean`() {
        val first = Histogram().apply { record(10) }
        val second = Histogram().apply { record(30) }
        first.add(second)
        first.count.assert().isEqualTo(2)
        first.max.assert().isEqualTo(30)
        first.mean.assert().isEqualTo(20.0)
        first.buckets().map { it.toList() }.assert().containsExactly(listOf(10L, 10L, 1L), listOf(30L, 30L, 1L))
    }

    @Test
    fun `an empty histogram should report zero`() {
        Histogram().percentile(99.0).assert().isEqualTo(0)
    }
}
