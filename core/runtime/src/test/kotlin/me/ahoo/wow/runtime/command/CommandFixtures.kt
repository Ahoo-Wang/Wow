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

package me.ahoo.wow.runtime.command

import kotlinx.coroutines.CompletableDeferred
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.spi.wait.WaitContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import me.ahoo.wow.manifest.Creation as ManifestCreation

const val SALES = "sales"
const val STOCK = "stock"
const val SALES_SERVICE = "sales-service"
const val STOCK_SERVICE = "stock-service"
const val TENANT = "t"
const val PREFIX = "test."
const val INSUFFICIENT = "stock.insufficient"
const val REFUSED = "sales.order.refused"

/** How many items one stock item holds: a reservation of more is rejected. */
const val AVAILABLE = 5

/** A reservation of this quantity fails with a technical error while [StockItems.broken] is set. */
const val BREAKING = 13

val ORDER: AggregateType = AggregateType(SALES, "order")
val ITEM: AggregateType = AggregateType(STOCK, "item")

data class PlaceOrder(val quantity: Int)
data class OrderPlaced(val quantity: Int)
class CancelOrder
class OrderCancelled
class ConfirmOrder
class OrderConfirmed

class OrderState {
    var quantity: Int = 0
    var cancelled: Boolean = false
}

data class Reserve(val quantity: Int)
data class Reserved(val quantity: Int)

class ItemState {
    var reserved: Int = 0
}

/**
 * The order of the sales context; every cancellation it decides is recorded in [decided], and every confirmation in
 * [confirmed], by id; a confirmation is rejected unless [confirmable].
 */
fun orderModel(
    decided: MutableList<String> = CopyOnWriteArrayList(),
    confirmed: MutableList<String> = CopyOnWriteArrayList(),
    confirmable: () -> Boolean = { true },
): AggregateModel<OrderState> =
    aggregate<OrderState>(SALES, "order") {
        initial { OrderState() }
        decide<PlaceOrder>(Creation.REQUIRED) { _, body -> Decision.events(OrderPlaced(body.quantity)) }
        decide<CancelOrder> { _, _ ->
            decided += command.id
            Decision.events(OrderCancelled())
        }
        decide<ConfirmOrder> { _, _ ->
            if (!confirmable()) {
                return@decide Decision.reject(REFUSED, "The order cannot be confirmed.")
            }
            confirmed += command.id
            Decision.events(OrderConfirmed())
        }
        evolve<OrderPlaced> { quantity = it.quantity }
        evolve<OrderCancelled> { cancelled = true }
        evolve<OrderConfirmed> { }
    }

/**
 * The stock items of the stock context: a reservation of more than [AVAILABLE] is rejected, one of [BREAKING] fails
 * while [broken], and one waits for [gates] of its item, if any. Every reservation decided is recorded in [decided].
 */
class StockItems {
    val decided: MutableList<String> = CopyOnWriteArrayList()
    val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    var broken: Boolean = true

    val model: AggregateModel<ItemState> = aggregate<ItemState>(STOCK, "item") {
        initial { ItemState() }
        decideSuspending<Reserve>(Creation.ALLOWED) { _, body ->
            gates[command.ref.id]?.await()
            // A failure of the handling, not a refusal (a check would refuse the command, decision D311).
            if (broken && body.quantity == BREAKING) {
                throw UnsupportedOperationException(
                    "The stock store is unreachable."
                )
            }
            decided += command.id
            if (body.quantity > AVAILABLE) {
                Decision.reject(INSUFFICIENT, "Only $AVAILABLE left.")
            } else {
                Decision.events(Reserved(body.quantity))
            }
        }
        evolve<Reserved> { reserved += it.quantity }
    }
}

/**
 * The order saga of the sales context: each order placed reserves its quantity of the stock item of the same id, and
 * a reservation the stock context rejects cancels the order; each rejection it handles is recorded in [rejections].
 */
