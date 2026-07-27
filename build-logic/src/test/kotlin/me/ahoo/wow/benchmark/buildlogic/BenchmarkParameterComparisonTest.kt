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

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BenchmarkParameterComparisonTest {
    @Test
    fun `should render parameter comparison with a stable baseline`() {
        val report = renderBenchmarkParameterComparison(
            spec = spec(),
            rows = listOf("1", "2", "4").flatMap { lanes ->
                listOf("thrpt", "avgt").map { mode ->
                    row(lanes, mode)
                }
            },
        )

        assertTrue(
            report.contains(
                "| Coordinator lanes | Throughput | vs lane 1 | " +
                    "Amortized time per event | Time reduction vs lane 1 |"
            )
        )
        assertTrue(report.contains("| 1 | 1 k ops/s | baseline | 100 µs/op | baseline | 1 KiB/op | baseline |"))
        assertTrue(report.contains("| 4 | 4 k ops/s | +300.0% | 25 µs/op | +75.0% | 4 KiB/op | -300.0% |"))
    }

    @Test
    fun `should reject duplicate result rows`() {
        val spec = spec()
        val rows = listOf("1", "2", "4").flatMap { lanes ->
            listOf("thrpt", "avgt").map { mode ->
                row(lanes, mode)
            }
        }

        assertThrows(BenchmarkValidationException::class.java) {
            renderBenchmarkParameterComparison(spec, rows + rows.first())
        }
    }

    @Test
    fun `should reject missing and unexpected result rows`() {
        val spec = spec()
        val rows = listOf("1", "2", "4").flatMap { lanes ->
            listOf("thrpt", "avgt").map { mode ->
                row(lanes, mode)
            }
        }

        assertThrows(BenchmarkValidationException::class.java) {
            renderBenchmarkParameterComparison(spec, rows.dropLast(1))
        }
        assertThrows(BenchmarkValidationException::class.java) {
            renderBenchmarkParameterComparison(spec, rows + row("8", "thrpt"))
        }
    }

    private fun spec(): BenchmarkParameterComparisonSpec {
        return BenchmarkParameterComparisonSpec(
            sectionTitle = "Coordinator Lane Comparison",
            introduction = "Introduction.",
            parameterName = "coordinatorLanes",
            parameterLabel = "Coordinator lanes",
            parameterValues = listOf("1", "2", "4"),
            baselineLabel = "lane 1",
            conclusion = "Conclusion.",
        )
    }

    private fun row(lanes: String, mode: String): BenchmarkResultRow {
        val laneCount = lanes.toInt()
        return BenchmarkResultRow(
            suiteId = "suite",
            profile = "profile",
            method = "append",
            threads = 4,
            parameters = mapOf("coordinatorLanes" to lanes),
            mode = mode,
            score = if (mode == "thrpt") 1_000.0 * laneCount else 100.0 / laneCount,
            scoreError = null,
            unit = if (mode == "thrpt") "ops/s" else "us/op",
            allocationBytesPerOp = 1_024.0 * laneCount,
        )
    }
}
