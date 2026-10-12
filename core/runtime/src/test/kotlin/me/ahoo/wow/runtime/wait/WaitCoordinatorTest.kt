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

package me.ahoo.wow.runtime.wait

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.InMemoryCheckpointStore
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Design 04 §6 in one service: the stages, chains, duplicates and timeouts of waits (decisions D61–D65, D69). */
class WaitCoordinatorTest {
    data class Place(val items: Int = 1, val reject: Boolean = false)
    data class Touch(val note: String = "")
    data class PlaceSlowly(val items: Int = 1)
    data class Placed(val items: Int)
    data class Reserve(val order: String, val reject: Boolean = false)
    data class Reserved(val order: String)

    class Order(val id: String) {
        var items = 0
    }

    class Stock(val id: String) {
        var reserved = 0
    }

    /** Completed once a [PlaceSlowly] is being decided. */
    private val deciding = CompletableDeferred<Unit>()

    /** A [PlaceSlowly] is decided once it completes. */
    private val decideGate = CompletableDeferred<Unit>()

    private val order = aggregate<Order>("shop", "order") {
        initial { Order(it.id) }
        decide<Place>(Creation.ALLOWED) { _, body ->
            if (body.reject) {
                Decision.reject("shop.order.refused", "Refused.")
            } else {
                putResult(ITEMS, body.items)
                Decision.events(Placed(body.items))
            }
        }
        decide<Touch>(Creation.NONE) { _, _ -> Decision.NoOp }
        decideSuspending<PlaceSlowly>(Creation.ALLOWED) { _, body ->
            deciding.complete(Unit)
            decideGate.await()
            Decision.events(Placed(body.items))
        }
        evolve<Placed> { items = it.items }
    }
    private val stock = aggregate<Stock>("shop", "stock") {
        initial { Stock(it.id) }
        decide<Reserve>(Creation.ALLOWED) { _, body ->
            if (body.reject) {
                Decision.reject(
                    "shop.stock.out",
                    "Out of stock."
                )
            } else {
                putResult(ORDER, body.order)
                Decision.events(Reserved(body.order))
            }
        }
        evolve<Reserved> { reserved++ }
    }

    private val background = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val log = InMemoryEventLog(partitions = 2)
    private val ledger = InMemoryFailureLedger()
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val signals = LocalSignalSender(origin.node, registry)
    private val sequence = AtomicInteger()

    /** While not null, the snapshot subscription holds every entry until it completes. */
    @Volatile
    private var snapshotGate: CompletableDeferred<Unit>? = null

    /** While not null, the saga executes every derived command but its first only once it completes. */
    @Volatile
    private var sagaGate: CompletableDeferred<Unit>? = null
    private val snapshotted = CopyOnWriteArrayList<String>()

    private val snapshot = Subscription("snapshot", SubscriptionFilter("shop"), kind = FunctionKind.SNAPSHOT) {
        snapshotGate?.await()
        snapshotted += "${it.ref.id}@${it.version}"
    }

    /** Reserves stock for each item of a placed order; the commands carry the entry's wait context on. */
    private val saga = Subscription.saga("order.saga", SubscriptionFilter("shop", setOf("order"))) { entry ->
        val wait = entry.transport?.wait?.derived()
        val commands = (1..itemsOf(entry.ref.id)).map { item ->
            Command(
                id = "${entry.stream.commandId}-reserve-$item",
                requestId = "${entry.stream.requestId}-reserve-$item",
                ref = stock.type.ref("t", "${entry.ref.id}-$item"),
                body = Reserve(entry.ref.id, reject = item == rejectedItem),
                causation = Causation(entry.stream.correlationId, "${entry.ref}@${entry.version}"),
            )
        }
        commands.forEachIndexed { index, command ->
            val gate = sagaGate
            if (index == 0 || gate == null) {
                coordinator.forward(stock, command, wait)
            } else {
                background.launch {
                    gate.await()
                    coordinator.forward(stock, command, wait)
                }
            }
        }
        commands.map { it.id }
    }

    private val failing = Subscription("order.failing", SubscriptionFilter("shop", setOf("order"))) {
        error("${it.ref} cannot be projected")
    }

    private val stockOnly = Subscription("stock.projector", SubscriptionFilter("shop", setOf("stock"))) { }

