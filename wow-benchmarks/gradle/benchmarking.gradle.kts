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

fun BenchmarkRequiredService.toRunSpec(): Map<String, Any> {
    return linkedMapOf(
        "service" to service,
        "host" to host,
        "port" to port,
    )
}

fun BenchmarkSuite.requiredServicesRunSpec(): List<Map<String, Any>> {
    return requiredServices.map(BenchmarkRequiredService::toRunSpec)
}

data class BenchmarkSuite(
    val id: String,
    val displayName: String,
    val includeClasses: List<String>,
    val resultFileName: String,
    val humanFileName: String,
    val requiredForGroupedReport: Boolean = false,
    val formalRegressionSource: Boolean = false,
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

abstract class OpenLoopExecutionService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    override fun close() = Unit
}

val openLoopExecutionService = gradle.sharedServices.registerIfAbsent(
    "openLoopExecution",
    OpenLoopExecutionService::class,
) {
    maxParallelUsages.set(1)
}

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

fun parseBenchmarkParameters(propertyName: String, value: String): Map<String, String> {
    val parameters = linkedMapOf<String, String>()
    value.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { parameter ->
            val separatorIndex = parameter.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex == parameter.lastIndex) {
                throw GradleException(
                    "Gradle property $propertyName must use name=value entries separated by semicolons: $parameter"
                )
            }
            val name = parameter.substring(0, separatorIndex).trim()
            val parameterValue = parameter.substring(separatorIndex + 1).trim()
            if (name.isEmpty() || parameterValue.isEmpty()) {
                throw GradleException(
                    "Gradle property $propertyName must use non-empty parameter names and values: $parameter"
                )
            }
            if (parameters.put(name, parameterValue) != null) {
                throw GradleException("Gradle property $propertyName contains duplicate parameter: $name")
            }
        }
    return parameters
}

fun benchmarkParametersProperty(propertyName: String): Map<String, String> {
    return providers.gradleProperty(propertyName)
        .map { value -> parseBenchmarkParameters(propertyName, value) }
        .getOrElse(emptyMap())
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

val quickBatchE2EProfile = quickProfile.copy(
    threads = listOf(1),
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

val confirmationE2EProfile = baselineE2EProfile.copy(
    id = "confirmation",
    threads = benchmarkThreadsProperty("benchmarkConfirmE2EThreads", listOf(4)),
    parameters = benchmarkParametersProperty("benchmarkConfirmE2EParameters"),
)

val commandIngressHeadOfLineScenarios = listOf(
    "DISTINCT_UNIFORM_POOL4",
    "DISTINCT_ONE_SLOW_POOL4",
    "COLLIDING_UNIFORM_POOL4",
    "COLLIDING_ONE_SLOW_POOL4",
    "DISTINCT_UNIFORM_POOL1",
    "DISTINCT_ONE_SLOW_POOL1",
)

val commandIngressHeadOfLineE2EProfile = BenchmarkRunProfile(
    id = "scheduler-hol",
    warmupIterations = 2,
    warmupTime = "3s",
    measurementIterations = 3,
    measurementTime = "5s",
    forks = 3,
    threads = listOf(4),
    benchmarkModes = listOf("thrpt", "sample"),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = false,
    includeAsyncProfiler = false,
    parameters = benchmarkParametersProperty("benchmarkCommandIngressHolParameters").ifEmpty {
        mapOf("contentionScenario" to commandIngressHeadOfLineScenarios.joinToString(","))
    },
)

val multiAggregateSchedulerProfile = BenchmarkRunProfile(
    id = "multi-aggregate-scheduler",
    warmupIterations = 2,
    warmupTime = "2s",
    measurementIterations = 4,
    measurementTime = "3s",
    forks = 3,
    threads = benchmarkThreadsProperty(
        "benchmarkMultiAggregateSchedulerThreads",
        listOf(16),
    ),
    benchmarkModes = benchmarkModesProperty(
        "benchmarkMultiAggregateSchedulerModes",
        listOf("thrpt"),
    ),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
    parameters =
        benchmarkParametersProperty("benchmarkMultiAggregateSchedulerParameters"),
)

val multiAggregateSchedulerFixedBudgetProfile = multiAggregateSchedulerProfile.copy(
    id = "multi-aggregate-scheduler-fixed-budget",
    parameters =
        benchmarkParametersProperty("benchmarkMultiAggregateSchedulerFixedBudgetParameters"),
)

val multiAggregateSchedulerIsolationProfile = BenchmarkRunProfile(
    id = "multi-aggregate-scheduler-isolation",
    warmupIterations = 2,
    warmupTime = "2s",
    measurementIterations = 3,
    measurementTime = "3s",
    forks = 3,
    threads = listOf(8),
    benchmarkModes = listOf("thrpt", "sample"),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = false,
    includeAsyncProfiler = false,
    parameters =
        benchmarkParametersProperty("benchmarkMultiAggregateSchedulerIsolationParameters"),
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

val openLoopObserverComponentProfile = diagnosticComponentProfile.copy(
    id = "open-loop-observer",
    warmupIterations = 3,
    warmupTime = "250ms",
    measurementIterations = 10,
    measurementTime = "250ms",
    forks = 3,
    threads = benchmarkThreadsProperty(
        "benchmarkOpenLoopObserverThreads",
        listOf(1, 4, 16),
    ),
    benchmarkModes = benchmarkModesProperty(
        "benchmarkOpenLoopObserverModes",
        listOf("avgt"),
    ),
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
        "me.ahoo.wow.benchmark.e2e.BatchCommandWriteE2EBenchmark.sendBatchSequentialAndWaitProcessed",
        "me.ahoo.wow.benchmark.webflux.WebFluxSmokeBenchmark.monoCommandResultServerResponseOnly",
    ),
    resultFileName = "benchmark-smoke.json",
    humanFileName = "benchmark-smoke-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
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
    formalRegressionSource = true,
)

val batchCommandWriteE2ESuite = BenchmarkSuite(
    id = "batch-command-write-e2e",
    displayName = "Batch CommandWrite E2E",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.e2e.BatchCommandWriteE2EBenchmark",
    ),
    resultFileName = "batch-command-write-e2e.json",
    humanFileName = "batch-command-write-e2e-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
)

val commandIngressHeadOfLineE2ESuite = BenchmarkSuite(
    id = "command-ingress-hol-e2e",
    displayName = "Command Ingress Head-of-Line E2E",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.e2e.CommandIngressHeadOfLineBenchmark.contention",
    ),
    resultFileName = "command-ingress-hol-e2e.json",
    humanFileName = "command-ingress-hol-e2e-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
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
    formalRegressionSource = false,
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
    formalRegressionSource = false,
)

val openLoopObserverComponentSuite = BenchmarkSuite(
    id = "open-loop-observer-component",
    displayName = "Open-Loop Observer Component",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.openloop.OpenLoopObserverComponentBenchmark",
    ),
    resultFileName = "open-loop-observer-component.json",
    humanFileName = "open-loop-observer-component-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
)

val multiAggregateSchedulerSuite = BenchmarkSuite(
    id = "multi-aggregate-scheduler-component",
    displayName = "Multi-Aggregate Scheduler Component",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.MultiAggregateSchedulerComponentBenchmark",
    ),
    resultFileName = "multi-aggregate-scheduler-component.json",
    humanFileName = "multi-aggregate-scheduler-component-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
)

val multiAggregateSchedulerFixedBudgetSuite = BenchmarkSuite(
    id = "multi-aggregate-scheduler-fixed-budget",
    displayName = "Multi-Aggregate Scheduler Fixed Worker Budget",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.MultiAggregateFixedWorkerBudgetComponentBenchmark",
    ),
    resultFileName = "multi-aggregate-scheduler-fixed-budget.json",
    humanFileName = "multi-aggregate-scheduler-fixed-budget-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
)

val multiAggregateSchedulerIsolationSuite = BenchmarkSuite(
    id = "multi-aggregate-scheduler-isolation",
    displayName = "Multi-Aggregate Scheduler Isolation",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.component.MultiAggregateSchedulerIsolationBenchmark",
    ),
    resultFileName = "multi-aggregate-scheduler-isolation.json",
    humanFileName = "multi-aggregate-scheduler-isolation-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
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
    formalRegressionSource = false,
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

val confirmationE2ESuite = frameworkE2ESuite.copy(
    includeClasses = benchmarkIncludesProperty(
        "benchmarkConfirmE2EIncludes",
        frameworkE2ESuite.includeClasses,
    ),
    requiredForGroupedReport = false,
    formalRegressionSource = false,
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

val quickBatchE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickBatchE2E",
    suite = batchCommandWriteE2ESuite,
    profile = quickBatchE2EProfile,
    description = "Runs the single-subscription Batch CommandWrite E2E feedback catalog.",
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

val confirmationE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkConfirmE2E",
    suite = confirmationE2ESuite,
    profile = confirmationE2EProfile,
    description = "Confirms selected Framework E2E signals with the formal baseline measurement profile.",
)

val commandIngressHeadOfLineE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkCommandIngressHolE2E",
    suite = commandIngressHeadOfLineE2ESuite,
    profile = commandIngressHeadOfLineE2EProfile,
    description = "Measures stripe and Scheduler worker head-of-line effects with isolated fast-command latency.",
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

val openLoopObserverComponentTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkOpenLoopObserverComponent",
    suite = openLoopObserverComponentSuite,
    profile = openLoopObserverComponentProfile,
    description =
        "Measures only the write-side cost of bounded-open-loop latency recorders.",
)

val multiAggregateSchedulerTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkMultiAggregateScheduler",
    suite = multiAggregateSchedulerSuite,
    profile = multiAggregateSchedulerProfile,
    description =
        "Compares per-aggregate and role-shared Scheduler topology across aggregate counts.",
)

val multiAggregateSchedulerFixedBudgetTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkMultiAggregateSchedulerFixedBudget",
    suite = multiAggregateSchedulerFixedBudgetSuite,
    profile = multiAggregateSchedulerFixedBudgetProfile,
    description =
        "Compares Scheduler ownership while holding total role worker capacity constant.",
)

val multiAggregateSchedulerIsolationTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkMultiAggregateSchedulerIsolation",
    suite = multiAggregateSchedulerIsolationSuite,
    profile = multiAggregateSchedulerIsolationProfile,
    description =
        "Measures equal-budget Scheduler work conservation and cross-type isolation.",
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
    quickBatchE2ETaskSpec,
    baselineE2ETaskSpec,
    latencyE2ETaskSpec,
    confirmationE2ETaskSpec,
    commandIngressHeadOfLineE2ETaskSpec,
    quickInfrastructureE2ETaskSpec,
    baselineInfrastructureE2ETaskSpec,
    quickComponentTaskSpec,
    diagnosticComponentTaskSpec,
    openLoopObserverComponentTaskSpec,
    multiAggregateSchedulerTaskSpec,
    multiAggregateSchedulerFixedBudgetTaskSpec,
    multiAggregateSchedulerIsolationTaskSpec,
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

