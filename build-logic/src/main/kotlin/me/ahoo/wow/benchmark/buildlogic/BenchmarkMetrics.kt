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

import java.util.Locale
import kotlin.math.abs

data class FormattedBenchmarkScore(
    val score: String,
    val error: String,
    val unit: String,
) {
    val scoreWithUnit: String
        get() = "$score $unit"

    val errorWithUnit: String
        get() = if (error == "-") "-" else "$error $unit"
}

data class BenchmarkMetricScale(
    val multiplier: Double,
    val unit: String,
)

fun latencyUnitSeconds(unit: String): Double? {
    return when (unit) {
        "s" -> 1.0
        "ms" -> 1.0e-3
        "us", "µs" -> 1.0e-6
        "ns" -> 1.0e-9
        else -> null
    }
}

private fun latencyDisplayUnit(secondsPerOp: Double): String {
    val absoluteSeconds = abs(secondsPerOp)
    return when {
        absoluteSeconds == 0.0 -> "s"
        absoluteSeconds < 1.0e-6 -> "ns"
        absoluteSeconds < 1.0e-3 -> "µs"
        absoluteSeconds < 1.0 -> "ms"
        else -> "s"
    }
}

fun benchmarkMetricScale(values: List<Double>, unit: String): BenchmarkMetricScale {
    val magnitude = values.maxOfOrNull { abs(it) } ?: 0.0
    val latencySourceUnit = unit.removeSuffix("/op").takeIf { unit.endsWith("/op") }
    val sourceSeconds = latencySourceUnit?.let(::latencyUnitSeconds)
    if (sourceSeconds != null) {
        val secondsPerOp = magnitude * sourceSeconds
        val displayUnit = if (secondsPerOp == 0.0) {
            latencySourceUnit.replace("us", "µs")
        } else {
            latencyDisplayUnit(secondsPerOp)
        }
        return BenchmarkMetricScale(
            multiplier = sourceSeconds / checkNotNull(latencyUnitSeconds(displayUnit)),
            unit = "$displayUnit/op",
        )
    }
    if (unit.equals("B/op", ignoreCase = true)) {
        val (divisor, displayUnit) = when {
            magnitude >= 1024.0 * 1024.0 * 1024.0 -> 1024.0 * 1024.0 * 1024.0 to "GiB/op"
            magnitude >= 1024.0 * 1024.0 -> 1024.0 * 1024.0 to "MiB/op"
            magnitude >= 1024.0 -> 1024.0 to "KiB/op"
            else -> 1.0 to "B/op"
        }
        return BenchmarkMetricScale(multiplier = 1.0 / divisor, unit = displayUnit)
    }
    if (unit.contains("ops", ignoreCase = true)) {
        val (divisor, prefix) = when {
            magnitude >= 1.0e12 -> 1.0e12 to "T"
            magnitude >= 1.0e9 -> 1.0e9 to "G"
            magnitude >= 1.0e6 -> 1.0e6 to "M"
            magnitude >= 1.0e3 -> 1.0e3 to "k"
            else -> 1.0 to ""
        }
        val displayUnit = if (prefix.isEmpty()) unit else "$prefix $unit"
        return BenchmarkMetricScale(multiplier = 1.0 / divisor, unit = displayUnit)
    }
    return BenchmarkMetricScale(multiplier = 1.0, unit = unit)
}

fun formatMetricNumber(value: Double): String {
    if (value == 0.0) {
        return "0"
    }
    val formatted = if (abs(value) < 0.01) {
        String.format(Locale.US, "%.2g", value)
    } else {
        String.format(Locale.US, "%.2f", value)
    }
    return if (formatted.contains('e', ignoreCase = true)) {
        formatted
    } else {
        formatted.trimEnd('0').trimEnd('.')
    }
}

fun formatMetricError(error: Double?, scale: BenchmarkMetricScale): String {
    return error?.let {
        val scaledError = abs(it * scale.multiplier)
        if (scaledError in 0.0..<0.01 && scaledError != 0.0) {
            "±<0.01"
        } else {
            "±${formatMetricNumber(scaledError)}"
        }
    } ?: "-"
}

fun formatBenchmarkMetric(
    score: Double,
    scoreError: Double?,
    unit: String,
    scaleReferenceValues: List<Double> = listOf(score),
): FormattedBenchmarkScore {
    val scale = benchmarkMetricScale(scaleReferenceValues, unit)
    return FormattedBenchmarkScore(
        score = formatMetricNumber(score * scale.multiplier),
        error = formatMetricError(scoreError, scale),
        unit = scale.unit,
    )
}

fun formatBenchmarkScore(
    score: Double,
    scoreError: Double?,
    unit: String,
): FormattedBenchmarkScore {
    return formatBenchmarkMetric(score, scoreError, unit)
}

fun formatScaledBenchmarkScore(
    score: Double,
    scoreError: Double?,
    scale: BenchmarkMetricScale,
): FormattedBenchmarkScore {
    return FormattedBenchmarkScore(
        score = formatMetricNumber(score * scale.multiplier),
        error = formatMetricError(scoreError, scale),
        unit = scale.unit,
    )
}

fun formatAllocationBytes(allocationBytesPerOp: Double?): String {
    return allocationBytesPerOp?.let { allocation ->
        formatBenchmarkMetric(allocation, null, "B/op").scoreWithUnit
    } ?: "-"
}

fun relativeChangePercent(reference: Double, current: Double): Double {
    require(reference > 0.0) {
        "Comparison reference must be greater than zero: $reference"
    }
    return (current / reference - 1.0) * 100.0
}

fun reductionPercent(reference: Double, current: Double): Double {
    return -relativeChangePercent(reference, current)
}

fun formatSignedPercent(value: Double): String {
    return String.format(Locale.US, "%+.1f%%", value)
}

fun formatUnsignedPercent(value: Double): String {
    return String.format(Locale.US, "%.1f%%", value)
}

fun formatRatio(reference: Double, current: Double): String {
    require(reference > 0.0) {
        "Ratio reference must be greater than zero: $reference"
    }
    return String.format(Locale.US, "%.2f×", current / reference)
}
