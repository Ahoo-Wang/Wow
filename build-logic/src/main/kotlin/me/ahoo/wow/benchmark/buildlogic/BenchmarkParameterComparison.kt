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

data class BenchmarkParameterComparisonSpec(
    val sectionTitle: String,
    val introduction: String,
    val parameterName: String,
    val parameterLabel: String,
    val parameterValues: List<String>,
    val baselineLabel: String,
    val conclusion: String,
) {
    init {
        require(sectionTitle.isNotBlank()) { "sectionTitle must not be blank." }
        require(introduction.isNotBlank()) { "introduction must not be blank." }
        require(parameterName.isNotBlank()) { "parameterName must not be blank." }
        require(parameterLabel.isNotBlank()) { "parameterLabel must not be blank." }
        require(
            parameterValues.isNotEmpty() &&
                parameterValues.all(String::isNotBlank) &&
                parameterValues.distinct().size == parameterValues.size
        ) {
            "parameterValues must contain distinct non-blank values."
        }
        require(baselineLabel.isNotBlank()) { "baselineLabel must not be blank." }
        require(conclusion.isNotBlank()) { "conclusion must not be blank." }
    }
}

fun renderBenchmarkParameterComparison(
    spec: BenchmarkParameterComparisonSpec,
    rows: List<BenchmarkResultRow>,
): String {
    val keyedRows = rows.map { row ->
        val parameter = row.parameters[spec.parameterName]
            ?: throw BenchmarkValidationException(
                "Benchmark row is missing parameter[${spec.parameterName}]: $row."
            )
        (parameter to row.mode) to row
    }
    val duplicateKeys = keyedRows.groupingBy { it.first }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    if (duplicateKeys.isNotEmpty()) {
        throw BenchmarkValidationException(
            "Benchmark parameter comparison contains duplicate rows: $duplicateKeys."
        )
    }
    val rowsByKey = keyedRows.toMap()
    val expectedKeys = spec.parameterValues.flatMap { parameter ->
        listOf("thrpt", "avgt").map { mode -> parameter to mode }
    }.toSet()
    if (rowsByKey.keys != expectedKeys) {
        throw BenchmarkValidationException(
            "Benchmark parameter comparison has an invalid result matrix. " +
                "Missing=${expectedKeys - rowsByKey.keys}, " +
                "unexpected=${rowsByKey.keys - expectedKeys}."
        )
    }
    val throughputRows = spec.parameterValues.map { parameter ->
        rowsByKey.required(parameter, "thrpt")
    }
    val averageTimeRows = spec.parameterValues.map { parameter ->
        rowsByKey.required(parameter, "avgt")
    }
    val throughputScale = benchmarkMetricScale(
        throughputRows.map(BenchmarkResultRow::score),
        throughputRows.first().unit,
    )
    val averageTimeScale = benchmarkMetricScale(
        averageTimeRows.map(BenchmarkResultRow::score),
        averageTimeRows.first().unit,
    )
    val baselineThroughput = throughputRows.first()
    val baselineAverageTime = averageTimeRows.first()
    val baselineAllocation = checkNotNull(baselineThroughput.allocationBytesPerOp)

    return buildString {
        appendLine("## ${spec.sectionTitle}")
        appendLine()
        appendLine(spec.introduction)
        appendLine()
        appendLine(
            "| ${spec.parameterLabel} | Throughput | vs ${spec.baselineLabel} | " +
                "Amortized time per event | Time reduction vs ${spec.baselineLabel} | " +
                "Allocation | Allocation reduction vs ${spec.baselineLabel} |"
        )
        appendLine(
            "|-------------------|------------|-----------|---------------------------|" +
                "--------------------------|------------|--------------------------------|"
        )
        spec.parameterValues.forEachIndexed { index, parameter ->
            val throughput = throughputRows[index]
            val averageTime = averageTimeRows[index]
            val allocation = checkNotNull(throughput.allocationBytesPerOp)
            val throughputDisplay = formatScaledBenchmarkScore(
                throughput.score,
                throughput.scoreError,
                throughputScale,
            )
            val averageTimeDisplay = formatScaledBenchmarkScore(
                averageTime.score,
                averageTime.scoreError,
                averageTimeScale,
            )
            val throughputChange = baselineOrChange(index) {
                relativeChangePercent(baselineThroughput.score, throughput.score)
            }
            val averageTimeReduction = baselineOrChange(index) {
                reductionPercent(baselineAverageTime.score, averageTime.score)
            }
            val allocationReduction = baselineOrChange(index) {
                reductionPercent(baselineAllocation, allocation)
            }
            appendLine(
                "| $parameter | ${throughputDisplay.scoreWithUnit} | $throughputChange | " +
                    "${averageTimeDisplay.scoreWithUnit} | $averageTimeReduction | " +
                    "${formatAllocationBytes(allocation)} | $allocationReduction |"
            )
        }
        appendLine()
        appendLine(spec.conclusion)
        appendLine()
    }
}

private fun Map<Pair<String, String>, BenchmarkResultRow>.required(
    parameter: String,
    mode: String,
): BenchmarkResultRow {
    return this[parameter to mode]
        ?: throw BenchmarkValidationException(
            "Benchmark parameter comparison is missing parameter[$parameter]/mode[$mode]."
        )
}

private inline fun baselineOrChange(
    index: Int,
    change: () -> Double,
): String {
    return if (index == 0) {
        "baseline"
    } else {
        formatSignedPercent(change())
    }
}
