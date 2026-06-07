import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
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
                                val newAggMap = aggValue as Map<String, Any?>
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

val mergedJmhServicesRoot = layout.buildDirectory.dir("tmp/jmh-services-merged")
val jmhServiceFilesToMerge = listOf(
    "META-INF/services/tools.jackson.databind.JacksonModule",
    "META-INF/services/me.ahoo.wow.id.GlobalIdGeneratorFactory",
)
val mergeJmhServices = tasks.register("mergeJmhServices") {
    description = "Merge critical SPI service files from the JMH runtime classpath."
    outputs.dir(mergedJmhServicesRoot)
    inputs.files(configurations.jmhRuntimeClasspath)
    inputs.files(jmhServiceFilesToMerge.map { layout.projectDirectory.file("src/jmh/resources/$it") })
    doLast {
        val outputRoot = mergedJmhServicesRoot.get().asFile
        outputRoot.deleteRecursively()

        fun MutableSet<String>.addServiceProviders(text: String) {
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .forEach { add(it) }
        }

        for (servicePath in jmhServiceFilesToMerge) {
            val providers = linkedSetOf<String>()
            configurations.jmhRuntimeClasspath.get().resolve()
                .filter { it.name.endsWith(".jar") || it.isDirectory }
                .forEach { file ->
                    if (file.isDirectory) {
                        val serviceFile = file.resolve(servicePath)
                        if (serviceFile.exists()) {
                            providers.addServiceProviders(serviceFile.readText())
                        }
                    } else {
                        ZipFile(file).use { zip ->
                            zip.getEntry(servicePath)?.let { entry ->
                                providers.addServiceProviders(zip.getInputStream(entry).bufferedReader().readText())
                            }
                        }
                    }
                }

            val localServiceFile = file("src/jmh/resources/$servicePath")
            if (localServiceFile.exists()) {
                providers.addServiceProviders(localServiceFile.readText())
            }

            if (providers.isNotEmpty()) {
                val outputFile = outputRoot.resolve(servicePath)
                outputFile.parentFile.mkdirs()
                outputFile.writeText(providers.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
            }
        }
    }
}

tasks.named<Jar>("jmhJar") {
    isZip64 = true
    dependsOn(mergeWowMetadata)
    dependsOn(mergeJmhServices)
    // Exclude all wow-metadata.json from dependency JARs (they're duplicated)
    exclude("META-INF/wow-metadata.json")
    // Add the merged metadata file
    from(mergedWowMetadata) {
        into("META-INF")
    }
    eachFile {
        if (path in jmhServiceFilesToMerge && !file.absolutePath.startsWith(mergedJmhServicesRoot.get().asFile.absolutePath)) {
            exclude()
        }
    }
    from(mergedJmhServicesRoot)
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

apply(from = "gradle/benchmark-reporting.gradle.kts")
