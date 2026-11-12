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
 * The raw-driver ceiling: what the store alone does with the durable writes Wow asks of it, per command, row or
 * update, in today's shape and in the shapes that price a candidate (MongoDB's update shapes M0–M3; PostgreSQL's
 * P0–P2), before any kernel work.
 *
 * First the benchmark's domain runs in this JVM on the store (ComponentRun, as the L1 benchmark does): `--templates`
 * accounts and four deposits each, so Wow creates its tables and writes real rows, and the store's counters per
 * command are read around it. Then, with the driver alone, each case runs on fresh clones of Wow's tables over
 * `--connections`, `--warm-up` seconds unmeasured and `--seconds` measured, over `--aggregates` opened aggregates
 * whose versions its steps carry on; on PostgreSQL P2's two cases run again at `--p2-share` of the split's
 * throughput, so they compare at one rate. `--cases` keeps the cases whose name starts with one of its prefixes.
 *
 * The calibration leads raw.json: today's shape on Wow's ground (as many aggregates as Wow's run, every one written
 * since the checkpoint, at its concurrency) against Wow's own counters per command, each within its bound: WAL
 * records, commits, bytes and the HOT share on PostgreSQL; inserts, updates, journal and oplog bytes on MongoDB. Full
 * page images and commands are reported apart. A run that fails it or cannot take it says so first, and none of its
 * cases is read; nor is a step with a failed or refused write. raw.json goes to `--out`, with the store's version and
 * durability settings and the run's batch parameters.
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
    val result = when (backend) {
        "postgres" -> postgres(settings, options)
        "mongo" -> mongo(settings, options)
        else -> error("Unknown backend $backend: postgres or mongo.")
    }
    val json = rawJson(result.calibration, settings.toMap() + ("backend" to backend), result.store, result.steps)
    File(out, "raw.json").writeText(json + "\n")
    println(json)
    exitProcess(0)
}

/** A raw run's result: its calibration, its store's version and settings, and its steps. */
class RawResult(val calibration: RawCalibration, val store: Map<String, Any?>, val steps: List<RawStep>)

/** A raw run's settings, from its options; each store's defaults are its share of Wow's batches in the L1 counts. */
data class RawSettings(
    val connections: List<Int>,
    val warmUpSeconds: Int,
    val seconds: Int,
    val logBatch: Int,
    val snapshotBatch: Int,
    val batches: List<Int>,
    val aggregates: Int,
    val templates: Int,
    val p2Share: Double,
    val cases: List<String>,
    val container: String?,
) {
    val timing: Pair<Int, Int> get() = warmUpSeconds to seconds

    fun toMap(): Map<String, Any?> = mapOf(
        "connections" to connections,
        "warmUpSeconds" to warmUpSeconds,
        "seconds" to seconds,
        "logBatch" to logBatch,
        "snapshotBatch" to snapshotBatch,
        "batches" to batches,
        "aggregates" to aggregates,
        "templates" to templates,
        "p2Share" to p2Share,
        "cases" to cases,
        "container" to container,
    )

    /** Whether [name] is one of the cases this run keeps. */
    fun keeps(name: String): Boolean = cases.isEmpty() || cases.any { name.startsWith(it) }

    companion object {
        fun of(options: Map<String, String>, backend: String): RawSettings {
            fun list(key: String, default: String) = (options[key] ?: default).split(',').map { it.trim() }
                .filter { it.isNotEmpty() }
            fun int(key: String, default: Int) = options[key]?.toInt() ?: default
            val mongo = backend == "mongo"
            val settings = RawSettings(
                connections = list("connections", "16,64,256").map { it.toInt() },
                warmUpSeconds = int("warm-up", DEFAULT_WARM_UP),
                seconds = int("seconds", DEFAULT_SECONDS),
                logBatch = int("log-batch", if (mongo) MONGO_LOG_BATCH else PG_LOG_BATCH),
                snapshotBatch = int("snapshot-batch", if (mongo) MONGO_SNAPSHOT_BATCH else PG_SNAPSHOT_BATCH),
                batches = list("batches", "4,10,25").map { it.toInt() },
                aggregates = int("aggregates", DEFAULT_AGGREGATES),
                templates = int("templates", DEFAULT_TEMPLATES),
                p2Share = options["p2-share"]?.toDouble() ?: DEFAULT_P2_SHARE,
                cases = list("cases", ""),
                container = options["container"],
            )
            require(settings.connections.all { it >= 1 } && settings.logBatch >= 1 && settings.snapshotBatch >= 1)
            val widest = maxOf(settings.logBatch, settings.batches.max())
            require(settings.connections.max() * widest <= settings.aggregates) {
                "Each connection's flush needs $widest aggregates of its own: raise --aggregates."
            }
            require(CALIBRATION_CONNECTIONS * settings.logBatch <= settings.templates) {
                "The calibration's $CALIBRATION_CONNECTIONS connections need ${settings.logBatch} templates each."
            }
            return settings
        }

        private const val DEFAULT_WARM_UP = 5
        private const val DEFAULT_SECONDS = 20
        private const val PG_LOG_BATCH = 10
        private const val PG_SNAPSHOT_BATCH = 4
        private const val MONGO_LOG_BATCH = 14
        private const val MONGO_SNAPSHOT_BATCH = 9
        private const val DEFAULT_AGGREGATES = 100_000
        private const val DEFAULT_TEMPLATES = 2_000
        private const val DEFAULT_P2_SHARE = 0.5
    }
}

