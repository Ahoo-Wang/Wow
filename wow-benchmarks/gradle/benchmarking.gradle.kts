import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

data class BenchmarkRequiredService(
    val service: String,
    val port: Int,
)

data class BenchmarkSuite(
    val id: String,
    val displayName: String,
    val commandName: String,
    val includeClasses: List<String>,
    val resultFileName: String,
    val humanFileName: String,
    val requiredForGroupedReport: Boolean = false,
    val performanceConclusionSource: Boolean = false,
    val requiredServices: List<BenchmarkRequiredService> = emptyList(),
)

data class BenchmarkRunProfile(
    val id: String,
    val warmupIterations: Int,
    val warmupTime: String?,
    val measurementIterations: Int,
    val measurementTime: String,
    val forks: Int,
    val threads: List<Int>,
    val benchmarkModes: List<String>,
    val includeProfilers: Boolean,
)

fun benchmarkThreadsProperty(propertyName: String, defaultThreads: List<Int>): List<Int> {
    return providers.gradleProperty(propertyName)
        .map { value ->
            val threads = value.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { token ->
                    token.toIntOrNull()
                        ?: throw GradleException("Gradle property $propertyName contains non-integer thread value: $token")
                }
                .distinct()
            if (threads.isEmpty()) {
                throw GradleException("Gradle property $propertyName must contain at least one thread value.")
            }
            threads.forEach { thread ->
                if (thread <= 0) {
                    throw GradleException("Gradle property $propertyName must contain only positive thread values.")
                }
            }
            threads
        }
        .getOrElse(defaultThreads)
}

val smokeProfile = BenchmarkRunProfile(
    id = "smoke",
    warmupIterations = 0,
    warmupTime = null,
    measurementIterations = 1,
    measurementTime = "1s",
    forks = 1,
    threads = listOf(1),
    benchmarkModes = listOf("thrpt"),
    includeProfilers = false,
)

val quickProfile = BenchmarkRunProfile(
    id = "quick",
    warmupIterations = 1,
    warmupTime = "3s",
    measurementIterations = 2,
    measurementTime = "5s",
    forks = 1,
    threads = benchmarkThreadsProperty("benchmarkQuickThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt", "avgt"),
    includeProfilers = true,
)

val fullProfile = BenchmarkRunProfile(
    id = "full",
    warmupIterations = 3,
    warmupTime = "10s",
    measurementIterations = 5,
    measurementTime = "20s",
    forks = 3,
    threads = benchmarkThreadsProperty("benchmarkThreads", listOf(1, 2, 4, 8)),
    benchmarkModes = listOf("thrpt", "avgt"),
    includeProfilers = true,
)

val smokeSuite = BenchmarkSuite(
    id = "smoke",
    displayName = "Smoke",
    commandName = "benchmarkSmoke",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIdComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark",
        "me.ahoo.wow.benchmark.component.SerializationComponentBenchmark",
        "me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark",
    ),
    resultFileName = "benchmark-smoke.json",
    humanFileName = "benchmark-smoke-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = false,
)

val frameworkE2ESuite = BenchmarkSuite(
    id = "framework-e2e",
    displayName = "Primary Framework E2E",
    commandName = "benchmarkFullE2E",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark",
    ),
    resultFileName = "framework-e2e.json",
    humanFileName = "framework-e2e-human.txt",
    requiredForGroupedReport = true,
    performanceConclusionSource = true,
)

val infrastructureE2ESuite = BenchmarkSuite(
    id = "infrastructure-e2e",
    displayName = "Infrastructure E2E",
    commandName = "benchmarkFullInfrastructureE2E",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.redis.RedisCommandWriteE2EBenchmark",
        "me.ahoo.wow.benchmark.infrastructure.mongo.MongoCommandWriteE2EBenchmark",
    ),
    resultFileName = "infrastructure-e2e.json",
    humanFileName = "infrastructure-e2e-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = true,
    requiredServices = listOf(
        BenchmarkRequiredService("Redis", 6379),
        BenchmarkRequiredService("MongoDB", 27017),
    ),
)

