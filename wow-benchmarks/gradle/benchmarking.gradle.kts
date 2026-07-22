import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

val benchmarkBaselineSchemaVersion = 2

data class BenchmarkRequiredService(
    val service: String,
    val host: String,
    val port: Int,
)

data class BenchmarkSuite(
    val id: String,
    val displayName: String,
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
    val jvmArgs: List<String>,
    val includeGcProfiler: Boolean,
    val includeAsyncProfiler: Boolean,
    val parameters: Map<String, String> = emptyMap(),
)

data class BenchmarkTaskSpec(
    val taskName: String,
    val suite: BenchmarkSuite,
    val profile: BenchmarkRunProfile,
    val description: String,
    val taskGroup: String = "benchmark",
)

data class CommandOutput(
    val exitCode: Int,
    val output: String,
)

abstract class BenchmarkRunIdentityService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    val runId: String = UUID.randomUUID().toString()

    override fun close() = Unit
}

val benchmarkRunIdentityService = gradle.sharedServices.registerIfAbsent(
    "benchmarkRunIdentity",
    BenchmarkRunIdentityService::class,
) {}

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

fun benchmarkIncludesProperty(propertyName: String, defaultIncludes: List<String>): List<String> {
    return providers.gradleProperty(propertyName)
        .map { value ->
            val includes = value.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (includes.isEmpty()) {
                throw GradleException("Gradle property $propertyName must contain at least one benchmark include.")
            }
            includes
        }
        .getOrElse(defaultIncludes)
}

fun benchmarkModesProperty(propertyName: String, defaultModes: List<String>): List<String> {
    return providers.gradleProperty(propertyName)
        .map { value ->
            val modes = value.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (modes.isEmpty()) {
                throw GradleException("Gradle property $propertyName must contain at least one benchmark mode.")
            }
            val unsupportedModes = modes - setOf("thrpt", "avgt")
            if (unsupportedModes.isNotEmpty()) {
                throw GradleException(
                    "Gradle property $propertyName contains unsupported benchmark modes: " +
                        unsupportedModes.joinToString(",")
                )
            }
            modes
        }
        .getOrElse(defaultModes)
}

val benchmarkJvmArgs = listOf(
    "-Xmx4g",
    "-Xms4g",
    "-XX:+UseG1GC",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+DebugNonSafepoints",
    "-XX:+AlwaysPreTouch",
)

val smokeBenchmarkJvmArgs = listOf(
    "-Xmx512m",
    "-Xms512m",
    "-XX:+UseG1GC",
)

val quickBenchmarkJvmArgs = listOf(
    "-Xmx1g",
    "-Xms1g",
    "-XX:+UseG1GC",
)

val asyncBenchmarkJvmArgs = listOf(
    "-Xmx2g",
    "-Xms2g",
    "-XX:+UseG1GC",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+DebugNonSafepoints",
)

val smokeProfile = BenchmarkRunProfile(
    id = "smoke",
    warmupIterations = 0,
    warmupTime = null,
    measurementIterations = 1,
    measurementTime = "1s",
    forks = 1,
    threads = listOf(1),
    benchmarkModes = listOf("thrpt"),
    jvmArgs = smokeBenchmarkJvmArgs,
    parameters = mapOf(
        "scenario" to "ceiling",
        "schedulerStrategy" to "IMMEDIATE",
    ),
    includeGcProfiler = false,
    includeAsyncProfiler = false,
)