/**
 * Wow's own run on the store: after a [checkpoint], the domain opens [templates] accounts and deposits into each once
 * unmeasured, so every page it measures has been written since the checkpoint, as the clone's warm-up does; then four
 * deposits each, measured, at L1's concurrency (so Wow's appends and snapshot flushes take the shape the clone's
 * batches copy). Around those, with a moment for their snapshots to land, the store's [counters] are read: each one's
 * growth per deposit.
 */
private fun seed(
    backend: String,
    templates: Int,
    options: Map<String, String>,
    counters: () -> Map<String, Double>,
    checkpoint: () -> Unit,
): Map<String, Double> {
    val context = SpringApplicationBuilder(TransferServer::class.java, ComponentCounters::class.java)
        .web(WebApplicationType.NONE)
        .profiles(backend)
        .run(
            "--wow.internal.port=0",
            *options.filterKeys { it.startsWith("spring.") || it.startsWith("wow.") }
                .map { "--${it.key}=${it.value}" }
                .toTypedArray(),
        )
    var before: Map<String, Double> = emptyMap()
    var after: Map<String, Double> = emptyMap()
    val ops = templates * DEPOSITS
    checkpoint()
    val run = ComponentRun(
        scenario = "E1",
        accounts = templates,
        ops = ops,
        warmUpOps = templates,
        concurrency = SEED_CONCURRENCY,
    )
    val component = try {
        run.run(context.getBean(CommandClient::class.java), context.getBean(StoreCounter::class.java)) { starting ->
            // The warm-up's snapshots are written behind its commits: let them land before the measured deposits.
            Thread.sleep(SETTLE_MILLIS)
            if (starting) {
                before = counters()
            } else {
                after = counters()
            }
        }
    } finally {
        context.close()
    }
    // Wow's own statements per deposit, by statement (ComponentRun's count), beside the store's counters.
    val statements = (component["storeRoundTripsByCommand"] as Map<*, *>?).orEmpty()
        .mapNotNull { (key, value) -> (value as? Number)?.let { "statements: $key" to it.toDouble() } }
    return after.mapValues { (key, value) -> (value - (before[key] ?: value)) / ops } + statements
}

/** The calibration's connections: Wow's run's concurrency. */
const val CALIBRATION_CONNECTIONS = 64

/** The calibration's case: today's, on Wow's ground. */
const val CALIBRATION = "calibration"

/** Versions of [aggregates] opened aggregates, at version 1, which a case's steps carry on. */
fun opened(aggregates: Int): IntArray = IntArray(aggregates) { 1 }

private fun postgres(settings: RawSettings, options: Map<String, String>): RawResult {
    val raw = RawPostgres(
        url = options["spring.datasource.url"] ?: "jdbc:postgresql://localhost:5432/transfer",
        user = options["spring.datasource.username"] ?: "postgres",
        password = options["spring.datasource.password"] ?: "postgres",
    )
    val wow = seed("postgres", settings.templates, options, { raw.counters() + raw.tables() }, raw::checkpoint)
    raw.loadTemplates(settings.templates)
    val cpu = ContainerCpu(settings.container)
    val cases = raw.cases(settings.logBatch, settings.snapshotBatch, settings.batches)
    fun step(case: RawPostgres.Case, connections: Int, pace: Pace, versions: IntArray): RawStep =
        runStep(case.case, connections, pace, cpu, raw::counters, raw::ratios) { index ->
            val connection = raw.connect()
            val owned = Aggregates(versions, index, connections)
            object : RawWorker {
                override fun flush(): Flushed = case.flush(connection, owned)
                override fun close() = connection.close()
            }
        }.also(::print)
    val today = cases.first { it.case.name == RawPostgres.WOW_TODAY }
    raw.prepare(today.fillfactor, settings.templates)
    val calibrating = step(
        RawPostgres.Case(today.case.copy(name = CALIBRATION, prices = WOW_GROUND), today.fillfactor, today.flush),
        CALIBRATION_CONNECTIONS,
        Pace(settings.timing),
        opened(settings.templates),
    )
    val steps = mutableListOf(calibrating)
    for (case in cases.filter { settings.keeps(it.case.name) }) {
        raw.prepare(case.fillfactor, settings.aggregates)
        val versions = opened(settings.aggregates)
        settings.connections.forEach { steps += step(case, it, Pace(settings.timing), versions) }
    }
    // P2 at one rate: both cases at a share of the split's throughput at the most connections.
    val split = steps.lastOrNull { it.case.name == RawPostgres.P2_SEPARATE && it.rate == null }
    if (split != null) {
        val pace = Pace(settings.timing, split.unitsPerSecond * settings.p2Share)
        for (case in cases.filter { it.case.name in setOf(RawPostgres.P2_SEPARATE, RawPostgres.P2_COMBINED) }) {
            raw.prepare(case.fillfactor, settings.aggregates)
            steps += step(case, split.connections, pace, opened(settings.aggregates))
        }
    }
    // Wow's HOT share, of its snapshot table's updates per command.
    val table = RawPostgres.SNAPSHOT
    val wowHot = (wow["$table updates"] ?: 0.0).takeIf { it > 0 }
        ?.let { mapOf("hotShare" to (wow["$table hot updates"] ?: 0.0) / it) }.orEmpty()
    return RawResult(calibrationOf(POSTGRES_CHECKS, listOf("walFpi"), wow + wowHot, calibrating), raw.settings(), steps)
}

