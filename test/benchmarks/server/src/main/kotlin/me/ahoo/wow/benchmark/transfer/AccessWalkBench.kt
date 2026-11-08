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

package me.ahoo.wow.benchmark.transfer

import com.mongodb.client.MongoClients
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.sun.management.OperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE
import org.bson.Document
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import java.io.File
import java.lang.management.ManagementFactory
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * The access walk's scale check (the `access-walk-*` suites, kind `measure`): the benchmark's accounts on the V10 node
 * and one store, in this JVM, as [ComponentRun] runs them, through the node's public API and the store's own driver.
 *
 * A first node opens `--aggregates` accounts and deposits into each until it has `--streams` streams, then completes
 * its walk of them; the options `--seed.<name>=<value>` are its alone (`--seed.wow.access.walk-share=1`: it walks what
 * it seeded unbounded). The store's history then loses its access attributes, as a log an import wrote holds none,
 * except the accounts the deposits go to, and the account's walk record unless `--walk=none` (the A/A arm: the same
 * history, with its walk recorded as completed, so nothing is walked); the store then settles (a vacuum and a
 * checkpoint), so the measured window pays for neither that rewrite nor the seeding. A second node then starts: its
 * walk stamps the accounts as its `wow.access.*` options pace it, while `--rate` deposits a second (E1, open loop, none
 * at 0) run over `--measure` seconds after `--warm-up`. `--targets` says where they go: `stamped` (the default), the
 * tenth of the accounts the walk reaches last, which keep their attributes, so the walk is the only stamping; `first`,
 * the first accounts the walk reaches, which keep theirs too, so it stamps what the deposits write; `all`, every
 * account, none stamped, so the built-in `wow.access` subscription also stamps the history of each one a deposit
 * reaches first, as it does after a change of the access schema. It writes `measure.json` to `--out`: the
 * deposits' latencies, the CPU used over the window, and the walk's progress: how many it stamped over the measured window and at what rate, and how
 * far it got from the second node's start; with `--complete=yes`, it waits for the walk's completion.
 *
 * Run it on the install's class path with main class `AccessWalkBenchKt`; bench.sh's `measure` command does.
 */
fun main(args: Array<String>) {
    val options = args.associate { argument ->
        require(argument.startsWith("--") && '=' in argument) { "Not an option: $argument" }
        argument.removePrefix("--").substringBefore('=') to argument.substringAfter('=')
    }
    val backend = options["backend"] ?: "mongo"
    val run = WalkRun(
        aggregates = options["aggregates"]?.toInt() ?: DEFAULT_AGGREGATES,
        streams = options["streams"]?.toInt() ?: DEFAULT_STREAMS,
        walks = options["walk"] != "none",
        rate = options["rate"]?.toInt() ?: 0,
        warmUpSeconds = options["warm-up"]?.toInt() ?: DEFAULT_WARM_UP,
        measureSeconds = options["measure"]?.toInt() ?: DEFAULT_MEASURE,
        targetMode = options["targets"] ?: TARGETS_STAMPED,
        complete = options["complete"] == "yes",
    )
    val out = File(options["out"] ?: ".").apply { mkdirs() }
    fun properties(of: Map<String, String>) = of.filterKeys { it.startsWith("spring.") || it.startsWith("wow.") }
        .map { "--${it.key}=${it.value}" }
    val seedOnly = options.filterKeys { it.startsWith(SEED) }.mapKeys { it.key.removePrefix(SEED) }
    val node = { seeding: Boolean ->
        SpringApplicationBuilder(TransferServer::class.java)
            .web(WebApplicationType.NONE)
            .profiles(backend)
            .run(
                "--wow.internal.port=0",
                *properties(options).toTypedArray(),
                *(if (seeding) properties(seedOnly) else emptyList()).toTypedArray(),
            )
    }
    val result = run.run(backend, node)
    val json = toJson(result + ("backend" to backend))
    File(out, "measure.json").writeText(json + "\n")
    println(json)
    exitProcess(0)
}

