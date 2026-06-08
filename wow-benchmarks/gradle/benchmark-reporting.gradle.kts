import groovy.json.JsonSlurper
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

val resultsDir = layout.projectDirectory.dir("results")
val frameworkE2EBaselineJson = resultsDir.file("framework-e2e-baseline.json")
val frameworkE2EJson = layout.buildDirectory.file("results/jmh/framework-e2e.json")
val readmeFile = layout.projectDirectory.file("README.md")

val benchmarkFrameworkE2EReport = layout.buildDirectory.file("results/jmh/framework-e2e.json")
val benchmarkInfrastructureE2EReport = layout.buildDirectory.file("results/jmh/infrastructure-e2e.json")
val benchmarkDiagnosticsReport = layout.buildDirectory.file("results/jmh/diagnostics.json")
val benchmarkInfrastructureDiagnosticsReport = layout.buildDirectory.file("results/jmh/infrastructure-diagnostics.json")

data class BenchmarkResultGroup(
    val name: String,
    val command: String,
    val resultFile: Provider<RegularFile>,
    val required: Boolean = true,
    val performanceConclusionSource: Boolean = false,
)

data class BenchmarkGroupReport(
    val group: BenchmarkResultGroup,
    val rows: List<ParsedBenchmarkResult>,
    val sourceRowCount: Int = rows.size,
    val unavailableReason: String? = null,
)

data class ParsedBenchmarkResult(
    val group: String,
    val benchmark: String,
    val displayName: String,
    val score: Double,
    val scoreError: Double?,
    val unit: String,
    val allocationBytesPerOp: Double?,
    val allocationErrorBytesPerOp: Double?,
)

fun shortBenchmarkName(benchmark: String): String {
    val parts = benchmark.split(".")
    return if (parts.size >= 2) {
        "${parts[parts.size - 2]}.${parts.last()}"
    } else {
        benchmark
    }
}

fun benchmarkDisplayName(result: Map<*, *>): String {
    val benchmark = result["benchmark"] as String
    @Suppress("UNCHECKED_CAST")
    val params = result["params"] as? Map<*, *>
    if (params.isNullOrEmpty()) {
        return shortBenchmarkName(benchmark)
    }
    val paramText = params.entries.sortedBy { it.key.toString() }
        .joinToString(", ") { "${it.key}=${it.value}" }
    return "${shortBenchmarkName(benchmark)} ($paramText)"
}

fun parseMetricNumber(value: Any?): Double? {
    val parsed = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    } ?: return null
    return parsed.takeIf { it.isFinite() }
}

fun benchmarkResultRowException(
    group: BenchmarkResultGroup,
    resultFile: java.io.File,
    rowIndex: Int,
    message: String,
): GradleException {
    return GradleException(
        "Invalid JMH result row for ${group.name} at index $rowIndex in ${resultFile.absolutePath}: $message"
    )
}