val componentSuite = BenchmarkSuite(
    id = "component",
    displayName = "Component",
    commandName = "benchmarkFullComponent",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIdComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandValidationComponentBenchmark",
        "me.ahoo.wow.benchmark.component.IdempotencyComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AggregateLoadComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AggregateHandleComponentBenchmark",
        "me.ahoo.wow.benchmark.component.EventStoreComponentBenchmark",
        "me.ahoo.wow.benchmark.component.EventPublishComponentBenchmark",
        "me.ahoo.wow.benchmark.component.WaitNotifyComponentBenchmark",
        "me.ahoo.wow.benchmark.component.SerializationComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark",
    ),
    resultFileName = "component.json",
    humanFileName = "component-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = false,
)

val reportSuites = listOf(frameworkE2ESuite, infrastructureE2ESuite, componentSuite)

val benchmarkJvmArgs = listOf(
    "-Xmx4g",
    "-Xms4g",
    "-XX:+UseG1GC",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+DebugNonSafepoints",
    "-XX:+AlwaysPreTouch",
)

fun benchmarkIncludePattern(includes: List<String>): String {
    return includes.joinToString("|") { Regex.escape(it) + ".*" }
}

fun benchmarkProfilerArgs(includeProfilers: Boolean): List<String> {
    if (!includeProfilers) {
        return emptyList()
    }
    val asyncProfilerLib = file("/opt/async-profiler/lib/libasyncProfiler.dylib")
    return buildList {
        add("-prof")
        add("gc")
        add("-prof")
        if (asyncProfilerLib.exists()) {
            add("async:output=flamegraph;dir=build/profiling;event=cpu;libPath=${asyncProfilerLib.absolutePath}")
        } else {
            add("stack:lines=10;top=20")
        }
    }
}

fun requireBenchmarkService(service: String, port: Int) {
    val available = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("localhost", port), 2000)
        }
    }.isSuccess
    require(available) {
        "$service is required for Infrastructure I/O benchmarks at localhost:$port. " +
            "Start $service and rerun the selected infrastructure benchmark task."
    }
}

fun suiteResultFile(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): Provider<RegularFile> {
    return layout.buildDirectory.file(
        "results/jmh/${profile.id}/${suite.id}/threads-$threads-${suite.resultFileName}"
    )
}

fun suiteHumanFile(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): Provider<RegularFile> {
    return layout.buildDirectory.file(
        "reports/jmh/${profile.id}/${suite.id}/threads-$threads-${suite.humanFileName}"
    )
}

fun BenchmarkRunProfile.configSummary(): String {
    val warmup = if (warmupTime == null) {
        "warmup=$warmupIterations"
    } else {
        "warmup=${warmupIterations}x$warmupTime"
    }
    return "$warmup, measurement=${measurementIterations}x$measurementTime, " +
        "fork=$forks, threads=${threads.joinToString(",")}, modes=${benchmarkModes.joinToString(",")}"
}

fun registerBenchmarkThreadTask(
    taskName: String,
    suite: BenchmarkSuite,
    profile: BenchmarkRunProfile,
    threads: Int,
): TaskProvider<JavaExec> {
    val jmhJar = tasks.named<Jar>("jmhJar")
    val resultFile = suiteResultFile(profile, suite, threads)
    val humanFile = suiteHumanFile(profile, suite, threads)
    return tasks.register<JavaExec>(taskName) {
        description = "Runs ${suite.displayName} JMH benchmarks with ${profile.id} profile and $threads thread(s)."
        dependsOn(jmhJar)
        classpath(jmhJar.flatMap { it.archiveFile })
        mainClass.set("org.openjdk.jmh.Main")

        val jmhArgs = buildList {
            add(benchmarkIncludePattern(suite.includeClasses))
            add("-bm")
            add(profile.benchmarkModes.joinToString(","))
            add("-t")
            add(threads.toString())
            add("-wi")
            add(profile.warmupIterations.toString())
            profile.warmupTime?.let { warmupTime ->
                add("-w")
                add(warmupTime)
            }
            add("-i")
            add(profile.measurementIterations.toString())
            add("-r")
            add(profile.measurementTime)
            add("-f")
            add(profile.forks.toString())
            add("-foe")
            add("true")
            add("-rf")
            add("json")
            add("-rff")
            add(resultFile.get().asFile.absolutePath)
            add("-o")
            add(humanFile.get().asFile.absolutePath)
            add("-jvmArgs")
            add(benchmarkJvmArgs.joinToString(" "))
        }
        args(jmhArgs)
        args(benchmarkProfilerArgs(profile.includeProfilers))

        outputs.file(resultFile)
        outputs.file(humanFile)
        outputs.upToDateWhen { false }

        doFirst {
            resultFile.get().asFile.parentFile.mkdirs()
            humanFile.get().asFile.parentFile.mkdirs()
            suite.requiredServices.forEach { requiredService ->
                requireBenchmarkService(requiredService.service, requiredService.port)
            }
        }
    }
}