private const val DEFAULT_AGGREGATES = 100_000
private const val DEFAULT_STREAMS = 10
private const val DEFAULT_WARM_UP = 10
private const val DEFAULT_MEASURE = 40
private const val BALANCE = 1_000_000_000_000L
private const val SEED_CONCURRENCY = 256
private const val IN_FLIGHT = 1_024
private const val WALK_TIMEOUT_SECONDS = 3_600L
private const val POLL_MILLIS = 100L
private const val NANOS_PER_MILLI = 1_000_000.0
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MICROS_PER_SECOND = 1_000_000.0
private const val PERCENT = 100.0
private const val MILLIS_PER_SECOND = 1_000L
private const val SAMPLE_MILLIS = 1_000L
private const val LAST_SHARE = 10
private const val SEED = "seed."
private const val SEED_ATTEMPTS = 8
private const val FIRST_ACCOUNTS = 1_000
private const val TARGETS_STAMPED = "stamped"
private const val TARGETS_FIRST = "first"
private const val TARGETS_ALL = "all"

/** One walk run: the history a first node writes, then the second node's walk under the deposits. */
class WalkRun(
    private val aggregates: Int,
    private val streams: Int,
    private val walks: Boolean,
    private val rate: Int,
    private val warmUpSeconds: Int,
    private val measureSeconds: Int,
    /** Where the deposits go: [TARGETS_STAMPED], [TARGETS_FIRST] or [TARGETS_ALL] (the class says). */
    private val targetMode: String = TARGETS_STAMPED,
    /** Whether the walk is timed to its completion; else it is read when the measured window ends. */
    private val complete: Boolean = false,
) {
    init {
        require(aggregates > 0 && streams > 0) { "A walk needs aggregates with streams." }
    }

    fun run(backend: String, node: (seeding: Boolean) -> ConfigurableApplicationContext): Map<String, Any?> =
        runBlocking(Dispatchers.Default) {
            val seedStarted = System.nanoTime()
            val seeding = node(true)
            val environment = seeding.environment
            val kept = try {
                seed(seeding.getBean(CommandClient::class.java))
                WalkStore.of(backend, environment).use { store ->
                    // The first node walks the history it wrote: a node starting after it finds it walked.
                    walked(store, seedStarted) { false }
                    when (targetMode) {
                        TARGETS_ALL -> emptyList()
                        TARGETS_FIRST -> store.inWalkOrder(FIRST_ACCOUNTS, last = false)
                        else -> store.inWalkOrder(aggregates / LAST_SHARE, last = true)
                    }
                }
            } finally {
                seeding.close()
            }
            // After the first node is gone, its leases released: a store this busy would cost it some to expiry, which
            // the second node would then wait out or take over inside its measured window.
            WalkStore.of(backend, environment).use { store ->
                targets = kept.takeIf { it.isNotEmpty() }
                store.unstamp(kept, walkRecord = !walks)
                store.settle()
            }
            val seedSeconds = (System.nanoTime() - seedStarted) / NANOS_PER_SECOND
            val started = System.nanoTime()
            val walking = node(false)
            val walkRate = walking.environment.getProperty("wow.access.walk-rate")
            val walkShare = walking.environment.getProperty("wow.access.walk-share")
            // The waits are sampled on a connection of their own: the walk's is polled meanwhile.
            val sampled = WalkStore.of(backend, walking.environment)
            try {
                WalkStore.of(backend, walking.environment).use { store ->
                    measured(backend, walking, store, sampled, started) + mapOf(
                        "schema" to "wow-e2e-bench/measure/1",
                        "program" to "walk",
                        "aggregates" to aggregates,
                        "streams" to streams,
                        "walks" to walks,
                        "walkRate" to walkRate,
                        "walkShare" to walkShare,
                        "rate" to rate,
                        "warmUpSeconds" to warmUpSeconds,
                        "measureSeconds" to measureSeconds,
                        "seedSeconds" to seedSeconds,
                        "completeWalk" to complete,
                        "targets" to targetMode,
                        "note" to "In one JVM: the walk's rate is over the measured window, its time from the " +
                            "second node's start to its completion (--complete=yes) or to the window's end; the " +
                            "deposits' latencies an open loop over the measured window, where the store's waits " +
                            "and the walk's progress are sampled.",
                    )
                }
            } finally {
                sampled.close()
                walking.close()
            }
        }

    /**
     * The second node's walk from [started], timed while the deposits run, and the deposits, the store's waits sampled
     * on [sampled] over their measured window.
     */
    private suspend fun measured(
        backend: String,
        walking: ConfigurableApplicationContext,
        store: WalkStore,
        sampled: WalkStore,
        started: Long,
    ): Map<String, Any?> = coroutineScope {
        var over = false
        val walk = async { if (walks) walked(store, started) { !complete && over } else null }
        val waits = Waits(sampled, poolOf(walking))
        val cpu = CpuUse("wow-e2e-bench-$backend")
        // The measured window starts after the warm-up, from the second node's start: the deposits' or, without
        // them, the walk's own.
        val sampling = launch {
            delay(warmUpSeconds * MILLIS_PER_SECOND)
            cpu.start()
            while (true) {
                waits.sample()
                delay(SAMPLE_MILLIS)
            }
        }
        val deposits = if (rate > 0) {
            deposits(walking.getBean(CommandClient::class.java))
        } else {
            delay((warmUpSeconds + measureSeconds) * MILLIS_PER_SECOND)
            null
        }
        sampling.cancel()
        over = true
        val used = cpu.end()
        val progress = waits.progress().takeIf { walks }
        mapOf(
            "walk" to walk.await()?.plus(progress.orEmpty()),
            "e1" to deposits,
            "waits" to waits.summary(),
            "cpu" to used
        )
    }

    /** Opens the accounts, then deposits into each until it holds [streams] streams. */
    private suspend fun seed(client: CommandClient) = coroutineScope {
        val permits = Semaphore(SEED_CONCURRENCY)
        repeat(aggregates) { index ->
            launch {
                permits.withPermit {
                    val ref = ACCOUNT_TYPE.ref(TENANT, accountId(index))
                    seeded(client, ref, "seed-$index-0", OpenAccount("owner-${index % OWNERS}", BALANCE))
                    repeat(streams - 1) { seeded(client, ref, "seed-$index-${it + 1}", Deposit(1)) }
                }
            }
        }
    }

    /**
     * Sends [body] until it is answered. A store that stalls times a wait out, which says nothing of the command: the
     * same request id is safe to send again, and the run must not fail on the seeding, which it does not measure.
     */
    private suspend fun seeded(client: CommandClient, ref: AggregateRef, requestId: String, body: Any) {
        val request = CommandRequest.builder(ref, body).wait(WaitPlan.COMMITTED).requestId(requestId).build()
        repeat(SEED_ATTEMPTS - 1) {
            try {
                client.wait(request)
                return
            } catch (_: WowProblemException) {
                delay(MILLIS_PER_SECOND)
            }
        }
        client.wait(request)
    }

    /**
     * Waits for the account's walk in [store] to complete, or for [stop]: how long it took from [from], how far it got
     * and its rate.
     */
    private suspend fun walked(store: WalkStore, from: Long, stop: () -> Boolean): Map<String, Any?> {
        val deadline = from + WALK_TIMEOUT_SECONDS * NANOS_PER_SECOND.toLong()
        var walk = store.walk()
        while (walk?.completed != true && System.nanoTime() < deadline && !stop()) {
            delay(POLL_MILLIS)
            walk = store.walk()
        }
        val seconds = (System.nanoTime() - from) / NANOS_PER_SECOND
        return mapOf(
            "completed" to (walk?.completed == true),
            "walked" to walk?.walked,
            "seconds" to seconds,
            "aggregatesPerSecond" to (walk?.walked ?: 0L) / seconds,
        )
    }

    /** [rate] deposits a second over the warm-up and the measured window, open loop; the measured ones' latencies. */
    private suspend fun deposits(client: CommandClient): Map<String, Any?> {
        val latencies = ConcurrentLinkedQueue<Pair<Int, Long>>()
        val failures = ConcurrentHashMap<String, LongAdder>()
        val permits = Semaphore(IN_FLIGHT)
        val total = rate * (warmUpSeconds + measureSeconds)
        val started = System.nanoTime()
        // The scope ends once every deposit sent has answered, before their latencies are counted.
        coroutineScope {
            repeat(total) { index ->
                val due = started + (index * NANOS_PER_SECOND / rate).toLong()
                val wait = (due - System.nanoTime()) / NANOS_PER_MILLI.toLong()
                if (wait > 0) delay(wait)
                permits.acquire()
                launch {
                    try {
                        deposit(client, index)?.let { (latency, failure) ->
                            latencies += index to latency
                            failure?.let { failures.computeIfAbsent(it) { LongAdder() }.increment() }
                        }
                    } finally {
                        permits.release()
                    }
                }
            }
        }
        val measured = latencies.map { it.second }.toLongArray().apply { sort() }
        fun percentile(p: Double): Double? =
            measured.takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * p / PERCENT).toInt()] / NANOS_PER_MILLI }
        return mapOf(
            "ops" to measured.size,
            "failed" to failures.mapValues { it.value.sum() },
            "latencyMs" to mapOf(
                "p50" to percentile(P50),
                "p99" to percentile(P99),
                "p999" to percentile(P999),
                "max" to measured.lastOrNull()?.let { it / NANOS_PER_MILLI },
            ),
            "perSecond" to perSecond(latencies),
        )
    }

    /** Each measured second's deposits, by when each was due: how many, and their slowest, so a stall shows when it was. */
    private fun perSecond(latencies: Collection<Pair<Int, Long>>): List<Map<String, Any?>> =
        latencies.groupBy { (index, _) -> index / rate - warmUpSeconds }.toSortedMap().map { (second, deposits) ->
            mapOf(
                "second" to second,
                "ops" to deposits.size,
                "maxMs" to deposits.maxOf { it.second } / NANOS_PER_MILLI,
            )
        }

    /**
     * Deposit [index], to the account it falls on: its latency and why it failed, or null when it is of the warm-up.
     */
    private suspend fun deposit(client: CommandClient, index: Int): Pair<Long, String?>? {
        val ref = ACCOUNT_TYPE.ref(
            TENANT,
            targets?.let { it[(index * PRIME) % it.size] } ?: accountId((index * PRIME) % aggregates),
        )
        val sent = System.nanoTime()
        val outcome = runCatching {
            client.wait(CommandRequest.builder(ref, Deposit(1)).wait(WaitPlan.COMMITTED).build())
        }
        if (index < rate * warmUpSeconds) return null
        val failure = outcome.exceptionOrNull()?.javaClass?.simpleName
            ?: outcome.getOrNull()?.takeIf { it.status != CommandResult.Status.SUCCEEDED }?.status?.name
        return (System.nanoTime() - sent) to failure
    }

    /** The accounts the deposits go to; null for every one. */
    private var targets: List<String>? = null

    private companion object {
        const val TENANT = "(0)"
        const val OWNERS = 100
        const val PRIME = 7_919
        const val P50 = 50.0
        const val P99 = 99.0
        const val P999 = 99.9

        /** The load generator's account ids, as [ComponentRun]'s. */
        fun accountId(index: Int): String = "acc-%07d".format(index)
    }
}

