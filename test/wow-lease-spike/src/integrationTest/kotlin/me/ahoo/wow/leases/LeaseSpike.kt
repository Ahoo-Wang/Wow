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

package me.ahoo.wow.leases

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.Hrw
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Spike H6 (documentation/designs/v10/decisions.md): do storage leases support membership and rebalancing? Nodes in
 * one JVM, each with its own clients of a real PostgreSQL or MongoDB, its own `PartitionOwnership`, subscriptions,
 * router and coordinator, joined by the loopback cluster channel, under a steady write load. Each repetition starts
 * three nodes and then joins, leaves gracefully and kills nodes one at a time (see [PLAN]), measuring each change.
 *
 * Runs only with `-Dwow.spike.h6=true`; `-Dwow.spike.h6.quick=true` runs one repetition at 64 partitions per
 * backend and configuration to check the harness. Writes `build/spike-h6.json` and `build/spike-h6.md`.
 */
class LeaseSpike {
    private val quick = System.getProperty("wow.spike.h6.quick") == "true"
    private val repetitions = System.getProperty("wow.spike.h6.repetitions")?.toIntOrNull()
        ?: if (quick) 1 else REPETITIONS
    private val only = System.getProperty("wow.spike.h6.only").orEmpty()
    private val contexts = AtomicInteger()
    private val steps = mutableListOf<StepResult>()
    private val leaseSeries = mutableListOf<LeaseSeries>()
    private val backends = linkedMapOf<String, String>()
    private val started = System.currentTimeMillis()

    @Test
    fun measure() {
        assumeTrue(
            System.getProperty("wow.spike.h6") == "true" || quick,
            "Run with -Dwow.spike.h6=true",
        )
        val factories = listOf<Pair<String, () -> Backend>>("PostgreSQL" to ::PostgresBackend, "MongoDB" to ::MongoBackend)
            .filter { only.isEmpty() || it.first.equals(only, ignoreCase = true) }
        for ((_, factory) in factories) {
            factory().use { backend ->
                backends[backend.name] = backend.description
                log("${backend.name}: ${backend.description}")
                if (!quick) {
                    // One discarded repetition warms the JIT, the pools and the database, which otherwise slow the
                    // first lease rounds of the first series enough for renewals to miss (see the report).
                    log("${backend.name} warm-up")
                    runBlocking(Dispatchers.Default) {
                        repetition(backend, "warm-up", CONFIGS.getValue("default"), QUICK_PARTITIONS, 0, LeaseMetrics())
                    }
                }
                for ((config, options) in CONFIGS) {
                    for (partitions in if (quick) listOf(QUICK_PARTITIONS) else PARTITIONS) {
                        series(backend, config, options, partitions)
                        write()
                    }
                }
            }
        }
        write()
    }

    private fun series(backend: Backend, config: String, options: ClusterOptions, partitions: Int) {
        val metrics = LeaseMetrics()
        val began = System.currentTimeMillis()
        repeat(repetitions) { index ->
            val repetition = index + 1
            log("${backend.name} $config P=$partitions #$repetition")
            runBlocking(Dispatchers.Default) {
                steps += repetition(backend, config, options, partitions, repetition, metrics)
            }
        }
        val seconds = (System.currentTimeMillis() - began) / MILLIS
        leaseSeries += LeaseSeries(
            backend.name,
            config,
            partitions,
            seconds,
            metrics.failures.sum(),
            metrics.latencies.entries.sortedBy { it.key }.map { (operation, values) ->
                val millis = values.map { it / MICROS_PER_MILLI }
                LeaseOperation(
                    operation,
                    millis.size,
                    millis.size / seconds,
                    Stats.quantile(millis, P50),
                    Stats.quantile(millis, P99),
                    millis.max(),
                )
            },
        )
    }

