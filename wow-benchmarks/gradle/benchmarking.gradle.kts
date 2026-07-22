import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BenchmarkRequiredService(
    val service: String,
    val host: String,
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
    val parameters: Map<String, List<String>> = emptyMap(),
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
    val includeGcProfiler: Boolean,
    val includeAsyncProfiler: Boolean,
)

data class BenchmarkReportSpec(
    val suite: BenchmarkSuite,
    val profile: BenchmarkRunProfile,
)

data class CommandOutput(
    val exitCode: Int,
    val output: String,
)

data class DockerContainerRuntime(
    val label: String,
    val containerName: String,
    val image: String?,
    val imageId: String?,
    val repoDigests: String?,
)

fun parseBenchmarkDockerEnvFile(envFile: File): Map<String, String> {
    if (!envFile.exists()) {
        return emptyMap()
    }
    return envFile.readLines()
        .mapIndexedNotNull { lineIndex, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@mapIndexedNotNull null
            }
            val parts = line.split("=", limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) {
                throw GradleException(
                    "Invalid benchmark Docker env entry at ${envFile.absolutePath}:${lineIndex + 1}: $rawLine"
                )
            }
            parts[0].trim() to parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
        }
        .toMap()
}

val benchmarkDockerEnvFile = providers.gradleProperty("benchmarkDockerEnvFile")
    .map { envFilePath -> file(envFilePath) }
    .getOrElse(file("docker/benchmark.env"))

val benchmarkDockerFileEnvironment = parseBenchmarkDockerEnvFile(benchmarkDockerEnvFile)

fun benchmarkDockerConfig(name: String, defaultValue: String): String {
    return providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: benchmarkDockerFileEnvironment[name]?.takeIf { it.isNotBlank() }
        ?: defaultValue
}

fun benchmarkDockerPort(name: String, defaultValue: Int): Int {
    val configuredPort = benchmarkDockerConfig(name, defaultValue.toString())
    val port = configuredPort.toIntOrNull()
    if (port == null || port <= 0) {
        throw GradleException("$name must be a positive integer.")
    }
    return port
}

fun benchmarkDockerRuntimeEnvironment(): Map<String, String> {
    if (benchmarkDockerFileEnvironment.isEmpty()) {
        return emptyMap()
    }
    return benchmarkDockerFileEnvironment.mapValues { (name, value) ->
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } ?: value
    }
}

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
    includeGcProfiler = false,
    includeAsyncProfiler = false,
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
    includeGcProfiler = true,
    includeAsyncProfiler = true,
)

val quickWebFluxProfile = BenchmarkRunProfile(
    id = "quick",
    warmupIterations = 0,
    warmupTime = null,
    measurementIterations = 1,
    measurementTime = "2s",
    forks = 1,
    threads = benchmarkThreadsProperty("benchmarkQuickWebFluxThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt"),
    includeGcProfiler = true,
    includeAsyncProfiler = false,
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
    includeGcProfiler = true,
    includeAsyncProfiler = true,
)

val commandIngressSinkDiagnosticProfile = BenchmarkRunProfile(
    id = "command-ingress-sink-diagnostic",
    warmupIterations = 3,
    warmupTime = "1s",
    measurementIterations = 10,
    measurementTime = "1s",
    forks = 3,
    threads = listOf(1, 4),
    benchmarkModes = listOf("ss"),
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val commandIngressE2EDiagnosticProfile = BenchmarkRunProfile(
    id = "command-ingress-e2e-diagnostic",
    warmupIterations = 3,
    warmupTime = "1s",
    measurementIterations = 5,
    measurementTime = "1s",
    forks = 3,
    threads = listOf(1, 4),
    benchmarkModes = listOf("thrpt"),
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val smokeSuite = BenchmarkSuite(
    id = "smoke",
    displayName = "Smoke",
    commandName = "benchmarkSmoke",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIdComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AccessorComponentBenchmark",
        "me.ahoo.wow.benchmark.component.SerializationComponentBenchmark",
        "me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark",
        "me.ahoo.wow.benchmark.webflux.WebFluxSmokeBenchmark",
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
        BenchmarkRequiredService(
            service = "Redis",
            host = benchmarkDockerConfig("WOW_BENCHMARK_REDIS_HOST", "localhost"),
            port = benchmarkDockerPort("WOW_BENCHMARK_REDIS_HOST_PORT", 6379),
        ),
        BenchmarkRequiredService(
            service = "MongoDB",
            host = benchmarkDockerConfig("WOW_BENCHMARK_MONGO_HOST", "localhost"),
            port = benchmarkDockerPort("WOW_BENCHMARK_MONGO_HOST_PORT", 27017),
        ),
    ),
)

val componentSuite = BenchmarkSuite(
    id = "component",
    displayName = "Component",
    commandName = "benchmarkFullComponent",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIdComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AccessorComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandValidationComponentBenchmark",
        "me.ahoo.wow.benchmark.component.IdempotencyComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AggregateLoadComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AggregateHandleComponentBenchmark",
        "me.ahoo.wow.benchmark.component.EventStoreComponentBenchmark",
        "me.ahoo.wow.benchmark.component.EventPublishComponentBenchmark",
        "me.ahoo.wow.benchmark.component.WaitNotifyComponentBenchmark",
        "me.ahoo.wow.benchmark.component.SerializationComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandDispatcherChainComponentBenchmark",
        "me.ahoo.wow.benchmark.component.MongoDocumentComponentBenchmark",
    ),
    resultFileName = "component.json",
    humanFileName = "component-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = false,
)