/** The account's walk record, as the log's registry holds it: whether it is [completed], and the aggregates [walked]. */
class WalkRecord(val completed: Boolean, val walked: Long)

/**
 * The account's log and its walk record, read and written with the store's own driver, as an operator reads the
 * registry (Rebuild and Replay, "Watching it"): the log collection or table `wow_log__transfer__account`, the registry
 * `wow_log_aggregate`.
 */
sealed interface WalkStore : AutoCloseable {
    /** The account's walk record; null when none is recorded. */
    fun walk(): WalkRecord?

    /**
     * Removes the access attributes from the history of every account but [except], as an import writes a log. The
     * account's walk record goes too, unless [walkRecord] keeps it: a run with nothing to walk.
     */
    fun unstamp(except: List<String>, walkRecord: Boolean)

    /** Flushes what the seeding and [unstamp] left to do: the measured window pays for neither. */
    fun settle()

    /** The ids of the first [count] accounts in the walk's order, or the [last] ones: those it reaches last. */
    fun inWalkOrder(count: Int, last: Boolean): List<String>

    /** What the store's sessions of the log are doing now, by what they wait on: one sample. */
    fun waits(): Map<String, Int>

    companion object {
        const val LOG = "wow_log__${TransferContext.NAME}__${TransferContext.ACCOUNT}"
        const val REGISTRY = "wow_log_aggregate"

        /** The store of [backend], reached as [environment] configures the node. */
        fun of(backend: String, environment: Environment): WalkStore = when (backend) {
            "postgres" -> Postgres(
                checkNotNull(environment.getProperty("spring.datasource.url")),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"),
            )
            "mongo" -> Mongo(
                checkNotNull(environment.getProperty("spring.mongodb.uri")),
                checkNotNull(environment.getProperty("wow.mongo.database")),
            )
            else -> throw IllegalArgumentException("Unknown backend '$backend': mongo or postgres.")
        }
    }