fun registerBenchmarkAggregateTask(
    taskName: String,
    suite: BenchmarkSuite,
    profile: BenchmarkRunProfile,
) {
    val threadTasks = profile.threads.map { threads ->
        registerBenchmarkThreadTask("${taskName}Thread$threads", suite, profile, threads)
    }
    tasks.register(taskName) {
        description = "Runs ${suite.displayName} JMH benchmarks with the ${profile.id} profile."
        group = if (taskName == "benchmarkSmoke") {
            "verification"
        } else {
            "benchmark"
        }
        dependsOn(threadTasks)
    }
}

registerBenchmarkAggregateTask("benchmarkSmoke", smokeSuite, smokeProfile)
registerBenchmarkAggregateTask("benchmarkQuickE2E", frameworkE2ESuite, quickProfile)
registerBenchmarkAggregateTask("benchmarkFullE2E", frameworkE2ESuite, fullProfile)
registerBenchmarkAggregateTask("benchmarkQuickInfrastructureE2E", infrastructureE2ESuite, quickProfile)
registerBenchmarkAggregateTask("benchmarkFullInfrastructureE2E", infrastructureE2ESuite, fullProfile)
registerBenchmarkAggregateTask("benchmarkQuickComponent", componentSuite, quickProfile)
registerBenchmarkAggregateTask("benchmarkFullComponent", componentSuite, fullProfile)

val resultsDir = layout.projectDirectory.dir("results")
val frameworkE2EBaselineJson = resultsDir.file("framework-e2e-baseline.json")
val readmeFile = layout.projectDirectory.file("README.md")
val groupedBenchmarkReport = layout.buildDirectory.file("reports/jmh/grouped.md")

data class BenchmarkResultFile(
    val threads: Int,
    val resultFile: Provider<RegularFile>,
)

data class BenchmarkResultGroup(
    val suite: BenchmarkSuite,
    val profile: BenchmarkRunProfile,
    val resultFiles: List<BenchmarkResultFile>,
)

data class BenchmarkGroupReport(
    val group: BenchmarkResultGroup,
    val rows: List<ParsedBenchmarkResult>,
    val sourceRowCount: Int = rows.size,
    val unavailableReason: String? = null,
)

data class ParsedBenchmarkResult(
    val group: String,
    val threads: Int,
    val benchmark: String,
    val displayName: String,
    val mode: String,
    val score: Double,
    val scoreError: Double?,
    val unit: String,
    val allocationBytesPerOp: Double?,
    val allocationErrorBytesPerOp: Double?,
)

fun benchmarkResultGroup(suite: BenchmarkSuite, profile: BenchmarkRunProfile = fullProfile): BenchmarkResultGroup {
    return BenchmarkResultGroup(
        suite = suite,
        profile = profile,
        resultFiles = profile.threads.map { threads ->
            BenchmarkResultFile(
                threads = threads,
                resultFile = suiteResultFile(profile, suite, threads),
            )
        },
    )
}

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
    resultFile: File,
    rowIndex: Int,
    message: String,
): GradleException {
    return GradleException(
        "Invalid JMH result row for ${group.suite.displayName} at index $rowIndex in " +
            "${resultFile.absolutePath}: $message"
    )
}