    private val itemsByOrder = HashMap<String, Int>()

    /** The item whose reservation the stock rejects, when not null. */
    @Volatile
    private var rejectedItem: Int? = null

    private fun itemsOf(orderId: String): Int = synchronized(itemsByOrder) { itemsByOrder[orderId] ?: 0 }

    private val runtime = SubscriptionRuntime(
        "shop",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(snapshot, saga, failing, stockOnly),
        testOptions(),
        signals = signals,
    )
    private val executor = Executor(Kernel(log))
    private val coordinator = coordinator(WaitOptions(origin))

    private fun coordinator(
        options: WaitOptions,
        directory: SubscriptionDirectory = runtime,
    ) = WaitCoordinator(executor, registry, directory, signals, options)

    private fun place(
        id: String = "o-${sequence.incrementAndGet()}",
        wait: WaitPlan = WaitPlan.DEFAULT,
        items: Int = 0,
        reject: Boolean = false,
        requestId: String = "r-$id",
    ): Command<Place> {
        synchronized(itemsByOrder) { itemsByOrder[id] = items }
        return Command(
            "c-${sequence.incrementAndGet()}",
            requestId,
            order.type.ref("t", id),
            Place(items, reject),
            wait = wait
        )
    }

    private suspend fun started() {
        runtime.start(setOf(0, 1))
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        snapshotGate?.complete(Unit)
        sagaGate?.complete(Unit)
        decideGate.complete(Unit)
        runtime.stop()
        background.cancel()
    }

