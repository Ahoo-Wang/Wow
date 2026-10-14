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

package me.ahoo.wow.routing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Spike H2, phase 1 (documentation/designs/v10/decisions.md): does affinity routing (Partitioned) pay for its
 * forwarding hop? Simulated nodes, each with its own pool, kernel, executor and state cache, share one real
 * PostgreSQL log and snapshot store; the cluster channel serializes commands for real and injects 0.3 to 1 ms.
 *
 * Runs only with `-Dwow.spike.h2=true` (`-Dwow.spike.h2.quick=true` for a shortened matrix); writes
 * `build/spike-h2.json` and `build/spike-h2.md`.
 */
class AffinityRoutingSpike {
    private val quick = System.getProperty("wow.spike.h2.quick") == "true"
    private val repetitions = if (quick) 1 else REPETITIONS
    private val warmupSeconds = if (quick) 1.0 else WARMUP_SECONDS
    private val measureSeconds = if (quick) 2.0 else MEASURE_SECONDS
    private val contexts = AtomicInteger()

    @Test
    fun measure() {
        assumeTrue(
            System.getProperty("wow.spike.h2") == "true" || quick,
            "Run with -Dwow.spike.h2=true",
        )
        val container = WowContainers.newPostgres()
            // The routing effect under test is about a millisecond; fsync and checkpoint stalls of a laptop's Docker VM
            // reach hundreds of milliseconds and would drown it, so commits do not wait for the WAL flush.
            .withCommand(
                "postgres",
                "-c", "max_connections=$MAX_CONNECTIONS",
                "-c", "synchronous_commit=off",
                "-c", "shared_buffers=1GB",
                "-c", "max_wal_size=8GB",
                "-c", "checkpoint_timeout=30min",
            )
        container.start()
        try {
            run(container)
        } finally {
            container.stop()
        }
    }

    private fun run(container: PostgreSQLContainer) {
        val admin = pool(container, ADMIN_POOL)
        val measurements = mutableListOf<Measurement>()
        val saturations = mutableListOf<Measurement>()
        val started = System.currentTimeMillis()
        workloads().forEachIndexed { index, workload ->
            val context = "h2w${contexts.incrementAndGet()}"
            val model = accountModel(context, workload.shape.recentCap)
            log("[$index] ${workload.name}: seeding $context")
            seed(admin, context, model, workload.shape)
            if (index == 0) {
                log("JIT warm-up")
                saturation(container, context, model, workload, Routing.PARTITIONED)
            }
            val saturation = Routing.entries.associateWith { routing ->
                saturation(container, context, model, workload, routing).also {
                    saturations += it
                    log("  saturation ${routing.label}: ${"%.0f".format(it.throughput)}/s")
                }
            }
            val localSaturation = saturation.getValue(Routing.LOCAL).throughput
            val loads = LOADS.map { (label, fraction) -> label to localSaturation * fraction } +
                // Concurrent hot spots crush Local's saturation; also probe the rate the owners can actually carry.
                if (workload.access is Access.Hot && !workload.sequential) {
                    listOf(HOT_PROBE to saturation.getValue(Routing.PARTITIONED).throughput * HOT_PROBE_FRACTION)
                } else {
                    emptyList()
                }
            loads.forEach { (label, offered) ->
                val rate = offered.coerceAtLeast(MIN_RATE)
                repeat(if (workload.decisive && !quick) DECISIVE_REPETITIONS else repetitions) { repetition ->
                    // Rotate the order so no variant always runs first (or last) on a growing log.
                    val order = Routing.entries.indices.map { Routing.entries[(it + repetition) % Routing.entries.size] }
                    order.forEach { routing ->
                        val measured = runCluster(container, context, model, workload, routing) { driver, counters ->
                            openLoop(
                                driver, counters, workload, routing, label, rate, repetition + 1,
                                warmupSeconds, measureSeconds,
                            )
                        }
                        measurements += measured
                        log(
                            "  $label ${routing.label} #${repetition + 1}: ${"%.0f".format(measured.throughput)}/s " +
                                "p50 ${"%.2f".format(measured.p50Millis)} p99 ${"%.2f".format(measured.p99Millis)} " +
                                "p999 ${"%.2f".format(measured.p999Millis)} ms, hit ${"%.2f".format(measured.cacheHitRate)}, " +
                                "reads ${"%.2f".format(measured.readsPerCommand)}, contended ${measured.contended}, " +
                                "steady p99 ${"%.2f".format(measured.steadyP99Millis)} worst-second ${"%.2f".format(measured.worstSecondP99Millis)} ms, gc ${measured.gcMillis} ms",
                        )
                    }
                }
            }
            drop(admin, context)
        }
        admin.close()
        val minutes = (System.currentTimeMillis() - started) / MILLIS_PER_MINUTE
        write(saturations, measurements, minutes)
    }

