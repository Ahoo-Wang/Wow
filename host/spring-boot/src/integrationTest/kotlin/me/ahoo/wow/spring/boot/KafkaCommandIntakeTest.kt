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

package me.ahoo.wow.spring.boot

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kafka.KafkaOptions
import me.ahoo.wow.kafka.KafkaPartitionedSource
import me.ahoo.wow.kafka.KafkaTopicAdmin
import me.ahoo.wow.kafka.KafkaTopicProducer
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation
import me.ahoo.wow.manifest.SystemCommands

/**
 * Cross-context commands and rejection receipts on a real broker (decisions D33, D47, D135–D137): two nodes of two
 * services in one JVM, a sales service whose saga reserves stock for each order and a stock service that takes
 * commands from its command topic. A reservation the stock accepts is executed by the owner of its log partition; one
 * it rejects comes back as a receipt, and the sales saga's `@OnRejected` handler cancels the order. When the saga
 * asks for it (decision D192), one it commits comes back as a success receipt too, to its `@OnCommitted` handler,
 * once, though a stock node that crashed before its checkpoint sends it again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaCommandIntakeTest {
    private val kafka = WowContainers.newKafka().also { it.start() }
    private val options = KafkaOptions(kafka.bootstrapServers)
    private val admin = Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))
    private val producer = KafkaTopicProducer.create(options)
    private val sources = CopyOnWriteArrayList<KafkaPartitionedSource>()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val channel = LoopbackClusterChannel()

    private val reservations = CopyOnWriteArrayList<String>()
    private val cancellations = CopyOnWriteArrayList<String>()
    private val rejections = CopyOnWriteArrayList<CommandRejected<*>>()
    private val confirmations = CopyOnWriteArrayList<String>()
    private val commits = CopyOnWriteArrayList<CommandCommitted<*>>()

    init {
        admin.createVisible(options, NewTopic(PREFIX + "wow.stock.commands", PARTITIONS, 1.toShort()), NewTopic(PREFIX + "wow.sales.receipts", 2, 1.toShort()))
    }

    @AfterEach
    fun stopNodes(): Unit = runBlocking {
        nodes.forEach {
            it.stop()
            channel.unbind(it.address)
        }
        nodes.clear()
        sources.forEach { it.close() }
        sources.clear()
        listOf(reservations, cancellations, rejections, confirmations, commits).forEach { it.clear() }
    }

    @AfterAll
    fun close() {
        topicAdmin.close()
        producer.close()
        admin.close()
        kafka.stop()
    }

    private val topicAdmin = KafkaTopicAdmin.create(options)

    private fun topics(prefix: String = PREFIX, intake: Boolean = false, ddl: Ddl = Ddl.NONE): SourceTopics =
        SourceTopics(
            KafkaPartitionedSource.create(options).also { sources += it },
            prefix,
            producer,
            intake,
            topicAdmin,
            ddl,
        )

    private fun node(
        service: String,
        model: AggregateModel<*>,
        manifest: Manifest,
        topics: SourceTopics,
        contracts: ContractDirectory = ContractDirectory(manifest),
        bindings: SubscriptionBindings = SubscriptionBindings.NONE,
        backends: BackendFactory = InMemoryContexts(),
    ): ServiceNode = ServiceNode(
        service,
        "$service-1",
        "$service-1.internal:9090",
        listOf(model),
        manifest,
        backends,
        channel,
        // Each node signals the other's waits, as /wow/internal/signals would.
        remoteSignals = SignalSender { origin, signal, _ -> nodes.firstOrNull { it.node == origin.node }?.waits?.signal(signal) },
        options = NODE_OPTIONS,
        subscriptions = bindings,
        contracts = contracts,
        topics = topics,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun stockNode(topics: SourceTopics = topics(intake = true)) =
        node(STOCK_SERVICE, stockModel(), stockManifest(), topics)

    private fun salesNode(topics: SourceTopics = topics(), committed: Boolean = false) = node(
        SALES_SERVICE,
        orderModel(),
        salesManifest(),
        topics,
        ContractDirectory(salesManifest(), listOf(ManifestFragment("stock-api.jar!/${PeerContracts.RESOURCE}", stockManifest()))),
        SubscriptionBindings(mapOf(SALES to listOf(orderSaga(committed)))),
    )

    private suspend fun ServiceNode.place(id: String, quantity: Int): CommandResult {
        val context = context(SALES)
        return context.coordinator.execute(
            context.models.first(),
            Command("place-$id", "place-$id", ORDER.ref(TENANT, id), PlaceOrder(quantity), wait = WaitPlan.COMMITTED),
        )
    }

    @Test
    fun `a saga's command should be executed by the other service, and its rejection compensated`(): Unit =
        runBlocking {
            val stock = stockNode().also { it.start() }
            val sales = salesNode().also { it.start() }
            sales.topicSubscriptions.getValue(PREFIX + "wow.sales.receipts").awaitStarted()
            sales.place("o-1", 2).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            sales.place("o-2", AVAILABLE + 1).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { "o-1" in reservations && cancellations.size == 1 }
            // The accepted reservation, in the stock's log partition of its item among the topic's partitions.
            val item = ITEM.ref(TENANT, "o-1")
            val partition = Partitioner.partitionOf(item, PARTITIONS)
            val intake = checkNotNull(stock.context(STOCK).intake)
            intake.partitions.assert().isEqualTo((0 until PARTITIONS).toSet())
            KafkaPartitionedSource.create(options).use { it.offsets(PREFIX + "wow.stock.commands", partition) }
                .end.assert().isGreaterThanOrEqualTo(1L)
            // The rejected one came back once, to the saga's handler, which cancelled the order.
            val rejected = rejections.single()
            rejected.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-2"))
            rejected.error.code.assert().isEqualTo(INSUFFICIENT)
            rejected.commandType.assert().isEqualTo("stock.item.Reserve")
            rejected.executionId.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-2"))
            cancellations.assert().containsExactly("o-2")
            reservations.assert().containsExactly("o-1")
        }

    @Test
    fun `a success receipt should come back once, though a stock node that crashed before its checkpoint sends it again`(): Unit =
        runBlocking {
            val prefix = "committed."
            admin.createVisible(options, NewTopic(prefix + "wow.stock.commands", PARTITIONS, 1.toShort()), NewTopic(prefix + "wow.sales.receipts", 2, 1.toShort()))
            // The first stock node never saves a checkpoint: it crashes after each receipt, before the save.
            val stockBackends = InMemoryContexts()
            val crashing = BackendFactory { stockBackends.backends(it).copyWith(checkpoints = DroppedCheckpoints) }
            val first = node(STOCK_SERVICE, stockModel(), stockManifest(), topics(prefix, intake = true), backends = crashing)
            first.start()
            val sales = salesNode(topics(prefix), committed = true).also { it.start() }
            sales.topicSubscriptions.getValue(prefix + "wow.sales.receipts").awaitStarted()
            sales.place("o-3", 1).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { confirmations.size == 1 }
            val committed = commits.single()
            committed.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-3"))
            committed.version.assert().isEqualTo(1)
            committed.result.duplicate.assert().isFalse()
            committed.executionId.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-3"))
            first.stop()
            nodes.remove(first)
            channel.unbind(first.address)
            // Its successor reads the command again from the last checkpoint: decided once, its receipt sent again.
            node(STOCK_SERVICE, stockModel(), stockManifest(), topics(prefix, intake = true), backends = stockBackends).start()
            KafkaPartitionedSource.create(options).use { source ->
                eventually { (0 until 2).sumOf { source.offsets(prefix + "wow.sales.receipts", it).end } == 2L }
            }
            delay(500.milliseconds)
            reservations.assert().containsExactly("o-3")
            commits.size.assert().isEqualTo(1)
            confirmations.assert().containsExactly("o-3")
        }

    @Test
    fun `a command of the other service from code that is no saga should go through its command topic (M-1)`(): Unit =
        runBlocking {
            val prefix = "peer."
            admin.createVisible(options, NewTopic(prefix + "wow.stock.commands", PARTITIONS, 1.toShort()))
            stockNode(topics(prefix, intake = true)).also { it.start() }
            val sales = salesNode(topics(prefix)).also { it.start() }
            fun reserve(item: String, quantity: Int, wait: WaitPlan) =
                ServerCommandRequest.builder(Reserve(quantity), ITEM.ref(TENANT, item)).caller(CLERK).wait(wait).build()
            val accepted = sales.gateway.execute(reserve("m-1", 1, WaitPlan.ACCEPTED))
            accepted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            accepted.hop.assert().isEqualTo(Hop.accepted(STOCK))
            eventually { "m-1" in reservations }
            val committed = sales.gateway.execute(reserve("m-2", 2, WaitPlan.COMMITTED.within(30.seconds)))
            committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            committed.hop.assert().isEqualTo(Hop.committed(STOCK))
            committed.version.assert().isEqualTo(1)
            val rejected = sales.gateway.execute(reserve("m-3", AVAILABLE + 1, WaitPlan.COMMITTED.within(30.seconds)))
            rejected.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            rejected.error!!.code.assert().isEqualTo(INSUFFICIENT)
            val beyond = sales.gateway.execute(reserve("m-4", 1, WaitPlan.SNAPSHOT))
            beyond.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
            reservations.assert().containsExactly("m-1", "m-2")
        }

    @Test
    fun `the intake should not start when its command topic's partitions are not the log's`(): Unit = runBlocking {
        admin.createVisible(options, NewTopic("mismatch.wow.stock.commands", PARTITIONS - 1, 1.toShort()))
        val stock = stockNode(topics("mismatch.", intake = true))
        assertThrows<IllegalStateException> { runBlocking { stock.start() } }.message.assert()
            .contains("has ${PARTITIONS - 1} partitions").contains("must have $PARTITIONS")
        CommandIntake.NAME.assert().isEqualTo("wow.commands")
    }

    @Test
    fun `the node should create its command topic with the log's partitions, or refuse to start without it`(): Unit =
        runBlocking {
            assertThrows<IllegalStateException> { runBlocking { stockNode(topics("validate.", true, Ddl.VALIDATE)).start() } }
                .message.assert().contains("validate.wow.stock.commands does not exist")
            nodes.removeLast().also { channel.unbind(it.address) }
            val stock = stockNode(topics("auto.", intake = true, ddl = Ddl.AUTO)).also { it.start() }
            stock.isReady.assert().isTrue()
            topicAdmin.partitions(setOf("auto.wow.stock.commands")).assert().isEqualTo(mapOf("auto.wow.stock.commands" to PARTITIONS))
            eventually { checkNotNull(stock.context(STOCK).intake).partitions == (0 until PARTITIONS).toSet() }
        }

    private suspend fun eventually(timeout: Duration = 60.seconds, condition: suspend () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) {
                delay(20)
            }
        }
    }

    data class PlaceOrder(val quantity: Int)
    data class OrderPlaced(val quantity: Int)
    class CancelOrder
    class OrderCancelled
    class ConfirmOrder
    class OrderConfirmed

    class OrderState {
        var cancelled = false
    }

    data class Reserve(val quantity: Int)
    data class Reserved(val quantity: Int)

    class ItemState {
        var reserved = 0
    }

    private fun orderModel(): AggregateModel<OrderState> = aggregate<OrderState>(SALES, "order") {
        initial { OrderState() }
        decide<PlaceOrder>(Creation.REQUIRED) { _, body -> Decision.events(OrderPlaced(body.quantity)) }
        decide<CancelOrder> { _, _ ->
            cancellations += command.ref.id
            Decision.events(OrderCancelled())
        }
        decide<ConfirmOrder> { _, _ ->
            confirmations += command.ref.id
            Decision.events(OrderConfirmed())
        }
        evolve<OrderPlaced> { }
        evolve<OrderCancelled> { cancelled = true }
        evolve<OrderConfirmed> { }
    }

    private fun stockModel(): AggregateModel<ItemState> = aggregate<ItemState>(STOCK, "item") {
        initial { ItemState() }
        decide<Reserve>(Creation.ALLOWED) { _, body ->
            if (body.quantity > AVAILABLE) {
                Decision.reject(INSUFFICIENT, "Only $AVAILABLE left.")
            } else {
                reservations += command.ref.id
                Decision.events(Reserved(body.quantity))
            }
        }
        evolve<Reserved> { reserved += it.quantity }
    }

    private fun orderSaga(committed: Boolean): ProcessorBinding<OrderState> {
        val onRejected = RejectionBinding.of("OrderSaga", "onReserveRejected", Reserve::class.java) { rejected, context ->
            rejections += rejected
            listOf(context.command(rejected.source, CancelOrder()))
        }
        val onReserved = CommittedBinding.of("OrderSaga", "onReserved", Reserve::class.java) { receipt, context ->
            commits += receipt
            listOf(context.command(receipt.executionId.ref, ConfirmOrder()))
        }
        return ProcessorBinding.saga(
            "OrderSaga",
            "onOrderPlaced",
            OrderState::class.java,
            rejections = listOf(onRejected),
            commits = if (committed) listOf(onReserved) else emptyList(),
        ) {
                event,
                context,
            ->
            event.events.filterIsInstance<OrderPlaced>().map {
                context.command(ITEM.ref(event.ref.tenantId, event.ref.id), Reserve(it.quantity))
            }
        }
    }

    /** A checkpoint store that never saves: the node crashes before each save. */
    private object DroppedCheckpoints : CheckpointStore {
        override suspend fun load(group: String, subscription: String, partition: Int): Long? = null

        override suspend fun list(group: String): List<Checkpoint> = emptyList()

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) = Unit
    }

    /** Each context's stores in memory, opened once. */
    private class InMemoryContexts : BackendFactory {
        private val opened = ConcurrentHashMap<String, ContextBackends>()

        override fun backends(context: String): ContextBackends = opened.computeIfAbsent(context) {
            ContextBackends(InMemoryEventLog(PARTITIONS), InMemoryCheckpointStore(), InMemoryLeaseStore(), InMemoryFailureLedger())
        }
    }

    private companion object {
        const val PREFIX = "it."
        const val PARTITIONS = 4
        const val SALES = "sales"
        const val STOCK = "stock"
        const val SALES_SERVICE = "sales-service"
        const val STOCK_SERVICE = "stock-service"
        const val TENANT = "t"
        const val AVAILABLE = 5
        const val INSUFFICIENT = "stock.insufficient"
        val CLERK: Caller = Caller(TENANT, Scope("clerk"), subject = "clerk")
        val ORDER = AggregateType(SALES, "order")
        val ITEM = AggregateType(STOCK, "item")

        val NODE_OPTIONS = ServiceNodeOptions(
            cluster = ClusterOptions(
                leaseTtl = 3.seconds,
                renewInterval = 500.milliseconds,
                memberTtl = 3.seconds,
                heartbeatInterval = 500.milliseconds,
                reconcileInterval = 200.milliseconds,
                routeRefreshInterval = 200.milliseconds,
            ),
            subscription = SubscriptionOptions(
                retry = RetryPolicy(maxAttempts = 3, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
                checkpointInterval = 20.milliseconds,
                readRetry = RetryPolicy(maxAttempts = 1, initialBackoff = 10.milliseconds, maxBackoff = 100.milliseconds),
            ),
        )

        private fun aggregateManifest(
            context: String,
            name: String,
            commands: List<Pair<Class<*>, ManifestCreation>>,
            events: List<Class<*>>,
        ) = AggregateManifest(
            name,
            state = StateManifest(Manifest.schemaRef("${name}State")),
            commands = commands.map { (command, creation) ->
                CommandManifest(command.simpleName, schema = Manifest.schemaRef(command.name), creation = creation)
            } + Lifecycle.COMMANDS.keys.map { SystemCommands.lifecycle(context, name, it) },
            events = events.map { EventManifest(it.simpleName, schema = Manifest.schemaRef(it.name)) },
        )

        fun salesManifest(): Manifest = Manifest(
            service = ServiceInfo(SALES_SERVICE),
            contexts = listOf(
                ContextManifest(
                    SALES,
                    aggregates = listOf(
                        aggregateManifest(
                            SALES,
                            "order",
                            listOf(
                                PlaceOrder::class.java to ManifestCreation.REQUIRED,
                                CancelOrder::class.java to ManifestCreation.NONE,
                                ConfirmOrder::class.java to ManifestCreation.NONE,
                            ),
                            listOf(OrderPlaced::class.java, OrderCancelled::class.java, OrderConfirmed::class.java),
                        ),
                    ),
                ),
            ),
        )

        fun stockManifest(): Manifest = Manifest(
            service = ServiceInfo(STOCK_SERVICE),
            contexts = listOf(
                ContextManifest(
                    STOCK,
                    aggregates = listOf(
                        aggregateManifest(STOCK, "item", listOf(Reserve::class.java to ManifestCreation.ALLOWED), listOf(Reserved::class.java)),
                    ),
                ),
            ),
        )
    }
}
