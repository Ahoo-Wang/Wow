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

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.suspendCancellableCoroutine
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.ExecutorBusyException
import me.ahoo.wow.spi.log.Partitioner
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/** How a command ended, as the caller sees it. */
enum class Result { COMMITTED, REJECTED, CONTENDED, BUSY, FAILED }

/** What a node answers over the cluster channel. */
data class Reply(val result: Result, val version: Int = 0)

/** A command as it crosses the cluster channel: the body by its model name, as JSON. */
data class WireCommand(
    val id: String,
    val requestId: String,
    val context: String,
    val aggregate: String,
    val tenantId: String,
    val aggregateId: String,
    val bodyType: String,
    val body: JsonNode,
)

/** One simulated node: its own pool, log and snapshot clients, kernel, executor and state cache. */
class Node(
    val index: Int,
    private val dataSource: HikariDataSource,
    context: String,
    partitions: Int,
    private val model: AggregateModel<AccountState>,
    shape: StateShape,
    cacheCapacity: Int,
    private val counters: Counters,
) {
    private val codec = EventCodec()
    private val snapshots = CountingSnapshotStore(PostgresSnapshotStore(dataSource, context), counters)
    private val cache = SnapshottingCache(cacheCapacity, counters, model, codec, shape)
    private val executor = Executor(
        Kernel(
            log = CountingEventLog(PostgresEventLog(dataSource, context, partitions), counters),
            codec = codec,
            states = cache,
            snapshots = snapshots,
        ),
    )

    suspend fun handle(command: Command<*>): Reply {
        val outcome = try {
            executor.execute(model, command)
        } catch (busy: ExecutorBusyException) {
            return Reply(Result.BUSY).also { counters.executed.increment() }
        }
        counters.executed.increment()
        return when (outcome) {
            is CommandOutcome.Committed -> {
                cache.takeSnapshot(command.ref, outcome.stream.version)?.let { snapshots.save(it) }
                Reply(Result.COMMITTED, outcome.stream.version)
            }

            is CommandOutcome.Rejected -> Reply(Result.REJECTED)
            is CommandOutcome.Contended -> Reply(Result.CONTENDED)
            is CommandOutcome.Unchanged, is CommandOutcome.AlreadyCommitted, is CommandOutcome.Unknown ->
                Reply(Result.FAILED)
        }
    }

    suspend fun close() {
        executor.close(10.seconds)
        dataSource.close()
    }
}

/**
 * [nodes] sharing one log: routes each command by [routing]. Partition `p` is owned by node `p % nodes.size`, a
 * fixed assignment standing in for the partition leases.
 */
class Cluster(
    val nodes: List<Node>,
    private val partitions: Int,
    private val routing: Routing,
    private val hop: Hop,
    private val counters: Counters,
) {
    private val ownerOf = IntArray(partitions) { it % nodes.size }

    suspend fun send(command: Command<*>, random: ThreadLocalRandom): Reply {
        val owner = ownerOf[Partitioner.partitionOf(command.ref, partitions)]
        return when (routing) {
            Routing.LOCAL -> nodes[random.nextInt(nodes.size)].handle(command)
            Routing.INGRESS_HASH -> nodes[owner].handle(command)
            Routing.PARTITIONED -> {
                val receiver = random.nextInt(nodes.size)
                if (receiver == owner) nodes[owner].handle(command) else forward(nodes[owner], command)
            }
        }
    }

    /**
     * The cluster channel: the command and the reply are really serialized; the network is an injected delay. The
     * forward's own cost (both legs, without the owner's execution) is counted in [Counters.forwardNanos].
     */
    private suspend fun forward(owner: Node, command: Command<*>): Reply {
        counters.forwards.increment()
        val sent = System.nanoTime()
        val request = WIRE.writeValueAsBytes(toWire(command))
        val roundTrip = ThreadLocalRandom.current().nextLong(MIN_HOP_NANOS, MAX_HOP_NANOS + 1)
        hop.delay(roundTrip / 2)
        val received = fromWire(WIRE.readValue(request, WireCommand::class.java))
        val executing = System.nanoTime()
        val handled = owner.handle(received)
        val answering = System.nanoTime()
        val reply = WIRE.writeValueAsBytes(handled)
        hop.delay(roundTrip - roundTrip / 2)
        return WIRE.readValue(reply, Reply::class.java).also {
            counters.forwardNanos.add((executing - sent) + (System.nanoTime() - answering))
        }
    }

    suspend fun close() {
        nodes.forEach { it.close() }
    }

    companion object {
        const val MIN_HOP_NANOS = 300_000L
        const val MAX_HOP_NANOS = 1_000_000L
        private val WIRE: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        private val BODY_TYPES = listOf(OpenAccount::class.java, Deposit::class.java, Withdraw::class.java)
            .associateBy { it.simpleName }

        fun toWire(command: Command<*>) = WireCommand(
            id = command.id,
            requestId = command.requestId,
            context = command.ref.type.context,
            aggregate = command.ref.type.name,
            tenantId = command.ref.tenantId,
            aggregateId = command.ref.id,
            bodyType = command.body.javaClass.simpleName,
            body = WIRE.valueToTree(command.body),
        )

        fun fromWire(wire: WireCommand): Command<Any> = Command(
            id = wire.id,
            requestId = wire.requestId,
            ref = AggregateType(wire.context, wire.aggregate).ref(wire.tenantId, wire.aggregateId),
            body = WIRE.treeToValue(wire.body, BODY_TYPES.getValue(wire.bodyType)),
        )
    }
}

/**
 * The network of the cluster channel: a sub-millisecond timer (kotlinx `delay` rounds up to whole milliseconds).
 * The achieved hop time is measured and reported, since timer precision is the host's.
 */
class Hop : AutoCloseable {
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "h2-hop").also { it.isDaemon = true }
    }

    @Suppress("RedundantSuspendModifier") // suspendCancellableCoroutine suspends; detekt does not resolve it.
    suspend fun delay(nanos: Long): Unit = suspendCancellableCoroutine { continuation ->
        val scheduled = timer.schedule({ continuation.resume(Unit) }, nanos, TimeUnit.NANOSECONDS)
        continuation.invokeOnCancellation { scheduled.cancel(false) }
    }

    override fun close() {
        timer.shutdownNow()
    }
}
