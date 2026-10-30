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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

/**
 * A context whose aggregates are in two log groups (decision D177): its orders on the default store, its invoices on a
 * store of their own. Each group has its own partition sequences, checkpoints, parked entries and timers; the owner of
 * a partition reads it in both, and hands both over.
 */
class LogGroupsTest {
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stores = GroupedStores()
    private val projected = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun node(name: String, backends: BackendFactory = stores): ServiceNode = ServiceNode(
        SERVICE,
        name,
        "$name.internal:9090",
        listOf(orderModel(), invoiceModel()),
        manifest(),
        backends,
        channel,
        options = fastNodeOptions().copyWith(
            timers = TimerOptions(pollInterval = 20.milliseconds, lookahead = 1.seconds)
        ),
        subscriptions = SubscriptionFactory { context ->
            listOf(
                Subscription(PROJECTOR, SubscriptionFilter(context.context)) { entry ->
                    check(entry.stream.ref.id != POISON) { "The projector fails on $POISON." }
                    projected += "${entry.stream.ref.type.name}:${entry.stream.ref.id}@${entry.stream.version}"
                },
            )
        },
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun ServiceNode.send(ref: AggregateRef, body: Any, wait: WaitPlan = WaitPlan.COMMITTED): CommandResult {
        val context = context(CONTEXT)
        val requestId = "${ref.id}-${body.javaClass.simpleName}"
        return context.coordinator.execute(
            context.models.first { it.type == ref.type },
            Command(requestId, requestId, ref, body, wait = wait),
        )
    }

    @Test
    fun `each group keeps its aggregates, its partition sequences and its checkpoints`(): Unit = runBlocking {
        val node = node("node-1").also { it.start() }
        node.send(ORDER.ref(TENANT, "o-1"), Place(delayMillis = 60_000)).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        node.send(INVOICE.ref(TENANT, "i-1"), Issue(delayMillis = 60_000)).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        val default = stores.default
        val invoices = stores.invoices
        default.log.read(ORDER.ref(TENANT, "o-1")).toList().assert().hasSize(1)
        default.log.read(INVOICE.ref(TENANT, "i-1")).toList().assert().isEmpty()
        invoices.log.read(INVOICE.ref(TENANT, "i-1")).toList().assert().hasSize(1)
        invoices.log.read(ORDER.ref(TENANT, "o-1")).toList().assert().isEmpty()
        // Each group's partition sequence starts at 1 on its own.
        val invoicePartition = Partitioner.partitionOf(INVOICE.ref(TENANT, "i-1"), PARTITIONS)
        invoices.log.tail(invoicePartition, null).first().position.sequence.assert().isEqualTo(1L)
        node.context(CONTEXT).log.read(INVOICE.ref(TENANT, "i-1")).toList().assert().hasSize(1)
        eventually { projected.containsAll(listOf("order:o-1@1", "invoice:i-1@1")) }
        val groups = node.context(CONTEXT).logGroups
        groups.map { it.store }.assert().containsExactly(DEFAULT_STORE, INVOICE_STORE)
        groups.map { it.types }.assert().containsExactly(setOf(ORDER, SnapshotRebuilds.typeOf(CONTEXT)), setOf(INVOICE))
        groups.last().subscriptions.group.assert().isEqualTo("$SERVICE/$CONTEXT#$INVOICE_STORE")
        groups.forEach { it.subscriptions.subscriptionNames.assert().contains(PROJECTOR) }
        eventually { invoices.checkpoints.load("$SERVICE/$CONTEXT#$INVOICE_STORE", PROJECTOR, invoicePartition) == 1L }
        default.checkpoints.load("$SERVICE/$CONTEXT#$INVOICE_STORE", PROJECTOR, invoicePartition).assert().isNull()
    }

    @Test
    fun `a wait follows each group's subscriptions, a duplicate request answered by its group`(): Unit = runBlocking {
        val node = node("node-1").also { it.start() }
        val invoice = INVOICE.ref(TENANT, "i-2")
        node.send(invoice, Issue(delayMillis = 60_000), WaitPlan.SNAPSHOT).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { stores.snapshots.load(invoice)?.version == 1 }
        val again = node.send(invoice, Issue(delayMillis = 60_000), WaitPlan.SNAPSHOT)
        again.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        node.context(CONTEXT).log.findByRequestId(INVOICE, TENANT, "i-2-Issue")?.ref.assert().isEqualTo(invoice)
    }

    @Test
    fun `a type's snapshot write checkpoint should move with its group's snapshot writes only`(): Unit = runBlocking {
        val node = node("node-1").also { it.start() }
        val context = node.context(CONTEXT)
        context.snapshotCheckpoint(AggregateType("elsewhere", "order")).assert().isNull()
        context.snapshotCheckpoint(AggregateType(CONTEXT, "unknown")).assert().isNull()
        val orders = checkNotNull(context.snapshotCheckpoint(ORDER))
        val invoices = checkNotNull(context.snapshotCheckpoint(INVOICE))
        node.send(INVOICE.ref(TENANT, "i-5"), Issue(delayMillis = 60_000), WaitPlan.SNAPSHOT).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { checkNotNull(context.snapshotCheckpoint(INVOICE)) > invoices }
        context.snapshotCheckpoint(ORDER).assert().isEqualTo(orders)
        node.send(ORDER.ref(TENANT, "o-5"), Place(delayMillis = 60_000), WaitPlan.SNAPSHOT)
        eventually { checkNotNull(context.snapshotCheckpoint(ORDER)) > orders }

        val unsnapshotted = node("node-2", { stores.backends(it).copyWith(snapshots = null) }).also { it.start() }
        unsnapshotted.context(CONTEXT).snapshotCheckpoint(ORDER).assert().isNull()
    }

    @Test
    fun `each type's reservations are in its group's store, unique within the type`(): Unit = runBlocking {
        val node = node("node-1").also { it.start() }
        node.send(ORDER.ref(TENANT, "o-6"), Place(60_000, "n-1")).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        // The same key in another type's scope: no conflict (decision D307).
        node.send(INVOICE.ref(TENANT, "i-6"), Issue(60_000, "n-1")).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        node.send(INVOICE.ref(TENANT, "i-7"), Issue(60_000, "n-1")).error?.code.assert().isEqualTo("number.taken")
        val orders = ReservationScope.of(ORDER, TENANT, NUMBERS)
        val invoices = ReservationScope.of(INVOICE, TENANT, NUMBERS)
        eventually { stores.invoices.reservations.find(invoices, "n-1")?.confirmed == true }
        eventually { stores.default.reservations.find(orders, "n-1")?.confirmed == true }
        stores.invoices.reservations.find(invoices, "n-1")?.owner.assert().isEqualTo(INVOICE.ref(TENANT, "i-6"))
        stores.default.reservations.find(invoices, "n-1").assert().isNull()
        stores.invoices.reservations.find(orders, "n-1").assert().isNull()
    }

    @Test
    fun `each group's timers are in its store, and sent`(): Unit = runBlocking {
        val node = node("node-1").also { it.start() }
        node.send(ORDER.ref(TENANT, "o-3"), Place(delayMillis = 100))
        node.send(INVOICE.ref(TENANT, "i-3"), Issue(delayMillis = 100))
        eventually { stores.default.timers.find(Timers.ownerOf(ORDER.ref(TENANT, "o-3")), DUE)?.status == TimerStatus.FIRED }
        eventually {
            stores.invoices.timers.find(Timers.ownerOf(INVOICE.ref(TENANT, "i-3")), DUE)?.status == TimerStatus.FIRED
        }
        stores.default.timers.find(Timers.ownerOf(INVOICE.ref(TENANT, "i-3")), DUE).assert().isNull()
        stores.invoices.log.read(INVOICE.ref(TENANT, "i-3")).toList().flatMap { it.events.map { event -> event.name } }
            .assert().containsExactly("$CONTEXT.invoice.Issued", "wow.ScheduleSet", "$CONTEXT.invoice.Closed")
        node.context(CONTEXT).ownership.owned.forEach { partition ->
            node.context(CONTEXT).logGroups.forEach { group ->
                group.timers!!.runner.partitions.assert().contains(partition)
                group.subscriptions.partitions.assert().contains(partition)
            }
        }
    }

    @Test
    fun `the next owner reads both groups on from their checkpoints and sends both groups' timers`(): Unit =
        runBlocking {
            val first = node("node-1").also { it.start() }
            first.send(ORDER.ref(TENANT, "o-4"), Place(delayMillis = 1_500))
            first.send(INVOICE.ref(TENANT, "i-4"), Issue(delayMillis = 1_500))
            eventually { projected.containsAll(listOf("order:o-4@1", "invoice:i-4@1")) }
            first.stop()
            first.context(CONTEXT).logGroups.forEach { group ->
                group.timers!!.runner.partitions.assert().isEmpty()
                group.subscriptions.partitions.assert().isEmpty()
            }
            val second = node("node-2").also { it.start() }
            eventually {
                stores.invoices.timers.find(Timers.ownerOf(INVOICE.ref(TENANT, "i-4")), DUE)?.status == TimerStatus.FIRED &&
                    stores.default.timers.find(Timers.ownerOf(ORDER.ref(TENANT, "o-4")), DUE)?.status == TimerStatus.FIRED
            }
            eventually { projected.containsAll(listOf("order:o-4@2", "invoice:i-4@2")) }
            // Neither group's first entries were handled again: each group's checkpoint was saved on the hand-off.
            projected.count { it == "invoice:i-4@1" }.assert().isEqualTo(1)
            projected.count { it == "order:o-4@1" }.assert().isEqualTo(1)
            second.context(CONTEXT).ownership.owned.assert().hasSize(PARTITIONS)
        }

    @Test
    fun `a parked aggregate of a group is in its group's ledger, and retried there`(): Unit = runBlocking {
        val node = node("node-1").also { it.start() }
        val poison = INVOICE.ref(TENANT, POISON)
        node.send(poison, Issue(delayMillis = 60_000))
        eventually { stores.invoices.ledger.isParked(PROJECTOR, poison) }
        stores.default.ledger.isParked(PROJECTOR, poison).assert().isFalse()
        node.parked.get(PROJECTOR_ID, poison)?.entry?.ref.assert().isEqualTo(poison)
        node.parked.list(PROJECTOR_ID).entries.map { it.entry.ref }.assert().containsExactly(poison)
        val failedAt = node.parked.get(PROJECTOR_ID, poison)!!.entry.failedAt
        node.parked.skip(PROJECTOR_ID, poison, failedAt).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        stores.invoices.ledger.isParked(PROJECTOR, poison).assert().isFalse()
    }

    @Test
    fun `a group that holds a type of no model of its context is refused`() {
        val wrong = object : BackendFactory {
            override fun backends(context: String): ContextBackends = stores.backends(context).let {
                val ghost = it.groups.single().copyWith(types = setOf(AggregateType(CONTEXT, "ghost")))
                it.copyWith(groups = listOf(ghost))
            }
        }
        assertThrows<IllegalArgumentException> { node("node-1", wrong) }.message.assert().contains("ghost")
    }

    @Test
    fun `the context's backends refuse groups that do not add up`() {
        val base = stores.backends(CONTEXT)
        val group = base.groups.single()
        assertThrows<IllegalArgumentException> { base.copyWith(groups = listOf(group, group.copyWith(store = "c"))) }
        val onDefaultStore = group.copyWith(store = DEFAULT_STORE)
        assertThrows<IllegalArgumentException> { base.copyWith(groups = listOf(onDefaultStore)) }
        val otherPartitions = group.copyWith(log = InMemoryEventLog(2))
        assertThrows<IllegalArgumentException> { base.copyWith(groups = listOf(otherPartitions)) }
        assertThrows<IllegalArgumentException> { base.copyWith(groups = listOf(group.copyWith(timers = null))) }
        assertThrows<IllegalArgumentException> { group.copyWith(types = emptySet()) }
        assertThrows<IllegalArgumentException> { group.copyWith(store = " ") }
        assertThrows<IllegalArgumentException> { base.copyWith(store = "") }
    }

    /** One store's parts, in memory. */
    class MemoryStore {
        val log = InMemoryEventLog(PARTITIONS)
        val checkpoints = InMemoryCheckpointStore()
        val ledger = InMemoryFailureLedger()
        val timers = InMemoryTimerStore()
        val reservations = InMemoryReservationStore()
    }

    /** The context's orders on the default store, its invoices on a store of their own; one lease store. */
    class GroupedStores : BackendFactory {
        val default = MemoryStore()
        val invoices = MemoryStore()
        val snapshots = InMemorySnapshotStore()
        private val leases = InMemoryLeaseStore()
        private val opened = ConcurrentHashMap<String, ContextBackends>()

        override fun backends(context: String): ContextBackends = opened.computeIfAbsent(context) {
            ContextBackends(
                log = default.log,
                checkpoints = default.checkpoints,
                leases = leases,
                ledger = default.ledger,
                snapshots = SnapshotTargets.single(snapshots),
                reservations = default.reservations,
                timers = default.timers,
                store = DEFAULT_STORE,
                groups = listOf(
                    LogGroupBackends(
                        INVOICE_STORE,
                        setOf(INVOICE),
                        invoices.log,
                        invoices.checkpoints,
                        invoices.ledger,
                        invoices.timers,
                        invoices.reservations,
                    ),
                ),
            )
        }
    }

    data class Place(val delayMillis: Long, val number: String? = null)
    data class Issue(val delayMillis: Long, val number: String? = null)
    data class Close(val reason: String = "due")
    data class Placed(val delayMillis: Long)
    data class Issued(val delayMillis: Long)
    data class Closed(val reason: String)

    class Item {
        var closed: Boolean = false
    }

    private companion object {
        const val SERVICE = "grouped-service"
        const val CONTEXT = "sales"
        const val TENANT = "t"
        const val PARTITIONS = 4
        const val DEFAULT_STORE = "orders-store"
        const val INVOICE_STORE = "invoices-store"
        const val PROJECTOR = "Projector.onEvent"
        val PROJECTOR_ID = SubscriptionId(CONTEXT, PROJECTOR)
        const val POISON = "poison"
        const val DUE = "due"
        const val NUMBERS = "number"
        val ORDER = AggregateType(CONTEXT, "order")
        val INVOICE = AggregateType(CONTEXT, "invoice")

        /** An aggregate created by [create] that closes itself [Close] when its schedule `due` is. */
        inline fun <reified C : Any, reified E : Any> item(
            name: String,
            crossinline delay: (C) -> Long,
            crossinline number: (C) -> String?,
            crossinline created: (C) -> E,
        ): AggregateModel<Item> = aggregate<Item>(CONTEXT, name) {
            initial { Item() }
            decideSuspending<C>(Creation.REQUIRED) { _, body ->
                val key = number(body)
                if (key != null && !reserve(NUMBERS, key)) return@decideSuspending Decision.reject("number.taken", key)
                schedule(DUE, ScheduleRule.after(delay(body).milliseconds), Close())
                Decision.events(created(body))
            }
            decide<Close> { state, body ->
                if (state.closed) Decision.NoOp else Decision.events(Closed(body.reason))
            }
            evolve<E> { }
            evolve<Closed> { closed = true }
        }

        fun orderModel(): AggregateModel<Item> = item<Place, Placed>(
            "order",
            { it.delayMillis },
            { it.number },
        ) { Placed(it.delayMillis) }

        fun invoiceModel(): AggregateModel<Item> = item<Issue, Issued>(
            "invoice",
            { it.delayMillis },
            { it.number },
        ) { Issued(it.delayMillis) }

        fun manifest(): Manifest = Manifest(
            service = ServiceInfo(SERVICE),
            contexts = listOf(
                ContextManifest(
                    CONTEXT,
                    aggregates = listOf(
                        aggregateManifest("order", Place::class.java, Placed::class.java),
                        aggregateManifest("invoice", Issue::class.java, Issued::class.java),
                    ),
                ),
            ),
        )

        fun aggregateManifest(name: String, create: Class<*>, created: Class<*>): AggregateManifest = AggregateManifest(
            name,
            state = StateManifest(Manifest.schemaRef(Item::class.java.name)),
            commands = listOf(create to ManifestCreation.REQUIRED, Close::class.java to ManifestCreation.NONE)
                .map { (command, creation) ->
                    CommandManifest(command.simpleName, schema = Manifest.schemaRef(command.name), creation = creation)
                } + Lifecycle.COMMANDS.keys.map { SystemCommands.lifecycle(CONTEXT, name, it) },
            events = listOf(created, Closed::class.java).map {
                EventManifest(it.simpleName, schema = Manifest.schemaRef(it.name))
            },
        )
    }
}