    /** The pruned matrix (see the report): every access pattern on 3 nodes, the decisive ones again on 5. */
    private fun workloads(): List<Workload> {
        val uniform = Access.Uniform(AGGREGATES)
        val zipf = Access.Zipf(AGGREGATES, ZIPF_S)
        val hot = Access.Hot(AGGREGATES, HOT)
        val all = listOf(
            Workload(uniform, sequential = false, StateShape.SMALL, nodes = 3, decisive = true),
            Workload(zipf, sequential = false, StateShape.SMALL, nodes = 3, decisive = true),
            Workload(zipf, sequential = true, StateShape.SMALL, nodes = 3),
            Workload(hot, sequential = false, StateShape.SMALL, nodes = 3),
            Workload(hot, sequential = true, StateShape.SMALL, nodes = 3),
            Workload(uniform, sequential = false, StateShape.LARGE, nodes = 3),
            Workload(zipf, sequential = false, StateShape.LARGE, nodes = 3),
            Workload(uniform, sequential = false, StateShape.SMALL, nodes = 5),
            Workload(zipf, sequential = false, StateShape.SMALL, nodes = 5),
            Workload(hot, sequential = false, StateShape.SMALL, nodes = 5),
        )
        return if (quick) listOf(all[1], all[3]) else all
    }

    private fun saturation(
        container: PostgreSQLContainer,
        context: String,
        model: AggregateModel<AccountState>,
        workload: Workload,
        routing: Routing,
    ): Measurement = runCluster(container, context, model, workload, routing) { driver, counters ->
        closedLoop(driver, counters, workload, routing, SATURATION_WORKERS, SATURATION_WARMUP, SATURATION_MEASURE)
    }

    /** Builds fresh nodes (cold caches, own pools) for one run, and closes them after it. */
    private fun runCluster(
        container: PostgreSQLContainer,
        context: String,
        model: AggregateModel<AccountState>,
        workload: Workload,
        routing: Routing,
        body: (Driver, Counters) -> Measurement,
    ): Measurement {
        val counters = Counters()
        val nodes = List(workload.nodes) { index ->
            Node(
                index,
                pool(container, NODE_POOL),
                context,
                PARTITIONS,
                model,
                workload.shape,
                CACHE_CAPACITY,
                counters
            )
        }
        val hop = Hop()
        val cluster = Cluster(nodes, PARTITIONS, routing, hop, counters)
        try {
            return body(Driver(cluster, workload, model.type, "r${runs.incrementAndGet()}"), counters)
        } finally {
            runBlocking { cluster.close() }
            hop.close()
        }
    }

