import java.net.InetSocketAddress
import java.net.Socket
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.jmh)
    kotlin("kapt")
}

dependencies {
    implementation(project(":example-domain"))
    implementation(project(":wow-test"))
    implementation(project(":wow-redis"))
    implementation(project(":wow-mongo"))
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
    jmh(libs.jmh.generator.bytecode)
    kapt(libs.jmh.generator.annprocess)
}

apply(from = "gradle/jmh-packaging.gradle.kts")

val benchmarkSmokeIncludes = listOf(
    "me.ahoo.wow.command.CommandFactoryBenchmark",
    "me.ahoo.wow.command.GlobalIdBenchmark",
    "me.ahoo.wow.messaging.function.MessageFunctionRegistrarBenchmark",
    "me.ahoo.wow.commandpath.CommandHeaderBenchmark",
    "me.ahoo.wow.commandpath.CommandMessageBenchmark",
    "me.ahoo.wow.commandpath.CommandAggregateIdBenchmark",
    "me.ahoo.wow.commandpath.CommandPayloadSerializationBenchmark",
    "me.ahoo.wow.commandpath.CommandPipelineE2EBenchmark",
)

val benchmarkSmokeReport = layout.buildDirectory.file("reports/jmh/benchmark-smoke.json")

val frameworkE2EBenchmarkIncludes = listOf(
    "me.ahoo.wow.commandpath.CommandPipelineE2EBenchmark",
    "me.ahoo.wow.modeling.NoopCommandDispatcherBenchmark",
    "me.ahoo.wow.modeling.NoopEventStoreCommandDispatcherBenchmark",
    "me.ahoo.wow.modeling.InMemoryCommandDispatcherBenchmark",
    "me.ahoo.wow.modeling.InMemoryCommandDispatcherGrowthBenchmark",
)

val infrastructureE2EBenchmarkIncludes = listOf(
    "me.ahoo.wow.infrastructure.redis.RedisCommandDispatcherBenchmark",
    "me.ahoo.wow.infrastructure.mongo.MongoCommandDispatcherBenchmark",
)

val diagnosticBenchmarkIncludes = listOf(
    "me.ahoo.wow.command.BloomFilterIdempotencyCheckerBenchmark",
    "me.ahoo.wow.command.CommandFactoryBenchmark",
    "me.ahoo.wow.command.CommandGatewayBenchmark",
    "me.ahoo.wow.command.GlobalIdBenchmark",
    "me.ahoo.wow.command.InMemoryCommandBusBenchmark",
    "me.ahoo.wow.commandpath.CommandAggregateHandlingBenchmark",
    "me.ahoo.wow.commandpath.CommandAggregateIdBenchmark",
    "me.ahoo.wow.commandpath.CommandAggregateLoadBenchmark",
    "me.ahoo.wow.commandpath.CommandEventPublishBenchmark",
    "me.ahoo.wow.commandpath.CommandHeaderBenchmark",
    "me.ahoo.wow.commandpath.CommandIdempotencyBenchmark",
    "me.ahoo.wow.commandpath.CommandMessageBenchmark",
    "me.ahoo.wow.commandpath.CommandPayloadSerializationBenchmark",
    "me.ahoo.wow.commandpath.CommandPipelineDiagnosticBenchmark",
    "me.ahoo.wow.commandpath.CommandSnapshotSaveBenchmark",
    "me.ahoo.wow.commandpath.CommandValidationBenchmark",
    "me.ahoo.wow.event.EventDispatcherBenchmark",
    "me.ahoo.wow.event.EventUpgraderBenchmark",
    "me.ahoo.wow.eventsourcing.AggregateStateRecoveryBenchmark",
    "me.ahoo.wow.eventsourcing.EventStreamFactoryBenchmark",
    "me.ahoo.wow.eventsourcing.InMemoryEventStoreBenchmark",
    "me.ahoo.wow.eventsourcing.NoopEventStoreBenchmark",
    "me.ahoo.wow.eventsourcing.SnapshotBenchmark",
    "me.ahoo.wow.messaging.FilterChainBenchmark",
    "me.ahoo.wow.messaging.function.MessageFunctionRegistrarBenchmark",
    "me.ahoo.wow.projection.ProjectionBenchmark",
    "me.ahoo.wow.runtime.EventStreamCopyBenchmark",
    "me.ahoo.wow.runtime.ReactorSinkBenchmark",
    "me.ahoo.wow.saga.StatelessSagaBenchmark",
    "me.ahoo.wow.serialization.SerializationBenchmark",
)

val infrastructureDiagnosticBenchmarkIncludes = listOf(
    "me.ahoo.wow.infrastructure.redis.RedisCommandProcessingPipelineBenchmark",
    "me.ahoo.wow.infrastructure.redis.RedisEventStoreBenchmark",
    "me.ahoo.wow.infrastructure.redis.RedisEventStoreReadBenchmark",
    "me.ahoo.wow.infrastructure.mongo.MongoEventStoreBenchmark",
)

fun benchmarkIncludePattern(includes: List<String>): String {
    return includes.joinToString("|") { Regex.escape(it) + ".*" }
}

