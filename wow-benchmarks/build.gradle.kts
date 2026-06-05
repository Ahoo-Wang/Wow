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
tasks.named<Jar>("jmhJar") {
    isZip64 = true
}

val benchmarkSmokeIncludes = listOf(
    "me.ahoo.wow.command.CommandFactoryBenchmark",
    "me.ahoo.wow.command.GlobalIdBenchmark",
    "me.ahoo.wow.messaging.function.MessageFunctionRegistrarBenchmark",
)

val benchmarkSmokeReport = layout.buildDirectory.file("reports/jmh/benchmark-smoke.json")
tasks.register<JavaExec>("benchmarkSmoke") {
    description = "Runs a PR-safe JMH smoke benchmark set."
    group = "verification"
    dependsOn(tasks.named("jmhJar"))
    classpath(tasks.named<Jar>("jmhJar").flatMap { it.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    args(
        benchmarkSmokeIncludes.joinToString("|") { Regex.escape(it) + ".*" },
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

jmh {
    zip64.set(true)
    includes.set(listOf(".*Benchmark.*"))
    threads.set(1)
    warmupIterations.set(2)
    warmup.set("5s")
    iterations.set(3)
    timeOnIteration.set("10s")
    fork.set(2)
    resultFormat.set("json")
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))
    resultsFile.set(layout.buildDirectory.file("results/jmh/latest.json"))
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
