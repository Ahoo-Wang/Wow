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
import me.ahoo.wow.benchmark.transfer.BenchPipeline
import me.ahoo.wow.benchmark.transfer.CONNECTION_HOLD
import me.ahoo.wow.benchmark.transfer.ComponentCounters
import me.ahoo.wow.benchmark.transfer.ComponentRun
import me.ahoo.wow.benchmark.transfer.StoreCounter
import me.ahoo.wow.benchmark.transfer.TransferServer
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContext
import java.io.File
import kotlin.system.exitProcess

/**
 * The raw-driver ceiling: what the store alone does with the durable writes Wow asks of it, per command, row or
 * update, in today's shape and in the shapes that price a candidate (MongoDB's update shapes M0–M3; PostgreSQL's
 * P0–P2), before any kernel work.
 *
 * First the benchmark's domain runs in this JVM on the store (ComponentRun, as the L1 benchmark does): `--templates`
 * accounts and four deposits each, so Wow creates its tables and writes real rows, and the store's counters per
 * command are read around it. Then, with the driver alone, each case's steps run over `--connections`, each on fresh
 * clones of Wow's tables with `--aggregates` aggregates opened at version 1 (no step runs on what another left),
 * `--warm-up` seconds unmeasured and `--seconds` measured; on PostgreSQL P2's two cases run again at `--p2-share` of
 * the split's throughput, so they compare at one rate. `--cases` keeps the cases whose name starts with one of its
 * prefixes.
 *
 * The calibration leads raw.json: today's shape on Wow's ground (as many aggregates as Wow's run, every one written
 * since the checkpoint, at its concurrency, paced at its throughput, in its batch shape from its statements per
 * command) against Wow's own counters per command, each within its bound: WAL records, bytes and the HOT share on
 * PostgreSQL; inserts, updates, journal and oplog bytes on MongoDB, Wow's updates and oplog bytes without its node's
 * checkpoint and lease writes, which the clone does not make; the appends and saves per command, so a wrong batch
 * shape fails it; and no command of Wow's run failed. Reported apart: full-page images and commits on PostgreSQL (its
 * commit counter counts the transactions Wow's node only reads in), commands and the node's own oplog bytes on
 * MongoDB. A run that fails it or cannot take it says so first, and none of its cases is read; nor is a step with a
 * failed or refused write. raw.json goes to `--out`, with the store's version and durability settings and the run's
 * batch parameters.
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
    val shapeClients: List<Int> = listOf(
        DEFAULT_SHAPE_CLIENTS_LOW,
        DEFAULT_SHAPE_CLIENTS_MID,
        DEFAULT_SHAPE_CLIENTS_HIGH
    ),
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
        "shapeClients" to shapeClients,
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
                shapeClients = list("shape-clients", "256,1024,4096").map { it.toInt() },
            )
            require(settings.connections.all { it >= 1 } && settings.logBatch >= 1 && settings.snapshotBatch >= 1)
            val widest = maxOf(settings.logBatch, settings.batches.max())
            require(settings.connections.max() * widest <= settings.aggregates) {
                "Each connection's flush needs $widest aggregates of its own: raise --aggregates."
            }
            require(settings.shapeClients.all { it >= 1 } && settings.shapeClients.max() <= settings.aggregates) {
                "Each of Wow's shape's callers needs an aggregate of its own: raise --aggregates."
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
        private const val DEFAULT_SHAPE_CLIENTS_LOW = 256
        private const val DEFAULT_SHAPE_CLIENTS_MID = 1_024
        private const val DEFAULT_SHAPE_CLIENTS_HIGH = 4_096
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
    own: (Long, Long) -> Map<String, Double> = { _, _ -> emptyMap() },
    records: (Map<String, Double>, Map<String, Double>) -> Map<String, Double> = { _, _ -> emptyMap() },
    watch: () -> () -> Map<String, Double> = { { emptyMap() } },
): Map<String, Double> {
    val context = SpringApplicationBuilder(
        TransferServer::class.java,
        ComponentCounters::class.java,
        RawCommands::class.java
    )
        .web(WebApplicationType.NONE)
        .profiles(backend)
        .run(
            "--wow.internal.port=0",
            // The node's append queue and pool, read over the window (WowWindow): an atomic add a call.
            "--bench.pipeline.enabled=true",
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
    val commands = context.getBean(RawCommands::class.java)
    var from = 0L
    var to = 0L
    var watched: () -> Map<String, Double> = { emptyMap() }
    var diagnostics: Map<String, Double> = emptyMap()
    var window: WowWindow? = null
    val component = try {
        run.run(context.getBean(CommandClient::class.java), context.getBean(StoreCounter::class.java)) { starting ->
            // The horizon is watched over the measured deposits alone, not the settling after them.
            if (!starting) diagnostics = watched() + window?.stop().orEmpty() + wowTimes(context)
            // The warm-up's snapshots are written behind its commits: let them land before the measured deposits.
            Thread.sleep(SETTLE_MILLIS)
            if (starting) {
                before = counters() + commands.snapshot()
                from = System.currentTimeMillis()
                watched = watch()
                window = windowOf(context, commands)
            } else {
                to = System.currentTimeMillis()
                after = counters() + commands.snapshot()
            }
        }
    } finally {
        context.close()
    }
    // Wow's own statements per deposit, by statement (ComponentRun's count), beside the store's counters.
    val statements = (component["storeRoundTripsByCommand"] as Map<*, *>?).orEmpty()
        .mapNotNull { (key, value) -> (value as? Number)?.let { "statements: $key" to it.toDouble() } }
    val rate = (component["throughput"] as? Number)?.let { mapOf(WOW_RATE to it.toDouble()) }.orEmpty()
    val failed = (component["failed"] as Map<*, *>?).orEmpty().values.sumOf { (it as Number).toDouble() }
    val growth = growthOf(before, after) + own(from, to) + records(before, after)
    return growth.mapValues { (_, delta) -> delta / ops } + statements + rate + (WOW_FAILED to failed / ops) +
        diagnostics
}

/** Wow's run's window, read on its node's `bench-pipeline` line and its saves; null without the line. */
private fun windowOf(context: ApplicationContext, commands: RawCommands): WowWindow? {
    val store = context.getBean(StoreCounter::class.java)
    return context.getBeanProvider(BenchPipeline::class.java).ifAvailable?.let { line ->
        WowWindow(line::snapshot) { savesOf(store.snapshot(), commands.snapshot()) }
    }
}