val quickProfile = BenchmarkRunProfile(
    id = "quick",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 2,
    measurementTime = "3s",
    forks = 1,
    threads = benchmarkThreadsProperty("benchmarkQuickThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt"),
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
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
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val baselineE2EProfile = BenchmarkRunProfile(
    id = "baseline",
    warmupIterations = 2,
    warmupTime = "3s",
    measurementIterations = 3,
    measurementTime = "5s",
    forks = 2,
    threads = benchmarkThreadsProperty("benchmarkBaselineThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt"),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val latencyE2EProfile = BenchmarkRunProfile(
    id = "latency",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 3,
    measurementTime = "3s",
    forks = 2,
    threads = benchmarkThreadsProperty("benchmarkLatencyThreads", listOf(1)),
    benchmarkModes = listOf("avgt"),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val diagnosticComponentProfile = BenchmarkRunProfile(
    id = "diagnostic",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 3,
    measurementTime = "3s",
    forks = 1,
    threads = benchmarkThreadsProperty("benchmarkDiagnosticThreads", listOf(1)),
    benchmarkModes = benchmarkModesProperty("benchmarkDiagnosticModes", listOf("thrpt")),
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val exhaustiveComponentProfile = BenchmarkRunProfile(
    id = "exhaustive",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 2,
    measurementTime = "3s",
    forks = 1,
    threads = benchmarkThreadsProperty("benchmarkExhaustiveThreads", listOf(1)),
    benchmarkModes = benchmarkModesProperty("benchmarkExhaustiveModes", listOf("thrpt")),
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val exhaustiveWebFluxProfile = BenchmarkRunProfile(
    id = "exhaustive",
    warmupIterations = 1,
    warmupTime = "3s",
    measurementIterations = 3,
    measurementTime = "5s",
    forks = 1,
    threads = benchmarkThreadsProperty("benchmarkExhaustiveWebFluxThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt", "avgt"),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val baselineInfrastructureProfile = BenchmarkRunProfile(
    id = "baseline",
    warmupIterations = 2,
    warmupTime = "5s",
    measurementIterations = 3,
    measurementTime = "10s",
    forks = 2,
    threads = benchmarkThreadsProperty("benchmarkBaselineInfrastructureThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt", "avgt"),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val asyncProfile = BenchmarkRunProfile(
    id = "async",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 2,
    measurementTime = "3s",
    forks = 1,
    threads = benchmarkThreadsProperty("benchmarkAsyncThreads", listOf(1)),
    benchmarkModes = listOf("thrpt"),
    jvmArgs = asyncBenchmarkJvmArgs,
    includeGcProfiler = false,
    includeAsyncProfiler = true,
)

val smokeSuite = BenchmarkSuite(
    id = "smoke",
    displayName = "Smoke",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIdComponentBenchmark.createAggregateId",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark.createCommandMessage",
        "me.ahoo.wow.benchmark.component.AccessorComponentBenchmark.functionAccessorInvoke1",
        "me.ahoo.wow.benchmark.component.SerializationComponentBenchmark.commandSerializeDeserialize",
        "me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark.sendAndWaitProcessed",
        "me.ahoo.wow.benchmark.webflux.WebFluxSmokeBenchmark.monoCommandResultServerResponseOnly",
    ),
    resultFileName = "benchmark-smoke.json",
    humanFileName = "benchmark-smoke-human.txt",
    requiredForGroupedReport = false,
    performanceConclusionSource = false,
)

val frameworkE2ESuite = BenchmarkSuite(
    id = "framework-e2e",
    displayName = "Primary Framework E2E",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark",
        "me.ahoo.wow.benchmark.e2e.CommandSendE2EBenchmark",
    ),
    resultFileName = "framework-e2e.json",
    humanFileName = "framework-e2e-human.txt",
    requiredForGroupedReport = true,
    performanceConclusionSource = true,
)

val infrastructureE2ESuite = BenchmarkSuite(
    id = "infrastructure-e2e",
    displayName = "Infrastructure E2E",
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
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIdComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AccessorComponentBenchmark",
        "me.ahoo.wow.benchmark.component.CommandValidationComponentBenchmark",
        "me.ahoo.wow.benchmark.component.IdempotencyComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AggregateLoadComponentBenchmark",
        "me.ahoo.wow.benchmark.component.AggregateRepositoryLoadComponentBenchmark",
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

val webFluxSuite = BenchmarkSuite(
    id = "webflux",
    displayName = "WebFlux Adapter",
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

val quickComponentSuite = componentSuite.copy(
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.CommandIdComponentBenchmark.generateGlobalIdAndCreateAggregateId",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark.createCommandMessage",
        "me.ahoo.wow.benchmark.component.CommandMessageComponentBenchmark.readCommandMessageProperties",
        "me.ahoo.wow.benchmark.component.CommandValidationComponentBenchmark.validateCommandBody",
        "me.ahoo.wow.benchmark.component.IdempotencyComponentBenchmark.checkKnownRequestId",
        "me.ahoo.wow.benchmark.component.AggregateLoadComponentBenchmark.recoverConstantSizeStateFromEvents",
        "me.ahoo.wow.benchmark.component.AggregateRepositoryLoadComponentBenchmark.loadEmptyStateAggregate",
        "me.ahoo.wow.benchmark.component.AggregateRepositoryLoadComponentBenchmark.loadSnapshot",
        "me.ahoo.wow.benchmark.component.AggregateHandleComponentBenchmark.processCommandAggregate",
        "me.ahoo.wow.benchmark.component.EventStoreComponentBenchmark.appendInMemoryNewAggregateEventStream",
        "me.ahoo.wow.benchmark.component.EventStoreComponentBenchmark.appendNoopEventStream",
        "me.ahoo.wow.benchmark.component.EventPublishComponentBenchmark.publishDomainEventStream",
        "me.ahoo.wow.benchmark.component.WaitNotifyComponentBenchmark.registerWaitRegistration",
        "me.ahoo.wow.benchmark.component.WaitNotifyComponentBenchmark.notifyProcessed",
        "me.ahoo.wow.benchmark.component.WaitNotifyComponentBenchmark.waitForProcessed",
        "me.ahoo.wow.benchmark.component.SerializationComponentBenchmark.commandSerializeDeserialize",
        "me.ahoo.wow.benchmark.component.SerializationComponentBenchmark.eventStreamSerializeDeserialize",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateOnly",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateWithoutRetry",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndSendDomainStateEvents",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithoutWait",
        "me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndNotifyProcessedWithLocalWait",
        "me.ahoo.wow.benchmark.component.CommandDispatcherChainComponentBenchmark.dispatchSingleHotAggregateThroughChain",
        "me.ahoo.wow.benchmark.component.MongoDocumentComponentBenchmark.eventStreamToDocument",
    )
)

val quickComponentProfile = quickProfile.copy(
    threads = benchmarkThreadsProperty("benchmarkQuickComponentThreads", listOf(1)),
    parameters = mapOf(
        "eventCount" to "10,500",
        "handlerCost" to "NOOP",
    )
)

val diagnosticComponentSuite = quickComponentSuite.copy(
    includeClasses = benchmarkIncludesProperty(
        "benchmarkDiagnosticComponentIncludes",
        quickComponentSuite.includeClasses,
    ),
)

val diagnosticComponentRunProfile = diagnosticComponentProfile.copy(
    parameters = quickComponentProfile.parameters,
)

val asyncE2ESuite = frameworkE2ESuite.copy(
    includeClasses = benchmarkIncludesProperty(
        "benchmarkAsyncE2EIncludes",
        frameworkE2ESuite.includeClasses,
    ),
)

val asyncComponentSuite = quickComponentSuite.copy(
    includeClasses = benchmarkIncludesProperty(
        "benchmarkAsyncComponentIncludes",
        quickComponentSuite.includeClasses,
    ),
)

val asyncComponentProfile = asyncProfile.copy(parameters = quickComponentProfile.parameters)

val asyncWebFluxSuite = quickWebFluxSuite.copy(
    includeClasses = benchmarkIncludesProperty(
        "benchmarkAsyncWebFluxIncludes",
        quickWebFluxSuite.includeClasses,
    ),
)

val smokeTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkSmoke",
    suite = smokeSuite,
    profile = smokeProfile,
    description = "Runs the bounded cross-layer JMH verification catalog.",
    taskGroup = "verification",
)

val quickE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickE2E",
    suite = frameworkE2ESuite,
    profile = quickProfile,
    description = "Runs the bounded Framework E2E feedback catalog.",
)

val baselineE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkBaselineE2E",
    suite = frameworkE2ESuite,
    profile = baselineE2EProfile,
    description = "Runs the formal Framework E2E throughput and allocation baseline.",
)

val latencyE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkLatencyE2E",
    suite = frameworkE2ESuite,
    profile = latencyE2EProfile,
    description = "Runs the optional Framework E2E average-latency profile.",
)

val quickInfrastructureE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickInfrastructureE2E",
    suite = infrastructureE2ESuite,
    profile = quickProfile,
    description = "Runs the bounded infrastructure feedback catalog.",
)

val baselineInfrastructureE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkBaselineInfrastructureE2E",
    suite = infrastructureE2ESuite,
    profile = baselineInfrastructureProfile,
    description = "Runs the formal Redis and Mongo infrastructure baseline.",
)

val quickComponentTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickComponent",
    suite = quickComponentSuite,
    profile = quickComponentProfile,
    description = "Runs the representative Component feedback catalog.",
)

val diagnosticComponentTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkDiagnosticComponent",
    suite = diagnosticComponentSuite,
    profile = diagnosticComponentRunProfile,
    description = "Runs the selected Component diagnostic catalog.",
)

val exhaustiveComponentTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkExhaustiveComponent",
    suite = componentSuite,
    profile = exhaustiveComponentProfile,
    description = "Runs every Component workload with the bounded exhaustive profile.",
)

val quickWebFluxTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickWebFlux",
    suite = quickWebFluxSuite,
    profile = quickWebFluxProfile,
    description = "Runs the representative WebFlux adapter feedback catalog.",
)

val exhaustiveWebFluxTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkExhaustiveWebFlux",
    suite = webFluxSuite,
    profile = exhaustiveWebFluxProfile,
    description = "Runs the complete WebFlux adapter catalog.",
)

val asyncE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkAsyncE2E",
    suite = asyncE2ESuite,
    profile = asyncProfile,
    description = "Profiles selected Framework E2E workloads with AsyncProfiler.",
)

val asyncComponentTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkAsyncComponent",
    suite = asyncComponentSuite,
    profile = asyncComponentProfile,
    description = "Profiles selected Component workloads with AsyncProfiler.",
)

val asyncWebFluxTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkAsyncWebFlux",
    suite = asyncWebFluxSuite,
    profile = asyncProfile,
    description = "Profiles selected WebFlux adapter workloads with AsyncProfiler.",
)

val benchmarkTaskSpecs = listOf(
    smokeTaskSpec,
    quickE2ETaskSpec,
    baselineE2ETaskSpec,
    latencyE2ETaskSpec,
    quickInfrastructureE2ETaskSpec,
    baselineInfrastructureE2ETaskSpec,
    quickComponentTaskSpec,
    diagnosticComponentTaskSpec,
    exhaustiveComponentTaskSpec,
    quickWebFluxTaskSpec,
    exhaustiveWebFluxTaskSpec,
    asyncE2ETaskSpec,
    asyncComponentTaskSpec,
    asyncWebFluxTaskSpec,
)

val baselineReportTaskSpecs = listOf(
    baselineE2ETaskSpec,
    baselineInfrastructureE2ETaskSpec,
    exhaustiveComponentTaskSpec,
    exhaustiveWebFluxTaskSpec,
)

val quickReportTaskSpecs = listOf(
    quickE2ETaskSpec,
    quickInfrastructureE2ETaskSpec,
    quickComponentTaskSpec,
    quickWebFluxTaskSpec,
)

fun benchmarkIncludePattern(includes: List<String>): String {
    return includes.joinToString("|") { Regex.escape(it) + ".*" }
}

val benchmarkAsyncProfilerLib = providers.gradleProperty("benchmarkAsyncProfilerLib")
    .map { libraryPath -> file(libraryPath) }
    .getOrElse(file("/opt/async-profiler/lib/libasyncProfiler.dylib"))