fun orderSaga(
    rejections: MutableList<CommandReceipt.Rejected<*>> = CopyOnWriteArrayList()
): ProcessorBinding<OrderState> {
    val onReserveRejected = RejectionBinding.of(
        "OrderSaga",
        "onReserveRejected",
        Reserve::class.java,
        derives = listOf(CancelOrder::class.java),
    ) { rejected, _ ->
        rejections += rejected
        listOf(CommandRequest.to(rejected.source, CancelOrder()))
    }
    return ProcessorBinding.saga(
        "OrderSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = listOf(onReserveRejected),
        derives = listOf(Reserve::class.java),
    ) { event, _ -> reservationsOf(event) }
}

/** The order saga's handler: each placed order reserves its quantity of its item. */
private fun reservationsOf(event: StateEvent<OrderState>): List<CommandRequest<*>> =
    event.events.filterIsInstance<OrderPlaced>().map {
        CommandRequest.to(ITEM.ref(event.ref.tenantId, event.ref.id), Reserve(it.quantity))
    }

/**
 * The order saga with an `@OnCommitted` handler of its reservations too (decision D192): each reservation the stock
 * context commits confirms the order; each receipt it handles is recorded in [commits].
 */
fun committingOrderSaga(
    commits: MutableList<CommandReceipt.Committed<*>> = CopyOnWriteArrayList(),
    rejections: MutableList<CommandReceipt.Rejected<*>> = CopyOnWriteArrayList(),
): ProcessorBinding<OrderState> {
    val onReserved = CommittedBinding.of(
        "OrderSaga",
        "onReserved",
        Reserve::class.java,
        derives = listOf(ConfirmOrder::class.java),
    ) { committed, _ ->
        commits += committed
        listOf(CommandRequest.to(committed.source, ConfirmOrder()))
    }
    val saga = orderSaga(rejections)
    return ProcessorBinding.saga(
        "OrderSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = saga.rejections,
        commits = listOf(onReserved),
        derives = listOf(Reserve::class.java),
    ) { event, _ -> reservationsOf(event) }
}

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

/** The sales service's Manifest: its orders. */
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

/** The stock service's Manifest: its items, whose reservations the sales saga sends. */
fun stockManifest(): Manifest = Manifest(
    service = ServiceInfo(STOCK_SERVICE),
    contexts = listOf(
        ContextManifest(
            STOCK,
            aggregates = listOf(
                aggregateManifest(
                    STOCK,
                    "item",
                    listOf(Reserve::class.java to ManifestCreation.ALLOWED),
                    listOf(Reserved::class.java)
                ),
            ),
        ),
    ),
)

/**
 * [manifest] with its command [from] named [to], the same schema: the contract of a newer version of its service, as
 * a peer that upgraded first has it.
 */
fun renamed(manifest: Manifest, from: String, to: String): Manifest = manifest.copyWith(
    contexts = manifest.contexts.map { context ->
        context.copyWith(
            aggregates = context.aggregates.map { aggregate ->
                aggregate.copyWith(
                    commands = aggregate.commands.map { if (it.name == from) it.copyWith(name = to) else it },
                )
            },
        )
    },
)

/** [manifest]'s contexts as the peer contract their api jar carries on the class path (decision D234). */
fun peer(manifest: Manifest): ManifestFragment = ManifestFragment(
    "${manifest.contexts.first().name}-api.jar!/${PeerContracts.RESOURCE}",
    Manifest(manifest.format, null, manifest.contexts, manifest.errors, manifest.defs),
)

/** A receipt handler of the given [rejected] and [committed] answers; by default it takes none. */
fun receiptHandler(
    rejected: suspend (CommandReceipt.Rejected<*>, Class<*>) -> List<String>? = { _, _ -> null },
    committed: suspend (CommandReceipt.Committed<*>, Class<*>) -> List<String>? = { _, _ -> null },
): ReceiptHandler {
    val onRejected = rejected
    val onCommitted = committed
    return object : ReceiptHandler {
        override suspend fun rejected(rejected: CommandReceipt.Rejected<*>, command: Class<*>): List<String>? =
            onRejected(rejected, command)

        override suspend fun committed(
            committed: CommandReceipt.Committed<*>,
            command: Class<*>,
            wait: WaitContext?,
        ): List<String>? = onCommitted(committed, command)
    }
}