    @Suppress("LongMethod")
    private suspend fun repetition(
        backend: Backend,
        config: String,
        options: ClusterOptions,
        partitions: Int,
        repetition: Int,
        metrics: LeaseMetrics,
    ): List<StepResult> {
        val context = "h6c${contexts.incrementAndGet()}"
        backend.createSchema(context, partitions)
        val timeline = Timeline()
        val handlings = Handlings()
        val cluster = SpikeCluster(backend, context, partitions, options, timeline, handlings, metrics)
        val load = LoadDriver(cluster, RATE, Zipf(AGGREGATES, ZIPF_S))
        val results = mutableListOf<StepResult>()
        val names = generateSequence(1) { it + 1 }.map { "n$repetition-$it-${randomSuffix()}" }.iterator()
        val step = Step(backend, config, partitions, repetition, cluster)
        try {
            val first = List(START_NODES) { cluster.newNode(names.next()) }
            results += step.measure(Change.START, first.map { it.name }, "-") {
                first.map { node ->
                    async {
                        node.member = true
                        node.start()
                        node.receiving = true
                    }
                }.awaitAll()
            }
            load.start()
            delay(WARMUP)
            for (change in PLAN) {
                val current = cluster.members.map { it.name }
                results += when (change) {
                    Change.JOIN -> {
                        val node = cluster.newNode(names.next())
                        step.measure(change, current + node.name, node.name) {
                            node.member = true
                            node.start()
                            node.receiving = true
                        }
                    }

                    Change.LEAVE, Change.KILL -> {
                        val node = cluster.members.random()
                        step.measure(change, current - node.name, node.name) {
                            node.receiving = false
                            node.member = false
                            if (change == Change.LEAVE) node.leave() else node.kill()
                        }
                    }

                    Change.START -> error("Only the first step starts nodes.")
                }
                delay(QUIET)
            }
            load.stop()
        } finally {
            withTimeoutOrNull(SHUTDOWN) {
                cluster.nodes.values.filter { !it.dead }.forEach { it.kill() }
            }
            cluster.nodes.values.forEach { runCatching { it.close() } }
            cluster.close()
        }
        val ended = System.currentTimeMillis()
        return results.mapIndexed { index, result ->
            val until = results.getOrNull(index + 1)?.startedAt ?: ended
            finish(result, until, timeline, handlings)
        }
    }

    /** Adds the duplicates up to [until] and the load around the change. */
    private fun finish(
        result: StepResult,
        until: Long,
        timeline: Timeline,
        handlings: Handlings,
    ): StepResult {
        val window = result.startedAt until until
        val firsts = handlings.firsts.filter { it.at in window }
        val repeats = handlings.repeats.filter { it.at in window }
        val movedPartitions = result.movedSet
        val startSecond = result.startedAt / MILLIS_LONG
        val convergedSecond = (result.startedAt + (result.convergedMillis ?: STEP_TIMEOUT_MILLIS)) / MILLIS_LONG
        val profileEnd = convergedSecond + PROFILE_AFTER
        return result.copy(
            entries = firsts.size,
            repeats = repeats.size,
            movedEntries = firsts.count { it.partition in movedPartitions },
            movedRepeats = repeats.count { it.partition in movedPartitions },
            window = Tally.of(timeline, startSecond..convergedSecond + 1),
            baseline = Tally.of(timeline, startSecond - BASELINE_SECONDS until startSecond),
            profile = (startSecond - PROFILE_BEFORE..profileEnd).map {
                Tally.of(timeline, it..it, offset = (it - startSecond).toInt())
            },
        )
    }

    private fun write() {
        val json = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build()
        File("build").mkdirs()
        val minutes = (System.currentTimeMillis() - started) / MILLIS_PER_MINUTE
        File("build/spike-h6.json").writeText(
            json.writeValueAsString(
                mapOf(
                    "minutes" to minutes,
                    "settings" to settings(),
                    "backends" to backends,
                    "leaseStore" to leaseSeries,
                    "steps" to steps,
                ),
            ),
        )
        val markdown = SpikeReport.markdown(steps, leaseSeries)
        File("build/spike-h6.md").writeText(markdown)
        log("wrote ${steps.size} steps after ${"%.1f".format(minutes)} min")
    }

    private fun settings() = mapOf(
        "repetitions" to repetitions,
        "plan" to PLAN.map { it.name },
        "startNodes" to START_NODES,
        "partitions" to if (quick) listOf(QUICK_PARTITIONS) else PARTITIONS,
        "configs" to CONFIGS.mapValues { (_, options) -> options.toString() },
        "ratePerSecond" to RATE,
        "aggregates" to AGGREGATES,
        "zipfS" to ZIPF_S,
        "warmupMillis" to WARMUP.inWholeMilliseconds,
        "quietMillis" to QUIET.inWholeMilliseconds,
        "pollMillis" to Step.POLL.inWholeMilliseconds,
        "stepTimeoutMillis" to STEP_TIMEOUT_MILLIS,
        "jvm" to "${System.getProperty("java.vm.vendor")} ${System.getProperty("java.vm.version")}",
        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        "processors" to Runtime.getRuntime().availableProcessors(),
        "maxHeapMiB" to Runtime.getRuntime().maxMemory() / BYTES_PER_MIB,
    )