fun benchmarkProfilingDirectory(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): File {
    return layout.buildDirectory
        .dir("profiling/${profile.id}/${suite.id}/threads-$threads")
        .get()
        .asFile
}

fun benchmarkProfilerArgs(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): List<String> {
    if (!profile.includeGcProfiler && !profile.includeAsyncProfiler) {
        return emptyList()
    }
    return buildList {
        if (profile.includeGcProfiler) {
            add("-prof")
            add("gc")
        }
        if (profile.includeAsyncProfiler) {
            val profilingDirectory = benchmarkProfilingDirectory(profile, suite, threads)
            add("-prof")
            add(
                "async:output=flamegraph;dir=${profilingDirectory.absolutePath};event=cpu;" +
                    "libPath=${benchmarkAsyncProfilerLib.absolutePath}"
            )
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

fun suiteManifestFile(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): Provider<RegularFile> {
    val resultBaseName = suite.resultFileName.removeSuffix(".json")
    return providers.provider {
        layout.projectDirectory.file(
            "results/jmh/${profile.id}/${suite.id}/threads-$threads-$resultBaseName.manifest.json"
        )
    }
}

fun suiteInProgressManifestFile(
    profile: BenchmarkRunProfile,
    suite: BenchmarkSuite,
    threads: Int,
): Provider<RegularFile> {
    val resultBaseName = suite.resultFileName.removeSuffix(".json")
    return providers.provider {
        layout.projectDirectory.file(
            "results/jmh/${profile.id}/${suite.id}/threads-$threads-$resultBaseName.manifest.in-progress.json"
        )
    }
}

fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun writePrettyJson(file: File, value: Any) {
    file.parentFile.mkdirs()
    file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)))
}

