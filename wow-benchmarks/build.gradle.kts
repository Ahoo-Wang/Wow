import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import java.time.LocalDate
import java.util.zip.ZipFile

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

/**
 * Merge all META-INF/wow-metadata.json from the runtime classpath into a single valid JSON.
 * Without this, the JMH JAR contains duplicate entries that concatenate into invalid JSON,
 * causing MetadataSearcher to fail silently at runtime.
 *
 * Aggregate entries are deep-merged (non-null values take precedence) to avoid the API module's
 * type=null overwriting the domain module's type=full.class.Name.
 */
val mergedWowMetadata = layout.buildDirectory.file("tmp/wow-metadata-merged/META-INF/wow-metadata.json")
val mergeWowMetadata = tasks.register("mergeWowMetadata") {
    description = "Merge all wow-metadata.json from classpath into a single file."
    outputs.file(mergedWowMetadata)
    inputs.files(configurations.jmhRuntimeClasspath)
    doLast {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val merged = mutableMapOf<String, Any>()
        val metadataContents = mutableListOf<String>()

        configurations.jmhRuntimeClasspath.get().resolve()
            .filter { it.name.endsWith(".jar") || it.isDirectory }
            .forEach { file ->
                if (file.isDirectory) {
                    val meta = file.resolve("META-INF/wow-metadata.json")
                    if (meta.exists()) {
                        metadataContents.add(meta.readText())
                    }
                } else {
                    ZipFile(file).use { zip ->
                        zip.getEntry("META-INF/wow-metadata.json")?.let { entry ->
                            metadataContents.add(zip.getInputStream(entry).bufferedReader().readText())
                        }
                    }
                }
            }

        for (text in metadataContents) {
            try {
                @Suppress("UNCHECKED_CAST")
                val next = mapper.readValue(text, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val contexts = next["contexts"] as? Map<String, Any> ?: continue
                @Suppress("UNCHECKED_CAST")
                val mergedContexts = merged.getOrPut("contexts") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                for ((ctxName, ctxValue) in contexts) {
                    val existing = mergedContexts[ctxName]
                    if (existing == null) {
                        mergedContexts[ctxName] = ctxValue
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val existingMap = existing as MutableMap<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val newMap = ctxValue as Map<String, Any>
                        // Deep-merge aggregates (non-null values win)
                        @Suppress("UNCHECKED_CAST")
                        val existingAggregates = existingMap.getOrPut("aggregates") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val newAggregates = newMap["aggregates"] as? Map<String, Any> ?: emptyMap()
                        for ((aggName, aggValue) in newAggregates) {
                            val existingAgg = existingAggregates[aggName]
                            if (existingAgg == null) {
                                existingAggregates[aggName] = aggValue
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                val existingAggMap = existingAgg as MutableMap<String, Any>
                                @Suppress("UNCHECKED_CAST")
                                val newAggMap = aggValue as Map<String, Any>
                                for ((key, value) in newAggMap) {
                                    if (value != null) {
                                        val existingVal = existingAggMap[key]
                                        if (existingVal == null || value is String) {
                                            existingAggMap[key] = value
                                        } else if (value is List<*>) {
                                            @Suppress("UNCHECKED_CAST")
                                            val existingList = (existingVal as? List<String> ?: emptyList()).toMutableList()
                                            @Suppress("UNCHECKED_CAST")
                                            val newList = value as List<String>
                                            existingList.addAll(newList.filter { it !in existingList })
                                            existingAggMap[key] = existingList
                                        }
                                    }
                                }
                            }
                        }
                        // Merge scopes (union)
                        @Suppress("UNCHECKED_CAST")
                        val existingScopes = existingMap.getOrPut("scopes") { mutableListOf<String>() } as MutableList<String>
                        @Suppress("UNCHECKED_CAST")
                        val newScopes = newMap["scopes"] as? List<String> ?: emptyList()
                        existingScopes.addAll(newScopes.filter { it !in existingScopes })
                    }
                }
            } catch (_: Exception) {
                // skip invalid metadata
            }
        }

        val outputFile = mergedWowMetadata.get().asFile
        outputFile.parentFile.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, merged)
        logger.lifecycle("Merged ${metadataContents.size} wow-metadata.json files into ${outputFile.absolutePath}")
    }
}

tasks.named<Jar>("jmhJar") {
    isZip64 = true
    dependsOn(mergeWowMetadata)
    // Exclude all wow-metadata.json from dependency JARs (they're duplicated)
    exclude("META-INF/wow-metadata.json")
    // Add the merged metadata file
    from(mergedWowMetadata) {
        into("META-INF")
    }
}

val benchmarkSmokeIncludes = listOf(
    "me.ahoo.wow.command.CommandFactoryBenchmark",
    "me.ahoo.wow.command.GlobalIdBenchmark",
    "me.ahoo.wow.messaging.function.MessageFunctionRegistrarBenchmark",
    "me.ahoo.wow.hotpath.HeaderCreationBenchmark",
    "me.ahoo.wow.hotpath.MessageWrappingBenchmark",
    "me.ahoo.wow.hotpath.AggregateIdGenerationBenchmark",
    "me.ahoo.wow.hotpath.ObjectMapperLookupBenchmark",
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

val benchmarkInternalReport = layout.buildDirectory.file("results/jmh/internal.json")
val benchmarkExternalReport = layout.buildDirectory.file("results/jmh/external.json")
val benchmarkInternalHumanReport = layout.buildDirectory.file("reports/jmh/internal-human.txt")
val benchmarkExternalHumanReport = layout.buildDirectory.file("reports/jmh/external-human.txt")

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

tasks.register<JavaExec>("benchmarkInternal") {
    description = "Runs non-Mongo and non-Redis JMH benchmarks."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = """me\.ahoo\.wow\.(?!mongo\.|redis\.).*Benchmark.*""",
        resultsFile = benchmarkInternalReport,
        humanOutputFile = benchmarkInternalHumanReport,
    )
}

tasks.register<JavaExec>("benchmarkExternal") {
    description = "Runs MongoDB and Redis JMH benchmarks."
    group = "benchmark"
    configureJmhBenchmarkRun(
        includePattern = """me\.ahoo\.wow\.(mongo|redis)\..*Benchmark.*""",
        resultsFile = benchmarkExternalReport,
        humanOutputFile = benchmarkExternalHumanReport,
    )
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

            val parts = benchmark.split(".")
            val shortName = if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else benchmark
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
                val params = result["params"] as? Map<String, String>
                val key = if (params != null && params.isNotEmpty()) {
                    "$benchmark(${params.entries.joinToString(",") { "${it.key}=${it.value}" }})"
                } else {
                    benchmark
                }
                @Suppress("UNCHECKED_CAST")
                val primaryMetric = result["primaryMetric"] as Map<String, Any>
                key to (primaryMetric["score"] as Double)
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
            val parts = benchmark.split("(")[0].split(".")
            val shortName = if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else benchmark
            val paramSuffix = if ("(" in benchmark) " ${benchmark.substringAfter("(").substringBefore(")")}" else ""

            val displayName = "$shortName$paramSuffix"

            if (baseScore == null) {
                println("| $displayName | — | ${String.format("%.2f", latestScore)} | NEW | 🆕 |")
                continue
            }
            if (latestScore == null) {
                println("| $displayName | ${String.format("%.2f", baseScore)} | — | REMOVED | ⚠️ |")
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

            println("| $displayName | ${String.format("%.2f", baseScore)} | ${String.format("%.2f", latestScore)} | ${String.format("%+.1f%%", changePercent)} | $status |")
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