    /**
     * Opens [AGGREGATES] accounts: version 1 holds the opening and, for a large state, enough deposits to fill it;
     * a snapshot of version 1; then a tail of 0 until [StateShape.seededTail] deposits after it.
     */
    @Suppress("InjectDispatcher") // The seeding workers run on the shared pool, as the runs do.
    private fun seed(admin: HikariDataSource, context: String, model: AggregateModel<AccountState>, shape: StateShape) {
        PostgresEventLog(admin, context, PARTITIONS).createSchema()
        PostgresSnapshotStore(admin, context).createSchema()
        val log = PostgresEventLog(admin, context, PARTITIONS)
        val snapshots = PostgresSnapshotStore(admin, context)
        val codec = EventCodec()
        val type = model.type
        val memo = "m".repeat(shape.memoLength)
        val next = AtomicInteger()
        val now = System.currentTimeMillis()
        runBlocking(Dispatchers.Default) {
            repeat(SEED_WORKERS) {
                launch {
                    while (true) {
                        val index = next.getAndIncrement()
                        if (index >= AGGREGATES) break
                        val ref = type.ref(Driver.TENANT, Driver.accountId(index))
                        val opening = listOf<Any>(AccountOpened("owner-$index", INITIAL_BALANCE)) +
                            List(shape.recentCap) { Deposited(1, memo) }
                        val state = opening.fold(
                            model.initialState(ref)
                        ) { current, event -> model.evolve(current, event) }
                        append(log, codec, model, ref, 1, "open-$index", OpenAccount::class.java, opening)
                        snapshots.save(codec.snapshotOf(model, ref, state, 1, Scope.NONE, now, now))
                        val tail = if (shape.seededTail > 1) Random.nextInt(shape.seededTail) else 0
                        for (version in 2..tail + 1) {
                            append(
                                log,
                                codec,
                                model,
                                ref,
                                version,
                                "seed-$index-$version",
                                Deposit::class.java,
                                listOf(Deposited(1, memo))
                            )
                        }
                    }
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun append(
        log: PostgresEventLog,
        codec: EventCodec,
        model: AggregateModel<AccountState>,
        ref: me.ahoo.wow.api.aggregate.AggregateRef,
        version: Int,
        requestId: String,
        commandClass: Class<*>,
        events: List<Any>,
    ) {
        val outcome = log.append(
            EventStreamRecord(
                ref = ref,
                version = version,
                commandId = requestId,
                requestId = requestId,
                commandType = model.commandType(commandClass).name,
                commitTime = System.currentTimeMillis(),
                events = events.map { codec.encode(model, it) },
                scope = Scope.NONE,
            ),
        )
        check(outcome is AppendOutcome.Committed) { "Seeding $ref v$version failed: $outcome" }
    }

    private fun drop(admin: HikariDataSource, context: String) {
        admin.connection.use { connection ->
            connection.createStatement().use { statement ->
                val tables = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name LIKE 'wow\\_%' AND table_name LIKE '%$context%'",
                ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                tables.forEach { statement.execute("DROP TABLE IF EXISTS \"$it\"") }
                statement.execute("DELETE FROM wow_log_aggregate WHERE context = '$context'")
                statement.execute("DELETE FROM wow_format WHERE structure LIKE '%$context%'")
            }
        }
    }

    private fun pool(container: PostgreSQLContainer, size: Int) = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = size
            minimumIdle = size
        },
    )

    private fun write(saturations: List<Measurement>, measurements: List<Measurement>, minutes: Double) {
        val json = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build()
        File("build").mkdirs()
        File("build/spike-h2.json").writeText(
            json.writeValueAsString(
                mapOf(
                    "minutes" to minutes,
                    "settings" to settings(),
                    "saturation" to saturations,
                    "runs" to measurements,
                ),
            ),
        )
        val table = SpikeReport.markdown(saturations, measurements)
        File("build/spike-h2.md").writeText(table)
        println(table)
        log("done in ${"%.1f".format(minutes)} min")
    }

    private fun settings() = mapOf(
        "aggregates" to AGGREGATES,
        "zipfS" to ZIPF_S,
        "hot" to HOT,
        "partitions" to PARTITIONS,
        "cacheCapacityPerNode" to CACHE_CAPACITY,
        "nodePool" to NODE_POOL,
        "saturationWorkers" to SATURATION_WORKERS,
        "saturationSeconds" to listOf(SATURATION_WARMUP, SATURATION_MEASURE),
        "openLoopSeconds" to listOf(warmupSeconds, measureSeconds),
        "repetitions" to repetitions,
        "decisiveRepetitions" to DECISIVE_REPETITIONS,
        "hopMicros" to listOf(Cluster.MIN_HOP_NANOS / NANOS_PER_MICRO, Cluster.MAX_HOP_NANOS / NANOS_PER_MICRO),
        "loads" to LOADS,
        "postgres" to "postgres:17-alpine, synchronous_commit=off, shared_buffers=1GB, max_wal_size=8GB, checkpoint_timeout=30min",
    )

    private fun log(message: String) = println("[h2 ${java.time.LocalTime.now().withNano(0)}] $message")

    private companion object {
        val runs = AtomicInteger()
        const val AGGREGATES = 100_000
        const val ZIPF_S = 1.1
        const val HOT = 10
        const val PARTITIONS = 64
        const val CACHE_CAPACITY = 10_000
        const val NODE_POOL = 16
        const val ADMIN_POOL = 32
        const val MAX_CONNECTIONS = 300
        const val SEED_WORKERS = 128
        const val SATURATION_WORKERS = 128
        const val SATURATION_WARMUP = 2.0
        const val SATURATION_MEASURE = 3.0
        const val WARMUP_SECONDS = 3.0
        const val MEASURE_SECONDS = 5.0
        const val REPETITIONS = 3
        const val DECISIVE_REPETITIONS = 5
        const val MIN_RATE = 50.0
        const val INITIAL_BALANCE = 1_000_000_000L
        const val MILLIS_PER_MINUTE = 60_000.0
        const val NANOS_PER_MICRO = 1_000L
        val LOADS = listOf("50%" to 0.5, "80%" to 0.8)
        const val HOT_PROBE = "50% of Partitioned"
        const val HOT_PROBE_FRACTION = 0.5
    }
}