    class Postgres(url: String, user: String?, password: String?) : WalkStore {
        private val connection = DriverManager.getConnection(url, user, password)

        override fun walk(): WalkRecord? = connection.prepareStatement(
            "SELECT (access_walk ->> 'completed')::boolean, (access_walk ->> 'walked')::bigint FROM $REGISTRY " +
                "WHERE context = ? AND aggregate = ? AND access_walk IS NOT NULL",
        ).use { query ->
            query.setString(1, TransferContext.NAME)
            query.setString(2, TransferContext.ACCOUNT)
            query.executeQuery().use { rows ->
                if (rows.next()) WalkRecord(rows.getBoolean(1), rows.getLong(2)) else null
            }
        }

        override fun unstamp(except: List<String>, walkRecord: Boolean) {
            connection.prepareStatement(
                "UPDATE $LOG SET access = NULL, access_version = NULL WHERE aggregate_id <> ALL(?)",
            ).use {
                it.setArray(1, connection.createArrayOf("text", except.toTypedArray()))
                it.executeUpdate()
            }
            if (walkRecord) return
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE $REGISTRY SET access_walk = NULL " +
                        "WHERE context = '${TransferContext.NAME}' AND aggregate = '${TransferContext.ACCOUNT}'",
                )
            }
        }

        /** Each its own transaction: a vacuum runs outside one. */
        override fun settle() {
            connection.createStatement().use {
                it.execute("VACUUM")
                it.execute("CHECKPOINT")
            }
        }

        /** The walk's order is the version key's: aggregate id, then tenant. */
        override fun inWalkOrder(count: Int, last: Boolean): List<String> {
            val direction = if (last) "DESC" else "ASC"
            return connection.prepareStatement(
                "SELECT aggregate_id FROM $LOG WHERE version = 1 " +
                    "ORDER BY aggregate_id $direction, tenant_id $direction LIMIT ?",
            ).use { query ->
                query.setInt(1, count)
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

        /** The active sessions of the database but this one, by wait event (`CPU:running` for none). */
        override fun waits(): Map<String, Int> = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT coalesce(wait_event_type, 'CPU') || ':' || coalesce(wait_event, 'running'), count(*) " +
                    "FROM pg_stat_activity WHERE datname = current_database() AND state = 'active' " +
                    "AND pid <> pg_backend_pid() GROUP BY 1",
            ).use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) } }
        }

        override fun close() = connection.close()
    }

    class Mongo(uri: String, database: String) : WalkStore {
        private val client = MongoClients.create(uri)
        private val database = client.getDatabase(database)
        private val registry = Filters.and(
            Filters.eq("context", TransferContext.NAME),
            Filters.eq("aggregate", TransferContext.ACCOUNT),
        )

        override fun walk(): WalkRecord? {
            val walk = database.getCollection(REGISTRY).find(registry).first()
                ?.get("access_walk", Document::class.java) ?: return null
            return WalkRecord(walk.getBoolean("completed") == true, (walk["walked"] as? Number)?.toLong() ?: 0)
        }

        override fun unstamp(except: List<String>, walkRecord: Boolean) {
            database.getCollection(LOG)
                .updateMany(
                    Filters.nin("aggregate_id", except),
                    Updates.combine(Updates.unset("access"), Updates.unset("access_version"))
                )
            if (!walkRecord) database.getCollection(REGISTRY).updateMany(registry, Updates.unset("access_walk"))
        }

        override fun settle() {
            client.getDatabase("admin").runCommand(Document("fsync", 1))
        }

        /** The walk's order: partition by partition, each by the version key. */
        override fun inWalkOrder(count: Int, last: Boolean): List<String> {
            val direction = if (last) -1 else 1
            return database.getCollection(LOG)
                .find(Filters.eq("version", 1))
                .sort(Document("partition", direction).append("aggregate_id", direction).append("tenant_id", direction))
                .limit(count)
                .map { it.getString("aggregate_id") }
                .toList()
        }

        /** The database's operations in progress (`currentOp`), by operation, collection and whether they wait for a lock. */
        override fun waits(): Map<String, Int> {
            val current = client.getDatabase("admin")
                .runCommand(
                    Document(
                        "currentOp",
                        1
                    ).append("active", true).append("ns", Document("\$regex", "^${database.name}\\."))
                )
            return current.getList("inprog", Document::class.java).orEmpty()
                .groupingBy { op ->
                    val collection = op.getString("ns").orEmpty().substringAfter('.')
                    val waiting = if (op.getBoolean("waitingForLock") == true) "lock" else "running"
                    "${op.getString("op")}:$collection:$waiting"
                }
                .eachCount()
        }

        override fun close() = client.close()
    }
}

