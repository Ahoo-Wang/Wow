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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A node activated from standby over a log holding a large backlog, as after migrate-data imported a 9.x history, on
 * a store whose few connections each call holds a while: the activation does not wait behind the backlog, which its
 * subscriptions read once every partition has started, and they handle all of it afterwards, each aggregate's streams
 * in version order, the imported parked entries retried too.
 */
class BacklogActivationTest {
    private val log = InMemoryEventLog(PARTITIONS)

    /** The store's connections: each call of the checkpoints, the ledger and the projection's writes holds one. */
    private val pool = Semaphore(CONNECTIONS)

    private suspend fun <T> pooled(block: suspend () -> T): T = pool.withPermit {
        delay(CALL_MILLIS)
        block()
    }

    private val checkpointStore = InMemoryCheckpointStore()
    private val checkpoints = object : CheckpointStore by checkpointStore {
        override suspend fun load(group: String, subscription: String, partition: Int): Long? =
            pooled { checkpointStore.load(group, subscription, partition) }

        override suspend fun list(group: String): List<Checkpoint> = pooled { checkpointStore.list(group) }

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) =
            pooled { checkpointStore.save(group, subscription, partition, position) }

        override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) =
            pooled { checkpointStore.saveAll(group, checkpoints) }
    }
    private val ledgerStore = InMemoryFailureLedger()
    private val ledger = object : FailureLedger by ledgerStore {
        override suspend fun due(
            subscription: SubscriptionId,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> = pooled { ledgerStore.due(subscription, partitions, now, after, limit) }
    }
    private val backends = BackendFactory {
        ContextBackends(
            log = log,
            checkpoints = checkpoints,
            leases = InMemoryLeaseStore(),
            ledger = ledger,
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
            reservations = InMemoryReservationStore(),
        )
    }

    /** What the projection wrote, as `id@version`, in the order it wrote it. */
    private val projected = CopyOnWriteArrayList<String>()

    private val projection = ProcessorBinding.project(VIEW, "onEvent", CartState::class.java) { event, _ ->
        pooled { projected += "${event.ref.id}@${event.version}" }
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = ServiceNodeOptions(
            // The default leases, which a loaded machine keeps while it catches up: a lost one replays what it handled.
            cluster = ClusterOptions(),
            subscription = testOptions().copyWith(
                parked = ParkedRetryOptions(
                    // A retry a partition's stop interrupts is retried a second later.
                    ParkedRetrySpec(maxRetries = 3, initialBackoff = Duration.ZERO, claimTtl = 1.seconds),
                    scanInterval = 100.milliseconds,
                ),
            ),
        ),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(projection))),
    )

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    /** What migrate-data imports: [streams] streams of each of [CARTS] carts, and a parked entry of every tenth cart. */
    private suspend fun imported() {
        for (cart in 0 until CARTS) {
            val ref = CART.ref(TENANT, "c-$cart")
            for (version in 1..STREAMS) {
                val stream = EventStreamRecord(
                    ref,
                    version,
                    "9x-$cart-$version",
                    "9x-$cart-$version",
                    "shop.cart.AddItems",
                    System.currentTimeMillis(),
                    listOf(EventRecord("shop.cart.ItemsAdded", 1, """{"items":["i-$version"]}""")),
                    Scope.NONE,
                )
                log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            }
            if (cart % PARKED_EVERY == 0) {
                val partition = Partitioner.partitionOf(ref, PARTITIONS)
                ledgerStore.park(
                    ParkedAggregate.of(SUBSCRIPTION_ID, ref, partition, 1, "imported", failedAt = 0L)
                )
            }
        }
    }

    @Test
    fun `a node over a large backlog should activate within a bound, then handle all of it`(): Unit = runBlocking {
        imported()
        node.start(standby = true)

        val started = TimeSource.Monotonic.markNow()
        node.activate("operator", "cut-over").activated.assert().isTrue()
        val took = started.elapsedNow()
        // The backlog alone takes the pool CARTS × STREAMS × CALL_MILLIS / CONNECTIONS, over the bound.
        took.assert().isLessThan(BOUND)
        projected.size.assert().isLessThan(CARTS * STREAMS)

        // Every stream, each cart's in version order; a stream may come again above a checkpoint, never out of order.
        eventually(timeout = 60.seconds) {
            projected.toSet().size == CARTS * STREAMS && ledgerStore.count(SUBSCRIPTION_ID, CARTS) == 0
        }
        val byCart = projected.groupBy({ it.substringBefore('@') }, { it.substringAfter('@').toInt() })
        byCart.keys.assert().hasSize(CARTS)
        byCart.values.forEach { it.distinct().assert().isEqualTo((1..STREAMS).toList()) }
        val subscriptions = node.context(SHOP).subscriptions
        eventually {
            subscriptions.positions().filter { it.subscription == SUBSCRIPTION }
                .all { it.watermark == (log.head(it.partition) ?: 0L) }
        }
    }

    private companion object {
        val SUBSCRIPTION_ID = SubscriptionId(SHOP, SUBSCRIPTION)
        const val PARTITIONS = 32
        const val CARTS = 6_000
        const val STREAMS = 2
        const val PARKED_EVERY = 10
        const val CONNECTIONS = 8
        const val CALL_MILLIS = 2L
        const val VIEW = "CartView"
        const val SUBSCRIPTION = "$VIEW.onEvent"

        /** Generous for a loaded machine, and under the 3 s the backlog takes on the pool. */
        val BOUND: Duration = 1_500.milliseconds
    }
}