fun parseBenchmarkGroup(
    parser: JsonSlurper,
    group: BenchmarkResultGroup,
): BenchmarkGroupReport {
    val resultFile = group.resultFile.get().asFile
    if (!resultFile.exists()) {
        if (!group.required) {
            return BenchmarkGroupReport(
                group = group,
                rows = emptyList(),
                unavailableReason = "Status: unavailable. Result file was not present. Run ${group.command} to include this optional group.",
            )
        }
        throw GradleException(
            "JMH results not found for ${group.name}: ${resultFile.absolutePath}. Run ${group.command} first."
        )
    }
    val resultsText = resultFile.readText()
    if (resultsText.isBlank()) {
        throw GradleException("JMH results are empty for ${group.name}: ${resultFile.absolutePath}")
    }
    @Suppress("UNCHECKED_CAST")
    val results = parser.parseText(resultsText) as List<*>
    if (results.isEmpty()) {
        throw GradleException("JMH results contain no benchmarks for ${group.name}: ${resultFile.absolutePath}")
    }
    val rows = results.mapIndexed { rowIndex, rawResult ->
        val result = rawResult as? Map<*, *> ?: throw benchmarkResultRowException(
            group = group,
            resultFile = resultFile,
            rowIndex = rowIndex,
            message = "expected row to be a JSON object.",
        )
        val benchmark = result["benchmark"] as? String ?: throw benchmarkResultRowException(
            group = group,
            resultFile = resultFile,
            rowIndex = rowIndex,
            message = "missing benchmark.",
        )
        val primaryMetric = result["primaryMetric"] as? Map<*, *> ?: throw benchmarkResultRowException(
            group = group,
            resultFile = resultFile,
            rowIndex = rowIndex,
            message = "missing primaryMetric.",
        )
        val score = parseMetricNumber(primaryMetric["score"]) ?: throw benchmarkResultRowException(
            group = group,
            resultFile = resultFile,
            rowIndex = rowIndex,
            message = "missing or unusable primaryMetric.score.",
        )
        val scoreError = parseMetricNumber(primaryMetric["scoreError"])
        val unit = primaryMetric["scoreUnit"] as? String ?: "ops/s"
        val secondaryMetrics = result["secondaryMetrics"] as? Map<*, *>
        val allocationMetric = secondaryMetrics?.get("gc.alloc.rate.norm") as? Map<*, *>
        val allocationBytesPerOp = parseMetricNumber(allocationMetric?.get("score"))
        val allocationErrorBytesPerOp = parseMetricNumber(allocationMetric?.get("scoreError"))
        ParsedBenchmarkResult(
            group = group.name,
            benchmark = benchmark,
            displayName = benchmarkDisplayName(result),
            score = score,
            scoreError = scoreError,
            unit = unit,
            allocationBytesPerOp = allocationBytesPerOp,
            allocationErrorBytesPerOp = allocationErrorBytesPerOp,
        )
    }
    return BenchmarkGroupReport(group = group, rows = rows, sourceRowCount = results.size)
}

fun formatScoreError(scoreError: Double?): String {
    return scoreError?.let { "+/-${String.format(Locale.US, "%.2f", it)}" } ?: "-"
}

fun formatAllocationBytes(allocationBytesPerOp: Double?): String {
    return allocationBytesPerOp?.let { String.format(Locale.US, "%.1f B/op", it) } ?: "-"
}

fun formatAllocationError(allocationErrorBytesPerOp: Double?): String {
    return allocationErrorBytesPerOp?.let { "+/-${String.format(Locale.US, "%.1f B/op", it)}" } ?: "-"
}

fun StringBuilder.appendBenchmarkTable(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Benchmark | Score | Error | Unit | gc.alloc.rate.norm |")
    appendLine("|-----------|-------|-------|------|-------------------|")
    rows.sortedBy { it.displayName }.forEach { row ->
        appendLine(
            "| ${row.displayName} | ${String.format(Locale.US, "%.2f", row.score)} | " +
                "${formatScoreError(row.scoreError)} | ${row.unit} | ${formatAllocationBytes(row.allocationBytesPerOp)} |"
        )
    }
}

fun StringBuilder.appendThroughputBottlenecks(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Benchmark | Score | Error | Unit |")
    appendLine("|-----------|-------|-------|------|")
    rows.filter { it.unit.contains("ops", ignoreCase = true) }
        .sortedBy { it.score }
        .take(10)
        .forEach { row ->
            appendLine(
                "| ${row.group}: ${row.displayName} | ${String.format(Locale.US, "%.2f", row.score)} | " +
                    "${formatScoreError(row.scoreError)} | ${row.unit} |"
            )
        }
}

fun StringBuilder.appendAllocationBottlenecks(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Benchmark | Allocation | Error | Score | Unit |")
    appendLine("|-----------|------------|-------|-------|------|")
    rows.filter { it.allocationBytesPerOp != null }
        .sortedByDescending { it.allocationBytesPerOp }
        .take(10)
        .forEach { row ->
            appendLine(
                "| ${row.group}: ${row.displayName} | " +
                    "${formatAllocationBytes(row.allocationBytesPerOp)} | " +
                    "${formatAllocationError(row.allocationErrorBytesPerOp)} | " +
                    "${String.format(Locale.US, "%.2f", row.score)} | ${row.unit} |"
            )
        }
}