    @Test
    fun `the default wait answers once the snapshot subscription has handled the commit`(): Unit = runBlocking {
        started()
        val gate = CompletableDeferred<Unit>().also { snapshotGate = it }
        val command = place()
        val result = async { coordinator.execute(order, command) }
        delay(100)
        result.isCompleted.assert().isFalse()
        registry.size.assert().isEqualTo(1)
        gate.complete(Unit)
        val answered = withTimeout(5.seconds) { result.await() }
        answered.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        answered.hop.assert().isEqualTo(Hop.snapshot("shop"))
        answered.function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
        answered.version.assert().isEqualTo(1)
        answered.commandId.assert().isEqualTo(command.id)
        snapshotted.assert().contains("${command.ref.id}@1")
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a COMMITTED wait answers once the events are appended, and writes no wait context`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val result = coordinator.execute(order, place(wait = WaitPlan.COMMITTED))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.function.kind.assert().isEqualTo(FunctionKind.COMMAND)
        result.function.name.assert().isEqualTo("Place")
        val partition = log.positionOf(result.ref, 1)!!.partition
        log.tail(partition).take(1).toList().single().transport.assert().isNull()
    }

    @Test
    fun `without a snapshot subscription the default wait is COMMITTED`(): Unit = runBlocking {
        val result = coordinator(WaitOptions(origin), SubscriptionDirectory.NONE).execute(order, place())
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `an explicit snapshot wait without a snapshot subscription is rejected without executing`(): Unit =
        runBlocking {
            val coordinator = coordinator(WaitOptions(origin), SubscriptionDirectory.NONE)
            listOf(WaitPlan.SNAPSHOT, WaitPlan.of(Hop.committed(), Hop.snapshot("shop"))).forEach { wait ->
                val command = place(wait = wait)
                val result = coordinator.execute(order, command)
                result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
                result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
                result.error!!.message.assert().contains("shop.order has no snapshot subscription")
                result.hop.assert().isEqualTo(Hop.accepted("shop"))
                log.findByRequestId("t", command.requestId).assert().isNull()
            }
            registry.size.assert().isEqualTo(0)
        }

    @Test
    fun `an explicit snapshot wait is rejected for an aggregate whose snapshots are disabled`(): Unit = runBlocking {
        started()
        val ordersUnsnapshotted = object : SubscriptionDirectory by runtime {
            override fun covers(subscription: String, type: me.ahoo.wow.api.aggregate.AggregateType): Boolean =
                type != order.type && runtime.covers(subscription, type)
        }
        val coordinator = coordinator(WaitOptions(origin), ordersUnsnapshotted)
        val explicit = place(wait = WaitPlan.SNAPSHOT.within(5.seconds))
        coordinator.execute(order, explicit).error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        log.findByRequestId("t", explicit.requestId).assert().isNull()
        val byDefault = coordinator.execute(order, place(wait = WaitPlan.DEFAULT.within(5.seconds)))
        byDefault.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        byDefault.hop.assert().isEqualTo(Hop.committed("shop"))
    }

    @Test
    fun `a rejection fails the wait at once`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val result = withTimeout(2.seconds) { coordinator.execute(order, place(reject = true)) }
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo("shop.order.refused")
        result.hop.assert().isEqualTo(Hop.committed("shop"))
    }

    @Test
    fun `a saga chain completes only when every derived command has committed`(): Unit = runBlocking {
        started()
        val gate = CompletableDeferred<Unit>().also { sagaGate = it }
        val command = place(items = 2, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()))
        val emitted = CopyOnWriteArrayList<CommandResult>()
        val done = background.async { coordinator.stream(order, command).collect { emitted += it } }
        // The first derived command commits before the saga's own signal arrives: it is buffered until then, and
        // emitted right after the saga's hop.
        eventually { emitted.size == 2 }
        val sagaHop = emitted.first()
        sagaHop.hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        sagaHop.function.kind.assert().isEqualTo(FunctionKind.SAGA)
        sagaHop.derived.assert().containsExactly("${command.id}-reserve-1", "${command.id}-reserve-2")
        emitted[1].commandId.assert().isEqualTo("${command.id}-reserve-1")
        emitted[1].hop.assert().isEqualTo(Hop.committed("shop"))
        delay(100)
        done.isCompleted.assert().isFalse()
        gate.complete(Unit)
        withTimeout(5.seconds) { done.await() }
        emitted.size.assert().isEqualTo(3)
        val last = emitted.last()
        last.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        last.hop.assert().isEqualTo(Hop.committed("shop"))
        last.commandId.assert().isEqualTo("${command.id}-reserve-2")
        last.correlationId.assert().isEqualTo(command.id)
        last.ref.type.assert().isEqualTo(stock.type)
    }

    @Test
    fun `the stream emits one result per derived command at each hop after the saga`(): Unit = runBlocking {
        started()
        val command = place(items = 3, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.snapshot()))
        val emitted = withTimeout(5.seconds) { coordinator.stream(order, command).toList() }
        emitted.size.assert().isEqualTo(4)
        emitted.first().hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        val derived = (1..3).map { "${command.id}-reserve-$it" }
        emitted.first().derived.assert().containsExactly(*derived.toTypedArray())
        emitted.drop(1).forEach {
            it.hop.assert().isEqualTo(Hop.snapshot("shop"))
            it.function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        emitted.drop(1).map { it.commandId }.assert().containsExactlyInAnyOrder(*derived.toTypedArray())
        // The answer without streaming is the last command to settle.
        val answered = coordinator.execute(
            order,
            place(items = 2, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.snapshot()))
        )
        answered.hop.assert().isEqualTo(Hop.snapshot("shop"))
        answered.ref.type.assert().isEqualTo(stock.type)
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a derived command's rejection ends the stream at once, as its last result`(): Unit = runBlocking {
        started()
        rejectedItem = 2
        val command = place(items = 3, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()))
        val emitted = withTimeout(5.seconds) { coordinator.stream(order, command).toList() }
        // The saga forwards its commands in order, so their results arrive in order and the third is never applied.
        emitted.map { it.commandId }.assert().containsExactly(
            command.id,
            "${command.id}-reserve-1",
            "${command.id}-reserve-2",
        )
        val last = emitted.last()
        last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        last.error!!.code.assert().isEqualTo("shop.stock.out")
        last.hop.assert().isEqualTo(Hop.committed("shop"))
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a timeout while derived commands settle ends the stream at the saga hop`(): Unit = runBlocking {
        started()
        sagaGate = CompletableDeferred()
        val command = place(
            items = 2,
            wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()).within(500.milliseconds),
        )
        val emitted = withTimeout(5.seconds) { coordinator.stream(order, command).toList() }
        emitted.map { it.commandId }.assert().containsExactly(
            command.id,
            "${command.id}-reserve-1",
            command.id,
        )
        val last = emitted.last()
        last.status.assert().isEqualTo(CommandResult.Status.FAILED)
        last.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        last.hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        last.derived.assert().containsExactly("${command.id}-reserve-1", "${command.id}-reserve-2")
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a saga deriving nothing completes the chain at its own hop`(): Unit = runBlocking {
        started()
        val result = coordinator.execute(
            order,
            place(items = 0, wait = WaitPlan.of(Hop.handled("order.saga"), Hop.committed()))
        )
        result.hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
        result.derived.assert().isEmpty()
    }

    @Test
    fun `a chain may go on to the snapshot of the derived commands`(): Unit = runBlocking {
        started()
        val command = place(items = 1, wait = WaitPlan.of(Hop.committed(), Hop.handled("order.saga"), Hop.snapshot()))
        val hops = coordinator.stream(order, command).toList()
        hops.map { it.hop.stage }.assert().containsExactly(Stage.COMMITTED, Stage.HANDLED, Stage.HANDLED)
        hops.last().function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
        hops.last().commandId.assert().isEqualTo("${command.id}-reserve-1")
        snapshotted.assert().contains("${command.ref.id}-1@1")
        // Each hop answers the results of its own command: the order's at COMMITTED and at its saga's hop, the derived
        // one's at its snapshot.
        hops[0].results.assert().isEqualTo(mapOf(ITEMS to 1))
        hops[1].results.assert().isEqualTo(mapOf(ITEMS to 1))
        hops.last().results.assert().isEqualTo(mapOf(ORDER to command.ref.id))
    }

    @Test
    fun `the stream emits each hop as it is reached`(): Unit = runBlocking {
        started()
        val hops = coordinator.stream(order, place(wait = WaitPlan.of(Hop.accepted(), Hop.committed(), Hop.snapshot())))
            .toList()
        hops.map { it.hop }.assert().containsExactly(Hop.accepted("shop"), Hop.committed("shop"), Hop.snapshot("shop"))
        hops[0].version.assert().isNull()
        hops[1].version.assert().isEqualTo(1)
    }

    @Test
    fun `a timeout reports the last hop reached`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val result = coordinator.execute(order, place(wait = WaitPlan.SNAPSHOT.within(200.milliseconds)))
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.version.assert().isEqualTo(1)
        registry.size.assert().isEqualTo(0)
    }