fun parseBenchmarkResultFile(
    parser: JsonSlurper,
    group: BenchmarkResultGroup,
    resultFile: File,
    threads: Int,
): List<ParsedBenchmarkResult> {
    val resultsText = resultFile.readText()
    if (resultsText.isBlank()) {
        throw GradleException("JMH results are empty for ${group.suite.displayName}: ${resultFile.absolutePath}")
    }
    @Suppress("UNCHECKED_CAST")
    val results = parser.parseText(resultsText) as List<*>
    if (results.isEmpty()) {
        throw GradleException("JMH results contain no benchmarks for ${group.suite.displayName}: ${resultFile.absolutePath}")
    }
    return results.mapIndexed { rowIndex, rawResult ->
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
        val unit = primaryMetric["scoreUnit"] as? String ?: throw benchmarkResultRowException(
            group = group,
            resultFile = resultFile,
            rowIndex = rowIndex,
            message = "missing primaryMetric.scoreUnit.",
        )
        val secondaryMetrics = result["secondaryMetrics"] as? Map<*, *>
        val allocationMetric = secondaryMetrics?.get("gc.alloc.rate.norm") as? Map<*, *>
        val allocationBytesPerOp = parseMetricNumber(allocationMetric?.get("score"))
        val allocationErrorBytesPerOp = parseMetricNumber(allocationMetric?.get("scoreError"))
        ParsedBenchmarkResult(
            group = group.suite.displayName,
            threads = threads,
            benchmark = benchmark,
            displayName = benchmarkDisplayName(result),
            mode = result["mode"] as? String ?: "unknown",
            score = score,
            scoreError = scoreError,
            unit = unit,
            allocationBytesPerOp = allocationBytesPerOp,
            allocationErrorBytesPerOp = allocationErrorBytesPerOp,
        )
    }
}

fun parseBenchmarkGroup(
    parser: JsonSlurper,
    group: BenchmarkResultGroup,
): BenchmarkGroupReport {
    val presentFiles = group.resultFiles
        .map { it.threads to it.resultFile.get().asFile }
        .filter { (_, resultFile) -> resultFile.exists() }
    if (presentFiles.isEmpty()) {
        if (!group.suite.requiredForGroupedReport) {
            return BenchmarkGroupReport(
                group = group,
                rows = emptyList(),
                unavailableReason = "Status: unavailable. Result files were not present. " +
                    "Run ${group.suite.commandName} to include this optional group.",
            )
        }
        val missingFiles = group.resultFiles.joinToString(", ") { it.resultFile.get().asFile.absolutePath }
        throw GradleException(
            "JMH results not found for ${group.suite.displayName}: $missingFiles. " +
                "Run ${group.suite.commandName} first."
        )
    }
    val missingRequiredFile = group.resultFiles
        .map { it.resultFile.get().asFile }
        .firstOrNull { !it.exists() }
    if (group.suite.requiredForGroupedReport && missingRequiredFile != null) {
        throw GradleException(
            "JMH result file not found for ${group.suite.displayName}: ${missingRequiredFile.absolutePath}. " +
                "Run ${group.suite.commandName} first."
        )
    }
    val rows = presentFiles.flatMap { (threads, resultFile) ->
        parseBenchmarkResultFile(parser, group, resultFile, threads)
    }
    return BenchmarkGroupReport(group = group, rows = rows, sourceRowCount = rows.size)
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
    appendLine("| Threads | Mode | Benchmark | Score | Error | Unit | gc.alloc.rate.norm |")
    appendLine("|---------|------|-----------|-------|-------|------|-------------------|")
    rows.sortedWith(compareBy<ParsedBenchmarkResult> { it.threads }.thenBy { it.displayName }.thenBy { it.mode })
        .forEach { row ->
            appendLine(
                "| ${row.threads} | ${row.mode} | ${row.displayName} | " +
                    "${String.format(Locale.US, "%.2f", row.score)} | ${formatScoreError(row.scoreError)} | " +
                    "${row.unit} | ${formatAllocationBytes(row.allocationBytesPerOp)} |"
            )
        }
}