/**
 * The samples of the store's waits ([WalkStore.waits]) and of the node's connection pool over the measured window: how
 * often each wait was seen, and the pool's most connections in use and most callers waiting for one.
 */
class Waits(private val store: WalkStore, private val pool: (() -> Map<String, Int>)?) {
    private val seen = HashMap<String, Int>()
    private val poolMax = HashMap<String, Int>()
    private var samples = 0

    /** The walk's first and last sample of the window: when, and how many it had walked. */
    private var first: Pair<Long, Long>? = null
    private var last: Pair<Long, Long>? = null

    fun sample() {
        samples++
        runCatching { store.waits() }.getOrNull()?.forEach { (wait, count) -> seen.merge(wait, count, Int::plus) }
        pool?.let { read -> runCatching(read).getOrNull() }?.forEach { (name, value) ->
            poolMax.merge(name, value, ::maxOf)
        }
        runCatching { store.walk() }.getOrNull()?.let { walk ->
            val now = System.nanoTime() to walk.walked
            if (first == null) first = now
            last = now
        }
    }

    /**
     * The walk over the measured window: the aggregates it stamped between the window's first and last sample, and
     * their rate. A walk that completed within the window stamped no more after it, so its rate is a lower bound.
     */
    fun progress(): Map<String, Any?> {
        val (from, to) = (first ?: return emptyMap()) to (last ?: return emptyMap())
        val seconds = (to.first - from.first) / NANOS_PER_SECOND
        val stamped = to.second - from.second
        return mapOf(
            "windowWalked" to stamped,
            "windowSeconds" to seconds,
            "windowPerSecond" to if (seconds > 0) stamped / seconds else null,
        )
    }

