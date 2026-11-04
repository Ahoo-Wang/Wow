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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE
import org.bson.Document
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder
import kotlin.system.exitProcess

/**
 * The access walk's scale check (the `access-walk-*` suites, kind `measure`): the benchmark's accounts on the V10 node
 * and one store, in this JVM, as [ComponentRun] runs them, through the node's public API and the store's own driver.
 *
 * A first node opens `--aggregates` accounts and deposits into each until it has `--streams` streams, then completes
 * its walk of them. Unless `--walk=none` (the A/A arm, nothing to walk), the store's history then loses its access
 * attributes and the account's walk record, as a log an import wrote holds none. A second node then starts: its walk
 * stamps the accounts at `wow.access.walk-rate`, while `--rate` deposits a second (E1, open loop) go to the same
 * accounts, measured over `--measure` seconds after `--warm-up`. It writes `measure.json` to `--out`: the walk's time
 * from the second node's start to its completion and its rate, and the deposits' latencies.
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
    )
    val out = File(options["out"] ?: ".").apply { mkdirs() }
    val node = {
        SpringApplicationBuilder(TransferServer::class.java)
            .web(WebApplicationType.NONE)
            .profiles(backend)
            .run(
                "--wow.internal.port=0",
                *options.filterKeys { it.startsWith("spring.") || it.startsWith("wow.") }
                    .map { "--${it.key}=${it.value}" }
                    .toTypedArray(),
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
private const val PERCENT = 100.0

/** One walk run: the history a first node writes, then the second node's walk under the deposits. */
class WalkRun(
    private val aggregates: Int,
    private val streams: Int,
    private val walks: Boolean,
    private val rate: Int,
    private val warmUpSeconds: Int,
    private val measureSeconds: Int,
) {
    init {
        require(aggregates > 0 && streams > 0) { "A walk needs aggregates with streams." }
    }

    fun run(backend: String, node: () -> ConfigurableApplicationContext): Map<String, Any?> =
        runBlocking(Dispatchers.Default) {
            val seedStarted = System.nanoTime()
            val seeding = node()
            val walkRate = seeding.environment.getProperty("wow.access.walk-rate")
            try {
                seed(seeding.getBean(CommandClient::class.java))
                WalkStore.of(backend, seeding.environment).use { store ->
                    // The first node walks the history it wrote: a node starting after it finds it walked.
                    walked(store, seedStarted)
                    if (walks) store.unstamp()
                }
            } finally {
                seeding.close()
            }
            val seedSeconds = (System.nanoTime() - seedStarted) / NANOS_PER_SECOND
            val started = System.nanoTime()
            val walking = node()
            try {
                WalkStore.of(backend, walking.environment).use { store ->
                    // The walk is timed while the deposits run, not after them.
                    val walk = async { if (walks) walked(store, started) else null }
                    val deposits = if (rate > 0) deposits(walking.getBean(CommandClient::class.java)) else null
                    mapOf(
                        "schema" to "wow-e2e-bench/measure/1",
                        "program" to "walk",
                        "aggregates" to aggregates,
                        "streams" to streams,
                        "walks" to walks,
                        "walkRate" to walkRate,
                        "rate" to rate,
                        "warmUpSeconds" to warmUpSeconds,
                        "measureSeconds" to measureSeconds,
                        "seedSeconds" to seedSeconds,
                        "walk" to walk.await(),
                        "e1" to deposits,
                        "note" to "In one JVM: the walk's time is from the second node's start to its completion, " +
                            "the deposits' latencies an open loop over the measured window.",
                    )
                }
            } finally {
                walking.close()
            }
        }

    /** Opens the accounts, then deposits into each until it holds [streams] streams. */
    private suspend fun seed(client: CommandClient) = coroutineScope {
        val permits = Semaphore(SEED_CONCURRENCY)
        repeat(aggregates) { index ->
            launch {
                permits.withPermit {
                    val ref = ACCOUNT_TYPE.ref(TENANT, accountId(index))
                    val open = OpenAccount("owner-${index % OWNERS}", BALANCE)
                    client.wait(CommandRequest.builder(ref, open).wait(WaitPlan.COMMITTED).build())
                    repeat(streams - 1) {
                        client.wait(CommandRequest.builder(ref, Deposit(1)).wait(WaitPlan.COMMITTED).build())
                    }
                }
            }
        }
    }

    /** Waits for the account's walk in [store] to complete: how long it took from [from], and its rate. */
    private suspend fun walked(store: WalkStore, from: Long): Map<String, Any?> {
        val deadline = from + WALK_TIMEOUT_SECONDS * NANOS_PER_SECOND.toLong()
        var walk = store.walk()
        while (walk?.completed != true && System.nanoTime() < deadline) {
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
        val latencies = ConcurrentLinkedQueue<Long>()
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
                            latencies += latency
                            failure?.let { failures.computeIfAbsent(it) { LongAdder() }.increment() }
                        }
                    } finally {
                        permits.release()
                    }
                }
            }
        }
        val measured = latencies.toLongArray().apply { sort() }
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
        )
    }

    /**
     * Deposit [index], to the account it falls on: its latency and why it failed, or null when it is of the warm-up.
     */
    private suspend fun deposit(client: CommandClient, index: Int): Pair<Long, String?>? {
        val ref = ACCOUNT_TYPE.ref(TENANT, accountId((index * PRIME) % aggregates))
        val sent = System.nanoTime()
        val outcome = runCatching {
            client.wait(CommandRequest.builder(ref, Deposit(1)).wait(WaitPlan.COMMITTED).build())
        }
        if (index < rate * warmUpSeconds) return null
        val failure = outcome.exceptionOrNull()?.javaClass?.simpleName
            ?: outcome.getOrNull()?.takeIf { it.status != CommandResult.Status.SUCCEEDED }?.status?.name
        return (System.nanoTime() - sent) to failure
    }

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

    /** Removes the access attributes from the account's history, and its walk record: a log as an import writes it. */
    fun unstamp()

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

        override fun unstamp() {
            connection.createStatement().use {
                it.executeUpdate("UPDATE $LOG SET access = NULL, access_version = NULL")
                it.executeUpdate(
                    "UPDATE $REGISTRY SET access_walk = NULL " +
                        "WHERE context = '${TransferContext.NAME}' AND aggregate = '${TransferContext.ACCOUNT}'",
                )
            }
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

        override fun unstamp() {
            database.getCollection(LOG)
                .updateMany(
                    Filters.exists("version"),
                    Updates.combine(Updates.unset("access"), Updates.unset("access_version"))
                )
            database.getCollection(REGISTRY).updateMany(registry, Updates.unset("access_walk"))
        }

        override fun close() = client.close()
    }
}
