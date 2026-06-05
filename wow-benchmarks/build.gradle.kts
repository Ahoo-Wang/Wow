import java.time.LocalDate

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

val resultsDir = layout.projectDirectory.dir("results")
val baselineJson = resultsDir.file("baseline.json")
val latestJson = layout.buildDirectory.file("results/jmh/latest.json")
val readmeFile = layout.projectDirectory.file("README.md")

tasks.register("generateBenchmarkReport") {
    description = "Generate benchmark README.md from JMH JSON results."
    group = "benchmark"
    dependsOn(tasks.named("jmh"))

    doLast {
        val resultsFile = latestJson.get().asFile
        if (!resultsFile.exists()) {
            throw GradleException("JMH results not found: ${resultsFile.absolutePath}. Run :wow-benchmarks:jmh first.")
        }

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val resultsText = resultsFile.readText()
        @Suppress("UNCHECKED_CAST")
        val jmhResults = mapper.readValue(resultsText, List::class.java) as List<Map<String, Any>>

        val version = project.version.toString()
        val date = LocalDate.now().toString()
        val jvm = System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")
        val os = System.getProperty("os.name") + " " + System.getProperty("os.arch")

        val sb = StringBuilder()
        sb.appendLine("# Benchmark Report")
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
            val score = primaryMetric["score"] as? Double ?: continue
            val scoreError = primaryMetric["scoreError"] as? Double ?: 0.0
            val unit = primaryMetric["scoreUnit"] as? String ?: "ops/s"

            var allocRateNorm = "—"
            @Suppress("UNCHECKED_CAST")
            val secondaryMetrics = result["secondaryMetrics"] as? Map<String, Map<String, Any>>
            if (secondaryMetrics != null) {
                val gcAlloc = secondaryMetrics["gc.alloc.rate.norm"]
                allocRateNorm = String.format("%.1f B/op", gcAlloc?.get("score") as? Double ?: 0.0)
            }

            val shortName = benchmark.substringAfterLast(".")
            sb.appendLine("| $shortName | ${String.format("%.2f", score)} | ±${String.format("%.2f", scoreError)} | $unit | $allocRateNorm |")
        }

        readmeFile.asFile.writeText(sb.toString())
        logger.lifecycle("Benchmark report generated: ${readmeFile.asFile.absolutePath}")
    }
}

tasks.register("benchmarkCompare") {
    description = "Compare latest benchmark results against baseline."
    group = "benchmark"

    doLast {
        val latestFile = latestJson.get().asFile
        val baselineFile = baselineJson.asFile

        if (!baselineFile.exists()) {
            throw GradleException("Baseline not found: ${baselineFile.absolutePath}. Run :wow-benchmarks:updateBaseline first.")
        }
        if (!latestFile.exists()) {
            throw GradleException("Latest results not found: ${latestFile.absolutePath}. Run :wow-benchmarks:jmh first.")
        }

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()

        fun parseScores(file: java.io.File): Map<String, Double> {
            @Suppress("UNCHECKED_CAST")
            val results = mapper.readValue(file, List::class.java) as List<Map<String, Any>>
            return results.associate { result ->
                val benchmark = result["benchmark"] as String
                @Suppress("UNCHECKED_CAST")
                val primaryMetric = result["primaryMetric"] as Map<String, Any>
                benchmark to (primaryMetric["score"] as Double)
            }
        }

        val baseline = parseScores(baselineFile)
        val latest = parseScores(latestFile)
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
            val shortName = benchmark.substringAfterLast(".")

            if (baseScore == null) {
                println("| $shortName | — | ${String.format("%.2f", latestScore)} | NEW | 🆕 |")
                continue
            }
            if (latestScore == null) {
                println("| $shortName | ${String.format("%.2f", baseScore)} | — | REMOVED | ⚠️ |")
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

            println("| $shortName | ${String.format("%.2f", baseScore)} | ${String.format("%.2f", latestScore)} | ${String.format("%+.1f%%", changePercent)} | $status |")
        }

        println()
        println("**Summary:** $regressions regression(s), $improvements improvement(s), ${allBenchmarks.size - regressions - improvements} stable")

        if (regressions > 0) {
            throw GradleException("Benchmark regressions detected: $regressions")
        }
    }
}

tasks.register("updateBaseline") {
    description = "Copy latest benchmark results as the new baseline."
    group = "benchmark"

    doLast {
        val latestFile = latestJson.get().asFile
        val baselineFile = baselineJson.asFile

        if (!latestFile.exists()) {
            throw GradleException("Latest results not found: ${latestFile.absolutePath}. Run :wow-benchmarks:jmh first.")
        }

        latestFile.copyTo(baselineFile, overwrite = true)
        logger.lifecycle("Baseline updated: ${baselineFile.absolutePath}")
    }
}
