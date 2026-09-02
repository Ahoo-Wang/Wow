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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean
import me.ahoo.wow.benchmark.buildlogic.BenchmarkMatrixSpec
import me.ahoo.wow.benchmark.buildlogic.BenchmarkParameterComparisonSpec
import me.ahoo.wow.benchmark.buildlogic.BenchmarkResultRow
import me.ahoo.wow.benchmark.buildlogic.benchmarkMetricScale
import me.ahoo.wow.benchmark.buildlogic.formatAllocationBytes
import me.ahoo.wow.benchmark.buildlogic.formatBenchmarkMetric
import me.ahoo.wow.benchmark.buildlogic.formatBenchmarkScore
import me.ahoo.wow.benchmark.buildlogic.formatMetricNumber
import me.ahoo.wow.benchmark.buildlogic.formatRatio
import me.ahoo.wow.benchmark.buildlogic.formatScaledBenchmarkScore
import me.ahoo.wow.benchmark.buildlogic.formatSignedPercent
import me.ahoo.wow.benchmark.buildlogic.formatUnsignedPercent
import me.ahoo.wow.benchmark.buildlogic.reductionPercent
import me.ahoo.wow.benchmark.buildlogic.relativeChangePercent
import me.ahoo.wow.benchmark.buildlogic.renderBenchmarkParameterComparison
import me.ahoo.wow.benchmark.buildlogic.validateBenchmarkMatrix
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
    val requiresCleanSource: Boolean = false,
    val requiredServices: List<BenchmarkRequiredService> = emptyList(),
    val runMetadata: Map<String, String> = emptyMap(),
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
) {
    maxParallelUsages.set(1)
}

data class DockerContainerRuntime(
    val service: String,
    val label: String,
    val containerName: String,
    val containerId: String?,
    val image: String?,
    val imageId: String?,
    val repoDigests: List<String>,
    val startedAt: String?,
    val running: Boolean?,
    val connectedAddress: String?,
    val publishedPorts: List<DockerPublishedPortBinding>,
    val performanceConfiguration: Map<String, String>,
    val composeProject: String?,
    val composeService: String?,
    val composeConfigHash: String?,
    val composeConfigFiles: String?,
    val configurationSha256: String?,
)

data class DockerPublishedPortBinding(
    val containerPort: Int,
    val protocol: String,
    val hostIp: String,
    val hostPort: Int,
)

fun DockerPublishedPortBinding.toRunSpec(): Map<String, Any> {
    return linkedMapOf(
        "containerPort" to containerPort,
        "protocol" to protocol,
        "hostIp" to hostIp,
        "hostPort" to hostPort,
    )
}

fun DockerContainerRuntime.toRunSpec(): Map<String, Any?> {
    return linkedMapOf(
        "service" to service,
        "label" to label,
        "containerName" to containerName,
        "containerId" to containerId,
        "image" to image,
        "imageId" to imageId,
        "repoDigests" to repoDigests,
        "startedAt" to startedAt,
        "running" to running,
        "connectedAddress" to connectedAddress,
        "publishedPorts" to publishedPorts.map(DockerPublishedPortBinding::toRunSpec),
        "performanceConfiguration" to performanceConfiguration,
        "composeProject" to composeProject,
        "composeService" to composeService,
        "composeConfigHash" to composeConfigHash,
        "composeConfigFiles" to composeConfigFiles,
        "configurationSha256" to configurationSha256,
    )
}

data class BenchmarkInfrastructureRuntime(
    val capturedAt: String,
    val clientLocation: String,
    val dockerServer: String?,
    val containers: List<DockerContainerRuntime>,
)

fun BenchmarkInfrastructureRuntime.toRunSpec(): Map<String, Any?> {
    return linkedMapOf(
        "capturedAt" to capturedAt,
        "clientLocation" to clientLocation,
        "dockerServer" to dockerServer,
        "containers" to containers.map(DockerContainerRuntime::toRunSpec),
    )
}

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

fun benchmarkParametersProperty(
    propertyName: String,
    defaultParameters: Map<String, String> = emptyMap(),
): Map<String, String> {
    return providers.gradleProperty(propertyName)
        .map { value -> parseBenchmarkParameters(propertyName, value) }
        .getOrElse(defaultParameters)
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

val mongoBatchQuickCurrentOptions = "128x1000us"
val mongoBatchQuickCandidateOptions = "192x250us"
val quickBatchRegenerateAggregateSnapshotLaneCounts = listOf(1, 2, 4)

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

val quickMongoBatchAppendProfile = quickProfile.copy(
    threads = benchmarkThreadsProperty("benchmarkQuickMongoBatchThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt", "avgt"),
)

val quickMongoSnapshotBatchSaveProfile = quickProfile.copy(
    id = "quick-mongo-snapshot-batch-save",
    threads = benchmarkThreadsProperty("benchmarkQuickMongoSnapshotBatchThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt", "avgt"),
    parameters = mapOf("batchOptions" to mongoBatchQuickCurrentOptions),
)

val quickBatchRegenerateAggregateSnapshotProfile = quickProfile.copy(
    id = "quick-batch-regenerate-aggregate-snapshot",
    threads = benchmarkThreadsProperty("benchmarkQuickBatchRegenerateSnapshotThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt", "avgt"),
    parameters = mapOf(
        "batchOptions" to mongoBatchQuickCurrentOptions,
        "laneCount" to quickBatchRegenerateAggregateSnapshotLaneCounts.joinToString(","),
    ),
)

val quickMongoBatchCandidateE2EProfile = BenchmarkRunProfile(
    id = "quick-mongo-candidate-e2e",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 1,
    measurementTime = "3s",
    forks = 1,
    threads = listOf(1, 4),
    benchmarkModes = listOf("thrpt", "avgt"),
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
    parameters = mapOf("batchOptions" to mongoBatchQuickCandidateOptions),
)

val quickMongoBatchCoordinatorConcurrencyProfile = BenchmarkRunProfile(
    id = "quick-mongo-coordinator-concurrency",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 1,
    measurementTime = "3s",
    forks = 1,
    threads = listOf(4),
    benchmarkModes = listOf("thrpt", "avgt"),
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
    parameters = linkedMapOf(
        "batchOptions" to mongoBatchQuickCandidateOptions,
        "coordinatorLanes" to "1,2,4",
    ),
)

val quickMongoBatchOptionsPairedProfile = BenchmarkRunProfile(
    id = "quick-mongo-options-paired",
    warmupIterations = 1,
    warmupTime = "2s",
    measurementIterations = 1,
    measurementTime = "3s",
    forks = 1,
    threads = listOf(1, 4),
    benchmarkModes = listOf("thrpt"),
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val quickElasticsearchBatchAppendProfile = quickProfile.copy(
    threads = benchmarkThreadsProperty("benchmarkQuickElasticsearchBatchThreads", listOf(1, 4)),
    benchmarkModes = listOf("thrpt", "avgt"),
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

val confirmationMongoBatchAppendProfile = baselineInfrastructureProfile.copy(
    id = "confirmation",
    threads = benchmarkThreadsProperty("benchmarkConfirmMongoBatchThreads", listOf(1, 4)),
)

val confirmationElasticsearchBatchAppendProfile = baselineInfrastructureProfile.copy(
    id = "confirmation",
    threads = benchmarkThreadsProperty("benchmarkConfirmElasticsearchBatchThreads", listOf(1, 4)),
)

val storageBatchTuningOptions = listOf(16, 32, 64, 128, 256, 512)
    .flatMap { maxSize ->
        listOf(250, 500, 1000, 2000, 4000).map { maxDelayMicros ->
            "${maxSize}x${maxDelayMicros}us"
        }
    }
    .joinToString(",")

val mongoCurrentStorageBatchOptions = "128x1000us"
val elasticsearchCurrentStorageBatchOptions = "128x1000us"
val allowDirtyStorageBatchTuning = providers.gradleProperty("benchmarkAllowDirtyStorageBatchTuning")
    .map { value ->
        value.toBooleanStrictOrNull()
            ?: throw GradleException(
                "Gradle property benchmarkAllowDirtyStorageBatchTuning must be true or false: $value"
            )
    }
    .getOrElse(false)

val storageBatchTuningScanProfile = BenchmarkRunProfile(
    id = "tuning-scan",
    warmupIterations = 1,
    warmupTime = "1s",
    measurementIterations = 2,
    measurementTime = "2s",
    forks = 1,
    threads = listOf(1),
    benchmarkModes = listOf("thrpt"),
    jvmArgs = quickBenchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val mongoBatchOptionsTuningProfile = storageBatchTuningScanProfile.copy(
    parameters = benchmarkParametersProperty(
        "benchmarkTuneMongoBatchOptionsParameters",
        mapOf("batchOptions" to storageBatchTuningOptions),
    ),
)

val elasticsearchBatchOptionsTuningProfile = storageBatchTuningScanProfile.copy(
    parameters = benchmarkParametersProperty(
        "benchmarkTuneElasticsearchBatchOptionsParameters",
        mapOf("batchOptions" to storageBatchTuningOptions),
    ),
)

val storageBatchTuningConfirmationProfile = BenchmarkRunProfile(
    id = "tuning-confirmation",
    warmupIterations = 2,
    warmupTime = "3s",
    measurementIterations = 3,
    measurementTime = "5s",
    forks = 2,
    threads = listOf(1),
    benchmarkModes = listOf("thrpt", "avgt"),
    jvmArgs = benchmarkJvmArgs,
    includeGcProfiler = true,
    includeAsyncProfiler = false,
)

val storageBatchTuningConfirmationThreads = listOf(1, 4)

val mongoBatchOptionsTuningConfirmationProfile = storageBatchTuningConfirmationProfile.copy(
    threads = benchmarkThreadsProperty(
        "benchmarkConfirmMongoBatchOptionsThreads",
        storageBatchTuningConfirmationThreads,
    ),
    parameters = benchmarkParametersProperty(
        "benchmarkConfirmMongoBatchOptionsParameters",
        mapOf("batchOptions" to mongoCurrentStorageBatchOptions),
    ),
)

val elasticsearchBatchOptionsTuningConfirmationProfile = storageBatchTuningConfirmationProfile.copy(
    threads = benchmarkThreadsProperty(
        "benchmarkConfirmElasticsearchBatchOptionsThreads",
        storageBatchTuningConfirmationThreads,
    ),
    parameters = benchmarkParametersProperty(
        "benchmarkConfirmElasticsearchBatchOptionsParameters",
        mapOf("batchOptions" to elasticsearchCurrentStorageBatchOptions),
    ),
)

val pairedMongoBatchAppendProfile = BenchmarkRunProfile(
    id = "paired-confirmation",
    warmupIterations = 2,
    warmupTime = "3s",
    measurementIterations = 1,
    measurementTime = "5s",
    forks = 1,
    threads = listOf(1, 4),
    benchmarkModes = listOf("thrpt"),
    jvmArgs = listOf(
        "-Xmx4g",
        "-Xms4g",
        "-XX:+UseG1GC",
        "-XX:+AlwaysPreTouch",
    ),
    includeGcProfiler = false,
    includeAsyncProfiler = false,
)

val pairedMongoBatchOptionsProfile = pairedMongoBatchAppendProfile.copy(
    id = "paired-options-confirmation",
    includeGcProfiler = true,
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

val mongoBatchAppendSuite = BenchmarkSuite(
    id = "mongo-batch-append",
    displayName = "Mongo EventStore Batch Append",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.mongo.MongoEventStoreAppendBenchmark",
    ),
    resultFileName = "mongo-batch-append.json",
    humanFileName = "mongo-batch-append-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
    requiredServices = listOf(
        BenchmarkRequiredService(
            service = "MongoDB",
            host = benchmarkDockerConfig("WOW_BENCHMARK_MONGO_HOST", "localhost"),
            port = benchmarkDockerPort("WOW_BENCHMARK_MONGO_HOST_PORT", 27017),
        ),
    ),
)

val mongoSnapshotBatchSaveSuite = BenchmarkSuite(
    id = "mongo-snapshot-batch-save",
    displayName = "Mongo SnapshotStore Batch Save",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.mongo.MongoSnapshotStoreSaveBenchmark",
    ),
    resultFileName = "mongo-snapshot-batch-save.json",
    humanFileName = "mongo-snapshot-batch-save-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
    requiresCleanSource = true,
    requiredServices = mongoBatchAppendSuite.requiredServices,
    runMetadata = linkedMapOf(
        "experiment" to "quick-mongo-snapshot-batch-save",
        "evidenceClass" to "quick-engineering",
        "formalProtocol" to "false",
        "batchOptions" to mongoBatchQuickCurrentOptions,
        "operationsPerInvocation" to "128",
        "correctnessCheck" to "completion-count-and-iteration-document-count",
    ),
)

val batchRegenerateAggregateSnapshotSuite = BenchmarkSuite(
    id = "batch-regenerate-aggregate-snapshot",
    displayName = "Batch Regenerate Aggregate Snapshot",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.snapshot.BatchRegenerateAggregateSnapshotBenchmark",
    ),
    resultFileName = "batch-regenerate-aggregate-snapshot.json",
    humanFileName = "batch-regenerate-aggregate-snapshot-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
    requiredServices = mongoBatchAppendSuite.requiredServices + listOf(
        BenchmarkRequiredService(
            service = "Elasticsearch",
            host = benchmarkDockerConfig("WOW_BENCHMARK_ELASTICSEARCH_HOST", "localhost"),
            port = benchmarkDockerPort("WOW_BENCHMARK_ELASTICSEARCH_HOST_PORT", 9200),
        ),
    ),
    runMetadata = linkedMapOf(
        "experiment" to "quick-batch-regenerate-aggregate-snapshot",
        "evidenceClass" to "quick-engineering",
        "formalProtocol" to "false",
        "batchOptions" to mongoBatchQuickCurrentOptions,
        "laneCount" to quickBatchRegenerateAggregateSnapshotLaneCounts.joinToString(","),
        "operationsPerInvocation" to "128",
        "aggregatesPerInvocation" to "128",
        "eventsPerAggregate" to "10",
        "executionConcurrency" to "128",
        "threadPartitioning" to "thread-indexed-aggregate-batches",
        "correctnessCheck" to "processed-count-and-snapshot-document-count-and-version",
    ),
)

val quickMongoBatchCandidateE2ESuite = mongoBatchAppendSuite.copy(
    id = "mongo-batch-append-quick-engineering",
    displayName = "Quick Mongo Batch Candidate E2E",
    resultFileName = "mongo-batch-append-candidate-e2e.json",
    humanFileName = "mongo-batch-append-candidate-e2e-human.txt",
    requiresCleanSource = true,
    runMetadata = linkedMapOf(
        "experiment" to "quick-mongo-batch-candidate-e2e",
        "evidenceClass" to "quick-engineering",
        "formalProtocol" to "false",
        "currentBatchOptions" to mongoBatchQuickCurrentOptions,
        "candidateBatchOptions" to mongoBatchQuickCandidateOptions,
        "batchOptions" to mongoBatchQuickCandidateOptions,
        "operationsPerInvocation" to "128",
        "correctnessCheck" to "completion-count-and-iteration-document-count",
    ),
)

val quickMongoBatchCoordinatorConcurrencySuite = BenchmarkSuite(
    id = "mongo-batch-coordinator-concurrency-quick-engineering",
    displayName = "Quick Mongo Batch Coordinator Concurrency",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.mongo.MongoBatchCoordinatorConcurrencyBenchmark",
    ),
    resultFileName = "mongo-batch-coordinator-concurrency.json",
    humanFileName = "mongo-batch-coordinator-concurrency-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
    requiredServices = mongoBatchAppendSuite.requiredServices,
    runMetadata = linkedMapOf(
        "experiment" to "quick-mongo-batch-coordinator-concurrency",
        "evidenceClass" to "quick-engineering",
        "formalProtocol" to "false",
        "implementation" to "single-production-keyed-coordinator",
        "productionDefaultChanged" to "false",
        "batchOptions" to mongoBatchQuickCandidateOptions,
        "coordinatorLanes" to "1,2,4",
        "operationsPerInvocation" to "128",
        "correctnessCheck" to "completion-count-and-iteration-document-count",
    ),
)

val elasticsearchBatchAppendSuite = BenchmarkSuite(
    id = "elasticsearch-batch-append",
    displayName = "Elasticsearch EventStore Batch Append",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.elasticsearch.ElasticsearchEventStoreAppendBenchmark",
    ),
    resultFileName = "elasticsearch-batch-append.json",
    humanFileName = "elasticsearch-batch-append-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
    requiredServices = listOf(
        BenchmarkRequiredService(
            service = "Elasticsearch",
            host = benchmarkDockerConfig("WOW_BENCHMARK_ELASTICSEARCH_HOST", "localhost"),
            port = benchmarkDockerPort("WOW_BENCHMARK_ELASTICSEARCH_HOST_PORT", 9200),
        ),
    ),
)

val mongoBatchOptionsTuningSuite = BenchmarkSuite(
    id = "mongo-batch-options-tuning",
    displayName = "Mongo EventStore Batch Options Tuning",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.mongo.MongoEventStoreBatchTuningBenchmark",
    ),
    resultFileName = "mongo-batch-options-tuning.json",
    humanFileName = "mongo-batch-options-tuning-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
    requiredServices = mongoBatchAppendSuite.requiredServices,
)

val elasticsearchBatchOptionsTuningSuite = BenchmarkSuite(
    id = "elasticsearch-batch-options-tuning",
    displayName = "Elasticsearch EventStore Batch Options Tuning",
    includeClasses = listOf(
        "me.ahoo.wow.benchmark.infrastructure.elasticsearch.ElasticsearchEventStoreBatchTuningBenchmark",
    ),
    resultFileName = "elasticsearch-batch-options-tuning.json",
    humanFileName = "elasticsearch-batch-options-tuning-human.txt",
    requiredForGroupedReport = false,
    formalRegressionSource = false,
    requiredServices = elasticsearchBatchAppendSuite.requiredServices,
)

val storageBatchTuningOptionFormat = Regex("""([1-9]\d*)x([1-9]\d*)us""")

fun requireStorageBatchTuningOption(option: String, source: String) {
    val match = storageBatchTuningOptionFormat.matchEntire(option)
        ?: throw GradleException(
            "$source must use '<maxSize>x<maxDelayMicros>us': $option"
        )
    if (match.groupValues[1].toIntOrNull() == null || match.groupValues[2].toLongOrNull() == null) {
        throw GradleException("$source is out of range: $option")
    }
}

fun storageBatchTuningOptions(profile: BenchmarkRunProfile): List<String> {
    val encodedOptions = profile.parameters["batchOptions"]
        ?: throw GradleException("Storage batch tuning profile must define batchOptions.")
    val options = encodedOptions.split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (options.isEmpty() || options.distinct().size != options.size) {
        throw GradleException("Storage batch tuning options must be non-empty and distinct: $encodedOptions")
    }
    options.forEach { option -> requireStorageBatchTuningOption(option, "Storage batch tuning option") }
    return options
}

fun currentStorageBatchOptions(suite: BenchmarkSuite): String {
    return when (suite.id) {
        mongoBatchOptionsTuningSuite.id -> mongoCurrentStorageBatchOptions
        elasticsearchBatchOptionsTuningSuite.id -> elasticsearchCurrentStorageBatchOptions
        else -> throw GradleException("Unsupported storage batch tuning suite: ${suite.id}")
    }
}

fun validateStorageBatchTuningExecution(
    suite: BenchmarkSuite,
    profile: BenchmarkRunProfile,
    currentJmhJarSha256: String? = null,
): ValidatedStorageBatchTuningScreening? {
    val options = storageBatchTuningOptions(profile)
    if (profile.id != "tuning-confirmation") {
        return null
    }
    if (options.size < 2) {
        throw GradleException(
            "Storage batch tuning confirmation requires the current option and at least one distinct " +
                "challenger, found ${options.size}: " +
                options.joinToString(",")
        )
    }
    if (profile.threads != storageBatchTuningConfirmationThreads) {
        throw GradleException(
            "Formal storage batch confirmation requires threads " +
                "$storageBatchTuningConfirmationThreads, found ${profile.threads}."
        )
    }
    val currentOptions = currentStorageBatchOptions(suite)
    if (currentOptions !in options) {
        throw GradleException(
            "Storage batch tuning confirmation must include current options $currentOptions: " +
            options.joinToString(",")
        )
    }
    val screening = validateStorageBatchTuningScreeningEvidence(
        suite = suite,
        currentJmhJarSha256 = currentJmhJarSha256,
    )
    requireStorageBatchTuningConfirmationOptions(
        options = options,
        suiteId = suite.id,
        currentOptions = currentOptions,
        evidence = screening.evidence,
        source = storageBatchTuningFrontierEvidenceFile(suite).absolutePath,
    )
    return screening
}

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

val quickInfrastructureE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickInfrastructureE2E",
    suite = infrastructureE2ESuite,
    profile = quickProfile,
    description = "Runs the bounded infrastructure feedback catalog.",
)

val quickMongoBatchAppendTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickMongoBatchAppend",
    suite = mongoBatchAppendSuite,
    profile = quickMongoBatchAppendProfile,
    description = "Compares Mongo single, native insertMany, and coordinated batch throughput and latency.",
)

val quickMongoSnapshotBatchSaveTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickMongoSnapshotBatchSave",
    suite = mongoSnapshotBatchSaveSuite,
    profile = quickMongoSnapshotBatchSaveProfile,
    description = "Compares Mongo snapshot updateOne, native bulkWrite, and coordinated batch throughput and latency.",
)

val quickBatchRegenerateAggregateSnapshotTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickBatchRegenerateAggregateSnapshot",
    suite = batchRegenerateAggregateSnapshotSuite,
    profile = quickBatchRegenerateAggregateSnapshotProfile,
    description =
        "Measures batch aggregate snapshot regeneration from MongoEventStore into ElasticsearchSnapshotStore.",
)

val quickMongoBatchCandidateE2ETaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickMongoBatchAppendCandidateE2E",
    suite = quickMongoBatchCandidateE2ESuite,
    profile = quickMongoBatchCandidateE2EProfile,
    description =
        "Compares Mongo single, native insertMany, and coordinated 192x250us batching with quick engineering settings.",
)

val quickMongoBatchCoordinatorConcurrencyTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickMongoBatchCoordinatorConcurrency",
    suite = quickMongoBatchCoordinatorConcurrencySuite,
    profile = quickMongoBatchCoordinatorConcurrencyProfile,
    description =
        "Measures the production keyed coordinator with 1, 2, and 4 serial lanes at JMH threads=4.",
)

val confirmationMongoBatchAppendTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkConfirmMongoBatchAppend",
    suite = mongoBatchAppendSuite,
    profile = confirmationMongoBatchAppendProfile,
    description = "Confirms Mongo batch throughput and latency with stable measurement settings.",
)

val quickElasticsearchBatchAppendTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkQuickElasticsearchBatchAppend",
    suite = elasticsearchBatchAppendSuite,
    profile = quickElasticsearchBatchAppendProfile,
    description = "Compares single create, native Bulk create, and coordinated Elasticsearch EventStore batching.",
)

val confirmationElasticsearchBatchAppendTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkConfirmElasticsearchBatchAppend",
    suite = elasticsearchBatchAppendSuite,
    profile = confirmationElasticsearchBatchAppendProfile,
    description = "Confirms Elasticsearch Bulk create throughput and latency with stable measurement settings.",
)

val mongoBatchOptionsTuningTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkTuneMongoBatchOptions",
    suite = mongoBatchOptionsTuningSuite,
    profile = mongoBatchOptionsTuningProfile,
    description = "Historical stopped Mongo full-candidate scan; retained for audit only.",
)

val elasticsearchBatchOptionsTuningTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkTuneElasticsearchBatchOptions",
    suite = elasticsearchBatchOptionsTuningSuite,
    profile = elasticsearchBatchOptionsTuningProfile,
    description =
        "Scans Elasticsearch EventStore maxSize and maxDelay across isolated, burst, representative, and saturated workloads.",
)

val mongoBatchOptionsTuningConfirmationTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkConfirmMongoBatchOptions",
    suite = mongoBatchOptionsTuningSuite,
    profile = mongoBatchOptionsTuningConfirmationProfile,
    description = "Historical stopped Mongo multiple-fork comparison; retained for audit only.",
)

val elasticsearchBatchOptionsTuningConfirmationTaskSpec = BenchmarkTaskSpec(
    taskName = "benchmarkConfirmElasticsearchBatchOptions",
    suite = elasticsearchBatchOptionsTuningSuite,
    profile = elasticsearchBatchOptionsTuningConfirmationProfile,
    description = "Confirms selected Elasticsearch EventStore batch options with multiple forks.",
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
    quickBatchE2ETaskSpec,
    baselineE2ETaskSpec,
    latencyE2ETaskSpec,
    confirmationE2ETaskSpec,
    quickInfrastructureE2ETaskSpec,
    quickMongoBatchAppendTaskSpec,
    quickMongoSnapshotBatchSaveTaskSpec,
    quickBatchRegenerateAggregateSnapshotTaskSpec,
    quickMongoBatchCandidateE2ETaskSpec,
    quickMongoBatchCoordinatorConcurrencyTaskSpec,
    confirmationMongoBatchAppendTaskSpec,
    quickElasticsearchBatchAppendTaskSpec,
    confirmationElasticsearchBatchAppendTaskSpec,
    mongoBatchOptionsTuningTaskSpec,
    elasticsearchBatchOptionsTuningTaskSpec,
    mongoBatchOptionsTuningConfirmationTaskSpec,
    elasticsearchBatchOptionsTuningConfirmationTaskSpec,
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

fun requireBenchmarkService(service: String, host: String, port: Int): String {
    return runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2000)
            socket.inetAddress.hostAddress.substringBefore('%')
        }
    }.getOrElse { error ->
        throw GradleException(
            "$service is required for Infrastructure I/O benchmarks at $host:$port. " +
                "Start $service and rerun the selected infrastructure benchmark task.",
            error,
        )
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

fun runCommand(
    command: List<String>,
    timeoutSeconds: Long = 3,
    trimOutput: Boolean = true,
): CommandOutput {
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
        val rawOutput = process.inputStream.bufferedReader().readText()
        CommandOutput(
            exitCode = process.exitValue(),
            output = if (trimOutput) rawOutput.trim() else rawOutput,
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

fun dockerPerformanceConfiguration(
    service: String,
    command: List<String>,
    environment: List<String>,
): Map<String, String> {
    val configuration = linkedMapOf<String, String>()
    if (service == "MongoDB") {
        val valueOptions = mapOf(
            "--wiredTigerCacheSizeGB" to "wiredTiger.cacheSizeGB",
            "--wiredTigerCollectionBlockCompressor" to "wiredTiger.collectionBlockCompressor",
            "--wiredTigerJournalCompressor" to "wiredTiger.journalCompressor",
        )
        command.forEachIndexed { index, option ->
            valueOptions[option]?.let { name ->
                command.getOrNull(index + 1)?.takeIf(String::isNotBlank)?.let { value ->
                    configuration[name] = value
                }
            }
            if (option == "--setParameter") {
                val parameter = command.getOrNull(index + 1).orEmpty()
                val parts = parameter.split("=", limit = 2)
                if (parts.size == 2 &&
                    parts[0] in setOf("diagnosticDataCollectionEnabled", "ttlMonitorEnabled")
                ) {
                    configuration["setParameter.${parts[0]}"] = parts[1]
                }
            }
        }
    }
    if (service == "Elasticsearch") {
        val environmentByName = environment.mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            parts.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        listOf(
            "ingest.geoip.downloader.enabled",
            "cluster.routing.allocation.disk.threshold_enabled",
        ).forEach { name ->
            environmentByName[name]?.let { value -> configuration[name] = value }
        }
        val javaOptions = environmentByName["ES_JAVA_OPTS"].orEmpty()
        Regex("""(?:^|\s)-Xms(\S+)""").find(javaOptions)?.groupValues?.get(1)?.let { value ->
            configuration["heap.initial"] = value
        }
        Regex("""(?:^|\s)-Xmx(\S+)""").find(javaOptions)?.groupValues?.get(1)?.let { value ->
            configuration["heap.maximum"] = value
        }
    }
    return configuration.toSortedMap()
}

fun dockerContainerRuntime(
    service: String,
    label: String,
    containerName: String,
    required: Boolean = false,
): DockerContainerRuntime {
    val inspectOutput = runCommand(listOf("docker", "inspect", containerName))
    if (inspectOutput.exitCode != 0 || inspectOutput.output.isBlank()) {
        if (required) {
            throw GradleException(
                "Unable to capture run-time Docker provenance for required $service container " +
                    "'$containerName': ${inspectOutput.output}"
            )
        }
        return DockerContainerRuntime(
            service = service,
            label = label,
            containerName = containerName,
            containerId = null,
            image = null,
            imageId = null,
            repoDigests = emptyList(),
            startedAt = null,
            running = null,
            connectedAddress = null,
            publishedPorts = emptyList(),
            performanceConfiguration = emptyMap(),
            composeProject = null,
            composeService = null,
            composeConfigHash = null,
            composeConfigFiles = null,
            configurationSha256 = null,
        )
    }
    val inspectRows = runCatching {
        JsonSlurper().parseText(inspectOutput.output) as? List<*>
    }.getOrNull()
    val inspect = inspectRows?.singleOrNull() as? Map<*, *>
    if (inspect == null) {
        if (required) {
            throw GradleException(
                "Docker inspect returned unusable provenance for required $service container '$containerName'."
            )
        }
        return DockerContainerRuntime(
            service = service,
            label = label,
            containerName = containerName,
            containerId = null,
            image = null,
            imageId = null,
            repoDigests = emptyList(),
            startedAt = null,
            running = null,
            connectedAddress = null,
            publishedPorts = emptyList(),
            performanceConfiguration = emptyMap(),
            composeProject = null,
            composeService = null,
            composeConfigHash = null,
            composeConfigFiles = null,
            configurationSha256 = null,
        )
    }
    val config = inspect["Config"] as? Map<*, *> ?: emptyMap<Any, Any>()
    val state = inspect["State"] as? Map<*, *> ?: emptyMap<Any, Any>()
    val networkSettings = inspect["NetworkSettings"] as? Map<*, *> ?: emptyMap<Any, Any>()
    val labels = config["Labels"] as? Map<*, *> ?: emptyMap<Any, Any>()
    fun stringList(value: Any?): List<String> {
        return (value as? List<*>)
            ?.mapNotNull { item -> (item as? String)?.takeIf(String::isNotBlank) }
            ?: emptyList()
    }
    val containerId = (inspect["Id"] as? String)?.takeIf(String::isNotBlank)
    val image = (config["Image"] as? String)?.takeIf(String::isNotBlank)
    val imageId = (inspect["Image"] as? String)?.takeIf(String::isNotBlank)
    val repoDigests = imageId?.let { capturedImageId ->
        val imageOutput = runCommand(
            listOf(
                "docker",
                "image",
                "inspect",
                capturedImageId,
                "--format",
                "{{json .RepoDigests}}",
            )
        )
        if (imageOutput.exitCode != 0 || imageOutput.output.isBlank()) {
            emptyList()
        } else {
            runCatching {
                (JsonSlurper().parseText(imageOutput.output) as? List<*>)
                    ?.mapNotNull { digest -> digest as? String }
                    ?.sorted()
                    ?: emptyList()
            }.getOrElse { emptyList() }
        }
    } ?: emptyList()
    val command = stringList(config["Cmd"])
    val environment = stringList(config["Env"])
    val performanceConfiguration = dockerPerformanceConfiguration(service, command, environment)
    val publishedPorts = (networkSettings["Ports"] as? Map<*, *>)
        ?.entries
        ?.flatMap { (rawContainerPort, rawBindings) ->
            val containerPortAndProtocol = (rawContainerPort as? String)
                ?.split("/", limit = 2)
                ?.takeIf { it.size == 2 }
                ?: return@flatMap emptyList()
            val containerPort = containerPortAndProtocol[0].toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: return@flatMap emptyList()
            val protocol = containerPortAndProtocol[1].lowercase()
            (rawBindings as? List<*>)
                ?.mapNotNull { rawBinding ->
                    val binding = rawBinding as? Map<*, *> ?: return@mapNotNull null
                    val hostIp = (binding["HostIp"] as? String).orEmpty()
                    val hostPort = (binding["HostPort"] as? String)
                        ?.toIntOrNull()
                        ?.takeIf { it in 1..65535 }
                        ?: return@mapNotNull null
                    DockerPublishedPortBinding(
                        containerPort = containerPort,
                        protocol = protocol,
                        hostIp = hostIp,
                        hostPort = hostPort,
                    )
                }
                ?: emptyList()
        }
        ?.sortedWith(
            compareBy(
                DockerPublishedPortBinding::containerPort,
                DockerPublishedPortBinding::protocol,
                DockerPublishedPortBinding::hostIp,
                DockerPublishedPortBinding::hostPort,
            )
        )
        ?: emptyList()
    fun label(name: String): String? {
        return (labels[name] as? String)?.takeIf(String::isNotBlank)
    }
    val composeProject = label("com.docker.compose.project")
    val composeService = label("com.docker.compose.service")
    val composeConfigHash = label("com.docker.compose.config-hash")
    val composeConfigFiles = label("com.docker.compose.project.config_files")
    val configuration = linkedMapOf<String, Any?>(
        "imageId" to imageId,
        "publishedPorts" to publishedPorts.map(DockerPublishedPortBinding::toRunSpec),
        "performanceConfiguration" to performanceConfiguration,
        "composeProject" to composeProject,
        "composeService" to composeService,
        "composeConfigHash" to composeConfigHash,
        "composeConfigFiles" to composeConfigFiles,
    )
    val runtime = DockerContainerRuntime(
        service = service,
        label = label,
        containerName = containerName,
        containerId = containerId,
        image = image,
        imageId = imageId,
        repoDigests = repoDigests,
        startedAt = (state["StartedAt"] as? String)?.takeIf(String::isNotBlank),
        running = state["Running"] as? Boolean,
        connectedAddress = null,
        publishedPorts = publishedPorts,
        performanceConfiguration = performanceConfiguration,
        composeProject = composeProject,
        composeService = composeService,
        composeConfigHash = composeConfigHash,
        composeConfigFiles = composeConfigFiles,
        configurationSha256 = sha256Text(JsonOutput.toJson(configuration)),
    )
    if (required) {
        val missing = linkedMapOf(
            "containerId" to runtime.containerId,
            "image" to runtime.image,
            "imageId" to runtime.imageId,
            "startedAt" to runtime.startedAt,
            "composeProject" to runtime.composeProject,
            "composeService" to runtime.composeService,
            "composeConfigHash" to runtime.composeConfigHash,
            "configurationSha256" to runtime.configurationSha256,
        ).filterValues { value -> value.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Run-time Docker provenance for required $service container '$containerName' is incomplete: " +
                    missing.joinToString(",")
                )
        }
        if (runtime.running != true) {
            throw GradleException(
                "Required $service container '$containerName' is not running."
            )
        }
    }
    return runtime
}

fun benchmarkContainerRuntime(
    requiredService: BenchmarkRequiredService,
    connectedAddress: String,
): DockerContainerRuntime? {
    val descriptor = when (requiredService.service) {
        "Redis" -> Triple(
            "Redis",
            "WOW_BENCHMARK_REDIS_CONTAINER_NAME",
            "wow-benchmark-redis",
        )

        "MongoDB" -> Triple(
            "Mongo",
            "WOW_BENCHMARK_MONGO_CONTAINER_NAME",
            "wow-benchmark-mongo",
        )

        "Elasticsearch" -> Triple(
            "Elasticsearch",
            "WOW_BENCHMARK_ELASTICSEARCH_CONTAINER_NAME",
            "wow-benchmark-elasticsearch",
        )

        else -> return null
    }
    val internalPort = when (requiredService.service) {
        "Redis" -> 6379
        "MongoDB" -> 27017
        "Elasticsearch" -> 9200
        else -> error("Unsupported benchmark container service: ${requiredService.service}")
    }
    val runtime = dockerContainerRuntime(
        service = requiredService.service,
        label = descriptor.first,
        containerName = benchmarkDockerConfig(descriptor.second, descriptor.third),
        required = true,
    ).copy(connectedAddress = connectedAddress)
    requireBenchmarkContainerEndpoint(runtime, requiredService, internalPort)
    return runtime
}

fun normalizedIpAddress(value: String): InetAddress? {
    return runCatching {
        InetAddress.getByName(value.removePrefix("[").removeSuffix("]").substringBefore('%'))
    }.getOrNull()
}

fun dockerHostBindingCoversConnectedAddress(hostIp: String, connectedAddress: String): Boolean {
    val boundAddress = normalizedIpAddress(hostIp) ?: return false
    val connected = normalizedIpAddress(connectedAddress) ?: return false
    return when {
        boundAddress.isAnyLocalAddress -> {
            (boundAddress is Inet4Address && connected is Inet4Address) ||
                (boundAddress is Inet6Address && connected is Inet6Address)
        }

        else -> boundAddress.address.contentEquals(connected.address)
    }
}

fun requireBenchmarkContainerEndpoint(
    runtime: DockerContainerRuntime,
    requiredService: BenchmarkRequiredService,
    internalPort: Int,
) {
    if (runtime.running != true) {
        throw GradleException(
            "Required ${requiredService.service} container '${runtime.containerName}' is not running."
        )
    }
    val connectedAddress = runtime.connectedAddress
        ?: throw GradleException(
            "Required ${requiredService.service} endpoint is missing its connected remote address."
        )
    if (runtime.publishedPorts.none { binding ->
            binding.containerPort == internalPort &&
                binding.protocol == "tcp" &&
                binding.hostPort == requiredService.port &&
                dockerHostBindingCoversConnectedAddress(binding.hostIp, connectedAddress)
        }) {
        throw GradleException(
            "Required ${requiredService.service} endpoint ${requiredService.host}:${requiredService.port} " +
                "connected to $connectedAddress but does not match published port $internalPort/tcp " +
                "on container '${runtime.containerName}': " +
                runtime.publishedPorts
        )
    }
}

fun isLocalBenchmarkHost(host: String): Boolean {
    return host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
}

fun captureBenchmarkInfrastructureRuntime(
    requiredServices: List<BenchmarkRequiredService>,
    connectedAddresses: Map<String, String> = emptyMap(),
): BenchmarkInfrastructureRuntime {
    val localContainerServices = requiredServices
        .filter { requiredService -> isLocalBenchmarkHost(requiredService.host) }
        .mapNotNull { requiredService ->
            val connectedAddress = connectedAddresses[requiredService.service]
                ?: throw GradleException(
                    "Missing connected address for required local ${requiredService.service} endpoint."
                )
            benchmarkContainerRuntime(requiredService, connectedAddress)
        }
    val dockerServer = if (localContainerServices.isEmpty()) {
        null
    } else {
        dockerServerSummary().also { summary ->
            if (summary.startsWith("unavailable")) {
                throw GradleException("Unable to capture run-time Docker server provenance: $summary")
            }
        }
    }
    return BenchmarkInfrastructureRuntime(
        capturedAt = Instant.now().toString(),
        clientLocation = benchmarkClientLocation(),
        dockerServer = dockerServer,
        containers = localContainerServices,
    )
}

fun dockerContainerRuntimes(): List<DockerContainerRuntime> {
    return listOf(
        dockerContainerRuntime(
            service = "Redis",
            label = "Redis",
            containerName = benchmarkDockerConfig(
                name = "WOW_BENCHMARK_REDIS_CONTAINER_NAME",
                defaultValue = "wow-benchmark-redis",
            ),
        ),
        dockerContainerRuntime(
            service = "MongoDB",
            label = "Mongo",
            containerName = benchmarkDockerConfig(
                name = "WOW_BENCHMARK_MONGO_CONTAINER_NAME",
                defaultValue = "wow-benchmark-mongo",
            ),
        ),
        dockerContainerRuntime(
            service = "Elasticsearch",
            label = "Elasticsearch",
            containerName = benchmarkDockerConfig(
                name = "WOW_BENCHMARK_ELASTICSEARCH_CONTAINER_NAME",
                defaultValue = "wow-benchmark-elasticsearch",
            ),
        ),
    )
}

fun markdownCodeOrUnavailable(value: String?): String {
    val normalized = value?.replace(Regex("[\\r\\n]+"), " ")?.takeIf { it.isNotBlank() }
        ?: return "unavailable"
    val longestBacktickRun = Regex("`+").findAll(normalized)
        .maxOfOrNull { match -> match.value.length }
        ?: 0
    val fence = "`".repeat(longestBacktickRun + 1)
    return "$fence $normalized $fence"
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

fun StringBuilder.appendCapturedInfrastructureRuntime(
    manifests: List<ParsedBenchmarkRunManifest>,
) {
    val runtimes = manifests.flatMap { manifest ->
        listOf(
            manifest.infrastructureRuntime,
            manifest.completionInfrastructureRuntime,
        )
    }
    validateBenchmarkInfrastructureRuntimeStability(
        runtimes = runtimes,
        context = "captured run-time infrastructure report",
    )
    val reference = runtimes.first()
    val containers = runtimes.flatMap(BenchmarkInfrastructureRuntime::containers)
        .distinctBy(DockerContainerRuntime::service)
        .sortedBy(DockerContainerRuntime::service)
    appendLine("### Manifest-bound Run-Time Infrastructure")
    appendLine()
    val capturedAtValues = runtimes.map(BenchmarkInfrastructureRuntime::capturedAt).sorted()
    appendLine(
        "- **Captured At**: " +
            if (capturedAtValues.size == 1) {
                capturedAtValues.single()
            } else {
                "${capturedAtValues.first()} to ${capturedAtValues.last()}"
            }
    )
    appendLine("- **Benchmark Client**: ${reference.clientLocation}")
    appendLine("- **Docker Server**: ${reference.dockerServer ?: "not required by these suites"}")
    if (containers.isEmpty()) {
        appendLine("- **Local Docker Containers**: none required; service endpoints remain bound in each run manifest.")
    }
    containers.forEach { runtime ->
        appendLine("- **${runtime.label} Container**: ${markdownCodeOrUnavailable(runtime.containerName)}")
        appendLine("  - Running: `${runtime.running}`")
        appendLine("  - Container ID: ${markdownCodeOrUnavailable(runtime.containerId)}")
        appendLine("  - Started At: ${markdownCodeOrUnavailable(runtime.startedAt)}")
        appendLine("  - Image: ${markdownCodeOrUnavailable(runtime.image)}")
        appendLine("  - Image ID: ${markdownCodeOrUnavailable(runtime.imageId)}")
        appendLine(
            "  - Repo Digests: " +
                markdownCodeOrUnavailable(runtime.repoDigests.takeIf { it.isNotEmpty() }?.joinToString(","))
        )
        appendLine("  - Configuration SHA-256: ${markdownCodeOrUnavailable(runtime.configurationSha256)}")
        appendLine(
            "  - Published Ports: " +
                markdownCodeOrUnavailable(runtime.publishedPorts.takeIf { it.isNotEmpty() }?.joinToString(","))
        )
        appendLine(
            "  - Performance Configuration: " +
                markdownCodeOrUnavailable(
                    runtime.performanceConfiguration.takeIf { it.isNotEmpty() }
                        ?.entries
                        ?.joinToString(",") { (name, value) -> "$name=$value" }
                )
        )
        appendLine(
            "  - Compose: project=${markdownCodeOrUnavailable(runtime.composeProject)}, " +
                "service=${markdownCodeOrUnavailable(runtime.composeService)}, " +
                "config hash=${markdownCodeOrUnavailable(runtime.composeConfigHash)}, " +
                "config files=${markdownCodeOrUnavailable(runtime.composeConfigFiles)}"
        )
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
    manifests.groupBy { it.suite }
        .mapValues { (_, suiteManifests) -> suiteManifests.first().requiredServices }
        .filterValues { it.isNotEmpty() }
        .forEach { (suite, requiredServices) ->
            appendLine("- **$suite Required Services**: `${formatRequiredServiceEndpoints(requiredServices)}`")
        }
    appendLine()
    appendCapturedInfrastructureRuntime(manifests)
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

fun StringBuilder.appendInfrastructureRuntime(
    requiredServices: List<BenchmarkRequiredService>? = null,
) {
    val requiredServiceNames = requiredServices?.mapTo(mutableSetOf()) { service -> service.service }
    appendLine("## Report-Time Infrastructure Diagnostics")
    appendLine(
        "This section is live diagnostic context only. Manifest-bound run-time container identity and " +
            "configuration above are the evidence used for comparability checks."
    )
    appendLine()
    appendLine("- **Benchmark Client**: ${benchmarkClientLocation()}")
    appendLine(
        "- **Docker Compose Env File**: " +
            markdownCodeOrUnavailable(benchmarkReportPath(benchmarkDockerEnvFile))
    )
    appendLine("- **Docker Server**: ${dockerServerSummary()}")
    appendLine("- **Docker Desktop VM**: ${dockerDesktopVmSummary()}")
    dockerContainerRuntimes()
        .filter { containerRuntime ->
            requiredServiceNames == null ||
                containerRuntime.label in requiredServiceNames ||
                (containerRuntime.label == "Mongo" && "MongoDB" in requiredServiceNames)
        }
        .forEach { containerRuntime ->
            appendLine(
                "- **${containerRuntime.label} Container**: " +
                    markdownCodeOrUnavailable(containerRuntime.containerName)
            )
            appendLine("  - Image: ${markdownCodeOrUnavailable(containerRuntime.image)}")
            appendLine("  - Image ID: ${markdownCodeOrUnavailable(containerRuntime.imageId)}")
            appendLine(
                "  - Repo Digests: " +
                    markdownCodeOrUnavailable(
                        containerRuntime.repoDigests.takeIf { it.isNotEmpty() }?.joinToString(",")
                    )
            )
        }
    appendLine(
        "- **Network Note**: Host JVM infrastructure benchmarks use Docker-published localhost ports; " +
            "Docker Desktop host-to-VM networking can materially affect Redis, Mongo, and Elasticsearch results."
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
            val jmhJarFile = jmhJar.get().archiveFile.get().asFile
            val isStorageBatchTuning =
                suite.id == mongoBatchOptionsTuningSuite.id ||
                    suite.id == elasticsearchBatchOptionsTuningSuite.id
            val validatedScreening = if (isStorageBatchTuning) {
                validateStorageBatchTuningExecution(
                    suite = suite,
                    profile = profile,
                    currentJmhJarSha256 = fileSha256(jmhJarFile),
                )
            } else {
                null
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
            if (suite.requiresCleanSource && statusOutput.output.isNotBlank()) {
                throw GradleException(
                    "${suite.displayName} requires a clean source tree. Commit or stash changes before running $path."
                )
            }
            if (isStorageBatchTuning && statusOutput.output.isNotBlank() && !allowDirtyStorageBatchTuning) {
                throw GradleException(
                    "Storage batch tuning requires a clean source tree. Commit or stash changes before " +
                        "running $path. For implementation smoke checks only, set " +
                        "-PbenchmarkAllowDirtyStorageBatchTuning=true; dirty results remain ineligible for " +
                        "generated tuning reports."
                )
            }
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

            environment(benchmarkDockerRuntimeEnvironment())
            val connectedAddresses = suite.requiredServices.associate { requiredService ->
                requiredService.service to requireBenchmarkService(
                    requiredService.service,
                    requiredService.host,
                    requiredService.port,
                )
            }
            val infrastructureRuntime = captureBenchmarkInfrastructureRuntime(
                requiredServices = suite.requiredServices,
                connectedAddresses = connectedAddresses,
            )
            validatedScreening?.let { screening ->
                requireCurrentBenchmarkEnvironmentCompatibility(
                    screening = screening.report.manifests.single(),
                    requiredServices = suite.requiredServices,
                    infrastructureRuntime = infrastructureRuntime,
                    context = path,
                )
            }
            val startedAt = Instant.now().toString()
            val inProgress = linkedMapOf<String, Any?>(
                "schemaVersion" to 2,
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
                    "metadata" to suite.runMetadata,
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
                "infrastructure" to infrastructureRuntime.toRunSpec(),
            )
            writePrettyJson(inProgressManifest, inProgress)
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
            val startedInfrastructureRuntime = manifestInfrastructureRuntime(
                container = manifestData,
                key = "infrastructure",
                source = inProgressManifest.absolutePath,
            )
            val completionConnectedAddresses = suite.requiredServices.associate { requiredService ->
                requiredService.service to requireBenchmarkService(
                    requiredService.service,
                    requiredService.host,
                    requiredService.port,
                )
            }
            val completionInfrastructureRuntime = captureBenchmarkInfrastructureRuntime(
                requiredServices = suite.requiredServices,
                connectedAddresses = completionConnectedAddresses,
            )
            validateBenchmarkInfrastructureRuntimeWindow(
                started = startedInfrastructureRuntime,
                completed = completionInfrastructureRuntime,
                requiredServices = suite.requiredServices,
                context = path,
            )
            manifestData["infrastructureAtCompletion"] = completionInfrastructureRuntime.toRunSpec()
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

tasks.named<Jar>("jmhJar") {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val resultsDir = layout.projectDirectory.dir("results")
val frameworkE2EBaselineJson = resultsDir.file("baselines/framework-e2e.json")
val reportsDir = resultsDir.dir("reports")
val benchmarkReportFile = reportsDir.file("quick-framework-e2e.md")
val batchBenchmarkReportFile = reportsDir.file("quick-batch-command-write-e2e.md")
val mongoBatchAppendReportFile = reportsDir.file("quick-mongo-batch-append.md")
val mongoSnapshotBatchSaveReportFile = reportsDir.file("quick-mongo-snapshot-batch-save.md")
val batchRegenerateAggregateSnapshotReportFile =
    reportsDir.file("quick-batch-regenerate-aggregate-snapshot.md")
val mongoBatchAppendConfirmationReportFile = reportsDir.file("confirmation-mongo-batch-append.md")
val mongoBatchAppendPairedE2EReportFile = reportsDir.file("mongo-batch-append-paired-e2e.md")
val elasticsearchBatchAppendReportFile = reportsDir.file("quick-elasticsearch-batch-append.md")
val elasticsearchBatchAppendConfirmationReportFile =
    reportsDir.file("confirmation-elasticsearch-batch-append.md")
val mongoBatchOptionsTuningReportFile = reportsDir.file("tuning-mongo-batch-options.md")
val elasticsearchBatchOptionsTuningReportFile =
    reportsDir.file("tuning-elasticsearch-batch-options.md")
val mongoBatchOptionsFrontierEvidenceFile =
    reportsDir.file("tuning-mongo-batch-options.frontier.json")
val elasticsearchBatchOptionsFrontierEvidenceFile =
    reportsDir.file("tuning-elasticsearch-batch-options.frontier.json")
val mongoBatchOptionsTuningConfirmationReportFile =
    reportsDir.file("confirmation-mongo-batch-options.md")
val mongoBatchOptionsPairedConfirmationReportFile =
    reportsDir.file("confirmation-mongo-batch-options-paired.md")
val quickMongoBatchOptionsPairedReportFile =
    reportsDir.file("quick-mongo-batch-options-paired.md")
val quickMongoBatchCandidateE2EReportFile =
    reportsDir.file("quick-mongo-batch-append-candidate-e2e.md")
val quickMongoBatchCoordinatorConcurrencyReportFile =
    reportsDir.file("quick-mongo-batch-coordinator-concurrency.md")
val elasticsearchBatchOptionsTuningConfirmationReportFile =
    reportsDir.file("confirmation-elasticsearch-batch-options.md")
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
    val runMetadata: Map<String, String>,
    val infrastructureRuntime: BenchmarkInfrastructureRuntime,
    val completionInfrastructureRuntime: BenchmarkInfrastructureRuntime,
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

fun manifestOptionalString(container: Map<*, *>, key: String, source: String): String? {
    val value = container[key] ?: return null
    return (value as? String)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Benchmark manifest '$key' must be null or a non-blank string: $source")
}

fun manifestLong(container: Map<*, *>, key: String, source: String): Long {
    val number = container[key] as? Number
        ?: throw GradleException("Benchmark manifest is missing integer '$key': $source")
    return runCatching {
        java.math.BigDecimal(number.toString()).longValueExact()
    }.getOrElse {
        throw GradleException(
            "Benchmark manifest '$key' must be a finite whole number in Long range: $source"
        )
    }
}

fun manifestInt(container: Map<*, *>, key: String, source: String): Int {
    val value = manifestLong(container, key, source)
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
        throw GradleException("Benchmark manifest '$key' must be in Int range: $source")
    }
    return value.toInt()
}

fun manifestBoolean(container: Map<*, *>, key: String, source: String): Boolean {
    return container[key] as? Boolean
        ?: throw GradleException("Benchmark manifest is missing boolean '$key': $source")
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

fun manifestOptionalStringMap(container: Map<*, *>, key: String, source: String): Map<String, String> {
    if (container[key] == null) {
        return emptyMap()
    }
    return manifestStringMap(container, key, source)
}

fun manifestPublishedPorts(
    container: Map<*, *>,
    key: String,
    source: String,
): List<DockerPublishedPortBinding> {
    val values = container[key] as? List<*>
        ?: throw GradleException("Benchmark manifest is missing array '$key': $source")
    return values.mapIndexed { index, value ->
        val bindingSource = "$key[$index]"
        val binding = value as? Map<*, *>
            ?: throw GradleException(
                "Benchmark manifest '$bindingSource' must be an object: $source"
            )
        val containerPort = manifestInt(binding, "containerPort", "$source ($bindingSource)")
        val hostPort = manifestInt(binding, "hostPort", "$source ($bindingSource)")
        if (containerPort !in 1..65535 || hostPort !in 1..65535) {
            throw GradleException(
                "Benchmark manifest '$bindingSource' contains an invalid TCP/UDP port: $source"
            )
        }
        DockerPublishedPortBinding(
            containerPort = containerPort,
            protocol = manifestString(binding, "protocol", "$source ($bindingSource)").lowercase(),
            hostIp = manifestString(binding, "hostIp", "$source ($bindingSource)"),
            hostPort = hostPort,
        )
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
        val port = manifestLong(service, "port", "$source ($serviceSource)")
            .takeIf { it in 1..65535 }
            ?.toInt()
            ?: throw GradleException(
                "Benchmark manifest '$serviceSource.port' must be a valid TCP port: $source"
            )
        BenchmarkRequiredService(service = serviceName, host = host, port = port)
    }
    val duplicateServices = requiredServices.groupingBy { it.service }.eachCount().filterValues { it > 1 }.keys
    if (duplicateServices.isNotEmpty()) {
        throw GradleException("Benchmark manifest '$key' contains duplicate services $duplicateServices: $source")
    }
    return requiredServices
}

fun manifestInfrastructureRuntime(
    container: Map<*, *>,
    key: String,
    source: String,
): BenchmarkInfrastructureRuntime {
    val infrastructure = manifestMap(container, key, source)
    val rawContainers = infrastructure["containers"] as? List<*>
        ?: throw GradleException("Benchmark manifest is missing array '$key.containers': $source")
    val containers = rawContainers.mapIndexed { index, rawContainer ->
        val containerSource = "$key.containers[$index]"
        val runtime = rawContainer as? Map<*, *>
            ?: throw GradleException("Benchmark manifest '$containerSource' must be an object: $source")
        DockerContainerRuntime(
            service = manifestString(runtime, "service", "$source ($containerSource)"),
            label = manifestString(runtime, "label", "$source ($containerSource)"),
            containerName = manifestString(runtime, "containerName", "$source ($containerSource)"),
            containerId = manifestString(runtime, "containerId", "$source ($containerSource)"),
            image = manifestString(runtime, "image", "$source ($containerSource)"),
            imageId = manifestString(runtime, "imageId", "$source ($containerSource)"),
            repoDigests = manifestStringList(runtime, "repoDigests", "$source ($containerSource)"),
            startedAt = manifestString(runtime, "startedAt", "$source ($containerSource)"),
            running = manifestBoolean(runtime, "running", "$source ($containerSource)"),
            connectedAddress = manifestString(
                runtime,
                "connectedAddress",
                "$source ($containerSource)",
            ),
            publishedPorts = manifestPublishedPorts(
                runtime,
                "publishedPorts",
                "$source ($containerSource)",
            ),
            performanceConfiguration = manifestStringMap(
                runtime,
                "performanceConfiguration",
                "$source ($containerSource)",
            ),
            composeProject = manifestOptionalString(runtime, "composeProject", "$source ($containerSource)"),
            composeService = manifestOptionalString(runtime, "composeService", "$source ($containerSource)"),
            composeConfigHash = manifestOptionalString(runtime, "composeConfigHash", "$source ($containerSource)"),
            composeConfigFiles = manifestOptionalString(runtime, "composeConfigFiles", "$source ($containerSource)"),
            configurationSha256 = manifestString(
                runtime,
                "configurationSha256",
                "$source ($containerSource)",
            ),
        )
    }
    val duplicateServices = containers.groupingBy(DockerContainerRuntime::service)
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    if (duplicateServices.isNotEmpty()) {
        throw GradleException(
            "Benchmark manifest '$key.containers' contains duplicate services $duplicateServices: $source"
        )
    }
    return BenchmarkInfrastructureRuntime(
        capturedAt = manifestString(infrastructure, "capturedAt", source),
        clientLocation = manifestString(infrastructure, "clientLocation", source),
        dockerServer = manifestOptionalString(infrastructure, "dockerServer", source),
        containers = containers,
    )
}

fun requireManifestValue(actual: Any?, expected: Any?, field: String, source: String) {
    if (actual != expected) {
        throw GradleException(
            "Benchmark manifest '$field' mismatch in $source: expected [$expected], found [$actual]."
        )
    }
}

fun requireManifestInfrastructureIdentity(
    actual: BenchmarkInfrastructureRuntime,
    requiredServices: List<BenchmarkRequiredService>,
    source: String,
) {
    val supportedContainerServices = setOf("Redis", "MongoDB", "Elasticsearch")
    val localServices = requiredServices
        .filter { service ->
            service.service in supportedContainerServices && isLocalBenchmarkHost(service.host)
        }
    val expectedServices = localServices
        .map(BenchmarkRequiredService::service)
    requireManifestValue(
        actual.containers.map(DockerContainerRuntime::service),
        expectedServices,
        "infrastructure.containers services",
        source,
    )
    if (expectedServices.isNotEmpty() && actual.dockerServer == null) {
        throw GradleException(
            "Benchmark manifest is missing run-time Docker server provenance for local services " +
            "$expectedServices: $source"
        )
    }
    actual.containers.zip(localServices).forEach { (runtime, requiredService) ->
        val internalPort = when (requiredService.service) {
            "Redis" -> 6379
            "MongoDB" -> 27017
            "Elasticsearch" -> 9200
            else -> error("Unsupported benchmark container service: ${requiredService.service}")
        }
        requireBenchmarkContainerEndpoint(runtime, requiredService, internalPort)
        if (runtime.composeProject == null ||
            runtime.composeService == null ||
            runtime.composeConfigHash == null
        ) {
            throw GradleException(
                "Benchmark manifest requires Compose identity and config hash for local " +
                    "${requiredService.service}: $source"
            )
        }
    }
}

fun validateBenchmarkInfrastructureRuntimeStability(
    runtimes: List<BenchmarkInfrastructureRuntime>,
    context: String,
) {
    if (runtimes.isEmpty()) {
        throw GradleException("No benchmark infrastructure runtime was available for $context.")
    }
    val clientLocations = runtimes.map(BenchmarkInfrastructureRuntime::clientLocation).distinct()
    if (clientLocations.size != 1) {
        throw GradleException(
            "Benchmark manifests mix different benchmark client locations for $context: $clientLocations"
        )
    }
    val dockerServers = runtimes.mapNotNull(BenchmarkInfrastructureRuntime::dockerServer).distinct()
    if (dockerServers.size > 1) {
        throw GradleException(
            "Benchmark manifests mix different Docker server runtimes for $context: $dockerServers"
        )
    }
    runtimes.flatMap(BenchmarkInfrastructureRuntime::containers)
        .groupBy(DockerContainerRuntime::service)
        .forEach { (service, containers) ->
            val identities = containers.distinct()
            if (identities.size != 1) {
                throw GradleException(
                    "Benchmark manifests mix different run-time $service container provenance for $context: " +
                        identities.map { runtime ->
                            "${runtime.containerId}/${runtime.imageId}/${runtime.configurationSha256}"
                        }
                )
            }
        }
}

fun validateBenchmarkInfrastructureRuntimeWindow(
    started: BenchmarkInfrastructureRuntime,
    completed: BenchmarkInfrastructureRuntime,
    requiredServices: List<BenchmarkRequiredService>,
    context: String,
) {
    requireManifestInfrastructureIdentity(started, requiredServices, "$context start")
    requireManifestInfrastructureIdentity(completed, requiredServices, "$context completion")
    val startedAt = runCatching { Instant.parse(started.capturedAt) }
        .getOrElse {
            throw GradleException(
                "Benchmark infrastructure start capture has an invalid timestamp for $context: " +
                    started.capturedAt
            )
        }
    val completedAt = runCatching { Instant.parse(completed.capturedAt) }
        .getOrElse {
            throw GradleException(
                "Benchmark infrastructure completion capture has an invalid timestamp for $context: " +
                    completed.capturedAt
            )
        }
    if (completedAt.isBefore(startedAt)) {
        throw GradleException(
            "Benchmark infrastructure completion capture precedes its start for $context: " +
                "$completedAt < $startedAt"
        )
    }
    validateBenchmarkInfrastructureRuntimeStability(
        runtimes = listOf(started, completed),
        context = "$context sampling window",
    )
}

fun benchmarkExecutionEnvironmentIdentity(
    javaVersion: String,
    vmName: String,
    vmVersion: String,
    osName: String,
    osVersion: String,
    osArch: String,
    availableProcessors: Int,
    physicalMemoryBytes: Long?,
): Map<String, Any?> {
    return linkedMapOf(
        "javaVersion" to javaVersion,
        "vmName" to vmName,
        "vmVersion" to vmVersion,
        "osName" to osName,
        "osVersion" to osVersion,
        "osArch" to osArch,
        "availableProcessors" to availableProcessors,
        "physicalMemoryBytes" to physicalMemoryBytes,
    )
}

fun requireBenchmarkExecutionEnvironmentCompatibility(
    screeningIdentity: Map<String, Any?>,
    confirmationIdentity: Map<String, Any?>,
    context: String,
) {
    requireManifestValue(
        confirmationIdentity,
        screeningIdentity,
        "screening/confirmation JVM, OS, and CPU identity",
        context,
    )
}

fun requireBenchmarkEnvironmentCompatibility(
    screening: ParsedBenchmarkRunManifest,
    confirmationIdentity: Map<String, Any?>,
    confirmationRequiredServices: List<BenchmarkRequiredService>,
    confirmationInfrastructure: List<BenchmarkInfrastructureRuntime>,
    context: String,
) {
    requireBenchmarkExecutionEnvironmentCompatibility(
        screeningIdentity = benchmarkExecutionEnvironmentIdentity(
            javaVersion = screening.javaVersion,
            vmName = screening.vmName,
            vmVersion = screening.vmVersion,
            osName = screening.osName,
            osVersion = screening.osVersion,
            osArch = screening.osArch,
            availableProcessors = screening.availableProcessors,
            physicalMemoryBytes = screening.physicalMemoryBytes,
        ),
        confirmationIdentity = confirmationIdentity,
        context = context,
    )
    requireManifestValue(
        confirmationRequiredServices,
        screening.requiredServices,
        "screening/confirmation required endpoints",
        context,
    )
    validateBenchmarkInfrastructureRuntimeStability(
        runtimes = listOf(
            screening.infrastructureRuntime,
            screening.completionInfrastructureRuntime,
        ) + confirmationInfrastructure,
        context = "$context screening/confirmation infrastructure",
    )
}

fun requireCurrentBenchmarkEnvironmentCompatibility(
    screening: ParsedBenchmarkRunManifest,
    requiredServices: List<BenchmarkRequiredService>,
    infrastructureRuntime: BenchmarkInfrastructureRuntime,
    context: String,
) {
    requireBenchmarkEnvironmentCompatibility(
        screening = screening,
        confirmationIdentity = benchmarkExecutionEnvironmentIdentity(
            javaVersion = System.getProperty("java.version"),
            vmName = System.getProperty("java.vm.name"),
            vmVersion = System.getProperty("java.vm.version"),
            osName = System.getProperty("os.name"),
            osVersion = System.getProperty("os.version"),
            osArch = System.getProperty("os.arch"),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            physicalMemoryBytes = physicalMemoryBytes(),
        ),
        confirmationRequiredServices = requiredServices,
        confirmationInfrastructure = listOf(infrastructureRuntime),
        context = context,
    )
}

fun requireBenchmarkManifestEnvironmentCompatibility(
    screening: ParsedBenchmarkRunManifest,
    confirmations: List<ParsedBenchmarkRunManifest>,
    context: String,
) {
    confirmations.forEach { confirmation ->
        requireBenchmarkEnvironmentCompatibility(
            screening = screening,
            confirmationIdentity = benchmarkExecutionEnvironmentIdentity(
                javaVersion = confirmation.javaVersion,
                vmName = confirmation.vmName,
                vmVersion = confirmation.vmVersion,
                osName = confirmation.osName,
                osVersion = confirmation.osVersion,
                osArch = confirmation.osArch,
                availableProcessors = confirmation.availableProcessors,
                physicalMemoryBytes = confirmation.physicalMemoryBytes,
            ),
            confirmationRequiredServices = confirmation.requiredServices,
            confirmationInfrastructure = listOf(
                confirmation.infrastructureRuntime,
                confirmation.completionInfrastructureRuntime,
            ),
            context = context,
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
    requireManifestValue(manifestInt(manifest, "schemaVersion", sourcePath), 2, "schemaVersion", sourcePath)
    requireManifestValue(manifest["status"], "SUCCESS", "status", sourcePath)

    val source = manifestMap(manifest, "source", sourcePath)
    val runSpec = manifestMap(manifest, "runSpec", sourcePath)
    val runtime = manifestMap(manifest, "runtime", sourcePath)
    val infrastructureRuntime = manifestInfrastructureRuntime(
        container = manifest,
        key = "infrastructure",
        source = sourcePath,
    )
    val completionInfrastructureRuntime = manifestInfrastructureRuntime(
        container = manifest,
        key = "infrastructureAtCompletion",
        source = sourcePath,
    )
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
    validateBenchmarkInfrastructureRuntimeWindow(
        started = infrastructureRuntime,
        completed = completionInfrastructureRuntime,
        requiredServices = requiredServices,
        context = sourcePath,
    )
    val runMetadata = manifestOptionalStringMap(runSpec, "metadata", sourcePath)
    requireManifestValue(runMetadata, group.suite.runMetadata, "runSpec.metadata", sourcePath)

    if (!resultFile.isFile || !humanFile.isFile) {
        throw GradleException("Benchmark artifacts referenced by manifest are missing: $sourcePath")
    }
    requireManifestValue(manifestString(resultArtifact, "path", sourcePath), resultFile.name, "artifacts.result.path", sourcePath)
    requireManifestValue(
        manifestLong(resultArtifact, "size", sourcePath),
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
        manifestLong(humanArtifact, "size", sourcePath),
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
        runMetadata = runMetadata,
        infrastructureRuntime = infrastructureRuntime,
        completionInfrastructureRuntime = completionInfrastructureRuntime,
        javaVersion = manifestString(runtime, "javaVersion", sourcePath),
        vmName = manifestString(runtime, "vmName", sourcePath),
        vmVersion = manifestString(runtime, "vmVersion", sourcePath),
        osName = manifestString(runtime, "osName", sourcePath),
        osVersion = manifestString(runtime, "osVersion", sourcePath),
        osArch = manifestString(runtime, "osArch", sourcePath),
        availableProcessors = manifestInt(runtime, "availableProcessors", sourcePath),
        physicalMemoryBytes = runtime["physicalMemoryBytes"]?.let {
            manifestLong(runtime, "physicalMemoryBytes", sourcePath)
        },
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
    validateBenchmarkInfrastructureRuntimeStability(
        runtimes = manifests.flatMap { manifest ->
            listOf(
                manifest.infrastructureRuntime,
                manifest.completionInfrastructureRuntime,
            )
        },
        context = context,
    )
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

enum class MongoBatchPairedVariant(
    val id: String,
    val displayName: String,
    val methodName: String,
    val taskSuffix: String,
) {
    DIRECT(
        id = "direct",
        displayName = "insertOne",
        methodName = "appendWithInsertOne",
        taskSuffix = "Direct",
    ),
    BATCH(
        id = "batch",
        displayName = "insertMany batch",
        methodName = "appendWithInsertManyBatch",
        taskSuffix = "Batch",
    ),
}

enum class MongoBatchPairedOrder(
    val id: String,
    val displayName: String,
) {
    DIRECT_THEN_BATCH(id = "AB", displayName = "insertOne → batch"),
    BATCH_THEN_DIRECT(id = "BA", displayName = "batch → insertOne"),
}

data class MongoBatchPairedTrialSpec(
    val threads: Int,
    val round: Int,
    val order: MongoBatchPairedOrder,
    val position: Int,
    val variant: MongoBatchPairedVariant,
    val taskSpec: BenchmarkTaskSpec,
    val task: TaskProvider<JavaExec>,
)

data class ParsedMongoBatchPairedLeg(
    val trial: MongoBatchPairedTrialSpec,
    val row: ParsedBenchmarkResult,
    val manifest: ParsedBenchmarkRunManifest,
)

data class MongoBatchPairedObservation(
    val threads: Int,
    val round: Int,
    val order: MongoBatchPairedOrder,
    val directScore: Double,
    val batchScore: Double,
    val unit: String,
) {
    val ratio: Double
        get() = batchScore / directScore
}

data class MongoBatchPairedExperiment(
    val legs: List<ParsedMongoBatchPairedLeg>,
    val observations: List<MongoBatchPairedObservation>,
) {
    val manifests: List<ParsedBenchmarkRunManifest>
        get() = legs.map(ParsedMongoBatchPairedLeg::manifest)
}

enum class MongoBatchPairedVerdict(
    val displayName: String,
) {
    PASS("PASS"),
    INCONCLUSIVE("INCONCLUSIVE"),
    REGRESSION("REGRESSION"),
}

data class MongoBatchPairedStatistics(
    val threads: Int,
    val pairCount: Int,
    val directGeometricMean: Double,
    val batchGeometricMean: Double,
    val geometricRatio: Double,
    val lower95Ratio: Double,
    val upper95Ratio: Double,
    val directThenBatchRatio: Double,
    val batchThenDirectRatio: Double,
    val minimumPairRatio: Double,
    val maximumPairRatio: Double,
    val passingPairCount: Int,
    val verdict: MongoBatchPairedVerdict,
)

val mongoBatchPairedRounds = 8
val mongoBatchPairedOperationsPerInvocation = 128
val mongoBatchPairedMinimumRatio = 1.05
val mongoBatchPairedProtocolVersion = "1"
val mongoBatchPairedBenchmarkClass =
    "me.ahoo.wow.benchmark.infrastructure.mongo.MongoEventStoreAppendBenchmark"

fun pairedT95Critical(pairCount: Int): Double {
    return when (pairCount) {
        4 -> 3.182446305
        8 -> 2.364624251
        24 -> 2.06865761
        else -> throw GradleException(
            "The paired benchmark protocol has no configured 95% Student-t critical value for $pairCount pairs."
        )
    }
}

fun mongoBatchPairedT95Critical(pairCount: Int): Double = pairedT95Critical(pairCount)

fun mongoBatchPairedOrder(round: Int): MongoBatchPairedOrder {
    return if (round % 2 == 1) {
        MongoBatchPairedOrder.DIRECT_THEN_BATCH
    } else {
        MongoBatchPairedOrder.BATCH_THEN_DIRECT
    }
}

fun MongoBatchPairedOrder.variants(): List<MongoBatchPairedVariant> {
    return when (this) {
        MongoBatchPairedOrder.DIRECT_THEN_BATCH ->
            listOf(MongoBatchPairedVariant.DIRECT, MongoBatchPairedVariant.BATCH)

        MongoBatchPairedOrder.BATCH_THEN_DIRECT ->
            listOf(MongoBatchPairedVariant.BATCH, MongoBatchPairedVariant.DIRECT)
    }
}

fun mongoBatchPairedTaskPath(taskName: String): String {
    return if (project.path == ":") {
        ":$taskName"
    } else {
        "${project.path}:$taskName"
    }
}

val mongoBatchPairedTrials = buildList {
    pairedMongoBatchAppendProfile.threads.forEach { threads ->
        (1..mongoBatchPairedRounds).forEach { round ->
            val order = mongoBatchPairedOrder(round)
            order.variants().forEachIndexed { index, variant ->
                val roundId = round.toString().padStart(2, '0')
                val position = index + 1
                val resultId = "round-$roundId-${order.id.lowercase()}-$position-${variant.id}"
                val suite = BenchmarkSuite(
                    id = "mongo-batch-append-paired-e2e",
                    displayName = "Mongo EventStore Batch Append Paired E2E",
                    includeClasses = listOf("$mongoBatchPairedBenchmarkClass.${variant.methodName}"),
                    resultFileName = "$resultId.json",
                    humanFileName = "$resultId-human.txt",
                    requiredForGroupedReport = false,
                    formalRegressionSource = false,
                    requiredServices = mongoBatchAppendSuite.requiredServices,
                    runMetadata = linkedMapOf(
                        "experiment" to "mongo-batch-append-paired-e2e",
                        "protocolVersion" to mongoBatchPairedProtocolVersion,
                        "pairCountPerThread" to mongoBatchPairedRounds.toString(),
                        "operationsPerInvocation" to mongoBatchPairedOperationsPerInvocation.toString(),
                        "statistic" to "paired-log-ratio-student-t",
                        "confidenceLevel" to "0.95",
                        "tCritical" to mongoBatchPairedT95Critical(mongoBatchPairedRounds).toString(),
                        "minimumRatio" to mongoBatchPairedMinimumRatio.toString(),
                        "acceptanceRule" to "lower95Ratio>minimumRatio",
                        "round" to round.toString(),
                        "order" to order.id,
                        "position" to position.toString(),
                        "variant" to variant.id,
                        "method" to variant.methodName,
                    ),
                )
                val taskName =
                    "benchmarkMongoBatchAppendPairedE2ET${threads}R${roundId}${variant.taskSuffix}"
                val taskSpec = BenchmarkTaskSpec(
                    taskName = taskName,
                    suite = suite,
                    profile = pairedMongoBatchAppendProfile.copy(threads = listOf(threads)),
                    description = "Runs paired Mongo append E2E round $round/$mongoBatchPairedRounds " +
                        "(${order.id} position $position: ${variant.displayName}) with $threads JMH thread(s).",
                )
                val task = registerBenchmarkThreadTask(
                    taskName = taskName,
                    suite = suite,
                    profile = taskSpec.profile,
                    threads = threads,
                )
                add(
                    MongoBatchPairedTrialSpec(
                        threads = threads,
                        round = round,
                        order = order,
                        position = position,
                        variant = variant,
                        taskSpec = taskSpec,
                        task = task,
                    )
                )
            }
        }
    }
}

mongoBatchPairedTrials.zipWithNext().forEach { (previous, current) ->
    current.task.configure {
        mustRunAfter(previous.task)
    }
}

fun parseMongoBatchPairedExperiment(parser: JsonSlurper = JsonSlurper()): MongoBatchPairedExperiment {
    val legs = mongoBatchPairedTrials.map { trial ->
        val report = parseBenchmarkGroup(
            parser = parser,
            group = benchmarkResultGroup(trial.taskSpec),
        )
        if (report.rows.size != 1 || report.manifests.size != 1) {
            throw GradleException(
                "Paired Mongo benchmark leg is missing or incomplete: ${trial.taskSpec.taskName}. " +
                    "Run benchmarkMongoBatchAppendPairedE2E first."
            )
        }
        val row = report.rows.single()
        val manifest = report.manifests.single()
        if (row.threads != trial.threads) {
            throw GradleException(
                "Paired Mongo benchmark thread mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.threads}, found ${row.threads}."
            )
        }
        if (row.mode != "thrpt" || !row.unit.equals("ops/s", ignoreCase = true)) {
            throw GradleException(
                "Paired Mongo benchmark must contain one thrpt ops/s row: ${trial.taskSpec.taskName}."
            )
        }
        if (!row.score.isFinite() || row.score <= 0.0) {
            throw GradleException(
                "Paired Mongo benchmark score must be positive and finite: ${trial.taskSpec.taskName}."
            )
        }
        val actualMethod = benchmarkMethodName(row)
        if (actualMethod != trial.variant.methodName) {
            throw GradleException(
                "Paired Mongo benchmark method mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.variant.methodName}, found $actualMethod."
            )
        }
        val expectedTaskPath = mongoBatchPairedTaskPath(trial.taskSpec.taskName)
        if (manifest.taskPath != expectedTaskPath) {
            throw GradleException(
                "Paired Mongo benchmark task path mismatch: expected $expectedTaskPath, found ${manifest.taskPath}."
            )
        }
        ParsedMongoBatchPairedLeg(trial = trial, row = row, manifest = manifest)
    }

    validateBenchmarkRunManifests(
        manifests = legs.map(ParsedMongoBatchPairedLeg::manifest),
        context = "Mongo EventStore Batch Append Paired E2E",
        requireSameRunId = true,
    )
    legs.zipWithNext().forEach { (previous, current) ->
        val previousCompletedAt = Instant.parse(previous.manifest.completedAt)
        val currentStartedAt = Instant.parse(current.manifest.startedAt)
        if (currentStartedAt.isBefore(previousCompletedAt)) {
            throw GradleException(
                "Paired Mongo benchmark legs did not execute in declared AB/BA order: " +
                    "${previous.trial.taskSpec.taskName} completed at $previousCompletedAt, " +
                    "${current.trial.taskSpec.taskName} started at $currentStartedAt."
            )
        }
    }

    val observations = legs.groupBy { it.trial.threads to it.trial.round }
        .toSortedMap(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
        .map { (key, roundLegs) ->
            val (threads, round) = key
            if (roundLegs.size != MongoBatchPairedVariant.entries.size) {
                throw GradleException(
                    "Paired Mongo benchmark round must contain direct and batch legs: threads=$threads, round=$round."
                )
            }
            val legsByVariant = roundLegs.associateBy { it.trial.variant }
            if (legsByVariant.size != MongoBatchPairedVariant.entries.size) {
                throw GradleException(
                    "Paired Mongo benchmark round contains duplicate variants: threads=$threads, round=$round."
                )
            }
            val expectedOrder = mongoBatchPairedOrder(round)
            val actualOrder = roundLegs.sortedBy { it.trial.position }.map { it.trial.variant }
            if (roundLegs.any { it.trial.order != expectedOrder } || actualOrder != expectedOrder.variants()) {
                throw GradleException(
                    "Paired Mongo benchmark round order mismatch: threads=$threads, round=$round, " +
                        "expected ${expectedOrder.id}, found $actualOrder."
                )
            }
            val direct = checkNotNull(legsByVariant[MongoBatchPairedVariant.DIRECT])
            val batch = checkNotNull(legsByVariant[MongoBatchPairedVariant.BATCH])
            if (!direct.row.unit.equals(batch.row.unit, ignoreCase = true)) {
                throw GradleException(
                    "Paired Mongo benchmark unit mismatch: threads=$threads, round=$round, " +
                        "direct=${direct.row.unit}, batch=${batch.row.unit}."
                )
            }
            MongoBatchPairedObservation(
                threads = threads,
                round = round,
                order = expectedOrder,
                directScore = direct.row.score,
                batchScore = batch.row.score,
                unit = direct.row.unit,
            )
        }

    pairedMongoBatchAppendProfile.threads.forEach { threads ->
        val threadRounds = observations.filter { it.threads == threads }.map { it.round }
        val expectedRounds = (1..mongoBatchPairedRounds).toList()
        if (threadRounds != expectedRounds) {
            throw GradleException(
                "Paired Mongo benchmark rounds are incomplete for threads=$threads: " +
                    "expected $expectedRounds, found $threadRounds."
            )
        }
    }
    return MongoBatchPairedExperiment(legs = legs, observations = observations)
}

fun geometricMean(values: List<Double>, context: String): Double {
    if (values.isEmpty()) {
        throw GradleException("Cannot calculate a geometric mean for an empty $context sample.")
    }
    values.forEach { value ->
        if (!value.isFinite() || value <= 0.0) {
            throw GradleException("$context values must be positive and finite: $value.")
        }
    }
    return kotlin.math.exp(values.sumOf { value -> kotlin.math.ln(value) } / values.size)
}

fun classifyMongoBatchPairedVerdict(
    lower95Ratio: Double,
    upper95Ratio: Double,
): MongoBatchPairedVerdict {
    if (!lower95Ratio.isFinite() || lower95Ratio <= 0.0 ||
        !upper95Ratio.isFinite() || upper95Ratio <= 0.0 ||
        lower95Ratio > upper95Ratio
    ) {
        throw GradleException(
            "Paired Mongo confidence bounds must be positive, finite, and ordered: " +
                "[$lower95Ratio, $upper95Ratio]."
        )
    }
    return when {
        lower95Ratio > mongoBatchPairedMinimumRatio -> MongoBatchPairedVerdict.PASS
        upper95Ratio < 1.0 -> MongoBatchPairedVerdict.REGRESSION
        else -> MongoBatchPairedVerdict.INCONCLUSIVE
    }
}

fun calculateMongoBatchPairedStatistics(
    observations: List<MongoBatchPairedObservation>,
): MongoBatchPairedStatistics {
    if (observations.size != mongoBatchPairedRounds) {
        throw GradleException(
            "Paired Mongo statistics require exactly $mongoBatchPairedRounds observations, " +
                "found ${observations.size}."
        )
    }
    val threads = observations.map(MongoBatchPairedObservation::threads).distinct().singleOrNull()
        ?: throw GradleException("Paired Mongo statistics cannot mix JMH thread counts.")
    val expectedRounds = (1..mongoBatchPairedRounds).toList()
    val actualRounds = observations.sortedBy(MongoBatchPairedObservation::round)
        .map(MongoBatchPairedObservation::round)
    if (actualRounds != expectedRounds) {
        throw GradleException(
            "Paired Mongo statistics require rounds $expectedRounds for threads=$threads, found $actualRounds."
        )
    }
    observations.forEach { observation ->
        val expectedOrder = mongoBatchPairedOrder(observation.round)
        if (observation.order != expectedOrder) {
            throw GradleException(
                "Paired Mongo statistics require ${expectedOrder.id} for threads=$threads, " +
                    "round=${observation.round}."
            )
        }
        if (!observation.unit.equals("ops/s", ignoreCase = true)) {
            throw GradleException(
                "Paired Mongo statistics require ops/s, found ${observation.unit} for threads=$threads."
            )
        }
        if (!observation.directScore.isFinite() || observation.directScore <= 0.0 ||
            !observation.batchScore.isFinite() || observation.batchScore <= 0.0
        ) {
            throw GradleException(
                "Paired Mongo statistics require positive finite scores for threads=$threads, " +
                    "round=${observation.round}."
            )
        }
    }

    val ratios = observations.map(MongoBatchPairedObservation::ratio)
    ratios.forEach { ratio ->
        if (!ratio.isFinite() || ratio <= 0.0) {
            throw GradleException("Paired Mongo ratios must be positive and finite: $ratio.")
        }
    }
    val logRatios = observations.map { observation ->
        kotlin.math.ln(observation.batchScore) - kotlin.math.ln(observation.directScore)
    }
    val meanLogRatio = logRatios.average()
    val sampleVariance = logRatios.sumOf { logRatio ->
        val deviation = logRatio - meanLogRatio
        deviation * deviation
    } / (logRatios.size - 1)
    val standardError = kotlin.math.sqrt(sampleVariance) / kotlin.math.sqrt(logRatios.size.toDouble())
    val margin = mongoBatchPairedT95Critical(logRatios.size) * standardError
    val lower95Ratio = kotlin.math.exp(meanLogRatio - margin)
    val upper95Ratio = kotlin.math.exp(meanLogRatio + margin)
    val geometricRatio = kotlin.math.exp(meanLogRatio)
    val orderRatios = observations.groupBy(MongoBatchPairedObservation::order)
        .mapValues { (order, orderObservations) ->
            geometricMean(
                values = orderObservations.map(MongoBatchPairedObservation::ratio),
                context = "${order.id} Mongo paired ratio",
            )
        }
    val directThenBatchRatio = orderRatios[MongoBatchPairedOrder.DIRECT_THEN_BATCH]
        ?: throw GradleException("Paired Mongo statistics are missing AB observations.")
    val batchThenDirectRatio = orderRatios[MongoBatchPairedOrder.BATCH_THEN_DIRECT]
        ?: throw GradleException("Paired Mongo statistics are missing BA observations.")
    val verdict = classifyMongoBatchPairedVerdict(lower95Ratio, upper95Ratio)
    return MongoBatchPairedStatistics(
        threads = threads,
        pairCount = observations.size,
        directGeometricMean = geometricMean(
            observations.map(MongoBatchPairedObservation::directScore),
            "direct Mongo throughput",
        ),
        batchGeometricMean = geometricMean(
            observations.map(MongoBatchPairedObservation::batchScore),
            "batch Mongo throughput",
        ),
        geometricRatio = geometricRatio,
        lower95Ratio = lower95Ratio,
        upper95Ratio = upper95Ratio,
        directThenBatchRatio = directThenBatchRatio,
        batchThenDirectRatio = batchThenDirectRatio,
        minimumPairRatio = ratios.min(),
        maximumPairRatio = ratios.max(),
        passingPairCount = ratios.count { it > mongoBatchPairedMinimumRatio },
        verdict = verdict,
    )
}

fun MongoBatchPairedExperiment.statistics(): List<MongoBatchPairedStatistics> {
    return observations.groupBy(MongoBatchPairedObservation::threads)
        .toSortedMap()
        .map { (_, threadObservations) ->
            calculateMongoBatchPairedStatistics(threadObservations)
        }
}

enum class MongoBatchOptionsPairedWorkload(
    val id: String,
    val displayName: String,
    val methodName: String,
    val operationsPerInvocation: Int,
) {
    ISOLATED(
        id = "isolated",
        displayName = "Isolated 1",
        methodName = "appendIsolated",
        operationsPerInvocation = 1,
    ),
    BURST_32(
        id = "burst32",
        displayName = "Burst 32",
        methodName = "appendBurst32",
        operationsPerInvocation = 32,
    ),
    REPRESENTATIVE_128(
        id = "representative128",
        displayName = "Representative 128",
        methodName = "appendRepresentative128",
        operationsPerInvocation = 128,
    ),
    SATURATED_512(
        id = "saturated512",
        displayName = "Saturated 512",
        methodName = "appendSaturated512",
        operationsPerInvocation = 512,
    ),
}

enum class PairedExperimentOrder(val id: String) {
    AB("AB"),
    BA("BA"),
}

data class MongoBatchOptionsPairedObservation(
    val workload: MongoBatchOptionsPairedWorkload,
    val threads: Int,
    val round: Int,
    val order: PairedExperimentOrder,
    val currentScore: Double,
    val finalistScore: Double,
    val currentAllocation: Double,
    val finalistAllocation: Double,
    val unit: String,
)

data class PairedMetricStatistics(
    val currentGeometricMean: Double,
    val finalistGeometricMean: Double,
    val geometricRatio: Double,
    val lower95Ratio: Double,
    val upper95Ratio: Double,
    val currentThenFinalistRatio: Double,
    val finalistThenCurrentRatio: Double,
    val minimumPairRatio: Double,
    val maximumPairRatio: Double,
    val lagOneResidualCorrelation: Double,
) {
    val orderEffectRatio: Double
        get() = maxOf(
            currentThenFinalistRatio / finalistThenCurrentRatio,
            finalistThenCurrentRatio / currentThenFinalistRatio,
        )

    fun diagnosticsPass(
        maximumOrderEffectRatio: Double,
        maximumAbsoluteLagOneCorrelation: Double,
    ): Boolean {
        return orderEffectRatio.isFinite() &&
            orderEffectRatio <= maximumOrderEffectRatio &&
            lagOneResidualCorrelation.isFinite() &&
            kotlin.math.abs(lagOneResidualCorrelation) <= maximumAbsoluteLagOneCorrelation
    }
}

data class MongoBatchOptionsPairedStratumStatistics(
    val workload: MongoBatchOptionsPairedWorkload,
    val threads: Int,
    val pairCount: Int,
    val throughput: PairedMetricStatistics,
    val allocation: PairedMetricStatistics,
    val requiredThroughputRatio: Double,
) {
    val equivalentTimeRatio: Double
        get() = 1.0 / throughput.geometricRatio

    val equivalentTimeLower95Ratio: Double
        get() = 1.0 / throughput.upper95Ratio

    val equivalentTimeUpper95Ratio: Double
        get() = 1.0 / throughput.lower95Ratio
}

enum class MongoBatchOptionsPairedVerdict {
    PASS,
    INCONCLUSIVE,
    REGRESSION,
    NO_BENEFIT,
}

val mongoBatchOptionsPairedRounds = 24
val mongoBatchOptionsPairedThreads = listOf(1, 4)
val mongoBatchOptionsPairedAllocationMaximumRatio = 1.10
val mongoBatchOptionsPairedGuardMinimumRatio = 1.0 / 1.10
val mongoBatchOptionsPairedSaturatedMinimumRatio = 0.95
val mongoBatchOptionsPairedMaximumOrderEffectRatio = 1.10
val mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation = 0.40
val mongoBatchOptionsPairedOrderSequence = listOf(
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
)

fun MongoBatchOptionsPairedStratumStatistics.safetyVerdict(): MongoBatchOptionsPairedVerdict {
    return when {
        throughput.upper95Ratio < requiredThroughputRatio ||
            allocation.lower95Ratio > mongoBatchOptionsPairedAllocationMaximumRatio ->
            MongoBatchOptionsPairedVerdict.REGRESSION

        !throughput.diagnosticsPass(
            mongoBatchOptionsPairedMaximumOrderEffectRatio,
            mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation,
        ) ||
            !allocation.diagnosticsPass(
                mongoBatchOptionsPairedMaximumOrderEffectRatio,
                mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation,
            ) ->
            MongoBatchOptionsPairedVerdict.INCONCLUSIVE

        throughput.lower95Ratio >= requiredThroughputRatio &&
            allocation.upper95Ratio <= mongoBatchOptionsPairedAllocationMaximumRatio ->
            MongoBatchOptionsPairedVerdict.PASS

        else -> MongoBatchOptionsPairedVerdict.INCONCLUSIVE
    }
}

fun mongoBatchOptionsPairedOrder(round: Int): PairedExperimentOrder {
    return mongoBatchOptionsPairedOrderSequence.getOrNull(round - 1)
        ?: throw GradleException(
            "Mongo batch-options paired round must be in 1..${mongoBatchOptionsPairedOrderSequence.size}: $round."
        )
}

fun lagOneCorrelation(values: List<Double>, context: String): Double {
    if (values.size < 3) {
        throw GradleException("$context requires at least three values.")
    }
    values.forEach { value ->
        if (!value.isFinite()) {
            throw GradleException("$context values must be finite: $value.")
        }
    }
    val spread = checkNotNull(values.maxOrNull()) - checkNotNull(values.minOrNull())
    val scale = maxOf(1.0, kotlin.math.abs(values.average()))
    if (spread <= 1.0e-12 * scale) {
        return 0.0
    }
    val leading = values.dropLast(1)
    val lagged = values.drop(1)
    val leadingMean = leading.average()
    val laggedMean = lagged.average()
    val numerator = leading.indices.sumOf { index ->
        (leading[index] - leadingMean) * (lagged[index] - laggedMean)
    }
    val leadingSquares = leading.sumOf { value ->
        val deviation = value - leadingMean
        deviation * deviation
    }
    val laggedSquares = lagged.sumOf { value ->
        val deviation = value - laggedMean
        deviation * deviation
    }
    val denominator = kotlin.math.sqrt(leadingSquares * laggedSquares)
    if (!denominator.isFinite() || denominator <= 0.0) {
        throw GradleException("$context has an invalid lag-one correlation denominator: $denominator.")
    }
    val correlation = numerator / denominator
    if (!correlation.isFinite() || correlation < -1.000_000_000_001 || correlation > 1.000_000_000_001) {
        throw GradleException("$context produced an invalid lag-one correlation: $correlation.")
    }
    return correlation.coerceIn(-1.0, 1.0)
}

fun calculatePairedMetricStatistics(
    currentValues: List<Double>,
    finalistValues: List<Double>,
    orders: List<PairedExperimentOrder>,
    context: String,
): PairedMetricStatistics {
    if (currentValues.size != finalistValues.size || currentValues.size != orders.size || currentValues.size < 2) {
        throw GradleException(
            "$context requires equally sized current, finalist, and order samples with at least two pairs."
        )
    }
    currentValues.forEach { value ->
        if (!value.isFinite() || value <= 0.0) {
            throw GradleException("$context current values must be positive and finite: $value.")
        }
    }
    finalistValues.forEach { value ->
        if (!value.isFinite() || value <= 0.0) {
            throw GradleException("$context finalist values must be positive and finite: $value.")
        }
    }
    val ratios = currentValues.indices.map { index ->
        val ratio = finalistValues[index] / currentValues[index]
        if (!ratio.isFinite() || ratio <= 0.0) {
            throw GradleException("$context ratios must be positive and finite: $ratio.")
        }
        ratio
    }
    val logRatios = ratios.map { ratio ->
        val logRatio = kotlin.math.ln(ratio)
        if (!logRatio.isFinite()) {
            throw GradleException("$context log ratios must be finite: $logRatio.")
        }
        logRatio
    }
    val meanLogRatio = logRatios.average()
    val sampleVariance = logRatios.sumOf { logRatio ->
        val deviation = logRatio - meanLogRatio
        deviation * deviation
    } / (logRatios.size - 1)
    val standardError = kotlin.math.sqrt(sampleVariance) / kotlin.math.sqrt(logRatios.size.toDouble())
    val margin = pairedT95Critical(logRatios.size) * standardError
    val orderRatios = orders.zip(ratios).groupBy(
        keySelector = { (order, _) -> order },
        valueTransform = { (_, ratio) -> ratio },
    )
    val currentThenFinalistRatio = orderRatios[PairedExperimentOrder.AB]
        ?.let { values -> geometricMean(values, "$context AB ratio") }
        ?: throw GradleException("$context is missing AB pairs.")
    val finalistThenCurrentRatio = orderRatios[PairedExperimentOrder.BA]
        ?.let { values -> geometricMean(values, "$context BA ratio") }
        ?: throw GradleException("$context is missing BA pairs.")
    val orderMeanLogRatios = orders.zip(logRatios).groupBy(
        keySelector = { (order, _) -> order },
        valueTransform = { (_, logRatio) -> logRatio },
    ).mapValues { (_, values) -> values.average() }
    val residualLogRatios = orders.indices.map { index ->
        logRatios[index] - checkNotNull(orderMeanLogRatios[orders[index]])
    }
    return PairedMetricStatistics(
        currentGeometricMean = geometricMean(currentValues, "$context current"),
        finalistGeometricMean = geometricMean(finalistValues, "$context finalist"),
        geometricRatio = kotlin.math.exp(meanLogRatio),
        lower95Ratio = kotlin.math.exp(meanLogRatio - margin),
        upper95Ratio = kotlin.math.exp(meanLogRatio + margin),
        currentThenFinalistRatio = currentThenFinalistRatio,
        finalistThenCurrentRatio = finalistThenCurrentRatio,
        minimumPairRatio = ratios.min(),
        maximumPairRatio = ratios.max(),
        lagOneResidualCorrelation =
            lagOneCorrelation(residualLogRatios, "$context order-adjusted log-ratio residuals"),
    )
}

fun calculateMongoBatchOptionsPairedStratumStatistics(
    observations: List<MongoBatchOptionsPairedObservation>,
): MongoBatchOptionsPairedStratumStatistics {
    if (observations.size != mongoBatchOptionsPairedRounds) {
        throw GradleException(
            "Mongo batch-options paired statistics require exactly $mongoBatchOptionsPairedRounds observations, " +
                "found ${observations.size}."
        )
    }
    val workload = observations.map(MongoBatchOptionsPairedObservation::workload).distinct().singleOrNull()
        ?: throw GradleException("Mongo batch-options paired statistics cannot mix workloads.")
    val threads = observations.map(MongoBatchOptionsPairedObservation::threads).distinct().singleOrNull()
        ?: throw GradleException("Mongo batch-options paired statistics cannot mix JMH thread counts.")
    val sortedObservations = observations.sortedBy(MongoBatchOptionsPairedObservation::round)
    val expectedRounds = (1..mongoBatchOptionsPairedRounds).toList()
    val actualRounds = sortedObservations.map(MongoBatchOptionsPairedObservation::round)
    if (actualRounds != expectedRounds) {
        throw GradleException(
            "Mongo batch-options paired statistics require rounds $expectedRounds for " +
                "${workload.id}/threads=$threads, found $actualRounds."
        )
    }
    sortedObservations.forEach { observation ->
        val expectedOrder = mongoBatchOptionsPairedOrder(observation.round)
        if (observation.order != expectedOrder) {
            throw GradleException(
                "Mongo batch-options paired statistics require ${expectedOrder.id} for " +
                    "${workload.id}/threads=$threads/round=${observation.round}."
            )
        }
        if (!observation.unit.equals("ops/s", ignoreCase = true)) {
            throw GradleException(
                "Mongo batch-options paired statistics require ops/s, found ${observation.unit}."
            )
        }
    }
    val orders = sortedObservations.map(MongoBatchOptionsPairedObservation::order)
    val throughput = calculatePairedMetricStatistics(
        currentValues = sortedObservations.map(MongoBatchOptionsPairedObservation::currentScore),
        finalistValues = sortedObservations.map(MongoBatchOptionsPairedObservation::finalistScore),
        orders = orders,
        context = "Mongo batch-options ${workload.id}/threads=$threads throughput",
    )
    val allocation = calculatePairedMetricStatistics(
        currentValues = sortedObservations.map(MongoBatchOptionsPairedObservation::currentAllocation),
        finalistValues = sortedObservations.map(MongoBatchOptionsPairedObservation::finalistAllocation),
        orders = orders,
        context = "Mongo batch-options ${workload.id}/threads=$threads allocation",
    )
    val requiredThroughputRatio = if (workload == MongoBatchOptionsPairedWorkload.SATURATED_512) {
        mongoBatchOptionsPairedSaturatedMinimumRatio
    } else {
        mongoBatchOptionsPairedGuardMinimumRatio
    }
    return MongoBatchOptionsPairedStratumStatistics(
        workload = workload,
        threads = threads,
        pairCount = observations.size,
        throughput = throughput,
        allocation = allocation,
        requiredThroughputRatio = requiredThroughputRatio,
    )
}

fun classifyMongoBatchOptionsPairedExperiment(
    statistics: List<MongoBatchOptionsPairedStratumStatistics>,
): MongoBatchOptionsPairedVerdict {
    val expectedStrata = MongoBatchOptionsPairedWorkload.entries
        .flatMap { workload -> mongoBatchOptionsPairedThreads.map { threads -> workload to threads } }
        .toSet()
    val actualStrata = statistics.map { statistic -> statistic.workload to statistic.threads }.toSet()
    if (statistics.size != expectedStrata.size || actualStrata != expectedStrata) {
        throw GradleException(
            "Mongo batch-options paired verdict requires strata $expectedStrata, found $actualStrata."
        )
    }
    val regression = statistics.any { statistic ->
        statistic.throughput.upper95Ratio < statistic.requiredThroughputRatio ||
            statistic.allocation.lower95Ratio > mongoBatchOptionsPairedAllocationMaximumRatio
    }
    if (regression) {
        return MongoBatchOptionsPairedVerdict.REGRESSION
    }
    val safetyPass = statistics.all { statistic ->
        statistic.throughput.lower95Ratio >= statistic.requiredThroughputRatio &&
            statistic.allocation.upper95Ratio <= mongoBatchOptionsPairedAllocationMaximumRatio &&
            statistic.throughput.diagnosticsPass(
                mongoBatchOptionsPairedMaximumOrderEffectRatio,
                mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation,
            ) &&
            statistic.allocation.diagnosticsPass(
                mongoBatchOptionsPairedMaximumOrderEffectRatio,
                mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation,
            )
    }
    val primaryStatistics = statistics.filter { statistic ->
        statistic.workload == MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128
    }
    if (safetyPass && primaryStatistics.all { statistic -> statistic.throughput.lower95Ratio > 1.0 }) {
        return MongoBatchOptionsPairedVerdict.PASS
    }
    if (safetyPass && primaryStatistics.any { statistic -> statistic.throughput.upper95Ratio <= 1.0 }) {
        return MongoBatchOptionsPairedVerdict.NO_BENEFIT
    }
    return MongoBatchOptionsPairedVerdict.INCONCLUSIVE
}

enum class MongoBatchOptionsPairedVariant(
    val id: String,
    val displayName: String,
    val taskSuffix: String,
) {
    CURRENT(id = "current", displayName = "current", taskSuffix = "Current"),
    FINALIST(id = "finalist", displayName = "finalist", taskSuffix = "Finalist"),
}

val MongoBatchOptionsPairedVariant.batchOptions: String
    get() = when (this) {
        MongoBatchOptionsPairedVariant.CURRENT -> mongoBatchOptionsPairedCurrent
        MongoBatchOptionsPairedVariant.FINALIST -> mongoBatchOptionsPairedFinalist
    }

fun requireMongoBatchOptionsPairedProtocol() {
    if (mongoBatchOptionsPairedConfiguredFinalist == null) {
        throw GradleException(
            "Mongo batch-options paired confirmation requires " +
                "-P$mongoBatchOptionsPairedFinalistProperty=<maxSize>x<maxDelayMicros>us."
        )
    }
    requireStorageBatchTuningOption(
        mongoBatchOptionsPairedFinalist,
        "Gradle property $mongoBatchOptionsPairedFinalistProperty",
    )
    if (mongoBatchOptionsPairedCurrent == mongoBatchOptionsPairedFinalist) {
        throw GradleException(
            "Mongo batch-options paired confirmation requires distinct current and finalist options."
        )
    }
    if (mongoBatchOptionsPairedOrderSequence.size != mongoBatchOptionsPairedRounds ||
        mongoBatchOptionsPairedOrderSequence.count { order -> order == PairedExperimentOrder.AB } !=
        mongoBatchOptionsPairedRounds / 2 ||
        mongoBatchOptionsPairedOrderSequence.count { order -> order == PairedExperimentOrder.BA } !=
        mongoBatchOptionsPairedRounds / 2
    ) {
        throw GradleException(
            "Mongo batch-options paired confirmation requires a fixed balanced order sequence " +
                "with $mongoBatchOptionsPairedRounds rounds."
        )
    }
}

fun PairedExperimentOrder.variants(): List<MongoBatchOptionsPairedVariant> {
    return when (this) {
        PairedExperimentOrder.AB ->
            listOf(MongoBatchOptionsPairedVariant.CURRENT, MongoBatchOptionsPairedVariant.FINALIST)

        PairedExperimentOrder.BA ->
            listOf(MongoBatchOptionsPairedVariant.FINALIST, MongoBatchOptionsPairedVariant.CURRENT)
    }
}

data class MongoBatchOptionsPairedTrialSpec(
    val workload: MongoBatchOptionsPairedWorkload,
    val threads: Int,
    val round: Int,
    val order: PairedExperimentOrder,
    val position: Int,
    val variant: MongoBatchOptionsPairedVariant,
    val taskSpec: BenchmarkTaskSpec,
    val task: TaskProvider<JavaExec>,
)

data class ParsedMongoBatchOptionsPairedLeg(
    val trial: MongoBatchOptionsPairedTrialSpec,
    val row: ParsedBenchmarkResult,
    val manifest: ParsedBenchmarkRunManifest,
)

data class MongoBatchOptionsPairedExperiment(
    val legs: List<ParsedMongoBatchOptionsPairedLeg>,
    val observations: List<MongoBatchOptionsPairedObservation>,
) {
    val manifests: List<ParsedBenchmarkRunManifest>
        get() = legs.map(ParsedMongoBatchOptionsPairedLeg::manifest)

    fun statistics(): List<MongoBatchOptionsPairedStratumStatistics> {
        return observations.groupBy { observation -> observation.workload to observation.threads }
            .toSortedMap(compareBy<Pair<MongoBatchOptionsPairedWorkload, Int>>({ it.first.ordinal }, { it.second }))
            .map { (_, stratumObservations) ->
                calculateMongoBatchOptionsPairedStratumStatistics(stratumObservations)
            }
    }
}

data class BenchmarkEvidenceWindow(
    val id: String,
    val startedAt: Instant,
    val completedAt: Instant,
)

fun validateNonOverlappingBenchmarkEvidenceWindows(
    windows: List<BenchmarkEvidenceWindow>,
    context: String,
) {
    if (windows.isEmpty()) {
        throw GradleException("$context requires at least one evidence window.")
    }
    windows.forEach { window ->
        if (window.startedAt.isAfter(window.completedAt)) {
            throw GradleException(
                "$context has an invalid evidence window for ${window.id}: " +
                    "${window.startedAt} to ${window.completedAt}."
            )
        }
    }
    windows.sortedBy(BenchmarkEvidenceWindow::startedAt)
        .zipWithNext()
        .forEach { (previous, current) ->
            if (current.startedAt.isBefore(previous.completedAt)) {
                throw GradleException(
                    "$context has overlapping evidence windows: " +
                        "${previous.id} (${previous.startedAt} to ${previous.completedAt}) and " +
                        "${current.id} (${current.startedAt} to ${current.completedAt})."
                )
            }
        }
}

val mongoBatchOptionsPairedProtocolVersion = "3"
val mongoBatchOptionsPairedCurrent = "128x1000us"
val mongoBatchOptionsPairedFinalistProperty = "benchmarkMongoBatchOptionsPairedFinalist"
val mongoBatchOptionsPairedUnconfiguredFinalist = "UNCONFIGURED"
val mongoBatchOptionsPairedConfiguredFinalist =
    providers.gradleProperty(mongoBatchOptionsPairedFinalistProperty)
        .map(String::trim)
        .orNull
val mongoBatchOptionsPairedFinalist =
    mongoBatchOptionsPairedConfiguredFinalist ?: mongoBatchOptionsPairedUnconfiguredFinalist
val mongoBatchOptionsPairedBenchmarkClass =
    "me.ahoo.wow.benchmark.infrastructure.mongo.MongoEventStoreBatchTuningBenchmark"
val mongoBatchOptionsPairedPreflight = tasks.register("preflightMongoBatchOptionsPairedConfirmation") {
    description = "Historical stopped Mongo paired preflight; retained for artifact audit only."
    group = "benchmark"
    dependsOn(tasks.named("jmhJar"))

    doLast {
        val jmhJarFile = tasks.named<Jar>("jmhJar").get().archiveFile.get().asFile
        requireMongoBatchOptionsPairedPreflight(fileSha256(jmhJarFile))
    }
}

val mongoBatchOptionsPairedTrials = buildList {
    mongoBatchOptionsPairedThreads.forEach { threads ->
        MongoBatchOptionsPairedWorkload.entries.forEach { workload ->
            (1..mongoBatchOptionsPairedRounds).forEach { round ->
                val order = mongoBatchOptionsPairedOrder(round)
                order.variants().forEachIndexed { index, variant ->
                    val roundId = round.toString().padStart(2, '0')
                    val position = index + 1
                    val resultId =
                        "${workload.id}-round-$roundId-${order.id.lowercase()}-$position-${variant.id}"
                    val suite = BenchmarkSuite(
                        id = "mongo-batch-options-paired-confirmation",
                        displayName = "Mongo EventStore Batch Options Paired Confirmation",
                        includeClasses = listOf("$mongoBatchOptionsPairedBenchmarkClass.${workload.methodName}"),
                        resultFileName = "$resultId.json",
                        humanFileName = "$resultId-human.txt",
                        requiredForGroupedReport = false,
                        formalRegressionSource = false,
                        requiresCleanSource = true,
                        requiredServices = mongoBatchAppendSuite.requiredServices,
                        runMetadata = linkedMapOf(
                            "experiment" to "mongo-batch-options-paired-confirmation",
                            "protocolVersion" to mongoBatchOptionsPairedProtocolVersion,
                            "pairCountPerStratum" to mongoBatchOptionsPairedRounds.toString(),
                            "currentBatchOptions" to mongoBatchOptionsPairedCurrent,
                            "finalistBatchOptions" to mongoBatchOptionsPairedFinalist,
                            "statistic" to "paired-log-ratio-student-t",
                            "confidenceLevel" to "0.95",
                            "tCritical" to pairedT95Critical(mongoBatchOptionsPairedRounds).toString(),
                            "guardMinimumRatio" to mongoBatchOptionsPairedGuardMinimumRatio.toString(),
                            "saturatedMinimumRatio" to mongoBatchOptionsPairedSaturatedMinimumRatio.toString(),
                            "allocationMaximumRatio" to mongoBatchOptionsPairedAllocationMaximumRatio.toString(),
                            "maximumOrderEffectRatio" to
                                mongoBatchOptionsPairedMaximumOrderEffectRatio.toString(),
                            "maximumAbsoluteLagOneResidualCorrelation" to
                                mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation.toString(),
                            "orderSequence" to
                                mongoBatchOptionsPairedOrderSequence.joinToString("") { sequenceOrder ->
                                    sequenceOrder.id
                                },
                            "primaryWorkload" to MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128.id,
                            "acceptanceRule" to
                                "allSafetyBoundsPass&&primaryRepresentativeLower95Ratio>1",
                            "workload" to workload.id,
                            "runScope" to "${workload.id}-t$threads",
                            "method" to workload.methodName,
                            "operationsPerInvocation" to workload.operationsPerInvocation.toString(),
                            "round" to round.toString(),
                            "order" to order.id,
                            "position" to position.toString(),
                            "variant" to variant.id,
                            "batchOptions" to variant.batchOptions,
                        ),
                    )
                    val taskName =
                        "benchmarkMongoBatchOptionsPairedConfirmation" +
                            "T${threads}${workload.id.replaceFirstChar(Char::uppercase)}" +
                            "R$roundId${variant.taskSuffix}"
                    val profile = pairedMongoBatchOptionsProfile.copy(
                        threads = listOf(threads),
                        parameters = mapOf("batchOptions" to variant.batchOptions),
                    )
                    val taskSpec = BenchmarkTaskSpec(
                        taskName = taskName,
                        suite = suite,
                        profile = profile,
                        description =
                            "Historical stopped Mongo paired leg for ${workload.displayName}, " +
                                "threads=$threads, round=$round/$mongoBatchOptionsPairedRounds " +
                                "(${order.id} position $position: ${variant.displayName} ${variant.batchOptions}).",
                    )
                    val task = registerBenchmarkThreadTask(
                        taskName = taskName,
                        suite = suite,
                        profile = profile,
                        threads = threads,
                    )
                    task.configure {
                        dependsOn(mongoBatchOptionsPairedPreflight)
                    }
                    add(
                        MongoBatchOptionsPairedTrialSpec(
                            workload = workload,
                            threads = threads,
                            round = round,
                            order = order,
                            position = position,
                            variant = variant,
                            taskSpec = taskSpec,
                            task = task,
                        )
                    )
                }
            }
        }
    }
}

mongoBatchOptionsPairedTrials.zipWithNext().forEach { (previous, current) ->
    current.task.configure {
        mustRunAfter(previous.task)
    }
}

fun parseMongoBatchOptionsPairedExperiment(
    parser: JsonSlurper = JsonSlurper(),
): MongoBatchOptionsPairedExperiment {
    val legs = mongoBatchOptionsPairedTrials.map { trial ->
        val report = parseBenchmarkGroup(
            parser = parser,
            group = benchmarkResultGroup(trial.taskSpec),
        )
        if (report.rows.size != 1 || report.manifests.size != 1) {
            throw GradleException(
                "Mongo batch-options paired leg is missing or incomplete: ${trial.taskSpec.taskName}. " +
                    "Run benchmarkMongoBatchOptionsPairedConfirmation first."
            )
        }
        val row = report.rows.single()
        val manifest = report.manifests.single()
        if (row.threads != trial.threads) {
            throw GradleException(
                "Mongo batch-options paired thread mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.threads}, found ${row.threads}."
            )
        }
        if (row.mode != "thrpt" || !row.unit.equals("ops/s", ignoreCase = true)) {
            throw GradleException(
                "Mongo batch-options paired leg must contain one thrpt ops/s row: ${trial.taskSpec.taskName}."
            )
        }
        if (!row.score.isFinite() || row.score <= 0.0) {
            throw GradleException(
                "Mongo batch-options paired score must be positive and finite: ${trial.taskSpec.taskName}."
            )
        }
        val allocation = row.allocationBytesPerOp
        if (allocation == null || !allocation.isFinite() || allocation <= 0.0) {
            throw GradleException(
                "Mongo batch-options paired allocation must be positive and finite: ${trial.taskSpec.taskName}."
            )
        }
        val actualMethod = benchmarkMethodName(row)
        if (actualMethod != trial.workload.methodName) {
            throw GradleException(
                "Mongo batch-options paired method mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.workload.methodName}, found $actualMethod."
            )
        }
        val actualBatchOptions = row.parameters["batchOptions"]
        if (actualBatchOptions != trial.variant.batchOptions) {
            throw GradleException(
                "Mongo batch-options paired parameter mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.variant.batchOptions}, found $actualBatchOptions."
            )
        }
        val expectedTaskPath = mongoBatchPairedTaskPath(trial.taskSpec.taskName)
        if (manifest.taskPath != expectedTaskPath) {
            throw GradleException(
                "Mongo batch-options paired task path mismatch: " +
                    "expected $expectedTaskPath, found ${manifest.taskPath}."
            )
        }
        ParsedMongoBatchOptionsPairedLeg(trial = trial, row = row, manifest = manifest)
    }
    validateBenchmarkRunManifests(
        manifests = legs.map(ParsedMongoBatchOptionsPairedLeg::manifest),
        context = "Mongo EventStore Batch Options Paired Confirmation",
        requireSameRunId = false,
    )
    if (legs.any { leg -> leg.manifest.sourceDirty }) {
        throw GradleException(
            "Mongo batch-options paired confirmation requires sourceDirty=false for every leg."
        )
    }
    val stratumLegGroups = legs.groupBy { leg -> leg.trial.workload to leg.trial.threads }
    stratumLegGroups
        .forEach { (stratum, stratumLegs) ->
            val (workload, threads) = stratum
            val context = "Mongo EventStore Batch Options Paired Confirmation " +
                "${workload.id}/threads=$threads"
            validateBenchmarkRunManifests(
                manifests = stratumLegs.map(ParsedMongoBatchOptionsPairedLeg::manifest),
                context = context,
                requireSameRunId = true,
            )
            stratumLegs.zipWithNext().forEach { (previous, current) ->
                val previousCompletedAt = Instant.parse(previous.manifest.completedAt)
                val currentStartedAt = Instant.parse(current.manifest.startedAt)
                if (currentStartedAt.isBefore(previousCompletedAt)) {
                    throw GradleException(
                        "Mongo batch-options paired legs did not execute in declared stratum order: " +
                            "${previous.trial.taskSpec.taskName} completed at $previousCompletedAt, " +
                            "${current.trial.taskSpec.taskName} started at $currentStartedAt."
                    )
                }
            }
        }
    validateNonOverlappingBenchmarkEvidenceWindows(
        windows = stratumLegGroups.map { (stratum, stratumLegs) ->
            val (workload, threads) = stratum
            BenchmarkEvidenceWindow(
                id = "${workload.id}/threads=$threads",
                startedAt = stratumLegs.minOf { leg -> Instant.parse(leg.manifest.startedAt) },
                completedAt = stratumLegs.maxOf { leg -> Instant.parse(leg.manifest.completedAt) },
            )
        },
        context = "Mongo EventStore Batch Options Paired Confirmation",
    )
    val observations = legs.groupBy { leg ->
        Triple(leg.trial.workload, leg.trial.threads, leg.trial.round)
    }
        .toSortedMap(
            compareBy<Triple<MongoBatchOptionsPairedWorkload, Int, Int>>(
                { it.first.ordinal },
                { it.second },
                { it.third },
            )
        )
        .map { (key, roundLegs) ->
            val (workload, threads, round) = key
            if (roundLegs.size != MongoBatchOptionsPairedVariant.entries.size) {
                throw GradleException(
                    "Mongo batch-options paired round must contain current and finalist legs: " +
                        "${workload.id}/threads=$threads/round=$round."
                )
            }
            val legsByVariant = roundLegs.associateBy { leg -> leg.trial.variant }
            if (legsByVariant.size != MongoBatchOptionsPairedVariant.entries.size) {
                throw GradleException(
                    "Mongo batch-options paired round contains duplicate variants: " +
                        "${workload.id}/threads=$threads/round=$round."
                )
            }
            val expectedOrder = mongoBatchOptionsPairedOrder(round)
            val actualOrder = roundLegs.sortedBy { leg -> leg.trial.position }
                .map { leg -> leg.trial.variant }
            if (roundLegs.any { leg -> leg.trial.order != expectedOrder } ||
                actualOrder != expectedOrder.variants()
            ) {
                throw GradleException(
                    "Mongo batch-options paired round order mismatch for " +
                        "${workload.id}/threads=$threads/round=$round: " +
                        "expected ${expectedOrder.id}, found $actualOrder."
                )
            }
            val current = checkNotNull(legsByVariant[MongoBatchOptionsPairedVariant.CURRENT])
            val finalist = checkNotNull(legsByVariant[MongoBatchOptionsPairedVariant.FINALIST])
            if (!current.row.unit.equals(finalist.row.unit, ignoreCase = true)) {
                throw GradleException(
                    "Mongo batch-options paired unit mismatch for " +
                        "${workload.id}/threads=$threads/round=$round."
                )
            }
            MongoBatchOptionsPairedObservation(
                workload = workload,
                threads = threads,
                round = round,
                order = expectedOrder,
                currentScore = current.row.score,
                finalistScore = finalist.row.score,
                currentAllocation = checkNotNull(current.row.allocationBytesPerOp),
                finalistAllocation = checkNotNull(finalist.row.allocationBytesPerOp),
                unit = current.row.unit,
            )
        }
    val expectedRounds = (1..mongoBatchOptionsPairedRounds).toList()
    MongoBatchOptionsPairedWorkload.entries.forEach { workload ->
        mongoBatchOptionsPairedThreads.forEach { threads ->
            val actualRounds = observations
                .filter { observation -> observation.workload == workload && observation.threads == threads }
                .map(MongoBatchOptionsPairedObservation::round)
            if (actualRounds != expectedRounds) {
                throw GradleException(
                    "Mongo batch-options paired rounds are incomplete for ${workload.id}/threads=$threads: " +
                        "expected $expectedRounds, found $actualRounds."
                )
            }
        }
    }
    return MongoBatchOptionsPairedExperiment(legs = legs, observations = observations)
}

fun validateMongoBatchOptionsPairedStratumArtifacts(
    trials: List<MongoBatchOptionsPairedTrialSpec>,
    parser: JsonSlurper = JsonSlurper(),
) {
    if (trials.isEmpty()) {
        throw GradleException("Mongo batch-options paired stratum cannot be empty.")
    }
    val workload = trials.map(MongoBatchOptionsPairedTrialSpec::workload).distinct().singleOrNull()
        ?: throw GradleException("Mongo batch-options paired stratum cannot mix workloads.")
    val threads = trials.map(MongoBatchOptionsPairedTrialSpec::threads).distinct().singleOrNull()
        ?: throw GradleException("Mongo batch-options paired stratum cannot mix JMH thread counts.")
    val artifacts = trials.map { trial ->
        val report = parseBenchmarkGroup(
            parser = parser,
            group = benchmarkResultGroup(trial.taskSpec),
        )
        if (report.rows.size != 1 || report.manifests.size != 1) {
            throw GradleException(
                "Mongo batch-options paired stratum leg is missing or incomplete: ${trial.taskSpec.taskName}."
            )
        }
        trial to report.manifests.single()
    }
    val manifests = artifacts.map { (_, manifest) -> manifest }
    validateBenchmarkRunManifests(
        manifests = manifests,
        context = "Mongo EventStore Batch Options Paired Confirmation ${workload.id}/threads=$threads",
        requireSameRunId = true,
    )
    if (manifests.any(ParsedBenchmarkRunManifest::sourceDirty)) {
        throw GradleException(
            "Mongo batch-options paired confirmation requires sourceDirty=false for every leg."
        )
    }
    artifacts.zipWithNext().forEach { (previous, current) ->
        val previousCompletedAt = Instant.parse(previous.second.completedAt)
        val currentStartedAt = Instant.parse(current.second.startedAt)
        if (currentStartedAt.isBefore(previousCompletedAt)) {
            throw GradleException(
                "Mongo batch-options paired legs did not execute in declared stratum order: " +
                    "${previous.first.taskSpec.taskName} completed at $previousCompletedAt, " +
                    "${current.first.taskSpec.taskName} started at $currentStartedAt."
            )
        }
    }
}

val benchmarkMongoBatchOptionsPairedStrata = mongoBatchOptionsPairedTrials
    .groupBy { trial -> trial.workload to trial.threads }
    .map { (stratum, stratumTrials) ->
        val (workload, threads) = stratum
        val taskName =
            "benchmarkMongoBatchOptionsPairedConfirmation" +
                "T${threads}${workload.id.replaceFirstChar(Char::uppercase)}"
        tasks.register(taskName) {
            description =
                "Historical stopped 24-pair Mongo batch-options stratum for " +
                    "${workload.displayName}, threads=$threads."
            group = "benchmark"
            dependsOn(stratumTrials.map(MongoBatchOptionsPairedTrialSpec::task))

            doLast {
                validateMongoBatchOptionsPairedStratumArtifacts(stratumTrials)
            }
        }
    }

val benchmarkMongoBatchOptionsPairedConfirmation =
    tasks.register("benchmarkMongoBatchOptionsPairedConfirmation") {
        description = "Historical stopped Mongo paired campaign; retained for artifact audit only."
        group = "benchmark"
        dependsOn(benchmarkMongoBatchOptionsPairedStrata)

        doLast {
            val jmhJarFile = tasks.named<Jar>("jmhJar").get().archiveFile.get().asFile
            val screening = requireMongoBatchOptionsPairedPreflight(fileSha256(jmhJarFile))
            val experiment = parseMongoBatchOptionsPairedExperiment()
            validateMongoBatchOptionsPairedPostflight(screening, experiment)
        }
    }

data class MongoBatchOptionsQuickStratum(
    val workload: MongoBatchOptionsPairedWorkload,
    val threads: Int,
)

data class MongoBatchOptionsQuickTrialSpec(
    val stratum: MongoBatchOptionsQuickStratum,
    val round: Int,
    val order: PairedExperimentOrder,
    val position: Int,
    val variant: MongoBatchOptionsPairedVariant,
    val batchOptions: String,
    val taskSpec: BenchmarkTaskSpec,
    val task: TaskProvider<JavaExec>,
)

data class ParsedMongoBatchOptionsQuickLeg(
    val trial: MongoBatchOptionsQuickTrialSpec,
    val row: ParsedBenchmarkResult,
    val manifest: ParsedBenchmarkRunManifest,
)

data class MongoBatchOptionsQuickExperiment(
    val legs: List<ParsedMongoBatchOptionsQuickLeg>,
    val observations: List<MongoBatchOptionsPairedObservation>,
) {
    val manifests: List<ParsedBenchmarkRunManifest>
        get() = legs.map(ParsedMongoBatchOptionsQuickLeg::manifest)

    fun statistics(): List<MongoBatchOptionsPairedStratumStatistics> {
        return observations.groupBy { observation -> observation.workload to observation.threads }
            .toSortedMap(
                compareBy<Pair<MongoBatchOptionsPairedWorkload, Int>>(
                    { key -> mongoBatchOptionsQuickStrata.indexOfFirst { it.workload == key.first && it.threads == key.second } },
                    { it.second },
                )
            )
            .map { (_, stratumObservations) ->
                calculateMongoBatchOptionsQuickStratumStatistics(stratumObservations)
            }
    }
}

val mongoBatchOptionsQuickRounds = 4
val mongoBatchOptionsQuickProtocolVersion = "1"
val mongoBatchOptionsQuickOrderSequence = listOf(
    PairedExperimentOrder.AB,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.BA,
    PairedExperimentOrder.AB,
)
val mongoBatchOptionsQuickStrata = listOf(
    MongoBatchOptionsQuickStratum(
        workload = MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128,
        threads = 1,
    ),
    MongoBatchOptionsQuickStratum(
        workload = MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128,
        threads = 4,
    ),
    MongoBatchOptionsQuickStratum(
        workload = MongoBatchOptionsPairedWorkload.BURST_32,
        threads = 4,
    ),
)
val mongoBatchOptionsQuickBenchmarkClass =
    "me.ahoo.wow.benchmark.infrastructure.mongo.MongoEventStoreBatchTuningBenchmark"

fun mongoBatchOptionsQuickOrder(round: Int): PairedExperimentOrder {
    return mongoBatchOptionsQuickOrderSequence.getOrNull(round - 1)
        ?: throw GradleException(
            "Quick Mongo batch-options round must be in 1..$mongoBatchOptionsQuickRounds: $round."
        )
}

fun MongoBatchOptionsPairedVariant.quickBatchOptions(): String {
    return when (this) {
        MongoBatchOptionsPairedVariant.CURRENT -> mongoBatchQuickCurrentOptions
        MongoBatchOptionsPairedVariant.FINALIST -> mongoBatchQuickCandidateOptions
    }
}

fun calculateMongoBatchOptionsQuickStratumStatistics(
    observations: List<MongoBatchOptionsPairedObservation>,
): MongoBatchOptionsPairedStratumStatistics {
    if (observations.size != mongoBatchOptionsQuickRounds) {
        throw GradleException(
            "Quick Mongo batch-options statistics require exactly $mongoBatchOptionsQuickRounds observations, " +
                "found ${observations.size}."
        )
    }
    val workload = observations.map(MongoBatchOptionsPairedObservation::workload).distinct().singleOrNull()
        ?: throw GradleException("Quick Mongo batch-options statistics cannot mix workloads.")
    val threads = observations.map(MongoBatchOptionsPairedObservation::threads).distinct().singleOrNull()
        ?: throw GradleException("Quick Mongo batch-options statistics cannot mix JMH thread counts.")
    val expectedStratum = MongoBatchOptionsQuickStratum(workload = workload, threads = threads)
    if (expectedStratum !in mongoBatchOptionsQuickStrata) {
        throw GradleException("Unexpected quick Mongo batch-options stratum: $expectedStratum.")
    }
    val sortedObservations = observations.sortedBy(MongoBatchOptionsPairedObservation::round)
    val expectedRounds = (1..mongoBatchOptionsQuickRounds).toList()
    if (sortedObservations.map(MongoBatchOptionsPairedObservation::round) != expectedRounds) {
        throw GradleException(
            "Quick Mongo batch-options statistics require rounds $expectedRounds for " +
                "${workload.id}/threads=$threads."
        )
    }
    sortedObservations.forEach { observation ->
        val expectedOrder = mongoBatchOptionsQuickOrder(observation.round)
        if (observation.order != expectedOrder) {
            throw GradleException(
                "Quick Mongo batch-options statistics require ${expectedOrder.id} for " +
                    "${workload.id}/threads=$threads/round=${observation.round}."
            )
        }
        if (!observation.unit.equals("ops/s", ignoreCase = true)) {
            throw GradleException(
                "Quick Mongo batch-options statistics require ops/s, found ${observation.unit}."
            )
        }
    }
    val orders = sortedObservations.map(MongoBatchOptionsPairedObservation::order)
    val throughput = calculatePairedMetricStatistics(
        currentValues = sortedObservations.map(MongoBatchOptionsPairedObservation::currentScore),
        finalistValues = sortedObservations.map(MongoBatchOptionsPairedObservation::finalistScore),
        orders = orders,
        context = "Quick Mongo batch-options ${workload.id}/threads=$threads throughput",
    )
    val allocation = calculatePairedMetricStatistics(
        currentValues = sortedObservations.map(MongoBatchOptionsPairedObservation::currentAllocation),
        finalistValues = sortedObservations.map(MongoBatchOptionsPairedObservation::finalistAllocation),
        orders = orders,
        context = "Quick Mongo batch-options ${workload.id}/threads=$threads allocation",
    )
    return MongoBatchOptionsPairedStratumStatistics(
        workload = workload,
        threads = threads,
        pairCount = observations.size,
        throughput = throughput,
        allocation = allocation,
        requiredThroughputRatio = mongoBatchOptionsPairedGuardMinimumRatio,
    )
}

val mongoBatchOptionsQuickTrials = buildList {
    mongoBatchOptionsQuickStrata.forEach { stratum ->
        (1..mongoBatchOptionsQuickRounds).forEach { round ->
            val order = mongoBatchOptionsQuickOrder(round)
            order.variants().forEachIndexed { index, variant ->
                val roundId = round.toString().padStart(2, '0')
                val position = index + 1
                val batchOptions = variant.quickBatchOptions()
                val resultId =
                    "${stratum.workload.id}-t${stratum.threads}-round-$roundId-" +
                        "${order.id.lowercase()}-$position-${variant.id}"
                val suite = BenchmarkSuite(
                    id = "mongo-batch-options-quick-engineering",
                    displayName = "Quick Mongo EventStore Batch Options Engineering",
                    includeClasses = listOf(
                        "$mongoBatchOptionsQuickBenchmarkClass.${stratum.workload.methodName}"
                    ),
                    resultFileName = "$resultId.json",
                    humanFileName = "$resultId-human.txt",
                    requiredForGroupedReport = false,
                    formalRegressionSource = false,
                    requiresCleanSource = true,
                    requiredServices = mongoBatchAppendSuite.requiredServices,
                    runMetadata = linkedMapOf(
                        "experiment" to "quick-mongo-batch-options-engineering",
                        "protocolVersion" to mongoBatchOptionsQuickProtocolVersion,
                        "evidenceClass" to "quick-engineering",
                        "formalProtocol" to "false",
                        "currentBatchOptions" to mongoBatchQuickCurrentOptions,
                        "candidateBatchOptions" to mongoBatchQuickCandidateOptions,
                        "pairCountPerStratum" to mongoBatchOptionsQuickRounds.toString(),
                        "totalLegCount" to "24",
                        "orderSequence" to
                            mongoBatchOptionsQuickOrderSequence.joinToString(",") { it.id },
                        "workload" to stratum.workload.id,
                        "threads" to stratum.threads.toString(),
                        "round" to round.toString(),
                        "order" to order.id,
                        "position" to position.toString(),
                        "variant" to variant.id,
                        "batchOptions" to batchOptions,
                        "method" to stratum.workload.methodName,
                        "operationsPerInvocation" to stratum.workload.operationsPerInvocation.toString(),
                        "warmup" to "1x2s",
                        "measurement" to "1x3s",
                        "forks" to "1",
                        "mode" to "thrpt",
                        "profiler" to "gc",
                        "correctnessCheck" to "completion-count-and-iteration-document-count",
                    ),
                )
                val taskName =
                    "benchmarkQuickMongoBatchOptionsPaired" +
                        stratum.workload.id.replaceFirstChar(Char::uppercase) +
                        "T${stratum.threads}R$roundId${variant.taskSuffix}"
                val profile = quickMongoBatchOptionsPairedProfile.copy(
                    threads = listOf(stratum.threads),
                    parameters = mapOf("batchOptions" to batchOptions),
                )
                val taskSpec = BenchmarkTaskSpec(
                    taskName = taskName,
                    suite = suite,
                    profile = profile,
                    description =
                        "Runs quick Mongo batch-options engineering for ${stratum.workload.displayName}, " +
                            "threads=${stratum.threads}, round=$round/$mongoBatchOptionsQuickRounds " +
                            "(${order.id} position $position: ${variant.id} $batchOptions).",
                )
                val task = registerBenchmarkThreadTask(
                    taskName = taskName,
                    suite = suite,
                    profile = profile,
                    threads = stratum.threads,
                )
                add(
                    MongoBatchOptionsQuickTrialSpec(
                        stratum = stratum,
                        round = round,
                        order = order,
                        position = position,
                        variant = variant,
                        batchOptions = batchOptions,
                        taskSpec = taskSpec,
                        task = task,
                    )
                )
            }
        }
    }
}

mongoBatchOptionsQuickTrials.zipWithNext().forEach { (previous, current) ->
    current.task.configure {
        mustRunAfter(previous.task)
    }
}

fun parseMongoBatchOptionsQuickExperiment(
    parser: JsonSlurper = JsonSlurper(),
): MongoBatchOptionsQuickExperiment {
    val legs = mongoBatchOptionsQuickTrials.map { trial ->
        val report = parseBenchmarkGroup(
            parser = parser,
            group = benchmarkResultGroup(trial.taskSpec),
        )
        if (report.rows.size != 1 || report.manifests.size != 1) {
            throw GradleException(
                "Quick Mongo batch-options leg is missing or incomplete: ${trial.taskSpec.taskName}."
            )
        }
        val row = report.rows.single()
        val manifest = report.manifests.single()
        if (row.threads != trial.stratum.threads) {
            throw GradleException(
                "Quick Mongo batch-options thread mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.stratum.threads}, found ${row.threads}."
            )
        }
        if (row.mode != "thrpt" || !row.unit.equals("ops/s", ignoreCase = true)) {
            throw GradleException(
                "Quick Mongo batch-options leg must contain one thrpt ops/s row: ${trial.taskSpec.taskName}."
            )
        }
        if (!row.score.isFinite() || row.score <= 0.0) {
            throw GradleException(
                "Quick Mongo batch-options score must be positive and finite: ${trial.taskSpec.taskName}."
            )
        }
        val allocation = row.allocationBytesPerOp
        if (allocation == null || !allocation.isFinite() || allocation <= 0.0) {
            throw GradleException(
                "Quick Mongo batch-options allocation must be positive and finite: ${trial.taskSpec.taskName}."
            )
        }
        val actualMethod = benchmarkMethodName(row)
        if (actualMethod != trial.stratum.workload.methodName) {
            throw GradleException(
                "Quick Mongo batch-options method mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.stratum.workload.methodName}, found $actualMethod."
            )
        }
        val actualBatchOptions = row.parameters["batchOptions"]
        if (actualBatchOptions != trial.batchOptions) {
            throw GradleException(
                "Quick Mongo batch-options parameter mismatch for ${trial.taskSpec.taskName}: " +
                    "expected ${trial.batchOptions}, found $actualBatchOptions."
            )
        }
        val expectedTaskPath = mongoBatchPairedTaskPath(trial.taskSpec.taskName)
        if (manifest.taskPath != expectedTaskPath) {
            throw GradleException(
                "Quick Mongo batch-options task path mismatch: " +
                    "expected $expectedTaskPath, found ${manifest.taskPath}."
            )
        }
        if (manifest.runMetadata["formalProtocol"] != "false" ||
            manifest.runMetadata["batchOptions"] != trial.batchOptions
        ) {
            throw GradleException(
                "Quick Mongo batch-options manifest metadata mismatch: ${trial.taskSpec.taskName}."
            )
        }
        ParsedMongoBatchOptionsQuickLeg(trial = trial, row = row, manifest = manifest)
    }
    validateBenchmarkRunManifests(
        manifests = legs.map(ParsedMongoBatchOptionsQuickLeg::manifest),
        context = "Quick Mongo EventStore Batch Options Engineering",
        requireSameRunId = true,
    )
    if (legs.any { leg -> leg.manifest.sourceDirty }) {
        throw GradleException("Quick Mongo batch-options engineering requires sourceDirty=false.")
    }
    legs.zipWithNext().forEach { (previous, current) ->
        val previousCompletedAt = Instant.parse(previous.manifest.completedAt)
        val currentStartedAt = Instant.parse(current.manifest.startedAt)
        if (currentStartedAt.isBefore(previousCompletedAt)) {
            throw GradleException(
                "Quick Mongo batch-options legs did not execute in protocol order: " +
                    "${previous.trial.taskSpec.taskName} completed at $previousCompletedAt, " +
                    "${current.trial.taskSpec.taskName} started at $currentStartedAt."
            )
        }
    }
    validateNonOverlappingBenchmarkEvidenceWindows(
        windows = legs.map { leg ->
            BenchmarkEvidenceWindow(
                id = leg.trial.taskSpec.taskName,
                startedAt = Instant.parse(leg.manifest.startedAt),
                completedAt = Instant.parse(leg.manifest.completedAt),
            )
        },
        context = "Quick Mongo EventStore Batch Options Engineering",
    )
    val observations = legs.groupBy { leg ->
        Triple(leg.trial.stratum.workload, leg.trial.stratum.threads, leg.trial.round)
    }
        .map { (key, roundLegs) ->
            val (workload, threads, round) = key
            if (roundLegs.size != MongoBatchOptionsPairedVariant.entries.size) {
                throw GradleException(
                    "Quick Mongo batch-options round must contain current and candidate legs: " +
                        "${workload.id}/threads=$threads/round=$round."
                )
            }
            val expectedOrder = mongoBatchOptionsQuickOrder(round)
            val orderedLegs = roundLegs.sortedBy { it.trial.position }
            if (orderedLegs.map { it.trial.variant } != expectedOrder.variants() ||
                roundLegs.any { it.trial.order != expectedOrder }
            ) {
                throw GradleException(
                    "Quick Mongo batch-options round order mismatch for " +
                        "${workload.id}/threads=$threads/round=$round."
                )
            }
            val legsByVariant = roundLegs.associateBy { it.trial.variant }
            if (legsByVariant.size != MongoBatchOptionsPairedVariant.entries.size) {
                throw GradleException(
                    "Quick Mongo batch-options round contains duplicate variants: " +
                        "${workload.id}/threads=$threads/round=$round."
                )
            }
            val current = checkNotNull(legsByVariant[MongoBatchOptionsPairedVariant.CURRENT])
            val candidate = checkNotNull(legsByVariant[MongoBatchOptionsPairedVariant.FINALIST])
            MongoBatchOptionsPairedObservation(
                workload = workload,
                threads = threads,
                round = round,
                order = expectedOrder,
                currentScore = current.row.score,
                finalistScore = candidate.row.score,
                currentAllocation = checkNotNull(current.row.allocationBytesPerOp),
                finalistAllocation = checkNotNull(candidate.row.allocationBytesPerOp),
                unit = current.row.unit,
            )
        }
    val expectedStrata = mongoBatchOptionsQuickStrata.map { it.workload to it.threads }.toSet()
    val actualStrata = observations.map { it.workload to it.threads }.toSet()
    if (observations.size != mongoBatchOptionsQuickStrata.size * mongoBatchOptionsQuickRounds ||
        actualStrata != expectedStrata
    ) {
        throw GradleException(
            "Quick Mongo batch-options observations are incomplete: " +
                "expected strata=$expectedStrata and " +
                "${mongoBatchOptionsQuickStrata.size * mongoBatchOptionsQuickRounds} pairs, " +
                "found strata=$actualStrata and ${observations.size} pairs."
        )
    }
    return MongoBatchOptionsQuickExperiment(legs = legs, observations = observations)
}

val benchmarkQuickMongoBatchOptionsPaired =
    tasks.register("benchmarkQuickMongoBatchOptionsPaired") {
        description = "Runs the fixed 24-leg quick Mongo batch-options engineering comparison."
        group = "benchmark"
        dependsOn(mongoBatchOptionsQuickTrials.map(MongoBatchOptionsQuickTrialSpec::task))

        doLast {
            parseMongoBatchOptionsQuickExperiment()
        }
    }

val benchmarkMongoBatchAppendPairedE2E = tasks.register("benchmarkMongoBatchAppendPairedE2E") {
    description = "Runs the counterbalanced AB/BA paired Mongo EventStore append E2E confirmation."
    group = "benchmark"
    dependsOn(mongoBatchPairedTrials.map(MongoBatchPairedTrialSpec::task))

    doLast {
        parseMongoBatchPairedExperiment()
    }
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

fun ParsedBenchmarkResult.toBuildLogicRow(): BenchmarkResultRow {
    return BenchmarkResultRow(
        suiteId = suite.id,
        profile = profile,
        method = benchmarkMethodName(this),
        threads = threads,
        parameters = parameters,
        mode = mode,
        score = score,
        scoreError = scoreError,
        unit = unit,
        allocationBytesPerOp = allocationBytesPerOp,
    )
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

data class StorageBatchMethods(
    val single: String,
    val nativeBatch: String,
    val coordinatedBatch: String,
    val singleLabel: String,
    val nativeBatchLabel: String,
    val coordinatedBatchLabel: String,
)

data class StorageBatchComparisonKey(
    val threads: Int,
    val parameters: Map<String, String>,
)

fun storageBatchComparisonKey(row: ParsedBenchmarkResult): StorageBatchComparisonKey {
    return StorageBatchComparisonKey(
        threads = row.threads,
        parameters = row.parameters.toSortedMap(),
    )
}

fun storageBatchParameters(parameters: Map<String, String>): String {
    return parameters.entries.joinToString(", ") { (name, value) -> "$name=$value" }
        .ifBlank { "-" }
}

fun storageBatchRows(
    rows: List<ParsedBenchmarkResult>,
    methods: StorageBatchMethods,
    key: StorageBatchComparisonKey,
): Triple<ParsedBenchmarkResult, ParsedBenchmarkResult, ParsedBenchmarkResult> {
    val rowsByMethod = rows.groupBy(::benchmarkMethodName)
    fun requiredRow(method: String): ParsedBenchmarkResult {
        val matchingRows = rowsByMethod[method].orEmpty()
        if (matchingRows.size != 1) {
            throw GradleException(
                "Storage batch comparison requires exactly one $method row for $key, " +
                    "found ${matchingRows.size}."
            )
        }
        return matchingRows.single()
    }
    val single = requiredRow(methods.single)
    val nativeBatch = requiredRow(methods.nativeBatch)
    val coordinatedBatch = requiredRow(methods.coordinatedBatch)
    return Triple(single, nativeBatch, coordinatedBatch)
}

fun StringBuilder.appendStorageBatchMetricComparison(
    rows: List<ParsedBenchmarkResult>,
    methods: StorageBatchMethods,
    throughput: Boolean,
    operationName: String,
) {
    val metricRows = if (throughput) {
        rows.filter { it.unit.equals("ops/s", ignoreCase = true) }
    } else {
        rows.filter { it.mode == "avgt" }
    }
    val rowsByKey = metricRows.groupBy(::storageBatchComparisonKey)
    val metricName = if (throughput) "Throughput" else "Amortized time per $operationName"
    appendLine("### $metricName")
    appendLine()
    appendLine(
        "| JMH Threads | Parameters | ${methods.singleLabel} | ${methods.nativeBatchLabel} | " +
            "${methods.coordinatedBatchLabel} | Native vs single | Coordinated vs single | " +
            "Coordinated vs native |"
    )
    appendLine("|-------------|------------|--------|--------|--------|------------------|-----------------------|-----------------------|")
    rowsByKey.entries
        .sortedWith(
            compareBy(
                { it.key.threads },
                { storageBatchParameters(it.key.parameters) },
            )
        )
        .forEach { (key, comparisonRows) ->
            val (single, nativeBatch, coordinatedBatch) =
                storageBatchRows(comparisonRows, methods, key)
            check(single.unit == nativeBatch.unit && single.unit == coordinatedBatch.unit) {
                "Storage batch comparison units do not match for $key."
            }
            val scale = benchmarkMetricScale(
                listOf(single.score, nativeBatch.score, coordinatedBatch.score),
                single.unit,
            )
            val singleScore = formatScaledBenchmarkScore(single.score, single.scoreError, scale)
            val nativeBatchScore = formatScaledBenchmarkScore(
                nativeBatch.score,
                nativeBatch.scoreError,
                scale,
            )
            val coordinatedBatchScore = formatScaledBenchmarkScore(
                coordinatedBatch.score,
                coordinatedBatch.scoreError,
                scale,
            )
            val nativeVsSingle = if (throughput) {
                relativeChangePercent(single.score, nativeBatch.score)
            } else {
                reductionPercent(single.score, nativeBatch.score)
            }
            val coordinatedVsSingle = if (throughput) {
                relativeChangePercent(single.score, coordinatedBatch.score)
            } else {
                reductionPercent(single.score, coordinatedBatch.score)
            }
            val coordinatedVsNative = if (throughput) {
                relativeChangePercent(nativeBatch.score, coordinatedBatch.score)
            } else {
                reductionPercent(nativeBatch.score, coordinatedBatch.score)
            }
            appendLine(
                "| ${key.threads} | `${storageBatchParameters(key.parameters)}` | " +
                    "${singleScore.scoreWithUnit} | ${nativeBatchScore.scoreWithUnit} | " +
                    "${coordinatedBatchScore.scoreWithUnit} | ${formatSignedPercent(nativeVsSingle)} | " +
                    "${formatSignedPercent(coordinatedVsSingle)} | " +
                    "${formatSignedPercent(coordinatedVsNative)} |"
            )
        }
    appendLine()
    if (throughput) {
        appendLine("Higher throughput is better; positive changes are gains.")
    } else {
        appendLine(
            "Lower amortized time is better. JMH divides each 128-$operationName invocation's wall time by 128; " +
                "this is not an independent single-request response latency. The two `vs single` columns " +
                "and `Coordinated vs native` all report time reduction, so positive changes are gains."
        )
    }
    appendLine()
}

fun StringBuilder.appendStorageBatchComparisons(
    rows: List<ParsedBenchmarkResult>,
    methods: StorageBatchMethods,
    operationName: String = "event",
    workloadDescription: String = "independent event streams",
) {
    appendLine("## Three-Layer JMH Comparison")
    appendLine()
    appendLine(
        "Each invocation writes 128 $workloadDescription and JMH normalizes scores per $operationName. " +
            "The three layers distinguish single-request protocol cost, native storage bulk capability, " +
            "and the end-to-end coordinator path. The coordinated path includes batch formation and may " +
            "flush a partial batch on `maxDelay`, so its delta from native Bulk is not a pure coordinator " +
            "CPU-cost estimate."
    )
    appendLine()
    appendStorageBatchMetricComparison(
        rows,
        methods,
        throughput = true,
        operationName = operationName,
    )
    appendStorageBatchMetricComparison(
        rows,
        methods,
        throughput = false,
        operationName = operationName,
    )
}

fun StringBuilder.appendMongoBatchAppendComparisons(rows: List<ParsedBenchmarkResult>) {
    appendStorageBatchComparisons(
        rows = rows,
        methods = StorageBatchMethods(
            single = "appendWithInsertOne",
            nativeBatch = "appendWithNativeInsertMany",
            coordinatedBatch = "appendWithInsertManyBatch",
            singleLabel = "EventStore insertOne",
            nativeBatchLabel = "Native insertMany",
            coordinatedBatchLabel = "Coordinated batch",
        ),
    )
}

fun StringBuilder.appendMongoSnapshotBatchSaveComparisons(rows: List<ParsedBenchmarkResult>) {
    appendStorageBatchComparisons(
        rows = rows,
        methods = StorageBatchMethods(
            single = "saveWithUpdateOne",
            nativeBatch = "saveWithNativeBulkWrite",
            coordinatedBatch = "saveWithCoordinatedBatch",
            singleLabel = "SnapshotStore updateOne",
            nativeBatchLabel = "Native bulkWrite",
            coordinatedBatchLabel = "Coordinated batch",
        ),
        operationName = "snapshot",
        workloadDescription = "independent aggregate snapshots",
    )
}

fun StringBuilder.appendBatchRegenerateAggregateSnapshotMetric(
    rows: List<ParsedBenchmarkResult>,
    throughput: Boolean,
) {
    val metricRows = if (throughput) {
        rows.filter { it.unit.equals("ops/s", ignoreCase = true) }
    } else {
        rows.filter { it.mode == "avgt" }
    }
    val rowsByKey = metricRows.groupBy(::storageBatchComparisonKey)
    val metricName = if (throughput) "Throughput" else "Amortized time per aggregate"
    appendLine("### $metricName")
    appendLine()
    appendLine(
        "| JMH Threads | Parameters | Single SnapshotStore | Batched SnapshotStore | Batch vs single |"
    )
    appendLine("|-------------|------------|---------------------|----------------------|----------------|")
    rowsByKey.entries
        .sortedWith(compareBy({ it.key.threads }, { storageBatchParameters(it.key.parameters) }))
        .forEach { (key, comparisonRows) ->
            val rowsByMethod = comparisonRows.groupBy(::benchmarkMethodName)
            fun requiredRow(method: String): ParsedBenchmarkResult {
                val matchingRows = rowsByMethod[method].orEmpty()
                if (matchingRows.size != 1) {
                    throw GradleException(
                        "Batch snapshot regeneration comparison requires exactly one $method row for $key, " +
                            "found ${matchingRows.size}."
                    )
                }
                return matchingRows.single()
            }

            val single = requiredRow("regenerateWithSingleSnapshotStore")
            val batch = requiredRow("regenerateWithBatchSnapshotStore")
            check(single.unit == batch.unit) {
                "Batch snapshot regeneration comparison units do not match for $key."
            }
            val scale = benchmarkMetricScale(listOf(single.score, batch.score), single.unit)
            val singleScore = formatScaledBenchmarkScore(single.score, single.scoreError, scale)
            val batchScore = formatScaledBenchmarkScore(batch.score, batch.scoreError, scale)
            val change = if (throughput) {
                relativeChangePercent(single.score, batch.score)
            } else {
                reductionPercent(single.score, batch.score)
            }
            appendLine(
                "| ${key.threads} | `${storageBatchParameters(key.parameters)}` | " +
                    "${singleScore.scoreWithUnit} | ${batchScore.scoreWithUnit} | " +
                    "${formatSignedPercent(change)} |"
            )
        }
    appendLine()
    appendLine(
        if (throughput) {
            "Higher throughput is better; positive changes are gains."
        } else {
            "Lower amortized time is better; positive changes are reductions. " +
                "JMH normalizes each 128-aggregate invocation by 128."
        }
    )
    appendLine()
}

fun StringBuilder.appendBatchRegenerateAggregateSnapshotComparisons(
    rows: List<ParsedBenchmarkResult>,
) {
    appendLine("## Batch Regenerate Comparison")
    appendLine()
    appendLine(
        "Each invocation scans 128 aggregates from MongoEventStore, replays 10 events per aggregate, " +
            "and saves the rebuilt snapshots to ElasticsearchSnapshotStore. Both rows use the same " +
            "BatchExecutionPolicy; the batched row enables Elasticsearch snapshot save batching. " +
            "Each JMH worker uses a distinct 128-aggregate partition; iteration validation checks one " +
            "snapshot per seeded aggregate and version correctness."
    )
    appendLine()
    appendBatchRegenerateAggregateSnapshotMetric(rows, throughput = true)
    appendBatchRegenerateAggregateSnapshotMetric(rows, throughput = false)
}

val quickMongoSnapshotBatchSaveMethods = setOf(
    "saveWithUpdateOne",
    "saveWithNativeBulkWrite",
    "saveWithCoordinatedBatch",
)

val quickMongoSnapshotBatchSaveMatrix = BenchmarkMatrixSpec(
    name = "Quick Mongo SnapshotStore batch save",
    suiteId = mongoSnapshotBatchSaveSuite.id,
    profile = quickMongoSnapshotBatchSaveProfile.id,
    methods = quickMongoSnapshotBatchSaveMethods,
    threads = quickMongoSnapshotBatchSaveProfile.threads.toSet(),
    modes = quickMongoSnapshotBatchSaveProfile.benchmarkModes.toSet(),
    fixedParameters = quickMongoSnapshotBatchSaveProfile.parameters,
)

fun validateQuickMongoSnapshotBatchSaveRows(rows: List<ParsedBenchmarkResult>) {
    validateBenchmarkMatrix(
        quickMongoSnapshotBatchSaveMatrix,
        rows.map(ParsedBenchmarkResult::toBuildLogicRow),
    )
}

val quickBatchRegenerateAggregateSnapshotMethods = setOf(
    "regenerateWithSingleSnapshotStore",
    "regenerateWithBatchSnapshotStore",
)

val quickBatchRegenerateAggregateSnapshotMatrix = BenchmarkMatrixSpec(
    name = "Quick batch aggregate snapshot regeneration",
    suiteId = batchRegenerateAggregateSnapshotSuite.id,
    profile = quickBatchRegenerateAggregateSnapshotProfile.id,
    methods = quickBatchRegenerateAggregateSnapshotMethods,
    threads = quickBatchRegenerateAggregateSnapshotProfile.threads.toSet(),
    modes = quickBatchRegenerateAggregateSnapshotProfile.benchmarkModes.toSet(),
    fixedParameters = mapOf("batchOptions" to mongoBatchQuickCurrentOptions),
    parameterDimensions = mapOf(
        "laneCount" to quickBatchRegenerateAggregateSnapshotLaneCounts.map(Int::toString),
    ),
)

fun validateQuickBatchRegenerateAggregateSnapshotRows(rows: List<ParsedBenchmarkResult>) {
    validateBenchmarkMatrix(
        quickBatchRegenerateAggregateSnapshotMatrix,
        rows.map(ParsedBenchmarkResult::toBuildLogicRow),
    )
}

val quickMongoBatchCandidateE2EMethods = setOf(
    "appendWithInsertOne",
    "appendWithNativeInsertMany",
    "appendWithInsertManyBatch",
)

val quickMongoBatchCandidateE2EMatrix = BenchmarkMatrixSpec(
    name = "Quick Mongo candidate E2E",
    suiteId = quickMongoBatchCandidateE2ESuite.id,
    profile = quickMongoBatchCandidateE2EProfile.id,
    methods = quickMongoBatchCandidateE2EMethods,
    threads = quickMongoBatchCandidateE2EProfile.threads.toSet(),
    modes = quickMongoBatchCandidateE2EProfile.benchmarkModes.toSet(),
    fixedParameters = mapOf("batchOptions" to mongoBatchQuickCandidateOptions),
)

fun validateQuickMongoBatchCandidateE2ERows(rows: List<ParsedBenchmarkResult>) {
    validateBenchmarkMatrix(
        quickMongoBatchCandidateE2EMatrix,
        rows.map(ParsedBenchmarkResult::toBuildLogicRow),
    )
}

val quickMongoBatchCoordinatorConcurrencyLanes = listOf(1, 2, 4)
val quickMongoBatchCoordinatorConcurrencyMethod = "appendWithCoordinatorLanes"
val quickMongoBatchCoordinatorConcurrencyMatrix = BenchmarkMatrixSpec(
    name = "Quick Mongo coordinator concurrency",
    suiteId = quickMongoBatchCoordinatorConcurrencySuite.id,
    profile = quickMongoBatchCoordinatorConcurrencyProfile.id,
    methods = setOf(quickMongoBatchCoordinatorConcurrencyMethod),
    threads = quickMongoBatchCoordinatorConcurrencyProfile.threads.toSet(),
    modes = quickMongoBatchCoordinatorConcurrencyProfile.benchmarkModes.toSet(),
    fixedParameters = mapOf("batchOptions" to mongoBatchQuickCandidateOptions),
    parameterDimensions = mapOf(
        "coordinatorLanes" to quickMongoBatchCoordinatorConcurrencyLanes.map(Int::toString)
    ),
)

fun validateQuickMongoBatchCoordinatorConcurrencyRows(rows: List<ParsedBenchmarkResult>) {
    validateBenchmarkMatrix(
        quickMongoBatchCoordinatorConcurrencyMatrix,
        rows.map(ParsedBenchmarkResult::toBuildLogicRow),
    )
}

val quickMongoBatchCoordinatorConcurrencyComparison = BenchmarkParameterComparisonSpec(
    sectionTitle = "Coordinator Lane Comparison",
    introduction =
        "JMH uses four worker threads and 128 independent event streams per invocation. One production " +
            "MongoEventStore routes each aggregate key through its KeyedBatchCoordinator to one serial lane. " +
            "Different lanes may write concurrently. Because every stream has a distinct aggregate, this " +
            "workload exercises production key routing but leaves repeated-key ordering to functional tests.",
    parameterName = "coordinatorLanes",
    parameterLabel = "Coordinator lanes",
    parameterValues = quickMongoBatchCoordinatorConcurrencyLanes.map(Int::toString),
    baselineLabel = "lane 1",
    conclusion =
        "Higher throughput and positive reductions are better. Average time is JMH-normalized amortized " +
            "wall time per event, not an independent append response percentile. Additional lanes add " +
            "grouping and buffer-window state and may form smaller native insertMany requests, so this " +
            "experiment diagnoses the single-flight constraint but does not isolate coordinator CPU overhead.",
)

fun StringBuilder.appendQuickMongoBatchCoordinatorConcurrencyComparison(
    rows: List<ParsedBenchmarkResult>,
) {
    append(
        renderBenchmarkParameterComparison(
            quickMongoBatchCoordinatorConcurrencyComparison,
            rows.map(ParsedBenchmarkResult::toBuildLogicRow),
        )
    )
}

fun StringBuilder.appendElasticsearchBatchAppendComparisons(rows: List<ParsedBenchmarkResult>) {
    appendStorageBatchComparisons(
        rows = rows,
        methods = StorageBatchMethods(
            single = "appendWithSingleCreate",
            nativeBatch = "appendWithNativeBulkCreate",
            coordinatedBatch = "appendWithCoordinatedBulkCreate",
            singleLabel = "EventStore create",
            nativeBatchLabel = "Native Bulk create",
            coordinatedBatchLabel = "Coordinated Bulk",
        ),
    )
}

data class StorageBatchTuningKey(
    val method: String,
    val refresh: String,
    val threads: Int,
)

data class StorageBatchTuningRowKey(
    val method: String,
    val refresh: String,
    val threads: Int,
    val batchOptions: String,
    val mode: String,
)

data class StorageBatchTuningCandidateSummary(
    val batchOptions: String,
    val maxSize: Int,
    val maxDelayMicros: Long,
    val primaryRepresentativeRatio: Double,
    val worstSaturatedRatio: Double,
    val worstGuardRatio: Double,
    val worstAllocationRatio: Double,
    val preferredRefreshSaturatedRatio: Double,
    val eligible: Boolean,
)

val storageBatchTuningMethodOrder = listOf(
    "appendIsolated",
    "appendBurst32",
    "appendRepresentative128",
    "appendSaturated512",
)
val tuningReportTopCandidates = 5
val storageBatchTuningSaturatedMinimumRatio = 0.95
val storageBatchTuningGuardMinimumRatio = 0.90
val storageBatchTuningAllocationMaximumRatio = 1.10

fun storageBatchTuningRowKey(row: ParsedBenchmarkResult): StorageBatchTuningRowKey {
    return StorageBatchTuningRowKey(
        method = benchmarkMethodName(row),
        refresh = row.parameters["refresh"] ?: "-",
        threads = row.threads,
        batchOptions = row.parameters["batchOptions"]
            ?: throw GradleException("Storage batch tuning row is missing batchOptions: ${row.benchmark}"),
        mode = row.mode,
    )
}

fun validateStorageBatchTuningMatrix(
    rows: List<ParsedBenchmarkResult>,
    expectedOptions: List<String>,
    expectedRefreshValues: List<String>,
    expectedThreads: List<Int>,
    expectedModes: List<String>,
) {
    val actualRowsByKey = rows.groupBy(::storageBatchTuningRowKey)
    val duplicateKeys = actualRowsByKey.filterValues { keyedRows -> keyedRows.size != 1 }.keys
    if (duplicateKeys.isNotEmpty()) {
        throw GradleException(
            "Storage batch tuning matrix contains duplicate rows: " +
                duplicateKeys.take(10).joinToString(",")
        )
    }
    val expectedKeys = storageBatchTuningMethodOrder.flatMap { method ->
        expectedRefreshValues.flatMap { refresh ->
            expectedThreads.flatMap { threads ->
                expectedOptions.flatMap { batchOptions ->
                    expectedModes.map { mode ->
                        StorageBatchTuningRowKey(
                            method = method,
                            refresh = refresh,
                            threads = threads,
                            batchOptions = batchOptions,
                            mode = mode,
                        )
                    }
                }
            }
        }
    }.toSet()
    val actualKeys = actualRowsByKey.keys
    val missingKeys = expectedKeys - actualKeys
    val unexpectedKeys = actualKeys - expectedKeys
    if (missingKeys.isNotEmpty() || unexpectedKeys.isNotEmpty()) {
        throw GradleException(
            "Storage batch tuning matrix mismatch. Expected ${expectedKeys.size} rows, found ${rows.size}. " +
                "Missing: ${missingKeys.take(10)}. Unexpected: ${unexpectedKeys.take(10)}."
        )
    }
}

fun storageBatchTuningCandidateSummary(
    batchOptions: String,
    rowsByKey: Map<StorageBatchTuningKey, List<ParsedBenchmarkResult>>,
    currentOptions: String,
    preferredRefresh: String,
): StorageBatchTuningCandidateSummary {
    val optionMatch = checkNotNull(storageBatchTuningOptionFormat.matchEntire(batchOptions))
    val saturatedKeys = rowsByKey.keys.filter { key -> key.method == "appendSaturated512" }
    val guardKeys = rowsByKey.keys.filter { key -> key.method != "appendSaturated512" }
    val preferredRefreshKeys = saturatedKeys.filter { key -> key.refresh == preferredRefresh }
    val primaryRepresentativeKeys = guardKeys.filter { key ->
        key.method == "appendRepresentative128" && key.refresh == preferredRefresh
    }
    if (saturatedKeys.isEmpty() || guardKeys.isEmpty() || preferredRefreshKeys.isEmpty() ||
        primaryRepresentativeKeys.isEmpty()
    ) {
        throw GradleException(
            "Storage batch tuning candidate summary is missing saturated, guard, preferred refresh, " +
                "or primary representative rows."
        )
    }

    fun StorageBatchTuningKey.rowsByOptions(): Map<String, ParsedBenchmarkResult> {
        return rowsByKey.getValue(this).associateBy { row -> row.parameters.getValue("batchOptions") }
    }

    val worstSaturatedRatio = saturatedKeys.minOf { key ->
        val optionRows = key.rowsByOptions()
        optionRows.getValue(batchOptions).score / optionRows.values.maxOf(ParsedBenchmarkResult::score)
    }
    val worstGuardRatio = guardKeys.minOf { key ->
        val optionRows = key.rowsByOptions()
        optionRows.getValue(batchOptions).score / optionRows.getValue(currentOptions).score
    }
    val worstAllocationRatio = rowsByKey.keys.maxOf { key ->
        val optionRows = key.rowsByOptions()
        val candidateAllocation = optionRows.getValue(batchOptions).allocationBytesPerOp
            ?: throw GradleException("Storage batch tuning row is missing allocation: $key/$batchOptions")
        val currentAllocation = optionRows.getValue(currentOptions).allocationBytesPerOp
            ?: throw GradleException("Storage batch tuning row is missing allocation: $key/$currentOptions")
        if (currentAllocation <= 0.0) {
            throw GradleException("Storage batch tuning current allocation must be positive: $key")
        }
        candidateAllocation / currentAllocation
    }
    val preferredRefreshSaturatedRatio = preferredRefreshKeys.minOf { key ->
        val optionRows = key.rowsByOptions()
        optionRows.getValue(batchOptions).score / optionRows.values.maxOf(ParsedBenchmarkResult::score)
    }
    val primaryRepresentativeRatio = primaryRepresentativeKeys.minOf { key ->
        val optionRows = key.rowsByOptions()
        val currentScore = optionRows.getValue(currentOptions).score
        if (currentScore <= 0.0) {
            throw GradleException(
                "Storage batch tuning current representative throughput must be positive: $key"
            )
        }
        optionRows.getValue(batchOptions).score / currentScore
    }
    return StorageBatchTuningCandidateSummary(
        batchOptions = batchOptions,
        maxSize = optionMatch.groupValues[1].toInt(),
        maxDelayMicros = optionMatch.groupValues[2].toLong(),
        primaryRepresentativeRatio = primaryRepresentativeRatio,
        worstSaturatedRatio = worstSaturatedRatio,
        worstGuardRatio = worstGuardRatio,
        worstAllocationRatio = worstAllocationRatio,
        preferredRefreshSaturatedRatio = preferredRefreshSaturatedRatio,
        eligible =
            worstSaturatedRatio >= storageBatchTuningSaturatedMinimumRatio &&
                worstGuardRatio >= storageBatchTuningGuardMinimumRatio &&
                worstAllocationRatio <= storageBatchTuningAllocationMaximumRatio,
    )
}

fun storageBatchTuningCandidateDominates(
    candidate: StorageBatchTuningCandidateSummary,
    other: StorageBatchTuningCandidateSummary,
): Boolean {
    val noWorse =
        candidate.primaryRepresentativeRatio >= other.primaryRepresentativeRatio &&
            candidate.preferredRefreshSaturatedRatio >= other.preferredRefreshSaturatedRatio &&
            candidate.worstSaturatedRatio >= other.worstSaturatedRatio &&
            candidate.worstGuardRatio >= other.worstGuardRatio &&
            candidate.worstAllocationRatio <= other.worstAllocationRatio
    val strictlyBetter =
        candidate.primaryRepresentativeRatio > other.primaryRepresentativeRatio ||
            candidate.preferredRefreshSaturatedRatio > other.preferredRefreshSaturatedRatio ||
            candidate.worstSaturatedRatio > other.worstSaturatedRatio ||
            candidate.worstGuardRatio > other.worstGuardRatio ||
            candidate.worstAllocationRatio < other.worstAllocationRatio
    return noWorse && strictlyBetter
}

val storageBatchTuningCandidateComparator =
    compareByDescending<StorageBatchTuningCandidateSummary> { summary ->
        summary.primaryRepresentativeRatio
    }
        .thenByDescending { summary -> summary.preferredRefreshSaturatedRatio }
        .thenByDescending { summary -> summary.worstSaturatedRatio }
        .thenByDescending { summary -> summary.worstGuardRatio }
        .thenBy { summary -> summary.worstAllocationRatio }
        .thenBy { summary -> summary.maxSize }
        .thenBy { summary -> summary.maxDelayMicros }
        .thenBy { summary -> summary.batchOptions }

fun storageBatchTuningParetoCandidates(
    summaries: List<StorageBatchTuningCandidateSummary>,
    currentOptions: String,
): List<StorageBatchTuningCandidateSummary> {
    val eligibleCandidates = summaries.filter(StorageBatchTuningCandidateSummary::eligible)
    val eligibleChallengers = eligibleCandidates.filter { summary -> summary.batchOptions != currentOptions }
    return eligibleChallengers
        .filter { candidate ->
            eligibleCandidates.none { other ->
                other !== candidate && storageBatchTuningCandidateDominates(other, candidate)
            }
        }
        .sortedWith(storageBatchTuningCandidateComparator)
}

data class StorageBatchTuningFrontierEvidence(
    val suite: String,
    val currentOptions: String,
    val orderedFrontier: List<String>,
    val sourceCommit: String,
    val jmhJarSha256: String,
    val benchmarkHarnessSha256: String,
    val resultSha256: String,
    val manifestSha256: String,
    val evidenceSha256: String,
)

fun storageBatchTuningFrontierEvidencePayload(
    suite: String,
    currentOptions: String,
    orderedFrontier: List<String>,
    sourceCommit: String,
    jmhJarSha256: String,
    benchmarkHarnessSha256: String,
    resultSha256: String,
    manifestSha256: String,
): Map<String, Any> {
    return linkedMapOf(
        "schemaVersion" to 2,
        "suite" to suite,
        "currentOptions" to currentOptions,
        "orderedFrontier" to orderedFrontier,
        "sourceCommit" to sourceCommit,
        "jmhJarSha256" to jmhJarSha256,
        "benchmarkHarnessSha256" to benchmarkHarnessSha256,
        "resultSha256" to resultSha256,
        "manifestSha256" to manifestSha256,
    )
}

fun storageBatchTuningFrontierEvidence(
    suite: BenchmarkSuite,
    rows: List<ParsedBenchmarkResult>,
    expectedOptions: List<String>,
    currentOptions: String,
    preferredRefresh: String,
    manifests: List<ParsedBenchmarkRunManifest>,
    benchmarkHarnessSha256: String,
    manifestSha256: String,
): StorageBatchTuningFrontierEvidence {
    val manifest = manifests.singleOrNull()
        ?: throw GradleException(
            "Storage batch frontier evidence requires exactly one scan manifest for ${suite.id}."
        )
    if (manifest.sourceDirty) {
        throw GradleException("Storage batch frontier evidence requires sourceDirty=false for ${suite.id}.")
    }
    val rowsByKey = rows
        .filter { row -> row.unit.equals("ops/s", ignoreCase = true) }
        .groupBy { row ->
            StorageBatchTuningKey(
                method = benchmarkMethodName(row),
                refresh = row.parameters["refresh"] ?: "-",
                threads = row.threads,
            )
        }
    val summaries = expectedOptions.map { batchOptions ->
        storageBatchTuningCandidateSummary(
            batchOptions = batchOptions,
            rowsByKey = rowsByKey,
            currentOptions = currentOptions,
            preferredRefresh = preferredRefresh,
        )
    }
    val orderedFrontier = storageBatchTuningParetoCandidates(summaries, currentOptions)
        .map(StorageBatchTuningCandidateSummary::batchOptions)
    val payload = storageBatchTuningFrontierEvidencePayload(
        suite = suite.id,
        currentOptions = currentOptions,
        orderedFrontier = orderedFrontier,
        sourceCommit = manifest.sourceCommit,
        jmhJarSha256 = manifest.jmhJarSha256,
        benchmarkHarnessSha256 = benchmarkHarnessSha256,
        resultSha256 = manifest.resultSha256,
        manifestSha256 = manifestSha256,
    )
    return StorageBatchTuningFrontierEvidence(
        suite = suite.id,
        currentOptions = currentOptions,
        orderedFrontier = orderedFrontier,
        sourceCommit = manifest.sourceCommit,
        jmhJarSha256 = manifest.jmhJarSha256,
        benchmarkHarnessSha256 = benchmarkHarnessSha256,
        resultSha256 = manifest.resultSha256,
        manifestSha256 = manifestSha256,
        evidenceSha256 = sha256Text(JsonOutput.toJson(payload)),
    )
}

fun StorageBatchTuningFrontierEvidence.toRunSpec(): Map<String, Any> {
    val payload = storageBatchTuningFrontierEvidencePayload(
        suite = suite,
        currentOptions = currentOptions,
        orderedFrontier = orderedFrontier,
        sourceCommit = sourceCommit,
        jmhJarSha256 = jmhJarSha256,
        benchmarkHarnessSha256 = benchmarkHarnessSha256,
        resultSha256 = resultSha256,
        manifestSha256 = manifestSha256,
    )
    return LinkedHashMap(payload).also { evidence ->
        evidence["evidenceSha256"] = evidenceSha256
    }
}

fun parseStorageBatchTuningFrontierEvidence(
    evidenceFile: File,
): StorageBatchTuningFrontierEvidence {
    if (!evidenceFile.isFile) {
        throw GradleException(
            "Storage batch frontier evidence is missing: ${evidenceFile.absolutePath}. " +
                "Generate and commit the matching tuning report before confirmation."
        )
    }
    val source = evidenceFile.absolutePath
    val parsed = JsonSlurper().parseText(evidenceFile.readText()) as? Map<*, *>
        ?: throw GradleException("Storage batch frontier evidence must be a JSON object: $source")
    requireManifestValue(manifestInt(parsed, "schemaVersion", source), 2, "schemaVersion", source)
    val orderedFrontier = manifestStringList(parsed, "orderedFrontier", source)
    if (orderedFrontier.distinct().size != orderedFrontier.size) {
        throw GradleException("Storage batch frontier evidence contains duplicate options: $source")
    }
    val evidence = StorageBatchTuningFrontierEvidence(
        suite = manifestString(parsed, "suite", source),
        currentOptions = manifestString(parsed, "currentOptions", source),
        orderedFrontier = orderedFrontier,
        sourceCommit = manifestString(parsed, "sourceCommit", source),
        jmhJarSha256 = manifestString(parsed, "jmhJarSha256", source),
        benchmarkHarnessSha256 = manifestString(parsed, "benchmarkHarnessSha256", source),
        resultSha256 = manifestString(parsed, "resultSha256", source),
        manifestSha256 = manifestString(parsed, "manifestSha256", source),
        evidenceSha256 = manifestString(parsed, "evidenceSha256", source),
    )
    val expectedDigest = sha256Text(
        JsonOutput.toJson(
            storageBatchTuningFrontierEvidencePayload(
                suite = evidence.suite,
                currentOptions = evidence.currentOptions,
                orderedFrontier = evidence.orderedFrontier,
                sourceCommit = evidence.sourceCommit,
                jmhJarSha256 = evidence.jmhJarSha256,
                benchmarkHarnessSha256 = evidence.benchmarkHarnessSha256,
                resultSha256 = evidence.resultSha256,
                manifestSha256 = evidence.manifestSha256,
            )
        )
    )
    requireManifestValue(
        evidence.evidenceSha256,
        expectedDigest,
        "evidenceSha256",
        source,
    )
    return evidence
}

fun storageBatchTuningFrontierEvidenceFile(suite: BenchmarkSuite): File {
    return when (suite.id) {
        mongoBatchOptionsTuningSuite.id -> mongoBatchOptionsFrontierEvidenceFile.asFile
        elasticsearchBatchOptionsTuningSuite.id -> elasticsearchBatchOptionsFrontierEvidenceFile.asFile
        else -> throw GradleException("Unsupported storage batch tuning suite: ${suite.id}")
    }
}

data class ValidatedStorageBatchTuningScreening(
    val evidence: StorageBatchTuningFrontierEvidence,
    val report: BenchmarkGroupReport,
)

fun storageBatchTuningScanTaskSpec(suite: BenchmarkSuite): BenchmarkTaskSpec {
    return when (suite.id) {
        mongoBatchOptionsTuningSuite.id -> mongoBatchOptionsTuningTaskSpec
        elasticsearchBatchOptionsTuningSuite.id -> elasticsearchBatchOptionsTuningTaskSpec
        else -> throw GradleException("Unsupported storage batch tuning suite: ${suite.id}")
    }
}

fun storageBatchTuningPreferredRefresh(suite: BenchmarkSuite): String {
    return when (suite.id) {
        mongoBatchOptionsTuningSuite.id -> "-"
        elasticsearchBatchOptionsTuningSuite.id -> "True"
        else -> throw GradleException("Unsupported storage batch tuning suite: ${suite.id}")
    }
}

fun storageBatchTuningRefreshValues(suite: BenchmarkSuite): List<String> {
    return when (suite.id) {
        mongoBatchOptionsTuningSuite.id -> listOf("-")
        elasticsearchBatchOptionsTuningSuite.id -> listOf("False", "True")
        else -> throw GradleException("Unsupported storage batch tuning suite: ${suite.id}")
    }
}

fun storageBatchTuningReportFiles(suite: BenchmarkSuite): Set<String> {
    val reportFiles = when (suite.id) {
        mongoBatchOptionsTuningSuite.id ->
            listOf(mongoBatchOptionsTuningReportFile.asFile, mongoBatchOptionsFrontierEvidenceFile.asFile)

        elasticsearchBatchOptionsTuningSuite.id ->
            listOf(
                elasticsearchBatchOptionsTuningReportFile.asFile,
                elasticsearchBatchOptionsFrontierEvidenceFile.asFile,
            )

        else -> throw GradleException("Unsupported storage batch tuning suite: ${suite.id}")
    }
    return reportFiles.mapTo(linkedSetOf(), ::benchmarkReportPath)
}

fun requireStorageBatchTuningSourceTransition(
    sourceIsHeadAncestor: Boolean,
    changedPaths: Set<String>,
    allowedPaths: Set<String>,
    source: String,
) {
    if (!sourceIsHeadAncestor) {
        throw GradleException("Storage batch screening source commit is not an ancestor of HEAD: $source")
    }
    val disallowedPaths = changedPaths - allowedPaths
    if (disallowedPaths.isNotEmpty()) {
        throw GradleException(
            "Only generated storage batch evidence may change after screening; disallowed paths in " +
                "$source: ${disallowedPaths.sorted()}"
        )
    }
}

fun parseGitNullSeparatedPaths(output: String): Set<String> {
    return output.split('\u0000').filter(String::isNotBlank).toSet()
}

fun requireStorageBatchTuningSourceTransition(
    sourceCommit: String,
    targetCommit: String,
    allowedPaths: Set<String>,
    context: String,
) {
    val commitFormat = Regex("[0-9a-fA-F]{40,64}")
    if (!commitFormat.matches(sourceCommit) || !commitFormat.matches(targetCommit)) {
        throw GradleException("Storage batch source transition has an invalid commit ID: $context")
    }
    val gitRoot = rootProject.projectDir.absolutePath
    val ancestor = runCommand(
        listOf(
            "git",
            "-C",
            gitRoot,
            "merge-base",
            "--is-ancestor",
            sourceCommit,
            targetCommit,
        )
    )
    if (ancestor.exitCode !in setOf(0, 1)) {
        throw GradleException(
            "Unable to verify storage batch source ancestry $sourceCommit..$targetCommit: " +
                ancestor.output
        )
    }
    val diff = runCommand(
        listOf(
            "git",
            "-C",
            gitRoot,
            "diff",
            "--no-renames",
            "--name-only",
            "-z",
            "$sourceCommit..$targetCommit",
            "--",
        ),
        trimOutput = false,
    )
    if (diff.exitCode != 0) {
        throw GradleException("Unable to inspect storage batch transition paths: ${diff.output}")
    }
    requireStorageBatchTuningSourceTransition(
        sourceIsHeadAncestor = ancestor.exitCode == 0,
        changedPaths = parseGitNullSeparatedPaths(diff.output),
        allowedPaths = allowedPaths,
        source = context,
    )
}

fun currentBenchmarkGitHead(): String {
    val currentHead = runCommand(
        listOf("git", "-C", rootProject.projectDir.absolutePath, "rev-parse", "HEAD")
    )
    if (currentHead.exitCode != 0 || currentHead.output.isBlank()) {
        throw GradleException("Unable to resolve benchmark confirmation HEAD: ${currentHead.output}")
    }
    return currentHead.output
}

fun requireStorageBatchTuningSourceTransition(
    evidence: StorageBatchTuningFrontierEvidence,
    suite: BenchmarkSuite,
    additionalAllowedPaths: Set<String> = emptySet(),
) {
    val currentHead = currentBenchmarkGitHead()
    requireStorageBatchTuningSourceTransition(
        sourceCommit = evidence.sourceCommit,
        targetCommit = currentHead,
        allowedPaths = storageBatchTuningReportFiles(suite) + additionalAllowedPaths,
        context = "${evidence.sourceCommit}..$currentHead",
    )
}

fun requireStorageBatchTuningConfirmationSourceTransition(
    evidence: StorageBatchTuningFrontierEvidence,
    suite: BenchmarkSuite,
    confirmationManifests: List<ParsedBenchmarkRunManifest>,
    allowedAfterConfirmation: Set<String> = emptySet(),
) {
    val confirmationSource = confirmationManifests
        .map(ParsedBenchmarkRunManifest::sourceCommit)
        .distinct()
        .singleOrNull()
        ?: throw GradleException("Storage batch confirmation manifests must bind one source commit.")
    requireStorageBatchTuningSourceTransition(
        sourceCommit = evidence.sourceCommit,
        targetCommit = confirmationSource,
        allowedPaths = storageBatchTuningReportFiles(suite),
        context = "${evidence.sourceCommit}..$confirmationSource",
    )
    val currentHead = currentBenchmarkGitHead()
    requireStorageBatchTuningSourceTransition(
        sourceCommit = confirmationSource,
        targetCommit = currentHead,
        allowedPaths = allowedAfterConfirmation,
        context = "$confirmationSource..$currentHead",
    )
}

fun requireStorageBatchTuningArtifactHashes(
    evidence: StorageBatchTuningFrontierEvidence,
    resultFile: File,
    manifestFile: File,
    source: String,
) {
    if (!resultFile.isFile || !manifestFile.isFile) {
        throw GradleException(
            "Storage batch confirmation requires retained raw screening result and manifest: " +
                "${resultFile.absolutePath}, ${manifestFile.absolutePath}"
        )
    }
    requireManifestValue(
        fileSha256(resultFile),
        evidence.resultSha256,
        "screening resultSha256",
        source,
    )
    requireManifestValue(
        fileSha256(manifestFile),
        evidence.manifestSha256,
        "screening manifestSha256",
        source,
    )
}

fun requireStorageBatchTuningScanParameters(
    parameters: Map<String, String>,
    source: String,
) {
    requireManifestValue(
        parameters.keys,
        setOf("batchOptions"),
        "screening runSpec.parameters keys",
        source,
    )
}

fun validateStorageBatchTuningScreeningEvidence(
    suite: BenchmarkSuite,
    currentJmhJarSha256: String? = null,
    additionalAllowedPaths: Set<String> = emptySet(),
): ValidatedStorageBatchTuningScreening {
    val evidenceFile = storageBatchTuningFrontierEvidenceFile(suite)
    val evidence = parseStorageBatchTuningFrontierEvidence(evidenceFile)
    val configuredTaskSpec = storageBatchTuningScanTaskSpec(suite)
    val configuredGroup = benchmarkResultGroup(configuredTaskSpec)
    val resultSource = configuredGroup.resultFiles.singleOrNull()
        ?: throw GradleException(
            "Storage batch screening preflight requires exactly one raw result and manifest for ${suite.id}."
        )
    val resultFile = resultSource.resultFile.get().asFile
    val manifestFile = resultSource.manifestFile.get().asFile
    requireStorageBatchTuningArtifactHashes(
        evidence = evidence,
        resultFile = resultFile,
        manifestFile = manifestFile,
        source = evidenceFile.absolutePath,
    )
    val rawManifest = JsonSlurper().parseText(manifestFile.readText()) as? Map<*, *>
        ?: throw GradleException("Storage batch screening manifest must be a JSON object.")
    val rawRunSpec = manifestMap(rawManifest, "runSpec", manifestFile.absolutePath)
    val rawParameters = manifestStringMap(rawRunSpec, "parameters", manifestFile.absolutePath)
    requireStorageBatchTuningScanParameters(rawParameters, manifestFile.absolutePath)
    val scanTaskSpec = configuredTaskSpec.copy(
        profile = configuredTaskSpec.profile.copy(parameters = rawParameters)
    )
    val resultGroup = benchmarkResultGroup(scanTaskSpec)
    val report = parseBenchmarkGroup(JsonSlurper(), resultGroup)
    val expectedOptions = storageBatchTuningOptions(resultGroup.profile)
    validateStorageBatchTuningMatrix(
        rows = report.rows,
        expectedOptions = expectedOptions,
        expectedRefreshValues = storageBatchTuningRefreshValues(suite),
        expectedThreads = resultGroup.profile.threads,
        expectedModes = resultGroup.profile.benchmarkModes,
    )
    val recomputedEvidence = storageBatchTuningFrontierEvidence(
        suite = suite,
        rows = report.rows,
        expectedOptions = expectedOptions,
        currentOptions = currentStorageBatchOptions(suite),
        preferredRefresh = storageBatchTuningPreferredRefresh(suite),
        manifests = report.manifests,
        benchmarkHarnessSha256 = benchmarkHarnessSha256(),
        manifestSha256 = fileSha256(manifestFile),
    )
    requireManifestValue(
        recomputedEvidence,
        evidence,
        "recomputed screening frontier evidence",
        evidenceFile.absolutePath,
    )
    currentJmhJarSha256?.let { currentSha256 ->
        requireStorageBatchTuningEvidenceCompatibility(
            evidence = evidence,
            currentJmhJarSha256 = currentSha256,
            currentBenchmarkHarnessSha256 = recomputedEvidence.benchmarkHarnessSha256,
            source = evidenceFile.absolutePath,
        )
    }
    requireStorageBatchTuningSourceTransition(
        evidence = evidence,
        suite = suite,
        additionalAllowedPaths = additionalAllowedPaths,
    )
    return ValidatedStorageBatchTuningScreening(evidence = evidence, report = report)
}

fun requireStorageBatchTuningConfirmationOptions(
    options: List<String>,
    suiteId: String,
    currentOptions: String,
    evidence: StorageBatchTuningFrontierEvidence,
    source: String,
) {
    requireManifestValue(evidence.suite, suiteId, "suite", source)
    requireManifestValue(
        evidence.currentOptions,
        currentOptions,
        "currentOptions",
        source,
    )
    val expectedOptions = listOf(currentOptions) + evidence.orderedFrontier
    if (options != expectedOptions) {
        throw GradleException(
            "Storage batch confirmation options must exactly match current plus the ordered frontier " +
                "bound by $source (${evidence.evidenceSha256}). " +
                "Expected $expectedOptions, found $options."
        )
    }
}

fun requireStorageBatchTuningEvidenceCompatibility(
    evidence: StorageBatchTuningFrontierEvidence,
    currentJmhJarSha256: String,
    currentBenchmarkHarnessSha256: String,
    source: String,
) {
    requireManifestValue(
        currentJmhJarSha256,
        evidence.jmhJarSha256,
        "current jmhJarSha256",
        source,
    )
    requireManifestValue(
        currentBenchmarkHarnessSha256,
        evidence.benchmarkHarnessSha256,
        "current benchmarkHarnessSha256",
        source,
    )
}

fun requireStorageBatchTuningConfirmationManifestCompatibility(
    evidence: StorageBatchTuningFrontierEvidence,
    screeningManifest: ParsedBenchmarkRunManifest,
    manifests: List<ParsedBenchmarkRunManifest>,
    source: String,
) {
    val confirmationJmhJarSha256 = manifests
        .map(ParsedBenchmarkRunManifest::jmhJarSha256)
        .distinct()
        .singleOrNull()
        ?: throw GradleException(
            "Storage batch confirmation manifests must bind one JMH jar: $source"
        )
    requireStorageBatchTuningEvidenceCompatibility(
        evidence = evidence,
        currentJmhJarSha256 = confirmationJmhJarSha256,
        currentBenchmarkHarnessSha256 = benchmarkHarnessSha256(),
        source = source,
    )
    requireBenchmarkManifestEnvironmentCompatibility(
        screening = screeningManifest,
        confirmations = manifests,
        context = source,
    )
}

fun StringBuilder.appendStorageBatchTuningSummary(
    rows: List<ParsedBenchmarkResult>,
    expectedOptions: List<String>,
    expectedRefreshValues: List<String>,
    expectedThreads: List<Int>,
    expectedModes: List<String>,
    currentOptions: String,
    preferredRefresh: String,
    confirmationTaskPath: String? = null,
    confirmationReportTaskPath: String? = null,
    confirmationPropertyName: String? = null,
    boundConfirmationEvidence: StorageBatchTuningFrontierEvidence? = null,
    campaignStopped: Boolean = false,
) {
    validateStorageBatchTuningMatrix(
        rows = rows,
        expectedOptions = expectedOptions,
        expectedRefreshValues = expectedRefreshValues,
        expectedThreads = expectedThreads,
        expectedModes = expectedModes,
    )
    if (currentOptions !in expectedOptions) {
        throw GradleException("Storage batch tuning matrix must include current options $currentOptions.")
    }
    if (campaignStopped) {
        appendLine("## Campaign Status")
        appendLine()
        appendLine(
            "This full-candidate campaign has been stopped. Its measurements and historical classifier " +
                "output are retained as exploratory evidence only; the remaining frontier must not be run " +
                "under the old protocol, and this report cannot establish a production default."
        )
        appendLine()
    }
    val throughputRows = rows.filter { it.unit.equals("ops/s", ignoreCase = true) }
    val rowsByKey = throughputRows.groupBy { row ->
        StorageBatchTuningKey(
            method = benchmarkMethodName(row),
            refresh = row.parameters["refresh"] ?: "-",
            threads = row.threads,
        )
    }

    appendLine("## Throughput Screening")
    appendLine()
    appendLine(
        if (campaignStopped) {
            "The table keeps the five highest historical point estimates per workload plus current " +
                "`$currentOptions`. These stopped-campaign scan results are exploratory and do not select " +
                "a production default."
        } else {
            "The table keeps the five highest point estimates per workload plus the current " +
                "`$currentOptions` default. Scan results select confirmation candidates; they do not by themselves " +
                "establish a new default."
        }
    )
    appendLine()
    appendLine(
        "| Workload | Refresh | Threads | Batch options | Throughput | Error | vs best | vs current | Allocation |"
    )
    appendLine("|----------|---------|---------|---------------|------------|-------|---------|------------|------------|")
    rowsByKey.entries
        .sortedWith(
            compareBy(
                { storageBatchTuningMethodOrder.indexOf(it.key.method) },
                { it.key.refresh },
                { it.key.threads },
            )
        )
        .forEach { (key, workloadRows) ->
            val rowsByOptions = workloadRows.groupBy { row ->
                row.parameters["batchOptions"]
                    ?: throw GradleException("Storage batch tuning row is missing batchOptions: ${row.benchmark}")
            }
            rowsByOptions.forEach { (options, optionRows) ->
                if (optionRows.size != 1) {
                    throw GradleException(
                        "Storage batch tuning requires one throughput row for $key/$options, " +
                            "found ${optionRows.size}."
                    )
                }
            }
            val ranked = workloadRows.sortedByDescending { it.score }
            val best = ranked.first()
            val current = rowsByOptions[currentOptions]?.single()
                ?: throw GradleException(
                    "Storage batch tuning is missing current options $currentOptions for $key."
                )
            val selected = (ranked.take(tuningReportTopCandidates) + current)
                .distinctBy { it.parameters.getValue("batchOptions") }
            val scale = benchmarkMetricScale(workloadRows.map { it.score }, best.unit)
            selected.forEach { row ->
                val formatted = formatScaledBenchmarkScore(row.score, row.scoreError, scale)
                appendLine(
                    "| `${key.method}` | `${key.refresh}` | ${key.threads} | " +
                        "`${row.parameters.getValue("batchOptions")}` | " +
                        "${formatted.scoreWithUnit} | ${formatted.errorWithUnit} | " +
                        "${formatSignedPercent(relativeChangePercent(best.score, row.score))} | " +
                        "${formatSignedPercent(relativeChangePercent(current.score, row.score))} | " +
                        "${formatAllocationBytes(row.allocationBytesPerOp)} |"
                )
            }
        }
    appendLine()
    appendLine(
        if (campaignStopped) {
            "Higher throughput is better. `vs best` and `vs current` use historical point estimates; " +
                "overlapping JMH error intervals remain uncertain and do not authorize continuing the " +
                "stopped protocol."
        } else {
            "Higher throughput is better. `vs best` and `vs current` use point estimates; overlapping JMH " +
                "error intervals remain inconclusive and require the multiple-fork confirmation task."
        }
    )
    appendLine()

    val candidateSummaries = expectedOptions.map { batchOptions ->
        storageBatchTuningCandidateSummary(
            batchOptions = batchOptions,
            rowsByKey = rowsByKey,
            currentOptions = currentOptions,
            preferredRefresh = preferredRefresh,
        )
    }.sortedWith(
        compareByDescending<StorageBatchTuningCandidateSummary> { summary -> summary.eligible }
            .then(storageBatchTuningCandidateComparator)
    )
    val paretoCandidates = storageBatchTuningParetoCandidates(
        summaries = candidateSummaries,
        currentOptions = currentOptions,
    )
    val confirmationOptions = boundConfirmationEvidence?.orderedFrontier
        ?: paretoCandidates.map(StorageBatchTuningCandidateSummary::batchOptions)
    val paretoOptions = confirmationOptions.toSet()
    appendLine("## Cross-workload Candidate Gate")
    appendLine()
    appendLine(
        if (campaignStopped) {
            "The historical gate marked a candidate eligible only when every saturated stratum was within 5% " +
                "of that stratum's best point estimate, every isolated/burst/representative stratum stayed " +
                "within 10% of current throughput, and allocation stayed within 10% of current. These labels " +
                "are retained for exploration and do not schedule further confirmation."
        } else {
            "A screening candidate is eligible only when every saturated stratum is within 5% of that " +
                "stratum's best point estimate, every isolated/burst/representative stratum stays within 10% " +
                "of current throughput, and allocation stays within 10% of current. All refresh and thread " +
                "strata participate. These point-estimate gates shortlist candidates; confirmation remains required."
        }
    )
    appendLine()
    appendLine(
        "| Batch options | Primary representative vs current | Preferred refresh saturated vs best | Worst saturated vs best | " +
            "Worst guard vs current | Worst allocation vs current | Screening status |"
    )
    appendLine(
        "|---------------|-----------------------------------|--------------------------------------|-------------------------|" +
            "------------------------|-----------------------------|------------------|"
    )
    candidateSummaries.forEach { summary ->
        val status = when {
            summary.batchOptions == currentOptions -> "CURRENT"
            summary.batchOptions in paretoOptions ->
                if (campaignStopped) "HISTORICAL_FRONTIER" else "CONFIRM"
            summary.eligible -> "ELIGIBLE_DOMINATED"
            else -> "REJECT"
        }
        appendLine(
            "| `${summary.batchOptions}` | " +
                "${formatSignedPercent((summary.primaryRepresentativeRatio - 1.0) * 100.0)} | " +
                "${formatSignedPercent((summary.preferredRefreshSaturatedRatio - 1.0) * 100.0)} | " +
                "${formatSignedPercent((summary.worstSaturatedRatio - 1.0) * 100.0)} | " +
                "${formatSignedPercent((summary.worstGuardRatio - 1.0) * 100.0)} | " +
                "${formatSignedPercent((summary.worstAllocationRatio - 1.0) * 100.0)} | $status |"
        )
    }
    appendLine()
    appendLine(
        if (campaignStopped && boundConfirmationEvidence == null) {
            "### Historical Deterministic Pareto Set"
        } else if (campaignStopped) {
            "### Historical Manifest-bound Candidate Set"
        } else if (boundConfirmationEvidence == null) {
            "### Deterministic Pareto Confirmation Set"
        } else {
            "### Manifest-bound Confirmation Set"
        }
    )
    appendLine()
    if (boundConfirmationEvidence == null) {
        appendLine(
            "The report removes eligible challengers only when another eligible challenger is no worse on " +
                "primary representative throughput, preferred-refresh saturated throughput, worst saturated " +
                "throughput, worst guard throughput, and allocation, and is strictly better on at least one. " +
                "The full non-dominated frontier is retained. Display order is deterministic by those metrics, " +
                "then smaller `maxSize`, shorter `maxDelay`, and encoded option."
        )
    } else {
        appendLine(
            if (campaignStopped) {
                "This historical candidate set is not recomputed from the supplied rows. It remains bound to " +
                    "exploratory screening evidence `${boundConfirmationEvidence.evidenceSha256}` from source " +
                    "commit `${boundConfirmationEvidence.sourceCommit}` and result " +
                    "`${boundConfirmationEvidence.resultSha256}`."
            } else {
                "This confirmation set is not recomputed from the supplied rows. It is bound to screening " +
                    "evidence `${boundConfirmationEvidence.evidenceSha256}` from source commit " +
                    "`${boundConfirmationEvidence.sourceCommit}` and result " +
                    "`${boundConfirmationEvidence.resultSha256}`."
            }
        )
    }
    appendLine()
    if (confirmationOptions.isEmpty()) {
        appendLine(
            "No challenger survived the gate. The scan supports retaining the current option, but it does " +
                "not establish a global optimum."
        )
    } else {
        appendLine(
            if (campaignStopped) {
                "- **Historical frontier**: "
            } else {
                "- **Required frontier**: "
            } +
                confirmationOptions.joinToString(", ") { candidate -> "`$candidate`" }
        )
        if (campaignStopped) {
            appendLine(
                "- **Campaign status**: stopped before full closure. Do not continue the remaining candidates " +
                    "or interpret this historical set as a pre-registered selection protocol."
            )
        } else {
            appendLine(
                "- **Closure rule**: run one multiple-fork confirmation over the complete ordered frontier, then " +
                    "run paired confirmation for every `INCONCLUSIVE` challenger. Only after every challenger has " +
                    "a final `PASS` or elimination verdict may the highest-ranked `PASS` be selected; if none pass, " +
                    "retain current. Until then, the default decision remains open."
            )
        }
        val confirmationCommandParts = listOf(
            confirmationTaskPath,
            confirmationReportTaskPath,
            confirmationPropertyName,
        )
        if (confirmationCommandParts.any { it == null } && confirmationCommandParts.any { it != null }) {
            throw GradleException(
                "Storage batch tuning confirmation benchmark task, report task, and property name " +
                    "must be supplied together."
            )
        }
        if (boundConfirmationEvidence != null && confirmationCommandParts.any { it != null }) {
            throw GradleException(
                "Manifest-bound confirmation output cannot emit a screening confirmation command."
            )
        }
        if (!campaignStopped &&
            confirmationTaskPath != null &&
            confirmationReportTaskPath != null &&
            confirmationPropertyName != null
        ) {
            val commandOptions = (listOf(currentOptions) + confirmationOptions)
                .joinToString(",")
            appendLine()
            appendLine("```bash")
            appendLine(
                "./gradlew $confirmationTaskPath $confirmationReportTaskPath " +
                    "-P$confirmationPropertyName='batchOptions=$commandOptions' --no-parallel"
            )
            appendLine("```")
        }
    }
    appendLine()
}

enum class StorageBatchTuningConfirmationVerdict {
    PASS,
    INCONCLUSIVE,
    REGRESSION,
}

data class StorageBatchTuningConfirmationSummary(
    val batchOptions: String,
    val worstThroughputRatio: Double?,
    val worstThroughputRequiredRatio: Double,
    val worstAverageTimeRatio: Double?,
    val worstAllocationRatio: Double?,
    val verdict: StorageBatchTuningConfirmationVerdict,
)

data class StorageBatchTuningBoundedRatio(
    val passRatio: Double?,
    val regressionRatio: Double?,
    val requiredRatio: Double,
)

fun conservativeLowerRatio(
    candidateScore: Double,
    candidateError: Double?,
    currentScore: Double,
    currentError: Double?,
): Double? {
    if (candidateError == null || currentError == null || candidateError < 0.0 || currentError < 0.0) {
        return null
    }
    val candidateLower = candidateScore - candidateError
    val currentUpper = currentScore + currentError
    if (candidateLower <= 0.0 || currentUpper <= 0.0) {
        return null
    }
    return (candidateLower / currentUpper).takeIf(Double::isFinite)
}

fun conservativeUpperRatio(
    candidateScore: Double,
    candidateError: Double?,
    currentScore: Double,
    currentError: Double?,
): Double? {
    if (candidateError == null || currentError == null || candidateError < 0.0 || currentError < 0.0) {
        return null
    }
    val candidateUpper = candidateScore + candidateError
    val currentLower = currentScore - currentError
    if (candidateUpper <= 0.0 || currentLower <= 0.0) {
        return null
    }
    return (candidateUpper / currentLower).takeIf(Double::isFinite)
}

fun storageBatchTuningConfirmationSummary(
    rows: List<ParsedBenchmarkResult>,
    batchOptions: String,
    currentOptions: String,
): StorageBatchTuningConfirmationSummary {
    val rowsByModeAndKey = rows.groupBy { row ->
        row.mode to StorageBatchTuningKey(
            method = benchmarkMethodName(row),
            refresh = row.parameters["refresh"] ?: "-",
            threads = row.threads,
        )
    }
    val throughputRatios = rowsByModeAndKey
        .filterKeys { (mode, _) -> mode == "thrpt" }
        .map { (modeAndKey, workloadRows) ->
            val key = modeAndKey.second
            val rowsByOptions = workloadRows.associateBy { row -> row.parameters.getValue("batchOptions") }
            val candidate = rowsByOptions.getValue(batchOptions)
            val current = rowsByOptions.getValue(currentOptions)
            val requiredRatio = if (key.method == "appendSaturated512") {
                storageBatchTuningSaturatedMinimumRatio
            } else {
                storageBatchTuningGuardMinimumRatio
            }
            StorageBatchTuningBoundedRatio(
                passRatio = conservativeLowerRatio(
                    candidateScore = candidate.score,
                    candidateError = candidate.scoreError,
                    currentScore = current.score,
                    currentError = current.scoreError,
                ),
                regressionRatio = conservativeUpperRatio(
                    candidateScore = candidate.score,
                    candidateError = candidate.scoreError,
                    currentScore = current.score,
                    currentError = current.scoreError,
                ),
                requiredRatio = requiredRatio,
            )
        }
    val averageTimeRatios = rowsByModeAndKey
        .filterKeys { (mode, _) -> mode == "avgt" }
        .map { (_, workloadRows) ->
            val rowsByOptions = workloadRows.associateBy { row -> row.parameters.getValue("batchOptions") }
            val candidate = rowsByOptions.getValue(batchOptions)
            val current = rowsByOptions.getValue(currentOptions)
            StorageBatchTuningBoundedRatio(
                passRatio = conservativeUpperRatio(
                    candidateScore = candidate.score,
                    candidateError = candidate.scoreError,
                    currentScore = current.score,
                    currentError = current.scoreError,
                ),
                regressionRatio = conservativeLowerRatio(
                    candidateScore = candidate.score,
                    candidateError = candidate.scoreError,
                    currentScore = current.score,
                    currentError = current.scoreError,
                ),
                requiredRatio = storageBatchTuningAllocationMaximumRatio,
            )
        }
    val allocationRatios = rowsByModeAndKey
        .filterKeys { (mode, _) -> mode == "thrpt" }
        .map { (_, workloadRows) ->
            val rowsByOptions = workloadRows.associateBy { row -> row.parameters.getValue("batchOptions") }
            val candidate = rowsByOptions.getValue(batchOptions)
            val current = rowsByOptions.getValue(currentOptions)
            val candidateAllocation = candidate.allocationBytesPerOp
                ?: throw GradleException("Storage batch confirmation is missing candidate allocation.")
            val currentAllocation = current.allocationBytesPerOp
                ?: throw GradleException("Storage batch confirmation is missing current allocation.")
            StorageBatchTuningBoundedRatio(
                passRatio = conservativeUpperRatio(
                    candidateScore = candidateAllocation,
                    candidateError = candidate.allocationErrorBytesPerOp,
                    currentScore = currentAllocation,
                    currentError = current.allocationErrorBytesPerOp,
                ),
                regressionRatio = conservativeLowerRatio(
                    candidateScore = candidateAllocation,
                    candidateError = candidate.allocationErrorBytesPerOp,
                    currentScore = currentAllocation,
                    currentError = current.allocationErrorBytesPerOp,
                ),
                requiredRatio = storageBatchTuningAllocationMaximumRatio,
            )
        }
    if (throughputRatios.isEmpty() || averageTimeRatios.isEmpty() || allocationRatios.isEmpty()) {
        throw GradleException("Storage batch confirmation requires thrpt, avgt, and allocation rows.")
    }
    val worstThroughput = throughputRatios.minBy { ratio ->
        ratio.passRatio?.div(ratio.requiredRatio) ?: Double.NEGATIVE_INFINITY
    }
    val worstAverageTime = averageTimeRatios.maxBy { ratio ->
        ratio.passRatio ?: Double.POSITIVE_INFINITY
    }
    val worstAllocation = allocationRatios.maxBy { ratio ->
        ratio.passRatio ?: Double.POSITIVE_INFINITY
    }
    val conservativeRegression =
        throughputRatios.any { ratio ->
            ratio.regressionRatio?.let { it < ratio.requiredRatio } == true
        } ||
            averageTimeRatios.any { ratio ->
                ratio.regressionRatio?.let { it > ratio.requiredRatio } == true
            } ||
            allocationRatios.any { ratio ->
                ratio.regressionRatio?.let { it > ratio.requiredRatio } == true
            }
    val conservativePass =
        throughputRatios.all { ratio ->
            ratio.passRatio?.let { it >= ratio.requiredRatio } == true
        } &&
            averageTimeRatios.all { ratio ->
                ratio.passRatio?.let { it <= ratio.requiredRatio } == true
            } &&
            allocationRatios.all { ratio ->
                ratio.passRatio?.let { it <= ratio.requiredRatio } == true
            }
    return StorageBatchTuningConfirmationSummary(
        batchOptions = batchOptions,
        worstThroughputRatio = worstThroughput.passRatio,
        worstThroughputRequiredRatio = worstThroughput.requiredRatio,
        worstAverageTimeRatio = worstAverageTime.passRatio,
        worstAllocationRatio = worstAllocation.passRatio,
        verdict = when {
            conservativeRegression -> StorageBatchTuningConfirmationVerdict.REGRESSION
            conservativePass -> StorageBatchTuningConfirmationVerdict.PASS
            else -> StorageBatchTuningConfirmationVerdict.INCONCLUSIVE
        },
    )
}

fun requireMongoBatchOptionsPairedFrontierCandidate(
    finalist: String,
    orderedFrontier: List<String>,
) {
    requireStorageBatchTuningOption(
        finalist,
        "Gradle property $mongoBatchOptionsPairedFinalistProperty",
    )
    if (finalist !in orderedFrontier) {
        throw GradleException(
            "Mongo paired finalist $finalist is not in the validated screening frontier: " +
                orderedFrontier
        )
    }
}

fun requireMongoBatchOptionsPairedCandidate(
    finalist: String,
    orderedFrontier: List<String>,
    multipleForkVerdict: StorageBatchTuningConfirmationVerdict,
) {
    requireMongoBatchOptionsPairedFrontierCandidate(finalist, orderedFrontier)
    if (multipleForkVerdict != StorageBatchTuningConfirmationVerdict.INCONCLUSIVE) {
        throw GradleException(
            "Mongo paired finalist $finalist requires an INCONCLUSIVE multiple-fork verdict, found " +
                multipleForkVerdict
        )
    }
}

fun requireMongoBatchOptionsPairedPreflight(
    currentJmhJarSha256: String? = null,
): ValidatedStorageBatchTuningScreening {
    requireMongoBatchOptionsPairedProtocol()
    val additionalAllowedPaths = setOf(
        benchmarkReportPath(mongoBatchOptionsTuningConfirmationReportFile.asFile),
        benchmarkReportPath(mongoBatchOptionsPairedConfirmationReportFile.asFile),
    )
    val screening = validateStorageBatchTuningScreeningEvidence(
        suite = mongoBatchOptionsTuningSuite,
        currentJmhJarSha256 = currentJmhJarSha256,
        additionalAllowedPaths = additionalAllowedPaths,
    )
    val confirmationOptions =
        listOf(mongoCurrentStorageBatchOptions) + screening.evidence.orderedFrontier
    val confirmationTaskSpec = mongoBatchOptionsTuningConfirmationTaskSpec.copy(
        profile = mongoBatchOptionsTuningConfirmationProfile.copy(
            threads = storageBatchTuningConfirmationThreads,
            parameters = mapOf("batchOptions" to confirmationOptions.joinToString(","))
        )
    )
    val confirmationReport = parseBenchmarkGroup(
        parser = JsonSlurper(),
        group = benchmarkResultGroup(confirmationTaskSpec),
    )
    requireStorageBatchTuningConfirmationSourceTransition(
        evidence = screening.evidence,
        suite = mongoBatchOptionsTuningSuite,
        confirmationManifests = confirmationReport.manifests,
        allowedAfterConfirmation = additionalAllowedPaths,
    )
    validateStorageBatchTuningMatrix(
        rows = confirmationReport.rows,
        expectedOptions = confirmationOptions,
        expectedRefreshValues = listOf("-"),
        expectedThreads = confirmationTaskSpec.profile.threads,
        expectedModes = confirmationTaskSpec.profile.benchmarkModes,
    )
    requireStorageBatchTuningConfirmationManifestCompatibility(
        evidence = screening.evidence,
        screeningManifest = screening.report.manifests.single(),
        manifests = confirmationReport.manifests,
        source = storageBatchTuningFrontierEvidenceFile(mongoBatchOptionsTuningSuite).absolutePath,
    )
    requireMongoBatchOptionsPairedFrontierCandidate(
        finalist = mongoBatchOptionsPairedFinalist,
        orderedFrontier = screening.evidence.orderedFrontier,
    )
    val finalistSummary = storageBatchTuningConfirmationSummary(
        rows = confirmationReport.rows,
        batchOptions = mongoBatchOptionsPairedFinalist,
        currentOptions = mongoCurrentStorageBatchOptions,
    )
    requireMongoBatchOptionsPairedCandidate(
        finalist = mongoBatchOptionsPairedFinalist,
        orderedFrontier = screening.evidence.orderedFrontier,
        multipleForkVerdict = finalistSummary.verdict,
    )
    if (currentJmhJarSha256 != null) {
        val requiredServices = mongoBatchOptionsTuningSuite.requiredServices
        val connectedAddresses = requiredServices.associate { requiredService ->
            requiredService.service to requireBenchmarkService(
                requiredService.service,
                requiredService.host,
                requiredService.port,
            )
        }
        requireCurrentBenchmarkEnvironmentCompatibility(
            screening = screening.report.manifests.single(),
            requiredServices = requiredServices,
            infrastructureRuntime = captureBenchmarkInfrastructureRuntime(
                requiredServices = requiredServices,
                connectedAddresses = connectedAddresses,
            ),
            context = "Mongo paired preflight",
        )
    }
    return screening
}

fun validateMongoBatchOptionsPairedPostflight(
    screening: ValidatedStorageBatchTuningScreening,
    experiment: MongoBatchOptionsPairedExperiment,
) {
    val pairedSourceCommit = experiment.manifests
        .map(ParsedBenchmarkRunManifest::sourceCommit)
        .distinct()
        .single()
    val currentHead = currentBenchmarkGitHead()
    requireStorageBatchTuningSourceTransition(
        sourceCommit = pairedSourceCommit,
        targetCommit = currentHead,
        allowedPaths = emptySet(),
        context = "$pairedSourceCommit..$currentHead",
    )
    requireStorageBatchTuningConfirmationManifestCompatibility(
        evidence = screening.evidence,
        screeningManifest = screening.report.manifests.single(),
        manifests = experiment.manifests,
        source = storageBatchTuningFrontierEvidenceFile(
            mongoBatchOptionsTuningSuite
        ).absolutePath,
    )
}

fun formatStorageBatchConfirmationBound(
    ratio: Double?,
    operator: String,
    requiredRatio: Double,
): String {
    val formattedRatio = ratio?.let { value -> String.format(Locale.US, "%.3f×", value) } ?: "-"
    return "$formattedRatio ($operator ${String.format(Locale.US, "%.2f×", requiredRatio)})"
}

fun StringBuilder.appendStorageBatchTuningConfirmationVerdict(
    rows: List<ParsedBenchmarkResult>,
    expectedOptions: List<String>,
    currentOptions: String,
    campaignStopped: Boolean = false,
) {
    val challengerOptions = expectedOptions.filterNot { option -> option == currentOptions }
    if (challengerOptions.isEmpty()) {
        throw GradleException("Storage batch tuning confirmation requires at least one challenger.")
    }
    val summaries = challengerOptions.map { batchOptions ->
        storageBatchTuningConfirmationSummary(
            rows = rows,
            batchOptions = batchOptions,
            currentOptions = currentOptions,
        )
    }
    appendLine("## Confirmation Verdict")
    appendLine()
    appendLine(
        if (campaignStopped) {
            "The statuses below are historical outputs from the stopped full-candidate campaign. Bounds combine " +
                "the independent JMH score intervals; they are not paired ratio confidence intervals. " +
                "Do not continue `INCONCLUSIVE` candidates under the old protocol or use these statuses to " +
                "claim a selected production default."
        } else {
            "PASS requires every candidate-vs-current throughput lower bound, average-time upper bound, " +
                "and allocation upper bound to meet the declared margin across every workload, refresh, and " +
                "thread stratum. REGRESSION requires an opposite conservative bound to violate a margin. " +
                "Bounds combine the independent JMH score intervals; they are not a paired ratio confidence " +
                "interval. Every INCONCLUSIVE candidate requires its own paired confirmation. This report classifies " +
                "only the supplied options; selection closes only when they equal the complete Pareto frontier in " +
                "the emitted order and every challenger has a final verdict. The selected default is then the " +
                "highest-ranked PASS, or current when none pass."
        }
    )
    appendLine()
    appendLine(
        "| Batch options | Worst throughput lower bound | Worst average-time upper bound | " +
            "Worst allocation upper bound | Verdict |"
    )
    appendLine(
        "|---------------|------------------------------|--------------------------------|" +
            "-------------------------------|---------|"
    )
    summaries.forEach { summary ->
        appendLine(
            "| `${summary.batchOptions}` | " +
                "${formatStorageBatchConfirmationBound(
                    summary.worstThroughputRatio,
                    ">=",
                    summary.worstThroughputRequiredRatio
                )} | " +
                "${formatStorageBatchConfirmationBound(
                    summary.worstAverageTimeRatio,
                    "<=",
                    storageBatchTuningAllocationMaximumRatio
                )} | " +
                "${formatStorageBatchConfirmationBound(
                    summary.worstAllocationRatio,
                    "<=",
                    storageBatchTuningAllocationMaximumRatio
                )} | ${summary.verdict} |"
        )
    }
    appendLine()
}

fun sha256Text(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun benchmarkHarnessSha256(): String {
    val buildLogicFiles = rootProject.fileTree("build-logic") {
        include("build.gradle.kts")
        include("settings.gradle.kts")
        include("src/main/**/*.kt")
        include("src/main/**/*.kts")
    }.files
    val harnessFiles = buildLogicFiles + layout.projectDirectory.file("build.gradle.kts").asFile
    val digestInput = harnessFiles.sortedBy { file ->
        file.relativeTo(rootProject.rootDir).invariantSeparatorsPath
    }.joinToString(separator = "\n", postfix = "\n") { file ->
        val relativePath = file.relativeTo(rootProject.rootDir).invariantSeparatorsPath
        "$relativePath=${fileSha256(file)}"
    }
    return sha256Text(digestInput)
}

fun benchmarkCombinedResultDigest(manifests: List<ParsedBenchmarkRunManifest>): String {
    val digestInput = manifests.sortedBy(ParsedBenchmarkRunManifest::taskPath)
        .joinToString(separator = "\n", postfix = "\n") { manifest ->
            "${manifest.taskPath}=${manifest.resultSha256}"
        }
    return sha256Text(digestInput)
}

fun mongoBatchPairedCombinedResultDigest(legs: List<ParsedMongoBatchPairedLeg>): String {
    return benchmarkCombinedResultDigest(legs.map(ParsedMongoBatchPairedLeg::manifest))
}

fun formatMongoBatchRatio(value: Double): String {
    return String.format(Locale.US, "%.6f×", value)
}

fun formatMongoBatchGain(value: Double): String {
    return String.format(Locale.US, "%+.3f%%", (value - 1.0) * 100.0)
}

fun mongoBatchPairedVerdictSummary(
    statistics: List<MongoBatchPairedStatistics>,
): String {
    if (statistics.isEmpty()) {
        throw GradleException("Cannot summarize an empty paired Mongo experiment.")
    }
    val allPass = statistics.all { threadStatistics ->
        threadStatistics.verdict == MongoBatchPairedVerdict.PASS
    }
    return if (allPass) {
        "All measured thread configurations pass because each unrounded paired 95% CI lower bound is " +
            "strictly greater than the configured `1.05×` throughput threshold."
    } else {
        val verdictSummary = statistics.joinToString(", ") { threadStatistics ->
            "threads=${threadStatistics.threads}: ${threadStatistics.verdict.displayName}"
        }
        "The configured acceptance rule is `unrounded paired 95% CI lower bound > 1.05×`; " +
            "observed verdicts: $verdictSummary."
    }
}

fun renderMongoBatchAppendPairedE2EReport(
    experiment: MongoBatchPairedExperiment,
): String {
    val command = "./gradlew :wow-benchmarks:benchmarkMongoBatchAppendPairedE2E " +
        ":wow-benchmarks:generateMongoBatchAppendPairedE2EReport --no-parallel"
    val manifests = experiment.manifests
    val statistics = experiment.statistics()
    val reference = manifests.first()
    val startedAt = manifests.minOf { manifest -> Instant.parse(manifest.startedAt) }
    val completedAt = manifests.maxOf { manifest -> Instant.parse(manifest.completedAt) }
    val combinedResultDigest = mongoBatchPairedCombinedResultDigest(experiment.legs)
    val sb = StringBuilder()
    sb.appendLine("<!--")
    sb.appendLine("  This file is auto-generated by `$command`.")
    sb.appendLine("  Do not manually edit benchmark results.")
    sb.appendLine("-->")
    sb.appendLine()
    sb.appendLine("# Mongo EventStore Batch Append Paired E2E Benchmark Report")
    sb.appendLine()
    sb.appendLine(
        "This report measures an append-path E2E workload from per-invocation event-stream creation and " +
            "reactive subscription through `MongoEventStore.append` completion after MongoDB acknowledgement. " +
            "It compares the direct `insertOne` path with transparent unordered `insertMany` batching. " +
            "It does not include Command Gateway ingress, command handling, event publication, or projection, " +
            "and it is not a production-capacity claim."
    )
    sb.appendLine()
    sb.appendLine("## Result")
    sb.appendLine()
    sb.appendLine(
        "| JMH Threads | Pairs | insertOne geometric mean | insertMany batch geometric mean | Paired ratio | Gain | " +
            "Paired 95% CI | Required lower bound | Verdict |"
    )
    sb.appendLine(
        "|-------------|------:|-------------:|--------------------:|-------------:|-----:|" +
            "---------------:|---------------------:|---------|"
    )
    statistics.forEach { threadStatistics ->
        val scale = benchmarkMetricScale(
            values = listOf(
                threadStatistics.directGeometricMean,
                threadStatistics.batchGeometricMean,
            ),
            unit = "ops/s",
        )
        val direct = formatScaledBenchmarkScore(
            score = threadStatistics.directGeometricMean,
            scoreError = null,
            scale = scale,
        ).scoreWithUnit
        val batch = formatScaledBenchmarkScore(
            score = threadStatistics.batchGeometricMean,
            scoreError = null,
            scale = scale,
        ).scoreWithUnit
        sb.appendLine(
            "| ${threadStatistics.threads} | ${threadStatistics.pairCount} | $direct | $batch | " +
                "${formatMongoBatchRatio(threadStatistics.geometricRatio)} | " +
                "${formatMongoBatchGain(threadStatistics.geometricRatio)} | " +
                "[${formatMongoBatchRatio(threadStatistics.lower95Ratio)}, " +
                "${formatMongoBatchRatio(threadStatistics.upper95Ratio)}] | " +
                "> ${formatMongoBatchRatio(mongoBatchPairedMinimumRatio)} | " +
                "**${threadStatistics.verdict.displayName}** |"
        )
    }
    sb.appendLine()
    sb.appendLine(mongoBatchPairedVerdictSummary(statistics))
    sb.appendLine()
    sb.appendLine("## Scope And Methodology")
    sb.appendLine()
    sb.appendLine(
        "- Each invocation creates and submits $mongoBatchPairedOperationsPerInvocation independent single-event " +
            "streams through one reactive subscription, including the shared Reactor harness and final blocking " +
            "wait, and waits for all append publishers to complete."
    )
    sb.appendLine(
        "- `@OperationsPerInvocation($mongoBatchPairedOperationsPerInvocation)` normalizes throughput per event " +
            "stream; the statistical sample is the $mongoBatchPairedRounds run pairs, not the appends inside an invocation."
    )
    sb.appendLine(
        "- Odd rounds use AB (`insertOne → batch`); even rounds use BA (`batch → insertOne`). " +
            "Every leg is a separate JMH process with one measurement fork, and Gradle executes all legs serially."
    )
    sb.appendLine(
        "- JMH configuration: `${pairedMongoBatchAppendProfile.configSummary()}`; " +
            "JVM args: `${pairedMongoBatchAppendProfile.jvmArgs.joinToString(" ")}`."
    )
    sb.appendLine(
        "- For pair `i`, `r_i = batch_i / insertOne_i`. The point estimate is " +
            "`exp(mean(ln(r_i)))`; the interval is the two-sided Student-t 95% CI on log ratios " +
            "with `df=${mongoBatchPairedRounds - 1}` and " +
            "`t=${mongoBatchPairedT95Critical(mongoBatchPairedRounds)}`, exponentiated back to the ratio scale."
    )
    sb.appendLine(
        "- Protocol version `$mongoBatchPairedProtocolVersion`, pair count, operations per invocation, statistic, " +
            "confidence level, t critical value, configured `1.05×` threshold, and acceptance rule are captured " +
            "in every leg manifest."
    )
    sb.appendLine(
        "- A single leg has only one measurement iteration, so its JMH `scoreError` is not used. " +
            "Uncertainty comes from the eight paired log ratios for each thread configuration."
    )
    sb.appendLine(
        "- The interval assumes the eight log-ratio pairs are independent and approximately normal. " +
            "AB/BA alternation is fixed rather than randomized, so it cannot eliminate time trends or autocorrelation."
    )
    sb.appendLine()
    sb.appendLine("## Order Diagnostics")
    sb.appendLine()
    sb.appendLine(
        "| JMH Threads | AB pairs | AB geometric ratio | BA pairs | BA geometric ratio | " +
            "Pair ratio range (descriptive) | Pairs > 1.05× (descriptive) |"
    )
    sb.appendLine("|-------------|---------:|-------------------:|---------:|-------------------:|-----------------:|---------------:|")
    statistics.forEach { threadStatistics ->
        sb.appendLine(
            "| ${threadStatistics.threads} | ${mongoBatchPairedRounds / 2} | " +
                "${formatMongoBatchRatio(threadStatistics.directThenBatchRatio)} | " +
                "${mongoBatchPairedRounds / 2} | " +
                "${formatMongoBatchRatio(threadStatistics.batchThenDirectRatio)} | " +
                "[${formatMongoBatchRatio(threadStatistics.minimumPairRatio)}, " +
                "${formatMongoBatchRatio(threadStatistics.maximumPairRatio)}] | " +
                "${threadStatistics.passingPairCount}/${threadStatistics.pairCount} |"
        )
    }
    sb.appendLine()
    sb.appendLine(
        "AB and BA subgroup ratios are diagnostics for a fixed-order artifact; they are not additional " +
            "hypothesis tests and do not prove that order effects are absent."
    )
    sb.appendLine()
    sb.appendLine("## Per-Pair Results")
    sb.appendLine()
    sb.appendLine("| JMH Threads | Round | Order | insertOne | insertMany batch | Ratio | Gain |")
    sb.appendLine("|-------------|------:|:-----:|----------:|-----------------:|------:|-----:|")
    experiment.observations
        .sortedWith(compareBy(MongoBatchPairedObservation::threads, MongoBatchPairedObservation::round))
        .forEach { observation ->
            val scale = benchmarkMetricScale(
                values = listOf(observation.directScore, observation.batchScore),
                unit = observation.unit,
            )
            val direct = formatScaledBenchmarkScore(
                score = observation.directScore,
                scoreError = null,
                scale = scale,
            ).scoreWithUnit
            val batch = formatScaledBenchmarkScore(
                score = observation.batchScore,
                scoreError = null,
                scale = scale,
            ).scoreWithUnit
            sb.appendLine(
                "| ${observation.threads} | ${observation.round} | ${observation.order.id} | " +
                    "$direct | $batch | " +
                    "${formatMongoBatchRatio(observation.ratio)} | " +
                    "${formatMongoBatchGain(observation.ratio)} |"
            )
        }
    sb.appendLine()
    sb.appendLine("## Benchmark Run Provenance")
    sb.appendLine()
    sb.appendLine("- **Suite**: `${reference.suite}`")
    sb.appendLine("- **Profile**: `${reference.profile}`")
    sb.appendLine("- **Run ID**: `${reference.runId}`")
    sb.appendLine("- **Run Window**: $startedAt to $completedAt")
    sb.appendLine("- **Source Commit**: `${reference.sourceCommit}`")
    sb.appendLine("- **Source Dirty**: `${reference.sourceDirty}`")
    sb.appendLine("- **Project Version**: `${reference.projectVersion}`")
    sb.appendLine("- **JMH Jar SHA-256**: `${reference.jmhJarSha256}`")
    sb.appendLine("- **Runtime JVM**: ${reference.vmName} ${reference.vmVersion} / Java ${reference.javaVersion}")
    sb.appendLine("- **Runtime OS**: ${reference.osName} ${reference.osVersion} ${reference.osArch}")
    sb.appendLine("- **CPU Cores**: ${reference.availableProcessors}")
    sb.appendLine("- **Physical Memory**: ${formatMemoryBytes(reference.physicalMemoryBytes)}")
    sb.appendLine(
        "- **Required Services**: `${formatRequiredServiceEndpoints(reference.requiredServices)}`"
    )
    sb.appendLine("- **Successful Leg Manifests**: ${manifests.size}")
    sb.appendLine(
        "- **Combined Result SHA-256**: `$combinedResultDigest` " +
            "(`SHA-256` over sorted `taskPath=resultSha256` lines)"
    )
    sb.appendLine()
    sb.appendCapturedInfrastructureRuntime(manifests)
    sb.appendLine("### Artifact Evidence")
    sb.appendLine()
    sb.appendLine("| Threads | Round | Order | Position | Variant | Task | Started | Completed | Result SHA-256 |")
    sb.appendLine("|---------|------:|:-----:|---------:|---------|------|---------|-----------|----------------|")
    experiment.legs.forEach { leg ->
        sb.appendLine(
            "| ${leg.trial.threads} | ${leg.trial.round} | ${leg.trial.order.id} | " +
                "${leg.trial.position} | ${leg.trial.variant.id} | `${leg.manifest.taskPath}` | " +
                "${leg.manifest.startedAt} | ${leg.manifest.completedAt} | " +
                "`${leg.manifest.resultSha256}` |"
        )
    }
    sb.appendLine()
    sb.appendBenchmarkEnvironment(project.version.toString(), pairedMongoBatchAppendProfile)
    sb.appendInfrastructureRuntime(reference.requiredServices)
    sb.appendLine("## Limitations")
    sb.appendLine()
    sb.appendLine(
        "- Throughput-only legs do not identify CPU, allocation, network, or storage bottlenecks; use profiling " +
            "and production telemetry before capacity planning."
    )
    sb.appendLine(
        "- MongoDB command counts are intentionally excluded because this experiment does not capture them in " +
            "the provenance manifests."
    )
    return sb.toString()
}

fun renderMongoBatchOptionsPairedConfirmationReport(
    experiment: MongoBatchOptionsPairedExperiment,
): String {
    val command = "./gradlew :wow-benchmarks:benchmarkMongoBatchOptionsPairedConfirmation " +
        ":wow-benchmarks:generateMongoBatchOptionsPairedConfirmationReport " +
        "-P$mongoBatchOptionsPairedFinalistProperty=$mongoBatchOptionsPairedFinalist --no-parallel"
    val manifests = experiment.manifests
    val statistics = experiment.statistics()
    val overallVerdict = classifyMongoBatchOptionsPairedExperiment(statistics)
    val reference = manifests.first()
    val startedAt = manifests.minOf { manifest -> Instant.parse(manifest.startedAt) }
    val completedAt = manifests.maxOf { manifest -> Instant.parse(manifest.completedAt) }
    val combinedResultDigest = benchmarkCombinedResultDigest(manifests)
    val sb = StringBuilder()
    sb.appendLine("<!--")
    sb.appendLine("  This file is auto-generated by `$command`.")
    sb.appendLine("  Do not manually edit benchmark results.")
    sb.appendLine("-->")
    sb.appendLine()
    sb.appendLine("# Mongo EventStore Batch Options Paired Confirmation Report")
    sb.appendLine()
    sb.appendLine(
        "This completed candidate-level experiment is retained as exploratory evidence. It compares finalist " +
            "`$mongoBatchOptionsPairedFinalist` with current `$mongoBatchOptionsPairedCurrent` through the same " +
            "coordinated Mongo EventStore path. The wider Pareto campaign was stopped before every candidate " +
            "completed, so this report is not a pre-registered final decision or default-selection proof."
    )
    sb.appendLine()
    sb.appendLine("## Overall Verdict")
    sb.appendLine()
    sb.appendLine("- **Verdict**: **$overallVerdict**")
    sb.appendLine("- **Current**: `$mongoBatchOptionsPairedCurrent`")
    sb.appendLine("- **Finalist**: `$mongoBatchOptionsPairedFinalist`")
    sb.appendLine(
        "- **Selection Scope**: exploratory candidate evidence only. The stopped campaign does not resolve the " +
            "remaining frontier and must not be used to claim an optimal or validated production default."
    )
    sb.appendLine(
        "- **Acceptance**: every workload/thread throughput and allocation safety bound must pass, and the " +
            "paired throughput lower bound for primary `representative128` must be greater than `1.0×` " +
            "at both one and four JMH threads."
    )
    sb.appendLine()
    sb.appendLine("## Stratum Results")
    sb.appendLine()
    sb.appendLine(
        "| Workload | Threads | Pairs | Current throughput | Finalist throughput | Throughput ratio | " +
            "95% CI | Required lower | Equivalent time ratio / 95% CI | Allocation ratio / 95% CI | " +
            "Safety verdict |"
    )
    sb.appendLine(
        "|----------|--------:|------:|-------------------:|---------------------:|-----------------:|" +
            "-------:|---------------:|-------------------------------:|---------------------------:|----------------|"
    )
    statistics.forEach { statistic ->
        val scale = benchmarkMetricScale(
            values = listOf(
                statistic.throughput.currentGeometricMean,
                statistic.throughput.finalistGeometricMean,
            ),
            unit = "ops/s",
        )
        val current = formatScaledBenchmarkScore(
            score = statistic.throughput.currentGeometricMean,
            scoreError = null,
            scale = scale,
        ).scoreWithUnit
        val finalist = formatScaledBenchmarkScore(
            score = statistic.throughput.finalistGeometricMean,
            scoreError = null,
            scale = scale,
        ).scoreWithUnit
        sb.appendLine(
            "| `${statistic.workload.id}` | ${statistic.threads} | ${statistic.pairCount} | " +
                "$current | $finalist | ${formatMongoBatchRatio(statistic.throughput.geometricRatio)} | " +
                "[${formatMongoBatchRatio(statistic.throughput.lower95Ratio)}, " +
                "${formatMongoBatchRatio(statistic.throughput.upper95Ratio)}] | " +
                ">= ${formatMongoBatchRatio(statistic.requiredThroughputRatio)} | " +
                "${formatMongoBatchRatio(statistic.equivalentTimeRatio)} / " +
                "[${formatMongoBatchRatio(statistic.equivalentTimeLower95Ratio)}, " +
                "${formatMongoBatchRatio(statistic.equivalentTimeUpper95Ratio)}] | " +
                "${formatMongoBatchRatio(statistic.allocation.geometricRatio)} / " +
                "[${formatMongoBatchRatio(statistic.allocation.lower95Ratio)}, " +
                "${formatMongoBatchRatio(statistic.allocation.upper95Ratio)}] | " +
                "**${statistic.safetyVerdict()}** |"
        )
    }
    sb.appendLine()
    sb.appendLine("## Scope And Methodology")
    sb.appendLine()
    sb.appendLine(
        "- Strata are the Cartesian product of four workloads " +
            "(`isolated`, `burst32`, `representative128`, `saturated512`) and JMH threads `1,4`; " +
            "ratios are never pooled across strata."
    )
    sb.appendLine(
        "- Each stratum has $mongoBatchOptionsPairedRounds adjacent run pairs using the same fixed, " +
            "balanced, non-periodic sequence: " +
            "`${mongoBatchOptionsPairedOrderSequence.joinToString(" ") { order -> order.id }}`. " +
            "AB means `current → finalist`, BA means `finalist → current`; every leg is an independent JMH process."
    )
    sb.appendLine(
        "- JMH configuration: `${pairedMongoBatchOptionsProfile.configSummary()}`; " +
            "JVM args: `${pairedMongoBatchOptionsProfile.jvmArgs.joinToString(" ")}`."
    )
    sb.appendLine(
        "- For pair `i`, throughput `r_i = finalist_i / current_i`. The point estimate is " +
            "`exp(mean(ln(r_i)))`; the interval is a two-sided Student-t 95% CI on log ratios with " +
            "`df=${mongoBatchOptionsPairedRounds - 1}` and " +
            "`t=${pairedT95Critical(mongoBatchOptionsPairedRounds)}`, exponentiated back to ratio scale."
    )
    sb.appendLine(
        "- Non-saturated throughput lower bounds must be at least " +
            "`${formatMongoBatchRatio(mongoBatchOptionsPairedGuardMinimumRatio)}` so their equivalent amortized " +
            "time upper bounds stay within `1.10×`. Saturated throughput lower bounds must be at least " +
            "`${formatMongoBatchRatio(mongoBatchOptionsPairedSaturatedMinimumRatio)}`."
    )
    sb.appendLine(
        "- Allocation uses `gc.alloc.rate.norm` from the same paired throughput legs and requires every " +
            "paired upper bound to be at most " +
            "`${formatMongoBatchRatio(mongoBatchOptionsPairedAllocationMaximumRatio)}`."
    )
    sb.appendLine(
        "- Equivalent amortized time is the inverse throughput ratio and CI. It is not a separately sampled " +
            "append response time, percentile, or open-loop latency distribution."
    )
    sb.appendLine(
        "- `PASS` requires all safety bounds plus primary representative superiority; `REGRESSION` requires an " +
            "opposite bound to clear a safety threshold; `NO_BENEFIT` requires safety to pass while a primary " +
            "upper bound is at most `1.0×`; all other outcomes are `INCONCLUSIVE`. Any non-PASS result keeps current."
    )
    sb.appendLine(
        "- Every throughput and allocation stratum must also keep AB/BA order effect at or below " +
            "`${formatMongoBatchRatio(mongoBatchOptionsPairedMaximumOrderEffectRatio)}` and absolute lag-one " +
            "order-adjusted log-ratio residual correlation at or below " +
            "`${formatMetricNumber(mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation)}`. " +
            "A diagnostic breach forces `INCONCLUSIVE` unless a safety regression is already proven."
    )
    sb.appendLine(
        "- All 48 legs within a workload/thread stratum must share one run ID and execute in protocol order. " +
            "Recovered strata may have different run IDs, but every manifest must retain the same source commit, " +
            "JMH jar, JVM, OS, processor count, and physical-memory identity, and stratum evidence windows " +
            "must not overlap."
    )
    sb.appendLine()
    sb.appendLine("## Order Diagnostics")
    sb.appendLine()
    sb.appendLine(
        "| Workload | Threads | Throughput AB / BA | Throughput order effect | Throughput residual lag-1 | " +
            "Allocation AB / BA | Allocation order effect | Allocation residual lag-1 | Diagnostics |"
    )
    sb.appendLine(
        "|----------|--------:|-------------------:|------------------------:|-----------------:|" +
            "------------------:|------------------------:|-----------------:|-------------|"
    )
    statistics.forEach { statistic ->
        val throughputDiagnostics = statistic.throughput.diagnosticsPass(
            mongoBatchOptionsPairedMaximumOrderEffectRatio,
            mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation,
        )
        val allocationDiagnostics = statistic.allocation.diagnosticsPass(
            mongoBatchOptionsPairedMaximumOrderEffectRatio,
            mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation,
        )
        val diagnostics = if (throughputDiagnostics && allocationDiagnostics) {
            "PASS"
        } else {
            "INCONCLUSIVE"
        }
        sb.appendLine(
            "| `${statistic.workload.id}` | ${statistic.threads} | " +
                "${formatMongoBatchRatio(statistic.throughput.currentThenFinalistRatio)} / " +
                "${formatMongoBatchRatio(statistic.throughput.finalistThenCurrentRatio)} | " +
                "${formatMongoBatchRatio(statistic.throughput.orderEffectRatio)} | " +
                "${formatMetricNumber(statistic.throughput.lagOneResidualCorrelation)} | " +
                "${formatMongoBatchRatio(statistic.allocation.currentThenFinalistRatio)} / " +
                "${formatMongoBatchRatio(statistic.allocation.finalistThenCurrentRatio)} | " +
                "${formatMongoBatchRatio(statistic.allocation.orderEffectRatio)} | " +
                "${formatMetricNumber(statistic.allocation.lagOneResidualCorrelation)} | **$diagnostics** |"
        )
    }
    sb.appendLine()
    sb.appendLine(
        "AB/BA and lag-one checks are conservative acceptance diagnostics. They reduce obvious order and serial " +
            "dependence risks but do not prove the absence of longer time trends or periodic host load."
    )
    sb.appendLine()
    sb.appendLine("## Per-Pair Results")
    sb.appendLine()
    sb.appendLine(
        "| Workload | Threads | Round | Order | Current throughput | Finalist throughput | Throughput ratio | " +
            "Current allocation | Finalist allocation | Allocation ratio |"
    )
    sb.appendLine(
        "|----------|--------:|------:|:-----:|-------------------:|---------------------:|-----------------:|" +
            "-------------------:|---------------------:|-----------------:|"
    )
    experiment.observations
        .sortedWith(
            compareBy<MongoBatchOptionsPairedObservation>(
                { it.workload.ordinal },
                { it.threads },
                { it.round },
            )
        )
        .forEach { observation ->
            val scale = benchmarkMetricScale(
                values = listOf(observation.currentScore, observation.finalistScore),
                unit = observation.unit,
            )
            val current = formatScaledBenchmarkScore(
                score = observation.currentScore,
                scoreError = null,
                scale = scale,
            ).scoreWithUnit
            val finalist = formatScaledBenchmarkScore(
                score = observation.finalistScore,
                scoreError = null,
                scale = scale,
            ).scoreWithUnit
            sb.appendLine(
                "| `${observation.workload.id}` | ${observation.threads} | ${observation.round} | " +
                    "${observation.order.id} | $current | $finalist | " +
                    "${formatMongoBatchRatio(observation.finalistScore / observation.currentScore)} | " +
                    "${formatAllocationBytes(observation.currentAllocation)} | " +
                    "${formatAllocationBytes(observation.finalistAllocation)} | " +
                    "${formatMongoBatchRatio(observation.finalistAllocation / observation.currentAllocation)} |"
            )
        }
    sb.appendLine()
    sb.appendLine("## Benchmark Run Provenance")
    sb.appendLine()
    sb.appendLine("- **Suite**: `${reference.suite}`")
    sb.appendLine("- **Profile**: `${reference.profile}`")
    sb.appendLine("- **Evidence Window**: $startedAt to $completedAt")
    sb.appendLine("- **Source Commit**: `${reference.sourceCommit}`")
    sb.appendLine("- **Source Dirty**: `${reference.sourceDirty}`")
    sb.appendLine("- **Project Version**: `${reference.projectVersion}`")
    sb.appendLine("- **JMH Jar SHA-256**: `${reference.jmhJarSha256}`")
    sb.appendLine("- **Runtime JVM**: ${reference.vmName} ${reference.vmVersion} / Java ${reference.javaVersion}")
    sb.appendLine("- **Runtime OS**: ${reference.osName} ${reference.osVersion} ${reference.osArch}")
    sb.appendLine("- **CPU Cores**: ${reference.availableProcessors}")
    sb.appendLine("- **Physical Memory**: ${formatMemoryBytes(reference.physicalMemoryBytes)}")
    sb.appendLine("- **Required Services**: `${formatRequiredServiceEndpoints(reference.requiredServices)}`")
    sb.appendLine("- **Successful Leg Manifests**: ${manifests.size}")
    sb.appendLine(
        "- **Combined Result SHA-256**: `$combinedResultDigest` " +
            "(`SHA-256` over sorted `taskPath=resultSha256` lines)"
    )
    sb.appendLine()
    sb.appendCapturedInfrastructureRuntime(manifests)
    sb.appendLine("### Stratum Run Scopes")
    sb.appendLine()
    sb.appendLine("| Workload | Threads | Run ID | Started | Completed |")
    sb.appendLine("|----------|--------:|--------|---------|-----------|")
    experiment.legs
        .groupBy { leg -> leg.trial.workload to leg.trial.threads }
        .forEach { (stratum, stratumLegs) ->
            val (workload, threads) = stratum
            val runId = stratumLegs.map { leg -> leg.manifest.runId }.distinct().single()
            val stratumStartedAt = stratumLegs.minOf { leg -> Instant.parse(leg.manifest.startedAt) }
            val stratumCompletedAt = stratumLegs.maxOf { leg -> Instant.parse(leg.manifest.completedAt) }
            sb.appendLine(
                "| `${workload.id}` | $threads | `$runId` | $stratumStartedAt | $stratumCompletedAt |"
            )
        }
    sb.appendLine()
    sb.appendLine("### Artifact Evidence")
    sb.appendLine()
    sb.appendLine(
        "| Workload | Threads | Round | Order | Position | Variant | Batch options | Task | " +
            "Run ID | Started | Completed | Result SHA-256 |"
    )
    sb.appendLine(
        "|----------|--------:|------:|:-----:|---------:|---------|---------------|------|" +
            "-------|---------|-----------|----------------|"
    )
    experiment.legs.forEach { leg ->
        sb.appendLine(
            "| `${leg.trial.workload.id}` | ${leg.trial.threads} | ${leg.trial.round} | " +
                "${leg.trial.order.id} | ${leg.trial.position} | ${leg.trial.variant.id} | " +
                "`${leg.trial.variant.batchOptions}` | `${leg.manifest.taskPath}` | " +
                "`${leg.manifest.runId}` | ${leg.manifest.startedAt} | ${leg.manifest.completedAt} | " +
                "`${leg.manifest.resultSha256}` |"
        )
    }
    sb.appendLine()
    sb.appendBenchmarkEnvironment(project.version.toString(), pairedMongoBatchOptionsProfile)
    sb.appendInfrastructureRuntime(reference.requiredServices)
    sb.appendLine("## Limitations")
    sb.appendLine()
    sb.appendLine(
        "- The interval assumes the $mongoBatchOptionsPairedRounds log-ratio pairs in each stratum are independent " +
            "and approximately normal. The fixed balanced order and acceptance diagnostics cannot " +
            "eliminate longer periodic load, time trends, or higher-order autocorrelation."
    )
    sb.appendLine(
        "- Closed-loop throughput and its inverse amortized time do not expose per-request p50/p95/p99 or " +
            "coordinated omission. Production latency SLOs require a separate fixed-arrival-rate experiment."
    )
    sb.appendLine(
        "- Results apply to the recorded local MongoDB, Docker, JVM, host, workload payload, and acknowledgement " +
            "configuration; they are exploratory candidate evidence, not a production default or capacity claim."
    )
    sb.appendLine(
        "- SnapshotStore is outside this EventStore experiment and must not inherit the finalist without its own " +
            "payload, ordering, and coalescing benchmark."
    )
    return sb.toString()
}

fun renderMongoBatchOptionsQuickReport(
    experiment: MongoBatchOptionsQuickExperiment,
): String {
    val command = "./gradlew :wow-benchmarks:benchmarkQuickMongoBatchOptionsPaired " +
        ":wow-benchmarks:generateQuickMongoBatchOptionsPairedReport --no-parallel --no-daemon"
    val manifests = experiment.manifests
    val statistics = experiment.statistics()
    val reference = manifests.first()
    val startedAt = manifests.minOf { manifest -> Instant.parse(manifest.startedAt) }
    val completedAt = manifests.maxOf { manifest -> Instant.parse(manifest.completedAt) }
    val combinedResultDigest = benchmarkCombinedResultDigest(manifests)
    val sb = StringBuilder()
    sb.appendLine("<!--")
    sb.appendLine("  This file is auto-generated by `$command`.")
    sb.appendLine("  Do not manually edit benchmark results.")
    sb.appendLine("-->")
    sb.appendLine()
    sb.appendLine("# Quick Mongo EventStore Batch Options Engineering Report")
    sb.appendLine()
    sb.appendLine(
        "This bounded quick experiment compares candidate `$mongoBatchQuickCandidateOptions` with current " +
            "`$mongoBatchQuickCurrentOptions`. It is independent from the stopped formal Pareto campaign, uses " +
            "its own raw-result directory, and is directional engineering evidence rather than a production " +
            "default-selection proof."
    )
    sb.appendLine()
    sb.appendLine("## Decision Status")
    sb.appendLine()
    sb.appendLine("- **Status**: **ACCEPTANCE CRITERIA INCOMPLETE**")
    sb.appendLine(
        "- The requested acceptance text ended after `representative128 / threads=1,4`; this report preserves " +
            "the exact measurements and does not invent a missing threshold."
    )
    sb.appendLine("- All ratios and 95% intervals below are descriptive quick signals based on four pairs.")
    sb.appendLine()
    sb.appendLine("## Stratum Results")
    sb.appendLine()
    sb.appendLine(
        "| Workload | Threads | Pairs | Current throughput | Candidate throughput | Throughput ratio / 95% CI | " +
            "Equivalent average-time ratio / 95% CI | Allocation ratio / 95% CI |"
    )
    sb.appendLine(
        "|----------|--------:|------:|-------------------:|---------------------:|---------------------------:|" +
            "-----------------------------------------:|---------------------------:|"
    )
    statistics.forEach { statistic ->
        val scale = benchmarkMetricScale(
            values = listOf(
                statistic.throughput.currentGeometricMean,
                statistic.throughput.finalistGeometricMean,
            ),
            unit = "ops/s",
        )
        val current = formatScaledBenchmarkScore(
            score = statistic.throughput.currentGeometricMean,
            scoreError = null,
            scale = scale,
        ).scoreWithUnit
        val candidate = formatScaledBenchmarkScore(
            score = statistic.throughput.finalistGeometricMean,
            scoreError = null,
            scale = scale,
        ).scoreWithUnit
        sb.appendLine(
            "| `${statistic.workload.id}` | ${statistic.threads} | ${statistic.pairCount} | " +
                "$current | $candidate | ${formatMongoBatchRatio(statistic.throughput.geometricRatio)} / " +
                "[${formatMongoBatchRatio(statistic.throughput.lower95Ratio)}, " +
                "${formatMongoBatchRatio(statistic.throughput.upper95Ratio)}] | " +
                "${formatMongoBatchRatio(statistic.equivalentTimeRatio)} / " +
                "[${formatMongoBatchRatio(statistic.equivalentTimeLower95Ratio)}, " +
                "${formatMongoBatchRatio(statistic.equivalentTimeUpper95Ratio)}] | " +
                "${formatMongoBatchRatio(statistic.allocation.geometricRatio)} / " +
                "[${formatMongoBatchRatio(statistic.allocation.lower95Ratio)}, " +
                "${formatMongoBatchRatio(statistic.allocation.upper95Ratio)}] |"
        )
    }
    sb.appendLine()
    sb.appendLine(
        "A throughput ratio above `1×` favors the candidate. An equivalent average-time or allocation ratio below " +
            "`1×` favors the candidate. The average-time ratio is the inverse of closed-loop throughput, not an " +
            "independently sampled response-time percentile."
    )
    sb.appendLine()
    sb.appendLine("## Per-Pair Results")
    sb.appendLine()
    sb.appendLine(
        "| Workload | Threads | Round | Order | Current throughput | Candidate throughput | Throughput ratio | " +
            "Current allocation | Candidate allocation | Allocation ratio |"
    )
    sb.appendLine(
        "|----------|--------:|------:|:-----:|-------------------:|---------------------:|-----------------:|" +
            "-------------------:|---------------------:|-----------------:|"
    )
    experiment.observations.forEach { observation ->
        val throughputScale = benchmarkMetricScale(
            values = listOf(observation.currentScore, observation.finalistScore),
            unit = observation.unit,
        )
        val currentThroughput = formatScaledBenchmarkScore(
            observation.currentScore,
            null,
            throughputScale,
        ).scoreWithUnit
        val candidateThroughput = formatScaledBenchmarkScore(
            observation.finalistScore,
            null,
            throughputScale,
        ).scoreWithUnit
        sb.appendLine(
            "| `${observation.workload.id}` | ${observation.threads} | ${observation.round} | " +
                "${observation.order.id} | $currentThroughput | $candidateThroughput | " +
                "${formatMongoBatchRatio(observation.finalistScore / observation.currentScore)} | " +
                "${formatAllocationBytes(observation.currentAllocation)} | " +
                "${formatAllocationBytes(observation.finalistAllocation)} | " +
                "${formatMongoBatchRatio(observation.finalistAllocation / observation.currentAllocation)} |"
        )
    }
    sb.appendLine()
    sb.appendLine("## Protocol")
    sb.appendLine()
    sb.appendLine(
        "- Fixed strata: `representative128 / threads=1`, `representative128 / threads=4`, and " +
            "`burst32 / threads=4`."
    )
    sb.appendLine(
        "- Each stratum runs four adjacent pairs in `${mongoBatchOptionsQuickOrderSequence.joinToString(" ") { it.id }}` " +
            "order. AB is `current → candidate`; BA is `candidate → current`; every leg is an independent JVM."
    )
    sb.appendLine(
        "- JMH configuration: `${quickMongoBatchOptionsPairedProfile.configSummary()}`; " +
            "JVM args: `${quickMongoBatchOptionsPairedProfile.jvmArgs.joinToString(" ")}`."
    )
    sb.appendLine(
        "- Ratios use paired log geometric means. The descriptive 95% interval uses Student-t with " +
            "`df=${mongoBatchOptionsQuickRounds - 1}` and " +
            "`t=${pairedT95Critical(mongoBatchOptionsQuickRounds)}`."
    )
    sb.appendLine(
        "- Every successful invocation checks all append publishers completed. Outside the measured iteration, " +
            "the benchmark checks Mongo document count equals the exact expected write count; a mismatch or timeout " +
            "fails the leg and prevents a SUCCESS manifest."
    )
    sb.appendLine()
    sb.appendLine("## Evidence")
    sb.appendLine()
    sb.appendLine("- **Source Commit**: `${reference.sourceCommit}`")
    sb.appendLine("- **Source Dirty**: `${reference.sourceDirty}`")
    sb.appendLine("- **JMH Jar SHA-256**: `${reference.jmhJarSha256}`")
    sb.appendLine("- **Run ID**: `${reference.runId}`")
    sb.appendLine("- **Started**: $startedAt")
    sb.appendLine("- **Completed**: $completedAt")
    sb.appendLine("- **Successful Leg Manifests**: ${manifests.size}")
    sb.appendLine(
        "- **Combined Result SHA-256**: `$combinedResultDigest` " +
            "(`SHA-256` over sorted `taskPath=resultSha256` lines)"
    )
    sb.appendLine()
    sb.appendCapturedInfrastructureRuntime(manifests)
    sb.appendLine("### Artifact Evidence")
    sb.appendLine()
    sb.appendLine(
        "| Workload | Threads | Round | Order | Position | Variant | Batch options | Task | Result SHA-256 |"
    )
    sb.appendLine(
        "|----------|--------:|------:|:-----:|---------:|---------|---------------|------|----------------|"
    )
    experiment.legs.forEach { leg ->
        sb.appendLine(
            "| `${leg.trial.stratum.workload.id}` | ${leg.trial.stratum.threads} | ${leg.trial.round} | " +
                "${leg.trial.order.id} | ${leg.trial.position} | ${leg.trial.variant.id} | " +
                "`${leg.trial.batchOptions}` | `${leg.manifest.taskPath}` | `${leg.manifest.resultSha256}` |"
        )
    }
    sb.appendLine()
    sb.appendBenchmarkEnvironment(project.version.toString(), quickMongoBatchOptionsPairedProfile)
    sb.appendInfrastructureRuntime(reference.requiredServices)
    sb.appendLine("## Limitations")
    sb.appendLine()
    sb.appendLine(
        "- Four pairs have low statistical power. The interval is descriptive and this quick run cannot establish " +
            "an optimum or justify changing a public default by itself."
    )
    sb.appendLine(
        "- Closed-loop throughput and its inverse do not expose p50/p95/p99 response latency or coordinated omission."
    )
    sb.appendLine(
        "- Full-success JMH runs can detect exceptions and write-count loss, but partial-failure result isolation is " +
            "proved separately by BatchCoordinator and Mongo integration tests."
    )
    return sb.toString()
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

val verifyBenchmarkInfrastructureManifest = tasks.register("verifyBenchmarkInfrastructureManifest") {
    description = "Verify benchmark run-time container provenance parsing, identity, and stability rules."
    group = "verification"

    doLast {
        val mongoRuntime = DockerContainerRuntime(
            service = "MongoDB",
            label = "Mongo",
            containerName = "wow-benchmark-mongo",
            containerId = "container-id",
            image = "mongo:8.3.4",
            imageId = "sha256:image-id",
            repoDigests = listOf("mongo@sha256:digest"),
            startedAt = "2026-07-26T01:02:03Z",
            running = true,
            connectedAddress = "127.0.0.1",
            publishedPorts = listOf(
                DockerPublishedPortBinding(27017, "tcp", "0.0.0.0", 27017)
            ),
            performanceConfiguration = mapOf("wiredTiger.cacheSizeGB" to "2"),
            composeProject = "wow-benchmarks-mongo",
            composeService = "mongo",
            composeConfigHash = "compose-config-hash",
            composeConfigFiles = "/workspace/wow-benchmarks/docker/compose.mongo.yml",
            configurationSha256 = "configuration-sha256",
        )
        val infrastructureRuntime = BenchmarkInfrastructureRuntime(
            capturedAt = "2026-07-26T01:02:04Z",
            clientLocation = "host JVM",
            dockerServer = "Server=28.0.0 CPUs=8 Memory=16.0 GiB",
            containers = listOf(mongoRuntime),
        )
        val mongoService = BenchmarkRequiredService("MongoDB", "localhost", 27017)
        requireBenchmarkContainerEndpoint(mongoRuntime, mongoService, 27017)
        check(
            runCatching {
                requireBenchmarkContainerEndpoint(
                    mongoRuntime.copy(running = false),
                    mongoService,
                    27017,
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                requireBenchmarkContainerEndpoint(
                    mongoRuntime.copy(
                        publishedPorts = listOf(
                            DockerPublishedPortBinding(27017, "tcp", "0.0.0.0", 27018)
                        )
                    ),
                    mongoService,
                    27017,
                )
            }.exceptionOrNull() is GradleException
        )
        requireBenchmarkContainerEndpoint(
            mongoRuntime.copy(
                connectedAddress = "::1",
                publishedPorts = listOf(
                    DockerPublishedPortBinding(27017, "tcp", "::", 27017)
                ),
            ),
            mongoService,
            27017,
        )
        listOf(
            mongoRuntime.copy(
                connectedAddress = "::1",
                publishedPorts = listOf(
                    DockerPublishedPortBinding(27017, "tcp", "0.0.0.0", 27017)
                ),
            ),
            mongoRuntime.copy(
                connectedAddress = "127.0.0.1",
                publishedPorts = listOf(
                    DockerPublishedPortBinding(27017, "tcp", "::", 27017)
                ),
            ),
            mongoRuntime.copy(
                connectedAddress = "127.0.0.1",
                publishedPorts = listOf(
                    DockerPublishedPortBinding(27017, "tcp", "127.0.0.2", 27017)
                ),
            ),
        ).forEach { mismatchedRuntime ->
            check(
                runCatching {
                    requireBenchmarkContainerEndpoint(mismatchedRuntime, mongoService, 27017)
                }.exceptionOrNull() is GradleException
            )
        }
        check(
            dockerPerformanceConfiguration(
                service = "Elasticsearch",
                command = emptyList(),
                environment = listOf(
                    "ES_JAVA_OPTS=-Xms2g -Xmx2g -Dsecret.token=do-not-persist",
                    "BENCHMARK_PASSWORD=do-not-persist",
                    "cluster.routing.allocation.disk.threshold_enabled=false",
                ),
            ) == mapOf(
                "cluster.routing.allocation.disk.threshold_enabled" to "false",
                "heap.initial" to "2g",
                "heap.maximum" to "2g",
            )
        )
        check(
            dockerPerformanceConfiguration(
                service = "MongoDB",
                command = listOf(
                    "mongod",
                    "--wiredTigerCacheSizeGB",
                    "2",
                    "--keyFile",
                    "do-not-persist",
                ),
                environment = listOf("MONGO_INITDB_ROOT_PASSWORD=do-not-persist"),
            ) == mapOf("wiredTiger.cacheSizeGB" to "2")
        )
        val untrustedMarkdown = markdownCodeOrUnavailable("name` **injection**\nnext")
        check(!untrustedMarkdown.contains('\n'))
        check(untrustedMarkdown.startsWith("`` "))
        val parsed = manifestInfrastructureRuntime(
            container = mapOf("infrastructure" to infrastructureRuntime.toRunSpec()),
            key = "infrastructure",
            source = "verification",
        )
        check(parsed == infrastructureRuntime)
        requireManifestInfrastructureIdentity(
            actual = parsed,
            requiredServices = listOf(BenchmarkRequiredService("MongoDB", "localhost", 27017)),
            source = "verification",
        )
        check(
            runCatching {
                requireManifestInfrastructureIdentity(
                    actual = parsed.copy(
                        containers = listOf(mongoRuntime.copy(composeConfigHash = null))
                    ),
                    requiredServices = listOf(
                        BenchmarkRequiredService("MongoDB", "localhost", 27017)
                    ),
                    source = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        validateBenchmarkInfrastructureRuntimeStability(
            runtimes = listOf(parsed, infrastructureRuntime),
            context = "verification",
        )
        val completionInfrastructureRuntime = infrastructureRuntime.copy(
            capturedAt = "2026-07-26T01:12:04Z"
        )
        validateBenchmarkInfrastructureRuntimeWindow(
            started = infrastructureRuntime,
            completed = completionInfrastructureRuntime,
            requiredServices = listOf(BenchmarkRequiredService("MongoDB", "localhost", 27017)),
            context = "verification",
        )
        val changedConfiguration = infrastructureRuntime.copy(
            capturedAt = "2026-07-26T01:12:04Z",
            containers = listOf(
                mongoRuntime.copy(configurationSha256 = "different-configuration-sha256")
            )
        )
        check(
            runCatching {
                validateBenchmarkInfrastructureRuntimeWindow(
                    started = infrastructureRuntime,
                    completed = changedConfiguration,
                    requiredServices = listOf(
                        BenchmarkRequiredService("MongoDB", "localhost", 27017)
                    ),
                    context = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        listOf(
            mongoRuntime.copy(imageId = "sha256:different-image-id"),
            mongoRuntime.copy(connectedAddress = "127.0.0.2"),
            mongoRuntime.copy(
                publishedPorts = listOf(
                    DockerPublishedPortBinding(27017, "tcp", "127.0.0.1", 27017)
                )
            ),
        ).forEach { changedRuntime ->
            check(
                runCatching {
                    validateBenchmarkInfrastructureRuntimeStability(
                        runtimes = listOf(
                            infrastructureRuntime,
                            infrastructureRuntime.copy(containers = listOf(changedRuntime)),
                        ),
                        context = "verification",
                    )
                }.exceptionOrNull() is GradleException
            )
        }
        val executionIdentity = benchmarkExecutionEnvironmentIdentity(
            javaVersion = "17",
            vmName = "OpenJDK",
            vmVersion = "17.0.1",
            osName = "TestOS",
            osVersion = "1",
            osArch = "aarch64",
            availableProcessors = 8,
            physicalMemoryBytes = 16L * 1024 * 1024 * 1024,
        )
        requireBenchmarkExecutionEnvironmentCompatibility(
            executionIdentity,
            executionIdentity,
            "verification",
        )
        check(
            runCatching {
                requireBenchmarkExecutionEnvironmentCompatibility(
                    executionIdentity,
                    executionIdentity + ("osVersion" to "2"),
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        check(manifestInt(mapOf("value" to 2.0), "value", "verification") == 2)
        check(
            runCatching {
                manifestInt(mapOf("value" to 2.5), "value", "verification")
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                manifestInt(mapOf("value" to Double.NaN), "value", "verification")
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                manifestInt(
                    mapOf("value" to Int.MAX_VALUE.toLong() + 1),
                    "value",
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                validateBenchmarkInfrastructureRuntimeWindow(
                    started = infrastructureRuntime,
                    completed = infrastructureRuntime.copy(
                        capturedAt = "2026-07-26T00:52:04Z"
                    ),
                    requiredServices = listOf(
                        BenchmarkRequiredService("MongoDB", "localhost", 27017)
                    ),
                    context = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        val redisRuntime = mongoRuntime.copy(
            service = "Redis",
            label = "Redis",
            containerName = "wow-benchmark-redis",
            image = "redis:7.4.9-alpine",
            imageId = "sha256:redis-image-id",
            repoDigests = listOf("redis@sha256:digest"),
            connectedAddress = "127.0.0.1",
            publishedPorts = listOf(
                DockerPublishedPortBinding(6379, "tcp", "0.0.0.0", 6379)
            ),
            performanceConfiguration = emptyMap(),
            composeProject = "wow-benchmarks-redis",
            composeService = "redis",
            composeConfigHash = "redis-compose-config-hash",
            composeConfigFiles = "/workspace/wow-benchmarks/docker/compose.redis.yml",
            configurationSha256 = "redis-configuration-sha256",
        )
        val orderedServices = listOf(
            BenchmarkRequiredService("Redis", "localhost", 6379),
            BenchmarkRequiredService("MongoDB", "localhost", 27017),
        )
        requireManifestInfrastructureIdentity(
            actual = infrastructureRuntime.copy(containers = listOf(redisRuntime, mongoRuntime)),
            requiredServices = orderedServices,
            source = "verification",
        )
        check(
            runCatching {
                requireManifestInfrastructureIdentity(
                    actual = infrastructureRuntime.copy(
                        containers = listOf(mongoRuntime, redisRuntime)
                    ),
                    requiredServices = orderedServices,
                    source = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                requireManifestInfrastructureIdentity(
                    actual = infrastructureRuntime.copy(containers = emptyList()),
                    requiredServices = listOf(BenchmarkRequiredService("MongoDB", "localhost", 27017)),
                    source = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        requireManifestInfrastructureIdentity(
            actual = BenchmarkInfrastructureRuntime(
                capturedAt = "2026-07-26T01:02:04Z",
                clientLocation = "host JVM",
                dockerServer = null,
                containers = emptyList(),
            ),
            requiredServices = listOf(BenchmarkRequiredService("MongoDB", "mongo.internal", 27017)),
            source = "verification",
        )
        val incomplete = infrastructureRuntime.toRunSpec().toMutableMap().also { rawInfrastructure ->
            @Suppress("UNCHECKED_CAST")
            val rawContainers = rawInfrastructure.getValue("containers") as List<Map<String, Any?>>
            rawInfrastructure["containers"] = listOf(
                rawContainers.single().toMutableMap().also { rawContainer ->
                    rawContainer.remove("configurationSha256")
                }
            )
        }
        check(
            runCatching {
                manifestInfrastructureRuntime(
                    container = mapOf("infrastructure" to incomplete),
                    key = "infrastructure",
                    source = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
    }
}

val verifyStorageBatchTuningParetoSelection = tasks.register("verifyStorageBatchTuningParetoSelection") {
    description = "Verify deterministic, complete Pareto selection for storage batch confirmation."
    group = "verification"

    doLast {
        fun candidate(
            options: String,
            primaryRepresentativeRatio: Double,
            preferredRefreshSaturatedRatio: Double,
            worstSaturatedRatio: Double,
            worstGuardRatio: Double,
            worstAllocationRatio: Double,
            eligible: Boolean = true,
        ): StorageBatchTuningCandidateSummary {
            val optionMatch = checkNotNull(storageBatchTuningOptionFormat.matchEntire(options))
            return StorageBatchTuningCandidateSummary(
                batchOptions = options,
                maxSize = optionMatch.groupValues[1].toInt(),
                maxDelayMicros = optionMatch.groupValues[2].toLong(),
                primaryRepresentativeRatio = primaryRepresentativeRatio,
                worstSaturatedRatio = worstSaturatedRatio,
                worstGuardRatio = worstGuardRatio,
                worstAllocationRatio = worstAllocationRatio,
                preferredRefreshSaturatedRatio = preferredRefreshSaturatedRatio,
                eligible = eligible,
            )
        }
        val current = candidate("128x1000us", 1.0, 0.99, 0.99, 1.0, 1.0)
        val primaryLeader = candidate("192x150us", 1.14, 0.95, 0.95, 1.14, 1.04)
        val balanced = candidate("256x200us", 1.08, 0.99, 0.99, 1.08, 1.03)
        val saturationLeader = candidate("512x375us", 0.94, 1.0, 1.0, 0.94, 1.03)
        val dominated = candidate("192x250us", 1.02, 0.96, 0.96, 1.02, 1.04)
        val rejected = candidate("1024x4000us", 1.50, 1.0, 1.0, 1.50, 1.50, eligible = false)
        val summaries = listOf(
            dominated,
            current,
            saturationLeader,
            rejected,
            balanced,
            primaryLeader,
        )
        val frontier = storageBatchTuningParetoCandidates(
            summaries = summaries,
            currentOptions = current.batchOptions,
        )
        check(frontier.map(StorageBatchTuningCandidateSummary::batchOptions) == listOf(
            "192x150us",
            "256x200us",
            "512x375us",
        ))
        check(storageBatchTuningCandidateDominates(balanced, dominated))
        check(!storageBatchTuningCandidateDominates(primaryLeader, balanced))
        val orderedFrontier = frontier.map(StorageBatchTuningCandidateSummary::batchOptions)
        val payload = storageBatchTuningFrontierEvidencePayload(
            suite = mongoBatchOptionsTuningSuite.id,
            currentOptions = current.batchOptions,
            orderedFrontier = orderedFrontier,
            sourceCommit = "screening-source-commit",
            jmhJarSha256 = "jmh-jar-sha256",
            benchmarkHarnessSha256 = "benchmark-harness-sha256",
            resultSha256 = "screening-result-sha256",
            manifestSha256 = "screening-manifest-sha256",
        )
        val evidence = StorageBatchTuningFrontierEvidence(
            suite = mongoBatchOptionsTuningSuite.id,
            currentOptions = current.batchOptions,
            orderedFrontier = orderedFrontier,
            sourceCommit = "screening-source-commit",
            jmhJarSha256 = "jmh-jar-sha256",
            benchmarkHarnessSha256 = "benchmark-harness-sha256",
            resultSha256 = "screening-result-sha256",
            manifestSha256 = "screening-manifest-sha256",
            evidenceSha256 = sha256Text(JsonOutput.toJson(payload)),
        )
        val confirmationOptions = listOf(current.batchOptions) + orderedFrontier
        check(
            runCatching {
                validateStorageBatchTuningExecution(
                    suite = mongoBatchOptionsTuningSuite,
                    profile = storageBatchTuningConfirmationProfile.copy(
                        threads = listOf(1),
                        parameters = mapOf(
                            "batchOptions" to confirmationOptions.joinToString(",")
                        ),
                    ),
                )
            }.exceptionOrNull() is GradleException
        )
        requireStorageBatchTuningConfirmationOptions(
            options = confirmationOptions,
            suiteId = mongoBatchOptionsTuningSuite.id,
            currentOptions = current.batchOptions,
            evidence = evidence,
            source = "verification",
        )
        listOf(
            confirmationOptions.dropLast(1),
            confirmationOptions + "1024x1000us",
            confirmationOptions.reversed(),
        ).forEach { invalidOptions ->
            check(
                runCatching {
                    requireStorageBatchTuningConfirmationOptions(
                        options = invalidOptions,
                        suiteId = mongoBatchOptionsTuningSuite.id,
                        currentOptions = current.batchOptions,
                        evidence = evidence,
                        source = "verification",
                    )
                }.exceptionOrNull() is GradleException
            )
        }
        requireStorageBatchTuningEvidenceCompatibility(
            evidence = evidence,
            currentJmhJarSha256 = evidence.jmhJarSha256,
            currentBenchmarkHarnessSha256 = evidence.benchmarkHarnessSha256,
            source = "verification",
        )
        check(
            runCatching {
                requireStorageBatchTuningEvidenceCompatibility(
                    evidence = evidence,
                    currentJmhJarSha256 = "different-jmh-jar",
                    currentBenchmarkHarnessSha256 = evidence.benchmarkHarnessSha256,
                    source = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                requireStorageBatchTuningEvidenceCompatibility(
                    evidence = evidence,
                    currentJmhJarSha256 = evidence.jmhJarSha256,
                    currentBenchmarkHarnessSha256 = "different-harness",
                    source = "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        requireStorageBatchTuningSourceTransition(
            sourceIsHeadAncestor = true,
            changedPaths = setOf("tuning.md", "frontier.json"),
            allowedPaths = setOf("tuning.md", "frontier.json"),
            source = "verification",
        )
        check(
            parseGitNullSeparatedPaths("report\nname.md\u0000frontier.json\u0000") ==
                setOf("report\nname.md", "frontier.json")
        )
        check(
            parseGitNullSeparatedPaths(
                runCommand(
                    listOf("printf", " leading.md\\000trailing .md\\000"),
                    trimOutput = false,
                ).also { output ->
                    check(output.exitCode == 0)
                }.output
            ) == setOf(" leading.md", "trailing .md")
        )
        check(parseGitNullSeparatedPaths("frontier.json") == setOf("frontier.json"))
        requireStorageBatchTuningScanParameters(
            mapOf("batchOptions" to confirmationOptions.joinToString(",")),
            "verification",
        )
        check(
            runCatching {
                requireStorageBatchTuningScanParameters(
                    mapOf(
                        "batchOptions" to confirmationOptions.joinToString(","),
                        "unregisteredParameter" to "value",
                    ),
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        listOf(
            false to setOf("tuning.md"),
            true to setOf("src/main/kotlin/Changed.kt"),
        ).forEach { (isAncestor, changedPaths) ->
            check(
                runCatching {
                    requireStorageBatchTuningSourceTransition(
                        sourceIsHeadAncestor = isAncestor,
                        changedPaths = changedPaths,
                        allowedPaths = setOf("tuning.md", "frontier.json"),
                        source = "verification",
                    )
                }.exceptionOrNull() is GradleException
            )
        }
        val rawResultFile = File(temporaryDir, "screening-result.json").apply {
            writeText("[{\"score\":1}]")
        }
        val rawManifestFile = File(temporaryDir, "screening.manifest.json").apply {
            writeText("{\"status\":\"SUCCESS\"}")
        }
        val artifactPayload = storageBatchTuningFrontierEvidencePayload(
            suite = evidence.suite,
            currentOptions = evidence.currentOptions,
            orderedFrontier = evidence.orderedFrontier,
            sourceCommit = evidence.sourceCommit,
            jmhJarSha256 = evidence.jmhJarSha256,
            benchmarkHarnessSha256 = evidence.benchmarkHarnessSha256,
            resultSha256 = fileSha256(rawResultFile),
            manifestSha256 = fileSha256(rawManifestFile),
        )
        val artifactEvidence = evidence.copy(
            resultSha256 = artifactPayload.getValue("resultSha256") as String,
            manifestSha256 = artifactPayload.getValue("manifestSha256") as String,
            evidenceSha256 = sha256Text(JsonOutput.toJson(artifactPayload)),
        )
        requireStorageBatchTuningArtifactHashes(
            artifactEvidence,
            rawResultFile,
            rawManifestFile,
            "verification",
        )
        rawResultFile.appendText(" ")
        check(
            runCatching {
                requireStorageBatchTuningArtifactHashes(
                    artifactEvidence,
                    rawResultFile,
                    rawManifestFile,
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        rawResultFile.writeText("[{\"score\":1}]")
        rawManifestFile.appendText(" ")
        check(
            runCatching {
                requireStorageBatchTuningArtifactHashes(
                    artifactEvidence,
                    rawResultFile,
                    rawManifestFile,
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        rawManifestFile.delete()
        check(
            runCatching {
                requireStorageBatchTuningArtifactHashes(
                    artifactEvidence,
                    rawResultFile,
                    rawManifestFile,
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
        val evidenceFile = File(temporaryDir, "frontier.json")
        writePrettyJson(evidenceFile, evidence.toRunSpec())
        check(parseStorageBatchTuningFrontierEvidence(evidenceFile) == evidence)
        writePrettyJson(
            evidenceFile,
            evidence.toRunSpec().toMutableMap().also { rawEvidence ->
                rawEvidence["orderedFrontier"] = orderedFrontier.dropLast(1)
            },
        )
        check(
            runCatching {
                parseStorageBatchTuningFrontierEvidence(evidenceFile)
            }.exceptionOrNull() is GradleException
        )
        val tamperedFrontier = orderedFrontier.dropLast(1)
        val tamperedPayload = storageBatchTuningFrontierEvidencePayload(
            suite = evidence.suite,
            currentOptions = evidence.currentOptions,
            orderedFrontier = tamperedFrontier,
            sourceCommit = evidence.sourceCommit,
            jmhJarSha256 = evidence.jmhJarSha256,
            benchmarkHarnessSha256 = evidence.benchmarkHarnessSha256,
            resultSha256 = evidence.resultSha256,
            manifestSha256 = evidence.manifestSha256,
        )
        val rehashedTamperedEvidence = evidence.copy(
            orderedFrontier = tamperedFrontier,
            evidenceSha256 = sha256Text(JsonOutput.toJson(tamperedPayload)),
        )
        writePrettyJson(evidenceFile, rehashedTamperedEvidence.toRunSpec())
        check(parseStorageBatchTuningFrontierEvidence(evidenceFile) == rehashedTamperedEvidence)
        check(
            runCatching {
                requireManifestValue(
                    rehashedTamperedEvidence,
                    evidence,
                    "recomputed screening frontier evidence",
                    "verification",
                )
            }.exceptionOrNull() is GradleException
        )
    }
}

fun requireApproximatelyEqual(
    actual: Double,
    expected: Double,
    tolerance: Double,
    label: String,
) {
    if (!actual.isFinite() || !expected.isFinite() || !tolerance.isFinite() || tolerance < 0.0 ||
        kotlin.math.abs(actual - expected) > tolerance
    ) {
        throw GradleException(
            "$label mismatch: expected $expected ± $tolerance, found $actual."
        )
    }
}

val verifyMongoBatchPairedStatistics = tasks.register("verifyMongoBatchPairedStatistics") {
    description = "Verify paired Mongo append geometric ratios, confidence intervals, and input rules."
    group = "verification"

    doLast {
        val directScores = listOf(
            21824.464088325072,
            22665.57373944818,
            21960.77788353063,
            18777.29501410656,
            19181.048326321983,
            17282.34688914761,
            20952.233671009188,
            21409.070798847537,
        )
        val batchScores = listOf(
            47659.112156572126,
            54280.70485575845,
            49273.90636133233,
            49047.07547170062,
            41358.67718614385,
            34294.41614419429,
            44497.059313479025,
            48948.13027021249,
        )
        val observations = directScores.indices.map { index ->
            val round = index + 1
            MongoBatchPairedObservation(
                threads = 1,
                round = round,
                order = mongoBatchPairedOrder(round),
                directScore = directScores[index],
                batchScore = batchScores[index],
                unit = "ops/s",
            )
        }
        val statistics = calculateMongoBatchPairedStatistics(observations)
        requireApproximatelyEqual(
            statistics.directGeometricMean,
            20428.514273072244,
            1.0e-9,
            "direct geometric mean",
        )
        requireApproximatelyEqual(
            statistics.batchGeometricMean,
            45786.228991770986,
            1.0e-9,
            "batch geometric mean",
        )
        requireApproximatelyEqual(statistics.geometricRatio, 2.2412902073903584, 1.0e-12, "paired ratio")
        requireApproximatelyEqual(statistics.lower95Ratio, 2.091192146065691, 1.0e-12, "paired CI lower")
        requireApproximatelyEqual(statistics.upper95Ratio, 2.4021617540955105, 1.0e-12, "paired CI upper")
        requireApproximatelyEqual(
            statistics.directThenBatchRatio,
            2.1764150928966677,
            1.0e-12,
            "AB ratio",
        )
        requireApproximatelyEqual(
            statistics.batchThenDirectRatio,
            2.3080991351967324,
            1.0e-12,
            "BA ratio",
        )
        check(statistics.pairCount == mongoBatchPairedRounds)
        check(statistics.passingPairCount == mongoBatchPairedRounds)
        check(statistics.verdict == MongoBatchPairedVerdict.PASS)
        check(formatMongoBatchRatio(statistics.geometricRatio) == "2.241290×")
        check(formatMongoBatchGain(statistics.geometricRatio) == "+124.129%")
        check(classifyMongoBatchPairedVerdict(mongoBatchPairedMinimumRatio, 1.2) ==
            MongoBatchPairedVerdict.INCONCLUSIVE)

        val inconclusiveStatistics = calculateMongoBatchPairedStatistics(
            observations.map { observation ->
                observation.copy(batchScore = observation.directScore)
            }
        )
        check(inconclusiveStatistics.verdict == MongoBatchPairedVerdict.INCONCLUSIVE)
        val regressionStatistics = calculateMongoBatchPairedStatistics(
            observations.map { observation ->
                observation.copy(batchScore = observation.directScore / 2.0)
            }
        )
        check(regressionStatistics.verdict == MongoBatchPairedVerdict.REGRESSION)
        val mixedSummary = mongoBatchPairedVerdictSummary(
            listOf(statistics, inconclusiveStatistics.copy(threads = 4))
        )
        check("All measured thread configurations pass" !in mixedSummary)
        check("threads=4: INCONCLUSIVE" in mixedSummary)

        val missingRoundFailure = runCatching {
            calculateMongoBatchPairedStatistics(observations.dropLast(1))
        }.exceptionOrNull()
        check(missingRoundFailure is GradleException)

        val wrongOrderFailure = runCatching {
            calculateMongoBatchPairedStatistics(
                observations.map { observation ->
                    if (observation.round == 2) {
                        observation.copy(order = MongoBatchPairedOrder.DIRECT_THEN_BATCH)
                    } else {
                        observation
                    }
                }
            )
        }.exceptionOrNull()
        check(wrongOrderFailure is GradleException)

        val invalidScoreFailure = runCatching {
            calculateMongoBatchPairedStatistics(
                observations.map { observation ->
                    if (observation.round == 1) {
                        observation.copy(batchScore = Double.NaN)
                    } else {
                        observation
                    }
                }
            )
        }.exceptionOrNull()
        check(invalidScoreFailure is GradleException)
    }
}

val verifyMongoBatchOptionsPairedStatistics = tasks.register("verifyMongoBatchOptionsPairedStatistics") {
    description = "Verify paired Mongo batch-options confidence intervals and default-selection rules."
    group = "verification"

    doLast {
        if (mongoBatchOptionsPairedConfiguredFinalist == null) {
            check(
                runCatching { requireMongoBatchOptionsPairedProtocol() }
                    .exceptionOrNull() is GradleException
            )
        } else {
            requireMongoBatchOptionsPairedProtocol()
        }
        val verifiedFrontier = listOf("192x150us", "256x200us")
        requireMongoBatchOptionsPairedCandidate(
            finalist = "192x150us",
            orderedFrontier = verifiedFrontier,
            multipleForkVerdict = StorageBatchTuningConfirmationVerdict.INCONCLUSIVE,
        )
        listOf(
            Triple("512x250us", StorageBatchTuningConfirmationVerdict.INCONCLUSIVE, "outside frontier"),
            Triple("192x150us", StorageBatchTuningConfirmationVerdict.PASS, "already PASS"),
            Triple("192x150us", StorageBatchTuningConfirmationVerdict.REGRESSION, "already REGRESSION"),
            Triple("", StorageBatchTuningConfirmationVerdict.INCONCLUSIVE, "empty"),
            Triple("128x1000us", StorageBatchTuningConfirmationVerdict.INCONCLUSIVE, "current"),
            Triple(
                "999999999999999999999x150us",
                StorageBatchTuningConfirmationVerdict.INCONCLUSIVE,
                "integer overflow",
            ),
        ).forEach { (finalist, verdict, _) ->
            check(
                runCatching {
                    requireMongoBatchOptionsPairedCandidate(
                        finalist = finalist,
                        orderedFrontier = verifiedFrontier,
                        multipleForkVerdict = verdict,
                    )
                }.exceptionOrNull() is GradleException
            )
        }
        fun observations(
            workload: MongoBatchOptionsPairedWorkload,
            threads: Int,
            throughputRatio: Double,
            allocationRatio: Double = 1.04,
        ): List<MongoBatchOptionsPairedObservation> {
            return (1..mongoBatchOptionsPairedRounds).map { round ->
                val currentScore = 10_000.0 + round
                val currentAllocation = 10_000.0 + round
                MongoBatchOptionsPairedObservation(
                    workload = workload,
                    threads = threads,
                    round = round,
                    order = mongoBatchOptionsPairedOrder(round),
                    currentScore = currentScore,
                    finalistScore = currentScore * throughputRatio,
                    currentAllocation = currentAllocation,
                    finalistAllocation = currentAllocation * allocationRatio,
                    unit = "ops/s",
                )
            }
        }

        val passingRatios = mapOf(
            MongoBatchOptionsPairedWorkload.ISOLATED to 1.50,
            MongoBatchOptionsPairedWorkload.BURST_32 to 1.20,
            MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128 to 1.08,
            MongoBatchOptionsPairedWorkload.SATURATED_512 to 0.99,
        )
        val passingStatistics = MongoBatchOptionsPairedWorkload.entries.flatMap { workload ->
            listOf(1, 4).map { threads ->
                calculateMongoBatchOptionsPairedStratumStatistics(
                    observations(
                        workload = workload,
                        threads = threads,
                        throughputRatio = passingRatios.getValue(workload),
                    )
                )
            }
        }
        check(classifyMongoBatchOptionsPairedExperiment(passingStatistics) == MongoBatchOptionsPairedVerdict.PASS)
        val saturated = passingStatistics.single {
            it.workload == MongoBatchOptionsPairedWorkload.SATURATED_512 && it.threads == 1
        }
        requireApproximatelyEqual(saturated.requiredThroughputRatio, 0.95, 0.0, "saturated throughput gate")
        requireApproximatelyEqual(saturated.equivalentTimeRatio, 1.0 / 0.99, 1.0e-12, "equivalent time ratio")
        requireApproximatelyEqual(
            saturated.equivalentTimeLower95Ratio,
            1.0 / saturated.throughput.upper95Ratio,
            1.0e-12,
            "equivalent time CI lower",
        )
        requireApproximatelyEqual(
            saturated.equivalentTimeUpper95Ratio,
            1.0 / saturated.throughput.lower95Ratio,
            1.0e-12,
            "equivalent time CI upper",
        )
        requireApproximatelyEqual(pairedT95Critical(mongoBatchOptionsPairedRounds), 2.06865761, 1.0e-8, "paired t")
        val analyticMeanLogRatio = kotlin.math.ln(1.08)
        val analyticDeviation = 0.10
        val analyticCurrent = List(mongoBatchOptionsPairedRounds) { index -> 10_000.0 + index }
        val analyticRatios = List(mongoBatchOptionsPairedRounds) { index ->
            kotlin.math.exp(
                analyticMeanLogRatio +
                    if (index < mongoBatchOptionsPairedRounds / 2) analyticDeviation else -analyticDeviation
            )
        }
        val analyticStatistics = calculatePairedMetricStatistics(
            currentValues = analyticCurrent,
            finalistValues = analyticCurrent.zip(analyticRatios) { current, ratio -> current * ratio },
            orders = mongoBatchOptionsPairedOrderSequence,
            context = "analytic non-zero variance",
        )
        val analyticMargin =
            pairedT95Critical(mongoBatchOptionsPairedRounds) *
                analyticDeviation / kotlin.math.sqrt((mongoBatchOptionsPairedRounds - 1).toDouble())
        requireApproximatelyEqual(analyticStatistics.geometricRatio, 1.08, 1.0e-12, "analytic paired ratio")
        requireApproximatelyEqual(
            analyticStatistics.lower95Ratio,
            kotlin.math.exp(analyticMeanLogRatio - analyticMargin),
            1.0e-12,
            "analytic paired CI lower",
        )
        requireApproximatelyEqual(
            analyticStatistics.upper95Ratio,
            kotlin.math.exp(analyticMeanLogRatio + analyticMargin),
            1.0e-12,
            "analytic paired CI upper",
        )
        val compliantOrderEffectRatios = mongoBatchOptionsPairedOrderSequence.map { order ->
            when (order) {
                PairedExperimentOrder.AB -> 1.02
                PairedExperimentOrder.BA -> 0.98
            }
        }
        val rawOrderConfoundedCorrelation = lagOneCorrelation(
            values = compliantOrderEffectRatios.map { ratio -> kotlin.math.ln(ratio) },
            context = "raw order-confounded log ratios",
        )
        check(
            kotlin.math.abs(rawOrderConfoundedCorrelation) >
                mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation
        )
        val orderAdjustedStatistics = calculatePairedMetricStatistics(
            currentValues = analyticCurrent,
            finalistValues = analyticCurrent.zip(compliantOrderEffectRatios) { current, ratio -> current * ratio },
            orders = mongoBatchOptionsPairedOrderSequence,
            context = "order-adjusted residual diagnostic",
        )
        requireApproximatelyEqual(
            orderAdjustedStatistics.lagOneResidualCorrelation,
            0.0,
            0.0,
            "order-adjusted lag-one residual correlation",
        )
        check(
            orderAdjustedStatistics.diagnosticsPass(
                mongoBatchOptionsPairedMaximumOrderEffectRatio,
                mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation,
            )
        )
        check(
            mongoBatchOptionsPairedTrials.size ==
                MongoBatchOptionsPairedWorkload.entries.size *
                mongoBatchOptionsPairedThreads.size *
                mongoBatchOptionsPairedRounds *
                MongoBatchOptionsPairedVariant.entries.size
        )
        check(
            benchmarkMongoBatchOptionsPairedStrata.size ==
                MongoBatchOptionsPairedWorkload.entries.size * mongoBatchOptionsPairedThreads.size
        )
        check(
            mongoBatchOptionsPairedTrials.map { trial -> trial.taskSpec.taskName }.distinct().size ==
                mongoBatchOptionsPairedTrials.size
        )
        check(
            mongoBatchOptionsPairedTrials
                .map { trial -> trial.threads to trial.taskSpec.suite.resultFileName }
                .distinct()
                .size ==
                mongoBatchOptionsPairedTrials.size
        )
        check(
            MongoBatchOptionsPairedVariant.CURRENT.batchOptions == mongoBatchOptionsPairedCurrent &&
                MongoBatchOptionsPairedVariant.FINALIST.batchOptions == mongoBatchOptionsPairedFinalist
        )
        check(mongoBatchOptionsPairedOrderSequence.size == mongoBatchOptionsPairedRounds)
        check(
            mongoBatchOptionsPairedOrderSequence.count { order -> order == PairedExperimentOrder.AB } ==
                mongoBatchOptionsPairedRounds / 2
        )
        check(
            mongoBatchOptionsPairedOrderSequence.count { order -> order == PairedExperimentOrder.BA } ==
                mongoBatchOptionsPairedRounds / 2
        )
        check(mongoBatchOptionsPairedTrials.all { trial -> trial.taskSpec.suite.requiresCleanSource })
        check(mongoBatchOptionsPairedTrials.all { trial -> trial.taskSpec.profile.includeGcProfiler })
        check(mongoBatchOptionsPairedTrials.all { trial -> trial.taskSpec.profile.benchmarkModes == listOf("thrpt") })
        check(
            mongoBatchOptionsPairedTrials.all { trial ->
                trial.taskSpec.profile.parameters["batchOptions"] == trial.variant.batchOptions
            }
        )
        check(
            mongoBatchOptionsPairedTrials.all { trial ->
                trial.taskSpec.suite.runMetadata["protocolVersion"] ==
                    mongoBatchOptionsPairedProtocolVersion &&
                    trial.taskSpec.suite.runMetadata["currentBatchOptions"] ==
                    mongoBatchOptionsPairedCurrent &&
                    trial.taskSpec.suite.runMetadata["finalistBatchOptions"] ==
                    mongoBatchOptionsPairedFinalist &&
                    trial.taskSpec.suite.runMetadata["batchOptions"] == trial.variant.batchOptions
            }
        )
        check(
            mongoBatchOptionsPairedTrials
                .groupBy { trial -> Triple(trial.workload, trial.threads, trial.round) }
                .values
                .all { roundTrials ->
                    roundTrials.sortedBy(MongoBatchOptionsPairedTrialSpec::position)
                        .map(MongoBatchOptionsPairedTrialSpec::variant) ==
                        roundTrials.first().order.variants()
                }
        )

        val representativeIndex = passingStatistics.indexOfFirst {
            it.workload == MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128 && it.threads == 1
        }
        val representative = passingStatistics[representativeIndex]
        val inconclusiveStatistics = passingStatistics.toMutableList().apply {
            this[representativeIndex] = representative.copy(
                throughput = representative.throughput.copy(
                    geometricRatio = 1.03,
                    lower95Ratio = 0.98,
                    upper95Ratio = 1.08,
                )
            )
        }
        check(
            classifyMongoBatchOptionsPairedExperiment(inconclusiveStatistics) ==
                MongoBatchOptionsPairedVerdict.INCONCLUSIVE
        )
        val noBenefitStatistics = passingStatistics.toMutableList().apply {
            this[representativeIndex] = representative.copy(
                throughput = representative.throughput.copy(
                    geometricRatio = 0.97,
                    lower95Ratio = 0.95,
                    upper95Ratio = 0.99,
                )
            )
        }
        check(
            classifyMongoBatchOptionsPairedExperiment(noBenefitStatistics) ==
                MongoBatchOptionsPairedVerdict.NO_BENEFIT
        )
        val regressionStatistics = passingStatistics.toMutableList().apply {
            val burstIndex = indexOfFirst {
                it.workload == MongoBatchOptionsPairedWorkload.BURST_32 && it.threads == 4
            }
            val burst = this[burstIndex]
            this[burstIndex] = burst.copy(
                throughput = burst.throughput.copy(
                    geometricRatio = 0.85,
                    lower95Ratio = 0.82,
                    upper95Ratio = 0.88,
                )
            )
        }
        check(
            classifyMongoBatchOptionsPairedExperiment(regressionStatistics) ==
                MongoBatchOptionsPairedVerdict.REGRESSION
        )
        val allocationRegressionStatistics = passingStatistics.toMutableList().apply {
            val isolatedIndex = indexOfFirst {
                it.workload == MongoBatchOptionsPairedWorkload.ISOLATED && it.threads == 1
            }
            val isolated = this[isolatedIndex]
            this[isolatedIndex] = isolated.copy(
                allocation = isolated.allocation.copy(
                    geometricRatio = 1.12,
                    lower95Ratio = 1.11,
                    upper95Ratio = 1.13,
                )
            )
        }
        check(
            classifyMongoBatchOptionsPairedExperiment(allocationRegressionStatistics) ==
                MongoBatchOptionsPairedVerdict.REGRESSION
        )
        val exactSafetyBoundary = saturated.copy(
            throughput = saturated.throughput.copy(
                lower95Ratio = saturated.requiredThroughputRatio,
                upper95Ratio = 1.02,
            ),
            allocation = saturated.allocation.copy(
                lower95Ratio = 1.0,
                upper95Ratio = mongoBatchOptionsPairedAllocationMaximumRatio,
            ),
        )
        check(exactSafetyBoundary.safetyVerdict() == MongoBatchOptionsPairedVerdict.PASS)
        check(
            exactSafetyBoundary.copy(
                throughput = exactSafetyBoundary.throughput.copy(
                    lower95Ratio = 0.90,
                    upper95Ratio = exactSafetyBoundary.requiredThroughputRatio,
                )
            ).safetyVerdict() == MongoBatchOptionsPairedVerdict.INCONCLUSIVE
        )
        check(
            exactSafetyBoundary.copy(
                allocation = exactSafetyBoundary.allocation.copy(
                    lower95Ratio = mongoBatchOptionsPairedAllocationMaximumRatio,
                    upper95Ratio = 1.12,
                )
            ).safetyVerdict() == MongoBatchOptionsPairedVerdict.INCONCLUSIVE
        )
        val diagnosticFailureStatistics = passingStatistics.toMutableList().apply {
            this[representativeIndex] = representative.copy(
                throughput = representative.throughput.copy(
                    lagOneResidualCorrelation =
                        mongoBatchOptionsPairedMaximumAbsoluteLagOneResidualCorrelation + 0.01
                )
            )
        }
        check(
            classifyMongoBatchOptionsPairedExperiment(diagnosticFailureStatistics) ==
                MongoBatchOptionsPairedVerdict.INCONCLUSIVE
        )
        val primaryLowerBoundaryStatistics = passingStatistics.toMutableList().apply {
            this[representativeIndex] = representative.copy(
                throughput = representative.throughput.copy(lower95Ratio = 1.0)
            )
        }
        check(
            classifyMongoBatchOptionsPairedExperiment(primaryLowerBoundaryStatistics) ==
                MongoBatchOptionsPairedVerdict.INCONCLUSIVE
        )
        val primaryUpperBoundaryStatistics = passingStatistics.toMutableList().apply {
            this[representativeIndex] = representative.copy(
                throughput = representative.throughput.copy(
                    geometricRatio = 0.97,
                    lower95Ratio = 0.95,
                    upper95Ratio = 1.0,
                )
            )
        }
        check(
            classifyMongoBatchOptionsPairedExperiment(primaryUpperBoundaryStatistics) ==
                MongoBatchOptionsPairedVerdict.NO_BENEFIT
        )
        check(
            runCatching {
                classifyMongoBatchOptionsPairedExperiment(passingStatistics.dropLast(1))
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                classifyMongoBatchOptionsPairedExperiment(passingStatistics + passingStatistics.first())
            }.exceptionOrNull() is GradleException
        )
        val validObservations = observations(
            workload = MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128,
            threads = 1,
            throughputRatio = 1.08,
        )
        check(
            runCatching {
                calculateMongoBatchOptionsPairedStratumStatistics(validObservations.dropLast(1))
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                calculateMongoBatchOptionsPairedStratumStatistics(
                    validObservations.map { observation ->
                        if (observation.round == 2) {
                            observation.copy(order = PairedExperimentOrder.AB)
                        } else {
                            observation
                        }
                    }
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                calculateMongoBatchOptionsPairedStratumStatistics(
                    validObservations.map { observation ->
                        if (observation.round == 1) {
                            observation.copy(finalistAllocation = Double.NaN)
                        } else {
                            observation
                        }
                    }
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                calculatePairedMetricStatistics(
                    currentValues = List(mongoBatchOptionsPairedRounds) { Double.MIN_VALUE },
                    finalistValues = List(mongoBatchOptionsPairedRounds) { Double.MAX_VALUE },
                    orders = mongoBatchOptionsPairedOrderSequence,
                    context = "overflow ratio",
                )
            }.exceptionOrNull() is GradleException
        )
        val evidenceStart = Instant.parse("2026-07-26T00:00:00Z")
        validateNonOverlappingBenchmarkEvidenceWindows(
            windows = listOf(
                BenchmarkEvidenceWindow("first", evidenceStart, evidenceStart.plusSeconds(10)),
                BenchmarkEvidenceWindow(
                    "second",
                    evidenceStart.plusSeconds(10),
                    evidenceStart.plusSeconds(20),
                ),
            ),
            context = "non-overlapping evidence test",
        )
        check(
            runCatching {
                validateNonOverlappingBenchmarkEvidenceWindows(
                    windows = listOf(
                        BenchmarkEvidenceWindow("first", evidenceStart, evidenceStart.plusSeconds(11)),
                        BenchmarkEvidenceWindow(
                            "second",
                            evidenceStart.plusSeconds(10),
                            evidenceStart.plusSeconds(20),
                        ),
                    ),
                    context = "overlapping evidence test",
                )
            }.exceptionOrNull() is GradleException
        )
        check(runCatching { pairedT95Critical(16) }.exceptionOrNull() is GradleException)
    }
}

val verifyMongoBatchOptionsQuickProtocol = tasks.register("verifyMongoBatchOptionsQuickProtocol") {
    description = "Verify the fixed quick Mongo options and candidate E2E engineering protocols."
    group = "verification"

    doLast {
        check(mongoBatchQuickCurrentOptions == "128x1000us")
        check(mongoBatchQuickCandidateOptions == "192x250us")
        check(mongoBatchQuickCurrentOptions == mongoCurrentStorageBatchOptions)
        check(
            mongoBatchOptionsQuickStrata ==
                listOf(
                    MongoBatchOptionsQuickStratum(
                        MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128,
                        1,
                    ),
                    MongoBatchOptionsQuickStratum(
                        MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128,
                        4,
                    ),
                    MongoBatchOptionsQuickStratum(
                        MongoBatchOptionsPairedWorkload.BURST_32,
                        4,
                    ),
                )
        )
        check(
            mongoBatchOptionsQuickOrderSequence ==
                listOf(
                    PairedExperimentOrder.AB,
                    PairedExperimentOrder.BA,
                    PairedExperimentOrder.BA,
                    PairedExperimentOrder.AB,
                )
        )
        check(mongoBatchOptionsQuickRounds == 4)
        check(mongoBatchOptionsQuickTrials.size == 24)
        check(
            mongoBatchOptionsQuickTrials.count {
                it.variant == MongoBatchOptionsPairedVariant.CURRENT
            } == 12
        )
        check(
            mongoBatchOptionsQuickTrials.count {
                it.variant == MongoBatchOptionsPairedVariant.FINALIST
            } == 12
        )
        check(
            mongoBatchOptionsQuickTrials.map { it.taskSpec.taskName }.distinct().size ==
                mongoBatchOptionsQuickTrials.size
        )
        check(
            mongoBatchOptionsQuickTrials.map { it.taskSpec.suite.resultFileName }.distinct().size ==
                mongoBatchOptionsQuickTrials.size
        )
        check(
            mongoBatchOptionsQuickTrials
                .groupBy { Triple(it.stratum.workload, it.stratum.threads, it.round) }
                .values
                .all { roundTrials ->
                    roundTrials.sortedBy(MongoBatchOptionsQuickTrialSpec::position)
                        .map(MongoBatchOptionsQuickTrialSpec::variant) ==
                        roundTrials.first().order.variants()
                }
        )
        check(mongoBatchOptionsQuickTrials.all { it.taskSpec.suite.requiresCleanSource })
        check(
            mongoBatchOptionsQuickTrials.all {
                it.taskSpec.suite.id == "mongo-batch-options-quick-engineering" &&
                    it.taskSpec.profile.id == "quick-mongo-options-paired" &&
                    it.taskSpec.profile.warmupIterations == 1 &&
                    it.taskSpec.profile.warmupTime == "2s" &&
                    it.taskSpec.profile.measurementIterations == 1 &&
                    it.taskSpec.profile.measurementTime == "3s" &&
                    it.taskSpec.profile.forks == 1 &&
                    it.taskSpec.profile.benchmarkModes == listOf("thrpt") &&
                    it.taskSpec.profile.includeGcProfiler &&
                    !it.taskSpec.profile.includeAsyncProfiler &&
                    it.taskSpec.profile.parameters["batchOptions"] == it.batchOptions &&
                    it.taskSpec.suite.runMetadata["formalProtocol"] == "false" &&
                    it.taskSpec.suite.runMetadata["correctnessCheck"] ==
                    "completion-count-and-iteration-document-count"
            }
        )
        check(quickMongoBatchOptionsPairedProfile.id != pairedMongoBatchOptionsProfile.id)
        check(
            mongoBatchOptionsQuickTrials.none {
                it.taskSpec.suite.id == "mongo-batch-options-paired-confirmation"
            }
        )
        requireApproximatelyEqual(
            pairedT95Critical(mongoBatchOptionsQuickRounds),
            3.182446305,
            1.0e-9,
            "quick paired t",
        )

        fun quickObservations(
            workload: MongoBatchOptionsPairedWorkload,
            threads: Int,
        ): List<MongoBatchOptionsPairedObservation> {
            return (1..mongoBatchOptionsQuickRounds).map { round ->
                val current = 10_000.0 + round
                MongoBatchOptionsPairedObservation(
                    workload = workload,
                    threads = threads,
                    round = round,
                    order = mongoBatchOptionsQuickOrder(round),
                    currentScore = current,
                    finalistScore = current * 1.20,
                    currentAllocation = current,
                    finalistAllocation = current * 1.05,
                    unit = "ops/s",
                )
            }
        }

        val quickStatistics = calculateMongoBatchOptionsQuickStratumStatistics(
            quickObservations(MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128, 1)
        )
        check(quickStatistics.pairCount == 4)
        requireApproximatelyEqual(
            quickStatistics.throughput.geometricRatio,
            1.20,
            1.0e-12,
            "quick paired throughput ratio",
        )
        requireApproximatelyEqual(
            quickStatistics.allocation.geometricRatio,
            1.05,
            1.0e-12,
            "quick paired allocation ratio",
        )
        check(
            runCatching {
                calculateMongoBatchOptionsQuickStratumStatistics(
                    quickObservations(
                        MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128,
                        1,
                    ).dropLast(1)
                )
            }.exceptionOrNull() is GradleException
        )
        check(
            runCatching {
                calculateMongoBatchOptionsQuickStratumStatistics(
                    quickObservations(
                        MongoBatchOptionsPairedWorkload.REPRESENTATIVE_128,
                        1,
                    ).map { observation ->
                        if (observation.round == 2) {
                            observation.copy(order = PairedExperimentOrder.AB)
                        } else {
                            observation
                        }
                    }
                )
            }.exceptionOrNull() is GradleException
        )

        check(quickMongoBatchCandidateE2ESuite.id == "mongo-batch-append-quick-engineering")
        check(quickMongoBatchCandidateE2ESuite.id != mongoBatchAppendSuite.id)
        check(quickMongoBatchCandidateE2ESuite.requiresCleanSource)
        check(quickMongoBatchCandidateE2EProfile.id == "quick-mongo-candidate-e2e")
        check(quickMongoBatchCandidateE2EProfile.warmupIterations == 1)
        check(quickMongoBatchCandidateE2EProfile.warmupTime == "2s")
        check(quickMongoBatchCandidateE2EProfile.measurementIterations == 1)
        check(quickMongoBatchCandidateE2EProfile.measurementTime == "3s")
        check(quickMongoBatchCandidateE2EProfile.forks == 1)
        check(quickMongoBatchCandidateE2EProfile.threads == listOf(1, 4))
        check(quickMongoBatchCandidateE2EProfile.benchmarkModes == listOf("thrpt", "avgt"))
        check(quickMongoBatchCandidateE2EProfile.includeGcProfiler)
        check(
            quickMongoBatchCandidateE2EProfile.parameters ==
                mapOf("batchOptions" to mongoBatchQuickCandidateOptions)
        )
        check(
            quickMongoBatchCandidateE2ESuite.runMetadata["correctnessCheck"] ==
                "completion-count-and-iteration-document-count"
        )
        check(
            quickMongoBatchCandidateE2EMatrix.fixedParameters ==
                quickMongoBatchCandidateE2EProfile.parameters
        )

        check(
            quickMongoBatchCoordinatorConcurrencySuite.id ==
                "mongo-batch-coordinator-concurrency-quick-engineering"
        )
        check(!quickMongoBatchCoordinatorConcurrencySuite.requiresCleanSource)
        check(quickMongoBatchCoordinatorConcurrencyProfile.warmupIterations == 1)
        check(quickMongoBatchCoordinatorConcurrencyProfile.warmupTime == "2s")
        check(quickMongoBatchCoordinatorConcurrencyProfile.measurementIterations == 1)
        check(quickMongoBatchCoordinatorConcurrencyProfile.measurementTime == "3s")
        check(quickMongoBatchCoordinatorConcurrencyProfile.forks == 1)
        check(quickMongoBatchCoordinatorConcurrencyProfile.threads == listOf(4))
        check(
            quickMongoBatchCoordinatorConcurrencyProfile.benchmarkModes ==
                listOf("thrpt", "avgt")
        )
        check(quickMongoBatchCoordinatorConcurrencyProfile.includeGcProfiler)
        check(
            quickMongoBatchCoordinatorConcurrencyProfile.parameters == linkedMapOf(
                "batchOptions" to mongoBatchQuickCandidateOptions,
                "coordinatorLanes" to "1,2,4",
            )
        )
        check(
            quickMongoBatchCoordinatorConcurrencySuite.runMetadata["implementation"] ==
                "single-production-keyed-coordinator"
        )
        check(
            quickMongoBatchCoordinatorConcurrencySuite.runMetadata["productionDefaultChanged"] ==
                "false"
        )
        check(
            quickMongoBatchCoordinatorConcurrencyMatrix.parameterDimensions ==
                mapOf(
                    "coordinatorLanes" to
                        quickMongoBatchCoordinatorConcurrencyLanes.map(Int::toString)
                )
        )
        check(
            quickMongoBatchCoordinatorConcurrencyComparison.parameterValues ==
                quickMongoBatchCoordinatorConcurrencyLanes.map(Int::toString)
        )
    }
}

val verifyMongoSnapshotBatchProtocol = tasks.register("verifyMongoSnapshotBatchProtocol") {
    description = "Verify the quick Mongo SnapshotStore batch-save benchmark protocol."
    group = "verification"

    doLast {
        check(mongoSnapshotBatchSaveSuite.id == "mongo-snapshot-batch-save")
        check(mongoSnapshotBatchSaveSuite.requiresCleanSource)
        check(
            mongoSnapshotBatchSaveSuite.includeClasses ==
                listOf(
                    "me.ahoo.wow.benchmark.infrastructure.mongo.MongoSnapshotStoreSaveBenchmark"
                )
        )
        check(
            mongoSnapshotBatchSaveSuite.runMetadata["correctnessCheck"] ==
                "completion-count-and-iteration-document-count"
        )
        check(mongoSnapshotBatchSaveSuite.runMetadata["operationsPerInvocation"] == "128")
        check(quickMongoSnapshotBatchSaveProfile.id == "quick-mongo-snapshot-batch-save")
        check(quickMongoSnapshotBatchSaveProfile.warmupIterations == 1)
        check(quickMongoSnapshotBatchSaveProfile.warmupTime == "2s")
        check(quickMongoSnapshotBatchSaveProfile.measurementIterations == 2)
        check(quickMongoSnapshotBatchSaveProfile.measurementTime == "3s")
        check(quickMongoSnapshotBatchSaveProfile.forks == 1)
        check(quickMongoSnapshotBatchSaveProfile.threads.isNotEmpty())
        check(quickMongoSnapshotBatchSaveProfile.threads.all { it > 0 })
        check(
            quickMongoSnapshotBatchSaveProfile.benchmarkModes ==
                listOf("thrpt", "avgt")
        )
        check(quickMongoSnapshotBatchSaveProfile.includeGcProfiler)
        check(
            quickMongoSnapshotBatchSaveProfile.parameters ==
                mapOf("batchOptions" to mongoBatchQuickCurrentOptions)
        )
        check(quickMongoSnapshotBatchSaveMatrix.methods == quickMongoSnapshotBatchSaveMethods)
        check(
            quickMongoSnapshotBatchSaveMatrix.threads ==
                quickMongoSnapshotBatchSaveProfile.threads.toSet()
        )
        check(
            quickMongoSnapshotBatchSaveMatrix.fixedParameters ==
                quickMongoSnapshotBatchSaveProfile.parameters
        )
        check(
            quickMongoSnapshotBatchSaveTaskSpec.taskName ==
                "benchmarkQuickMongoSnapshotBatchSave"
        )
    }
}

val verifyBatchRegenerateAggregateSnapshotProtocol = tasks.register(
    "verifyBatchRegenerateAggregateSnapshotProtocol"
) {
    description = "Verify the batch aggregate snapshot regeneration benchmark protocol."
    group = "verification"

    doLast {
        check(batchRegenerateAggregateSnapshotSuite.id == "batch-regenerate-aggregate-snapshot")
        check(
            batchRegenerateAggregateSnapshotSuite.includeClasses ==
                listOf(
                    "me.ahoo.wow.benchmark.infrastructure.snapshot." +
                        "BatchRegenerateAggregateSnapshotBenchmark"
                )
        )
        check(
            batchRegenerateAggregateSnapshotSuite.requiredServices.map(BenchmarkRequiredService::service)
                .toSet() == setOf("MongoDB", "Elasticsearch")
        )
        check(
            batchRegenerateAggregateSnapshotSuite.runMetadata["correctnessCheck"] ==
                "processed-count-and-snapshot-document-count-and-version"
        )
        check(
            batchRegenerateAggregateSnapshotSuite.runMetadata["threadPartitioning"] ==
                "thread-indexed-aggregate-batches"
        )
        check(batchRegenerateAggregateSnapshotSuite.runMetadata["laneCount"] == "1,2,4")
        check(batchRegenerateAggregateSnapshotSuite.runMetadata["aggregatesPerInvocation"] == "128")
        check(batchRegenerateAggregateSnapshotSuite.runMetadata["eventsPerAggregate"] == "10")
        check(quickBatchRegenerateAggregateSnapshotProfile.id == "quick-batch-regenerate-aggregate-snapshot")
        check(quickBatchRegenerateAggregateSnapshotProfile.warmupIterations == 1)
        check(quickBatchRegenerateAggregateSnapshotProfile.warmupTime == "2s")
        check(quickBatchRegenerateAggregateSnapshotProfile.measurementIterations == 2)
        check(quickBatchRegenerateAggregateSnapshotProfile.measurementTime == "3s")
        check(quickBatchRegenerateAggregateSnapshotProfile.forks == 1)
        check(quickBatchRegenerateAggregateSnapshotProfile.threads.isNotEmpty())
        check(quickBatchRegenerateAggregateSnapshotProfile.threads.all { it > 0 })
        check(
            quickBatchRegenerateAggregateSnapshotProfile.benchmarkModes ==
                listOf("thrpt", "avgt")
        )
        check(quickBatchRegenerateAggregateSnapshotProfile.includeGcProfiler)
        check(
            quickBatchRegenerateAggregateSnapshotProfile.parameters ==
                mapOf(
                    "batchOptions" to mongoBatchQuickCurrentOptions,
                    "laneCount" to "1,2,4",
                )
        )
        check(
            quickBatchRegenerateAggregateSnapshotMatrix.methods ==
                quickBatchRegenerateAggregateSnapshotMethods
        )
        check(
            quickBatchRegenerateAggregateSnapshotMatrix.threads ==
                quickBatchRegenerateAggregateSnapshotProfile.threads.toSet()
        )
        check(
            quickBatchRegenerateAggregateSnapshotMatrix.fixedParameters ==
                mapOf("batchOptions" to mongoBatchQuickCurrentOptions)
        )
        check(
            quickBatchRegenerateAggregateSnapshotMatrix.parameterDimensions ==
                mapOf("laneCount" to listOf("1", "2", "4"))
        )
        check(
            quickBatchRegenerateAggregateSnapshotTaskSpec.taskName ==
                "benchmarkQuickBatchRegenerateAggregateSnapshot"
        )
    }
}

tasks.named("check") {
    dependsOn(verifyBenchmarkRequiredServiceManifest)
    dependsOn(verifyBenchmarkInfrastructureManifest)
    dependsOn(verifyStorageBatchTuningParetoSelection)
    dependsOn(verifyMongoBatchPairedStatistics)
    dependsOn(verifyMongoBatchOptionsPairedStatistics)
    dependsOn(verifyMongoBatchOptionsQuickProtocol)
    dependsOn(verifyMongoSnapshotBatchProtocol)
    dependsOn(verifyBatchRegenerateAggregateSnapshotProtocol)
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
    appendLine("- Average-time results are automatically scaled to `ns/op`, `µs/op`, `ms/op`, or `s/op`.")
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
            sb.appendLine(
                "- **threads=${resultFile.threads} Result File**: " +
                    markdownCodeOrUnavailable(benchmarkReportPath(file))
            )
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
    requireCleanSource: Boolean = false,
    validateRows: (List<ParsedBenchmarkResult>) -> Unit = {},
    appendBeforeResults: StringBuilder.(List<ParsedBenchmarkResult>) -> Unit = {},
): String {
    val groupReport = parseBenchmarkGroup(JsonSlurper(), group)
    if (groupReport.rows.isEmpty()) {
        throw GradleException(
            "No benchmark rows were available for ${group.suite.displayName}. " +
            "Run ${group.taskSpec.taskName} first."
        )
    }
    if (requireCleanSource && groupReport.manifests.any(ParsedBenchmarkRunManifest::sourceDirty)) {
        throw GradleException(
            "${group.suite.displayName}/${group.profile.id} report requires sourceDirty=false. " +
                "Rerun ${group.taskSpec.taskName} from a clean source tree."
        )
    }
    validateRows(groupReport.rows)
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
        sb.appendInfrastructureRuntime(group.suite.requiredServices)
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
        sb.appendLine(
            "- **threads=${resultFile.threads} Result File**: " +
                markdownCodeOrUnavailable(benchmarkReportPath(file))
        )
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

tasks.register("generateMongoBatchAppendBenchmarkReport") {
    description = "Generate the quick Mongo EventStore batch append comparison report."
    group = "benchmark"
    mustRunAfter("benchmarkQuickMongoBatchAppend")
    outputs.file(mongoBatchAppendReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickMongoBatchAppendTaskSpec),
            title = "Quick Mongo EventStore Batch Append Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickMongoBatchAppend " +
                ":wow-benchmarks:generateMongoBatchAppendBenchmarkReport " +
                "-PbenchmarkQuickMongoBatchThreads=${quickMongoBatchAppendProfile.threads.joinToString(",")} " +
                "--no-parallel",
            description = "This Quick experiment compares the EventStore insertOne path, native unordered " +
                "insertMany, and transparent coordinated batching against the same MongoDB service. " +
                "It is directional local evidence rather than a cross-machine capacity claim.",
            includeInfrastructureRuntime = true,
            appendBeforeResults = { rows -> appendMongoBatchAppendComparisons(rows) },
        )

        val outputFile = mongoBatchAppendReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Mongo batch append benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateMongoSnapshotBatchSaveBenchmarkReport") {
    description = "Generate the quick Mongo SnapshotStore batch-save comparison report."
    group = "benchmark"
    mustRunAfter("benchmarkQuickMongoSnapshotBatchSave")
    outputs.file(mongoSnapshotBatchSaveReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickMongoSnapshotBatchSaveTaskSpec),
            title = "Quick Mongo SnapshotStore Batch Save Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickMongoSnapshotBatchSave " +
                ":wow-benchmarks:generateMongoSnapshotBatchSaveBenchmarkReport " +
                "-PbenchmarkQuickMongoSnapshotBatchThreads=" +
                "${quickMongoSnapshotBatchSaveProfile.threads.joinToString(",")} " +
                "--no-parallel --no-daemon",
            description = "This bounded engineering experiment compares version-guarded SnapshotStore " +
                "updateOne, native unordered bulkWrite, and transparent coordinated batching against the " +
                "same MongoDB service. Each invocation saves 128 independent aggregate snapshots; throughput " +
                "and average time are normalized per snapshot. It is directional local evidence rather than " +
                "a cross-machine capacity claim.",
            includeInfrastructureRuntime = true,
            requireCleanSource = true,
            validateRows = ::validateQuickMongoSnapshotBatchSaveRows,
            appendBeforeResults = { rows -> appendMongoSnapshotBatchSaveComparisons(rows) },
        )

        val outputFile = mongoSnapshotBatchSaveReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Mongo SnapshotStore batch-save benchmark report generated: ${outputFile.absolutePath}"
        )
    }
}

tasks.register("generateBatchRegenerateAggregateSnapshotBenchmarkReport") {
    description = "Generate the quick batch aggregate snapshot regeneration report."
    group = "benchmark"
    mustRunAfter("benchmarkQuickBatchRegenerateAggregateSnapshot")
    outputs.file(batchRegenerateAggregateSnapshotReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickBatchRegenerateAggregateSnapshotTaskSpec),
            title = "Quick Batch Regenerate Aggregate Snapshot Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickBatchRegenerateAggregateSnapshot " +
                ":wow-benchmarks:generateBatchRegenerateAggregateSnapshotBenchmarkReport " +
                "-PbenchmarkQuickBatchRegenerateSnapshotThreads=" +
                "${quickBatchRegenerateAggregateSnapshotProfile.threads.joinToString(",")} " +
                "--no-parallel --no-daemon",
            description = "This directional infrastructure benchmark measures batch aggregate snapshot " +
                "regeneration across MongoEventStore and ElasticsearchSnapshotStore. Each invocation scans " +
                "128 aggregates, replays 10 events per aggregate, and compares the direct and batched " +
                "Elasticsearch snapshot save paths. At multiple JMH threads, each worker uses a distinct " +
                "aggregate partition. It is local evidence rather than a production capacity claim.",
            includeInfrastructureRuntime = true,
            validateRows = ::validateQuickBatchRegenerateAggregateSnapshotRows,
            appendBeforeResults = { rows ->
                appendBatchRegenerateAggregateSnapshotComparisons(rows)
            },
        )

        val outputFile = batchRegenerateAggregateSnapshotReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Batch aggregate snapshot regeneration benchmark report generated: ${outputFile.absolutePath}"
        )
    }
}

tasks.register("generateQuickMongoBatchAppendCandidateE2EReport") {
    description = "Generate the quick Mongo 192x250us three-layer E2E comparison report."
    group = "benchmark"
    mustRunAfter("benchmarkQuickMongoBatchAppendCandidateE2E")
    outputs.file(quickMongoBatchCandidateE2EReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickMongoBatchCandidateE2ETaskSpec),
            title = "Quick Mongo Batch Candidate E2E Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickMongoBatchAppendCandidateE2E " +
                ":wow-benchmarks:generateQuickMongoBatchAppendCandidateE2EReport " +
                "--no-parallel --no-daemon",
            description = "This bounded engineering experiment compares EventStore insertOne, native unordered " +
                "insertMany, and transparent BatchCoordinator batching configured through the JMH parameter " +
                "`batchOptions=$mongoBatchQuickCandidateOptions`. It does not change the production default. " +
                "Each invocation writes 128 independent event streams; throughput and average time are normalized " +
                "per event stream.",
            includeInfrastructureRuntime = true,
            requireCleanSource = true,
            validateRows = ::validateQuickMongoBatchCandidateE2ERows,
            appendBeforeResults = { rows -> appendMongoBatchAppendComparisons(rows) },
        )

        val outputFile = quickMongoBatchCandidateE2EReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Quick Mongo batch candidate E2E report generated: ${outputFile.absolutePath}"
        )
    }
}

tasks.register("generateQuickMongoBatchCoordinatorConcurrencyReport") {
    description = "Generate the production Mongo keyed coordinator lane concurrency report."
    group = "benchmark"
    mustRunAfter("benchmarkQuickMongoBatchCoordinatorConcurrency")
    outputs.file(quickMongoBatchCoordinatorConcurrencyReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickMongoBatchCoordinatorConcurrencyTaskSpec),
            title = "Quick Mongo Batch Coordinator Concurrency Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickMongoBatchCoordinatorConcurrency " +
                ":wow-benchmarks:generateQuickMongoBatchCoordinatorConcurrencyReport " +
                "--no-parallel --no-daemon",
            description = "This bounded diagnostic estimates the performance available from partitioned " +
                "coordinator concurrency at `batchOptions=$mongoBatchQuickCandidateOptions`. It compares " +
                "one, two, and four serial lanes in one production KeyedBatchCoordinator with four JMH worker " +
                "threads. The JMH parameter changes the measured Store configuration but does not change the " +
                "production default.",
            includeInfrastructureRuntime = true,
            validateRows = ::validateQuickMongoBatchCoordinatorConcurrencyRows,
            appendBeforeResults = { rows ->
                appendQuickMongoBatchCoordinatorConcurrencyComparison(rows)
            },
        )

        val outputFile = quickMongoBatchCoordinatorConcurrencyReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Quick Mongo batch coordinator concurrency report generated: ${outputFile.absolutePath}"
        )
    }
}

tasks.register("generateMongoBatchAppendConfirmationReport") {
    description = "Generate the confirmation Mongo EventStore batch append comparison report."
    group = "benchmark"
    mustRunAfter("benchmarkConfirmMongoBatchAppend")
    outputs.file(mongoBatchAppendConfirmationReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(confirmationMongoBatchAppendTaskSpec),
            title = "Confirmation Mongo EventStore Batch Append Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkConfirmMongoBatchAppend " +
                ":wow-benchmarks:generateMongoBatchAppendConfirmationReport " +
                "-PbenchmarkConfirmMongoBatchThreads=" +
                "${confirmationMongoBatchAppendProfile.threads.joinToString(",")} --no-parallel",
            description = "This confirmation experiment compares EventStore insertOne, native unordered " +
                "insertMany, and transparent coordinated batching for saturated 128-request append workloads. " +
                "It uses multiple forks and confidence intervals, but remains local evidence rather than a " +
                "production capacity claim.",
            includeInfrastructureRuntime = true,
            appendBeforeResults = { rows -> appendMongoBatchAppendComparisons(rows) },
        )

        val outputFile = mongoBatchAppendConfirmationReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Mongo batch append confirmation report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateElasticsearchBatchAppendBenchmarkReport") {
    description = "Generate the quick Elasticsearch EventStore batch append comparison report."
    group = "benchmark"
    mustRunAfter("benchmarkQuickElasticsearchBatchAppend")
    outputs.file(elasticsearchBatchAppendReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(quickElasticsearchBatchAppendTaskSpec),
            title = "Quick Elasticsearch EventStore Batch Append Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkQuickElasticsearchBatchAppend " +
                ":wow-benchmarks:generateElasticsearchBatchAppendBenchmarkReport " +
                "-PbenchmarkQuickElasticsearchBatchThreads=" +
                "${quickElasticsearchBatchAppendProfile.threads.joinToString(",")} --no-parallel",
            description = "This Quick experiment compares EventStore create, native Bulk create, and " +
                "transparent coordinated Bulk create with identical refresh policies. It reports both " +
                "throughput and amortized time per event and is directional local evidence.",
            includeInfrastructureRuntime = true,
            appendBeforeResults = { rows -> appendElasticsearchBatchAppendComparisons(rows) },
        )

        val outputFile = elasticsearchBatchAppendReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Elasticsearch batch append benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateElasticsearchBatchAppendConfirmationReport") {
    description = "Generate the confirmation Elasticsearch EventStore batch append comparison report."
    group = "benchmark"
    mustRunAfter("benchmarkConfirmElasticsearchBatchAppend")
    outputs.file(elasticsearchBatchAppendConfirmationReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(confirmationElasticsearchBatchAppendTaskSpec),
            title = "Confirmation Elasticsearch EventStore Batch Append Benchmark Report",
            command = "./gradlew :wow-benchmarks:benchmarkConfirmElasticsearchBatchAppend " +
                ":wow-benchmarks:generateElasticsearchBatchAppendConfirmationReport " +
                "-PbenchmarkConfirmElasticsearchBatchThreads=" +
                "${confirmationElasticsearchBatchAppendProfile.threads.joinToString(",")} --no-parallel",
            description = "This confirmation experiment compares EventStore create, native Bulk create, and " +
                "transparent coordinated Bulk create for saturated 128-request workloads. It uses multiple " +
                "forks and reports throughput and amortized time per event for refresh=false and refresh=true.",
            includeInfrastructureRuntime = true,
            appendBeforeResults = { rows -> appendElasticsearchBatchAppendComparisons(rows) },
        )

        val outputFile = elasticsearchBatchAppendConfirmationReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Elasticsearch batch append confirmation report generated: ${outputFile.absolutePath}"
        )
    }
}

tasks.register("generateMongoBatchOptionsTuningReport") {
    description = "Regenerate the stopped Mongo scan as historical exploratory evidence."
    group = "benchmark"
    mustRunAfter("benchmarkTuneMongoBatchOptions")
    outputs.file(mongoBatchOptionsTuningReportFile)
    outputs.file(mongoBatchOptionsFrontierEvidenceFile)
    outputs.upToDateWhen { false }

    doLast {
        val parameters = mongoBatchOptionsTuningProfile.parameters.entries
            .joinToString(";") { (name, value) -> "$name=$value" }
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(mongoBatchOptionsTuningTaskSpec),
            title = "Mongo EventStore Batch Options Tuning Report",
            command = "./gradlew :wow-benchmarks:benchmarkTuneMongoBatchOptions " +
                ":wow-benchmarks:generateMongoBatchOptionsTuningReport " +
                "-PbenchmarkTuneMongoBatchOptionsParameters='$parameters' --no-parallel",
            description = "This stopped full-candidate experiment scanned storage-neutral batch size/delay " +
                "pairs through the real Mongo EventStore coordinated path at isolated (1), burst (32), " +
                "representative (128), and saturated (512) append counts. It is retained only as exploratory " +
                "evidence and does not define an active protocol or production default.",
            includeInfrastructureRuntime = true,
            requireCleanSource = true,
            appendBeforeResults = { rows ->
                appendStorageBatchTuningSummary(
                    rows = rows,
                    expectedOptions = storageBatchTuningOptions(mongoBatchOptionsTuningProfile),
                    expectedRefreshValues = listOf("-"),
                    expectedThreads = mongoBatchOptionsTuningProfile.threads,
                    expectedModes = mongoBatchOptionsTuningProfile.benchmarkModes,
                    currentOptions = mongoCurrentStorageBatchOptions,
                    preferredRefresh = "-",
                    confirmationTaskPath = ":wow-benchmarks:benchmarkConfirmMongoBatchOptions",
                    confirmationReportTaskPath =
                        ":wow-benchmarks:generateMongoBatchOptionsTuningConfirmationReport",
                    confirmationPropertyName = "benchmarkConfirmMongoBatchOptionsParameters",
                    campaignStopped = true,
                )
            },
        )

        val outputFile = mongoBatchOptionsTuningReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        val resultGroup = benchmarkResultGroup(mongoBatchOptionsTuningTaskSpec)
        val groupReport = parseBenchmarkGroup(
            parser = JsonSlurper(),
            group = resultGroup,
        )
        val manifestFile = resultGroup.resultFiles.singleOrNull()?.manifestFile?.get()?.asFile
            ?: throw GradleException(
                "Mongo batch-options frontier evidence requires exactly one screening manifest."
            )
        val frontierEvidence = storageBatchTuningFrontierEvidence(
            suite = mongoBatchOptionsTuningSuite,
            rows = groupReport.rows,
            expectedOptions = storageBatchTuningOptions(mongoBatchOptionsTuningProfile),
            currentOptions = mongoCurrentStorageBatchOptions,
            preferredRefresh = "-",
            manifests = groupReport.manifests,
            benchmarkHarnessSha256 = benchmarkHarnessSha256(),
            manifestSha256 = fileSha256(manifestFile),
        )
        publishJsonAtomically(
            mongoBatchOptionsFrontierEvidenceFile.asFile,
            frontierEvidence.toRunSpec(),
        )
        logger.lifecycle("Mongo batch options tuning report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateElasticsearchBatchOptionsTuningReport") {
    description = "Generate the Elasticsearch EventStore batch-options tuning report."
    group = "benchmark"
    mustRunAfter("benchmarkTuneElasticsearchBatchOptions")
    outputs.file(elasticsearchBatchOptionsTuningReportFile)
    outputs.file(elasticsearchBatchOptionsFrontierEvidenceFile)
    outputs.upToDateWhen { false }

    doLast {
        val parameters = elasticsearchBatchOptionsTuningProfile.parameters.entries
            .joinToString(";") { (name, value) -> "$name=$value" }
        val report = renderSingleBenchmarkReport(
            group = benchmarkResultGroup(elasticsearchBatchOptionsTuningTaskSpec),
            title = "Elasticsearch EventStore Batch Options Tuning Report",
            command = "./gradlew :wow-benchmarks:benchmarkTuneElasticsearchBatchOptions " +
                ":wow-benchmarks:generateElasticsearchBatchOptionsTuningReport " +
                "-PbenchmarkTuneElasticsearchBatchOptionsParameters='$parameters' --no-parallel",
            description = "This screening experiment scans batch size/delay pairs through the real " +
                "Elasticsearch EventStore coordinated Bulk create path at isolated (1), burst (32), " +
                "representative (128), and saturated (512) append counts with refresh=false and " +
                "refresh=true.",
            includeInfrastructureRuntime = true,
            requireCleanSource = true,
            appendBeforeResults = { rows ->
                appendStorageBatchTuningSummary(
                    rows = rows,
                    expectedOptions = storageBatchTuningOptions(elasticsearchBatchOptionsTuningProfile),
                    expectedRefreshValues = listOf("False", "True"),
                    expectedThreads = elasticsearchBatchOptionsTuningProfile.threads,
                    expectedModes = elasticsearchBatchOptionsTuningProfile.benchmarkModes,
                    currentOptions = elasticsearchCurrentStorageBatchOptions,
                    preferredRefresh = "True",
                    confirmationTaskPath = ":wow-benchmarks:benchmarkConfirmElasticsearchBatchOptions",
                    confirmationReportTaskPath =
                        ":wow-benchmarks:generateElasticsearchBatchOptionsTuningConfirmationReport",
                    confirmationPropertyName = "benchmarkConfirmElasticsearchBatchOptionsParameters",
                )
            },
        )

        val outputFile = elasticsearchBatchOptionsTuningReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        val resultGroup = benchmarkResultGroup(elasticsearchBatchOptionsTuningTaskSpec)
        val groupReport = parseBenchmarkGroup(
            parser = JsonSlurper(),
            group = resultGroup,
        )
        val manifestFile = resultGroup.resultFiles.singleOrNull()?.manifestFile?.get()?.asFile
            ?: throw GradleException(
                "Elasticsearch batch-options frontier evidence requires exactly one screening manifest."
            )
        val frontierEvidence = storageBatchTuningFrontierEvidence(
            suite = elasticsearchBatchOptionsTuningSuite,
            rows = groupReport.rows,
            expectedOptions = storageBatchTuningOptions(elasticsearchBatchOptionsTuningProfile),
            currentOptions = elasticsearchCurrentStorageBatchOptions,
            preferredRefresh = "True",
            manifests = groupReport.manifests,
            benchmarkHarnessSha256 = benchmarkHarnessSha256(),
            manifestSha256 = fileSha256(manifestFile),
        )
        publishJsonAtomically(
            elasticsearchBatchOptionsFrontierEvidenceFile.asFile,
            frontierEvidence.toRunSpec(),
        )
        logger.lifecycle("Elasticsearch batch options tuning report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateMongoBatchOptionsTuningConfirmationReport") {
    description = "Regenerate the stopped Mongo multiple-fork report as historical exploratory evidence."
    group = "benchmark"
    mustRunAfter("benchmarkConfirmMongoBatchOptions")
    outputs.file(mongoBatchOptionsTuningConfirmationReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val screening = checkNotNull(validateStorageBatchTuningExecution(
            mongoBatchOptionsTuningSuite,
            mongoBatchOptionsTuningConfirmationProfile,
        ))
        val frontierEvidenceFile = mongoBatchOptionsFrontierEvidenceFile.asFile
        val frontierEvidence = screening.evidence
        val confirmationGroup = benchmarkResultGroup(mongoBatchOptionsTuningConfirmationTaskSpec)
        val confirmationManifests = parseBenchmarkGroup(JsonSlurper(), confirmationGroup).manifests
        requireStorageBatchTuningConfirmationSourceTransition(
            evidence = frontierEvidence,
            suite = mongoBatchOptionsTuningSuite,
            confirmationManifests = confirmationManifests,
        )
        requireStorageBatchTuningConfirmationManifestCompatibility(
            evidence = frontierEvidence,
            screeningManifest = screening.report.manifests.single(),
            manifests = confirmationManifests,
            source = frontierEvidenceFile.absolutePath,
        )
        val parameters = mongoBatchOptionsTuningConfirmationProfile.parameters.entries
            .joinToString(";") { (name, value) -> "$name=$value" }
        val report = renderSingleBenchmarkReport(
            group = confirmationGroup,
            title = "Confirmation Mongo EventStore Batch Options Report",
            command = "./gradlew :wow-benchmarks:benchmarkConfirmMongoBatchOptions " +
                ":wow-benchmarks:generateMongoBatchOptionsTuningConfirmationReport " +
                "-PbenchmarkConfirmMongoBatchOptionsParameters='$parameters' --no-parallel",
            description = "This completed multiple-fork Mongo EventStore comparison is retained as historical " +
                "exploratory evidence. The wider full-candidate campaign was stopped before closure, so these " +
                "results do not define an active protocol or select a production default.",
            includeInfrastructureRuntime = true,
            requireCleanSource = true,
            appendBeforeResults = { rows ->
                appendStorageBatchTuningSummary(
                    rows = rows,
                    expectedOptions = storageBatchTuningOptions(mongoBatchOptionsTuningConfirmationProfile),
                    expectedRefreshValues = listOf("-"),
                    expectedThreads = mongoBatchOptionsTuningConfirmationProfile.threads,
                    expectedModes = mongoBatchOptionsTuningConfirmationProfile.benchmarkModes,
                    currentOptions = mongoCurrentStorageBatchOptions,
                    preferredRefresh = "-",
                    boundConfirmationEvidence = frontierEvidence,
                    campaignStopped = true,
                )
                appendStorageBatchTuningConfirmationVerdict(
                    rows = rows,
                    expectedOptions = storageBatchTuningOptions(
                        mongoBatchOptionsTuningConfirmationProfile
                    ),
                    currentOptions = mongoCurrentStorageBatchOptions,
                    campaignStopped = true,
                )
            },
        )

        val outputFile = mongoBatchOptionsTuningConfirmationReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Mongo batch options confirmation report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateElasticsearchBatchOptionsTuningConfirmationReport") {
    description = "Generate the Elasticsearch EventStore batch-options confirmation report."
    group = "benchmark"
    mustRunAfter("benchmarkConfirmElasticsearchBatchOptions")
    outputs.file(elasticsearchBatchOptionsTuningConfirmationReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val screening = checkNotNull(validateStorageBatchTuningExecution(
            elasticsearchBatchOptionsTuningSuite,
            elasticsearchBatchOptionsTuningConfirmationProfile,
        ))
        val frontierEvidenceFile = elasticsearchBatchOptionsFrontierEvidenceFile.asFile
        val frontierEvidence = screening.evidence
        val confirmationGroup =
            benchmarkResultGroup(elasticsearchBatchOptionsTuningConfirmationTaskSpec)
        val confirmationManifests = parseBenchmarkGroup(JsonSlurper(), confirmationGroup).manifests
        requireStorageBatchTuningConfirmationSourceTransition(
            evidence = frontierEvidence,
            suite = elasticsearchBatchOptionsTuningSuite,
            confirmationManifests = confirmationManifests,
        )
        requireStorageBatchTuningConfirmationManifestCompatibility(
            evidence = frontierEvidence,
            screeningManifest = screening.report.manifests.single(),
            manifests = confirmationManifests,
            source = frontierEvidenceFile.absolutePath,
        )
        val parameters = elasticsearchBatchOptionsTuningConfirmationProfile.parameters.entries
            .joinToString(";") { (name, value) -> "$name=$value" }
        val report = renderSingleBenchmarkReport(
            group = confirmationGroup,
            title = "Confirmation Elasticsearch EventStore Batch Options Report",
            command = "./gradlew :wow-benchmarks:benchmarkConfirmElasticsearchBatchOptions " +
                ":wow-benchmarks:generateElasticsearchBatchOptionsTuningConfirmationReport " +
                "-PbenchmarkConfirmElasticsearchBatchOptionsParameters='$parameters' --no-parallel",
            description = "This multiple-fork experiment confirms selected Elasticsearch EventStore batch " +
                "options against the current default across isolated, burst, representative, and saturated " +
                "append workloads for refresh=false and refresh=true.",
            includeInfrastructureRuntime = true,
            requireCleanSource = true,
            appendBeforeResults = { rows ->
                appendStorageBatchTuningSummary(
                    rows = rows,
                    expectedOptions = storageBatchTuningOptions(
                        elasticsearchBatchOptionsTuningConfirmationProfile
                    ),
                    expectedRefreshValues = listOf("False", "True"),
                    expectedThreads = elasticsearchBatchOptionsTuningConfirmationProfile.threads,
                    expectedModes = elasticsearchBatchOptionsTuningConfirmationProfile.benchmarkModes,
                    currentOptions = elasticsearchCurrentStorageBatchOptions,
                    preferredRefresh = "True",
                    boundConfirmationEvidence = frontierEvidence,
                )
                appendStorageBatchTuningConfirmationVerdict(
                    rows = rows,
                    expectedOptions = storageBatchTuningOptions(
                        elasticsearchBatchOptionsTuningConfirmationProfile
                    ),
                    currentOptions = elasticsearchCurrentStorageBatchOptions,
                )
            },
        )

        val outputFile = elasticsearchBatchOptionsTuningConfirmationReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Elasticsearch batch options confirmation report generated: ${outputFile.absolutePath}"
        )
    }
}

tasks.register("generateMongoBatchAppendPairedE2EReport") {
    description = "Generate the paired AB/BA Mongo EventStore append E2E benchmark report."
    group = "benchmark"
    mustRunAfter(benchmarkMongoBatchAppendPairedE2E)
    mustRunAfter(mongoBatchPairedTrials.map(MongoBatchPairedTrialSpec::task))
    outputs.file(mongoBatchAppendPairedE2EReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val experiment = parseMongoBatchPairedExperiment()
        val report = renderMongoBatchAppendPairedE2EReport(experiment)
        val outputFile = mongoBatchAppendPairedE2EReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle("Mongo batch append paired E2E benchmark report generated: ${outputFile.absolutePath}")
    }
}

tasks.register("generateMongoBatchOptionsPairedConfirmationReport") {
    description = "Regenerate the stopped Mongo paired report as historical exploratory evidence."
    group = "benchmark"
    mustRunAfter(benchmarkMongoBatchOptionsPairedConfirmation)
    mustRunAfter(mongoBatchOptionsPairedTrials.map(MongoBatchOptionsPairedTrialSpec::task))
    outputs.file(mongoBatchOptionsPairedConfirmationReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val screening = requireMongoBatchOptionsPairedPreflight()
        val experiment = parseMongoBatchOptionsPairedExperiment()
        validateMongoBatchOptionsPairedPostflight(screening, experiment)
        val report = renderMongoBatchOptionsPairedConfirmationReport(experiment)
        val outputFile = mongoBatchOptionsPairedConfirmationReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Mongo batch-options paired confirmation report generated: ${outputFile.absolutePath}"
        )
    }
}

tasks.register("generateQuickMongoBatchOptionsPairedReport") {
    description = "Generate the fixed 24-leg quick Mongo batch-options engineering report."
    group = "benchmark"
    mustRunAfter(benchmarkQuickMongoBatchOptionsPaired)
    mustRunAfter(mongoBatchOptionsQuickTrials.map(MongoBatchOptionsQuickTrialSpec::task))
    outputs.file(quickMongoBatchOptionsPairedReportFile)
    outputs.upToDateWhen { false }

    doLast {
        val experiment = parseMongoBatchOptionsQuickExperiment()
        val report = renderMongoBatchOptionsQuickReport(experiment)
        val outputFile = quickMongoBatchOptionsPairedReportFile.asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)
        logger.lifecycle(
            "Quick Mongo batch-options engineering report generated: ${outputFile.absolutePath}"
        )
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
    val schemaVersion = runCatching { manifestInt(parsed, "schemaVersion", source) }.getOrNull()
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
