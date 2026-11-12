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

package me.ahoo.wow.benchmark.transfer.raw

import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.benchmark.transfer.ComponentCounters
import me.ahoo.wow.benchmark.transfer.ComponentRun
import me.ahoo.wow.benchmark.transfer.StoreCounter
import me.ahoo.wow.benchmark.transfer.TransferServer
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.File
import kotlin.system.exitProcess

/**
 * The raw-driver ceiling (beta.4's evaluation, §3): what the store alone does with the durable writes Wow asks of it
 * for a command, and with the variants of them that price a candidate before any kernel work.
 *
 * First the benchmark's domain runs in this JVM on the store (ComponentRun, as the L1 benchmark does) for `--templates`
 * accounts and as many deposits, so Wow creates its tables and writes real rows. Then, with the driver alone, each of
 * `--shapes` (Shape) and each of `--variants` (PostgreSQL: the snapshot table's fillfactor; MongoDB: the snapshot
 * update as a pipeline or a plain `$set`) runs on fresh clones of Wow's tables over `--connections`, `--warm-up` seconds
 * unmeasured and `--seconds` measured each, `--log-batch` commands a flush in one append (about 10 in the L1 counts) and
 * their snapshots `--snapshot-batch` a statement (about 4), over `--aggregates` aggregates whose snapshots exist.
 *
 * Each step gives the commands a second, the flush's p50 and p99, the store container's CPU per command
 * (`--container`'s cgroup), and the store's own counters per command: PostgreSQL's WAL bytes, records, full-page images,
 * syncs and their time and commits; MongoDB's journal syncs, their time, writes and bytes and its inserts and updates.
 * The `today` shape is the check: its WAL per command should be Wow's (about 1.3 KB on PostgreSQL in the L3 runs).
 * `raw.json` goes to `--out`.
 *
 * Run it on the install's class path with main class `RawStoreBenchKt`; bench.sh's `measure --program=raw` does.
 */
fun main(args: Array<String>) {
    val options = args.associate { argument ->
        require(argument.startsWith("--") && '=' in argument) { "Not an option: $argument" }
        argument.removePrefix("--").substringBefore('=') to argument.substringAfter('=')
    }
    val backend = options["backend"] ?: "postgres"
    val settings = RawSettings.of(options, backend)
    val out = File(options["out"] ?: ".").apply { mkdirs() }
    seed(backend, settings.templates, options)
    val steps = when (backend) {
        "postgres" -> postgres(settings, options)
        "mongo" -> mongo(settings, options)
        else -> error("Unknown backend $backend: postgres or mongo.")
    }
    val json = rawJson(settings.toMap() + ("backend" to backend), steps)
    File(out, "raw.json").writeText(json + "\n")
    println(json)
    exitProcess(0)
}

/** A raw run's settings, from its options. */
data class RawSettings(
    val shapes: List<Shape>,
    val variants: List<String>,
    val connections: List<Int>,
    val warmUpSeconds: Int,
    val seconds: Int,
    val logBatch: Int,
    val snapshotBatch: Int,
    val aggregates: Int,
    val templates: Int,
    val container: String?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "shapes" to shapes.map { it.key },
        "variants" to variants,
        "connections" to connections,
        "warmUpSeconds" to warmUpSeconds,
        "seconds" to seconds,
        "logBatch" to logBatch,
        "snapshotBatch" to snapshotBatch,
        "aggregates" to aggregates,
        "templates" to templates,
        "container" to container,
    )

    /** The variants a [shape] runs: all of them when it writes snapshots, else the first. */
    fun variantsOf(shape: Shape): List<String> = if (shape.writesSnapshots) variants else variants.take(1)

    companion object {
        fun of(options: Map<String, String>, backend: String): RawSettings {
            fun list(key: String, default: String) = (options[key] ?: default).split(',').map { it.trim() }
                .filter { it.isNotEmpty() }
            fun int(key: String, default: Int) = options[key]?.toInt() ?: default
            val settings = RawSettings(
                shapes = list("shapes", Shape.entries.joinToString(",") { it.key }).map(Shape::of),
                variants = list("variants", if (backend == "mongo") "pipeline,set" else "100,80"),
                connections = list("connections", "1,4,16,64,256").map { it.toInt() },
                warmUpSeconds = int("warm-up", DEFAULT_WARM_UP),
                seconds = int("seconds", DEFAULT_SECONDS),
                logBatch = int("log-batch", DEFAULT_LOG_BATCH),
                snapshotBatch = int("snapshot-batch", DEFAULT_SNAPSHOT_BATCH),
                aggregates = int("aggregates", DEFAULT_AGGREGATES),
                templates = int("templates", DEFAULT_TEMPLATES),
                container = options["container"],
            )
            require(settings.connections.all { it >= 1 } && settings.logBatch >= 1 && settings.snapshotBatch >= 1)
            require(settings.connections.max() * settings.logBatch <= settings.aggregates) {
                "Each connection's flush needs ${settings.logBatch} aggregates of its own: raise --aggregates."
            }
            return settings
        }

        private const val DEFAULT_WARM_UP = 5
        private const val DEFAULT_SECONDS = 20
        private const val DEFAULT_LOG_BATCH = 10
        private const val DEFAULT_SNAPSHOT_BATCH = 4
        private const val DEFAULT_AGGREGATES = 100_000
        private const val DEFAULT_TEMPLATES = 2_000
    }
}

