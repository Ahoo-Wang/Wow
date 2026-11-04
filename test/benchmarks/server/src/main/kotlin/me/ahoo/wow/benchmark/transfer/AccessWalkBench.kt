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

// The walk, its record and the node's contexts are Wow's internals: a scale check of them reads them as Wow does.
@file:OptIn(InternalWowApi::class)

package me.ahoo.wow.benchmark.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.access.AccessSchema
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder
import kotlin.system.exitProcess

/**
 * The access walk's scale check (the `access-walk-*` suites, kind `measure`): the benchmark's accounts on the V10 node
 * and one store, in this JVM, as [ComponentRun] runs them.
 *
 * On a node started on standby, so that nothing walks or follows yet, it appends `--aggregates` accounts of
 * `--streams` streams each to the log, as an import writes them: without access attributes, or, with `--walk=none`,
 * with them and the account's walk recorded as completed, so the node has nothing to walk (the A/A arm). It then
 * activates the node, whose walk stamps the accounts at `wow.access.walk-rate`, while `--rate` deposits a second (E1,
 * open loop) go to the same accounts, measured over `--measure` seconds after `--warm-up`; and it waits for the walk to
 * complete. It writes `measure.json` to `--out`: the walk's time and rate, and the deposits' latencies.
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
    val context = SpringApplicationBuilder(TransferServer::class.java)
        .web(WebApplicationType.NONE)
        .profiles(backend)
        .run(
            "--wow.internal.port=0",
            "--wow.node.standby=true",
            *options.filterKeys { it.startsWith("spring.") || it.startsWith("wow.") }
                .map { "--${it.key}=${it.value}" }
                .toTypedArray(),
        )
    val walkRate = context.environment.getProperty("wow.access.walk-rate")
    val result = try {
        run.run(context.getBean(ServiceNode::class.java), context.getBean(CommandClient::class.java))
    } finally {
        context.close()
    }
    val json = toJson(result + ("backend" to backend) + ("walkRate" to walkRate))
    File(out, "measure.json").writeText(json + "\n")
    println(json)
    exitProcess(0)
}

private const val DEFAULT_AGGREGATES = 100_000
private const val DEFAULT_STREAMS = 10
private const val DEFAULT_WARM_UP = 10
private const val DEFAULT_MEASURE = 40
private const val BALANCE = 1_000_000_000_000L
private const val APPEND_BATCH = 500
private const val SEED_CONCURRENCY = 16
private const val IN_FLIGHT = 1_024
private const val WALK_TIMEOUT_SECONDS = 3_600L
private const val POLL_MILLIS = 50L
private const val NANOS_PER_MILLI = 1_000_000.0
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val PERCENT = 100.0

/** One walk run: the history appended on standby, then the activation, the deposits and the walk. */
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

    fun run(node: ServiceNode, client: CommandClient): Map<String, Any?> = runBlocking(Dispatchers.Default) {
        val context = node.context(ACCOUNT_TYPE.context)
        val log = context.backends.log
        val walksOf = log as AccessWalks
        @Suppress("UNCHECKED_CAST") // The account's model, as the node holds it.
        val model = context.models.first { it.type == ACCOUNT_TYPE } as AggregateModel<Any>
        val seedStarted = System.nanoTime()
        seed(model, log)
        if (!walks) {
            walksOf.claim(ACCOUNT_TYPE, AccessWalk(AccessSchema.of(model), emptyMap(), completed = true), null)
        }
        val seedSeconds = (System.nanoTime() - seedStarted) / NANOS_PER_SECOND
        val activated = System.nanoTime()
        node.activate("benchmark", "the access walk's scale check")
        val walk = async { if (walks) walked(node, activated) else null }
        val deposits = if (rate > 0) deposits(client) else null
        mapOf(
            "schema" to "wow-e2e-bench/measure/1",
            "program" to "walk",
            "aggregates" to aggregates,
            "streams" to streams,
            "walks" to walks,
            "rate" to rate,
            "warmUpSeconds" to warmUpSeconds,
            "measureSeconds" to measureSeconds,
            "seedSeconds" to seedSeconds,
            "walk" to walk.await(),
            "e1" to deposits,
            "note" to "In one JVM: the walk's time is from the activation to its completion, the deposits' latencies " +
                "an open loop over the measured window, both against the node's catch-up of the appended history.",
        )
    }

    /**
     * Appends the accounts' histories, each written by a kernel of its own in memory: without their attributes when
     * the node walks them, with them otherwise.
     */
    private suspend fun seed(model: AggregateModel<Any>, log: EventLog) = coroutineScope {
        val permits = Semaphore(SEED_CONCURRENCY)
        (0 until aggregates).chunked(APPEND_BATCH).map { chunk ->
            async {
                permits.withPermit {
                    val written = chunk.flatMap { history(model, it) }
                    written.groupBy { Partitioner.partitionOf(it.ref, log.partitions) }.values.forEach { partition ->
                        partition.chunked(APPEND_BATCH).forEach { group -> log.appendAll(group.map { it to null }) }
                    }
                }
            }
        }.awaitAll()
    }

    /** The streams of account [index]: opened, then deposited into, [streams] in all. */
    private suspend fun history(model: AggregateModel<Any>, index: Int): List<EventStreamRecord> {
        val memory = InMemoryEventLog()
        val kernel = Kernel(memory)
        val ref = ACCOUNT_TYPE.ref(TENANT, accountId(index))
        repeat(streams) { version ->
            val body: Any = if (version == 0) OpenAccount("owner-${index % OWNERS}", BALANCE) else Deposit(1)
            kernel.execute(model, Command("seed-$index-$version", "seed-$index-$version", ref, body))
        }
        return memory.read(ref).toList().map { if (walks) it.copyWith(access = null, accessVersion = null) else it }
    }

    /** How long the walk took from [activated] to its completion, and its rate. */
    private suspend fun walked(node: ServiceNode, activated: Long): Map<String, Any?> {
        val deadline = activated + WALK_TIMEOUT_SECONDS * NANOS_PER_SECOND.toLong()
        var walk = node.context(ACCOUNT_TYPE.context).accessWalking?.walks()?.get(ACCOUNT_TYPE)
        while (walk?.completed != true && System.nanoTime() < deadline) {
            delay(POLL_MILLIS)
            walk = node.context(ACCOUNT_TYPE.context).accessWalking?.walks()?.get(ACCOUNT_TYPE)
        }
        val seconds = (System.nanoTime() - activated) / NANOS_PER_SECOND
        return mapOf(
            "completed" to (walk?.completed == true),
            "walked" to walk?.walked,
            "seconds" to seconds,
            "aggregatesPerSecond" to (walk?.walked ?: 0L) / seconds,
        )
    }

    /** [rate] deposits a second over the warm-up and the measured window, open loop; the measured ones' latencies. */
    private suspend fun deposits(client: CommandClient): Map<String, Any?> = coroutineScope {
        val latencies = ConcurrentLinkedQueue<Long>()
        val failures = ConcurrentHashMap<String, LongAdder>()
        val permits = Semaphore(IN_FLIGHT)
        val total = rate * (warmUpSeconds + measureSeconds)
        val measuredFrom = rate * warmUpSeconds
        val started = System.nanoTime()
        repeat(total) { index ->
            val due = started + (index * NANOS_PER_SECOND / rate).toLong()
            val wait = (due - System.nanoTime()) / NANOS_PER_MILLI.toLong()
            if (wait > 0) delay(wait)
            permits.acquire()
            launch {
                try {
                    val ref = ACCOUNT_TYPE.ref(TENANT, accountId((index * PRIME) % aggregates))
                    val sent = System.nanoTime()
                    val outcome = runCatching {
                        client.wait(CommandRequest.builder(ref, Deposit(1)).wait(WaitPlan.COMMITTED).build())
                    }
                    if (index >= measuredFrom) {
                        latencies += System.nanoTime() - sent
                        val failure = outcome.exceptionOrNull()?.javaClass?.simpleName
                            ?: outcome.getOrNull()?.takeIf { it.status != CommandResult.Status.SUCCEEDED }?.status?.name
                        failure?.let { failures.computeIfAbsent(it) { LongAdder() }.increment() }
                    }
                } finally {
                    permits.release()
                }
            }
        }
        val measured = latencies.toLongArray().apply { sort() }
        fun percentile(p: Double): Double? =
            measured.takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * p / PERCENT).toInt()] / NANOS_PER_MILLI }
        mapOf(
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