fun formatRequiredServiceEndpoints(requiredServices: List<BenchmarkRequiredService>): String {
    return requiredServices.joinToString(", ") { service ->
        "${service.service}=${service.host}:${service.port}"
    }
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
    manifests.groupBy { it.suite }
        .mapValues { (_, suiteManifests) -> suiteManifests.first().requiredServices }
        .filterValues { it.isNotEmpty() }
        .forEach { (suite, requiredServices) ->
            appendLine("- **$suite Required Services**: `${formatRequiredServiceEndpoints(requiredServices)}`")
        }
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
                    "requiredServices" to suite.requiredServicesRunSpec(),
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

data class CommandProcessedOpenLoopTaskSpec(
    val taskName: String,
    val profile: String,
    val ratePerSecond: Long,
    val repeat: Int,
    val warmupSeconds: Long,
    val measurementSeconds: Long,
    val producerCount: Int,
    val maxInFlight: Int,
    val requestTimeoutMillis: Long,
    val watchdogIntervalMillis: Long,
    val startLeadMillis: Long,
    val maxGeneratorMissedRatio: Double,
    val maxGeneratorLagP99Millis: Long,
    val observationMode: String,
    val schedulerPoolSize: String,
    val stripeCount: String,
    val aggregateCardinality: String,
    val jvmArgs: List<String>,
    val observationDesignModes: List<String> = emptyList(),
    val observationDesignSequence: List<String> = emptyList(),
    val observationDesignPosition: Int? = null,
    val observationDesignBlockSize: Int? = null,
)

fun isFormalOpenLoopProfile(profile: String): Boolean =
    when (profile) {
        "formal" -> true
        "smoke", "observer-diagnostic" -> false
        else -> throw GradleException("Unsupported open-loop profile[$profile].")
    }

val supportedOpenLoopObservationModes = setOf(
    "FULL",
    "NO_DEADLINE_WHEEL",
    "NO_SERVER_TRACKER",
    "GENERATOR_ONLY_LATENCY",
    "NO_LATENCY",
)

fun normalizeOpenLoopObservationMode(rawValue: String): String =
    rawValue.trim().replace("-", "_").uppercase(Locale.US)

fun validateCommandProcessedOpenLoopTaskSpec(spec: CommandProcessedOpenLoopTaskSpec) {
    val formalEvidence = isFormalOpenLoopProfile(spec.profile)
    if (spec.observationMode !in supportedOpenLoopObservationModes) {
        throw GradleException(
            "Unsupported open-loop observation mode[${spec.observationMode}]. Supported values: " +
                supportedOpenLoopObservationModes.sorted().joinToString()
        )
    }
    if (formalEvidence && spec.observationMode != "FULL") {
        throw GradleException(
            "Formal open-loop benchmarks require observationMode=FULL; " +
                "diagnostic observer ablations cannot be published as capacity evidence."
        )
    }
    if (spec.profile == "observer-diagnostic") {
        val modes = spec.observationDesignModes
        if (modes.isEmpty() || modes.distinct() != modes) {
            throw GradleException(
                "Observer diagnostics require a non-empty, unique observationDesignModes list."
            )
        }
        if (modes.any { it !in supportedOpenLoopObservationModes }) {
            throw GradleException(
                "observationDesignModes contains an unsupported mode: $modes"
            )
        }
        val expectedBlockSize = minimumBalancedOpenLoopObservationRepeats(modes)
        if (spec.observationDesignBlockSize != expectedBlockSize) {
            throw GradleException(
                "observationDesignBlockSize[${spec.observationDesignBlockSize}] does not " +
                    "match Williams block size[$expectedBlockSize]."
            )
        }
        val expectedSequence = orderedOpenLoopObservationModes(modes, spec.repeat)
        if (spec.observationDesignSequence != expectedSequence) {
            throw GradleException(
                "observationDesignSequence[${spec.observationDesignSequence}] does not " +
                    "match repeat ${spec.repeat} sequence[$expectedSequence]."
            )
        }
        val expectedPosition = expectedSequence.indexOf(spec.observationMode)
        if (spec.observationDesignPosition != expectedPosition) {
            throw GradleException(
                "observationDesignPosition[${spec.observationDesignPosition}] does not " +
                    "match mode ${spec.observationMode} position[$expectedPosition]."
            )
        }
    } else if (
        spec.observationDesignModes.isNotEmpty() ||
        spec.observationDesignSequence.isNotEmpty() ||
        spec.observationDesignPosition != null ||
        spec.observationDesignBlockSize != null
    ) {
        throw GradleException(
            "Only observer-diagnostic tasks may declare an observation design."
        )
    }
    commandProcessedOpenLoopResolvedSchedulerPoolSize(spec)
    commandProcessedOpenLoopResolvedStripeCount(spec)
}

fun commandProcessedOpenLoopPositiveInt(
    name: String,
    rawValue: String,
): Int =
    rawValue.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: throw GradleException("$name must be a positive integer: $rawValue")

fun commandProcessedOpenLoopJvmIntArgument(
    spec: CommandProcessedOpenLoopTaskSpec,
    prefix: String,
    name: String,
): Int? =
    spec.jvmArgs
        .mapNotNull { argument ->
            argument.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        }
        .lastOrNull()
        ?.let { commandProcessedOpenLoopPositiveInt(name, it) }

fun commandProcessedOpenLoopActiveProcessors(
    spec: CommandProcessedOpenLoopTaskSpec,
): Int =
    commandProcessedOpenLoopJvmIntArgument(
        spec = spec,
        prefix = "-XX:ActiveProcessorCount=",
        name = "ActiveProcessorCount",
    ) ?: Runtime.getRuntime().availableProcessors()

fun commandProcessedOpenLoopResolvedSchedulerPoolSize(
    spec: CommandProcessedOpenLoopTaskSpec,
): Int =
    if (spec.schedulerPoolSize == "cpu") {
        commandProcessedOpenLoopJvmIntArgument(
            spec = spec,
            prefix = "-Dreactor.schedulers.defaultPoolSize=",
            name = "reactor.schedulers.defaultPoolSize",
        ) ?: System.getProperty("reactor.schedulers.defaultPoolSize")
            ?.let {
                commandProcessedOpenLoopPositiveInt(
                    "reactor.schedulers.defaultPoolSize",
                    it,
                )
            }
        ?: commandProcessedOpenLoopActiveProcessors(spec)
    } else {
        commandProcessedOpenLoopPositiveInt("schedulerPoolSize", spec.schedulerPoolSize)
    }

fun commandProcessedOpenLoopResolvedStripeCount(
    spec: CommandProcessedOpenLoopTaskSpec,
): Int =
    if (spec.stripeCount == "default") {
        commandProcessedOpenLoopJvmIntArgument(
            spec = spec,
            prefix = "-Dwow.parallelism=",
            name = "wow.parallelism",
        ) ?: System.getProperty("wow.parallelism")
            ?.let { commandProcessedOpenLoopPositiveInt("wow.parallelism", it) }
        ?: runCatching {
            Math.multiplyExact(64, commandProcessedOpenLoopActiveProcessors(spec))
        }.getOrElse {
            throw GradleException("Resolved default stripeCount overflows Int.", it)
        }
    } else {
        commandProcessedOpenLoopPositiveInt("stripeCount", spec.stripeCount)
    }

val commandProcessedOpenLoopOrchestratorSha256 =
    fileSha256(file("gradle/benchmarking.gradle.kts"))

fun commandProcessedOpenLoopRunnerSourceFiles(root: File): List<File> =
    rootProject.fileTree(root) {
        include(
            "**/*.java",
            "**/*.json",
            "**/*.kt",
            "**/*.kts",
            "**/*.properties",
            "**/*.toml",
            "**/*.yaml",
            "**/*.yml",
            "**/src/**/resources/**",
        )
        exclude(
            ".git/**",
            ".gradle/**",
            ".agents/**",
            ".codex/**",
            "**/build/**",
            "**/node_modules/**",
            "**/results/**",
            "compensation/dashboard/**",
            "document/**",
            "documentation/**",
        )
    }.files
        .filter(File::isFile)
        .sortedBy { source -> source.relativeTo(root).invariantSeparatorsPath }

fun computeCommandProcessedOpenLoopRunnerSourceSha256(
    root: File = rootProject.projectDir,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sourceFiles = commandProcessedOpenLoopRunnerSourceFiles(root)
    sourceFiles.forEach { source ->
        val relativePath = source.relativeTo(root).invariantSeparatorsPath
        digest.update(relativePath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

val commandProcessedOpenLoopRunnerSourceSha256 =
    computeCommandProcessedOpenLoopRunnerSourceSha256()

fun commandProcessedOpenLoopObservationDesignId(
    spec: CommandProcessedOpenLoopTaskSpec,
): String =
    if (spec.observationDesignModes.isEmpty()) {
        "none"
    } else {
        val identity =
            "williams-v1\n" +
                "modes=${spec.observationDesignModes.joinToString(",")}\n" +
                "blockSize=${spec.observationDesignBlockSize}"
        MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

fun commandProcessedOpenLoopProtocolIdentity(spec: CommandProcessedOpenLoopTaskSpec): String =
    listOf(
        "profile" to spec.profile,
        "ratePerSecond" to spec.ratePerSecond,
        "warmupSeconds" to spec.warmupSeconds,
        "measurementSeconds" to spec.measurementSeconds,
        "producerCount" to spec.producerCount,
        "maxInFlight" to spec.maxInFlight,
        "requestTimeoutMillis" to spec.requestTimeoutMillis,
        "watchdogIntervalMillis" to spec.watchdogIntervalMillis,
        "startLeadMillis" to spec.startLeadMillis,
        "maxGeneratorMissedRatio" to spec.maxGeneratorMissedRatio,
        "maxGeneratorLagP99Millis" to spec.maxGeneratorLagP99Millis,
        "observationMode" to spec.observationMode,
        "observationDesignId" to commandProcessedOpenLoopObservationDesignId(spec),
        "observationDesignModes" to spec.observationDesignModes.joinToString(","),
        "observationDesignBlockSize" to spec.observationDesignBlockSize,
        "observationDesignSequence" to spec.observationDesignSequence.joinToString(","),
        "observationDesignPosition" to spec.observationDesignPosition,
        "schedulerPoolSizeToken" to spec.schedulerPoolSize,
        "schedulerPoolSize" to commandProcessedOpenLoopResolvedSchedulerPoolSize(spec),
        "stripeCountToken" to spec.stripeCount,
        "stripeCount" to commandProcessedOpenLoopResolvedStripeCount(spec),
        "aggregateCardinality" to spec.aggregateCardinality,
        "jvmArgs" to spec.jvmArgs.joinToString("\u001f"),
        "orchestratorSha256" to commandProcessedOpenLoopOrchestratorSha256,
        "runnerSourceSha256" to commandProcessedOpenLoopRunnerSourceSha256,
    ).joinToString("\n") { (name, value) -> "$name=$value" }

fun commandProcessedOpenLoopProtocolFingerprint(
    spec: CommandProcessedOpenLoopTaskSpec,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(commandProcessedOpenLoopProtocolIdentity(spec).toByteArray(Charsets.UTF_8))
    return digest
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun commandProcessedOpenLoopResultPrefix(spec: CommandProcessedOpenLoopTaskSpec): String =
    "${spec.profile}-rate-${spec.ratePerSecond}-" +
        "pool-${openLoopSafeToken(spec.schedulerPoolSize)}-" +
        "${commandProcessedOpenLoopResolvedSchedulerPoolSize(spec)}-" +
        "stripes-${openLoopSafeToken(spec.stripeCount)}-" +
        "${commandProcessedOpenLoopResolvedStripeCount(spec)}-" +
        "cardinality-${openLoopSafeToken(spec.aggregateCardinality)}-" +
        "producers-${spec.producerCount}-watchdog-${spec.watchdogIntervalMillis}ms-" +
        "observer-${openLoopSafeToken(spec.observationMode.lowercase(Locale.US))}-" +
        "protocol-${commandProcessedOpenLoopProtocolFingerprint(spec)}-repeat-${spec.repeat}"

fun positiveOpenLoopLong(
    propertyName: String,
    defaultValue: Long,
): Long {
    val rawValue = providers.gradleProperty(propertyName).orNull ?: defaultValue.toString()
    return rawValue.toLongOrNull()
        ?.takeIf { it > 0 }
        ?: throw GradleException("$propertyName must be a positive integer: $rawValue")
}

fun positiveOpenLoopInt(
    propertyName: String,
    defaultValue: Int,
): Int {
    val value = positiveOpenLoopLong(propertyName, defaultValue.toLong())
    if (value > Int.MAX_VALUE) {
        throw GradleException("$propertyName must not exceed ${Int.MAX_VALUE}: $value")
    }
    return value.toInt()
}

fun openLoopRatio(
    propertyName: String,
    defaultValue: Double,
): Double {
    val rawValue = providers.gradleProperty(propertyName).orNull ?: defaultValue.toString()
    return rawValue.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?: throw GradleException(
            "$propertyName must be a finite number between 0 and 1: $rawValue"
        )
}

fun openLoopPositiveToken(
    propertyName: String,
    defaultValue: String,
    namedValue: String,
): String {
    val value = providers.gradleProperty(propertyName).orNull ?: defaultValue
    if (value == namedValue || value.toIntOrNull()?.let { it > 0 } == true) {
        return value
    }
    throw GradleException(
        "$propertyName must be '$namedValue' or a positive integer: $value"
    )
}

fun openLoopRates(): List<Long> {
    val rawValue = providers.gradleProperty("benchmarkOpenLoopRates").orNull
        ?: "200000,300000,350000,400000"
    val rates = rawValue.split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { token ->
            token.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw GradleException(
                    "benchmarkOpenLoopRates must contain only positive integers: $token"
                )
        }
        .distinct()
    if (rates.isEmpty()) {
        throw GradleException("benchmarkOpenLoopRates must contain at least one rate.")
    }
    return rates
}

fun openLoopSafeToken(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "-")

fun openLoopObservationModes(
    propertyName: String,
    defaultValue: String,
): List<String> {
    val rawValue = providers.gradleProperty(propertyName).orNull ?: defaultValue
    val modes = rawValue
        .split(",")
        .map(::normalizeOpenLoopObservationMode)
        .filter(String::isNotEmpty)
        .distinct()
    if (modes.isEmpty()) {
        throw GradleException("$propertyName must contain at least one observation mode.")
    }
    val unsupported = modes.filterNot(supportedOpenLoopObservationModes::contains)
    if (unsupported.isNotEmpty()) {
        throw GradleException(
            "$propertyName contains unsupported observation mode(s): ${unsupported.joinToString()}. " +
                "Supported values: ${supportedOpenLoopObservationModes.sorted().joinToString()}."
        )
    }
    return modes
}

fun orderedOpenLoopObservationModes(
    modes: List<String>,
    repeat: Int,
): List<String> {
    require(modes.isNotEmpty()) {
        "Observation modes must not be empty."
    }
    require(repeat > 0) {
        "Observation repeat must be positive."
    }
    if (modes.size == 1) {
        return modes
    }
    val baseIndexes = buildList {
        add(0)
        var lower = 1
        var upper = modes.lastIndex
        while (size < modes.size) {
            add(lower++)
            if (size < modes.size) {
                add(upper--)
            }
        }
    }
    val designSize = minimumBalancedOpenLoopObservationRepeats(modes)
    val designRow = (repeat - 1) % designSize
    val offset = designRow % modes.size
    val row = baseIndexes.map { index ->
        modes[(index + offset) % modes.size]
    }
    return if (designRow >= modes.size) row.reversed() else row
}

fun minimumBalancedOpenLoopObservationRepeats(modes: List<String>): Int {
    require(modes.isNotEmpty()) {
        "Observation modes must not be empty."
    }
    return when {
        modes.size == 1 -> 1
        modes.size % 2 == 0 -> modes.size
        else -> modes.size * 2
    }
}

fun registerCommandProcessedOpenLoopTask(
    spec: CommandProcessedOpenLoopTaskSpec,
): TaskProvider<JavaExec> {
    validateCommandProcessedOpenLoopTaskSpec(spec)
    val resolvedSchedulerPoolSize =
        commandProcessedOpenLoopResolvedSchedulerPoolSize(spec)
    val resolvedStripeCount = commandProcessedOpenLoopResolvedStripeCount(spec)
    val jmhJar = tasks.named<Jar>("jmhJar")
    val resultPrefix = commandProcessedOpenLoopResultPrefix(spec)
    val resultFile = layout.projectDirectory.file(
        "results/open-loop/command-processed/$resultPrefix.json"
    )
    val humanFile = layout.projectDirectory.file(
        "results/open-loop/command-processed/$resultPrefix.md"
    )
    val manifestFile = layout.projectDirectory.file(
        "results/open-loop/command-processed/$resultPrefix.manifest.json"
    )
    val inProgressManifestFile = layout.projectDirectory.file(
        "results/open-loop/command-processed/$resultPrefix.manifest.in-progress.json"
    )
    val runnerArgs = listOf(
        "--resultPath=${resultFile.asFile.absolutePath}",
        "--humanPath=${humanFile.asFile.absolutePath}",
        "--ratePerSecond=${spec.ratePerSecond}",
        "--warmupSeconds=${spec.warmupSeconds}",
        "--measurementSeconds=${spec.measurementSeconds}",
        "--producerCount=${spec.producerCount}",
        "--maxInFlight=${spec.maxInFlight}",
        "--requestTimeoutMillis=${spec.requestTimeoutMillis}",
        "--watchdogIntervalMillis=${spec.watchdogIntervalMillis}",
        "--startLeadMillis=${spec.startLeadMillis}",
        "--maxGeneratorMissedRatio=${spec.maxGeneratorMissedRatio}",
        "--maxGeneratorLagP99Millis=${spec.maxGeneratorLagP99Millis}",
        "--observationMode=${spec.observationMode}",
        "--schedulerPoolSizeToken=${spec.schedulerPoolSize}",
        "--schedulerPoolSize=$resolvedSchedulerPoolSize",
        "--stripeCountToken=${spec.stripeCount}",
        "--stripeCount=$resolvedStripeCount",
        "--aggregateCardinality=${spec.aggregateCardinality}",
    )

    return tasks.register<JavaExec>(spec.taskName) {
        description =
            "Runs the command PROCESSED bounded-open-loop benchmark at ${spec.ratePerSecond} commands/s " +
                "(repeat ${spec.repeat})."
        group = "benchmark"
        dependsOn(jmhJar)
        usesService(benchmarkRunIdentityService)
        usesService(openLoopExecutionService)
        classpath(jmhJar.flatMap { it.archiveFile })
        mainClass.set("me.ahoo.wow.benchmark.openloop.CommandProcessedOpenLoopRunner")
        jvmArgs(spec.jvmArgs)
        args(runnerArgs)

        outputs.file(resultFile)
        outputs.file(humanFile)
        outputs.file(manifestFile)
        outputs.upToDateWhen { false }

        doFirst {
            validateCommandProcessedOpenLoopTaskSpec(spec)
            val formalEvidence = isFormalOpenLoopProfile(spec.profile)
            val currentRunnerSourceSha256 =
                computeCommandProcessedOpenLoopRunnerSourceSha256()
            if (
                currentRunnerSourceSha256 !=
                commandProcessedOpenLoopRunnerSourceSha256
            ) {
                throw GradleException(
                    "Open-loop runner source changed after Gradle configuration; " +
                        "restart the task so its protocol fingerprint is recomputed."
                )
            }
            val result = resultFile.asFile
            val human = humanFile.asFile
            val manifest = manifestFile.asFile
            val inProgressManifest = inProgressManifestFile.asFile
            val gitRoot = rootProject.projectDir.absolutePath
            val commitOutput = runCommand(listOf("git", "-C", gitRoot, "rev-parse", "HEAD"))
            if (commitOutput.exitCode != 0 || commitOutput.output.isBlank()) {
                throw GradleException(
                    "Unable to resolve open-loop benchmark source commit: ${commitOutput.output}"
                )
            }
            val statusOutput = runCommand(
                listOf("git", "-C", gitRoot, "status", "--porcelain", "--untracked-files=normal")
            )
            if (statusOutput.exitCode != 0) {
                throw GradleException(
                    "Unable to resolve open-loop benchmark source status: ${statusOutput.output}"
                )
            }
            if (formalEvidence && statusOutput.output.isNotBlank()) {
                throw GradleException(
                    "Formal open-loop benchmarks require a clean source tree so the runner " +
                        "can be rebuilt from the recorded commit. Use the smoke task while " +
                        "developing, or commit the exact source before collecting formal evidence."
                )
            }
            if (formalEvidence) {
                val injectedJvmEnvironment = listOf(
                    "JAVA_TOOL_OPTIONS",
                    "_JAVA_OPTIONS",
                    "JDK_JAVA_OPTIONS",
                ).filter { name -> !System.getenv(name).isNullOrBlank() }
                if (injectedJvmEnvironment.isNotEmpty()) {
                    throw GradleException(
                        "Formal open-loop benchmarks reject JVM option injection through " +
                            "${injectedJvmEnvironment.joinToString()}; " +
                            "declare every JVM option in the benchmark profile."
                    )
                }
            }
            manifest.delete()
            result.delete()
            human.delete()
            inProgressManifest.delete()
            result.parentFile.mkdirs()
            val runnerJarFile = jmhJar.get().archiveFile.get().asFile
            val runId = benchmarkRunIdentityService.get().runId
            args("--runId=$runId")
            val inProgress = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "status" to "IN_PROGRESS",
                "engine" to "bounded-open-loop",
                "runId" to runId,
                "taskPath" to path,
                "startedAt" to Instant.now().toString(),
                "projectVersion" to project.version.toString(),
                "source" to linkedMapOf(
                    "commit" to commitOutput.output,
                    "dirty" to statusOutput.output.isNotBlank(),
                    "runnerJarSha256" to fileSha256(runnerJarFile),
                    "orchestratorSha256" to commandProcessedOpenLoopOrchestratorSha256,
                    "runnerSourceSha256" to
                        commandProcessedOpenLoopRunnerSourceSha256,
                ),
                "runSpec" to linkedMapOf(
                    "profile" to spec.profile,
                    "ratePerSecond" to spec.ratePerSecond,
                    "repeat" to spec.repeat,
                    "warmupSeconds" to spec.warmupSeconds,
                    "measurementSeconds" to spec.measurementSeconds,
                    "producerCount" to spec.producerCount,
                    "maxInFlight" to spec.maxInFlight,
                    "requestTimeoutMillis" to spec.requestTimeoutMillis,
                    "watchdogIntervalMillis" to spec.watchdogIntervalMillis,
                    "startLeadMillis" to spec.startLeadMillis,
                    "maxGeneratorMissedRatio" to spec.maxGeneratorMissedRatio,
                    "maxGeneratorLagP99Millis" to spec.maxGeneratorLagP99Millis,
                    "observationMode" to spec.observationMode,
                    "observationDesignId" to
                        commandProcessedOpenLoopObservationDesignId(spec),
                    "observationDesignModes" to spec.observationDesignModes,
                    "observationDesignBlockSize" to spec.observationDesignBlockSize,
                    "observationDesignSequence" to spec.observationDesignSequence,
                    "observationDesignPosition" to spec.observationDesignPosition,
                    "observationDesignBlock" to spec.observationDesignBlockSize
                        ?.let { blockSize -> ((spec.repeat - 1) / blockSize) + 1 },
                    "observationDesignRow" to spec.observationDesignBlockSize
                        ?.let { blockSize -> ((spec.repeat - 1) % blockSize) + 1 },
                    "schedulerPoolSizeToken" to spec.schedulerPoolSize,
                    "schedulerPoolSize" to resolvedSchedulerPoolSize,
                    "stripeCountToken" to spec.stripeCount,
                    "stripeCount" to resolvedStripeCount,
                    "aggregateCardinality" to spec.aggregateCardinality,
                    "jvmArgs" to spec.jvmArgs,
                    "runnerArgs" to runnerArgs,
                    "protocolFingerprint" to
                        commandProcessedOpenLoopProtocolFingerprint(spec),
                ),
                "orchestratorRuntime" to linkedMapOf(
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
        }

        doLast {
            val result = resultFile.asFile
            val human = humanFile.asFile
            val manifest = manifestFile.asFile
            val inProgressManifest = inProgressManifestFile.asFile
            if (!result.isFile || result.length() == 0L) {
                throw GradleException(
                    "Open-loop result file is missing or empty: ${result.absolutePath}"
                )
            }
            if (!human.isFile || human.length() == 0L) {
                throw GradleException(
                    "Open-loop human output is missing or empty: ${human.absolutePath}"
                )
            }
            val parsedResult = JsonSlurper().parseText(result.readText()) as? Map<*, *>
                ?: throw GradleException(
                    "Open-loop result must be a JSON object: ${result.absolutePath}"
                )
            @Suppress("UNCHECKED_CAST")
            val manifestData = LinkedHashMap(
                JsonSlurper().parseText(inProgressManifest.readText()) as Map<String, Any?>
            )
            fun requireResultValue(
                actual: Any?,
                expected: Any?,
                field: String,
            ) {
                if (actual != expected) {
                    throw GradleException(
                        "Open-loop result $field[$actual] does not match run spec[$expected]."
                    )
                }
            }
            fun resultLong(
                source: Map<*, *>,
                field: String,
            ): Long =
                (source[field] as? Number)?.toLong()
                    ?: throw GradleException("Open-loop result is missing numeric $field.")

            fun resultDouble(
                source: Map<*, *>,
                field: String,
            ): Double =
                (source[field] as? Number)?.toDouble()
                    ?: throw GradleException("Open-loop result is missing numeric $field.")

            if (parsedResult["status"] != "SUCCESS") {
                val validity = parsedResult["validity"] as? Map<*, *>
                throw GradleException(
                    "Open-loop runner did not publish SUCCESS: ${parsedResult["status"]}; " +
                        "violations=${validity?.get("violations")}"
                )
            }
            val validity = parsedResult["validity"] as? Map<*, *>
                ?: throw GradleException("Open-loop result is missing top-level validity.")
            if (validity["valid"] != true) {
                throw GradleException(
                    "Open-loop result top-level validity is invalid: ${validity["violations"]}"
                )
            }
            val validityViolations = validity["violations"] as? List<*>
                ?: throw GradleException(
                    "Open-loop result top-level validity is missing violations."
                )
            if (validityViolations.isNotEmpty()) {
                throw GradleException(
                    "Open-loop result claims valid with violations: $validityViolations"
                )
            }
            requireResultValue(
                parsedResult["engine"],
                "bounded-open-loop",
                "engine",
            )
            requireResultValue(
                parsedResult["runId"],
                manifestData["runId"],
                "runId",
            )
            val resultConfig = parsedResult["config"] as? Map<*, *>
                ?: throw GradleException("Open-loop result is missing config.")
            requireResultValue(
                resultLong(resultConfig, "ratePerSecond"),
                spec.ratePerSecond,
                "config.ratePerSecond",
            )
            requireResultValue(
                resultDouble(resultConfig, "warmupSeconds"),
                spec.warmupSeconds.toDouble(),
                "config.warmupSeconds",
            )
            requireResultValue(
                resultDouble(resultConfig, "measurementSeconds"),
                spec.measurementSeconds.toDouble(),
                "config.measurementSeconds",
            )
            requireResultValue(
                resultLong(resultConfig, "producerCount"),
                spec.producerCount.toLong(),
                "config.producerCount",
            )
            requireResultValue(
                resultLong(resultConfig, "maxInFlight"),
                spec.maxInFlight.toLong(),
                "config.maxInFlight",
            )
            requireResultValue(
                resultLong(resultConfig, "requestTimeoutMillis"),
                spec.requestTimeoutMillis,
                "config.requestTimeoutMillis",
            )
            requireResultValue(
                resultLong(resultConfig, "watchdogIntervalMillis"),
                spec.watchdogIntervalMillis,
                "config.watchdogIntervalMillis",
            )
            requireResultValue(
                resultLong(resultConfig, "startLeadMillis"),
                spec.startLeadMillis,
                "config.startLeadMillis",
            )
            requireResultValue(
                resultDouble(resultConfig, "maxGeneratorMissedRatio"),
                spec.maxGeneratorMissedRatio,
                "config.maxGeneratorMissedRatio",
            )
            requireResultValue(
                resultLong(resultConfig, "maxGeneratorLagP99Millis"),
                spec.maxGeneratorLagP99Millis,
                "config.maxGeneratorLagP99Millis",
            )
            requireResultValue(
                resultConfig["observationMode"],
                spec.observationMode,
                "config.observationMode",
            )
            val resultProtocol = parsedResult["protocol"] as? Map<*, *>
                ?: throw GradleException("Open-loop result is missing protocol.")
            requireResultValue(
                resultProtocol["fullObservationCoverage"],
                spec.observationMode == "FULL",
                "protocol.fullObservationCoverage",
            )
            if (
                isFormalOpenLoopProfile(spec.profile) &&
                resultProtocol["fullObservationCoverage"] != true
            ) {
                throw GradleException(
                    "Formal open-loop result does not have FULL observation coverage."
                )
            }
            requireResultValue(
                resultConfig["schedulerPoolSizeToken"],
                spec.schedulerPoolSize,
                "config.schedulerPoolSizeToken",
            )
            requireResultValue(
                resultConfig["stripeCountToken"],
                spec.stripeCount,
                "config.stripeCountToken",
            )
            requireResultValue(
                resultConfig["aggregateCardinality"],
                spec.aggregateCardinality,
                "config.aggregateCardinality",
            )
            val resultSchedulerPoolSize = resultLong(
                resultConfig,
                "schedulerPoolSize",
            )
            val resultStripeCount = resultLong(resultConfig, "stripeCount")
            requireResultValue(
                resultSchedulerPoolSize,
                resolvedSchedulerPoolSize.toLong(),
                "config.schedulerPoolSize",
            )
            requireResultValue(
                resultStripeCount,
                resolvedStripeCount.toLong(),
                "config.stripeCount",
            )
            val parsedResults = parsedResult["results"] as? Map<*, *>
                ?: throw GradleException("Open-loop result is missing results.")
            val invariants = parsedResults["invariants"] as? Map<*, *>
                ?: throw GradleException("Open-loop result is missing invariants.")
            if (invariants["valid"] != true) {
                throw GradleException(
                    "Open-loop result invariants are invalid: ${invariants["violations"]}"
                )
            }
            val childRuntime = parsedResult["runtime"] as? Map<*, *>
                ?: throw GradleException("Open-loop result is missing child runtime.")
            listOf(
                "javaVersion",
                "vmName",
                "vmVersion",
                "javaHome",
                "javaExecutable",
                "osName",
                "osVersion",
                "osArch",
            ).forEach { field ->
                val value = childRuntime[field] as? String
                if (value.isNullOrBlank()) {
                    throw GradleException(
                        "Open-loop child runtime is missing non-blank $field."
                    )
                }
            }
            val childProcessors = resultLong(childRuntime, "availableProcessors")
            if (childProcessors <= 0) {
                throw GradleException(
                    "Open-loop child runtime availableProcessors must be positive."
                )
            }
            val childMaxMemoryBytes = resultLong(childRuntime, "maxMemoryBytes")
            if (childMaxMemoryBytes <= 0) {
                throw GradleException(
                    "Open-loop child runtime maxMemoryBytes must be positive."
                )
            }
            val childJvmInputArguments =
                (childRuntime["jvmInputArguments"] as? List<*>)
                    ?.map { argument ->
                        argument as? String
                            ?: throw GradleException(
                                "Open-loop child JVM input argument must be a string."
                            )
                    }
                    ?: throw GradleException(
                        "Open-loop child runtime is missing jvmInputArguments."
                    )
            spec.jvmArgs.forEach { expectedArgument ->
                if (expectedArgument !in childJvmInputArguments) {
                    throw GradleException(
                        "Open-loop child JVM input arguments are missing " +
                            "configured argument[$expectedArgument]: $childJvmInputArguments"
                    )
                }
            }
            if (childRuntime["effectiveSystemProperties"] !is Map<*, *>) {
                throw GradleException(
                    "Open-loop child runtime is missing effectiveSystemProperties."
                )
            }
            @Suppress("UNCHECKED_CAST")
            val runSpec = LinkedHashMap(
                manifestData["runSpec"] as Map<String, Any?>
            )
            runSpec["resolvedSchedulerPoolSize"] = resolvedSchedulerPoolSize
            runSpec["resolvedStripeCount"] = resolvedStripeCount
            manifestData["runSpec"] = runSpec
            val manifestRuntime = linkedMapOf<String, Any?>()
            childRuntime.forEach { (key, value) ->
                if (key is String) {
                    manifestRuntime[key] = value
                }
            }
            val orchestratorRuntime = manifestData["orchestratorRuntime"] as? Map<*, *>
            manifestRuntime["physicalMemoryBytes"] =
                orchestratorRuntime?.get("physicalMemoryBytes")
            manifestData["runtime"] = manifestRuntime
            manifestData["status"] = "SUCCESS"
            manifestData["completedAt"] = Instant.now().toString()
            manifestData["artifacts"] = linkedMapOf(
                "result" to linkedMapOf(
                    "path" to result.name,
                    "size" to result.length(),
                    "sha256" to fileSha256(result),
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

val commandProcessedOpenLoopSchedulerPoolSize = openLoopPositiveToken(
    propertyName = "benchmarkOpenLoopSchedulerPoolSize",
    defaultValue = "4",
    namedValue = "cpu",
)
val commandProcessedOpenLoopStripeCount = openLoopPositiveToken(
    propertyName = "benchmarkOpenLoopStripeCount",
    defaultValue = "default",
    namedValue = "default",
)
val commandProcessedOpenLoopWarmupSeconds =
    positiveOpenLoopLong("benchmarkOpenLoopWarmupSeconds", 10)
val commandProcessedOpenLoopMeasurementSeconds =
    positiveOpenLoopLong("benchmarkOpenLoopMeasurementSeconds", 20)
val commandProcessedOpenLoopProducerCount =
    positiveOpenLoopInt("benchmarkOpenLoopProducerCount", 16)
val commandProcessedOpenLoopMaxInFlight =
    positiveOpenLoopInt("benchmarkOpenLoopMaxInFlight", 65_536)
val commandProcessedOpenLoopRequestTimeoutMillis =
    positiveOpenLoopLong("benchmarkOpenLoopRequestTimeoutMillis", 5_000)
val commandProcessedOpenLoopWatchdogIntervalMillis =
    positiveOpenLoopLong("benchmarkOpenLoopWatchdogIntervalMillis", 5)
val commandProcessedOpenLoopStartLeadMillis =
    positiveOpenLoopLong("benchmarkOpenLoopStartLeadMillis", 250)
val commandProcessedOpenLoopMaxGeneratorMissedRatio =
    openLoopRatio("benchmarkOpenLoopMaxGeneratorMissedRatio", 0.001)
val commandProcessedOpenLoopMaxGeneratorLagP99Millis =
    positiveOpenLoopLong("benchmarkOpenLoopMaxGeneratorLagP99Millis", 5)
val commandProcessedOpenLoopAggregateCardinality =
    providers.gradleProperty("benchmarkOpenLoopAggregateCardinality").orNull ?: "high"
val commandProcessedOpenLoopRepeats =
    positiveOpenLoopInt("benchmarkOpenLoopRepeats", 3)

val commandProcessedOpenLoopTasks = openLoopRates().flatMap { ratePerSecond ->
    (1..commandProcessedOpenLoopRepeats).map { repeat ->
        registerCommandProcessedOpenLoopTask(
            CommandProcessedOpenLoopTaskSpec(
                taskName = "benchmarkCommandProcessedOpenLoopRate${ratePerSecond}Repeat$repeat",
                profile = "formal",
                ratePerSecond = ratePerSecond,
                repeat = repeat,
                warmupSeconds = commandProcessedOpenLoopWarmupSeconds,
                measurementSeconds = commandProcessedOpenLoopMeasurementSeconds,
                producerCount = commandProcessedOpenLoopProducerCount,
                maxInFlight = commandProcessedOpenLoopMaxInFlight,
                requestTimeoutMillis = commandProcessedOpenLoopRequestTimeoutMillis,
                watchdogIntervalMillis = commandProcessedOpenLoopWatchdogIntervalMillis,
                startLeadMillis = commandProcessedOpenLoopStartLeadMillis,
                maxGeneratorMissedRatio =
                    commandProcessedOpenLoopMaxGeneratorMissedRatio,
                maxGeneratorLagP99Millis =
                    commandProcessedOpenLoopMaxGeneratorLagP99Millis,
                observationMode = "FULL",
                schedulerPoolSize = commandProcessedOpenLoopSchedulerPoolSize,
                stripeCount = commandProcessedOpenLoopStripeCount,
                aggregateCardinality = commandProcessedOpenLoopAggregateCardinality,
                jvmArgs = benchmarkJvmArgs,
            )
        )
    }
}

tasks.register("benchmarkCommandProcessedOpenLoop") {
    description =
        "Runs the formal command PROCESSED bounded-open-loop rate matrix in isolated JVMs."
    group = "benchmark"
    dependsOn(commandProcessedOpenLoopTasks)
}

val commandProcessedOpenLoopSmoke = registerCommandProcessedOpenLoopTask(
    CommandProcessedOpenLoopTaskSpec(
        taskName = "benchmarkCommandProcessedOpenLoopSmoke",
        profile = "smoke",
        ratePerSecond = 1_000,
        repeat = 1,
        warmupSeconds = 1,
        measurementSeconds = 1,
        producerCount = 2,
        maxInFlight = 128,
        requestTimeoutMillis = 2_000,
        watchdogIntervalMillis = 5,
        startLeadMillis = 250,
        maxGeneratorMissedRatio = 0.001,
        maxGeneratorLagP99Millis = 5,
        observationMode = "FULL",
        schedulerPoolSize = "4",
        stripeCount = "default",
        aggregateCardinality = "high",
        jvmArgs = smokeBenchmarkJvmArgs,
    )
)

val commandProcessedOpenLoopObservationModes = openLoopObservationModes(
    propertyName = "benchmarkOpenLoopObservationModes",
    defaultValue =
        "FULL,NO_DEADLINE_WHEEL,NO_SERVER_TRACKER,GENERATOR_ONLY_LATENCY,NO_LATENCY",
)
val commandProcessedOpenLoopObservationRate =
    positiveOpenLoopLong("benchmarkOpenLoopObservationRate", 340_000)
val commandProcessedOpenLoopObservationWarmupSeconds =
    positiveOpenLoopLong("benchmarkOpenLoopObservationWarmupSeconds", 5)
val commandProcessedOpenLoopObservationMeasurementSeconds =
    positiveOpenLoopLong("benchmarkOpenLoopObservationMeasurementSeconds", 10)
val commandProcessedOpenLoopObservationBlockSize =
    minimumBalancedOpenLoopObservationRepeats(
        commandProcessedOpenLoopObservationModes
    )
val commandProcessedOpenLoopObservationRepeats =
    positiveOpenLoopInt(
        "benchmarkOpenLoopObservationRepeats",
        commandProcessedOpenLoopObservationBlockSize,
    ).also { repeatCount ->
        if (repeatCount % commandProcessedOpenLoopObservationBlockSize != 0) {
            throw GradleException(
                "benchmarkOpenLoopObservationRepeats[$repeatCount] must contain complete " +
                    "Williams-balanced blocks of $commandProcessedOpenLoopObservationBlockSize " +
                    "repeats for ${commandProcessedOpenLoopObservationModes.size} mode(s)."
            )
        }
    }

val commandProcessedOpenLoopObservationSpecs =
    (1..commandProcessedOpenLoopObservationRepeats).flatMap { repeat ->
        val orderedModes = orderedOpenLoopObservationModes(
            modes = commandProcessedOpenLoopObservationModes,
            repeat = repeat,
        )
        orderedModes.mapIndexed { position, observationMode ->
            CommandProcessedOpenLoopTaskSpec(
                taskName =
                    "benchmarkCommandProcessedOpenLoopObservation" +
                        observationMode.replace("_", "") +
                        "Repeat$repeat",
                profile = "observer-diagnostic",
                ratePerSecond = commandProcessedOpenLoopObservationRate,
                repeat = repeat,
                warmupSeconds = commandProcessedOpenLoopObservationWarmupSeconds,
                measurementSeconds =
                    commandProcessedOpenLoopObservationMeasurementSeconds,
                producerCount = commandProcessedOpenLoopProducerCount,
                maxInFlight = commandProcessedOpenLoopMaxInFlight,
                requestTimeoutMillis = commandProcessedOpenLoopRequestTimeoutMillis,
                watchdogIntervalMillis =
                    commandProcessedOpenLoopWatchdogIntervalMillis,
                startLeadMillis = commandProcessedOpenLoopStartLeadMillis,
                maxGeneratorMissedRatio =
                    commandProcessedOpenLoopMaxGeneratorMissedRatio,
                maxGeneratorLagP99Millis =
                    commandProcessedOpenLoopMaxGeneratorLagP99Millis,
                observationMode = observationMode,
                schedulerPoolSize = commandProcessedOpenLoopSchedulerPoolSize,
                stripeCount = commandProcessedOpenLoopStripeCount,
                aggregateCardinality = commandProcessedOpenLoopAggregateCardinality,
                jvmArgs = benchmarkJvmArgs,
                observationDesignModes = commandProcessedOpenLoopObservationModes,
                observationDesignSequence = orderedModes,
                observationDesignPosition = position,
                observationDesignBlockSize =
                    commandProcessedOpenLoopObservationBlockSize,
            )
        }
    }

val commandProcessedOpenLoopObservationBlockFingerprint =
    MessageDigest.getInstance("SHA-256")
        .digest(
            commandProcessedOpenLoopObservationSpecs
                .joinToString("\n", transform = ::commandProcessedOpenLoopProtocolFingerprint)
                .toByteArray(Charsets.UTF_8)
        )
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
val commandProcessedOpenLoopObservationBlockManifest =
    layout.projectDirectory.file(
        "results/open-loop/command-processed/" +
            "observer-diagnostic-block-" +
            "$commandProcessedOpenLoopObservationBlockFingerprint.manifest.json"
    )
val commandProcessedOpenLoopObservationBlockInProgressManifest =
    layout.projectDirectory.file(
        "results/open-loop/command-processed/" +
            "observer-diagnostic-block-" +
            "$commandProcessedOpenLoopObservationBlockFingerprint.manifest.in-progress.json"
    )
val prepareCommandProcessedOpenLoopObservationBlock =
    tasks.register("prepareCommandProcessedOpenLoopObservationBlock") {
        description =
            "Invalidates the previous observer-ablation block manifest before leaf execution."
        usesService(benchmarkRunIdentityService)
        outputs.upToDateWhen { false }
        doLast {
            val manifest = commandProcessedOpenLoopObservationBlockManifest.asFile
            val inProgress =
                commandProcessedOpenLoopObservationBlockInProgressManifest.asFile
            manifest.delete()
            inProgress.delete()
            val runId = benchmarkRunIdentityService.get().runId
            publishJsonAtomically(
                inProgress,
                linkedMapOf(
                    "schemaVersion" to 1,
                    "status" to "IN_PROGRESS",
                    "engine" to "bounded-open-loop-observer-block",
                    "runId" to runId,
                    "startedAt" to Instant.now().toString(),
                    "source" to linkedMapOf(
                        "runnerSourceSha256" to
                            commandProcessedOpenLoopRunnerSourceSha256,
                        "orchestratorSha256" to
                            commandProcessedOpenLoopOrchestratorSha256,
                    ),
                    "design" to linkedMapOf(
                        "id" to commandProcessedOpenLoopObservationDesignId(
                            commandProcessedOpenLoopObservationSpecs.first()
                        ),
                        "kind" to "williams-v1",
                        "modes" to commandProcessedOpenLoopObservationModes,
                        "blockSize" to
                            commandProcessedOpenLoopObservationBlockSize,
                        "repeats" to commandProcessedOpenLoopObservationRepeats,
                    ),
                    "expectedLeaves" to
                        commandProcessedOpenLoopObservationSpecs.map { spec ->
                            linkedMapOf(
                                "repeat" to spec.repeat,
                                "mode" to spec.observationMode,
                                "sequence" to spec.observationDesignSequence,
                                "position" to spec.observationDesignPosition,
                                "protocolFingerprint" to
                                    commandProcessedOpenLoopProtocolFingerprint(spec),
                            )
                        },
                )
            )
        }
    }

val commandProcessedOpenLoopObservationTasks =
    commandProcessedOpenLoopObservationSpecs.map { spec ->
        registerCommandProcessedOpenLoopTask(spec).also { task ->
            task.configure {
                dependsOn(prepareCommandProcessedOpenLoopObservationBlock)
            }
        }
    }

commandProcessedOpenLoopObservationTasks.zipWithNext().forEach { (previous, next) ->
    next.configure {
        mustRunAfter(previous)
    }
}

tasks.register("benchmarkCommandProcessedOpenLoopObservationDiagnostic") {
    description =
        "Runs one or more complete benchmark-only observer-ablation blocks; " +
            "results are diagnostic and are not formal capacity evidence."
    group = "benchmark"
    dependsOn(commandProcessedOpenLoopObservationTasks)
    outputs.file(commandProcessedOpenLoopObservationBlockManifest)
    outputs.upToDateWhen { false }
    doLast {
        val expectedRunId = benchmarkRunIdentityService.get().runId
        val parsedLeaves =
            commandProcessedOpenLoopObservationSpecs.map { spec ->
                val manifestFile = file(
                    "results/open-loop/command-processed/" +
                        "${commandProcessedOpenLoopResultPrefix(spec)}.manifest.json"
                )
                if (!manifestFile.isFile || manifestFile.length() == 0L) {
                    throw GradleException(
                        "Observer block leaf manifest is missing: " +
                            manifestFile.absolutePath
                    )
                }
                val manifest =
                    JsonSlurper().parseText(manifestFile.readText()) as? Map<*, *>
                        ?: throw GradleException(
                            "Observer block leaf manifest must be a JSON object: " +
                                manifestFile.absolutePath
                        )
                if (manifest["status"] != "SUCCESS") {
                    throw GradleException(
                        "Observer block leaf is not SUCCESS: ${manifestFile.name}"
                    )
                }
                if (manifest["runId"] != expectedRunId) {
                    throw GradleException(
                        "Observer block leaf runId[${manifest["runId"]}] does not " +
                            "match block runId[$expectedRunId]: ${manifestFile.name}"
                    )
                }
                val runSpec = manifest["runSpec"] as? Map<*, *>
                    ?: throw GradleException(
                        "Observer block leaf is missing runSpec: ${manifestFile.name}"
                    )
                fun requireLeafValue(
                    field: String,
                    expected: Any?,
                ) {
                    val actual = runSpec[field]
                    val normalizedActual =
                        if (actual is Number && expected is Int) {
                            actual.toInt()
                        } else {
                            actual
                        }
                    if (normalizedActual != expected) {
                        throw GradleException(
                            "Observer block leaf ${manifestFile.name} " +
                                "$field[$normalizedActual] does not match[$expected]."
                        )
                    }
                }
                requireLeafValue("repeat", spec.repeat)
                requireLeafValue("observationMode", spec.observationMode)
                requireLeafValue(
                    "observationDesignId",
                    commandProcessedOpenLoopObservationDesignId(spec),
                )
                requireLeafValue(
                    "observationDesignModes",
                    spec.observationDesignModes,
                )
                requireLeafValue(
                    "observationDesignBlockSize",
                    spec.observationDesignBlockSize,
                )
                requireLeafValue(
                    "observationDesignSequence",
                    spec.observationDesignSequence,
                )
                requireLeafValue(
                    "observationDesignPosition",
                    spec.observationDesignPosition,
                )
                requireLeafValue(
                    "protocolFingerprint",
                    commandProcessedOpenLoopProtocolFingerprint(spec),
                )
                val source = manifest["source"] as? Map<*, *>
                    ?: throw GradleException(
                        "Observer block leaf is missing source: ${manifestFile.name}"
                    )
                if (
                    source["runnerSourceSha256"] !=
                    commandProcessedOpenLoopRunnerSourceSha256
                ) {
                    throw GradleException(
                        "Observer block leaf source fingerprint mismatch: " +
                        manifestFile.name
                    )
                }
                if (
                    source["orchestratorSha256"] !=
                    commandProcessedOpenLoopOrchestratorSha256
                ) {
                    throw GradleException(
                        "Observer block leaf orchestrator fingerprint mismatch: " +
                            manifestFile.name
                    )
                }
                if ((source["commit"] as? String).isNullOrBlank()) {
                    throw GradleException(
                        "Observer block leaf source commit is missing: " +
                            manifestFile.name
                    )
                }
                if (source["dirty"] !is Boolean) {
                    throw GradleException(
                        "Observer block leaf source dirty flag is missing: " +
                            manifestFile.name
                    )
                }
                val artifacts = manifest["artifacts"] as? Map<*, *>
                    ?: throw GradleException(
                        "Observer block leaf is missing artifacts: " +
                            manifestFile.name
                    )
                listOf("result", "human").forEach { artifactName ->
                    val artifact = artifacts[artifactName] as? Map<*, *>
                        ?: throw GradleException(
                            "Observer block leaf ${manifestFile.name} is missing " +
                                "$artifactName artifact metadata."
                        )
                    val artifactPath = artifact["path"] as? String
                        ?: throw GradleException(
                            "Observer block leaf ${manifestFile.name} has no " +
                                "$artifactName artifact path."
                        )
                    val artifactFile =
                        manifestFile.parentFile.resolve(artifactPath).canonicalFile
                    val artifactRoot = manifestFile.parentFile.canonicalFile
                    if (
                        !artifactFile.toPath().startsWith(artifactRoot.toPath()) ||
                        !artifactFile.isFile
                    ) {
                        throw GradleException(
                            "Observer block leaf ${manifestFile.name} has an invalid " +
                                "$artifactName artifact path[$artifactPath]."
                        )
                    }
                    val expectedSize = (artifact["size"] as? Number)?.toLong()
                    if (expectedSize == null || artifactFile.length() != expectedSize) {
                        throw GradleException(
                            "Observer block leaf ${manifestFile.name} $artifactName " +
                                "artifact size mismatch."
                        )
                    }
                    val expectedSha256 = artifact["sha256"] as? String
                    if (
                        expectedSha256.isNullOrBlank() ||
                        fileSha256(artifactFile) != expectedSha256
                    ) {
                        throw GradleException(
                            "Observer block leaf ${manifestFile.name} $artifactName " +
                                "artifact SHA-256 mismatch."
                        )
                    }
                }
                Triple(spec, manifestFile, manifest)
            }
        val sourceIdentityValues = parsedLeaves
            .map { (_, _, manifest) ->
                val source = manifest["source"] as Map<*, *>
                source["commit"] to source["dirty"]
            }
            .toSet()
        if (sourceIdentityValues.size != 1) {
            throw GradleException(
                "Observer block mixes source commit/dirty identities: " +
                    sourceIdentityValues
            )
        }
        val runnerJarSha256Values = parsedLeaves
            .map { (_, _, manifest) ->
                (manifest["source"] as Map<*, *>)["runnerJarSha256"]
            }
            .toSet()
        if (
            runnerJarSha256Values.size != 1 ||
            (runnerJarSha256Values.singleOrNull() as? String).isNullOrBlank()
        ) {
            throw GradleException(
                "Observer block mixes runner JARs: $runnerJarSha256Values"
            )
        }
        val completedManifest =
            commandProcessedOpenLoopObservationBlockManifest.asFile
        val inProgressManifest =
            commandProcessedOpenLoopObservationBlockInProgressManifest.asFile
        publishJsonAtomically(
            completedManifest,
            linkedMapOf(
                "schemaVersion" to 1,
                "status" to "SUCCESS",
                "engine" to "bounded-open-loop-observer-block",
                "runId" to expectedRunId,
                "completedAt" to Instant.now().toString(),
                "source" to linkedMapOf(
                    "runnerSourceSha256" to
                        commandProcessedOpenLoopRunnerSourceSha256,
                    "orchestratorSha256" to
                        commandProcessedOpenLoopOrchestratorSha256,
                    "commit" to sourceIdentityValues.single().first,
                    "dirty" to sourceIdentityValues.single().second,
                    "runnerJarSha256" to runnerJarSha256Values.single(),
                ),
                "design" to linkedMapOf(
                    "id" to commandProcessedOpenLoopObservationDesignId(
                        commandProcessedOpenLoopObservationSpecs.first()
                    ),
                    "kind" to "williams-v1",
                    "modes" to commandProcessedOpenLoopObservationModes,
                    "blockSize" to commandProcessedOpenLoopObservationBlockSize,
                    "repeats" to commandProcessedOpenLoopObservationRepeats,
                    "completedBlocks" to
                        commandProcessedOpenLoopObservationRepeats /
                        commandProcessedOpenLoopObservationBlockSize,
                ),
                "leaves" to parsedLeaves.map { (spec, manifestFile, manifest) ->
                    val artifacts = manifest["artifacts"] as Map<*, *>
                    linkedMapOf(
                        "repeat" to spec.repeat,
                        "mode" to spec.observationMode,
                        "sequence" to spec.observationDesignSequence,
                        "position" to spec.observationDesignPosition,
                        "protocolFingerprint" to
                            commandProcessedOpenLoopProtocolFingerprint(spec),
                        "manifestPath" to
                            manifestFile.relativeTo(project.projectDir)
                                .invariantSeparatorsPath,
                        "manifestSha256" to fileSha256(manifestFile),
                        "artifacts" to artifacts,
                    )
                },
            )
        )
        inProgressManifest.delete()
    }
}

val verifyCommandProcessedOpenLoopPolicy =
    tasks.register("verifyCommandProcessedOpenLoopPolicy") {
        description =
            "Verifies formal FULL-observer policy and collision-resistant open-loop artifact identity."
        group = "verification"

        doLast {
            val formal = CommandProcessedOpenLoopTaskSpec(
                taskName = "verification",
                profile = "formal",
                ratePerSecond = 340_000,
                repeat = 1,
                warmupSeconds = 10,
                measurementSeconds = 20,
                producerCount = 16,
                maxInFlight = 65_536,
                requestTimeoutMillis = 5_000,
                watchdogIntervalMillis = 5,
                startLeadMillis = 250,
                maxGeneratorMissedRatio = 0.001,
                maxGeneratorLagP99Millis = 5,
                observationMode = "FULL",
                schedulerPoolSize = "cpu",
                stripeCount = "default",
                aggregateCardinality = "high",
                jvmArgs = benchmarkJvmArgs,
            )
            validateCommandProcessedOpenLoopTaskSpec(formal)
            check(
                runCatching {
                    validateCommandProcessedOpenLoopTaskSpec(
                        formal.copy(observationMode = "NO_LATENCY")
                    )
                }.exceptionOrNull() is GradleException
            )
            val twoModeDesign = listOf("FULL", "NO_LATENCY")
            val twoModeFirstSequence =
                orderedOpenLoopObservationModes(twoModeDesign, repeat = 1)
            val diagnosticNoLatency = formal.copy(
                profile = "observer-diagnostic",
                observationMode = "NO_LATENCY",
                observationDesignModes = twoModeDesign,
                observationDesignSequence = twoModeFirstSequence,
                observationDesignPosition =
                    twoModeFirstSequence.indexOf("NO_LATENCY"),
                observationDesignBlockSize =
                    minimumBalancedOpenLoopObservationRepeats(twoModeDesign),
            )
            validateCommandProcessedOpenLoopTaskSpec(diagnosticNoLatency)
            check(
                runCatching {
                    validateCommandProcessedOpenLoopTaskSpec(
                        formal.copy(profile = "formal-v2")
                    )
                }.exceptionOrNull() is GradleException
            )
            check(normalizeOpenLoopObservationMode("no-server-tracker") == "NO_SERVER_TRACKER")
            check(
                orderedOpenLoopObservationModes(
                    listOf("FULL", "NO_LATENCY"),
                    repeat = 1,
                ) == listOf("FULL", "NO_LATENCY")
            )
            check(
                orderedOpenLoopObservationModes(
                    listOf("FULL", "NO_LATENCY"),
                    repeat = 2,
                ) == listOf("NO_LATENCY", "FULL")
            )
            check(
                (1..6).map { repeat ->
                    orderedOpenLoopObservationModes(
                        listOf("FULL", "GENERATOR_ONLY_LATENCY", "NO_LATENCY"),
                        repeat,
                    )
                } == listOf(
                    listOf("FULL", "GENERATOR_ONLY_LATENCY", "NO_LATENCY"),
                    listOf("GENERATOR_ONLY_LATENCY", "NO_LATENCY", "FULL"),
                    listOf("NO_LATENCY", "FULL", "GENERATOR_ONLY_LATENCY"),
                    listOf("NO_LATENCY", "GENERATOR_ONLY_LATENCY", "FULL"),
                    listOf("FULL", "NO_LATENCY", "GENERATOR_ONLY_LATENCY"),
                    listOf("GENERATOR_ONLY_LATENCY", "FULL", "NO_LATENCY"),
                )
            )
            val fiveModes = listOf("A", "B", "C", "D", "E")
            val balancedRepeatCount =
                minimumBalancedOpenLoopObservationRepeats(fiveModes)
            check(balancedRepeatCount == 10)
            val balancedRows = (1..balancedRepeatCount).map { repeat ->
                orderedOpenLoopObservationModes(fiveModes, repeat)
            }
            fiveModes.indices.forEach { position ->
                val positionCounts = balancedRows
                    .groupingBy { row -> row[position] }
                    .eachCount()
                check(positionCounts.values.toSet() == setOf(2))
                check(positionCounts.keys == fiveModes.toSet())
            }
            val predecessorCounts = balancedRows
                .flatMap { row -> row.zipWithNext() }
                .groupingBy { it }
                .eachCount()
            val expectedPredecessors = fiveModes.flatMap { predecessor ->
                fiveModes
                    .filterNot { it == predecessor }
                    .map { successor -> predecessor to successor }
            }.toSet()
            check(predecessorCounts.keys == expectedPredecessors)
            check(predecessorCounts.values.toSet() == setOf(2))
            val fourModes = listOf("A", "B", "C", "D")
            val evenBalancedRows =
                (1..minimumBalancedOpenLoopObservationRepeats(fourModes)).map { repeat ->
                    orderedOpenLoopObservationModes(fourModes, repeat)
                }
            fourModes.indices.forEach { position ->
                check(
                    evenBalancedRows
                        .groupingBy { row -> row[position] }
                        .eachCount()
                        .values
                        .toSet() == setOf(1)
                )
            }
            val evenPredecessorCounts = evenBalancedRows
                .flatMap { row -> row.zipWithNext() }
                .groupingBy { it }
                .eachCount()
            check(
                evenPredecessorCounts.keys ==
                    fourModes.flatMap { predecessor ->
                        fourModes
                            .filterNot { it == predecessor }
                            .map { successor -> predecessor to successor }
                    }.toSet()
            )
            check(evenPredecessorCounts.values.toSet() == setOf(1))
            check(
                runCatching {
                    validateCommandProcessedOpenLoopTaskSpec(
                        diagnosticNoLatency.copy(observationMode = "PARTIAL")
                    )
                }.exceptionOrNull() is GradleException
            )

            val identical = formal.copy()
            check(
                commandProcessedOpenLoopProtocolFingerprint(formal) ==
                    commandProcessedOpenLoopProtocolFingerprint(identical)
            )
            listOf(
                formal.copy(producerCount = 8),
                formal.copy(watchdogIntervalMillis = 50),
                formal.copy(
                    profile = "smoke",
                    observationMode = "GENERATOR_ONLY_LATENCY",
                ),
            ).forEach { changed ->
                check(
                    commandProcessedOpenLoopProtocolFingerprint(formal) !=
                        commandProcessedOpenLoopProtocolFingerprint(changed)
                )
                check(
                    commandProcessedOpenLoopResultPrefix(formal) !=
                        commandProcessedOpenLoopResultPrefix(changed)
                )
            }
            val fiveModeDesign = listOf(
                "FULL",
                "NO_DEADLINE_WHEEL",
                "NO_SERVER_TRACKER",
                "GENERATOR_ONLY_LATENCY",
                "NO_LATENCY",
            )
            val fiveModeFirstSequence =
                orderedOpenLoopObservationModes(
                    fiveModeDesign,
                    repeat = 1,
                )
            val fiveModeFull = formal.copy(
                profile = "observer-diagnostic",
                observationMode = "FULL",
                observationDesignModes = fiveModeDesign,
                observationDesignSequence = fiveModeFirstSequence,
                observationDesignPosition = fiveModeFirstSequence.indexOf("FULL"),
                observationDesignBlockSize =
                    minimumBalancedOpenLoopObservationRepeats(
                        fiveModeDesign
                    ),
            )
            val twoModeFull = diagnosticNoLatency.copy(
                observationMode = "FULL",
                observationDesignPosition =
                    twoModeFirstSequence.indexOf("FULL"),
            )
            validateCommandProcessedOpenLoopTaskSpec(fiveModeFull)
            validateCommandProcessedOpenLoopTaskSpec(twoModeFull)
            check(
                commandProcessedOpenLoopProtocolFingerprint(fiveModeFull) !=
                    commandProcessedOpenLoopProtocolFingerprint(twoModeFull)
            )
            check(
                commandProcessedOpenLoopResultPrefix(fiveModeFull) !=
                    commandProcessedOpenLoopResultPrefix(twoModeFull)
            )
            val constrainedCpu = formal.copy(
                jvmArgs = benchmarkJvmArgs + "-XX:ActiveProcessorCount=7",
            )
            check(commandProcessedOpenLoopResolvedSchedulerPoolSize(constrainedCpu) == 7)
            check(commandProcessedOpenLoopResolvedStripeCount(constrainedCpu) == 448)
            check(
                commandProcessedOpenLoopProtocolFingerprint(formal) !=
                    commandProcessedOpenLoopProtocolFingerprint(constrainedCpu)
            )
            val explicitDefaultParallelism = formal.copy(
                jvmArgs = benchmarkJvmArgs + "-Dwow.parallelism=257",
            )
            check(commandProcessedOpenLoopResolvedStripeCount(explicitDefaultParallelism) == 257)
            check(
                commandProcessedOpenLoopProtocolFingerprint(formal) !=
                    commandProcessedOpenLoopProtocolFingerprint(explicitDefaultParallelism)
            )
            val explicitReactorPool = formal.copy(
                jvmArgs = benchmarkJvmArgs + "-Dreactor.schedulers.defaultPoolSize=9",
            )
            check(commandProcessedOpenLoopResolvedSchedulerPoolSize(explicitReactorPool) == 9)
            check(
                commandProcessedOpenLoopProtocolFingerprint(formal) !=
                    commandProcessedOpenLoopProtocolFingerprint(explicitReactorPool)
            )
            val prefix = commandProcessedOpenLoopResultPrefix(formal)
            check(
                prefix.contains(
                    "pool-cpu-${commandProcessedOpenLoopResolvedSchedulerPoolSize(formal)}"
                )
            )
            check(
                prefix.contains(
                    "stripes-default-${commandProcessedOpenLoopResolvedStripeCount(formal)}"
                )
            )
            check(prefix.contains("producers-16"))
            check(prefix.contains("watchdog-5ms"))
            check(prefix.contains("observer-full"))
            check(prefix.contains("protocol-"))

            val fingerprintRoot =
                temporaryDir.resolve("runner-source-fingerprint").apply {
                    deleteRecursively()
                }
            val serviceResource = fingerprintRoot.resolve(
                "module/src/main/resources/META-INF/services/example.Service"
            ).apply {
                parentFile.mkdirs()
                writeText("example.Implementation\n")
            }
            val xmlResource = fingerprintRoot.resolve(
                "module/src/main/resources/logback.xml"
            ).apply {
                parentFile.mkdirs()
                writeText("<configuration/>\n")
            }
            val fingerprintFiles =
                commandProcessedOpenLoopRunnerSourceFiles(fingerprintRoot)
            check(fingerprintFiles.contains(serviceResource))
            check(fingerprintFiles.contains(xmlResource))
            val resourceFingerprint =
                computeCommandProcessedOpenLoopRunnerSourceSha256(fingerprintRoot)
            serviceResource.writeText("example.OtherImplementation\n")
            check(
                resourceFingerprint !=
                    computeCommandProcessedOpenLoopRunnerSourceSha256(fingerprintRoot)
            )
        }
    }

tasks.named("check") {
    dependsOn(verifyCommandProcessedOpenLoopPolicy)
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
val batchBenchmarkReportFile = reportsDir.file("quick-batch-command-write-e2e.md")
val infrastructureBenchmarkReportFile = reportsDir.file("quick-infrastructure-e2e.md")
val webFluxBenchmarkReportFile = reportsDir.file("quick-webflux.md")
val baselineGroupedBenchmarkReport = reportsDir.file("baseline-grouped.md")
val baselineComparisonReport = reportsDir.file("baseline-comparison.md")
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
    val formalRegressionSource: Boolean,
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
    val requiredServices: List<BenchmarkRequiredService>,
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
    val parameters: Map<String, String>,
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

fun benchmarkParameters(result: Map<*, *>): Map<String, String> {
    @Suppress("UNCHECKED_CAST")
    val params = result["params"] as? Map<*, *> ?: return emptyMap()
    return params.entries.associate { (key, value) -> key.toString() to value.toString() }
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
            parameters = benchmarkParameters(result),
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

fun manifestRequiredServices(container: Map<*, *>, key: String, source: String): List<BenchmarkRequiredService> {
    val values = container[key] as? List<*>
        ?: throw GradleException("Benchmark manifest is missing array '$key': $source")
    val requiredServices = values.mapIndexed { index, value ->
        val serviceSource = "$key[$index]"
        val service = value as? Map<*, *>
            ?: throw GradleException("Benchmark manifest '$serviceSource' must be an object: $source")
        val serviceName = (service["service"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Benchmark manifest '$serviceSource.service' must be a non-blank string: $source")
        val host = (service["host"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Benchmark manifest '$serviceSource.host' must be a non-blank string: $source")
        val portNumber = service["port"] as? Number
        val port = portNumber?.toLong()
            ?.takeIf { portNumber.toDouble() == it.toDouble() && it in 1..65535 }
            ?.toInt()
            ?: throw GradleException("Benchmark manifest '$serviceSource.port' must be a valid TCP port: $source")
        BenchmarkRequiredService(service = serviceName, host = host, port = port)
    }
    val duplicateServices = requiredServices.groupingBy { it.service }.eachCount().filterValues { it > 1 }.keys
    if (duplicateServices.isNotEmpty()) {
        throw GradleException("Benchmark manifest '$key' contains duplicate services $duplicateServices: $source")
    }
    return requiredServices
}

fun requireManifestValue(actual: Any?, expected: Any?, field: String, source: String) {
    if (actual != expected) {
        throw GradleException(
            "Benchmark manifest '$field' mismatch in $source: expected [$expected], found [$actual]."
        )
    }
}

fun requireManifestServiceIdentity(
    actual: List<BenchmarkRequiredService>,
    expected: List<BenchmarkRequiredService>,
    source: String,
) {
    requireManifestValue(
        actual.map { it.service },
        expected.map { it.service },
        "runSpec.requiredServices services",
        source,
    )
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
    val requiredServices = manifestRequiredServices(runSpec, "requiredServices", sourcePath)
    requireManifestServiceIdentity(requiredServices, group.suite.requiredServices, sourcePath)

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
        requiredServices = requiredServices,
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
        "available processor count" to manifests.map { it.availableProcessors },
        "physical memory bytes" to manifests.map { it.physicalMemoryBytes },
    )
    if (requireSameRunId) {
        comparableFields["run ID"] = manifests.map { it.runId }
    }
    comparableFields.forEach { (field, values) ->
        if (values.distinct().size != 1) {
            throw GradleException("Benchmark manifests mix different $field values for $context: ${values.distinct()}")
        }
    }
    manifests.groupBy { it.suite }.forEach { (suite, suiteManifests) ->
        val requiredServices = suiteManifests.map { it.requiredServices }
        if (requiredServices.distinct().size != 1) {
            throw GradleException(
                "Benchmark manifests mix different required services for suite '$suite' in $context: " +
                    requiredServices.distinct()
            )
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

fun latencyUnitSeconds(unit: String): Double? {
    return when (unit) {
        "s" -> 1.0
        "ms" -> 1.0e-3
        "us", "µs" -> 1.0e-6
        "ns" -> 1.0e-9
        else -> null
    }
}

fun latencyDisplayUnit(secondsPerOp: Double): String {
    val absoluteSeconds = kotlin.math.abs(secondsPerOp)
    return when {
        absoluteSeconds == 0.0 -> "s"
        absoluteSeconds < 1.0e-6 -> "ns"
        absoluteSeconds < 1.0e-3 -> "µs"
        absoluteSeconds < 1.0 -> "ms"
        else -> "s"
    }
}

fun benchmarkMetricScale(values: List<Double>, unit: String): BenchmarkMetricScale {
    val magnitude = values.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
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
            multiplier = sourceSeconds / latencyUnitSeconds(displayUnit)!!,
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
    val formatted = if (kotlin.math.abs(value) < 0.01) {
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
        val scaledError = kotlin.math.abs(it * scale.multiplier)
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

fun formatBenchmarkScore(score: Double, scoreError: Double?, unit: String): FormattedBenchmarkScore {
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
        val formatted = formatBenchmarkMetric(allocation, null, "B/op")
        formatted.scoreWithUnit
    } ?: "-"
}

fun relativeChangePercent(reference: Double, current: Double): Double {
    require(reference > 0.0) { "Comparison reference must be greater than zero: $reference" }
    return (current / reference - 1.0) * 100.0
}

fun reductionPercent(reference: Double, current: Double): Double {
    return -relativeChangePercent(reference, current)
}

fun formatSignedPercent(value: Double): String = String.format(Locale.US, "%+.1f%%", value)

fun formatUnsignedPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

fun formatRatio(reference: Double, current: Double): String {
    require(reference > 0.0) { "Ratio reference must be greater than zero: $reference" }
    return String.format(Locale.US, "%.2f×", current / reference)
}

data class BatchCommandWriteComparison(
    val scenario: String,
    val individual: ParsedBenchmarkResult,
    val sequential: ParsedBenchmarkResult,
    val concurrent: ParsedBenchmarkResult,
)

enum class BatchCommandWriteSignal(
    val method: String,
    val displayName: String,
    val columnLabel: String,
    val role: String,
    val interpretation: String,
) {
    CONTROL(
        method = "sendIndividuallyAndWaitProcessed",
        displayName = "Individual (32 blocks)",
        columnLabel = "Control",
        role = "Control",
        interpretation = "quantifies distortion from one blocking boundary per command",
    ),
    PRIMARY(
        method = "sendBatchSequentialAndWaitProcessed",
        displayName = "Sequential c1",
        columnLabel = "Primary c1",
        role = "Primary framework-cost signal",
        interpretation = "amortizes the harness boundary without introducing command concurrency",
    ),
    SCALING(
        method = "sendBatchConcurrentAndWaitProcessed",
        displayName = "Concurrent c4",
        columnLabel = "Scaling c4",
        role = "Scaling signal",
        interpretation = "adds bounded concurrency and exposes its throughput/allocation trade-off",
    ),
}

val batchCommandWriteMethods = BatchCommandWriteSignal.entries.mapTo(mutableSetOf()) { it.method }

val batchCommandWriteScenarioOrder = listOf(
    "ceiling",
    "noop-store",
    "in-memory-new-aggregate",
)

fun benchmarkMethodName(row: ParsedBenchmarkResult): String {
    return row.benchmark.substringBefore(" (").substringAfterLast('.')
}

fun batchCommandWriteComparisons(rows: List<ParsedBenchmarkResult>): List<BatchCommandWriteComparison> {
    val throughputRows = rows.filter { it.unit.equals("ops/s", ignoreCase = true) }
    val rowsByScenario = throughputRows.groupBy { row ->
        row.parameters["scenario"] ?: throw GradleException(
            "Batch CommandWrite result is missing the required 'scenario' parameter: ${row.benchmark}"
        )
    }
    val unexpectedScenarios = rowsByScenario.keys - batchCommandWriteScenarioOrder.toSet()
    if (unexpectedScenarios.isNotEmpty()) {
        throw GradleException("Unexpected Batch CommandWrite scenarios: ${unexpectedScenarios.sorted()}")
    }
    val missingScenarios = batchCommandWriteScenarioOrder.toSet() - rowsByScenario.keys
    if (missingScenarios.isNotEmpty()) {
        throw GradleException("Missing Batch CommandWrite scenarios: ${missingScenarios.sorted()}")
    }

    return batchCommandWriteScenarioOrder.map { scenario ->
        val scenarioRows = rowsByScenario.getValue(scenario)
        val rowsByMethod = scenarioRows.groupBy(::benchmarkMethodName)
        val unexpectedMethods = rowsByMethod.keys - batchCommandWriteMethods
        if (unexpectedMethods.isNotEmpty()) {
            throw GradleException(
                "Unexpected Batch CommandWrite methods for scenario '$scenario': ${unexpectedMethods.sorted()}"
            )
        }
        fun requiredRow(signal: BatchCommandWriteSignal): ParsedBenchmarkResult {
            val matches = rowsByMethod[signal.method].orEmpty()
            if (matches.size != 1) {
                throw GradleException(
                    "Batch CommandWrite scenario '$scenario' requires exactly one '${signal.method}' throughput row, " +
                        "found ${matches.size}."
                )
            }
            return matches.single()
        }

        BatchCommandWriteComparison(
            scenario = scenario,
            individual = requiredRow(BatchCommandWriteSignal.CONTROL),
            sequential = requiredRow(BatchCommandWriteSignal.PRIMARY),
            concurrent = requiredRow(BatchCommandWriteSignal.SCALING),
        )
    }
}

fun StringBuilder.appendBatchCommandWriteComparisons(rows: List<ParsedBenchmarkResult>) {
    val comparisons = batchCommandWriteComparisons(rows)
    appendLine("## Paired Comparison")
    appendLine()
    appendLine(
        "The same 32-command workload is normalized per command. " +
            "Sequential c1 isolates boundary amortization; Concurrent c4 adds bounded concurrency."
    )
    appendLine()
    appendLine("### Signal Roles")
    appendLine()
    BatchCommandWriteSignal.entries.forEach { signal ->
        appendLine("- **${signal.role}**: `${signal.displayName}` ${signal.interpretation}.")
    }
    appendLine(
        "- These roles define how to read this paired Quick experiment; they do not promote it to a formal regression source."
    )
    appendLine()
    appendLine("### Throughput")
    appendLine()
    appendLine(
        "| Scenario | ${BatchCommandWriteSignal.CONTROL.columnLabel} | " +
            "${BatchCommandWriteSignal.PRIMARY.columnLabel} | vs Control | " +
            "${BatchCommandWriteSignal.SCALING.columnLabel} | vs Control | c4 / c1 |"
    )
    appendLine("|----------|------------------------|---------------|---------------|---------------|---------------|---------|")
    comparisons.forEach { comparison ->
        val individual = formatBenchmarkScore(
            comparison.individual.score,
            comparison.individual.scoreError,
            comparison.individual.unit,
        )
        val sequential = formatBenchmarkScore(
            comparison.sequential.score,
            comparison.sequential.scoreError,
            comparison.sequential.unit,
        )
        val concurrent = formatBenchmarkScore(
            comparison.concurrent.score,
            comparison.concurrent.scoreError,
            comparison.concurrent.unit,
        )
        appendLine(
            "| `${comparison.scenario}` | ${individual.scoreWithUnit} | ${sequential.scoreWithUnit} | " +
                "${formatSignedPercent(relativeChangePercent(comparison.individual.score, comparison.sequential.score))} | " +
                "${concurrent.scoreWithUnit} | " +
                "${formatSignedPercent(relativeChangePercent(comparison.individual.score, comparison.concurrent.score))} | " +
                "${formatRatio(comparison.sequential.score, comparison.concurrent.score)} |"
        )
    }
    appendLine()
    appendLine("Higher throughput is better. Changes use unrounded JMH scores.")
    appendLine()
    appendLine("### Allocation per Command")
    appendLine()
    appendLine(
        "| Scenario | ${BatchCommandWriteSignal.CONTROL.columnLabel} | " +
            "${BatchCommandWriteSignal.PRIMARY.columnLabel} | Reduction vs Control | " +
            "${BatchCommandWriteSignal.SCALING.columnLabel} | Reduction vs Control | c4 / c1 |"
    )
    appendLine("|----------|------------------------|---------------|-----------|---------------|-----------|---------|")
    comparisons.forEach { comparison ->
        val individual = comparison.individual.allocationBytesPerOp
            ?: throw GradleException("Missing individual allocation for Batch scenario '${comparison.scenario}'.")
        val sequential = comparison.sequential.allocationBytesPerOp
            ?: throw GradleException("Missing sequential allocation for Batch scenario '${comparison.scenario}'.")
        val concurrent = comparison.concurrent.allocationBytesPerOp
            ?: throw GradleException("Missing concurrent allocation for Batch scenario '${comparison.scenario}'.")
        appendLine(
            "| `${comparison.scenario}` | ${formatAllocationBytes(individual)} | " +
                "${formatAllocationBytes(sequential)} | " +
                "${formatUnsignedPercent(reductionPercent(individual, sequential))} | " +
                "${formatAllocationBytes(concurrent)} | " +
                "${formatUnsignedPercent(reductionPercent(individual, concurrent))} | " +
                "${formatRatio(sequential, concurrent)} |"
        )
    }
    appendLine()
    appendLine(
        "Lower allocation is better. Reduction is relative to the Control; c4 / c1 makes the concurrency trade-off explicit."
    )
    appendLine()
}

val verifyBenchmarkReportFormatting = tasks.register("verifyBenchmarkReportFormatting") {
    description = "Verify human-readable benchmark metric formatting."
    group = "verification"

    doLast {
        check(formatBenchmarkScore(1_573.91, 42.0, "ops/s") == FormattedBenchmarkScore("1.57", "±0.04", "k ops/s"))
        check(
            formatBenchmarkScore(668_849_367.69, 1_240_000.0, "ops/s") ==
                FormattedBenchmarkScore("668.85", "±1.24", "M ops/s")
        )
        check(formatBenchmarkScore(0.000_85, 0.000_02, "ms/op") == FormattedBenchmarkScore("850", "±20", "ns/op"))
        check(formatBenchmarkScore(1_573.91, 42.0, "ops/s").scoreWithUnit == "1.57 k ops/s")
        check(formatBenchmarkScore(1_573.91, 42.0, "ops/s").errorWithUnit == "±0.04 k ops/s")
        check(formatBenchmarkScore(1_573.91, null, "ops/s").errorWithUnit == "-")
        check(formatAllocationBytes(2_982_851.6) == "2.84 MiB/op")
        check(formatAllocationBytes(272.0) == "272 B/op")
        check(formatAllocationBytes(0.0) == "0 B/op")
        check(formatMetricNumber(0.004_2) == "0.0042")
        check(formatMetricError(0.004_2, BenchmarkMetricScale(1.0, "ops/s")) == "±<0.01")
        check(formatAllocationBytes(null) == "-")
        check(relativeChangePercent(100.0, 125.0) == 25.0)
        check(reductionPercent(100.0, 25.0) == 75.0)
        check(formatSignedPercent(25.04) == "+25.0%")
        check(formatUnsignedPercent(75.04) == "75.0%")
        check(formatRatio(100.0, 197.0) == "1.97×")
    }
}

val verifyBenchmarkRequiredServiceManifest = tasks.register("verifyBenchmarkRequiredServiceManifest") {
    description = "Verify benchmark required-service manifest parsing and identity rules."
    group = "verification"

    doLast {
        val manifest = mapOf(
            "requiredServices" to listOf(
                mapOf("service" to "Redis", "host" to "redis.internal", "port" to 6380),
                mapOf("service" to "MongoDB", "host" to "mongo.internal", "port" to 27018),
            )
        )
        val requiredServices = manifestRequiredServices(manifest, "requiredServices", "verification")
        check(
            requiredServices == listOf(
                BenchmarkRequiredService("Redis", "redis.internal", 6380),
                BenchmarkRequiredService("MongoDB", "mongo.internal", 27018),
            )
        )
        check(
            formatRequiredServiceEndpoints(requiredServices) ==
                "Redis=redis.internal:6380, MongoDB=mongo.internal:27018"
        )
        requireManifestServiceIdentity(
            actual = requiredServices,
            expected = listOf(
                BenchmarkRequiredService("Redis", "localhost", 6379),
                BenchmarkRequiredService("MongoDB", "localhost", 27017),
            ),
            source = "verification",
        )
        check(
            runCatching {
                requireManifestServiceIdentity(
                    actual = requiredServices,
                    expected = listOf(BenchmarkRequiredService("Redis", "localhost", 6379)),
                    source = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                manifestRequiredServices(
                    mapOf(
                        "requiredServices" to listOf(
                            mapOf("service" to "Redis", "host" to "localhost", "port" to 0),
                        )
                    ),
                    "requiredServices",
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                manifestRequiredServices(
                    mapOf(
                        "requiredServices" to listOf(
                            mapOf("service" to "Redis", "host" to "redis-a", "port" to 6379),
                            mapOf("service" to "Redis", "host" to "redis-b", "port" to 6380),
                        )
                    ),
                    "requiredServices",
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
    }
}

tasks.named("check") {
    dependsOn(verifyBenchmarkReportFormatting)
    dependsOn(verifyBenchmarkRequiredServiceManifest)
}

fun StringBuilder.appendBenchmarkTable(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Suite | Benchmark | Threads | Mode | Score | Error | gc.alloc.rate.norm |")
    appendLine("|-------|-----------|---------|------|-------|-------|-------------------|")
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
                    "${score.scoreWithUnit} | ${score.errorWithUnit} | " +
                    "${formatAllocationBytes(row.allocationBytesPerOp)} |"
            )
        }
}

fun StringBuilder.appendThroughputBottlenecks(rows: List<ParsedBenchmarkResult>) {
    appendLine("| Suite | Threads | Benchmark | Score | Error |")
    appendLine("|-------|---------|-----------|-------|-------|")
    rows.filter { it.unit.contains("ops", ignoreCase = true) }
        .sortedBy { it.score }
        .take(10)
        .forEach { row ->
            val score = formatBenchmarkScore(row.score, row.scoreError, row.unit)
            appendLine(
                "| ${row.suite.displayName} | ${row.threads} | ${row.displayName} | " +
                    "${score.scoreWithUnit} | ${score.errorWithUnit} |"
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
    appendLine("| Suite | Threads | Benchmark | Mode | Allocation | Allocation Error | Score |")
    appendLine("|-------|---------|-----------|------|------------|------------------|-------|")
    allocationBottleneckRows(rows)
        .forEach { row ->
            val allocation = formatBenchmarkMetric(
                score = row.allocationBytesPerOp!!,
                scoreError = row.allocationErrorBytesPerOp,
                unit = "B/op",
            )
            val score = formatBenchmarkScore(row.score, row.scoreError, row.unit)
            appendLine(
                "| ${row.suite.displayName} | ${row.threads} | ${row.displayName} | ${row.mode} | " +
                    "${allocation.scoreWithUnit} | ${allocation.errorWithUnit} | ${score.scoreWithUnit} |"
            )
        }
}

fun StringBuilder.appendBenchmarkValueGuide() {
    appendLine("## Reading Values")
    appendLine()
    appendLine("- Throughput uses decimal prefixes: `k` = 1,000, `M` = 1,000,000, `G` = 1,000,000,000.")
    appendLine("- Allocation uses binary prefixes: `KiB` = 1,024 bytes, `MiB` = 1,048,576 bytes.")
    appendLine("- Every displayed score and error keeps its scaled unit attached, for example `1.57 k ops/s`.")
    appendLine("- Average latency is automatically scaled to `ns/op`, `µs/op`, `ms/op`, or `s/op`.")
    appendLine("- `±` is the JMH-reported error. Scaling changes presentation only; calculations keep raw precision.")
    appendLine()
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
    if (spec.formalRegressionSource) {
        sb.appendLine(
            "- Baseline E2E is the formal regression source for its exact synchronous workloads; " +
                "it is not a production capacity model."
        )
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
    sb.appendLine(
        "- Single-command blocking rows are synchronous round-trip regression controls. " +
            "Use Batch CommandWrite Sequential c1 as the primary framework-cost signal."
    )
    sb.appendLine("- Infrastructure E2E results reflect real Redis or Mongo persistence paths when services are available.")
    sb.appendLine(
        "- No-snapshot growing-stream scenarios are diagnostics for replay pressure, not default E2E goals."
    )
    sb.appendLine("- Component results explain bottlenecks and are not standalone performance goals.")
    sb.appendLine("- Smoke results are excluded from performance reports.")
    sb.appendLine()
    sb.appendBenchmarkValueGuide()
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
        val formalRegressionSource =
            group.profile.id == baselineE2EProfile.id && group.suite.formalRegressionSource
        sb.appendLine("- **Formal Regression Source**: ${if (formalRegressionSource) "yes" else "no"}")
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
    appendBeforeResults: StringBuilder.(List<ParsedBenchmarkResult>) -> Unit = {},
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
    sb.appendBenchmarkValueGuide()
    sb.appendBenchmarkRunProvenance(groupReport.manifests)
    sb.appendBenchmarkEnvironment(project.version.toString(), group.profile)
    if (includeInfrastructureRuntime) {
        sb.appendInfrastructureRuntime()
    }
    sb.appendBeforeResults(groupReport.rows)
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
    sb.appendBenchmarkValueGuide()
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
                "Use Baseline E2E runs for formal regression checks of the exact synchronous workloads. " +
                "Framework E2E isolates command pipeline overhead with in-memory or noop stores; " +
                "its single-command blocking rows are controls, not production capacity signals.",
        )

        val outputFile = benchmarkReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateBatchBenchmarkReport") {
    description = "Generate quick Batch CommandWrite E2E benchmark report from JMH JSON results."
    group = "benchmark"
    mustRunAfter("benchmarkQuickBatchE2E")
    outputs.file(batchBenchmarkReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickBatchE2ETaskSpec),
            title = "Quick Batch CommandWrite E2E Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickBatchE2E " +
                ":wow-benchmarks:generateBatchBenchmarkReport",
            description = "Quick Batch CommandWrite E2E compares 32 individual blocking boundaries with " +
                "one sequential or concurrent reactive batch boundary. JMH normalizes scores per command, " +
                "so the results isolate the net effect of amortizing per-block overhead. " +
                "Sequential c1 is the primary framework-cost signal; Concurrent c4 is a scaling signal; " +
                "Individual blocks is the control.",
            appendBeforeResults = { rows -> appendBatchCommandWriteComparisons(rows) },
        )

        val outputFile = batchBenchmarkReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Batch CommandWrite benchmark report generated: ${outputFile.absolutePath}")
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
                formalRegressionSource = true,
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
                formalRegressionSource = false,
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

fun confidenceIntervalsOverlap(
    baseline: Double,
    baselineError: Double?,
    current: Double,
    currentError: Double?,
): Boolean? {
    if (baselineError == null || currentError == null) {
        return null
    }
    val baselineErrorMagnitude = kotlin.math.abs(baselineError)
    val currentErrorMagnitude = kotlin.math.abs(currentError)
    val baselineLower = baseline - baselineErrorMagnitude
    val baselineUpper = baseline + baselineErrorMagnitude
    val currentLower = current - currentErrorMagnitude
    val currentUpper = current + currentErrorMagnitude
    return baselineLower <= currentUpper && currentLower <= baselineUpper
}

fun BenchmarkMetricComparison.status(): String {
    val delta = deltaPercent ?: return "STABLE"
    val regression = if (higherIsBetter) {
        delta < -thresholdPercent
    } else {
        delta > thresholdPercent
    }
    val improvement = if (higherIsBetter) {
        delta > thresholdPercent
    } else {
        delta < -thresholdPercent
    }
    if (!regression && !improvement) {
        return "STABLE"
    }
    val metricStatus = metric.uppercase(Locale.US)
    val intervalsOverlap = confidenceIntervalsOverlap(
        baseline = baseline,
        baselineError = baselineError,
        current = current,
        currentError = currentError,
    )
    if (intervalsOverlap != false) {
        return "${metricStatus}_INCONCLUSIVE"
    }
    return if (regression) {
        "${metricStatus}_REGRESSION_CANDIDATE"
    } else {
        "${metricStatus}_IMPROVEMENT_CANDIDATE"
    }
}

val verifyBenchmarkComparisonClassification = tasks.register("verifyBenchmarkComparisonClassification") {
    description = "Verify benchmark threshold and uncertainty classification."
    group = "verification"

    doLast {
        check(
            parseBenchmarkParameters(
                "benchmarkConfirmE2EParameters",
                "scenario=ceiling;schedulerStrategy=IMMEDIATE,PARALLEL",
            ) == linkedMapOf(
                "scenario" to "ceiling",
                "schedulerStrategy" to "IMMEDIATE,PARALLEL",
            )
        )
        check(
            runCatching {
                parseBenchmarkParameters("benchmarkConfirmE2EParameters", "scenario")
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                parseBenchmarkParameters(
                    "benchmarkConfirmE2EParameters",
                    "scenario=ceiling;scenario=noop-store",
                )
            }.exceptionOrNull() is GradleException
        )
        check(confidenceIntervalsOverlap(100.0, 10.0, 85.0, 10.0) == true)
        check(confidenceIntervalsOverlap(100.0, 5.0, 80.0, 5.0) == false)
        check(confidenceIntervalsOverlap(100.0, null, 80.0, 5.0) == null)

        val noisyRegression = BenchmarkMetricComparison(
            key = "test",
            metric = "throughput",
            displayName = "test",
            mode = "thrpt",
            threads = 1,
            baseline = 100.0,
            baselineError = 10.0,
            current = 80.0,
            currentError = 15.0,
            unit = "ops/s",
            deltaPercent = -20.0,
            thresholdPercent = 10.0,
            higherIsBetter = true,
        )
        check(noisyRegression.status() == "THROUGHPUT_INCONCLUSIVE")
        check(noisyRegression.copy(currentError = 5.0).status() == "THROUGHPUT_REGRESSION_CANDIDATE")
        check(noisyRegression.copy(currentError = null).status() == "THROUGHPUT_INCONCLUSIVE")
        check(
            noisyRegression.copy(
                current = 120.0,
                currentError = 5.0,
                deltaPercent = 20.0,
            ).status() == "THROUGHPUT_IMPROVEMENT_CANDIDATE"
        )
    }
}

tasks.named("check") {
    dependsOn(verifyBenchmarkComparisonClassification)
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

fun renderBenchmarkComparisonReport(
    baselineFile: File,
    baseline: Map<String, BenchmarkComparisonRow>,
    latest: Map<String, BenchmarkComparisonRow>,
    comparisons: List<BenchmarkMetricComparison>,
): String {
    val allBenchmarks = (baseline.keys + latest.keys).sorted()
    val comparisonsByKey = comparisons.groupBy { it.key }
    val regressionCandidates = comparisons.count { it.status().endsWith("_REGRESSION_CANDIDATE") }
    val improvementCandidates = comparisons.count { it.status().endsWith("_IMPROVEMENT_CANDIDATE") }
    val inconclusive = comparisons.count { it.status().endsWith("_INCONCLUSIVE") }
    val stable = comparisons.size - regressionCandidates - improvementCandidates - inconclusive
    val actionableComparisons = comparisons.filter { it.status() != "STABLE" }
    val coverageChanges = allBenchmarks.count { benchmark ->
        baseline[benchmark] == null || latest[benchmark] == null
    }
    val baselinePath = baselineFile.relativeTo(rootProject.projectDir).invariantSeparatorsPath

    return buildString {
        appendLine("# Framework E2E Baseline Comparison")
        appendLine()
        appendLine("- **Accepted Baseline**: `$baselinePath`")
        appendLine(
            "- **Thresholds**: throughput=${benchmarkThroughputRegressionPercent}%, " +
                "latency=${benchmarkLatencyRegressionPercent}%, " +
                "allocation=${benchmarkAllocationRegressionPercent}%"
        )
        appendLine(
            "- **Classification**: `REGRESSION_CANDIDATE`/`IMPROVEMENT_CANDIDATE` requires both a " +
                "threshold crossing and non-overlapping JMH error intervals; `INCONCLUSIVE` crosses " +
                "the threshold but has overlapping or unavailable intervals."
        )
        appendLine(
            "- **Interpretation**: JMH error describes measurement uncertainty inside one run, not " +
                "cross-run machine variance. Candidates are investigation signals and do not fail comparison; " +
                "confirm them with a controlled targeted rerun before treating them as regressions."
        )
        appendLine()
        appendLine(
            "**Summary:** $regressionCandidates regression candidate(s), " +
                "$improvementCandidates improvement candidate(s), " +
                "$inconclusive inconclusive comparison(s), $stable stable metric comparison(s), " +
                "$coverageChanges coverage change(s)."
        )
        appendLine()
        appendLine("## Actionable Signals")
        appendLine()
        appendLine("| Status | Metric | Benchmark | Threads | Baseline | Current | Delta |")
        appendLine("|--------|--------|-----------|---------|----------|---------|-------|")
        actionableComparisons.forEach { comparison ->
            val scale = benchmarkMetricScale(
                values = listOf(comparison.baseline, comparison.current),
                unit = comparison.unit,
            )
            val baselineScore = formatScaledBenchmarkScore(
                comparison.baseline,
                comparison.baselineError,
                scale,
            )
            val currentScore = formatScaledBenchmarkScore(
                comparison.current,
                comparison.currentError,
                scale,
            )
            appendLine(
                "| ${comparison.status()} | ${comparison.metric} | ${comparison.displayName} | " +
                    "${comparison.threads} | ${baselineScore.scoreWithUnit} | " +
                    "${currentScore.scoreWithUnit} | " +
                    "${comparison.deltaPercent?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "n/a"} |"
            )
        }
        appendLine()
        appendLine("## Full Comparison")
        appendLine()
        appendLine(
            "| Metric | Benchmark | Threads | Mode | Baseline | Baseline Error | Current | " +
                "Current Error | Delta | Threshold | Status |"
        )
        appendLine(
            "|--------|-----------|---------|------|----------|----------------|---------|" +
                "---------------|-------|-----------|--------|"
        )

        allBenchmarks.forEach { benchmark ->
            val baseRow = baseline[benchmark]
            val latestRow = latest[benchmark]

            if (baseRow == null) {
                val newRow = requireNotNull(latestRow)
                val newScore = formatBenchmarkScore(newRow.score, newRow.scoreError, newRow.unit)
                appendLine(
                    "| result | ${newRow.displayName} | ${newRow.threads} | ${newRow.mode} | " +
                        "- | - | ${newScore.scoreWithUnit} | ${newScore.errorWithUnit} | NEW | - | NEW |"
                )
                return@forEach
            }
            if (latestRow == null) {
                val removedScore = formatBenchmarkScore(baseRow.score, baseRow.scoreError, baseRow.unit)
                appendLine(
                    "| result | ${baseRow.displayName} | ${baseRow.threads} | ${baseRow.mode} | " +
                        "${removedScore.scoreWithUnit} | ${removedScore.errorWithUnit} | - | - | " +
                        "REMOVED | - | REMOVED |"
                )
                return@forEach
            }
            comparisonsByKey.getValue(benchmark)
                .forEach { comparison ->
                    val scale = benchmarkMetricScale(
                        values = listOf(comparison.baseline, comparison.current),
                        unit = comparison.unit,
                    )
                    val baselineScore = formatScaledBenchmarkScore(
                        comparison.baseline,
                        comparison.baselineError,
                        scale,
                    )
                    val currentScore = formatScaledBenchmarkScore(
                        comparison.current,
                        comparison.currentError,
                        scale,
                    )
                    appendLine(
                        "| ${comparison.metric} | ${comparison.displayName} | ${comparison.threads} | " +
                            "${comparison.mode} | ${baselineScore.scoreWithUnit} | " +
                            "${baselineScore.errorWithUnit} | ${currentScore.scoreWithUnit} | " +
                            "${currentScore.errorWithUnit} | " +
                            "${comparison.deltaPercent?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "n/a"} | " +
                            "${String.format(Locale.US, "%.1f%%", comparison.thresholdPercent)} | " +
                            "${comparison.status()} |"
                    )
                }
        }
    }
}

tasks.register("benchmarkCompare") {
    description = "Compare primary framework E2E benchmark results against baseline."
    group = "benchmark"
    mustRunAfter(baselineE2ETaskSpec.taskName)
    outputs.file(baselineComparisonReport)
    outputs.upToDateWhen { false }

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

        val regressionCandidates = comparisons.count { it.status().endsWith("_REGRESSION_CANDIDATE") }
        val coverageChanges = allBenchmarks.count { benchmark ->
            baseline[benchmark] == null || latest[benchmark] == null
        }
        val report = renderBenchmarkComparisonReport(
            baselineFile = baselineFile,
            baseline = baseline,
            latest = latest,
            comparisons = comparisons,
        )
        val reportFile = baselineComparisonReport.asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)
        println()
        println(report)
        logger.lifecycle("Benchmark comparison report generated: ${reportFile.absolutePath}")

        if (coverageChanges > 0) {
            throw GradleException("Benchmark coverage changed: $coverageChanges new or removed result row(s)")
        }
        if (regressionCandidates > 0) {
            logger.warn(
                "Benchmark regression candidates detected: $regressionCandidates. " +
                    "Run controlled targeted confirmation before treating them as regressions."
            )
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
        val manifests = benchmarkReport.manifests
        val manifestThreads = manifests.map { it.threads }
        if (manifestThreads != baselineE2EProfile.threads) {
            throw GradleException(
                "Benchmark baseline manifests do not preserve the requested thread order: " +
                    "expected ${baselineE2EProfile.threads}, found $manifestThreads."
            )
        }
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
