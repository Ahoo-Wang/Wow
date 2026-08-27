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

data class BenchmarkResultRow(
    val suiteId: String,
    val profile: String,
    val method: String,
    val threads: Int,
    val parameters: Map<String, String>,
    val mode: String,
    val score: Double,
    val scoreError: Double?,
    val unit: String,
    val allocationBytesPerOp: Double?,
)

data class BenchmarkMatrixSpec(
    val name: String,
    val suiteId: String,
    val profile: String,
    val methods: Set<String>,
    val threads: Set<Int>,
    val modes: Set<String>,
    val fixedParameters: Map<String, String> = emptyMap(),
    val parameterDimensions: Map<String, List<String>> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank." }
        require(suiteId.isNotBlank()) { "suiteId must not be blank." }
        require(profile.isNotBlank()) { "profile must not be blank." }
        require(methods.isNotEmpty() && methods.all(String::isNotBlank)) {
            "methods must contain only non-blank values."
        }
        require(threads.isNotEmpty() && threads.all { it > 0 }) {
            "threads must contain only positive values."
        }
        require(modes.isNotEmpty() && modes.all(String::isNotBlank)) {
            "modes must contain only non-blank values."
        }
        require(fixedParameters.keys.all(String::isNotBlank)) {
            "fixedParameters must contain only non-blank names."
        }
        require(fixedParameters.keys.intersect(parameterDimensions.keys).isEmpty()) {
            "fixedParameters and parameterDimensions must not overlap."
        }
        parameterDimensions.forEach { (parameter, values) ->
            require(parameter.isNotBlank()) {
                "Parameter dimension names must not be blank."
            }
            require(
                values.isNotEmpty() &&
                    values.all(String::isNotBlank) &&
                    values.distinct().size == values.size
            ) {
                "Parameter dimension[$parameter] must contain distinct non-blank values."
            }
        }
    }
}

private data class BenchmarkMatrixKey(
    val method: String,
    val threads: Int,
    val mode: String,
    val parameters: Map<String, String>,
)

fun validateBenchmarkMatrix(
    spec: BenchmarkMatrixSpec,
    rows: List<BenchmarkResultRow>,
) {
    val parameterSets = parameterCombinations(spec.parameterDimensions)
    val expectedKeys = spec.methods.flatMap { method ->
        spec.threads.flatMap { threads ->
            spec.modes.flatMap { mode ->
                parameterSets.map { dynamicParameters ->
                    BenchmarkMatrixKey(
                        method = method,
                        threads = threads,
                        mode = mode,
                        parameters = (spec.fixedParameters + dynamicParameters).toSortedMap(),
                    )
                }
            }
        }
    }.toSet()
    val actualKeys = rows.map(BenchmarkResultRow::toMatrixKey)
    val duplicateKeys = actualKeys.groupingBy { it }.eachCount()
        .filterValues { count -> count > 1 }
        .keys
    if (duplicateKeys.isNotEmpty()) {
        throw BenchmarkValidationException(
            "${spec.name} results contain duplicate rows: $duplicateKeys."
        )
    }
    if (rows.size != expectedKeys.size || actualKeys.toSet() != expectedKeys) {
        throw BenchmarkValidationException(
            "${spec.name} matrix must contain exactly ${expectedKeys.size} rows. " +
                "Missing=${expectedKeys - actualKeys.toSet()}, " +
                "unexpected=${actualKeys.toSet() - expectedKeys}."
        )
    }
    rows.forEach { row ->
        if (row.suiteId != spec.suiteId || row.profile != spec.profile) {
            throw BenchmarkValidationException(
                "${spec.name} row has unexpected suite/profile: " +
                    "${row.suiteId}/${row.profile}."
            )
        }
        val validUnit = when (row.mode) {
            "thrpt" -> row.unit.equals("ops/s", ignoreCase = true)
            "avgt" -> row.unit.endsWith("/op") &&
                latencyUnitSeconds(row.unit.removeSuffix("/op")) != null

            else -> false
        }
        if (!validUnit) {
            throw BenchmarkValidationException(
                "${spec.name} row has invalid ${row.mode} unit '${row.unit}': ${row.method}."
            )
        }
        if (!row.score.isFinite() || row.score <= 0.0) {
            throw BenchmarkValidationException(
                "${spec.name} row has a non-positive or non-finite score: " +
                    "${row.method}=${row.score}."
            )
        }
        val allocation = row.allocationBytesPerOp
        if (allocation == null || !allocation.isFinite() || allocation <= 0.0) {
            throw BenchmarkValidationException(
                "${spec.name} row is missing a positive finite gc.alloc.rate.norm: ${row.method}."
            )
        }
    }
}

private fun BenchmarkResultRow.toMatrixKey(): BenchmarkMatrixKey {
    return BenchmarkMatrixKey(
        method = method,
        threads = threads,
        mode = mode,
        parameters = parameters.toSortedMap(),
    )
}

private fun parameterCombinations(
    dimensions: Map<String, List<String>>,
): List<Map<String, String>> {
    return dimensions.entries.fold(listOf(emptyMap())) { combinations, (name, values) ->
        combinations.flatMap { combination ->
            values.map { value -> combination + (name to value) }
        }
    }
}
