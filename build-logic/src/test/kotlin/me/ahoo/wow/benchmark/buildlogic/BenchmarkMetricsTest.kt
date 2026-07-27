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

package me.ahoo.wow.benchmark.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BenchmarkMetricsTest {
    @Test
    fun `should format throughput latency and allocation consistently`() {
        assertEquals(
            FormattedBenchmarkScore("1.57", "±0.04", "k ops/s"),
            formatBenchmarkScore(1_573.91, 42.0, "ops/s"),
        )
        assertEquals(
            FormattedBenchmarkScore("668.85", "±1.24", "M ops/s"),
            formatBenchmarkScore(668_849_367.69, 1_240_000.0, "ops/s"),
        )
        assertEquals(
            FormattedBenchmarkScore("850", "±20", "ns/op"),
            formatBenchmarkScore(0.000_85, 0.000_02, "ms/op"),
        )
        assertEquals("2.84 MiB/op", formatAllocationBytes(2_982_851.6))
        assertEquals("272 B/op", formatAllocationBytes(272.0))
        assertEquals("0 B/op", formatAllocationBytes(0.0))
        assertEquals("-", formatAllocationBytes(null))
    }

    @Test
    fun `should format comparisons consistently`() {
        assertEquals("0.0042", formatMetricNumber(0.004_2))
        assertEquals(
            "±<0.01",
            formatMetricError(0.004_2, BenchmarkMetricScale(1.0, "ops/s")),
        )
        assertEquals(25.0, relativeChangePercent(100.0, 125.0))
        assertEquals(75.0, reductionPercent(100.0, 25.0))
        assertEquals("+25.0%", formatSignedPercent(25.04))
        assertEquals("75.0%", formatUnsignedPercent(75.04))
        assertEquals("1.97×", formatRatio(100.0, 197.0))
    }
}
