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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.HandledEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One service's coordinator over an order and a stock, a snapshot subscription, a saga and the gates that hold them:
 * what the wait tests share, each test with its own.
 */
abstract class WaitCoordinatorFixtures {
    data class Place(val items: Int = 1, val reject: Boolean = false)
    data class Touch(val note: String = "")
    data class Cancel(val withEvents: Boolean)
    data class CancelAttempted(val order: String)
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
    protected val deciding = CompletableDeferred<Unit>()

    /** A [PlaceSlowly] is decided once it completes. */
    protected val decideGate = CompletableDeferred<Unit>()

    protected val order = aggregate<Order>("shop", "order") {
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
        decide<Cancel>(Creation.NONE) { state, body ->
            val events = if (body.withEvents) listOf(CancelAttempted(state.id)) else emptyList()
            Decision.reject("shop.order.locked", "Locked.", events = events)
        }
        decideSuspending<PlaceSlowly>(Creation.ALLOWED) { _, body ->
            deciding.complete(Unit)
            decideGate.await()
            Decision.events(Placed(body.items))
        }
        evolve<Placed> { items = it.items }
        evolve<CancelAttempted> { }
    }
    protected val stock = aggregate<Stock>("shop", "stock") {
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

    protected val background = CoroutineScope(Dispatchers.Default + SupervisorJob())
    protected val log = InMemoryEventLog(partitions = 2)
    protected val ledger = InMemoryFailureLedger()
    protected val registry = WaitRegistry()
    protected val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    protected val signals = LocalSignalSender(origin.node, registry)
    protected val sequence = AtomicInteger()

    /** While not null, the snapshot subscription holds every entry until it completes. */
    @Volatile
    protected var snapshotGate: CompletableDeferred<Unit>? = null

    /** While not null, the saga executes every derived command but its first only once it completes. */
    @Volatile
    protected var sagaGate: CompletableDeferred<Unit>? = null
    protected val snapshotted = CopyOnWriteArrayList<String>()

    /** The entries the snapshot subscription has reached [snapshotGate] with, held or since let through. */
    protected val snapshotHeld = AtomicInteger()

    protected val snapshot = Subscription("wow.snapshot", SubscriptionFilter("shop"), kind = FunctionKind.SYSTEM) {
        snapshotGate?.let { gate ->
            snapshotHeld.incrementAndGet()
            gate.await()
        }
        snapshotted += "${it.ref.id}@${it.version}"
    }

    /** Reserves stock for each item of a placed order; the commands carry the entry's wait context on. */
    protected val saga = Subscription.saga("order.saga", SubscriptionFilter("shop", setOf("order"))) { entry ->
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
        HandledEntry(commands.map { it.id })
    }

    protected val failing = Subscription("order.failing", SubscriptionFilter("shop", setOf("order"))) {
        error("${it.ref} cannot be projected")
    }

    protected val stockOnly = Subscription("stock.projector", SubscriptionFilter("shop", setOf("stock"))) { }

    protected val itemsByOrder = HashMap<String, Int>()

    /** The item whose reservation the stock rejects, when not null. */
    @Volatile
    protected var rejectedItem: Int? = null

    protected fun itemsOf(orderId: String): Int = synchronized(itemsByOrder) { itemsByOrder[orderId] ?: 0 }

    protected val runtime = SubscriptionRuntime(
        "shop",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(snapshot, saga, failing, stockOnly),
        testOptions(),
        signals = signals,
    )
    protected val executor = Executor(Kernel(log))
    protected val coordinator = coordinator(WaitOptions())
    protected val held = HeldDeadlines()
    protected val holding =
        WaitCoordinator(executor, registry, runtime, signals, origin, WaitOptions(), deadlines = held)

    protected fun coordinator(
        options: WaitOptions,
        directory: SubscriptionDirectory = runtime,
    ) = WaitCoordinator(executor, registry, directory, signals, origin, options)

    protected fun place(
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

    protected suspend fun started() {
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

    protected companion object {
        const val ITEMS = "items"
        const val ORDER = "order"
    }
}