fun publishJsonAtomically(file: File, value: Any) {
    val temporaryFile = File(file.parentFile, "${file.name}.tmp")
    writePrettyJson(temporaryFile, value)
    runCatching {
        Files.move(
            temporaryFile.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.getOrElse {
        Files.move(
            temporaryFile.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

fun requestedBenchmarkProfilers(profile: BenchmarkRunProfile): List<String> {
    return buildList {
        if (profile.includeGcProfiler) {
            add("gc")
        }
        if (profile.includeAsyncProfiler) {
            add("async")
        }
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
    appendLine("## Report Generation Environment")
    appendLine("- **Version**: $version")
    appendLine("- **JVM**: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
    appendLine("- **OS**: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
    appendLine("- **Generated At**: ${reportDateTime()}")
    appendLine("- **CPU Cores**: ${Runtime.getRuntime().availableProcessors()}")
    appendLine("- **Physical Memory**: ${formatMemoryBytes(physicalMemoryBytes())}")
    if (profile != null) {
        appendLine("- **Benchmark JVM Args**: `${profile.jvmArgs.joinToString(" ")}`")
        appendLine("- **JMH Config**: ${profile.configSummary()}")
    } else {
        appendLine("- **Benchmark JVM Args**: see per-suite Run Profiles below")
    }
    appendLine()
}

fun StringBuilder.appendBenchmarkRunProvenance(manifests: List<ParsedBenchmarkRunManifest>) {
    validateBenchmarkRunManifests(
        manifests = manifests,
        context = "grouped benchmark report",
        requireSameRunId = false,
    )
    val reference = manifests.first()
    appendLine("## Benchmark Run Provenance")
    appendLine("- **Source Commit**: `${reference.sourceCommit}`")
    appendLine("- **Source Dirty**: `${reference.sourceDirty}`")
    appendLine("- **Project Version**: `${reference.projectVersion}`")
    appendLine("- **JMH Jar SHA-256**: `${reference.jmhJarSha256}`")
    appendLine("- **Runtime JVM**: ${reference.vmName} ${reference.vmVersion} / Java ${reference.javaVersion}")
    appendLine("- **Runtime OS**: ${reference.osName} ${reference.osVersion} ${reference.osArch}")
    appendLine("- **CPU Cores**: ${reference.availableProcessors}")
    appendLine("- **Physical Memory**: ${formatMemoryBytes(reference.physicalMemoryBytes)}")
    appendLine()
    appendLine("| Suite | Profile | Threads | Run ID | Started | Completed | Profilers | Rows | Result SHA-256 |")
    appendLine("|-------|---------|---------|--------|---------|-----------|-----------|------|----------------|")
    manifests.sortedWith(compareBy({ it.suite }, { it.threads })).forEach { manifest ->
        val profilers = manifest.resolvedProfilerArgs.ifEmpty { listOf("none") }.joinToString(" ")
        appendLine(
            "| ${manifest.suite} | ${manifest.profile} | ${manifest.threads} | `${manifest.runId}` | " +
                "${manifest.startedAt} | ${manifest.completedAt} | `${profilers}` | " +
                "${manifest.resultRowCount} | `${manifest.resultSha256}` |"
        )
    }
    appendLine()
}

fun StringBuilder.appendInfrastructureRuntime() {
    appendLine("## Report-Time Infrastructure Runtime")
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

fun registerBenchmarkThreadTask(
    taskName: String,
    suite: BenchmarkSuite,
    profile: BenchmarkRunProfile,
    threads: Int,
): TaskProvider<JavaExec> {
    val jmhJar = tasks.named<Jar>("jmhJar")
    val resultFile = suiteResultFile(profile, suite, threads)
    val humanFile = suiteHumanFile(profile, suite, threads)
    val manifestFile = suiteManifestFile(profile, suite, threads)
    val inProgressManifestFile = suiteInProgressManifestFile(profile, suite, threads)
    return tasks.register<JavaExec>(taskName) {
        description = "Runs ${suite.displayName} JMH benchmarks with ${profile.id} profile and $threads thread(s)."
        dependsOn(jmhJar)
        usesService(benchmarkRunIdentityService)
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
            profile.parameters.forEach { (name, value) ->
                add("-p")
                add("$name=$value")
            }
            add("-rf")
            add("json")
            add("-rff")
            add(resultFile.get().asFile.absolutePath)
            add("-o")
            add(humanFile.get().asFile.absolutePath)
            add("-jvmArgs")
            add(profile.jvmArgs.joinToString(" "))
        }
        val profilerArgs = benchmarkProfilerArgs(profile, suite, threads)
        val profilingDirectory = benchmarkProfilingDirectory(profile, suite, threads)
        args(jmhArgs)
        args(profilerArgs)

        outputs.file(resultFile)
        outputs.file(humanFile)
        outputs.file(manifestFile)
        if (profile.includeAsyncProfiler) {
            outputs.dir(profilingDirectory)
        }
        outputs.upToDateWhen { false }

        doFirst {
            val result = resultFile.get().asFile
            val human = humanFile.get().asFile
            val manifest = manifestFile.get().asFile
            val inProgressManifest = inProgressManifestFile.get().asFile
            manifest.delete()
            result.delete()
            human.delete()
            inProgressManifest.delete()
            result.parentFile.mkdirs()
            human.parentFile.mkdirs()
            if (profile.includeAsyncProfiler) {
                if (!benchmarkAsyncProfilerLib.isFile) {
                    throw GradleException(
                        "AsyncProfiler library is required for $path: ${benchmarkAsyncProfilerLib.absolutePath}. " +
                            "Set -PbenchmarkAsyncProfilerLib=/path/to/libasyncProfiler.dylib."
                    )
                }
                project.delete(profilingDirectory)
                profilingDirectory.mkdirs()
            }

            val gitRoot = rootProject.projectDir.absolutePath
            val commitOutput = runCommand(listOf("git", "-C", gitRoot, "rev-parse", "HEAD"))
            if (commitOutput.exitCode != 0 || commitOutput.output.isBlank()) {
                throw GradleException("Unable to resolve benchmark source commit: ${commitOutput.output}")
            }
            val statusOutput = runCommand(
                listOf("git", "-C", gitRoot, "status", "--porcelain", "--untracked-files=normal")
            )
            if (statusOutput.exitCode != 0) {
                throw GradleException("Unable to resolve benchmark source status: ${statusOutput.output}")
            }
            val jmhJarFile = jmhJar.get().archiveFile.get().asFile
            val startedAt = Instant.now().toString()
            val inProgress = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "status" to "IN_PROGRESS",
                "runId" to benchmarkRunIdentityService.get().runId,
                "taskPath" to path,
                "startedAt" to startedAt,
                "projectVersion" to project.version.toString(),
                "source" to linkedMapOf(
                    "commit" to commitOutput.output,
                    "dirty" to statusOutput.output.isNotBlank(),
                    "jmhJarSha256" to fileSha256(jmhJarFile),
                ),
                "runSpec" to linkedMapOf(
                    "suite" to suite.id,
                    "profile" to profile.id,
                    "threads" to threads,
                    "includePattern" to benchmarkIncludePattern(suite.includeClasses),
                    "modes" to profile.benchmarkModes,
                    "warmupIterations" to profile.warmupIterations,
                    "warmupTime" to profile.warmupTime,
                    "measurementIterations" to profile.measurementIterations,
                    "measurementTime" to profile.measurementTime,
                    "forks" to profile.forks,
                    "parameters" to profile.parameters,
                    "jvmArgs" to profile.jvmArgs,
                    "requestedProfilers" to requestedBenchmarkProfilers(profile),
                    "resolvedJmhArgs" to jmhArgs,
                    "resolvedProfilerArgs" to profilerArgs,
                    "requiredServices" to suite.requiredServices.map { requiredService ->
                        linkedMapOf(
                            "service" to requiredService.service,
                            "host" to requiredService.host,
                            "port" to requiredService.port,
                        )
                    },
                ),
                "runtime" to linkedMapOf(
                    "javaVersion" to System.getProperty("java.version"),
                    "vmName" to System.getProperty("java.vm.name"),
                    "vmVersion" to System.getProperty("java.vm.version"),
                    "javaExecutable" to javaLauncher.orNull?.executablePath?.asFile?.absolutePath,
                    "osName" to System.getProperty("os.name"),
                    "osVersion" to System.getProperty("os.version"),
                    "osArch" to System.getProperty("os.arch"),
                    "availableProcessors" to Runtime.getRuntime().availableProcessors(),
                    "physicalMemoryBytes" to physicalMemoryBytes(),
                ),
            )
            writePrettyJson(inProgressManifest, inProgress)
            environment(benchmarkDockerRuntimeEnvironment())
            suite.requiredServices.forEach { requiredService ->
                requireBenchmarkService(requiredService.service, requiredService.host, requiredService.port)
            }
        }

        doLast {
            val result = resultFile.get().asFile
            val human = humanFile.get().asFile
            val manifest = manifestFile.get().asFile
            val inProgressManifest = inProgressManifestFile.get().asFile
            if (!result.isFile || result.length() == 0L) {
                throw GradleException("JMH result file is missing or empty: ${result.absolutePath}")
            }
            if (!human.isFile || human.length() == 0L) {
                throw GradleException("JMH human output is missing or empty: ${human.absolutePath}")
            }
            val parsedResults = JsonSlurper().parseText(result.readText()) as? List<*>
                ?: throw GradleException("JMH result must be a JSON array: ${result.absolutePath}")
            if (parsedResults.isEmpty()) {
                throw GradleException("JMH result contains no benchmark rows: ${result.absolutePath}")
            }
            @Suppress("UNCHECKED_CAST")
            val manifestData = LinkedHashMap(
                JsonSlurper().parseText(inProgressManifest.readText()) as Map<String, Any?>
            )
            manifestData["status"] = "SUCCESS"
            manifestData["completedAt"] = Instant.now().toString()
            manifestData["artifacts"] = linkedMapOf(
                "result" to linkedMapOf(
                    "path" to result.name,
                    "size" to result.length(),
                    "sha256" to fileSha256(result),
                    "rowCount" to parsedResults.size,
                ),
                "human" to linkedMapOf(
                    "path" to human.name,
                    "size" to human.length(),
                    "sha256" to fileSha256(human),
                ),
            )
            publishJsonAtomically(manifest, manifestData)
            inProgressManifest.delete()
        }
    }
}

fun registerBenchmarkTask(taskSpec: BenchmarkTaskSpec) {
    val taskName = taskSpec.taskName
    val suite = taskSpec.suite
    val profile = taskSpec.profile
    val threadTasks = profile.threads.map { threads ->
        registerBenchmarkThreadTask("${taskName}Thread$threads", suite, profile, threads)
    }
    tasks.register(taskName) {
        description = taskSpec.description
        group = taskSpec.taskGroup
        dependsOn(threadTasks)
        doLast {
            parseBenchmarkGroup(
                parser = JsonSlurper(),
                group = benchmarkResultGroup(taskSpec),
            )
        }
    }
}

benchmarkTaskSpecs.forEach(::registerBenchmarkTask)

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
val baselineGroupedBenchmarkReport = reportsDir.file("baseline-grouped.md")
val quickGroupedBenchmarkReport = reportsDir.file("quick-grouped.md")

data class BenchmarkResultFile(
    val threads: Int,
    val resultFile: Provider<RegularFile>,
    val humanFile: Provider<RegularFile>,
    val manifestFile: Provider<RegularFile>,
)

data class BenchmarkResultGroup(
    val taskSpec: BenchmarkTaskSpec,
    val resultFiles: List<BenchmarkResultFile>,
) {
    val suite: BenchmarkSuite
        get() = taskSpec.suite
    val profile: BenchmarkRunProfile
        get() = taskSpec.profile
}

data class GroupedBenchmarkReportSpec(
    val label: String,
    val expectedProfileIds: Set<String>,
    val performanceConclusionSource: Boolean,
)

data class BenchmarkGroupReport(
    val group: BenchmarkResultGroup,
    val rows: List<ParsedBenchmarkResult>,
    val manifests: List<ParsedBenchmarkRunManifest>,
    val sourceRowCount: Int = rows.size,
    val unavailableReason: String? = null,
)

data class ParsedBenchmarkRunManifest(
    val runId: String,
    val taskPath: String,
    val startedAt: String,
    val completedAt: String,
    val projectVersion: String,
    val sourceCommit: String,
    val sourceDirty: Boolean,
    val jmhJarSha256: String,
    val suite: String,
    val profile: String,
    val threads: Int,
    val jvmArgs: List<String>,
    val requestedProfilers: List<String>,
    val resolvedProfilerArgs: List<String>,
    val javaVersion: String,
    val vmName: String,
    val vmVersion: String,
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val availableProcessors: Int,
    val physicalMemoryBytes: Long?,
    val resultSha256: String,
    val resultRowCount: Int,
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

fun benchmarkResultGroup(taskSpec: BenchmarkTaskSpec): BenchmarkResultGroup {
    val suite = taskSpec.suite
    val profile = taskSpec.profile
    return BenchmarkResultGroup(
        taskSpec = taskSpec,
        resultFiles = profile.threads.map { threads ->
            BenchmarkResultFile(
                threads = threads,
                resultFile = suiteResultFile(profile, suite, threads),
                humanFile = suiteHumanFile(profile, suite, threads),
                manifestFile = suiteManifestFile(profile, suite, threads),
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

fun manifestMap(container: Map<*, *>, key: String, source: String): Map<*, *> {
    return container[key] as? Map<*, *>
        ?: throw GradleException("Benchmark manifest is missing object '$key': $source")
}

fun manifestString(container: Map<*, *>, key: String, source: String): String {
    return (container[key] as? String)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Benchmark manifest is missing string '$key': $source")
}

fun manifestInt(container: Map<*, *>, key: String, source: String): Int {
    return (container[key] as? Number)?.toInt()
        ?: throw GradleException("Benchmark manifest is missing integer '$key': $source")
}

fun manifestStringList(container: Map<*, *>, key: String, source: String): List<String> {
    val values = container[key] as? List<*>
        ?: throw GradleException("Benchmark manifest is missing array '$key': $source")
    return values.mapIndexed { index, value ->
        value as? String
            ?: throw GradleException("Benchmark manifest '$key[$index]' must be a string: $source")
    }
}

fun manifestStringMap(container: Map<*, *>, key: String, source: String): Map<String, String> {
    val values = container[key] as? Map<*, *>
        ?: throw GradleException("Benchmark manifest is missing object '$key': $source")
    return values.entries.associate { (entryKey, entryValue) ->
        val stringKey = entryKey as? String
            ?: throw GradleException("Benchmark manifest '$key' contains a non-string key: $source")
        val stringValue = entryValue as? String
            ?: throw GradleException("Benchmark manifest '$key.$stringKey' must be a string: $source")
        stringKey to stringValue
    }
}

fun requireManifestValue(actual: Any?, expected: Any?, field: String, source: String) {
    if (actual != expected) {
        throw GradleException(
            "Benchmark manifest '$field' mismatch in $source: expected [$expected], found [$actual]."
        )
    }
}

fun parseBenchmarkRunManifest(
    parser: JsonSlurper,
    group: BenchmarkResultGroup,
    resultSource: BenchmarkResultFile,
): ParsedBenchmarkRunManifest {
    val resultFile = resultSource.resultFile.get().asFile
    val humanFile = resultSource.humanFile.get().asFile
    val manifestFile = resultSource.manifestFile.get().asFile
    if (!manifestFile.isFile) {
        throw GradleException(
            "Benchmark run manifest not found for ${group.suite.displayName}: ${manifestFile.absolutePath}. " +
                "Rerun ${group.taskSpec.taskName}; raw JMH JSON without provenance is not accepted."
        )
    }
    val sourcePath = manifestFile.absolutePath
    val manifest = parser.parseText(manifestFile.readText()) as? Map<*, *>
        ?: throw GradleException("Benchmark manifest must be a JSON object: $sourcePath")
    requireManifestValue((manifest["schemaVersion"] as? Number)?.toInt(), 1, "schemaVersion", sourcePath)
    requireManifestValue(manifest["status"], "SUCCESS", "status", sourcePath)

    val source = manifestMap(manifest, "source", sourcePath)
    val runSpec = manifestMap(manifest, "runSpec", sourcePath)
    val runtime = manifestMap(manifest, "runtime", sourcePath)
    val artifacts = manifestMap(manifest, "artifacts", sourcePath)
    val resultArtifact = manifestMap(artifacts, "result", sourcePath)
    val humanArtifact = manifestMap(artifacts, "human", sourcePath)

    requireManifestValue(manifestString(runSpec, "suite", sourcePath), group.suite.id, "runSpec.suite", sourcePath)
    requireManifestValue(manifestString(runSpec, "profile", sourcePath), group.profile.id, "runSpec.profile", sourcePath)
    requireManifestValue(manifestInt(runSpec, "threads", sourcePath), resultSource.threads, "runSpec.threads", sourcePath)
    requireManifestValue(
        manifestString(runSpec, "includePattern", sourcePath),
        benchmarkIncludePattern(group.suite.includeClasses),
        "runSpec.includePattern",
        sourcePath,
    )
    requireManifestValue(
        manifestStringList(runSpec, "modes", sourcePath),
        group.profile.benchmarkModes,
        "runSpec.modes",
        sourcePath,
    )
    requireManifestValue(
        manifestInt(runSpec, "warmupIterations", sourcePath),
        group.profile.warmupIterations,
        "runSpec.warmupIterations",
        sourcePath,
    )
    requireManifestValue(runSpec["warmupTime"], group.profile.warmupTime, "runSpec.warmupTime", sourcePath)
    requireManifestValue(
        manifestInt(runSpec, "measurementIterations", sourcePath),
        group.profile.measurementIterations,
        "runSpec.measurementIterations",
        sourcePath,
    )
    requireManifestValue(
        manifestString(runSpec, "measurementTime", sourcePath),
        group.profile.measurementTime,
        "runSpec.measurementTime",
        sourcePath,
    )
    requireManifestValue(manifestInt(runSpec, "forks", sourcePath), group.profile.forks, "runSpec.forks", sourcePath)
    requireManifestValue(
        manifestStringMap(runSpec, "parameters", sourcePath),
        group.profile.parameters,
        "runSpec.parameters",
        sourcePath,
    )
    requireManifestValue(
        manifestStringList(runSpec, "jvmArgs", sourcePath),
        group.profile.jvmArgs,
        "runSpec.jvmArgs",
        sourcePath,
    )
    requireManifestValue(
        manifestStringList(runSpec, "requestedProfilers", sourcePath),
        requestedBenchmarkProfilers(group.profile),
        "runSpec.requestedProfilers",
        sourcePath,
    )

    if (!resultFile.isFile || !humanFile.isFile) {
        throw GradleException("Benchmark artifacts referenced by manifest are missing: $sourcePath")
    }
    requireManifestValue(manifestString(resultArtifact, "path", sourcePath), resultFile.name, "artifacts.result.path", sourcePath)
    requireManifestValue(
        (resultArtifact["size"] as? Number)?.toLong(),
        resultFile.length(),
        "artifacts.result.size",
        sourcePath,
    )
    val resultSha256 = manifestString(resultArtifact, "sha256", sourcePath)
    requireManifestValue(resultSha256, fileSha256(resultFile), "artifacts.result.sha256", sourcePath)
    val parsedResultRows = parser.parseText(resultFile.readText()) as? List<*>
        ?: throw GradleException("JMH result must be a JSON array: ${resultFile.absolutePath}")
    val resultRowCount = manifestInt(resultArtifact, "rowCount", sourcePath)
    requireManifestValue(resultRowCount, parsedResultRows.size, "artifacts.result.rowCount", sourcePath)
    requireManifestValue(manifestString(humanArtifact, "path", sourcePath), humanFile.name, "artifacts.human.path", sourcePath)
    requireManifestValue(
        (humanArtifact["size"] as? Number)?.toLong(),
        humanFile.length(),
        "artifacts.human.size",
        sourcePath,
    )
    requireManifestValue(
        manifestString(humanArtifact, "sha256", sourcePath),
        fileSha256(humanFile),
        "artifacts.human.sha256",
        sourcePath,
    )

    return ParsedBenchmarkRunManifest(
        runId = manifestString(manifest, "runId", sourcePath),
        taskPath = manifestString(manifest, "taskPath", sourcePath),
        startedAt = manifestString(manifest, "startedAt", sourcePath),
        completedAt = manifestString(manifest, "completedAt", sourcePath),
        projectVersion = manifestString(manifest, "projectVersion", sourcePath),
        sourceCommit = manifestString(source, "commit", sourcePath),
        sourceDirty = source["dirty"] as? Boolean
            ?: throw GradleException("Benchmark manifest is missing boolean 'source.dirty': $sourcePath"),
        jmhJarSha256 = manifestString(source, "jmhJarSha256", sourcePath),
        suite = manifestString(runSpec, "suite", sourcePath),
        profile = manifestString(runSpec, "profile", sourcePath),
        threads = manifestInt(runSpec, "threads", sourcePath),
        jvmArgs = manifestStringList(runSpec, "jvmArgs", sourcePath),
        requestedProfilers = manifestStringList(runSpec, "requestedProfilers", sourcePath),
        resolvedProfilerArgs = manifestStringList(runSpec, "resolvedProfilerArgs", sourcePath),
        javaVersion = manifestString(runtime, "javaVersion", sourcePath),
        vmName = manifestString(runtime, "vmName", sourcePath),
        vmVersion = manifestString(runtime, "vmVersion", sourcePath),
        osName = manifestString(runtime, "osName", sourcePath),
        osVersion = manifestString(runtime, "osVersion", sourcePath),
        osArch = manifestString(runtime, "osArch", sourcePath),
        availableProcessors = manifestInt(runtime, "availableProcessors", sourcePath),
        physicalMemoryBytes = (runtime["physicalMemoryBytes"] as? Number)?.toLong(),
        resultSha256 = resultSha256,
        resultRowCount = resultRowCount,
    )
}

fun validateBenchmarkRunManifests(
    manifests: List<ParsedBenchmarkRunManifest>,
    context: String,
    requireSameRunId: Boolean,
) {
    if (manifests.isEmpty()) {
        throw GradleException("No benchmark run manifests were available for $context.")
    }
    val comparableFields = linkedMapOf<String, List<Any?>>(
        "source commit" to manifests.map { it.sourceCommit },
        "source dirty state" to manifests.map { it.sourceDirty },
        "JMH jar SHA-256" to manifests.map { it.jmhJarSha256 },
        "project version" to manifests.map { it.projectVersion },
        "Java version" to manifests.map { it.javaVersion },
        "VM" to manifests.map { "${it.vmName} ${it.vmVersion}" },
        "OS" to manifests.map { "${it.osName} ${it.osVersion} ${it.osArch}" },
    )
    if (requireSameRunId) {
        comparableFields["run ID"] = manifests.map { it.runId }
    }
    comparableFields.forEach { (field, values) ->
        if (values.distinct().size != 1) {
            throw GradleException("Benchmark manifests mix different $field values for $context: ${values.distinct()}")
        }
    }
}

fun parseBenchmarkGroup(
    parser: JsonSlurper,
    group: BenchmarkResultGroup,
): BenchmarkGroupReport {
    val presentResults = group.resultFiles.filter { it.resultFile.get().asFile.exists() }
    if (presentResults.isEmpty()) {
        if (!group.suite.requiredForGroupedReport) {
            return BenchmarkGroupReport(
                group = group,
                rows = emptyList(),
                manifests = emptyList(),
                unavailableReason = "Status: unavailable. Result files were not present. " +
                    "Run ${group.taskSpec.taskName} to include this optional group.",
            )
        }
        val missingFiles = group.resultFiles.joinToString(", ") { it.resultFile.get().asFile.absolutePath }
        throw GradleException(
            "JMH results not found for ${group.suite.displayName}: $missingFiles. " +
            "Run ${group.taskSpec.taskName} first."
        )
    }
    if (presentResults.size != group.resultFiles.size) {
        val missingFiles = group.resultFiles
            .filterNot { it.resultFile.get().asFile.exists() }
            .joinToString(", ") { it.resultFile.get().asFile.absolutePath }
        throw GradleException(
            "Benchmark result set is incomplete for ${group.suite.displayName}: $missingFiles. " +
                "Run ${group.taskSpec.taskName} first."
        )
    }
    val manifests = presentResults.map { resultSource ->
        parseBenchmarkRunManifest(parser, group, resultSource)
    }
    validateBenchmarkRunManifests(
        manifests = manifests,
        context = "${group.suite.displayName}/${group.profile.id}",
        requireSameRunId = true,
    )
    val rows = presentResults.flatMap { resultSource ->
        parseBenchmarkResultFile(
            parser = parser,
            group = group,
            resultFile = resultSource.resultFile.get().asFile,
            threads = resultSource.threads,
        )
    }
    return BenchmarkGroupReport(
        group = group,
        rows = rows,
        manifests = manifests,
        sourceRowCount = rows.size,
    )
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
    spec: GroupedBenchmarkReportSpec,
    version: String,
): String {
    val parser = JsonSlurper()
    val reportProfiles = groups.map { it.profile }.distinctBy { it.id }
    val reportProfileIds = reportProfiles.map { it.id }.toSet()
    if (reportProfileIds != spec.expectedProfileIds) {
        throw GradleException(
            "${spec.label} grouped benchmark report requires run profile ids " +
                "${spec.expectedProfileIds}, found: $reportProfileIds"
        )
    }
    val reportProfileConfigs = groups.map { it.profile.configSummary() }.distinct()
    val hasMultipleRunProfiles = reportProfiles.size > 1 || reportProfileConfigs.size > 1
    val parsedGroups = groups.map { parseBenchmarkGroup(parser, it) }
    val allRows = parsedGroups.flatMap { it.rows }
    val allManifests = parsedGroups.flatMap { it.manifests }
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
    sb.appendLine("# ${spec.label} Grouped Benchmark Report")
    sb.appendLine()
    sb.appendLine("## Policy")
    if (spec.performanceConclusionSource) {
        sb.appendLine("- Baseline E2E results are the performance conclusion source.")
    } else {
        sb.appendLine(
            "- ${spec.label} results are directional feedback; run Baseline E2E before updating baselines " +
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
    sb.appendBenchmarkRunProvenance(allManifests)
    sb.appendBenchmarkEnvironment(
        version = version,
        profile = if (hasMultipleRunProfiles) null else reportProfiles.singleOrNull(),
    )
    if (infrastructureRows.isNotEmpty()) {
        sb.appendInfrastructureRuntime()
    }
    if (hasMultipleRunProfiles) {
        sb.appendLine("## Run Profiles")
        sb.appendLine()
        groups.forEach { group ->
            sb.appendLine(
                "- **${group.suite.displayName}**: ${group.profile.configSummary()}, " +
                    "jvmArgs=`${group.profile.jvmArgs.joinToString(" ")}`"
            )
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
        sb.appendLine("- **Command**: `./gradlew :wow-benchmarks:${group.taskSpec.taskName}`")
        sb.appendLine("- **JMH Config**: ${group.profile.configSummary()}")
        val performanceConclusionSource =
            group.profile.id == baselineE2EProfile.id && group.suite.performanceConclusionSource
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
    return sb.toString().trimEnd() + "\n"
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
                "Run ${group.taskSpec.taskName} first."
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
    sb.appendBenchmarkRunProvenance(groupReport.manifests)
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
                "Run ${group.taskSpec.taskName} first."
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
    sb.appendBenchmarkRunProvenance(groupReport.manifests)
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

tasks.register("generateBenchmarkReport") {
    description = "Generate quick framework E2E benchmark report from JMH JSON results."
    group = "benchmark"
    mustRunAfter("benchmarkQuickE2E")
    outputs.file(benchmarkReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickE2ETaskSpec),
            title = "Quick Framework E2E Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport",
            description = "Quick Framework E2E results are directional local feedback. " +
                "Use Baseline E2E runs for formal performance conclusions. " +
                "Framework E2E isolates command pipeline overhead with in-memory or noop stores.",
        )

        val outputFile = benchmarkReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Benchmark report generated: ${outputFile.absolutePath}")
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
            group = benchmarkResultGroup(quickInfrastructureE2ETaskSpec),
            title = "Quick Infrastructure E2E Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E " +
                ":wow-benchmarks:generateInfrastructureBenchmarkReport",
            description = "Quick Infrastructure E2E results are directional local feedback for real Redis " +
                "and Mongo persistence paths. They include local service and machine effects; " +
                "use Baseline Infrastructure E2E for formal infrastructure conclusions.",
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
            group = benchmarkResultGroup(quickWebFluxTaskSpec),
            title = "Quick WebFlux Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickWebFlux " +
                ":wow-benchmarks:generateQuickWebFluxBenchmarkReport",
            description = "Quick WebFlux results are short-loop local feedback for command dispatch, " +
                "response construction, and aggregate tracing hotspots. The profile keeps the JMH GC profiler " +
                "so gc.alloc.rate.norm remains available, but skips async profiler flamegraphs; " +
                "run Exhaustive WebFlux for the complete benchmark matrix.",
        )

        val outputFile = webFluxBenchmarkReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Quick WebFlux benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateBaselineBenchmarkReport") {
    description = "Generate the formal grouped benchmark report from baseline and exhaustive JMH results."
    group = "benchmark"
    mustRunAfter(
        "benchmarkBaselineE2E",
        "benchmarkBaselineInfrastructureE2E",
        "benchmarkExhaustiveComponent",
        "benchmarkExhaustiveWebFlux",
    )
    outputs.file(baselineGroupedBenchmarkReport)
    outputs.upToDateWhen { false }
    doLast {
        val outputFile = baselineGroupedBenchmarkReport.asFile
        outputFile.delete()
        val report = renderGroupedBenchmarkReport(
            groups = baselineReportTaskSpecs.map(::benchmarkResultGroup),
            spec = GroupedBenchmarkReportSpec(
                label = "Baseline",
                expectedProfileIds = setOf(baselineE2EProfile.id, exhaustiveComponentProfile.id),
                performanceConclusionSource = true,
            ),
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
            groups = quickReportTaskSpecs.map(::benchmarkResultGroup),
            spec = GroupedBenchmarkReportSpec(
                label = "Quick",
                expectedProfileIds = setOf(quickProfile.id),
                performanceConclusionSource = false,
            ),
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
    val scoreError: Double?,
    val allocationBytesPerOp: Double?,
    val allocationErrorBytesPerOp: Double?,
)

data class BenchmarkMetricComparison(
    val key: String,
    val metric: String,
    val displayName: String,
    val mode: String,
    val threads: Int,
    val baseline: Double,
    val baselineError: Double?,
    val current: Double,
    val currentError: Double?,
    val unit: String,
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
        scoreError = scoreError,
        allocationBytesPerOp = allocationBytesPerOp,
        allocationErrorBytesPerOp = allocationErrorBytesPerOp,
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

fun parseMetricError(value: Any?): Double? {
    return parseMetricNumber(value)?.takeIf { it >= 0.0 }
}

fun parsedFrameworkE2EReport(): BenchmarkGroupReport {
    return parseBenchmarkGroup(
        parser = JsonSlurper(),
        group = benchmarkResultGroup(baselineE2ETaskSpec),
    )
}

fun benchmarkBaselineRunSpec(): Map<String, Any?> {
    val taskSpec = baselineE2ETaskSpec
    val profile = taskSpec.profile
    return linkedMapOf(
        "taskName" to taskSpec.taskName,
        "suite" to taskSpec.suite.id,
        "profile" to profile.id,
        "includePattern" to benchmarkIncludePattern(taskSpec.suite.includeClasses),
        "threads" to profile.threads,
        "modes" to profile.benchmarkModes,
        "warmupIterations" to profile.warmupIterations,
        "warmupTime" to profile.warmupTime,
        "measurementIterations" to profile.measurementIterations,
        "measurementTime" to profile.measurementTime,
        "forks" to profile.forks,
        "parameters" to profile.parameters,
        "jvmArgs" to profile.jvmArgs,
        "profilers" to requestedBenchmarkProfilers(profile),
    )
}

fun requireCleanBenchmarkWorkspace(): String {
    val gitRoot = rootProject.projectDir.absolutePath
    val commitOutput = runCommand(listOf("git", "-C", gitRoot, "rev-parse", "HEAD"))
    if (commitOutput.exitCode != 0 || commitOutput.output.isBlank()) {
        throw GradleException("Unable to resolve benchmark source commit: ${commitOutput.output}")
    }
    val statusOutput = runCommand(
        listOf("git", "-C", gitRoot, "status", "--porcelain", "--untracked-files=normal")
    )
    if (statusOutput.exitCode != 0) {
        throw GradleException("Unable to resolve benchmark source status: ${statusOutput.output}")
    }
    if (statusOutput.output.isNotBlank()) {
        throw GradleException(
            "Benchmark baseline updates require a clean Git workspace. " +
                "Commit the intended source and rerun benchmarkBaselineE2E before updating the baseline."
        )
    }
    return commitOutput.output
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
        scoreError = parseMetricError(row["scoreError"]),
        allocationBytesPerOp = parseMetricNumber(row["allocationBytesPerOp"]),
        allocationErrorBytesPerOp = parseMetricError(row["allocationErrorBytesPerOp"]),
    )
}

fun parseBaselineComparisonRows(baselineFile: File): Map<String, BenchmarkComparisonRow> {
    val parsed = JsonSlurper().parseText(baselineFile.readText()) as? Map<*, *>
        ?: throw GradleException(
            "Benchmark baseline must use the current object schema. " +
                "Regenerate it with :wow-benchmarks:updateBenchmarkBaseline."
        )
    val source = baselineFile.absolutePath
    val schemaVersion = (parsed["schemaVersion"] as? Number)?.toInt()
    if (schemaVersion != benchmarkBaselineSchemaVersion) {
        throw GradleException(
            "Benchmark baseline schema is incompatible: expected $benchmarkBaselineSchemaVersion, " +
                "found ${schemaVersion ?: "missing"}. " +
                "Regenerate it with :wow-benchmarks:updateBenchmarkBaseline."
        )
    }
    if (parsed["suite"] != frameworkE2ESuite.id || parsed["profile"] != baselineE2EProfile.id) {
        throw GradleException(
            "Benchmark baseline identity is incompatible: expected " +
                "suite=${frameworkE2ESuite.id}, profile=${baselineE2EProfile.id}. " +
                "Regenerate it with :wow-benchmarks:updateBenchmarkBaseline."
        )
    }
    val baselineSource = parsed["source"] as? Map<*, *>
        ?: throw GradleException("Benchmark baseline is missing source provenance: $source")
    if (baselineSource["dirty"] != false) {
        throw GradleException("Benchmark baseline source must be clean: $source")
    }
    listOf("commit", "jmhJarSha256", "runId").forEach { field ->
        if ((baselineSource[field] as? String).isNullOrBlank()) {
            throw GradleException("Benchmark baseline source is missing $field: $source")
        }
    }
    val actualRunSpec = parsed["runSpec"] as? Map<*, *>
        ?: throw GradleException("Benchmark baseline is missing runSpec: $source")
    val expectedRunSpec = benchmarkBaselineRunSpec()
    if (actualRunSpec != expectedRunSpec) {
        throw GradleException(
            "Benchmark baseline runSpec is incompatible with ${baselineE2ETaskSpec.taskName}. " +
                "Regenerate it with :wow-benchmarks:updateBenchmarkBaseline."
        )
    }
    if ((parsed["projectVersion"] as? String).isNullOrBlank()) {
        throw GradleException("Benchmark baseline is missing projectVersion: $source")
    }
    val runtime = parsed["runtime"] as? Map<*, *>
        ?: throw GradleException("Benchmark baseline is missing runtime provenance: $source")
    listOf("javaVersion", "vmName", "vmVersion", "osName", "osVersion", "osArch").forEach { field ->
        if ((runtime[field] as? String).isNullOrBlank()) {
            throw GradleException("Benchmark baseline runtime is missing $field: $source")
        }
    }
    if (parsePositiveInt(runtime["availableProcessors"]) == null) {
        throw GradleException("Benchmark baseline runtime is missing positive availableProcessors: $source")
    }
    val artifacts = parsed["artifacts"] as? List<*>
        ?: throw GradleException("Benchmark baseline is missing artifacts provenance: $source")
    if (artifacts.isEmpty()) {
        throw GradleException("Benchmark baseline contains no artifact provenance: $source")
    }
    val artifactThreads = artifacts.mapIndexed { artifactIndex, rawArtifact ->
        val artifact = rawArtifact as? Map<*, *>
            ?: throw GradleException("Benchmark baseline artifact $artifactIndex must be an object: $source")
        listOf("taskPath", "startedAt", "completedAt", "resultSha256").forEach { field ->
            if ((artifact[field] as? String).isNullOrBlank()) {
                throw GradleException("Benchmark baseline artifact $artifactIndex is missing $field: $source")
            }
        }
        if (parsePositiveInt(artifact["resultRowCount"]) == null) {
            throw GradleException(
                "Benchmark baseline artifact $artifactIndex is missing positive resultRowCount: $source"
            )
        }
        parsePositiveInt(artifact["threads"])
            ?: throw GradleException("Benchmark baseline artifact $artifactIndex is missing positive threads: $source")
    }
    if (artifactThreads != baselineE2EProfile.threads) {
        throw GradleException(
            "Benchmark baseline artifact threads are incompatible: " +
                "expected ${baselineE2EProfile.threads}, found $artifactThreads."
        )
    }
    val rows = parsed["results"] as? List<*>
        ?: throw GradleException("Benchmark baseline is missing results array: $source")
    if (rows.isEmpty()) {
        throw GradleException("Benchmark baseline contains no rows: $source")
    }
    return rows.mapIndexed { rowIndex, rawRow ->
        val row = rawRow as? Map<*, *> ?: throw GradleException(
            "Invalid benchmark baseline row at index $rowIndex in $source: expected row to be a JSON object."
        )
        parsedResultBaselineRow(row, source, rowIndex)
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
    baselineError: Double?,
    current: Double,
    currentError: Double?,
    unit: String,
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
        baselineError = baselineError,
        current = current,
        currentError = currentError,
        unit = unit,
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
                baselineError = baseRow.scoreError,
                current = latestRow.score,
                currentError = latestRow.scoreError,
                unit = latestRow.unit,
                thresholdPercent = benchmarkThroughputRegressionPercent,
                higherIsBetter = true,
            )
        } else {
            compareMetric(
                metric = "latency",
                baseRow = baseRow,
                latestRow = latestRow,
                baseline = baseRow.score,
                baselineError = baseRow.scoreError,
                current = latestRow.score,
                currentError = latestRow.scoreError,
                unit = latestRow.unit,
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
                    baselineError = baseRow.allocationErrorBytesPerOp,
                    current = latestRow.allocationBytesPerOp,
                    currentError = latestRow.allocationErrorBytesPerOp,
                    unit = "B/op",
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

fun formatComparisonNumber(value: Double?): String {
    return value?.let { String.format(Locale.US, "%.6g", it) } ?: "-"
}

fun formatComparisonError(value: Double?): String {
    return value?.let { "+/-${String.format(Locale.US, "%.6g", it)}" } ?: "-"
}

tasks.register("benchmarkCompare") {
    description = "Compare primary framework E2E benchmark results against baseline."
    group = "benchmark"
    mustRunAfter(baselineE2ETaskSpec.taskName)

    doLast {
        val baselineFile = frameworkE2EBaselineJson.asFile
        if (!baselineFile.exists()) {
            throw GradleException(
                "Baseline not found: ${baselineFile.absolutePath}. " +
                    "Run :wow-benchmarks:updateBenchmarkBaseline first."
            )
        }
        val baseline = parseBaselineComparisonRows(baselineFile)
        val latest = parsedComparisonRows(parsedFrameworkE2EReport().rows)
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
        println(
            "| Metric | Benchmark | Threads | Mode | Baseline | Baseline Error | Current | " +
                "Current Error | Unit | Delta % | Threshold | Status |"
        )
        println(
            "|--------|-----------|---------|------|----------|----------------|---------|" +
                "---------------|------|---------|-----------|--------|"
        )

        for (benchmark in allBenchmarks) {
            val baseRow = baseline[benchmark]
            val latestRow = latest[benchmark]

            if (baseRow == null) {
                println(
                    "| result | ${latestRow?.displayName ?: benchmark} | ${latestRow?.threads ?: "-"} | " +
                        "${latestRow?.mode ?: "-"} | - | - | ${formatComparisonNumber(latestRow?.score)} | " +
                        "${formatComparisonError(latestRow?.scoreError)} | ${latestRow?.unit ?: "-"} | NEW | - | NEW |"
                )
                continue
            }
            if (latestRow == null) {
                println(
                    "| result | ${baseRow.displayName} | ${baseRow.threads} | ${baseRow.mode} | " +
                        "${formatComparisonNumber(baseRow.score)} | ${formatComparisonError(baseRow.scoreError)} | " +
                        "- | - | ${baseRow.unit} | REMOVED | - | REMOVED |"
                )
                continue
            }
            comparisonsByKey.getValue(benchmark)
                .forEach { comparison ->
                    println(
                        "| ${comparison.metric} | ${comparison.displayName} | ${comparison.threads} | ${comparison.mode} | " +
                            "${formatComparisonNumber(comparison.baseline)} | " +
                            "${formatComparisonError(comparison.baselineError)} | " +
                            "${formatComparisonNumber(comparison.current)} | " +
                            "${formatComparisonError(comparison.currentError)} | ${comparison.unit} | " +
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
    description = "Publish clean, provenance-backed Framework E2E results as the new baseline."
    group = "benchmark"
    mustRunAfter(baselineE2ETaskSpec.taskName)

    doLast {
        val currentCommit = requireCleanBenchmarkWorkspace()
        val benchmarkReport = parsedFrameworkE2EReport()
        val manifests = benchmarkReport.manifests.sortedBy { it.threads }
        if (manifests.any { it.sourceDirty }) {
            throw GradleException(
                "Benchmark baseline updates reject dirty benchmark runs. " +
                    "Rerun ${baselineE2ETaskSpec.taskName} from a clean Git workspace."
            )
        }
        val sourceCommit = manifests.first().sourceCommit
        if (sourceCommit != currentCommit) {
            throw GradleException(
                "Benchmark results were produced from commit $sourceCommit, but HEAD is $currentCommit. " +
                    "Rerun ${baselineE2ETaskSpec.taskName} before updating the baseline."
            )
        }
        val sourceManifest = manifests.first()
        val baselineFile = frameworkE2EBaselineJson.asFile
        val baselineJson = linkedMapOf(
            "schemaVersion" to benchmarkBaselineSchemaVersion,
            "suite" to frameworkE2ESuite.id,
            "profile" to baselineE2EProfile.id,
            "generatedAt" to Instant.now().toString(),
            "projectVersion" to sourceManifest.projectVersion,
            "source" to linkedMapOf(
                "commit" to sourceCommit,
                "dirty" to false,
                "jmhJarSha256" to sourceManifest.jmhJarSha256,
                "runId" to sourceManifest.runId,
            ),
            "runSpec" to benchmarkBaselineRunSpec(),
            "runtime" to linkedMapOf(
                "javaVersion" to sourceManifest.javaVersion,
                "vmName" to sourceManifest.vmName,
                "vmVersion" to sourceManifest.vmVersion,
                "osName" to sourceManifest.osName,
                "osVersion" to sourceManifest.osVersion,
                "osArch" to sourceManifest.osArch,
                "availableProcessors" to sourceManifest.availableProcessors,
                "physicalMemoryBytes" to sourceManifest.physicalMemoryBytes,
            ),
            "artifacts" to manifests.map { manifest ->
                linkedMapOf(
                    "taskPath" to manifest.taskPath,
                    "startedAt" to manifest.startedAt,
                    "completedAt" to manifest.completedAt,
                    "threads" to manifest.threads,
                    "resultSha256" to manifest.resultSha256,
                    "resultRowCount" to manifest.resultRowCount,
                )
            },
            "results" to benchmarkReport.rows.map { it.toBaselineJsonRow() },
        )
        publishJsonAtomically(baselineFile, baselineJson)
        logger.lifecycle("Baseline updated: ${baselineFile.absolutePath}")
    }
}
