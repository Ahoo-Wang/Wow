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

package me.ahoo.wow.routing.stage2

import me.ahoo.test.asserts.assert
import me.ahoo.wow.routing.Access
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spike H2, stage 2 (documentation/designs/decisions.md, H2 and D155): three node JVMs on the Spring Boot host,
 * each receiving commands over its public HTTP route and forwarding them to the partition's owner over the real
 * cluster channel (HTTP/2 on the internal port); the log and the snapshots on a durable PostgreSQL (default
 * `synchronous_commit` and fsync). Per access distribution (stage 1's uniform and Zipf s=1.1) and router (Partitioned,
 * Local), fresh nodes take a fixed open-loop rate; p50/p99/p999, throughput, reads per command and the nodes' CPU are
 * measured in a steady window. In the Partitioned runs an owner is then killed (`SIGKILL`) and the load goes on: per
 * partition it owned, how long its commands failed and when they were back to normal, and when its lease moved. The
 * log is audited against the client's answers for lost and duplicated commits.
 *
 * Runs only through the task `h2Stage2` (see the build script and [Stage2Settings]); writes `report.json`,
 * `report.md` and each node's output to `build/h2-stage2/`.
 */
class H2Stage2Spike {
    private val settings = Stage2Settings.fromSystemProperties()
    private val logs = File(settings.directory, "nodes")
    private var runs = 0

    @Test
    fun measure() {
        logs.deleteRecursively()
        logs.mkdirs()
        val started = System.nanoTime()
        val postgres = WowContainers.newPostgres().withCommand(
            "postgres",
            "-c",
            "max_connections=$MAX_CONNECTIONS",
            // The statement counters give the reads per command; durability stays the default.
            "-c",
            "shared_preload_libraries=pg_stat_statements",
        )
        postgres.start()
        val results = mutableListOf<RunStats>()
        try {
            Database(postgres, settings.partitions).use { database ->
                log("seeding ${settings.aggregates} accounts")
                database.seed(settings.aggregates)
                settings.distributions.forEach { distribution ->
                    val access = when (distribution) {
                        "uniform" -> Access.Uniform(settings.aggregates)
                        else -> Access.Zipf(settings.aggregates, ZIPF_S)
                    }
                    settings.routers.forEach { router ->
                        results += run(postgres, database, distribution, access, router).also { print(it) }
                    }
                }
            }
        } finally {
            postgres.stop()
        }
        val minutes = (System.nanoTime() - started) / NANOS_PER_MINUTE
        write(results, minutes)
        results.forEach { run ->
            run.audit.lost.assert().describedAs("${run.distribution} ${run.router}: commits lost").isZero()
            run.audit.duplicates.assert().describedAs("${run.distribution} ${run.router}: duplicates").isZero()
        }
    }