    private fun randomSuffix(): String = Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt())

    /** Measures one change of a repetition. */
    private class Step(
        val backend: Backend,
        val config: String,
        val partitions: Int,
        val repetition: Int,
        val cluster: SpikeCluster,
    ) {
        /**
         * Runs [act] (the change) and polls, every [POLL], the observer's view of the leases and every expected
         * member's ownership and routes until all of them agree with HRW over [expected], or [STEP_TIMEOUT_MILLIS].
         */
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        suspend fun measure(
            change: Change,
            expected: List<String>,
            node: String,
            act: suspend CoroutineScope.() -> Unit,
        ): StepResult = coroutineScope {
            val group = cluster.group.name
            val before = cluster.backend.observer.owners(group).associate { it.partition to it.owner }
            val assignment = Hrw.assignment(expected, partitions)
            val shares = expected.associateWith { Hrw.partitionsOf(it, expected, partitions) }
            val startedAt = System.currentTimeMillis()
            val action = async { act() }
            var membership: Long? = null
            var leases: Long? = null
            var held: Long? = null
            var routes: Long? = null
            var converged: Long? = null
            var unownedMillis = 0.0
            var maxUnowned = 0
            var last = startedAt
            var after = before
            while (true) {
                val now = System.currentTimeMillis()
                val elapsed = now - startedAt
                after = cluster.backend.observer.owners(group).associate { it.partition to it.owner }
                val unowned = partitions - after.size
                unownedMillis += unowned.toDouble() * (now - last)
                last = now
                maxUnowned = maxOf(maxUnowned, unowned)
                val members = expected.mapNotNull { cluster.nodes[it] }
                val membersOk = members.size == expected.size && members.all { member ->
                    member.ownership.routes.members.map { it.node }.toSet() == expected.toSet()
                }
                val leasesOk = after == assignment
                val heldOk = members.all { it.ownership.owned == shares[it.name] }
                val routesOk = members.all { member ->
                    member.ownership.routes.owners.mapValues { it.value.node } == assignment
                }
                if (membersOk && membership == null) membership = elapsed
                if (leasesOk && leases == null) leases = elapsed
                if (heldOk && held == null) held = elapsed
                if (routesOk && routes == null) routes = elapsed
                if (membersOk && leasesOk && heldOk && routesOk) {
                    converged = elapsed
                    break
                }
                if (elapsed > STEP_TIMEOUT_MILLIS) {
                    log("  $change $node did not converge: leases $leasesOk members $membersOk held $heldOk routes $routesOk")
                    break
                }
                delay(POLL)
            }
            withTimeoutOrNull(ACTION_TIMEOUT) { action.await() } ?: log("  $change $node is still running its action")
            val movedSet = (0 until partitions).filterTo(HashSet()) { before[it] != after[it] }
            val predicted = when (change) {
                Change.START -> partitions
                Change.JOIN -> shares.getValue(node).size
                Change.LEAVE, Change.KILL -> Hrw.partitionsOf(node, expected + node, partitions).size
            }
            val from = when (change) {
                Change.START -> 0
                Change.JOIN -> expected.size - 1
                Change.LEAVE, Change.KILL -> expected.size + 1
            }
            log(
                "  $change ${from}→${expected.size} converged ${converged ?: "-"} ms (members $membership, " +
                    "leases $leases, held $held, routes $routes); moved ${movedSet.size}/$predicted, " +
                    "max unowned $maxUnowned",
            )
            StepResult(
                backend = backend.name,
                config = config,
                partitions = partitions,
                repetition = repetition,
                change = change,
                from = from,
                to = expected.size,
                node = node,
                startedAt = startedAt,
                convergedMillis = converged,
                membershipMillis = membership,
                leasesMillis = leases,
                heldMillis = held,
                routesMillis = routes,
                moved = movedSet.size,
                predicted = predicted,
                extraMoves = movedSet.size - predicted,
                maxUnowned = maxUnowned,
                unownedPartitionSeconds = unownedMillis / MILLIS,
                movedSet = movedSet,
            )
        }

        companion object {
            val POLL = 50.milliseconds
            val ACTION_TIMEOUT = 30.seconds
        }
    }

    companion object {
        const val REPETITIONS = 10
        const val START_NODES = 3
        val PARTITIONS = listOf(64, 256)
        const val QUICK_PARTITIONS = 64
        val PLAN = listOf(
            Change.JOIN, Change.JOIN, Change.LEAVE, Change.LEAVE,
            Change.JOIN, Change.JOIN, Change.KILL, Change.KILL,
        )
        val CONFIGS = linkedMapOf(
            "default" to ClusterOptions(),
            "short" to ClusterOptions(
                leaseTtl = 5.seconds,
                renewInterval = 1_500.milliseconds,
                memberTtl = 5.seconds,
                heartbeatInterval = 1_500.milliseconds,
            ),
        )
        const val RATE = 200.0
        const val AGGREGATES = 10_000
        const val ZIPF_S = 1.1
        val WARMUP = 3.seconds
        val QUIET = 3.seconds
        val SHUTDOWN = 30.seconds
        const val STEP_TIMEOUT_MILLIS = 60_000L
        const val BASELINE_SECONDS = 3L
        const val PROFILE_BEFORE = 2L
        const val PROFILE_AFTER = 2L
        const val MILLIS = 1_000.0
        const val MILLIS_LONG = 1_000L
        const val MICROS_PER_MILLI = 1_000.0
        const val MILLIS_PER_MINUTE = 60_000.0
        const val BYTES_PER_MIB = 1_048_576L
        const val P50 = 0.5
        const val P99 = 0.99

        fun log(message: String) = println("[h6 ${java.time.LocalTime.now().withNano(0)}] $message")
    }
}