val commandIngressSinkDiagnosticSuite = BenchmarkSuite(
    id = "command-ingress-sink-diagnostic",
    displayName = "Command Ingress Sink Diagnostic",
    commandName = "benchmarkCommandIngressSinkDiagnostic",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIngressSinkComponentBenchmark",
    ),
    resultFileName = "command-ingress-sink-diagnostic.json",
    humanFileName = "command-ingress-sink-diagnostic-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = false,
)

val commandIngressE2EDiagnosticSuite = BenchmarkSuite(
    id = "command-ingress-e2e-diagnostic",
    displayName = "Command Ingress E2E Diagnostic",
    commandName = "benchmarkCommandIngressE2EDiagnostic",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.e2e.CommandIngressE2EDiagnosticBenchmark",
    ),
    resultFileName = "command-ingress-e2e-diagnostic.json",
    humanFileName = "command-ingress-e2e-diagnostic-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = false,
    parameters = mapOf(
        "ingressStrategy" to listOf("current-production", "legacy-lock"),
    ),
)

val webFluxSuite = BenchmarkSuite(
    id = "webflux",
    displayName = "WebFlux Adapter",
    commandName = "benchmarkFullWebFlux",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.webflux.CommandHandlerFunctionBenchmark",
        "me.ahoo.wow.benchmark.webflux.WebFluxResponseBenchmark",
        "me.ahoo.wow.benchmark.webflux.AggregateTracingBenchmark",
    ),
    resultFileName = "webflux.json",
    humanFileName = "webflux-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = false,
)

val quickWebFluxSuite = webFluxSuite.copy(
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.webflux.CommandHandlerFunctionBenchmark.extractPreparedCommandMessage",
        "me.ahoo.wow.benchmark.webflux.CommandHandlerFunctionBenchmark.sendWaitSentCoreFromExtractedMessage",
        "me.ahoo.wow.benchmark.webflux.CommandHandlerFunctionBenchmark.commandResultJsonServerResponseOnly",
        "me.ahoo.wow.benchmark.webflux.CommandHandlerFunctionBenchmark.handlePreparedAddCartItemRequestWaitSent",
        "me.ahoo.wow.benchmark.webflux.WebFluxResponseBenchmark.commandResultSseServerResponseOnly",
        "me.ahoo.wow.benchmark.webflux.WebFluxResponseBenchmark.fluxJsonStreamingArrayServerResponseOnly",
        "me.ahoo.wow.benchmark.webflux.AggregateTracingBenchmark.traceAndSerializeCartHistory",
        "me.ahoo.wow.benchmark.webflux.AggregateTracingBenchmark.traceWindowWithPrefixReplayAndSerialize",
        "me.ahoo.wow.benchmark.webflux.AggregateTracingBenchmark.handleTailLimitRequestAndSerialize",
    )
)

val reportSuites = listOf(frameworkE2ESuite, infrastructureE2ESuite, componentSuite, webFluxSuite)
val quickReportSpecs = listOf(
    BenchmarkReportSpec(frameworkE2ESuite, quickProfile),
    BenchmarkReportSpec(infrastructureE2ESuite, quickProfile),
    BenchmarkReportSpec(componentSuite, quickProfile),
    BenchmarkReportSpec(quickWebFluxSuite, quickWebFluxProfile),
)

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

fun benchmarkProfilerArgs(includeGcProfiler: Boolean, includeAsyncProfiler: Boolean): List<String> {
    if (!includeGcProfiler && !includeAsyncProfiler) {
        return emptyList()
    }
    val asyncProfilerLib = file("/opt/async-profiler/lib/libasyncProfiler.dylib")
    return buildList {
        if (includeGcProfiler) {
            add("-prof")
            add("gc")
        }
        if (includeAsyncProfiler) {
            add("-prof")
            if (asyncProfilerLib.exists()) {
                add("async:output=flamegraph;dir=build/profiling;event=cpu;libPath=${asyncProfilerLib.absolutePath}")
            } else {
                add("stack:lines=10;top=20")
            }
        }
    }
}

fun requireBenchmarkService(service: String, host: String, port: Int) {
    val available = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2000)
        }
    }.isSuccess
    require(available) {
        "$service is required for Infrastructure I/O benchmarks at $host:$port. " +
            "Start $service and rerun the selected infrastructure benchmark task."
    }
}

fun suiteResultFile(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): Provider<RegularFile> {
    return providers.provider {
        layout.projectDirectory.file(
            "results/jmh/${profile.id}/${suite.id}/threads-$threads-${suite.resultFileName}"
        )
    }
}

fun suiteHumanFile(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): Provider<RegularFile> {
    return providers.provider {
        layout.projectDirectory.file(
            "results/jmh/${profile.id}/${suite.id}/threads-$threads-${suite.humanFileName}"
        )
    }
}