private fun mongo(settings: RawSettings, options: Map<String, String>): RawResult =
    RawMongo(
        uri = options["spring.mongodb.uri"] ?: "mongodb://localhost:27017/?replicaSet=rs0",
        databaseName = options["wow.mongo.database"] ?: "transfer",
    ).use { raw ->
        val wow = seed("mongo", settings.templates, options, { raw.counters() + raw.tables() }) {}
        raw.loadTemplates(settings.templates)
        val cpu = ContainerCpu(settings.container)
        val cases = raw.cases(settings.logBatch, settings.snapshotBatch, settings.batches)
        fun step(case: RawMongo.Case, connections: Int, versions: IntArray): RawStep =
            raw.client(connections).use { client ->
                runStep(case.case, connections, Pace(settings.timing), cpu, raw::counters, raw::ratios) { index ->
                    val writer = raw.writer(client, case.case)
                    val owned = Aggregates(versions, index, connections)
                    object : RawWorker {
                        override fun flush(): Flushed = case.flush(writer, owned)
                        override fun close() = writer.close()
                    }
                }.copy(pool = connections)
            }.also(::print)
        val today = cases.first { it.case.name == RawMongo.WOW_TODAY }
        raw.prepare(settings.templates, today.bigState)
        val calibrating = step(
            RawMongo.Case(today.case.copy(name = CALIBRATION, prices = WOW_GROUND), today.bigState, today.flush),
            CALIBRATION_CONNECTIONS,
            opened(settings.templates),
        )
        val steps = mutableListOf(calibrating)
        for (case in cases.filter { settings.keeps(it.case.name) }) {
            raw.prepare(settings.aggregates, case.bigState)
            val versions = opened(settings.aggregates)
            settings.connections.forEach { steps += step(case, it, versions) }
        }
        RawResult(calibrationOf(MONGO_CHECKS, listOf("commands"), wow, calibrating), raw.settings(), steps)
    }

/**
 * The calibration: each of [checks]' metrics per command of Wow's own run ([wow], every counter's) against the
 * [calibrating] step's, within its bound; [apart]'s metrics reported beside them, unbounded.
 */
fun calibrationOf(
    checks: List<Pair<String, RawCheck.Bound>>,
    apart: List<String>,
    wow: Map<String, Double>,
    calibrating: RawStep,
): RawCalibration {
    val raw = calibrating.perUnit + calibrating.ratios
    return RawCalibration(
        checks = checks.map { (metric, bound) -> RawCheck(metric, wow[metric], raw[metric], bound) },
        apart = apart.map { RawCheck(it, wow[it], raw[it], RawCheck.Bound()) },
        wowCounters = wow,
        rawCounters = raw,
        read = calibrating.read,
    )
}

private fun print(step: RawStep) =
    println("raw ${step.case.name} at ${step.connections}: ${step.unitsPerSecond} ${step.case.unit}s/s")

private const val SEED_CONCURRENCY = CALIBRATION_CONNECTIONS
private const val DEPOSITS = 4
private const val SETTLE_MILLIS = 2_000L
private const val SHARE = 0.10
private const val WOW_GROUND = "today on Wow's ground: the calibration"

/** PostgreSQL's checks: WAL records, commits and bytes within 10%, the HOT share within 0.10. */
private val POSTGRES_CHECKS = listOf(
    "walRecords" to RawCheck.Bound(share = SHARE),
    "commits" to RawCheck.Bound(share = SHARE),
    "walBytes" to RawCheck.Bound(share = SHARE),
    "hotShare" to RawCheck.Bound(absolute = SHARE),
)

/** MongoDB's checks: inserts, updates, journal and oplog bytes, each within 10%. */
private val MONGO_CHECKS = listOf("inserts", "updates", "journalBytes", "oplogBytes")
    .map { it to RawCheck.Bound(share = SHARE) }