    @Suppress("LongMethod")
    private fun run(
        postgres: org.testcontainers.postgresql.PostgreSQLContainer,
        database: Database,
        distribution: String,
        access: Access,
        router: Router,
    ): RunStats {
        val runId = "r${++runs}"
        log("$runId: $distribution, ${router.label}: starting 3 nodes")
        val nodes = NodeProcess.startAll(NODE_IDS, settings, postgres, router, logs, runId)
        try {
            if (router == Router.PARTITIONED) awaitOwners(database)
            if (runs == 1) awaitSnapshots(database)
            val kill = router == Router.PARTITIONED
            val warmUp = settings.warmUp.toNanos()
            val measured = settings.duration.toNanos()
            val seconds = (warmUp + measured + if (kill) settings.recovery.toNanos() else 0L) / NANOS_PER_SECOND
            val start = System.nanoTime() + START_DELAY
            val windowStart = start + warmUp
            val windowEnd = windowStart + measured
            val cpu = arrayOf(Duration.ZERO, Duration.ZERO)
            var statements = StatementCounts.NONE
            val victim = VICTIM
            var killedAt = 0L
            var victimPartitions = emptyList<Int>()
            val takeovers = ConcurrentHashMap<Int, Pair<Long, String>>()
            val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "h2-timer").apply { isDaemon = true } }
            OpenLoad(nodes, access, settings.aggregates, settings.partitions, runId).use { load ->
                val begin = timers.schedule({
                    database.resetStatements()
                    cpu[0] = nodes.fold(Duration.ZERO) { sum, node -> sum + node.cpu() }
                }, windowStart - System.nanoTime(), TimeUnit.NANOSECONDS)
                val end = timers.schedule({
                    statements = database.statements()
                    cpu[1] = nodes.fold(Duration.ZERO) { sum, node -> sum + node.cpu() }
                    if (kill) {
                        val owners = database.owners()
                        victimPartitions = owners.filterValues { it == NODE_IDS[victim] }.keys.toList()
                        load.exclude(victim)
                        killedAt = System.nanoTime()
                        nodes[victim].kill()
                        log("$runId: killed ${NODE_IDS[victim]}, the owner of ${victimPartitions.size} partitions")
                        watchLeases(database, victimPartitions, takeovers, start + seconds * NANOS_PER_SECOND)
                    }
                }, windowEnd - System.nanoTime(), TimeUnit.NANOSECONDS)
                val recording = load.run(start, settings.rate, seconds.toDouble())
                // The window's end (and the kill) may still be running when the last answers are in.
                begin.get()
                end.get()
                timers.shutdown()
                val loadEnd = start + seconds * NANOS_PER_SECOND
                val steady = Stage2Analysis.window(recording, windowStart, windowEnd)
                val finished = steady.outcomes.values.sum().coerceAtLeast(1).toDouble()
                val inLog = database.committedRequests(runId)
                return RunStats(
                    distribution = distribution,
                    router = router.label,
                    offeredRate = settings.rate,
                    steady = steady,
                    statements = statements,
                    readsPerCommand = (statements.logReads + statements.snapshotReads) / finished,
                    tailReadsPerCommand = statements.tailReads / finished,
                    statementsPerCommand = with(statements) {
                        logReads + snapshotReads + tailReads + appends + snapshotWrites + other
                    } / finished,
                    // Unknown (NaN) where the platform does not tell a child's CPU time.
                    nodeCpuMicrosPerCommand = (cpu[1] - cpu[0]).toNanos().takeIf { it > 0 }
                        ?.let { it / finished / NANOS_PER_MICRO } ?: Double.NaN,
                    kill = if (kill && killedAt > 0) {
                        Stage2Analysis.kill(
                            recording, victim, NODE_IDS[victim], victimPartitions, killedAt, windowStart, loadEnd,
                            steady.p999Millis, takeovers,
                        )
                    } else {
                        null
                    },
                    audit = Stage2Analysis.audit(recording, runId, inLog),
                    timeline = Stage2Analysis.timeline(recording, windowStart, loadEnd),
                )
            }
        } finally {
            nodes.forEach { it.close() }
        }
    }

    /** Waits until every partition of the commands has a live lease, the three nodes among their owners. */
    private fun awaitOwners(database: Database) {
        val deadline = System.nanoTime() + OWNERSHIP_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            val owners = database.owners()
            if (owners.size == settings.partitions && owners.values.toSet().size == NODE_IDS.size) {
                // Each node's routes learn the owners every second (route refresh interval).
                Thread.sleep(ROUTES_SETTLE.toMillis())
                return
            }
            Thread.sleep(POLL.toMillis())
        }
        error(
            "The nodes did not share the ${settings.partitions} partitions in $OWNERSHIP_TIMEOUT: ${database.owners()}"
        )
    }

    /** Waits until the snapshot subscription has caught up with the seeded log, for at most a bound. */
    private fun awaitSnapshots(database: Database) {
        val timeout = if (settings.smoke) SMOKE_SNAPSHOT_TIMEOUT else SNAPSHOT_TIMEOUT
        val deadline = System.nanoTime() + timeout.toNanos()
        var lag = database.snapshotLag()
        while (System.nanoTime() < deadline) {
            lag = database.snapshotLag()
            if (lag != null && lag <= 0) return
            Thread.sleep(POLL.toMillis())
        }
        log("the snapshot subscription still trails the log by $lag entries after $timeout; measuring anyway")
    }

    /** Polls the lease table until [until]: when each of [partitions] got a live owner other than the victim. */
    private fun watchLeases(
        database: Database,
        partitions: List<Int>,
        takeovers: MutableMap<Int, Pair<Long, String>>,
        until: Long,
    ) {
        thread(name = "h2-leases", isDaemon = true) {
            while (System.nanoTime() < until && takeovers.size < partitions.size) {
                val owners = database.owners()
                val now = System.nanoTime()
                partitions.forEach { partition ->
                    val owner = owners[partition]
                    if (owner != null && owner != NODE_IDS[VICTIM]) takeovers.putIfAbsent(partition, now to owner)
                }
                Thread.sleep(LEASE_POLL.toMillis())
            }
        }
    }

    private fun print(run: RunStats) {
        val steady = run.steady
        val kill = run.kill?.let {
            val unavailable = "%.0f".format(it.maxUnavailableMillis)
            " | kill: unavailable max $unavailable ms, failures ${it.afterKill.failureCodes}"
        }
        log(
            "  ${run.distribution} ${run.router}: ${"%.0f".format(steady.throughput)}/s p50 ${"%.2f".format(steady.p50Millis)} " +
                "p99 ${"%.2f".format(steady.p99Millis)} p999 ${"%.2f".format(steady.p999Millis)} ms, " +
                "reads ${"%.2f".format(run.readsPerCommand)}, failures ${steady.failureCodes}${kill.orEmpty()}",
        )
    }

    private fun write(results: List<RunStats>, minutes: Double) {
        val environment = mapOf(
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
            "cpus" to Runtime.getRuntime().availableProcessors(),
            "java" to System.getProperty("java.version"),
            "postgres" to "${WowContainers.POSTGRES_IMAGE} (durable defaults)",
            "minutes" to "%.1f".format(minutes),
        )
        val json = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .enable(SerializationFeature.INDENT_OUTPUT)
            // Unknown and unbounded values (NaN, Infinity) stay valid JSON.
            .enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
            .build()
        File(settings.directory, "report.json").writeText(
            json.writeValueAsString(
                mapOf(
                    "settings" to settings.copy(classpath = "").let {
                        mapOf(
                            "smoke" to it.smoke,
                            "duration" to it.duration.toString(),
                            "warmUp" to it.warmUp.toString(),
                            "recovery" to it.recovery.toString(),
                            "rate" to it.rate,
                            "aggregates" to it.aggregates,
                            "zipfS" to ZIPF_S,
                            "partitions" to it.partitions,
                            "nodes" to NODE_IDS,
                            "nodeHeap" to it.nodeHeap,
                            "nodePool" to it.nodePool,
                        )
                    },
                    "environment" to environment,
                    "runs" to results,
                ),
            ),
        )
        val markdown = Stage2Report.markdown(settings, environment, results)
        File(settings.directory, "report.md").writeText(markdown)
        println(markdown)
    }

    private fun log(message: String) = println("[h2 stage 2 ${LocalTime.now().withNano(0)}] $message")

    private companion object {
        val NODE_IDS = listOf("node-0", "node-1", "node-2")
        const val VICTIM = 1
        const val ZIPF_S = 1.1
        const val MAX_CONNECTIONS = 200
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MICRO = 1_000.0
        const val NANOS_PER_MINUTE = 60_000_000_000.0
        const val START_DELAY = 50_000_000L
        val OWNERSHIP_TIMEOUT: Duration = Duration.ofSeconds(90)
        val ROUTES_SETTLE: Duration = Duration.ofSeconds(3)
        val SNAPSHOT_TIMEOUT: Duration = Duration.ofMinutes(3)
        val SMOKE_SNAPSHOT_TIMEOUT: Duration = Duration.ofSeconds(20)
        val POLL: Duration = Duration.ofMillis(200)
        val LEASE_POLL: Duration = Duration.ofMillis(100)
    }
}