fun BenchmarkRunProfile.configSummary(): String {
    val warmup = if (warmupTime == null) {
        "warmup=$warmupIterations"
    } else {
        "warmup=${warmupIterations}x$warmupTime"
    }
    val profilers = buildList {
        if (includeGcProfiler) {
            add("gc")
        }
        if (includeAsyncProfiler) {
            add("async")
        }
    }.ifEmpty { listOf("none") }
    return "$warmup, measurement=${measurementIterations}x$measurementTime, " +
        "fork=$forks, threads=${threads.joinToString(",")}, modes=${benchmarkModes.joinToString(",")}, " +
        "profilers=${profilers.joinToString(",")}"
}

fun reportDateTime(): String {
    return ZonedDateTime.now()
        .truncatedTo(ChronoUnit.SECONDS)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

fun physicalMemoryBytes(): Long? {
    val osBean = ManagementFactory.getOperatingSystemMXBean() as? SunOperatingSystemMXBean ?: return null
    return runCatching {
        osBean.totalMemorySize.takeIf { it > 0 }
    }.getOrNull()
}

fun formatMemoryBytes(bytes: Long?): String {
    if (bytes == null) {
        return "unavailable"
    }
    val gib = bytes.toDouble() / 1024 / 1024 / 1024
    return "${String.format(Locale.US, "%.1f", gib)} GiB"
}

fun runCommand(command: List<String>, timeoutSeconds: Long = 3): CommandOutput {
    if (command.isEmpty()) {
        return CommandOutput(exitCode = -1, output = "empty command")
    }
    return try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            return CommandOutput(exitCode = -1, output = "timed out after ${timeoutSeconds}s")
        }
        CommandOutput(
            exitCode = process.exitValue(),
            output = process.inputStream.bufferedReader().readText().trim(),
        )
    } catch (error: Exception) {
        CommandOutput(
            exitCode = -1,
            output = error.message ?: error::class.java.simpleName,
        )
    }
}

fun unavailableCommandValue(commandName: String, commandOutput: CommandOutput): String {
    val reason = commandOutput.output.lineSequence()
        .firstOrNull()
        ?.take(160)
        ?: "exit ${commandOutput.exitCode}"
    return "unavailable ($commandName: $reason)"
}

fun formatMemoryMiB(mib: String?): String {
    val bytes = mib?.toLongOrNull()?.let { it * 1024L * 1024L }
    return formatMemoryBytes(bytes)
}

fun commandLineOption(commandLine: String, option: String): String? {
    val optionPattern = Regex("${Regex.escape(option)}(?:=|\\s+)(\\S+)")
    return optionPattern.find(commandLine)?.groupValues?.get(1)
}

fun benchmarkClientLocation(): String {
    val containerMarkerExists = File("/.dockerenv").exists() || File("/run/.containerenv").exists()
    return if (containerMarkerExists) {
        "container JVM"
    } else {
        "host JVM"
    }
}

fun dockerServerSummary(): String {
    val commandOutput = runCommand(
        listOf(
            "docker",
            "info",
            "--format",
            "Server={{.ServerVersion}} CPUs={{.NCPU}} MemoryBytes={{.MemTotal}} Kernel={{.KernelVersion}}",
        )
    )
    if (commandOutput.exitCode != 0 || commandOutput.output.isBlank()) {
        return unavailableCommandValue("docker info", commandOutput)
    }
    return commandOutput.output.replace(Regex("MemoryBytes=(\\d+)")) { match ->
        val memoryBytes = match.groupValues[1].toLongOrNull()
        "Memory=${formatMemoryBytes(memoryBytes)}"
    }
}

fun dockerDesktopVmSummary(): String {
    val pidOutput = runCommand(listOf("pgrep", "-f", "com.docker.virtualization"))
    if (pidOutput.exitCode != 0 || pidOutput.output.isBlank()) {
        return unavailableCommandValue("pgrep", pidOutput)
    }
    val virtualizationPid = pidOutput.output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: return "unavailable (Docker Desktop virtualization process not found)"
    val commandOutput = runCommand(listOf("ps", "-ww", "-o", "command=", "-p", virtualizationPid))
    if (commandOutput.exitCode != 0 || commandOutput.output.isBlank()) {
        return unavailableCommandValue("ps", commandOutput)
    }
    val virtualizationCommand = commandOutput.output.lineSequence().first()
    val networkType = commandLineOption(virtualizationCommand, "--networkType") ?: "unavailable"
    val cpus = commandLineOption(virtualizationCommand, "--cpus") ?: "unavailable"
    val memoryMiB = formatMemoryMiB(commandLineOption(virtualizationCommand, "--memoryMiB"))
    return "networkType=$networkType CPUs=$cpus Memory=$memoryMiB"
}

fun dockerContainerRuntime(label: String, containerName: String): DockerContainerRuntime {
    val inspectOutput = runCommand(
        listOf(
            "docker",
            "inspect",
            containerName,
            "--format",
            "{{.Config.Image}}|{{.Image}}",
        )
    )
    if (inspectOutput.exitCode != 0 || inspectOutput.output.isBlank()) {
        return DockerContainerRuntime(
            label = label,
            containerName = containerName,
            image = null,
            imageId = null,
            repoDigests = null,
        )
    }
    val parts = inspectOutput.output.lineSequence()
        .first()
        .split("|", limit = 2)
    val image = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "<no value>" }
    val imageId = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "<no value>" }
    val repoDigests = image?.let { imageName ->
        val imageOutput = runCommand(
            listOf(
                "docker",
                "image",
                "inspect",
                imageName,
                "--format",
                "{{json .RepoDigests}}",
            )
        )
        imageOutput.output
            .takeIf { imageOutput.exitCode == 0 }
            ?.takeIf { it.isNotBlank() && it != "[]" && it != "null" }
    }
    return DockerContainerRuntime(
        label = label,
        containerName = containerName,
        image = image,
        imageId = imageId,
        repoDigests = repoDigests,
    )
}

