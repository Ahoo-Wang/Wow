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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.command.CancelOrder
import me.ahoo.wow.runtime.command.ITEM
import me.ahoo.wow.runtime.command.ORDER
import me.ahoo.wow.runtime.command.OrderState
import me.ahoo.wow.runtime.command.PREFIX
import me.ahoo.wow.runtime.command.PlaceOrder
import me.ahoo.wow.runtime.command.Reserve
import me.ahoo.wow.runtime.command.SALES
import me.ahoo.wow.runtime.command.SALES_SERVICE
import me.ahoo.wow.runtime.command.STOCK
import me.ahoo.wow.runtime.command.StockItems
import me.ahoo.wow.runtime.command.TENANT
import me.ahoo.wow.runtime.command.orderModel
import me.ahoo.wow.runtime.command.salesManifest
import me.ahoo.wow.runtime.command.stockManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.runtime.timer.TestClock
import me.ahoo.wow.runtime.timer.TimerFirer
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import me.ahoo.wow.manifest.ServiceInfo as ManifestService

/**
 * Two contexts of one service on one store share its one failure ledger (`Store.ledger`, which `StoreLayout.open`
 * gives every context on the store), and each has a subscription of one name. The ledger keys an entry by the
 * subscription's identity, its context and name, so a failure of one is its own alone: the other neither holds it
 * back, nor sees, retries or resolves its entry, the operator sees it once under its own context, and a source's
 * partitions are never read as the other source's. Each test fails one subscription only.
 */