/**
 * Wow's run's store calls over its window (StoreCounter.timings, reset at its start), by the names the model's are
 * (CallTimes): its append's and save's statements' round trips, a connection's wait in the pool and its hold.
 */
private fun wowTimes(context: ApplicationContext): Map<String, Double> =
    CallTimes.means(context.getBean(StoreCounter::class.java).timings()).mapKeys { (key, _) ->
        when (key) {
            "${PG_APPENDS.removePrefix(STATEMENTS)}Ms" -> "${RawPostgres.APPEND_ROUND_TRIP}Ms"
            "${PG_SAVES.removePrefix(STATEMENTS)}Ms" -> "${RawPostgres.SAVE_ROUND_TRIP}Ms"
            "$CONNECTION_HOLD ${PG_APPENDS.removePrefix(STATEMENTS)}Ms" -> APPEND_HOLD_MS
            "$CONNECTION_HOLD ${PG_SAVES.removePrefix(STATEMENTS)}Ms" -> SAVE_HOLD_MS
            else -> key
        }
    }.filterKeys { it in WOW_TIMES }

/**
 * Wow's run's snapshot saves so far: its save statements of the snapshot table (PostgreSQL, [statements]) or its
 * update commands of the snapshot collection (MongoDB, [commands]), whichever the store counted.
 */
private fun savesOf(statements: Map<String, Long>, commands: Map<String, Double>): Long =
    statements.filterKeys { it.endsWith(" ${RawPostgres.SNAPSHOT}") && !it.contains(" select ") }.values.sum() +
        (commands["commands: update ${RawMongo.SNAPSHOT}"] ?: 0.0).toLong()

/** The calibration's connections: Wow's run's concurrency. */
const val CALIBRATION_CONNECTIONS = 64

/** The calibration's case: today's, on Wow's ground. */
const val CALIBRATION = "calibration"

/** The model's own shape beside the calibration, apart. */
const val CALIBRATION_MODEL = "calibration-model"

/** Versions of [aggregates] aggregates opened at version 1. */
fun opened(aggregates: Int): IntArray = IntArray(aggregates) { 1 }

/**
 * A case's steps, one at each of [connections]: each on tables [prepare]d afresh, its [aggregates] aggregates opened at
 * version 1, so no step runs on what an earlier one left (dead tuples, grown indexes, a vacuum's work).
 */
