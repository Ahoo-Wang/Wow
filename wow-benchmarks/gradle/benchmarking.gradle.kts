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
    performanceConclusionSource = false,
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

fun BenchmarkRunProfile.reportLabel(): String {
    return when (id) {
        smokeProfile.id -> "Smoke"
        quickProfile.id -> "Quick"
        fullProfile.id -> "Full"
        else -> id.replaceFirstChar { firstChar ->
            if (firstChar.isLowerCase()) firstChar.titlecase(Locale.US) else firstChar.toString()
        }
    }
}

fun BenchmarkSuite.taskName(profile: BenchmarkRunProfile): String {
    if (profile.id == smokeProfile.id) {
        return commandName
    }
    val profileLabel = profile.reportLabel()
    return when (id) {
        frameworkE2ESuite.id -> "benchmark${profileLabel}E2E"
        infrastructureE2ESuite.id -> "benchmark${profileLabel}InfrastructureE2E"
        componentSuite.id -> "benchmark${profileLabel}Component"
        else -> commandName
    }
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

tasks.named("jmh") {
    enabled = false
    group = null
    description = "Disabled. Use the layered benchmark tasks instead."
}

val resultsDir = layout.projectDirectory.dir("results")
val frameworkE2EBaselineJson = resultsDir.file("framework-e2e-baseline.json")
val readmeFile = layout.projectDirectory.file("README.md")
val groupedBenchmarkReport = layout.buildDirectory.file("reports/jmh/grouped.md")
val quickGroupedBenchmarkReport = layout.buildDirectory.file("reports/jmh/quick-grouped.md")

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
    val suite: BenchmarkSuite,
    val profile: String,
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

fun benchmarkDisplayName(result: Map<*, *>, benchmark: String = result["benchmark"] as String): String {
    @Suppress("UNCHECKED_CAST")
    val params = result["params"] as? Map<*, *>
    if (params.isNullOrEmpty()) {
        return shortBenchmarkName(benchmark)
    }
    val paramText = params.entries.sortedBy { it.key.toString() }
        .joinToString(", ") { "${it.key}=${it.value}" }
    return "${shortBenchmarkName(benchmark)} ($paramText)"
}

fun benchmarkIdentity(result: Map<*, *>, benchmark: String = result["benchmark"] as String): String {
    @Suppress("UNCHECKED_CAST")
    val params = result["params"] as? Map<*, *>
    if (params.isNullOrEmpty()) {
        return benchmark
    }
    val paramText = params.entries.sortedBy { it.key.toString() }
        .joinToString(", ") { "${it.key}=${it.value}" }
    return "$benchmark ($paramText)"
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
            suite = group.suite,
            profile = group.profile.id,
            threads = threads,
            benchmark = benchmarkIdentity(result, benchmark),
            displayName = benchmarkDisplayName(result, benchmark),
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
                    "Run ${group.suite.taskName(group.profile)} to include this optional group.",
            )
        }
        val missingFiles = group.resultFiles.joinToString(", ") { it.resultFile.get().asFile.absolutePath }
        throw GradleException(
            "JMH results not found for ${group.suite.displayName}: $missingFiles. " +
                "Run ${group.suite.taskName(group.profile)} first."
        )
    }
    val missingRequiredFile = group.resultFiles
        .map { it.resultFile.get().asFile }
        .firstOrNull { !it.exists() }
    if (group.suite.requiredForGroupedReport && missingRequiredFile != null) {
        throw GradleException(
            "JMH result file not found for ${group.suite.displayName}: ${missingRequiredFile.absolutePath}. " +
                "Run ${group.suite.taskName(group.profile)} first."
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
    appendLine("| Suite | Benchmark | Threads | Mode | Score | Error | Unit | gc.alloc.rate.norm |")
    appendLine("|-------|-----------|---------|------|-------|-------|------|-------------------|")
    rows.sortedWith(
        compareBy<ParsedBenchmarkResult> { it.suite.displayName }
            .thenBy { it.displayName }
            .thenBy { it.threads }
            .thenBy { it.mode }
    )
        .forEach { row ->
            appendLine(
                "| ${row.suite.displayName} | ${row.displayName} | ${row.threads} | ${row.mode} | " +
                    "${String.format(Locale.US, "%.2f", row.score)} | ${formatScoreError(row.scoreError)} | " +
                    "${row.unit} | ${formatAllocationBytes(row.allocationBytesPerOp)} |"
            )
        }
}

fun StringBuilder.appendThroughputBottlenecks(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Suite | Threads | Benchmark | Score | Error | Unit |")
    appendLine("|-------|---------|-----------|-------|-------|------|")
    rows.filter { it.unit.contains("ops", ignoreCase = true) }
        .sortedBy { it.score }
        .take(10)
        .forEach { row ->
            appendLine(
                "| ${row.suite.displayName} | ${row.threads} | ${row.displayName} | " +
                    "${String.format(Locale.US, "%.2f", row.score)} | ${formatScoreError(row.scoreError)} | ${row.unit} |"
            )
        }
}

fun allocationBottleneckRows(rows: List<ParsedBenchmarkResult>): List<ParsedBenchmarkResult> {
    return rows.filter { it.allocationBytesPerOp != null }
        .groupBy { row -> "${row.suite.id}|${row.displayName}|${row.threads}" }
        .map { (_, duplicateRows) ->
            val preferredRows = duplicateRows
                .filter { it.unit.contains("ops", ignoreCase = true) }
                .ifEmpty { duplicateRows }
            preferredRows.maxBy { it.allocationBytesPerOp ?: Double.NEGATIVE_INFINITY }
        }
        .sortedByDescending { it.allocationBytesPerOp ?: Double.NEGATIVE_INFINITY }
        .take(10)
}

fun StringBuilder.appendAllocationBottlenecks(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Suite | Threads | Benchmark | Mode | Allocation | Error | Score | Unit |")
    appendLine("|-------|---------|-----------|------|------------|-------|-------|------|")
    allocationBottleneckRows(rows)
        .forEach { row ->
            appendLine(
                "| ${row.suite.displayName} | ${row.threads} | ${row.displayName} | ${row.mode} | " +
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
    val reportProfiles = groups.map { it.profile }.distinctBy { it.id }
    if (reportProfiles.size != 1) {
        throw GradleException("Grouped benchmark report requires one run profile, found: ${reportProfiles.map { it.id }}")
    }
    val reportProfile = reportProfiles.single()
    val reportLabel = reportProfile.reportLabel()
    val parsedGroups = groups.map { parseBenchmarkGroup(parser, it) }
    val allRows = parsedGroups.flatMap { it.rows }
    val frameworkRows = parsedGroups
        .filter { it.group.suite.id == frameworkE2ESuite.id }
        .flatMap { it.rows }
    val componentRows = parsedGroups
        .filter { it.group.suite.id == componentSuite.id }
        .flatMap { it.rows }
    val infrastructureRows = parsedGroups
        .filter { it.group.suite.id == infrastructureE2ESuite.id }
        .flatMap { it.rows }
    if (allRows.isEmpty()) {
        throw GradleException("No benchmark rows were available for grouped report generation.")
    }
    val sb = StringBuilder()
    sb.appendLine("# $reportLabel Grouped Benchmark Report")
    sb.appendLine()
    sb.appendLine("## Policy")
    if (reportProfile.id == fullProfile.id) {
        sb.appendLine("- Full E2E results are the performance conclusion source.")
    } else {
        sb.appendLine(
            "- $reportLabel results are directional feedback; run Full E2E before updating baselines " +
                "or claiming framework performance conclusions."
        )
    }
    sb.appendLine("- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.")
    sb.appendLine("- Component results explain bottlenecks and are not standalone performance goals.")
    sb.appendLine("- Smoke results are excluded from performance reports.")
    sb.appendLine()
    sb.appendLine("## Environment")
    sb.appendLine("- **Version**: $version")
    sb.appendLine("- **JVM**: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
    sb.appendLine("- **OS**: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    sb.appendLine("- **Date**: ${LocalDate.now()}")
    sb.appendLine("- **JMH Config**: ${reportProfile.configSummary()}")
    sb.appendLine()
    if (frameworkRows.isNotEmpty()) {
        sb.appendLine("## Framework E2E Bottlenecks")
        sb.appendLine()
        sb.appendLine("### Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(frameworkRows)
        sb.appendLine()
        sb.appendLine("### Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(frameworkRows)
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
    if (infrastructureRows.isNotEmpty()) {
        sb.appendLine("## Infrastructure E2E Bottlenecks")
        sb.appendLine()
        sb.appendLine("### Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(infrastructureRows)
        sb.appendLine()
        sb.appendLine("### Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(infrastructureRows)
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
        sb.appendLine("- **Command**: `./gradlew :wow-benchmarks:${group.suite.taskName(group.profile)}`")
        val performanceConclusionSource = group.profile.id == fullProfile.id && group.suite.performanceConclusionSource
        sb.appendLine("- **Performance Conclusion Source**: ${if (performanceConclusionSource) "yes" else "no"}")
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
        sb.appendLine(
            "Framework performance conclusions use Full E2E results. " +
                "Component benchmarks explain bottlenecks and Smoke only verifies benchmark entry health."
        )
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
    description = "Generate full grouped E2E and component benchmark reports from JMH JSON results."
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

tasks.register("generateQuickBenchmarkReport") {
    description = "Generate quick grouped E2E and component benchmark report from quick JMH JSON results."
    group = "benchmark"
    outputs.file(quickGroupedBenchmarkReport)
    outputs.upToDateWhen { false }
    doLast {
        val outputFile = quickGroupedBenchmarkReport.get().asFile
        outputFile.delete()
        val report = renderGroupedBenchmarkReport(
            groups = reportSuites.map { benchmarkResultGroup(it, quickProfile) },
            version = project.version.toString(),
        )
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Quick benchmark report generated: ${outputFile.absolutePath}")
    }
}

data class BenchmarkComparisonRow(
    val key: String,
    val benchmark: String,
    val displayName: String,
    val mode: String,
    val threads: Int,
    val unit: String,
    val score: Double,
    val allocationBytesPerOp: Double?,
)

data class BenchmarkMetricComparison(
    val key: String,
    val metric: String,
    val displayName: String,
    val mode: String,
    val threads: Int,
    val baseline: Double,
    val current: Double,
    val deltaPercent: Double?,
    val thresholdPercent: Double,
    val higherIsBetter: Boolean,
)

fun benchmarkRegressionThreshold(propertyName: String, defaultValue: Double): Double {
    return providers.gradleProperty(propertyName)
        .map { value ->
            val threshold = value.toDoubleOrNull()
                ?: throw GradleException("Gradle property $propertyName must be a number: $value")
            if (threshold < 0.0) {
                throw GradleException("Gradle property $propertyName must be greater than or equal to zero.")
            }
            threshold
        }
        .getOrElse(defaultValue)
}

val benchmarkThroughputRegressionPercent =
    benchmarkRegressionThreshold("benchmarkThroughputRegressionPercent", 10.0)
val benchmarkAllocationRegressionPercent =
    benchmarkRegressionThreshold("benchmarkAllocationRegressionPercent", 10.0)
val benchmarkLatencyRegressionPercent =
    benchmarkRegressionThreshold("benchmarkLatencyRegressionPercent", 10.0)

fun comparisonKey(benchmark: String, mode: String, threads: Int): String {
    return "$benchmark|mode=$mode|threads=$threads"
}

fun ParsedBenchmarkResult.toComparisonRow(): BenchmarkComparisonRow {
    return BenchmarkComparisonRow(
        key = comparisonKey(benchmark, mode, threads),
        benchmark = benchmark,
        displayName = displayName,
        mode = mode,
        threads = threads,
        unit = unit,
        score = score,
        allocationBytesPerOp = allocationBytesPerOp,
    )
}

fun parsePositiveInt(value: Any?): Int? {
    val parsed = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    } ?: return null
    return parsed.takeIf { it > 0 }
}

fun parsedFrameworkE2EResults(): List<ParsedBenchmarkResult> {
    return parseBenchmarkGroup(
        parser = JsonSlurper(),
        group = benchmarkResultGroup(frameworkE2ESuite, fullProfile),
    ).rows
}

fun ParsedBenchmarkResult.toBaselineJsonRow(): Map<String, Any?> {
    return linkedMapOf(
        "suite" to suite.id,
        "suiteDisplayName" to suite.displayName,
        "profile" to profile,
        "threads" to threads,
        "benchmark" to benchmark,
        "displayName" to displayName,
        "mode" to mode,
        "score" to score,
        "scoreError" to scoreError,
        "unit" to unit,
        "allocationBytesPerOp" to allocationBytesPerOp,
        "allocationErrorBytesPerOp" to allocationErrorBytesPerOp,
    )
}

fun parsedResultBaselineRow(row: Map<*, *>, source: String, rowIndex: Int): BenchmarkComparisonRow {
    val benchmark = row["benchmark"] as? String ?: throw GradleException(
        "Invalid benchmark baseline row at index $rowIndex in $source: missing benchmark."
    )
    val mode = row["mode"] as? String ?: throw GradleException(
        "Invalid benchmark baseline row for $benchmark at index $rowIndex in $source: missing mode."
    )
    val threads = parsePositiveInt(row["threads"]) ?: throw GradleException(
        "Invalid benchmark baseline row for $benchmark at index $rowIndex in $source: missing positive threads. " +
            "Regenerate the baseline with :wow-benchmarks:updateBenchmarkBaseline."
    )
    val score = parseMetricNumber(row["score"]) ?: throw GradleException(
        "Invalid benchmark baseline row for $benchmark at index $rowIndex in $source: missing score."
    )
    val unit = row["unit"] as? String ?: throw GradleException(
        "Invalid benchmark baseline row for $benchmark at index $rowIndex in $source: missing unit."
    )
    val displayName = row["displayName"] as? String ?: shortBenchmarkName(benchmark)
    return BenchmarkComparisonRow(
        key = comparisonKey(benchmark, mode, threads),
        benchmark = benchmark,
        displayName = displayName,
        mode = mode,
        threads = threads,
        unit = unit,
        score = score,
        allocationBytesPerOp = parseMetricNumber(row["allocationBytesPerOp"]),
    )
}

fun legacyJmhBaselineRow(row: Map<*, *>, source: String, rowIndex: Int): BenchmarkComparisonRow {
    val benchmark = row["benchmark"] as? String ?: throw GradleException(
        "Invalid legacy JMH baseline row at index $rowIndex in $source: missing benchmark."
    )
    val threads = parsePositiveInt(row["threads"]) ?: throw GradleException(
        "Legacy JMH baseline row for $benchmark at index $rowIndex in $source does not contain a usable threads value. " +
            "Regenerate the baseline with :wow-benchmarks:updateBenchmarkBaseline."
    )
    val primaryMetric = row["primaryMetric"] as? Map<*, *> ?: throw GradleException(
        "Invalid legacy JMH baseline row for $benchmark at index $rowIndex in $source: missing primaryMetric."
    )
    val mode = row["mode"] as? String ?: "unknown"
    val score = parseMetricNumber(primaryMetric["score"]) ?: throw GradleException(
        "Invalid legacy JMH baseline row for $benchmark at index $rowIndex in $source: missing primaryMetric.score."
    )
    val unit = primaryMetric["scoreUnit"] as? String ?: throw GradleException(
        "Invalid legacy JMH baseline row for $benchmark at index $rowIndex in $source: missing primaryMetric.scoreUnit."
    )
    val secondaryMetrics = row["secondaryMetrics"] as? Map<*, *>
    val allocationMetric = secondaryMetrics?.get("gc.alloc.rate.norm") as? Map<*, *>
    val identity = benchmarkIdentity(row)
    return BenchmarkComparisonRow(
        key = comparisonKey(identity, mode, threads),
        benchmark = identity,
        displayName = benchmarkDisplayName(row),
        mode = mode,
        threads = threads,
        unit = unit,
        score = score,
        allocationBytesPerOp = parseMetricNumber(allocationMetric?.get("score")),
    )
}

fun parseBaselineComparisonRows(baselineFile: File): Map<String, BenchmarkComparisonRow> {
    val parsed = JsonSlurper().parseText(baselineFile.readText())
    val source = baselineFile.absolutePath
    val rows = when (parsed) {
        is Map<*, *> -> parsed["results"] as? List<*>
            ?: throw GradleException("Benchmark baseline is missing results array: $source")
        is List<*> -> parsed
        else -> throw GradleException("Benchmark baseline must be a JSON object or array: $source")
    }
    if (rows.isEmpty()) {
        throw GradleException("Benchmark baseline contains no rows: $source")
    }
    return rows.mapIndexed { rowIndex, rawRow ->
        val row = rawRow as? Map<*, *> ?: throw GradleException(
            "Invalid benchmark baseline row at index $rowIndex in $source: expected row to be a JSON object."
        )
        if (row.containsKey("primaryMetric")) {
            legacyJmhBaselineRow(row, source, rowIndex)
        } else {
            parsedResultBaselineRow(row, source, rowIndex)
        }
    }.associateBy { it.key }
}

fun parsedComparisonRows(rows: List<ParsedBenchmarkResult>): Map<String, BenchmarkComparisonRow> {
    return rows.map { it.toComparisonRow() }.associateBy { it.key }
}

fun isThroughputMetric(row: BenchmarkComparisonRow): Boolean {
    return row.mode == "thrpt" || row.unit.contains("ops", ignoreCase = true)
}

fun compareMetric(
    metric: String,
    baseRow: BenchmarkComparisonRow,
    latestRow: BenchmarkComparisonRow,
    baseline: Double,
    current: Double,
    thresholdPercent: Double,
    higherIsBetter: Boolean,
): BenchmarkMetricComparison {
    val deltaPercent = if (baseline == 0.0) {
        null
    } else {
        ((current - baseline) / baseline) * 100
    }
    return BenchmarkMetricComparison(
        key = latestRow.key,
        metric = metric,
        displayName = latestRow.displayName.ifBlank { baseRow.displayName },
        mode = latestRow.mode,
        threads = latestRow.threads,
        baseline = baseline,
        current = current,
        deltaPercent = deltaPercent,
        thresholdPercent = thresholdPercent,
        higherIsBetter = higherIsBetter,
    )
}

fun BenchmarkMetricComparison.status(): String {
    val delta = deltaPercent ?: return "STABLE"
    val regression = if (higherIsBetter) {
        delta < -thresholdPercent
    } else {
        delta > thresholdPercent
    }
    if (regression) {
        return "${metric.uppercase(Locale.US)}_REGRESSION"
    }
    val improvement = if (higherIsBetter) {
        delta > thresholdPercent
    } else {
        delta < -thresholdPercent
    }
    if (improvement) {
        return "${metric.uppercase(Locale.US)}_IMPROVED"
    }
    return "STABLE"
}

fun benchmarkMetricComparisons(
    baseline: Map<String, BenchmarkComparisonRow>,
    latest: Map<String, BenchmarkComparisonRow>,
): List<BenchmarkMetricComparison> {
    return (baseline.keys + latest.keys).sorted().flatMap { benchmark ->
        val baseRow = baseline[benchmark]
        val latestRow = latest[benchmark]
        if (baseRow == null || latestRow == null) {
            return@flatMap emptyList()
        }
        val primaryMetric = if (isThroughputMetric(latestRow)) {
            compareMetric(
                metric = "throughput",
                baseRow = baseRow,
                latestRow = latestRow,
                baseline = baseRow.score,
                current = latestRow.score,
                thresholdPercent = benchmarkThroughputRegressionPercent,
                higherIsBetter = true,
            )
        } else {
            compareMetric(
                metric = "latency",
                baseRow = baseRow,
                latestRow = latestRow,
                baseline = baseRow.score,
                current = latestRow.score,
                thresholdPercent = benchmarkLatencyRegressionPercent,
                higherIsBetter = false,
            )
        }
        val allocationMetric = if (baseRow.allocationBytesPerOp != null && latestRow.allocationBytesPerOp != null) {
            listOf(
                compareMetric(
                    metric = "allocation",
                    baseRow = baseRow,
                    latestRow = latestRow,
                    baseline = baseRow.allocationBytesPerOp,
                    current = latestRow.allocationBytesPerOp,
                    thresholdPercent = benchmarkAllocationRegressionPercent,
                    higherIsBetter = false,
                )
            )
        } else {
            emptyList()
        }
        listOf(primaryMetric) + allocationMetric
    }
}

tasks.register("benchmarkCompare") {
    description = "Compare primary framework E2E benchmark results against baseline."
    group = "benchmark"

    doLast {
        val baselineFile = frameworkE2EBaselineJson.asFile
        if (!baselineFile.exists()) {
            throw GradleException(
                "Baseline not found: ${baselineFile.absolutePath}. " +
                    "Run :wow-benchmarks:updateBenchmarkBaseline first."
            )
        }
        val baseline = parseBaselineComparisonRows(baselineFile)
        val latest = parsedComparisonRows(parsedFrameworkE2EResults())
        val allBenchmarks = (baseline.keys + latest.keys).sorted()
        val comparisons = benchmarkMetricComparisons(baseline, latest)
        val comparisonsByKey = comparisons.groupBy { it.key }

        val regressions = comparisons.count { it.status().endsWith("_REGRESSION") }
        val improvements = comparisons.count { it.status().endsWith("_IMPROVED") }
        val coverageChanges = allBenchmarks.count { benchmark ->
            baseline[benchmark] == null || latest[benchmark] == null
        }

        println()
        println("## Benchmark Comparison")
        println("Baseline: ${baselineFile.absolutePath}")
        println(
            "Thresholds: throughput=${benchmarkThroughputRegressionPercent}%, " +
                "latency=${benchmarkLatencyRegressionPercent}%, " +
                "allocation=${benchmarkAllocationRegressionPercent}%"
        )
        println()
        println("| Metric | Benchmark | Threads | Mode | Baseline | Current | Delta % | Threshold | Status |")
        println("|--------|-----------|---------|------|----------|---------|---------|-----------|--------|")

        for (benchmark in allBenchmarks) {
            val baseRow = baseline[benchmark]
            val latestRow = latest[benchmark]

            if (baseRow == null) {
                println(
                    "| result | ${latestRow?.displayName ?: benchmark} | ${latestRow?.threads ?: "-"} | " +
                        "${latestRow?.mode ?: "-"} | - | ${latestRow?.score?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} | " +
                        "NEW | - | NEW |"
                )
                continue
            }
            if (latestRow == null) {
                println(
                    "| result | ${baseRow.displayName} | ${baseRow.threads} | ${baseRow.mode} | " +
                        "${String.format(Locale.US, "%.2f", baseRow.score)} | - | REMOVED | - | REMOVED |"
                )
                continue
            }
            comparisonsByKey.getValue(benchmark)
                .forEach { comparison ->
                    println(
                        "| ${comparison.metric} | ${comparison.displayName} | ${comparison.threads} | ${comparison.mode} | " +
                            "${String.format(Locale.US, "%.2f", comparison.baseline)} | " +
                            "${String.format(Locale.US, "%.2f", comparison.current)} | " +
                            "${comparison.deltaPercent?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "n/a"} | " +
                            "${String.format(Locale.US, "%.1f%%", comparison.thresholdPercent)} | ${comparison.status()} |"
                    )
                }
        }

        println()
        println(
            "Summary: $regressions regression(s), $improvements improvement(s), " +
                "${comparisons.size - regressions - improvements} stable metric comparison(s), " +
                "$coverageChanges coverage change(s)"
        )

        if (coverageChanges > 0) {
            throw GradleException("Benchmark coverage changed: $coverageChanges new or removed result row(s)")
        }
        if (regressions > 0) {
            throw GradleException("Benchmark regressions detected: $regressions")
        }
    }
}

tasks.register("updateBenchmarkBaseline") {
    description = "Copy primary framework E2E benchmark results as the new baseline."
    group = "benchmark"

    doLast {
        val localRows = parsedFrameworkE2EResults()
        val baselineFile = frameworkE2EBaselineJson.asFile
        val baselineJson = linkedMapOf(
            "schemaVersion" to 1,
            "suite" to frameworkE2ESuite.id,
            "profile" to fullProfile.id,
            "generatedAt" to Instant.now().toString(),
            "results" to localRows.map { it.toBaselineJsonRow() },
        )
        baselineFile.parentFile.mkdirs()
        baselineFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(baselineJson)))
        logger.lifecycle("Baseline updated: ${baselineFile.absolutePath}")
    }
}
