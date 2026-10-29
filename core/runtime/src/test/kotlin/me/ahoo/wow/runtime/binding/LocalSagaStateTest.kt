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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.state.PublishedStateOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decision D261 (B.G-5): a saga chain on one node, as the E3 benchmark runs it. The log hands each commit to the
 * node's subscriptions before the executor returns (decision D249; [SlowReturns] makes sure they get it first); the
 * published state of that commit is taken before the append, so the saga and the snapshot subscription read the state of
 * the node's own commits without a read of the snapshots or the log, even when the aggregate's earlier views are gone.
 */
class LocalSagaStateTest {
    private val log = CountingLog(InMemoryEventLog(PARTITIONS))
    private val snapshots = CountingSnapshots(InMemorySnapshotStore())
    private val backends = BackendFactory {
        ContextBackends(
            log = log,
            checkpoints = InMemoryCheckpointStore(),
            leases = InMemoryLeaseStore(),
            ledger = InMemoryFailureLedger(),
            snapshots = SnapshotTargets.single(snapshots),
            reservations = InMemoryReservationStore(),
        )
    }
    private val handled = CopyOnWriteArrayList<String>()

    /** An order placed adds its items to the cart named by its first item. */
    private val onOrderPlaced = ProcessorBinding.saga("CartSaga", "onOrderPlaced", OrderState::class.java) { event, c ->
        event.events.filterIsInstance<Placed>().map {
            CommandRequest.to(CART.ref(event.ref.tenantId, it.items.first()), AddItems(it.items.drop(1)))
        }
    }

    /** Takes each commit of a cart with its state, as the transfer saga takes each commit of an account. */
    private val onCartChanged = ProcessorBinding.saga("CartSaga", "onCartChanged", CartState::class.java) { event, _ ->
        check(event.state.items.isNotEmpty())
        handled += "${event.ref.id}@${event.version}"
        emptyList()
    }

    private var node: ServiceNode? = null

    private fun start(queue: AppendQueueOptions): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        // Views age out between the rounds, as an account's do between two transfers in the benchmark.
        options = fastNodeOptions().copyWith(
            appendQueue = queue,
            publishedStates = PublishedStateOptions(maxAge = MAX_AGE)
        ),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(onOrderPlaced, onCartChanged))),
        observer = SlowReturns,
    ).also {
        node = it
        runBlocking { it.start() }
    }

    @AfterEach
    fun stop(): Unit = runBlocking { node?.stop() ?: Unit }

    private fun request(aggregate: String, command: String, items: List<String>, id: String, wait: WaitPlan) =
        ServerCommandRequest(
            context = SHOP,
            aggregate = aggregate,
            command = command,
            body = CommandBody.of("""{"items":[${items.joinToString { "\"$it\"" }}]}"""),
            caller = Caller(TENANT, Scope(ownerId = "bob")),
            aggregateId = id,
            wait = wait,
        )

    /** One transfer of the chain: order [order] adds [item] to [cart], answered once the cart committed it. */
    private suspend fun ServiceNode.chain(order: String, cart: String, item: String) {
        val results = gateway.send(request("order", "Place", listOf(cart, item), order, CHAIN)).toList()
        results.last().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        results.last().hop.stage.assert().isEqualTo(Stage.COMMITTED)
        results.last().ref.id.assert().isEqualTo(cart)
    }

    /** Waits until the saga handled every cart commit and the snapshot store holds every cart at its version. */
    private suspend fun settled(versions: Map<String, Int>) = eventually {
        versions.all { (cart, version) ->
            "$cart@$version" in handled && (snapshots.delegate.load(CART.ref(TENANT, cart))?.version ?: 0) >= version
        }
    }

    private fun steadyChains(queue: AppendQueueOptions): Unit = runBlocking {
        val node = start(queue)
        val carts = (0 until CARTS).map { "cart-$it" }
        // Warm-up: each cart exists, the node's caches hold it, and its subscriptions handled and wrote it.
        carts.forEach { cart ->
            node.gateway.execute(request("cart", "AddItems", listOf("seed"), cart, WaitPlan.COMMITTED))
                .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        carts.forEachIndexed { index, cart -> node.chain("warm-$index", cart, "warm") }
        settled(carts.associateWith { 2 })
        val handoffs = node.contexts.getValue(SHOP).logGroups.map { it.log }.filterIsInstance<HandoffLog>()
        handoffs.assert().isNotEmpty()
        val storeReads = handoffs.sumOf { it.storeReads }
        // Every view of the carts is older than the views' maximum age now.
        delay(MAX_AGE * 2)
        log.reset()
        snapshots.reset()

        carts.forEachIndexed { index, cart -> node.chain("order-$index", cart, "item-$index") }
        settled(carts.associateWith { 3 })

        // No stream of the log was read, and no cart's snapshot loaded: only each new order's, by the kernel.
        log.streams.assert().isEmpty()
        snapshots.loads.keys.map { it.type }.toSet().assert().containsExactly(ORDER)
        snapshots.loads.values.sumOf { it.get() }.assert().isEqualTo(CARTS)
        handoffs.sumOf { it.storeReads }.assert().isEqualTo(storeReads)
        carts.forEach { cart -> "$cart@3".assert().isIn(handled) }
    }

    @Test
    fun `a saga chain should read no store for the state of the node's own commits, behind an append queue`() {
        steadyChains(AppendQueueOptions())
    }

    @Test
    fun `a saga chain should read no store for the state of the node's own commits, one append per batch`() {
        steadyChains(AppendQueueOptions(maxBatch = 1))
    }

    private companion object {
        const val PARTITIONS = 4
        const val CARTS = 8
        val MAX_AGE = 300.milliseconds
        val CHAIN: WaitPlan = WaitPlan.of(Hop.committed(), Hop.handled("CartSaga.onOrderPlaced"), Hop.committed())
    }
}

/**
 * Holds the kernel back a moment once a cart's append returned, as a store's commit latency does (MongoDB's majority
 * commit): the log has handed the commit over by then, so the subscriptions handle it before the executor returns.
 */
private object SlowReturns : Observer {
    override fun appending(stream: EventStreamRecord): Observation =
        if (stream.ref.type != CART) {
            Observation.NONE
        } else {
            object : Observation {
                override fun outcome(outcome: ObservedOutcome) = Thread.sleep(HOLD_MILLIS)
            }
        }

    const val HOLD_MILLIS = 50L
}

/** A log recording each stream read of an aggregate. */
private class CountingLog(private val delegate: EventLog) : EventLog by delegate {
    val streams: MutableList<String> = CopyOnWriteArrayList()

    fun reset() = streams.clear()

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        delegate.read(ref, fromVersion, toVersion).onEach { streams += "${it.ref}@${it.version}" }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        delegate.readEntries(ref, fromVersion, toVersion).onEach { streams += "${it.stream.ref}@${it.stream.version}" }
}

/** A snapshot store counting its loads, by aggregate. */
private class CountingSnapshots(val delegate: SnapshotStore) : SnapshotStore by delegate {
    val loads = ConcurrentHashMap<AggregateRef, AtomicInteger>()

    fun reset() = loads.clear()

    override suspend fun load(ref: AggregateRef): SnapshotRecord? =
        delegate.load(ref).also { loads.computeIfAbsent(ref) { AtomicInteger() }.incrementAndGet() }
}