fun stepsOf(
    connections: List<Int>,
    aggregates: Int,
    prepare: () -> Unit,
    run: (connections: Int, versions: IntArray) -> RawStep,
): List<RawStep> = connections.map { n ->
    prepare()
    run(n, opened(aggregates))
}

private fun postgres(settings: RawSettings, options: Map<String, String>): RawResult {
    val raw = RawPostgres(
        url = options["spring.datasource.url"] ?: "jdbc:postgresql://localhost:5432/transfer",
        user = options["spring.datasource.username"] ?: "postgres",
        password = options["spring.datasource.password"] ?: "postgres",
    )
    val wow = seed(
        "postgres",
        settings.templates,
        options,
        { raw.counters() + raw.tables() },
        raw::checkpoint,
        records = raw::records,
        watch = raw::watchWow,
    )
    raw.loadTemplates(settings.templates)
    val cpu = ContainerCpu(settings.container)
    val cases = raw.cases(settings.logBatch, settings.snapshotBatch, settings.batches)
    fun step(case: RawPostgres.Case, connections: Int, pace: Pace, versions: IntArray): RawStep =
        postgresStep(raw, cpu, case, connections, pace, versions)
    val model = cases.first { it.case.ceiling == RawCase.COMMITTED }
    val (calibrating, uncapped) = listOf(equalShape(model.shape, wow, PG_APPENDS, PG_SAVES), model.shape)
        .zip(listOf(calibrationCase(model.case), modelCase(model.case))).map { (shape, case) ->
            raw.prepare(model.fillfactor, settings.templates)
            step(
                RawPostgres.Case(case, model.fillfactor, shape, model.flush),
                CALIBRATION_CONNECTIONS,
                Pace(calibrationTiming(settings), wow[WOW_RATE]),
                opened(settings.templates),
            )
        }
    val steps = mutableListOf(calibrating, uncapped)
    for (case in cases.filter { settings.keeps(it.case.name) }) {
        val clients = if (case.shape != null) settings.shapeClients else settings.connections
        steps += stepsOf(clients, settings.aggregates, {
            raw.prepare(case.fillfactor, settings.aggregates)
        }) { n, versions ->
            step(case, n, Pace(settings.timing), versions)
        }
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
    return RawResult(postgresCalibration(wow, calibrating, uncapped), raw.settings(), steps)
}

/** A PostgreSQL step: [case]'s workers on a connection each, or callers of Wow's own write path for a shape. */
private fun postgresStep(
    raw: RawPostgres,
    cpu: ContainerCpu,
    case: RawPostgres.Case,
    connections: Int,
    pace: Pace,
    versions: IntArray,
): RawStep {
    val shape = case.shape?.let(raw::shapeOf)
    return try {
        val counters = { raw.counters() + shape?.counters().orEmpty() }
        val ratios = { growth: Map<String, Double> -> raw.ratios(growth) + WowShape.ratios(growth) }
        runStep(case.case, connections, pace, cpu, counters, ratios, raw.recordsOf(case.case), raw::watch) { index ->
            val owned = Aggregates(versions, index, connections)
            if (shape != null) {
                caller(shape, owned)
            } else {
                val connection = raw.connect()
                object : RawWorker {
                    override fun flush(): Flushed = case.flush(connection, owned)
                    override fun close() = connection.close()
                }
            }
        }.let { step -> case.shape?.let { step.copy(pool = it.connections) } ?: step }
    } finally {
        shape?.close()
    }.also(::print)
}

private fun mongo(settings: RawSettings, options: Map<String, String>): RawResult =
    RawMongo(
        uri = options["spring.mongodb.uri"] ?: "mongodb://localhost:27017/?replicaSet=rs0",
        databaseName = options["wow.mongo.database"] ?: "transfer",
    ).use { raw ->
        val wow = seed("mongo", settings.templates, options, { raw.counters() + raw.tables() }, {}, raw::ownOplog)
        raw.loadTemplates(settings.templates)
        val cpu = ContainerCpu(settings.container)
        val cases = raw.cases(settings.logBatch, settings.snapshotBatch, settings.batches)
        fun step(case: RawMongo.Case, connections: Int, versions: IntArray, pace: Pace = Pace(settings.timing)) =
            raw.client(case.shape?.connections ?: connections).use { client ->
                val shape = case.shape?.let { raw.shapeOf(it, client) }
                try {
                    val counters = { raw.counters() + shape?.counters().orEmpty() }
                    val ratios = { growth: Map<String, Double> -> raw.ratios(growth) + WowShape.ratios(growth) }
                    runStep(case.case, connections, pace, cpu, counters, ratios) { index ->
                        val owned = Aggregates(versions, index, connections)
                        if (shape != null) {
                            caller(shape, owned)
                        } else {
                            val writer = raw.writer(client, case.case)
                            object : RawWorker {
                                override fun flush(): Flushed = case.flush(writer, owned)
                                override fun close() = writer.close()
                            }
                        }
                    }.copy(pool = case.shape?.connections ?: connections)
                } finally {
                    shape?.close()
                }
            }.also(::print)
        val model = cases.first { it.case.ceiling == RawCase.COMMITTED }
        val (calibrating, uncapped) = listOf(equalShape(model.shape, wow, MONGO_APPENDS, MONGO_SAVES), model.shape)
            .zip(listOf(calibrationCase(model.case), modelCase(model.case))).map { (shape, case) ->
                raw.prepare(settings.templates, model.bigState)
                step(
                    RawMongo.Case(case, model.bigState, shape, model.flush),
                    CALIBRATION_CONNECTIONS,
                    opened(settings.templates),
                    Pace(calibrationTiming(settings), wow[WOW_RATE]),
                )
            }
        val steps = mutableListOf(calibrating, uncapped)
        for (case in cases.filter { settings.keeps(it.case.name) }) {
            val clients = if (case.shape != null) settings.shapeClients else settings.connections
            steps += stepsOf(clients, settings.aggregates, {
                raw.prepare(settings.aggregates, case.bigState)
            }) { n, versions ->
                step(case, n, versions)
            }
        }
        RawResult(mongoCalibration(wow, calibrating, uncapped), raw.settings(), steps)
    }

/**
 * The calibration's warm-up and measured seconds: its warm-up at least CALIBRATION_WARM_UP, since PostgreSQL's table
 * counters reach a reader up to a second late and an idle backend holds its last ones longer, so a short warm-up's
 * first writes (every row's first update, never HOT) would be counted in the window.
 */
private fun calibrationTiming(
    settings: RawSettings
) = maxOf(settings.warmUpSeconds, CALIBRATION_WARM_UP) to settings.seconds

/**
 * The calibration's case: Wow's own write path answered at the append ([model], the committed ceiling's), as Wow's E1
 * answers, paced to Wow's rate on Wow's ground, in Wow's shape (equalShape); no ceiling of its own.
 */
private fun calibrationCase(model: RawCase) = model.copy(name = CALIBRATION, prices = WOW_GROUND, ceiling = null)

/** The model's own shape at Wow's rate, beside the calibration: what its lanes make of Wow's load, apart. */
private fun modelCase(model: RawCase) = model.copy(name = CALIBRATION_MODEL, prices = MODEL_GROUND, ceiling = null)

/**
 * Wow's own write path ([shape]) with its calls of the sizes Wow's run made ([sizesOf], from the counts [append] and
 * [save]), each call waiting for its size as long as its streams and snapshots take to arrive at Wow's rate
 * (WowShape.fillWaitOf): the store's cost a command at an equal shape, which the calibration checks. The model's own
 * sizes follow from its calls' time, and Wow's from its node's time around each call too, so they are compared apart.
 */
private fun equalShape(shape: WowShape.Options?, wow: Map<String, Double>, append: String, save: String) =
    sizesOf(wow, append, save)?.let { (commands, snapshots) ->
        val wait = WowShape.fillWaitOf(listOf(commands, snapshots), wow[WOW_RATE])
        shape?.copy(appendFill = commands, saveFill = snapshots, fillWaitNanos = wait)
    } ?: shape

/** A caller of Wow's own write path: one command a flush, counted once its stream and its snapshot were written. */
private fun caller(shape: WowShape<*>, owned: Aggregates) = object : RawWorker {
    override fun flush(): Flushed = if (shape.submit(owned.take(1).single())) Flushed(1) else Flushed(0, refused = 1)
    override fun close() = Unit
}

private fun print(step: RawStep) =
    println("raw ${step.case.name} at ${step.connections}: ${step.unitsPerSecond} ${step.case.unit}s/s")

private const val SEED_CONCURRENCY = CALIBRATION_CONNECTIONS
private const val CALIBRATION_WARM_UP = 10
private const val DEPOSITS = 4
private const val SETTLE_MILLIS = 2_000L
private const val MODEL_GROUND = "Wow's own write path at Wow's rate, its own shape: Wow's node time apart"
private const val WOW_GROUND = "Wow's own write path on Wow's ground, at its rate: the calibration"