fun StringBuilder.appendThroughputBottlenecks(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Group | Threads | Benchmark | Score | Error | Unit |")
    appendLine("|-------|---------|-----------|-------|-------|------|")
    rows.filter { it.unit.contains("ops", ignoreCase = true) }
        .sortedBy { it.score }
        .take(10)
        .forEach { row ->
            appendLine(
                "| ${row.group} | ${row.threads} | ${row.displayName} | " +
                    "${String.format(Locale.US, "%.2f", row.score)} | ${formatScoreError(row.scoreError)} | ${row.unit} |"
            )
        }
}

fun StringBuilder.appendAllocationBottlenecks(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Group | Threads | Benchmark | Allocation | Error | Score | Unit |")
    appendLine("|-------|---------|-----------|------------|-------|-------|------|")
    rows.filter { it.allocationBytesPerOp != null }
        .sortedByDescending { it.allocationBytesPerOp }
        .take(10)
        .forEach { row ->
            appendLine(
                "| ${row.group} | ${row.threads} | ${row.displayName} | " +
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
        .filter { it.group.suite.performanceConclusionSource }
        .flatMap { it.rows }
    val componentRows = parsedGroups
        .filterNot { it.group.suite.performanceConclusionSource }
        .flatMap { it.rows }
    if (allRows.isEmpty()) {
        throw GradleException("No benchmark rows were available for grouped report generation.")
    }
    val sb = StringBuilder()
    sb.appendLine("# E2E Benchmark Report")
    sb.appendLine()
    sb.appendLine("## Policy")
    sb.appendLine("- Primary Framework E2E results are the main framework performance conclusion source.")
    sb.appendLine("- Infrastructure E2E results are performance conclusion sources for Redis and Mongo environments.")
    sb.appendLine("- Component results explain bottlenecks; they are not standalone framework performance conclusions.")
    sb.appendLine("- Smoke results are excluded from performance reports.")
    sb.appendLine()
    sb.appendLine("## Environment")
    sb.appendLine("- **Version**: $version")
    sb.appendLine("- **JVM**: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
    sb.appendLine("- **OS**: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    sb.appendLine("- **Date**: ${LocalDate.now()}")
    sb.appendLine("- **JMH Config**: ${fullProfile.configSummary()}")
    sb.appendLine()
    if (conclusionRows.isNotEmpty()) {
        sb.appendLine("## E2E Bottlenecks")
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
    if (componentRows.isNotEmpty()) {
        sb.appendLine("## Component Bottlenecks")
        sb.appendLine()
        sb.appendLine("### Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(componentRows)
        sb.appendLine()
        sb.appendLine("### Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(componentRows)
        sb.appendLine()
    }
    sb.appendLine("## Group Details")
    sb.appendLine()
    parsedGroups.filter { it.rows.isNotEmpty() }.forEach { groupReport ->
        sb.appendLine("### ${groupReport.group.suite.displayName} Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(groupReport.rows)
        sb.appendLine()
        sb.appendLine("### ${groupReport.group.suite.displayName} Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(groupReport.rows)
        sb.appendLine()
    }
    parsedGroups.forEach { groupReport ->
        val group = groupReport.group
        val rows = groupReport.rows
        sb.appendLine("## ${group.suite.displayName} Results")
        sb.appendLine()
        sb.appendLine("- **Command**: `./gradlew :wow-benchmarks:${group.suite.commandName}`")
        sb.appendLine("- **Performance Conclusion Source**: ${if (group.suite.performanceConclusionSource) "yes" else "no"}")
        sb.appendLine("- **Source Row Count**: ${groupReport.sourceRowCount}")
        sb.appendLine("- **Parsed Row Count**: ${rows.size}")
        sb.appendLine()
        group.resultFiles.forEach { resultFile ->
            val file = resultFile.resultFile.get().asFile
            sb.appendLine("- **threads=${resultFile.threads} Result File**: `${file.absolutePath}`")
            if (file.exists()) {
                sb.appendLine("  - Last Modified: ${Instant.ofEpochMilli(file.lastModified())}")
            }
        }
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
        val group = benchmarkResultGroup(frameworkE2ESuite, fullProfile)
        val groupReport = parseBenchmarkGroup(JsonSlurper(), group)
        val rows = groupReport.rows

        val sb = StringBuilder()
        sb.appendLine("<!--")
        sb.appendLine("  This file is auto-generated by `./gradlew :wow-benchmarks:generateBenchmarkReport`.")
        sb.appendLine("  Do not manually edit benchmark results.")
        sb.appendLine("-->")
        sb.appendLine()
        sb.appendLine("# Framework E2E Benchmark Report")
        sb.appendLine()
        sb.appendLine("Framework performance conclusions must use this primary E2E report.")
        sb.appendLine()
        sb.appendLine("## Environment")
        sb.appendLine("- **Version**: ${project.version}")
        sb.appendLine("- **JVM**: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        sb.appendLine("- **OS**: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        sb.appendLine("- **Date**: ${LocalDate.now()}")
        sb.appendLine("- **JMH Config**: ${fullProfile.configSummary()}")
        sb.appendLine()
        sb.appendLine("## Results")
        sb.appendLine()
        sb.appendBenchmarkTable(rows)

        readmeFile.asFile.writeText(sb.toString())
        logger.lifecycle("Benchmark report generated: ${readmeFile.asFile.absolutePath}")
    }
}

tasks.register("generateGroupedBenchmarkReport") {
    description = "Generate grouped E2E and component benchmark reports from JMH JSON results."
    group = "benchmark"
    outputs.file(groupedBenchmarkReport)
    outputs.upToDateWhen { false }
    doLast {
        val outputFile = groupedBenchmarkReport.get().asFile
        outputFile.delete()
        val report = renderGroupedBenchmarkReport(
            groups = reportSuites.map { benchmarkResultGroup(it, fullProfile) },
            version = project.version.toString(),
        )
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Grouped benchmark report generated: ${outputFile.absolutePath}")
    }
}

fun parseJsonResultRows(resultFile: File): List<Map<*, *>> {
    val resultsText = resultFile.readText()
    if (resultsText.isBlank()) {
        throw GradleException("JMH results are empty: ${resultFile.absolutePath}")
    }
    @Suppress("UNCHECKED_CAST")
    val results = JsonSlurper().parseText(resultsText) as List<*>
    if (results.isEmpty()) {
        throw GradleException("JMH results contain no benchmarks: ${resultFile.absolutePath}")
    }
    return results.mapIndexed { rowIndex, rawResult ->
        rawResult as? Map<*, *> ?: throw GradleException(
            "Invalid JMH result row at index $rowIndex in ${resultFile.absolutePath}: expected row to be a JSON object."
        )
    }
}

fun fullFrameworkE2EResultFiles(): List<File> {
    return fullProfile.threads.map { threads ->
        suiteResultFile(fullProfile, frameworkE2ESuite, threads).get().asFile
    }
}

fun requireFullFrameworkE2EResultFiles(): List<File> {
    val resultFiles = fullFrameworkE2EResultFiles()
    val missingFile = resultFiles.firstOrNull { !it.exists() }
    if (missingFile != null) {
        throw GradleException(
            "Framework E2E benchmark results not found: ${missingFile.absolutePath}. " +
                "Run :wow-benchmarks:${frameworkE2ESuite.commandName} first."
        )
    }
    return resultFiles
}

data class BenchmarkComparisonRow(
    val key: String,
    val displayName: String,
    val mode: String,
    val unit: String,
    val score: Double,
)

fun comparisonKey(result: Map<*, *>): String {
    val benchmark = result["benchmark"] as? String ?: "unknown"
    val mode = result["mode"] as? String ?: "unknown"
    val threads = result["threads"]?.toString() ?: "unknown"
    val params = result["params"] as? Map<*, *>
    val paramText = params?.entries
        ?.sortedBy { it.key.toString() }
        ?.joinToString(",") { "${it.key}=${it.value}" }
        ?: ""
    return "$benchmark|mode=$mode|threads=$threads|$paramText"
}

fun parseComparisonRows(resultFiles: List<File>): Map<String, BenchmarkComparisonRow> {
    return resultFiles.flatMap { parseJsonResultRows(it) }
        .mapIndexed { rowIndex, result ->
            val benchmark = result["benchmark"] as? String ?: throw GradleException(
                "Invalid JMH result row at index $rowIndex: missing benchmark."
            )
            val primaryMetric = result["primaryMetric"] as? Map<*, *> ?: throw GradleException(
                "Invalid JMH result row for $benchmark at index $rowIndex: missing primaryMetric."
            )
            val score = parseMetricNumber(primaryMetric["score"]) ?: throw GradleException(
                "Invalid JMH result row for $benchmark at index $rowIndex: missing or unusable primaryMetric.score."
            )
            val unit = primaryMetric["scoreUnit"] as? String ?: throw GradleException(
                "Invalid JMH result row for $benchmark at index $rowIndex: missing primaryMetric.scoreUnit."
            )
            val mode = result["mode"] as? String ?: "unknown"
            val threads = result["threads"]?.toString() ?: "unknown"
            val displayName = "${benchmarkDisplayName(result)} [mode=$mode, threads=$threads]"
            BenchmarkComparisonRow(
                key = comparisonKey(result),
                displayName = displayName,
                mode = mode,
                unit = unit,
                score = score,
            )
        }
        .associateBy { it.key }
}

tasks.register("benchmarkCompare") {
    description = "Compare primary framework E2E benchmark results against baseline."
    group = "benchmark"

    doLast {
        val baselineFile = frameworkE2EBaselineJson.asFile
        if (!baselineFile.exists()) {
            throw GradleException("Baseline not found: ${baselineFile.absolutePath}. Run :wow-benchmarks:updateBaseline first.")
        }
        val latestFiles = requireFullFrameworkE2EResultFiles()
        val baseline = parseComparisonRows(listOf(baselineFile))
        val latest = parseComparisonRows(latestFiles)
        val allBenchmarks = (baseline.keys + latest.keys).sorted()

        var regressions = 0
        var improvements = 0

        println()
        println("## Benchmark Comparison")
        println()
        println("| Benchmark | Baseline | Current | Delta % | Status |")
        println("|-----------|----------|---------|---------|--------|")

        for (benchmark in allBenchmarks) {
            val baseRow = baseline[benchmark]
            val latestRow = latest[benchmark]

            if (baseRow == null) {
                println("| ${latestRow?.displayName ?: benchmark} | - | ${String.format(Locale.US, "%.2f", latestRow?.score)} | NEW | NEW |")
                continue
            }
            if (latestRow == null) {
                println("| ${baseRow.displayName} | ${String.format(Locale.US, "%.2f", baseRow.score)} | - | REMOVED | REMOVED |")
                continue
            }

            val changePercent = ((latestRow.score - baseRow.score) / baseRow.score) * 100
            val higherIsBetter = latestRow.mode == "thrpt" || latestRow.unit.contains("ops", ignoreCase = true)
            val status = when {
                higherIsBetter && changePercent < -10.0 -> {
                    regressions++
                    "REGRESSION"
                }
                !higherIsBetter && changePercent > 10.0 -> {
                    regressions++
                    "REGRESSION"
                }
                higherIsBetter && changePercent > 10.0 -> {
                    improvements++
                    "IMPROVED"
                }
                !higherIsBetter && changePercent < -10.0 -> {
                    improvements++
                    "IMPROVED"
                }
                else -> "STABLE"
            }

            println(
                "| ${latestRow.displayName} | ${String.format(Locale.US, "%.2f", baseRow.score)} | " +
                    "${String.format(Locale.US, "%.2f", latestRow.score)} | " +
                    "${String.format(Locale.US, "%+.1f%%", changePercent)} | $status |"
            )
        }

        println()
        println("Summary: $regressions regression(s), $improvements improvement(s), ${allBenchmarks.size - regressions - improvements} stable")

        if (regressions > 0) {
            throw GradleException("Benchmark regressions detected: $regressions")
        }
    }
}

tasks.register("updateBaseline") {
    description = "Copy primary framework E2E benchmark results as the new baseline."
    group = "benchmark"

    doLast {
        val localRows = requireFullFrameworkE2EResultFiles().flatMap { parseJsonResultRows(it) }
        val baselineFile = frameworkE2EBaselineJson.asFile
        baselineFile.parentFile.mkdirs()
        baselineFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(localRows)))
        logger.lifecycle("Baseline updated: ${baselineFile.absolutePath}")
    }
}