    private fun placeSlowly(wait: WaitPlan, id: String = "o-${sequence.incrementAndGet()}") =
        Command("c-$id", "r-$id", order.type.ref("t", id), PlaceSlowly(), wait = wait)

    /** A retry of [command], with its request id: the kernel answers it from the commit, if there is one. */
    private suspend fun retried(coordinator: WaitCoordinator, command: Command<*>): CommandResult {
        eventually { log.findByRequestId("t", command.requestId) != null }
        return coordinator.execute(order, command.copy(id = "${command.id}-retry", wait = WaitPlan.COMMITTED))
    }

    @Test
    fun `join waits for an execution that outlived its wait, which settles normally`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED.within(100.milliseconds))
        coordinator.execute(order, command).hop.assert().isEqualTo(Hop.accepted("shop"))
        coordinator.executing.assert().isEqualTo(1)
        val joining = async(Dispatchers.Default) { coordinator.join() }
        delay(100)
        joining.isCompleted.assert().isFalse()

        decideGate.complete(Unit)
        withTimeout(5.seconds) { joining.await() }
        coordinator.executing.assert().isEqualTo(0)
        log.findByRequestId("t", command.requestId)!!.version.assert().isEqualTo(1)
        coordinator.join()
    }

    @Test
    fun `close cancels what still executes`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED.within(100.milliseconds))
        coordinator.execute(order, command).hop.assert().isEqualTo(Hop.accepted("shop"))
        coordinator.close()
        eventually { coordinator.executing == 0 }
        decideGate.complete(Unit)
        log.findByRequestId("t", command.requestId).assert().isNull()
    }

    @Test
    fun `the default timeout is positive, and only capped by the maximum`() {
        WaitOptions(origin, maxTimeout = 5.seconds).defaultTimeout.assert().isEqualTo(WaitPlan.DEFAULT_TIMEOUT)
        assertThrows<IllegalArgumentException> { WaitOptions(origin, defaultTimeout = 0.seconds) }
    }

    @Test
    fun `the deadline bounds a slow execution, which still commits and answers a retry`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED.within(200.milliseconds))
        val emitted = withTimeout(5.seconds) { coordinator.stream(order, command).toList() }
        val timedOut = emitted.single()
        timedOut.status.assert().isEqualTo(CommandResult.Status.FAILED)
        timedOut.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        timedOut.error!!.message.assert().isEqualTo(
            "The wait timed out at accepted(shop), before the command's outcome was known.",
        )
        timedOut.hop.assert().isEqualTo(Hop.accepted("shop"))
        timedOut.version.assert().isNull()
        registry.size.assert().isEqualTo(0)
        log.findByRequestId("t", command.requestId).assert().isNull()

        decideGate.complete(Unit)
        val retry = retried(coordinator, command)
        retry.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        retry.duplicate.assert().isTrue()
        retry.hop.assert().isEqualTo(Hop.committed("shop"))
        retry.version.assert().isEqualTo(1)
    }

    @Test
    fun `a slow execution settled before the deadline answers as usual`(): Unit = runBlocking {
        started()
        val result = background.async { coordinator.execute(order, placeSlowly(WaitPlan.COMMITTED)) }
        deciding.await()
        delay(100)
        decideGate.complete(Unit)
        val answered = withTimeout(5.seconds) { result.await() }
        answered.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        answered.hop.assert().isEqualTo(Hop.committed("shop"))
        answered.version.assert().isEqualTo(1)
    }

    @Test
    fun `a forward that does not settle by the deadline times out at ACCEPTED, and the owner still commits`(): Unit =
        runBlocking {
            started()
            val owner = CompletableDeferred<Unit>()
            val forwarding = WaitCoordinator(
                executor,
                registry,
                runtime,
                signals,
                WaitOptions(origin),
                router = { _, _, _, local ->
                    owner.await()
                    local()
                },
            )
            val command = place(wait = WaitPlan.of(Hop.accepted(), Hop.committed()).within(200.milliseconds))
            val emitted = withTimeout(5.seconds) { forwarding.stream(order, command).toList() }
            emitted.map { it.hop }.assert().containsExactly(Hop.accepted("shop"), Hop.accepted("shop"))
            emitted.first().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            emitted.last().error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)

            owner.complete(Unit)
            val retry = retried(forwarding, command)
            retry.duplicate.assert().isTrue()
            retry.version.assert().isEqualTo(1)
        }

    @Test
    fun `a waiter that leaves does not cancel the execution`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED)
        val waiter = background.async { coordinator.execute(order, command) }
        deciding.await()
        waiter.cancel()
        eventually { registry.size == 0 }
        decideGate.complete(Unit)
        retried(coordinator, command).duplicate.assert().isTrue()
    }

    @Test
    fun `an execution that fails after its wait timed out is only logged`(): Unit = runBlocking {
        val failing = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val broken = WaitCoordinator(
            executor,
            registry,
            SubscriptionDirectory.NONE,
            signals,
            WaitOptions(origin),
            router = { _, _, _, _ ->
                try {
                    failing.await()
                    error("The owner is gone.")
                } finally {
                    failed.complete(Unit)
                }
            },
        )
        val result = broken.execute(order, place(wait = WaitPlan.COMMITTED.within(100.milliseconds)))
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        failing.complete(Unit)
        withTimeout(5.seconds) { failed.await() }
        delay(100) // The coordinator logs the failure once it reaches it, with nobody to answer.
        // While waited for, the failure is the caller's.
        val failingNow = WaitCoordinator(
            executor,
            registry,
            SubscriptionDirectory.NONE,
            signals,
            WaitOptions(origin),
            router = { _, _, _, _ -> error("The owner is gone.") },
        )
        runCatching { failingNow.execute(order, place(wait = WaitPlan.COMMITTED)) }
            .exceptionOrNull()!!.message.assert().isEqualTo("The owner is gone.")
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `the server caps the timeout a caller asks for`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val capped = coordinator(WaitOptions(origin, maxTimeout = 150.milliseconds))
        val result = withTimeout(
            5.seconds
        ) { capped.execute(order, place(wait = WaitPlan.SNAPSHOT.within(60.seconds))) }
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
    }

    @Test
    fun `a subscription that parks the aggregate fails the wait`(): Unit = runBlocking {
        started()
        val result = withTimeout(5.seconds) {
            coordinator.execute(order, place(wait = WaitPlan.of(Hop.handled("order.failing"))))
        }
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
        result.hop.assert().isEqualTo(Hop.handled("order.failing", "shop"))
    }

    @Test
    fun `a subscription whose filter passes over the commit reaches its hop at once`(): Unit = runBlocking {
        started()
        val result = coordinator.execute(order, place(wait = WaitPlan.of(Hop.handled("stock.projector"))))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.handled("stock.projector", "shop"))
    }

    @Test
    fun `a command that changed nothing reaches its later hops at once`(): Unit = runBlocking {
        started()
        val placed = place(wait = WaitPlan.COMMITTED)
        coordinator.execute(order, placed)
        snapshotGate = CompletableDeferred()
        val touch = Command("c-touch", "r-touch", placed.ref, Touch())
        val result = withTimeout(2.seconds) { coordinator.execute(order, touch) }
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.snapshot("shop"))
        result.version.assert().isEqualTo(1)
    }

    @Test
    fun `an invalid chain is rejected without executing the command`(): Unit = runBlocking {
        started()
        val command = place(wait = WaitPlan.of(Hop.snapshot(), Hop.committed()))
        val result = coordinator.execute(order, command)
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        result.hop.assert().isEqualTo(Hop.accepted("shop"))
        log.findByRequestId("t", command.requestId).assert().isNull()
    }

    @Test
    fun `a duplicate request reaches the snapshot when its checkpoint has passed the commit`(): Unit = runBlocking {
        started()
        val first = place()
        val answered = coordinator.execute(order, first)
        answered.hop.assert().isEqualTo(Hop.snapshot("shop"))
        answered.results.assert().isEqualTo(mapOf(ITEMS to 0))
        val retry = first.copy(id = "c-retry")
        val result = coordinator.execute(order, retry)
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.duplicate.assert().isTrue()
        result.hop.assert().isEqualTo(Hop.snapshot("shop"))
        result.function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
        // The results are not persisted (D49): only the first commit answered them.
        result.results.assert().isEmpty()
    }

    @Test
    fun `a duplicate request stops at COMMITTED when the snapshot cannot be told to have handled it`(): Unit =
        runBlocking {
            started()
            snapshotGate = CompletableDeferred()
            val first = place(wait = WaitPlan.COMMITTED)
            coordinator.execute(order, first)
            val result = withTimeout(
                2.seconds
            ) { coordinator.execute(order, first.copy(id = "c-retry", wait = WaitPlan.SNAPSHOT)) }
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.duplicate.assert().isTrue()
            result.hop.assert().isEqualTo(Hop.committed("shop"))
            result.version.assert().isEqualTo(1)
        }

    @Test
    fun `a duplicate request on a parked aggregate stops at COMMITTED`(): Unit = runBlocking {
        started()
        val first = place(wait = WaitPlan.of(Hop.handled("order.failing")))
        coordinator.execute(order, first).status.assert().isEqualTo(CommandResult.Status.FAILED)
        eventually { runtime.hasHandled("order.failing", log.findByRequestId("t", first.requestId)!!).not() }
        val result = coordinator.execute(order, first.copy(id = "c-retry"))
        result.duplicate.assert().isTrue()
        result.hop.assert().isEqualTo(Hop.committed("shop"))
    }

    @Test
    fun `a signal for a wait on another node goes to the remote sender`(): Unit = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val sender = LocalSignalSender(
            "node-1",
            registry
        ) { origin, signal -> sent += "${origin.node}:${signal.waitId}" }
        val result = CommandResult(
            commandId = "c-1",
            requestId = "r-1",
            correlationId = "c-1",
            ref = order.type.ref("t", "o-1"),
            hop = Hop.committed("shop"),
            function = commandFunction(order, place()),
            status = CommandResult.Status.SUCCEEDED,
            signalTime = 1L,
        )
        sender.send(Origin("node-2", "10.0.0.2:9090", "shop"), WaitSignal(waitId = "w-9", result = result))
        sender.send(origin, WaitSignal(waitId = "w-8", result = result))
        sent.assert().containsExactly("node-2:w-9")
    }

    private companion object {
        const val ITEMS = "items"
        const val ORDER = "order"
    }
}
