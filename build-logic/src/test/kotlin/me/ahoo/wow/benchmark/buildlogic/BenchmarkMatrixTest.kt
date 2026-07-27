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

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BenchmarkMatrixTest {
    private val spec = BenchmarkMatrixSpec(
        name = "coordinator lanes",
        suiteId = "suite",
        profile = "profile",
        methods = setOf("append"),
        threads = setOf(4),
        modes = setOf("thrpt", "avgt"),
        fixedParameters = mapOf("batchOptions" to "192x250us"),
        parameterDimensions = mapOf("coordinatorLanes" to listOf("1", "2", "4")),
    )
    private val validRows = listOf("1", "2", "4").flatMap { lanes ->
        listOf("thrpt", "avgt").map { mode ->
            row(lanes, mode)
        }
    }

    @Test
    fun `should accept the exact Cartesian matrix`() {
        assertDoesNotThrow {
            validateBenchmarkMatrix(spec, validRows)
        }
    }

    @Test
    fun `should reject missing duplicate and unexpected rows`() {
        assertThrows(BenchmarkValidationException::class.java) {
            validateBenchmarkMatrix(spec, validRows.dropLast(1))
        }
        assertThrows(BenchmarkValidationException::class.java) {
            validateBenchmarkMatrix(spec, validRows + validRows.first())
        }
        assertThrows(BenchmarkValidationException::class.java) {
            validateBenchmarkMatrix(
                spec,
                validRows.mapIndexed { index, row ->
                    if (index == 0) {
                        row.copy(parameters = row.parameters + ("coordinatorLanes" to "3"))
                    } else {
                        row
                    }
                },
            )
        }
    }

    @Test
    fun `should reject invalid identity metric and allocation`() {
        assertThrows(BenchmarkValidationException::class.java) {
            validateBenchmarkMatrix(
                spec,
                validRows.mapIndexed { index, row ->
                    if (index == 0) row.copy(suiteId = "other") else row
                },
            )
        }
        assertThrows(BenchmarkValidationException::class.java) {
            validateBenchmarkMatrix(
                spec,
                validRows.mapIndexed { index, row ->
                    if (index == 0) row.copy(unit = "ms") else row
                },
            )
        }
        assertThrows(BenchmarkValidationException::class.java) {
            validateBenchmarkMatrix(
                spec,
                validRows.mapIndexed { index, row ->
                    if (index == 0) row.copy(score = Double.NaN) else row
                },
            )
        }
        assertThrows(BenchmarkValidationException::class.java) {
            validateBenchmarkMatrix(
                spec,
                validRows.mapIndexed { index, row ->
                    if (index == 0) row.copy(allocationBytesPerOp = null) else row
                },
            )
        }
    }

    private fun row(lanes: String, mode: String): BenchmarkResultRow {
        return BenchmarkResultRow(
            suiteId = "suite",
            profile = "profile",
            method = "append",
            threads = 4,
            parameters = mapOf(
                "batchOptions" to "192x250us",
                "coordinatorLanes" to lanes,
            ),
            mode = mode,
            score = if (mode == "thrpt") 1_000.0 * lanes.toInt() else 100.0 / lanes.toInt(),
            scoreError = null,
            unit = if (mode == "thrpt") "ops/s" else "us/op",
            allocationBytesPerOp = 1_024.0 * lanes.toInt(),
        )
    }
}