/** Wow's own tables and rows: the domain on the store for [templates] accounts and as many deposits. */
private fun seed(backend: String, templates: Int, options: Map<String, String>) {
    val context = SpringApplicationBuilder(TransferServer::class.java, ComponentCounters::class.java)
        .web(WebApplicationType.NONE)
        .profiles(backend)
        .run(
            "--wow.internal.port=0",
            *options.filterKeys { it.startsWith("spring.") || it.startsWith("wow.") }
                .map { "--${it.key}=${it.value}" }
                .toTypedArray(),
        )
    try {
        ComponentRun(
            scenario = "E1",
            accounts = templates,
            ops = templates,
            warmUpOps = 0,
            concurrency = SEED_CONCURRENCY
        )
            .run(context.getBean(CommandClient::class.java), context.getBean(StoreCounter::class.java))
    } finally {
        context.close()
    }
}

private fun postgres(settings: RawSettings, options: Map<String, String>): List<RawStep> {
    val raw = RawPostgres(
        url = options["spring.datasource.url"] ?: "jdbc:postgresql://localhost:5432/transfer",
        user = options["spring.datasource.username"] ?: "postgres",
        password = options["spring.datasource.password"] ?: "postgres",
        partitions = options["wow.log.partitions"]?.toInt() ?: PARTITIONS,
    )
    raw.loadTemplates(settings.templates)
    val cpu = ContainerCpu(settings.container)
    return settings.shapes.flatMap { shape ->
        settings.variantsOf(shape).flatMap { variant ->
            settings.connections.map { connections ->
                raw.prepare(variant.toInt(), settings.aggregates)
                runStep(
                    shape.key,
                    "fillfactor-$variant",
                    connections,
                    settings.warmUpSeconds,
                    settings.seconds,
                    cpu,
                    raw::counters
                ) { index ->
                    raw.worker(
                        shape,
                        Aggregates(settings.aggregates, index, connections),
                        settings.logBatch,
                        settings.snapshotBatch
                    )
                }.also { println("raw ${it.shape} ${it.variant} ${it.connections}: ${it.commandsPerSecond}/s") }
            }
        }
    }
}

private fun mongo(settings: RawSettings, options: Map<String, String>): List<RawStep> =
    RawMongo(
        uri = options["spring.mongodb.uri"] ?: "mongodb://localhost:27017/?replicaSet=rs0",
        databaseName = options["wow.mongo.database"] ?: "transfer",
    ).use { raw ->
        raw.loadTemplates(settings.templates)
        val cpu = ContainerCpu(settings.container)
        settings.shapes.flatMap { shape ->
            settings.variantsOf(shape).flatMap { variant ->
                settings.connections.map { connections ->
                    raw.prepare(settings.aggregates)
                    runStep(
                        shape.key,
                        variant,
                        connections,
                        settings.warmUpSeconds,
                        settings.seconds,
                        cpu,
                        raw::counters
                    ) { index ->
                        raw.worker(
                            shape,
                            RawMongo.Variant.of(variant),
                            Aggregates(settings.aggregates, index, connections),
                            settings.logBatch,
                            settings.snapshotBatch
                        )
                    }.also { println("raw ${it.shape} ${it.variant} ${it.connections}: ${it.commandsPerSecond}/s") }
                }
            }
        }
    }

private const val SEED_CONCURRENCY = 16
private const val PARTITIONS = 64