fun dockerContainerRuntimes(): List<DockerContainerRuntime> {
    return listOf(
        dockerContainerRuntime(
            label = "Redis",
            containerName = benchmarkDockerConfig(
                name = "WOW_BENCHMARK_REDIS_CONTAINER_NAME",
                defaultValue = "wow-benchmark-redis",
            ),
        ),
        dockerContainerRuntime(
            label = "Mongo",
            containerName = benchmarkDockerConfig(
                name = "WOW_BENCHMARK_MONGO_CONTAINER_NAME",
                defaultValue = "wow-benchmark-mongo",
            ),
        ),
    )
}

fun markdownCodeOrUnavailable(value: String?): String {
    return value?.takeIf { it.isNotBlank() }?.let { "`$it`" } ?: "unavailable"
}

fun benchmarkReportPath(file: File): String {
    val projectRoot = layout.projectDirectory.asFile.toPath().toAbsolutePath().normalize()
    val filePath = file.toPath().toAbsolutePath().normalize()
    if (!filePath.startsWith(projectRoot)) {
        return file.absolutePath
    }
    val relativePath = projectRoot.relativize(filePath)
        .toString()
        .replace(File.separatorChar, '/')
    return "${project.name}/$relativePath"
}

fun StringBuilder.appendBenchmarkEnvironment(
    version: String,
    profile: BenchmarkRunProfile?,
) {
    appendLine("## Environment")
    appendLine("- **Version**: $version")
    appendLine("- **JVM**: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
    appendLine("- **OS**: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
    appendLine("- **DateTime**: ${reportDateTime()}")
    appendLine("- **CPU Cores**: ${Runtime.getRuntime().availableProcessors()}")
    appendLine("- **Physical Memory**: ${formatMemoryBytes(physicalMemoryBytes())}")
    appendLine("- **Benchmark JVM Args**: `${benchmarkJvmArgs.joinToString(" ")}`")
    if (profile != null) {
        appendLine("- **JMH Config**: ${profile.configSummary()}")
    }
    appendLine()
}

fun StringBuilder.appendInfrastructureRuntime() {
    appendLine("## Infrastructure Runtime")
    appendLine("- **Benchmark Client**: ${benchmarkClientLocation()}")
    appendLine("- **Docker Compose Env File**: `${benchmarkReportPath(benchmarkDockerEnvFile)}`")
    appendLine("- **Docker Server**: ${dockerServerSummary()}")
    appendLine("- **Docker Desktop VM**: ${dockerDesktopVmSummary()}")
    dockerContainerRuntimes().forEach { containerRuntime ->
        appendLine("- **${containerRuntime.label} Container**: `${containerRuntime.containerName}`")
        appendLine("  - Image: ${markdownCodeOrUnavailable(containerRuntime.image)}")
        appendLine("  - Image ID: ${markdownCodeOrUnavailable(containerRuntime.imageId)}")
        appendLine("  - Repo Digests: ${markdownCodeOrUnavailable(containerRuntime.repoDigests)}")
    }
    appendLine(
        "- **Network Note**: Host JVM infrastructure benchmarks use Docker-published localhost ports; " +
            "Docker Desktop host-to-VM networking can materially affect Redis and Mongo results."
    )
    appendLine()
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
        webFluxSuite.id -> "benchmark${profileLabel}WebFlux"
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
            suite.parameters.forEach { (name, values) ->
                add("-p")
                add("$name=${values.joinToString(",")}")
            }
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
        args(benchmarkProfilerArgs(profile.includeGcProfiler, profile.includeAsyncProfiler))

        outputs.file(resultFile)
        outputs.file(humanFile)
        outputs.upToDateWhen { false }

        doFirst {
            resultFile.get().asFile.parentFile.mkdirs()
            humanFile.get().asFile.parentFile.mkdirs()
            environment(benchmarkDockerRuntimeEnvironment())
            suite.requiredServices.forEach { requiredService ->
                requireBenchmarkService(requiredService.service, requiredService.host, requiredService.port)
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
    threadTasks.zipWithNext().forEach { (previous, next) ->
        next.configure {
            mustRunAfter(previous)
        }
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
registerBenchmarkAggregateTask(
    "benchmarkCommandIngressSinkDiagnostic",
    commandIngressSinkDiagnosticSuite,
    commandIngressSinkDiagnosticProfile,
)
registerBenchmarkAggregateTask(
    "benchmarkCommandIngressE2EDiagnostic",
    commandIngressE2EDiagnosticSuite,
    commandIngressE2EDiagnosticProfile,
)
registerBenchmarkAggregateTask("benchmarkQuickWebFlux", quickWebFluxSuite, quickWebFluxProfile)
registerBenchmarkAggregateTask("benchmarkFullWebFlux", webFluxSuite, fullProfile)

tasks.named("jmh") {
    enabled = false
    group = null
    description = "Disabled. Use the layered benchmark tasks instead."
}

val resultsDir = layout.projectDirectory.dir("results")
val frameworkE2EBaselineJson = resultsDir.file("baselines/framework-e2e.json")
val reportsDir = resultsDir.dir("reports")
val benchmarkReportFile = reportsDir.file("quick-framework-e2e.md")
val infrastructureBenchmarkReportFile = reportsDir.file("quick-infrastructure-e2e.md")
val webFluxBenchmarkReportFile = reportsDir.file("quick-webflux.md")
val commandIngressDiagnosticReportFile = reportsDir.file("quick-command-ingress.md")
val groupedBenchmarkReport = reportsDir.file("full-grouped.md")
val quickGroupedBenchmarkReport = reportsDir.file("quick-grouped.md")

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

data class FormattedBenchmarkScore(
    val score: String,
    val error: String,
    val unit: String,
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

fun latencyUnitSeconds(unit: String): Double? {
    return when (unit) {
        "s" -> 1.0
        "ms" -> 1.0e-3
        "us" -> 1.0e-6
        "ns" -> 1.0e-9
        else -> null
    }
}

fun latencyDisplayUnit(secondsPerOp: Double): String {
    val absoluteSeconds = kotlin.math.abs(secondsPerOp)
    return when {
        absoluteSeconds == 0.0 -> "s"
        absoluteSeconds < 1.0e-6 -> "ns"
        absoluteSeconds < 1.0e-3 -> "us"
        absoluteSeconds < 1.0 -> "ms"
        else -> "s"
    }
}

fun formatBenchmarkScore(score: Double, scoreError: Double?, unit: String): FormattedBenchmarkScore {
    val latencySourceUnit = unit.removeSuffix("/op").takeIf { unit.endsWith("/op") }
    val sourceSeconds = latencySourceUnit?.let { latencyUnitSeconds(it) }
    if (sourceSeconds != null) {
        val secondsPerOp = score * sourceSeconds
        val displayUnit = latencyDisplayUnit(secondsPerOp)
        val displaySeconds = latencyUnitSeconds(displayUnit)!!
        val displayScore = secondsPerOp / displaySeconds
        val displayError = scoreError?.let { "+/-${String.format(Locale.US, "%.2f", it * sourceSeconds / displaySeconds)}" }
            ?: "-"
        return FormattedBenchmarkScore(
            score = String.format(Locale.US, "%.2f", displayScore),
            error = displayError,
            unit = "$displayUnit/op",
        )
    }
    return FormattedBenchmarkScore(
        score = String.format(Locale.US, "%.2f", score),
        error = formatScoreError(scoreError),
        unit = unit,
    )
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
            val score = formatBenchmarkScore(row.score, row.scoreError, row.unit)
            appendLine(
                "| ${row.suite.displayName} | ${row.displayName} | ${row.threads} | ${row.mode} | " +
                    "${score.score} | ${score.error} | ${score.unit} | " +
                    "${formatAllocationBytes(row.allocationBytesPerOp)} |"
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
    val reportProfileIds = groups.map { it.profile.id }.distinct()
    if (reportProfileIds.size != 1) {
        throw GradleException("Grouped benchmark report requires one run profile id, found: $reportProfileIds")
    }
    val reportProfile = groups.first().profile
    val reportLabel = reportProfile.reportLabel()
    val reportProfileConfigs = groups.map { it.profile.configSummary() }.distinct()
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
    val webFluxRows = parsedGroups
        .filter { it.group.suite.id == webFluxSuite.id }
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
    sb.appendLine(
        "- Framework E2E results isolate command pipeline overhead with in-memory or noop stores; " +
            "they are not production persistence capacity."
    )
    sb.appendLine("- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.")
    sb.appendLine(
        "- No-snapshot growing-stream scenarios are diagnostics for replay pressure, not default E2E goals."
    )
    sb.appendLine("- Component results explain bottlenecks and are not standalone performance goals.")
    sb.appendLine("- Smoke results are excluded from performance reports.")
    sb.appendLine()
    sb.appendBenchmarkEnvironment(
        version = version,
        profile = reportProfile.takeIf { reportProfileConfigs.size == 1 },
    )
    if (infrastructureRows.isNotEmpty()) {
        sb.appendInfrastructureRuntime()
    }
    if (reportProfileConfigs.size > 1) {
        sb.appendLine("## Run Profiles")
        sb.appendLine()
        groups.forEach { group ->
            sb.appendLine("- **${group.suite.displayName}**: ${group.profile.configSummary()}")
        }
        sb.appendLine()
    }
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
    if (webFluxRows.isNotEmpty()) {
        sb.appendLine("## WebFlux Adapter Bottlenecks")
        sb.appendLine()
        sb.appendLine("### Lowest Throughput")
        sb.appendLine()
        sb.appendThroughputBottlenecks(webFluxRows)
        sb.appendLine()
        sb.appendLine("### Highest Allocation")
        sb.appendLine()
        sb.appendAllocationBottlenecks(webFluxRows)
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
        sb.appendLine("- **JMH Config**: ${group.profile.configSummary()}")
        val performanceConclusionSource = group.profile.id == fullProfile.id && group.suite.performanceConclusionSource
        sb.appendLine("- **Performance Conclusion Source**: ${if (performanceConclusionSource) "yes" else "no"}")
        sb.appendLine("- **Source Row Count**: ${groupReport.sourceRowCount}")
        sb.appendLine("- **Parsed Row Count**: ${rows.size}")
        sb.appendLine()
        group.resultFiles.forEach { resultFile ->
            val file = resultFile.resultFile.get().asFile
            sb.appendLine("- **threads=${resultFile.threads} Result File**: `${benchmarkReportPath(file)}`")
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

fun renderSingleBenchmarkReport(
    group: BenchmarkResultGroup,
    title: String,
    command: String,
    description: String,
    includeInfrastructureRuntime: Boolean = false,
): String {
    val groupReport = parseBenchmarkGroup(JsonSlurper(), group)
    if (groupReport.rows.isEmpty()) {
        throw GradleException(
            "No benchmark rows were available for ${group.suite.displayName}. " +
                "Run ${group.suite.taskName(group.profile)} first."
        )
    }
    val sb = StringBuilder()
    sb.appendLine("<!--")
    sb.appendLine("  This file is auto-generated by `$command`.")
    sb.appendLine("  Do not manually edit benchmark results.")
    sb.appendLine("-->")
    sb.appendLine()
    sb.appendLine("# $title")
    sb.appendLine()
    sb.appendLine(description)
    sb.appendLine()
    sb.appendBenchmarkEnvironment(project.version.toString(), group.profile)
    if (includeInfrastructureRuntime) {
        sb.appendInfrastructureRuntime()
    }
    sb.appendLine("## Results")
    sb.appendLine()
    sb.appendBenchmarkTable(groupReport.rows)
    return sb.toString()
}

fun renderBottleneckBenchmarkReport(
    group: BenchmarkResultGroup,
    title: String,
    command: String,
    description: String,
): String {
    val groupReport = parseBenchmarkGroup(JsonSlurper(), group)
    if (groupReport.rows.isEmpty()) {
        throw GradleException(
            "No benchmark rows were available for ${group.suite.displayName}. " +
                "Run ${group.suite.taskName(group.profile)} first."
        )
    }
    val sb = StringBuilder()
    sb.appendLine("<!--")
    sb.appendLine("  This file is auto-generated by `$command`.")
    sb.appendLine("  Do not manually edit benchmark results.")
    sb.appendLine("-->")
    sb.appendLine()
    sb.appendLine("# $title")
    sb.appendLine()
    sb.appendLine(description)
    sb.appendLine()
    sb.appendBenchmarkEnvironment(project.version.toString(), group.profile)
    sb.appendLine("## Source Files")
    sb.appendLine()
    group.resultFiles.forEach { resultFile ->
        val file = resultFile.resultFile.get().asFile
        sb.appendLine("- **threads=${resultFile.threads} Result File**: `${benchmarkReportPath(file)}`")
        if (file.exists()) {
            sb.appendLine("  - Last Modified: ${Instant.ofEpochMilli(file.lastModified())}")
        }
    }
    sb.appendLine()
    sb.appendLine("## Bottlenecks")
    sb.appendLine()
    sb.appendLine("### Lowest Throughput")
    sb.appendLine()
    sb.appendThroughputBottlenecks(groupReport.rows)
    sb.appendLine()
    sb.appendLine("### Highest Allocation")
    sb.appendLine()
    sb.appendAllocationBottlenecks(groupReport.rows)
    sb.appendLine()
    sb.appendLine("## Results")
    sb.appendLine()
    sb.appendBenchmarkTable(groupReport.rows)
    return sb.toString()
}

fun requireDiagnosticRow(
    rows: List<ParsedBenchmarkResult>,
    threads: Int,
    mode: String,
    parameter: String,
): ParsedBenchmarkResult =
    rows.singleOrNull { row ->
        row.threads == threads && row.mode == mode && row.benchmark.contains(parameter)
    } ?: throw GradleException(
        "Missing diagnostic row for threads=$threads, mode=$mode, parameter=$parameter."
    )

fun confidenceIntervalsSeparated(
    first: ParsedBenchmarkResult,
    second: ParsedBenchmarkResult,
): Boolean {
    val firstError = first.scoreError ?: return false
    val secondError = second.scoreError ?: return false
    return first.score - firstError > second.score + secondError ||
        second.score - secondError > first.score + firstError
}

fun renderCommandIngressDiagnosticReport(): String {
    val sinkGroup = benchmarkResultGroup(commandIngressSinkDiagnosticSuite, commandIngressSinkDiagnosticProfile)
    val e2eGroup = benchmarkResultGroup(commandIngressE2EDiagnosticSuite, commandIngressE2EDiagnosticProfile)
    val parser = JsonSlurper()
    val sinkRows = parseBenchmarkGroup(parser, sinkGroup).rows
    val e2eRows = parseBenchmarkGroup(parser, e2eGroup).rows
    val currentT1 = requireDiagnosticRow(e2eRows, 1, "thrpt", "ingressStrategy=current-production")
    val legacyT1 = requireDiagnosticRow(e2eRows, 1, "thrpt", "ingressStrategy=legacy-lock")
    val currentT4 = requireDiagnosticRow(e2eRows, 4, "thrpt", "ingressStrategy=current-production")
    val legacyT4 = requireDiagnosticRow(e2eRows, 4, "thrpt", "ingressStrategy=legacy-lock")
    val atomicSinkT4 = requireDiagnosticRow(
        sinkRows,
        4,
        "ss",
        "strategy=atomic-mpsc, subscriberCount=1",
    )
    val legacySinkT4 = requireDiagnosticRow(
        sinkRows,
        4,
        "ss",
        "strategy=legacy-lock, subscriberCount=1",
    )
    val t1Gain = (currentT1.score / legacyT1.score - 1.0) * 100.0
    val t4Gain = (currentT4.score / legacyT4.score - 1.0) * 100.0
    val t4AllocationDelta = currentT4.allocationBytesPerOp?.let { currentAllocation ->
        legacyT4.allocationBytesPerOp?.let { legacyAllocation -> currentAllocation - legacyAllocation }
    }
    val t4AllocationDeltaPercent = t4AllocationDelta?.let { delta ->
        legacyT4.allocationBytesPerOp
            ?.takeIf { it != 0.0 }
            ?.let { baseline -> delta / baseline * 100.0 }
    }
    val sinkRatio = if (atomicSinkT4.score >= legacySinkT4.score) {
        atomicSinkT4.score / legacySinkT4.score
    } else {
        legacySinkT4.score / atomicSinkT4.score
    }
    val sinkDirection = if (atomicSinkT4.score >= legacySinkT4.score) "slower" else "faster"
    val isolatedSinkConclusion = if (atomicSinkT4.score >= legacySinkT4.score) {
        "does not make an isolated no-op sink faster"
    } else {
        "makes the isolated no-op sink faster"
    }
    val t4IntervalsSeparated = confidenceIntervalsSeparated(currentT4, legacyT4)
    val t1IntervalsSeparated = confidenceIntervalsSeparated(currentT1, legacyT1)
    val t4Direction = if (t4Gain >= 0.0) "gain" else "regression"
    val t1Direction = if (t1Gain >= 0.0) "gain" else "regression"
    val allocationConclusion = when {
        t4AllocationDeltaPercent == null -> "Allocation comparison is unavailable."
        t4AllocationDeltaPercent <= 0.0 -> "No allocation increase was observed."
        t4AllocationDeltaPercent <= 1.0 -> "The allocation increase is below 1%."
        else -> "The allocation increase exceeds 1% and is a material regression signal."
    }
    val pipelineConclusion = if (t4Gain > 0.0 && t4IntervalsSeparated) {
        "the t4 processed pipeline has a statistically separated net throughput gain"
    } else {
        "the t4 processed pipeline does not confirm a statistically separated net throughput gain"
    }
    val sb = StringBuilder()
    sb.appendLine("<!--")
    sb.appendLine(
        "  This file is auto-generated by `./gradlew :wow-benchmarks:benchmarkCommandIngressSinkDiagnostic " +
            ":wow-benchmarks:benchmarkCommandIngressE2EDiagnostic " +
            ":wow-benchmarks:generateCommandIngressDiagnosticReport --no-parallel`."
    )
    sb.appendLine("  Do not manually edit benchmark results.")
    sb.appendLine("-->")
    sb.appendLine()
    sb.appendLine("# Quick Command Ingress Diagnostic Report")
    sb.appendLine()
    sb.appendLine(
        "This matched single-binary diagnostic separates bare sink cost from the real " +
            "`sendAndWaitProcessed` pipeline effect. It is stronger than comparing reports from different commits, " +
            "but remains local benchmark evidence rather than a cross-machine capacity claim."
    )
    sb.appendLine()
    sb.appendBenchmarkEnvironment(project.version.toString(), null)
    sb.appendLine("- **Sink Diagnostic JMH Config**: ${commandIngressSinkDiagnosticProfile.configSummary()}")
    sb.appendLine("- **E2E Diagnostic JMH Config**: ${commandIngressE2EDiagnosticProfile.configSummary()}")
    sb.appendLine()
    sb.appendLine("## Conclusion")
    sb.appendLine()
    sb.appendLine(
        "- **Primary t4 E2E $t4Direction**: `${String.format(Locale.US, "%.2f", legacyT4.score)} -> " +
            "${String.format(Locale.US, "%.2f", currentT4.score)} ops/s` " +
            "(`${String.format(Locale.US, "%+.2f", t4Gain)}%`). " +
            "The JMH confidence intervals are " +
            "${if (t4IntervalsSeparated) "separated" else "overlapping"}."
    )
    sb.appendLine(
        "- **t1 guardrail ($t1Direction)**: `${String.format(Locale.US, "%.2f", legacyT1.score)} -> " +
            "${String.format(Locale.US, "%.2f", currentT1.score)} ops/s` " +
            "(`${String.format(Locale.US, "%+.2f", t1Gain)}%`); the confidence intervals are " +
            "${if (t1IntervalsSeparated) "separated" else "overlapping"}. " +
            if (t1IntervalsSeparated) {
                "The t1 $t1Direction is statistically separated."
            } else {
                "No t1 change is claimed."
            }
    )
    sb.appendLine(
        "- **t4 E2E allocation**: `${formatAllocationBytes(legacyT4.allocationBytesPerOp)} -> " +
            "${formatAllocationBytes(currentT4.allocationBytesPerOp)}` " +
            "(`delta=${t4AllocationDelta?.let { String.format(Locale.US, "%+.1f B/op", it) } ?: "unavailable"}`). " +
            allocationConclusion
    )
    sb.appendLine(
        "- **Raw sink trade-off**: with one draining subscriber at t4, atomic MPSC is " +
            "`${String.format(Locale.US, "%.2f", sinkRatio)}x` $sinkDirection than the legacy lock in per-worker " +
            "single-shot latency and allocates `${formatAllocationBytes(atomicSinkT4.allocationBytesPerOp)}`."
    )
    sb.appendLine()
    sb.appendLine("## Interpretation")
    sb.appendLine()
    sb.appendLine(
        "The optimization $isolatedSinkConclusion. Its framework-level effect comes from " +
            "removing the legacy lock convoy around synchronous downstream `onNext` work: concurrent producers " +
            "can enqueue while one producer drains the command pipeline. The MPSC node allocation and atomic " +
            "coordination cost are visible in the component result; $pipelineConclusion. $allocationConclusion"
    )
    sb.appendLine()
    sb.appendLine("The zero-subscriber component rows are buffering diagnostics only. They retain a fixed backlog " +
        "per iteration and must not be used as production-path performance evidence.")
    sb.appendLine()
    sb.appendLine("## Sink Component Results")
    sb.appendLine()
    sb.appendBenchmarkTable(sinkRows)
    sb.appendLine()
    sb.appendLine("## Matched Framework E2E Results")
    sb.appendLine()
    sb.appendBenchmarkTable(e2eRows)
    return sb.toString()
}

tasks.register("generateBenchmarkReport") {
    description = "Generate quick framework E2E benchmark report from JMH JSON results."
    group = "benchmark"
    mustRunAfter("benchmarkQuickE2E")
    outputs.file(benchmarkReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(frameworkE2ESuite, quickProfile),
            title = "Quick Framework E2E Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport",
            description = "Quick Framework E2E results are directional local feedback. " +
                "Use Full E2E runs for formal performance conclusions. " +
                "Framework E2E isolates command pipeline overhead with in-memory or noop stores. " +
                "The sent-path benchmark keeps a no-op receiver subscribed so it measures acknowledged ingress " +
                "instead of an unbounded retained backlog.",
        ) + "\n## Command Ingress Diagnostic\n\n" +
            "See [Quick Command Ingress Diagnostic Report](quick-command-ingress.md) for the matched " +
            "legacy-lock versus current-production A/B evidence.\n"

        val outputFile = benchmarkReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateCommandIngressDiagnosticReport") {
    description = "Generate the matched command ingress component and E2E diagnostic report."
    group = "benchmark"
    mustRunAfter("benchmarkCommandIngressSinkDiagnostic", "benchmarkCommandIngressE2EDiagnostic")
    outputs.file(commandIngressDiagnosticReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val outputFile = commandIngressDiagnosticReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(renderCommandIngressDiagnosticReport())
        logger.lifecycle("Command ingress diagnostic report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateInfrastructureBenchmarkReport") {
    description = "Generate quick infrastructure E2E benchmark report from JMH JSON results."
    group = "benchmark"
    mustRunAfter("benchmarkQuickInfrastructureE2E")
    outputs.file(infrastructureBenchmarkReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(infrastructureE2ESuite, quickProfile),
            title = "Quick Infrastructure E2E Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E " +
                ":wow-benchmarks:generateInfrastructureBenchmarkReport",
            description = "Quick Infrastructure E2E results are directional local feedback for real Redis " +
                "and Mongo persistence paths. They include local service and machine effects; " +
                "use Full Infrastructure E2E for formal infrastructure conclusions.",
            includeInfrastructureRuntime = true,
        )

        val outputFile = infrastructureBenchmarkReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Infrastructure benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateQuickWebFluxBenchmarkReport") {
    description = "Generate quick WebFlux benchmark report from JMH JSON results."
    group = "benchmark"
    mustRunAfter("benchmarkQuickWebFlux")
    outputs.file(webFluxBenchmarkReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderBottleneckBenchmarkReport(
            group = benchmarkResultGroup(quickWebFluxSuite, quickWebFluxProfile),
            title = "Quick WebFlux Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickWebFlux " +
                ":wow-benchmarks:generateQuickWebFluxBenchmarkReport",
            description = "Quick WebFlux results are short-loop local feedback for command dispatch, " +
                "response construction, and aggregate tracing hotspots. The profile keeps the JMH GC profiler " +
                "so gc.alloc.rate.norm remains available, but skips async profiler flamegraphs; " +
                "run Full WebFlux for the complete benchmark matrix.",
        )

        val outputFile = webFluxBenchmarkReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Quick WebFlux benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateGroupedBenchmarkReport") {
    description = "Generate full grouped E2E and component benchmark reports from JMH JSON results."
    group = "benchmark"
    mustRunAfter("benchmarkFullE2E", "benchmarkFullInfrastructureE2E", "benchmarkFullComponent", "benchmarkFullWebFlux")
    outputs.file(groupedBenchmarkReport)
    outputs.upToDateWhen { false }
    doLast {
        val outputFile = groupedBenchmarkReport.asFile
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
    mustRunAfter("benchmarkQuickE2E", "benchmarkQuickInfrastructureE2E", "benchmarkQuickComponent", "benchmarkQuickWebFlux")
    outputs.file(quickGroupedBenchmarkReport)
    outputs.upToDateWhen { false }
    doLast {
        val outputFile = quickGroupedBenchmarkReport.asFile
        outputFile.delete()
        val report = renderGroupedBenchmarkReport(
            groups = quickReportSpecs.map { benchmarkResultGroup(it.suite, it.profile) },
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