class LedgerKeyAcrossContextsTest {
    private val topics = InMemoryTopics(PARTITIONS).also { it.create(ORDERS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()

    /** The store's ledger: what each subscription reads of its own entries in it is recorded. */
    private val shared = InMemoryFailureLedger()
    private val seenByScans = ConcurrentHashMap<SubscriptionId, MutableList<AggregateRef>>()
    private val scans = ConcurrentHashMap<SubscriptionId, AtomicInteger>()
    private val ledger = object : FailureLedger by shared {
        override suspend fun due(
            subscription: SubscriptionId,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> = shared.due(subscription, partitions, now, after, limit).also { page ->
            seenByScans.computeIfAbsent(subscription) { CopyOnWriteArrayList() } += page.map { it.ref }
            scans.computeIfAbsent(subscription) { AtomicInteger() }.incrementAndGet()
        }
    }
    private val stores = InMemoryBackends(LOG_PARTITIONS)

    /** Each context its own log and checkpoints, as on a store; the store's one ledger for both. */
    private var backends = BackendFactory { stores.backends(it).copyWith(ledger = ledger) }
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val commands = AtomicInteger()

    /** What each context's `Board.onOrder` handled: the order's id and version. */
    private val salesSeen = CopyOnWriteArrayList<Pair<String, Int>>()
    private val stockSeen = CopyOnWriteArrayList<Pair<String, Int>>()

    /** The orders each context's `Board.onOrder` fails on. */
    @Volatile
    private var salesFails: Set<String> = emptySet()

    @Volatile
    private var stockFails: Set<String> = emptySet()

    /** The stock context's view of a published order. */
    data class OrderView(val quantity: Int, val cancelled: Boolean)

    class BoardDown(message: String) : RuntimeException(message)

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    /** `sales`'s `Board.onOrder`, over its own log. */
    private val salesBoard = ProcessorBinding.project<OrderState>(
        "Board",
        "onOrder",
        OrderState::class.java,
        startFrom = StartFrom.BEGINNING,
    ) { event, _ -> handled(event, salesFails, salesSeen) }

    /** `stock`'s `Board.onOrder`, over the orders `sales` publishes to its topic. */
    private val stockBoard = ProcessorBinding.project<OrderView>(
        "Board",
        "onOrder",
        OrderView::class.java,
        startFrom = StartFrom.BEGINNING,
        aggregates = listOf("sales.order"),
    ) { event, _ -> handled(event, stockFails, stockSeen) }

    private fun handled(event: StateEvent<*>, fails: Set<String>, seen: MutableList<Pair<String, Int>>) {
        if (event.ref.id in fails) {
            throw BoardDown("Board.onOrder is down for ${event.ref.id}")
        }
        seen += event.ref.id to event.version
    }

    private fun board(context: String, aggregate: String = "order", source: String = SALES) = SubscriptionManifest(
        name = BOARD,
        kind = SubscriptionKind.PROJECTION,
        source = SubscriptionSource(source, listOf(aggregate)),
        function = FunctionManifest(FunctionKind.PROJECT, context, "Board", "onOrder"),
    )

    /** One service: `sales` publishes its orders and boards them; `stock` boards them from the topic. */
    private val manifest: Manifest = Manifest(
        service = ManifestService(SALES_SERVICE),
        contexts = listOf(
            salesManifest().contexts.single().copyWith(
                subscriptions = listOf(board(SALES)),
                published = listOf(PublishedManifest("order", PublishedManifest.defaultTopic(SALES, "order"))),
            ),
            stockManifest().contexts.single().copyWith(subscriptions = listOf(board(STOCK))),
        ),
    )

    /** The parked entries are scanned at once and retried without a backoff. */
    private val fastScans = ServiceNodeOptions(
        cluster = fastOptions(),
        subscription = testOptions().copyWith(
            parked = ParkedRetryOptions(
                spec = ParkedRetrySpec(maxRetries = 1_000, initialBackoff = Duration.ZERO, claimTtl = Duration.ZERO),
                scanInterval = 20.milliseconds,
            ),
        ),
    )

    private fun node(
        name: String,
        bindings: SubscriptionFactory = SubscriptionBindings(
            mapOf(SALES to listOf(salesBoard), STOCK to listOf(stockBoard))
        ) + PublishedSinks.topics(this.manifest, topics, PREFIX).factory,
        manifest: Manifest = this.manifest,
        options: ServiceNodeOptions = fastNodeOptions(),
    ): ServiceNode = ServiceNode(
        SALES_SERVICE,
        name,
        "$name.internal:9090",
        listOf(orderModel(), StockItems().model),
        manifest,
        backends,
        channel,
        options = options,
        subscriptions = bindings,
        contracts = ContractDirectory(manifest, emptyList()),
        topics = SourceTopics(topics, PREFIX, topics),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun started(name: String): ServiceNode = node(name).also {
        it.start()
        it.topicSubscriptions.getValue(ORDERS).awaitStarted()
    }

    private suspend fun ServiceNode.send(context: String, ref: AggregateRef, body: Any) {
        val id = "c-${commands.incrementAndGet()}"
        context(
            context
        ).let {
            it.coordinator.stream(
                it.models.first { m -> m.type == ref.type },
                Command(id, id, ref, body)
            ).toList()
        }
    }

    /**
     * An order whose partition in the context's log is, or is not, its partition in the topic: with even partition
     * counts the in-memory topic's and the log's hashes never agree, so the topic has 3.
     */
    private suspend fun order(samePartition: Boolean): AggregateRef {
        for (n in 0 until 1_000) {
            val ref = ORDER.ref(TENANT, UUID.nameUUIDFromBytes("o-$n".toByteArray()).toString())
            val same = Partitioner.partitionOf(ref, LOG_PARTITIONS) == topics.partitionOf(ORDERS, ref.toString())
            if (same == samePartition) {
                return ref
            }
        }
        error("No order whose partitions are ${if (samePartition) "the same" else "different"}.")
    }

    /** Waits until [subscription]'s parked entries are scanned [times] more times. */
    private suspend fun scannedAgain(subscription: SubscriptionId, times: Int = 3) {
        val from = scans[subscription]?.get() ?: 0
        eventually { (scans[subscription]?.get() ?: 0) >= from + times }
    }

    @Test
    fun `one context's failure holds back its own subscription only, not the other context's of one name`(): Unit =
        runBlocking {
            // Of the same partition in the log and the topic, where a shared key would hold it in both.
            val ref = order(samePartition = true)
            stockFails = setOf(ref.id)
            started("node-1").let { node ->
                node.send(SALES, ref, PlaceOrder(1))
                eventually { ledger.parked(STOCK_BOARD, ref) != null && (ref.id to 1) in salesSeen }
                node.stop()
            }
            ledger.parked(SALES_BOARD, ref).assert().isNull()

            val node = started("node-2")
            node.send(SALES, ref, CancelOrder())
            eventually { (ref.id to 2) in salesSeen }
            salesSeen.assert().containsExactly(ref.id to 1, ref.id to 2)
            ledger.parked(STOCK_BOARD, ref).assert().isNotNull()
        }

    @Test
    fun `ops list an entry once under its own context, and the other's retry finds nothing parked`(): Unit =
        runBlocking {
            val ref = order(samePartition = true)
            stockFails = setOf(ref.id)
            val node = started("node-1")
            node.send(SALES, ref, PlaceOrder(1))
            eventually { ledger.parked(STOCK_BOARD, ref) != null && (ref.id to 1) in salesSeen }

            node.parked.list().entries.filter { it.entry.ref == ref }.map { it.id }.assert().containsExactly(
                STOCK_BOARD
            )
            node.parked.get(STOCK_BOARD, ref)!!.context.assert().isEqualTo(STOCK)
            node.parked.get(SALES_BOARD, ref).assert().isNull()
            node.parked.retry(SALES_BOARD, ref).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
            salesSeen.assert().containsExactly(ref.id to 1)
            ledger.parked(STOCK_BOARD, ref).assert().isNotNull()

            // The stock's own retry, its downstream back, handles what it parked and resolves its entry.
            stockFails = emptySet()
            node.parked.retry(STOCK_BOARD, ref).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
            stockSeen.assert().containsExactly(ref.id to 1)
            ledger.parked(STOCK_BOARD, ref).assert().isNull()
        }

    @Test
    fun `a log subscription's entry keeps its partition when a topic subscription of one name starts`(): Unit =
        runBlocking {
            val ref = order(samePartition = false)
            salesFails = setOf(ref.id)
            started("node-1").let { node ->
                node.send(SALES, ref, PlaceOrder(1))
                eventually { ledger.parked(SALES_BOARD, ref) != null && (ref.id to 1) in stockSeen }
                node.stop()
            }
            val reason = ledger.parked(SALES_BOARD, ref)!!.reason

            // The stock's topic starts again, and lists its own entries for those parked before the topic grew.
            salesFails = emptySet()
            val node = started("node-2")
            val parked = ledger.parked(SALES_BOARD, ref)!!
            parked.partition.assert().isEqualTo(Partitioner.partitionOf(ref, LOG_PARTITIONS))
            parked.reason.assert().isEqualTo(reason)
            parked.unrecoverable.assert().isFalse()
            node.parked.retry(SALES_BOARD, ref).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
            salesSeen.assert().containsExactly(ref.id to 1)
        }

    @Test
    fun `a subscription of one name over other aggregates never scans, retries nor resolves the other's entry`(): Unit =
        runBlocking {
            val items = CopyOnWriteArrayList<String>()
            val bindings = { context: String, fails: Boolean ->
                ProcessorBinding.project<Any>("Board", "onOrder", null, startFrom = StartFrom.BEGINNING) { event, _ ->
                    if (fails) {
                        throw BoardDown("$context Board.onOrder is down")
                    }
                    items += event.ref.id
                }
            }
            // Each context's `Board.onOrder` over its own aggregates only.
            val own = manifest.copyWith(
                contexts = listOf(
                    manifest.contexts[0].copyWith(subscriptions = listOf(board(SALES)), published = emptyList()),
                    manifest.contexts[1].copyWith(subscriptions = listOf(board(STOCK, "item", STOCK))),
                ),
            )
            val node = node(
                "node-1",
                SubscriptionBindings(
                    mapOf(SALES to listOf(bindings(SALES, false)), STOCK to listOf(bindings(STOCK, true)))
                ),
                own,
                fastScans,
            )
            node.start()
            val item = ITEM.ref(TENANT, "i-1")
            node.send(STOCK, item, Reserve(1))
            eventually { ledger.parked(STOCK_BOARD, item) != null }
            scannedAgain(SALES_BOARD)
            ledger.parked(STOCK_BOARD, item).assert().isNotNull()
            seenByScans[SALES_BOARD].orEmpty().assert().doesNotContain(item)
            items.assert().isEmpty()
        }

    @Test
    fun `two contexts' built-in wow-snapshot keep their entries apart on one store`(): Unit = runBlocking {
        // The stock context's snapshot store refuses every write: its `wow.snapshot` parks the item.
        val broken = object : SnapshotStore by InMemorySnapshotStore() {
            override suspend fun save(snapshot: SnapshotRecord): Boolean = throw BoardDown(
                "The stock's snapshots are down."
            )

            override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> =
                throw BoardDown("The stock's snapshots are down.")
        }
        val healthy = backends
        backends = BackendFactory { context ->
            healthy.backends(context).let {
                if (context == STOCK) it.copyWith(snapshots = SnapshotTargets.single(broken)) else it
            }
        }
        val bare = manifest.copyWith(
            contexts = manifest.contexts.map { it.copyWith(subscriptions = emptyList(), published = emptyList()) },
        )
        val node = node("node-1", SubscriptionBindings(emptyMap()), bare, fastScans)
        node.start()
        val stockSnapshot = SubscriptionId(STOCK, Hop.SNAPSHOT_SUBSCRIPTION)
        val salesSnapshot = SubscriptionId(SALES, Hop.SNAPSHOT_SUBSCRIPTION)
        node.parked.subscriptions.assert().contains(stockSnapshot, salesSnapshot)
        val item = ITEM.ref(TENANT, "i-1")
        node.send(STOCK, item, Reserve(1))
        eventually { ledger.parked(stockSnapshot, item) != null }

        // The sales context's scan neither sees, retries nor resolves it.
        scannedAgain(salesSnapshot)
        seenByScans[salesSnapshot].orEmpty().assert().doesNotContain(item)
        ledger.parked(stockSnapshot, item).assert().isNotNull()
        ledger.parked(salesSnapshot, item).assert().isNull()
        // The operator sees it once, under its own context; the other context's retry finds nothing parked.
        node.parked.list().entries.filter { it.entry.ref == item }.map { it.id }.assert().containsExactly(stockSnapshot)
        node.parked.list(salesSnapshot).entries.assert().isEmpty()
        node.parked.retry(salesSnapshot, item).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        ledger.parked(stockSnapshot, item).assert().isNotNull()
    }

    @Test
    fun `two contexts' parked timers are each their own context's, on one ledger`(): Unit = runBlocking {
        val clock = TestClock()
        val one = InMemoryFailureLedger()
        val loop = TimerLoop(
            TimerOptions(
                pollInterval = 5.milliseconds,
                retry = RetryPolicy(maxAttempts = 1, initialBackoff = 1.milliseconds, maxBackoff = 1.minutes),
            ),
            clock,
        )
        val sales = TimerRunner(SALES, InMemoryTimerStore(), one, TimerFirer { "COMMITTED" }, loop)
        val stockStore = InMemoryTimerStore()
        // Only the stock's timers fail: the command they send is refused.
        val stock = TimerRunner(STOCK, stockStore, one, { error("The item cannot be released.") }, loop)
        val target = ITEM.ref(TENANT, "i-1")
        stockStore.put(
            Timer(
                id = "$target#release@1",
                owner = target,
                key = "release",
                version = 1,
                partition = 0,
                rule = ScheduleRule.At(clock.millis()),
                dueAt = clock.millis(),
                target = target,
                commandType = "stock.item.Release",
                body = "{}",
            ),
        )
        try {
            stock.startPartition(0)
            eventually { stockStore.find(target, "release")?.status == TimerStatus.PARKED }
            eventually { one.parked(stock.parkedSubscription, target) != null }
        } finally {
            stock.stop()
            loop.close()
        }
        val runtime = { group: String ->
            SubscriptionRuntime(group, InMemoryEventLog(1), InMemoryCheckpointStore(), one, emptyList())
        }
        val ops = ParkedOps.of(emptyList(), mapOf(sales to (runtime(SALES) to 1), stock to (runtime(STOCK) to 1)))
        val stockTimers = SubscriptionId(STOCK, TimerOptions.PARKED_SUBSCRIPTION)
        val salesTimers = SubscriptionId(SALES, TimerOptions.PARKED_SUBSCRIPTION)
        stock.parkedSubscription.assert().isEqualTo(stockTimers)
        ops.list().entries.map { it.id }.assert().containsExactly(stockTimers)
        ops.get(salesTimers, target).assert().isNull()
        val failedAt = ops.get(stockTimers, target)!!.entry.failedAt
        ops.skip(salesTimers, target, failedAt).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        one.parked(stockTimers, target).assert().isNotNull()
        stockStore.find(target, "release")!!.status.assert().isEqualTo(TimerStatus.PARKED)
        ops.skip(stockTimers, target, failedAt).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        one.parked(stockTimers, target).assert().isNull()
    }

    private companion object {
        const val PARTITIONS = 3
        const val LOG_PARTITIONS = 4
        const val BOARD = "Board.onOrder"
        const val ORDERS = PREFIX + "wow.sales.order.state"
        val SALES_BOARD = SubscriptionId(SALES, BOARD)
        val STOCK_BOARD = SubscriptionId(STOCK, BOARD)
    }
}