tasks.register<JavaExec>("benchmarkSmoke") {
    description = "Runs a PR-safe JMH smoke benchmark set."
    group = "verification"
    dependsOn(tasks.named("jmhJar"))
    classpath(tasks.named<Jar>("jmhJar").flatMap { it.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    args(
        benchmarkIncludePattern(benchmarkSmokeIncludes),
        "-wi",
        "0",
        "-i",
        "1",
        "-f",
        "1",
        "-foe",
        "true",
        "-r",
        "1s",
        "-rf",
        "json",
        "-rff",
        benchmarkSmokeReport.get().asFile.absolutePath,
    )
    outputs.file(benchmarkSmokeReport)
    doFirst {
        benchmarkSmokeReport.get().asFile.parentFile.mkdirs()
    }
}

val benchmarkFrameworkE2EReport = layout.buildDirectory.file("results/jmh/framework-e2e.json")
val benchmarkInfrastructureE2EReport = layout.buildDirectory.file("results/jmh/infrastructure-e2e.json")
val benchmarkDiagnosticsReport = layout.buildDirectory.file("results/jmh/diagnostics.json")
val benchmarkInfrastructureDiagnosticsReport = layout.buildDirectory.file("results/jmh/infrastructure-diagnostics.json")
val benchmarkFrameworkE2EHumanReport = layout.buildDirectory.file("reports/jmh/framework-e2e-human.txt")
val benchmarkInfrastructureE2EHumanReport = layout.buildDirectory.file("reports/jmh/infrastructure-e2e-human.txt")
val benchmarkDiagnosticsHumanReport = layout.buildDirectory.file("reports/jmh/diagnostics-human.txt")
val benchmarkInfrastructureDiagnosticsHumanReport =
    layout.buildDirectory.file("reports/jmh/infrastructure-diagnostics-human.txt")

val benchmarkJvmArgs = listOf(
    "-Xmx4g",
    "-Xms4g",
    "-XX:+UseG1GC",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+DebugNonSafepoints",
    "-XX:+AlwaysPreTouch",
)

fun benchmarkProfilerArgs(): List<String> {
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

fun JavaExec.configureJmhBenchmarkRun(
    includePattern: String,
    resultsFile: Provider<RegularFile>,
    humanOutputFile: Provider<RegularFile>,
) {
    dependsOn(tasks.named("jmhJar"))
    classpath(tasks.named<Jar>("jmhJar").flatMap { it.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    args(
        includePattern,
        "-t",
        "1",
        "-wi",
        "2",
        "-w",
        "5s",
        "-i",
        "3",
        "-r",
        "10s",
        "-f",
        "2",
        "-foe",
        "true",
        "-rf",
        "json",
        "-rff",
        resultsFile.get().asFile.absolutePath,
        "-o",
        humanOutputFile.get().asFile.absolutePath,
        "-jvmArgs",
        benchmarkJvmArgs.joinToString(" "),
    )
    args(benchmarkProfilerArgs())
    outputs.file(resultsFile)
    outputs.file(humanOutputFile)
    outputs.upToDateWhen { false }
    doFirst {
        resultsFile.get().asFile.parentFile.mkdirs()
        humanOutputFile.get().asFile.parentFile.mkdirs()
    }
}

tasks.register<JavaExec>("benchmarkFrameworkE2E") {
    description = "Runs primary framework E2E JMH benchmarks. Use these results for framework performance conclusions."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = benchmarkIncludePattern(frameworkE2EBenchmarkIncludes),
        resultsFile = benchmarkFrameworkE2EReport,
        humanOutputFile = benchmarkFrameworkE2EHumanReport,
    )
}

tasks.register<JavaExec>("benchmarkInfrastructureE2E") {
    description = "Runs secondary Redis and Mongo E2E JMH benchmarks with infrastructure I/O."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = benchmarkIncludePattern(infrastructureE2EBenchmarkIncludes),
        resultsFile = benchmarkInfrastructureE2EReport,
        humanOutputFile = benchmarkInfrastructureE2EHumanReport,
    )
    doFirst {
        requireBenchmarkService("Redis", 6379)
        requireBenchmarkService("MongoDB", 27017)
    }
}

tasks.register<JavaExec>("benchmarkDiagnostics") {
    description = "Runs local diagnostic JMH benchmarks. Do not use these results as framework performance conclusions."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = benchmarkIncludePattern(diagnosticBenchmarkIncludes),
        resultsFile = benchmarkDiagnosticsReport,
        humanOutputFile = benchmarkDiagnosticsHumanReport,
    )
}

tasks.register<JavaExec>("benchmarkInfrastructureDiagnostics") {
    description = "Runs Redis and Mongo diagnostic JMH benchmarks for infrastructure bottleneck analysis."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = benchmarkIncludePattern(infrastructureDiagnosticBenchmarkIncludes),
        resultsFile = benchmarkInfrastructureDiagnosticsReport,
        humanOutputFile = benchmarkInfrastructureDiagnosticsHumanReport,
    )
    doFirst {
        requireBenchmarkService("Redis", 6379)
        requireBenchmarkService("MongoDB", 27017)
    }
}

jmh {
    zip64.set(true)
    includes.set(listOf(benchmarkIncludePattern(frameworkE2EBenchmarkIncludes)))
    threads.set(1)
    warmupIterations.set(2)
    warmup.set("5s")
    iterations.set(3)
    timeOnIteration.set("10s")
    fork.set(2)
    resultFormat.set("json")
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/framework-e2e-human.txt"))
    resultsFile.set(layout.buildDirectory.file("results/jmh/framework-e2e.json"))
    jvmArgs.set(
        listOf(
            "-Xmx4g",
            "-Xms4g",
            "-XX:+UseG1GC",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-XX:+AlwaysPreTouch",
        )
    )
    val asyncProfilerLib = file("/opt/async-profiler/lib/libasyncProfiler.dylib")
    val hasAsyncProfiler = asyncProfilerLib.exists()
    profilers.set(buildList {
        add("gc")
        if (hasAsyncProfiler) {
            add("async:output=flamegraph;dir=build/profiling;event=cpu;libPath=${asyncProfilerLib.absolutePath}")
        } else {
            add("stack:lines=10;top=20")
        }
    })
}

apply(from = "gradle/benchmark-reporting.gradle.kts")