fun renderGroupedBenchmarkReport(
    groups: List<BenchmarkResultGroup>,
    version: String,
): String {
    val parser = JsonSlurper()
    val parsedGroups = groups.map { parseBenchmarkGroup(parser, it) }
    val allRows = parsedGroups.flatMap { it.rows }
    val conclusionRows = parsedGroups
        .filter { it.group.performanceConclusionSource }
        .flatMap { it.rows }
    val diagnosticRows = parsedGroups
        .filterNot { it.group.performanceConclusionSource }
        .flatMap { it.rows }
    if (allRows.isEmpty()) {
        throw GradleException("No benchmark rows were available for grouped report generation.")
    }
    val sb = StringBuilder()
    sb.appendLine("# E2E Benchmark Report")
    sb.appendLine()
    sb.appendLine("## Policy")
    sb.appendLine("- Framework performance conclusions must use Primary Framework E2E results only.")
    sb.appendLine("- Infrastructure E2E results are secondary because they include Redis or Mongo I/O.")
    sb.appendLine("- Diagnostic results explain bottlenecks, but they are not performance success criteria.")
    sb.appendLine()
    sb.appendLine("## Environment")
    sb.appendLine("- **Version**: $version")
    sb.appendLine("- **JVM**: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
    sb.appendLine("- **OS**: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    sb.appendLine("- **Date**: ${LocalDate.now()}")
    sb.appendLine("- **JMH Config**: threads=1, warmup=2x5s, measurement=3x10s, fork=2")
    sb.appendLine()
    if (conclusionRows.isNotEmpty()) {
        sb.appendLine("## Primary E2E Bottlenecks")
        sb.appendLine()
        sb.appendLine("### Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(conclusionRows)
        sb.appendLine()
        sb.appendLine("### Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(conclusionRows)
        sb.appendLine()
    }
    if (diagnosticRows.isNotEmpty()) {
        sb.appendLine("## Diagnostic Bottlenecks")
        sb.appendLine()
        sb.appendLine("### Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(diagnosticRows)
        sb.appendLine()
        sb.appendLine("### Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(diagnosticRows)
        sb.appendLine()
    }
    sb.appendLine("## Group Details")
    sb.appendLine()
    parsedGroups.filter { it.rows.isNotEmpty() }.forEach { groupReport ->
        sb.appendLine("### ${groupReport.group.name} Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(groupReport.rows)
        sb.appendLine()
        sb.appendLine("### ${groupReport.group.name} Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(groupReport.rows)
        sb.appendLine()
    }
    parsedGroups.forEach { groupReport ->
        val group = groupReport.group
        val rows = groupReport.rows
        val resultFile = group.resultFile.get().asFile
        sb.appendLine("## ${group.name} Results")
        sb.appendLine()
        sb.appendLine("- **Command**: `${group.command}`")
        sb.appendLine("- **Result File**: `${resultFile.absolutePath}`")
        sb.appendLine("- **Performance Conclusion Source**: ${if (group.performanceConclusionSource) "yes" else "no"}")
        if (resultFile.exists()) {
            sb.appendLine("- **Last Modified**: ${Instant.ofEpochMilli(resultFile.lastModified())}")
        }
        sb.appendLine("- **Source Row Count**: ${groupReport.sourceRowCount}")
        sb.appendLine("- **Parsed Row Count**: ${rows.size}")
        sb.appendLine()
        if (groupReport.unavailableReason != null) {
            sb.appendLine(groupReport.unavailableReason)
        } else {
            sb.appendBenchmarkTable(rows)
        }
        sb.appendLine()
    }
    return sb.toString()
}

tasks.register("generateBenchmarkReport") {
    description = "Generate benchmark README.md from primary framework E2E JMH JSON results."
    group = "benchmark"

    doLast {
        val resultsFile = frameworkE2EJson.get().asFile
        if (!resultsFile.exists()) {
            throw GradleException(
                "Framework E2E JMH results not found: ${resultsFile.absolutePath}. " +
                    "Run :wow-benchmarks:benchmarkFrameworkE2E first."
            )
        }

        val parser = JsonSlurper()
        val resultsText = resultsFile.readText()
        @Suppress("UNCHECKED_CAST")
        val jmhResults = parser.parseText(resultsText) as List<Map<String, Any>>

        val version = project.version.toString()
        val date = LocalDate.now().toString()
        val jvm = System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")
        val os = System.getProperty("os.name") + " " + System.getProperty("os.arch")

        val sb = StringBuilder()
        sb.appendLine("<!--")
        sb.appendLine("  This file is auto-generated by `./gradlew :wow-benchmarks:generateBenchmarkReport`.")
        sb.appendLine("  Do not manually edit benchmark results.")
        sb.appendLine("-->")
        sb.appendLine()
        sb.appendLine("# Framework E2E Benchmark Report")
        sb.appendLine()
        sb.appendLine("Framework performance conclusions must use this primary E2E report. " +
            "Component benchmarks are diagnostic only.")
        sb.appendLine()
        sb.appendLine("## Environment")
        sb.appendLine("- **Version**: $version")
        sb.appendLine("- **JVM**: $jvm")
        sb.appendLine("- **OS**: $os")
        sb.appendLine("- **Date**: $date")
        sb.appendLine("- **JMH Config**: threads=1, warmup=2×5s, measurement=3×10s, fork=2")
        sb.appendLine()
        sb.appendLine("## Results")
        sb.appendLine()
        sb.appendLine("| Benchmark | Score | Error | Unit | gc.alloc.rate.norm |")
        sb.appendLine("|-----------|-------|-------|------|-------------------|")

        for (result in jmhResults) {
            val benchmark = result["benchmark"] as? String ?: continue
            @Suppress("UNCHECKED_CAST")
            val primaryMetric = result["primaryMetric"] as? Map<String, Any> ?: continue
            val score = parseMetricNumber(primaryMetric["score"]) ?: continue
            val scoreError = parseMetricNumber(primaryMetric["scoreError"]) ?: 0.0
            val unit = primaryMetric["scoreUnit"] as? String ?: "ops/s"

            var allocRateNorm = "—"
            @Suppress("UNCHECKED_CAST")
            val secondaryMetrics = result["secondaryMetrics"] as? Map<String, Map<String, Any>>
            if (secondaryMetrics != null) {
                val gcAlloc = secondaryMetrics["gc.alloc.rate.norm"]
                allocRateNorm = String.format(Locale.US, "%.1f B/op", parseMetricNumber(gcAlloc?.get("score")) ?: 0.0)
            }

            val parts = benchmark.split(".")
            val shortName = if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else benchmark
            sb.appendLine(
                "| $shortName | ${String.format(Locale.US, "%.2f", score)} | " +
                    "±${String.format(Locale.US, "%.2f", scoreError)} | $unit | $allocRateNorm |"
            )
        }

        readmeFile.asFile.writeText(sb.toString())
        logger.lifecycle("Benchmark report generated: ${readmeFile.asFile.absolutePath}")
    }
}

val groupedBenchmarkReport = layout.buildDirectory.file("reports/jmh/grouped.md")

tasks.register("generateGroupedBenchmarkReport") {
    description = "Generate grouped E2E and diagnostic benchmark reports from JMH JSON results."
    group = "benchmark"
    outputs.file(groupedBenchmarkReport)
    outputs.upToDateWhen { false }
    doLast {
        val outputFile = groupedBenchmarkReport.get().asFile
        outputFile.delete()
        val report = renderGroupedBenchmarkReport(
            groups = listOf(
                BenchmarkResultGroup(
                    name = "Primary Framework E2E",
                    command = "./gradlew :wow-benchmarks:benchmarkFrameworkE2E",
                    resultFile = benchmarkFrameworkE2EReport,
                    performanceConclusionSource = true,
                ),
                BenchmarkResultGroup(
                    name = "Secondary Infrastructure E2E",
                    command = "./gradlew :wow-benchmarks:benchmarkInfrastructureE2E",
                    resultFile = benchmarkInfrastructureE2EReport,
                    required = false,
                ),
                BenchmarkResultGroup(
                    name = "Diagnostic Local Components",
                    command = "./gradlew :wow-benchmarks:benchmarkDiagnostics",
                    resultFile = benchmarkDiagnosticsReport,
                    required = false,
                ),
                BenchmarkResultGroup(
                    name = "Diagnostic Infrastructure Components",
                    command = "./gradlew :wow-benchmarks:benchmarkInfrastructureDiagnostics",
                    resultFile = benchmarkInfrastructureDiagnosticsReport,
                    required = false,
                ),
            ),
            version = project.version.toString(),
        )
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Grouped benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("benchmarkCompare") {
    description = "Compare primary framework E2E benchmark results against baseline."
    group = "benchmark"

    doLast {
        val localFile = frameworkE2EJson.get().asFile
        val baselineFile = frameworkE2EBaselineJson.asFile

        if (!baselineFile.exists()) {
            throw GradleException("Baseline not found: ${baselineFile.absolutePath}. Run :wow-benchmarks:updateBaseline first.")
        }
        if (!localFile.exists()) {
            throw GradleException(
                "Framework E2E benchmark results not found: ${localFile.absolutePath}. " +
                    "Run :wow-benchmarks:benchmarkFrameworkE2E first."
            )
        }

        val parser = JsonSlurper()

        fun parseScores(file: java.io.File): Map<String, Double> {
            @Suppress("UNCHECKED_CAST")
            val results = parser.parse(file) as List<Map<String, Any>>
            return results.mapIndexed { rowIndex, result ->
                val benchmark = result["benchmark"] as String
                @Suppress("UNCHECKED_CAST")
                val params = result["params"] as? Map<String, String>
                val key = if (params != null && params.isNotEmpty()) {
                    "$benchmark(${params.entries.joinToString(",") { "${it.key}=${it.value}" }})"
                } else {
                    benchmark
                }
                @Suppress("UNCHECKED_CAST")
                val primaryMetric = result["primaryMetric"] as Map<String, Any>
                val score = parseMetricNumber(primaryMetric["score"]) ?: throw GradleException(
                    "Invalid JMH score at index $rowIndex in ${file.absolutePath}: primaryMetric.score must be numeric."
                )
                key to score
            }.toMap()
        }

        val baseline = parseScores(baselineFile)
        val latest = parseScores(localFile)
        val allBenchmarks = (baseline.keys + latest.keys).sorted()

        var regressions = 0
        var improvements = 0

        println()
        println("## Benchmark Comparison")
        println()
        println("| Benchmark | Baseline | Current | Δ% | Status |")
        println("|-----------|----------|---------|----|--------|")

        for (benchmark in allBenchmarks) {
            val baseScore = baseline[benchmark]
            val latestScore = latest[benchmark]
            val parts = benchmark.split("(")[0].split(".")
            val shortName = if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else benchmark
            val paramSuffix = if ("(" in benchmark) " ${benchmark.substringAfter("(").substringBefore(")")}" else ""

            val displayName = "$shortName$paramSuffix"

            if (baseScore == null) {
                println("| $displayName | — | ${String.format(Locale.US, "%.2f", latestScore)} | NEW | 🆕 |")
                continue
            }
            if (latestScore == null) {
                println("| $displayName | ${String.format(Locale.US, "%.2f", baseScore)} | — | REMOVED | ⚠️ |")
                continue
            }

            val changePercent = ((latestScore - baseScore) / baseScore) * 100
            val status = when {
                changePercent < -10.0 -> {
                    regressions++
                    "🔴 REGRESSION"
                }
                changePercent > 10.0 -> {
                    improvements++
                    "🟢 IMPROVED"
                }
                else -> "✅"
            }

            println(
                "| $displayName | ${String.format(Locale.US, "%.2f", baseScore)} | " +
                    "${String.format(Locale.US, "%.2f", latestScore)} | " +
                    "${String.format(Locale.US, "%+.1f%%", changePercent)} | $status |"
            )
        }

        println()
        println("**Summary:** $regressions regression(s), $improvements improvement(s), ${allBenchmarks.size - regressions - improvements} stable")

        if (regressions > 0) {
            throw GradleException("Benchmark regressions detected: $regressions")
        }
    }
}

tasks.register("updateBaseline") {
    description = "Copy primary framework E2E benchmark results as the new baseline."
    group = "benchmark"

    doLast {
        val localFile = frameworkE2EJson.get().asFile
        val baselineFile = frameworkE2EBaselineJson.asFile

        if (!localFile.exists()) {
            throw GradleException(
                "Framework E2E benchmark results not found: ${localFile.absolutePath}. " +
                    "Run :wow-benchmarks:benchmarkFrameworkE2E first."
            )
        }

        localFile.copyTo(baselineFile, overwrite = true)
        logger.lifecycle("Baseline updated: ${baselineFile.absolutePath}")
    }
}