    fun summary(): Map<String, Any?> = mapOf(
        "samples" to samples,
        "events" to seen.entries.sortedByDescending { it.value }.associate { it.key to it.value },
        "poolMax" to poolMax.takeIf { pool != null },
    )
}

/**
 * The CPU this JVM (the node and the deposits' client) and the store's container used over the measured window, in
 * cores. The store's comes from its cgroup through `docker exec`, read only at the window's two ends, so the reading
 * costs the run nothing it measures; null where a reading fails (no Docker, no cgroup v2).
 */
class CpuUse(private val container: String) {
    private class Reading(val nanos: Long, val jvm: Double?, val store: Double?, val gcMillis: Long, val gcs: Long)

    private var from: Reading? = null

    fun start() {
        from = read()
    }

    fun end(): Map<String, Any?> {
        val start = from ?: return emptyMap()
        val end = read()
        val seconds = (end.nanos - start.nanos) / NANOS_PER_SECOND
        fun cores(of: (Reading) -> Double?): Double? = of(end)?.let { last -> of(start)?.let { (last - it) / seconds } }
        return mapOf(
            "windowSeconds" to seconds,
            "jvmCores" to cores { it.jvm },
            "storeCores" to cores { it.store },
            "gcMillis" to end.gcMillis - start.gcMillis,
            "gcs" to end.gcs - start.gcs,
        )
    }

    private fun read(): Reading {
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        return Reading(
            System.nanoTime(),
            jvmSeconds(),
            storeSeconds(),
            collectors.sumOf { it.collectionTime.coerceAtLeast(0) },
            collectors.sumOf { it.collectionCount.coerceAtLeast(0) },
        )
    }

    private fun jvmSeconds(): Double? = (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
        ?.processCpuTime?.takeIf { it >= 0 }?.let { it / NANOS_PER_SECOND }

    private fun storeSeconds(): Double? = runCatching {
        val process = ProcessBuilder("docker", "exec", container, "cat", "/sys/fs/cgroup/cpu.stat")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.lineSequence().first { it.startsWith("usage_usec") }.substringAfter(' ').trim().toLong() /
            MICROS_PER_SECOND
    }.getOrNull()
}

/**
 * The node's JDBC pool, read through its HikariCP pool bean: connections in use, callers waiting for one, its size;
 * null for a node without one (MongoDB's driver keeps its pool to itself).
 */
fun poolOf(context: ConfigurableApplicationContext): (() -> Map<String, Int>)? {
    val bean = context.getBeansOfType(DataSource::class.java).values.firstNotNullOfOrNull { source ->
        runCatching { source.javaClass.getMethod("getHikariPoolMXBean").invoke(source) }.getOrNull()
    } ?: return null
    fun read(name: String): Int = bean.javaClass.getMethod(name).invoke(bean) as Int
    return {
        mapOf(
            "active" to read("getActiveConnections"),
            "pending" to read("getThreadsAwaitingConnection"),
            "total" to read("getTotalConnections"),
        )
    }
}
