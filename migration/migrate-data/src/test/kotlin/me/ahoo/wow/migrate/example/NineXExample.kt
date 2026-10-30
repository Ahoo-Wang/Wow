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

package me.ahoo.wow.migrate.example

import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.migrate.MigrationMap
import me.ahoo.wow.migrate.MigrationPlan
import me.ahoo.wow.migrate.NineXSnapshot
import me.ahoo.wow.migrate.NineXStream
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * The 9.x example's data and its V10 port, the fixtures of the import tests (roadmap 8b.1: the 9.x example's data
 * imported).
 *
 * The streams under `ninex-example/` are what the 9.x example (`example-domain`, `example-api`) wrote for an order
 * (create, change address, pay, ship) and a cart (add two items, change a quantity, remove one), serialized by the 9.x
 * `EventStreamJsonSerializer`; `order-state.json` and `cart-state.json` are the 9.x states after them, as the 9.x
 * serializer writes a snapshot's state. The V10 port below follows the Skill reference: context `example`,
 * `OrderCreated` without its identity field at revision 2 with an upgrade from the 9.x shape (revision 1).
 */
internal object NineXExample {
    const val CONTEXT = "example"

    /**
     * The event the V10 port retired (decision D219): the 9.x `order_archived` the import tests add, as 9.x's
     * `DroppedEvent` would have handled it after the archive feature was removed.
     */
    const val ORDER_ARCHIVED = "example.order.OrderArchived"

    val ORDER_STREAMS = (1..4).map { "order-stream-v$it.json" }
    val CART_STREAMS = (1..4).map { "cart-stream-v$it.json" }

    fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("ninex-example/$name")) { "No fixture $name." }.readText()

    fun stream(name: String): NineXStream = NineXStream.parse(resource(name))

    /** Every stream of the example. */
    fun streams(): List<NineXStream> = (ORDER_STREAMS + CART_STREAMS).map(::stream)

    /**
     * The 9.x snapshots of the example after its streams, as the 9.x `SnapshotSerializer` lays them out: the captured
     * 9.x states (`order-state.json`, `cart-state.json`) with the metadata of the streams they fold.
     */
    val SNAPSHOTS = listOf("order-snapshot.json", "cart-snapshot.json")

    fun snapshot(name: String): NineXSnapshot = NineXSnapshot.parse(resource(name))

    /** Every snapshot of the example. */
    fun snapshots(): List<NineXSnapshot> = SNAPSHOTS.map(::snapshot)

    val MAP_TEXT: String get() = resource("migration-map.json")

    val map: MigrationMap get() = MigrationMap.read(MAP_TEXT)

    val manifest: Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = CONTEXT,
                aggregates = listOf(
                    aggregate(
                        "order",
                        listOf("CreateOrder", "ChangeAddress", "PayOrder", "ShipOrder", "ReceiptOrder"),
                        listOf(
                            EventManifest("OrderCreated", revision = 2, schema = ref("OrderCreated")),
                            event("AddressChanged"),
                            event("OrderPaid"),
                            event("OrderShipped"),
                            event("OrderReceived"),
                        ),
                    ),
                    aggregate(
                        "cart",
                        listOf("AddCartItem", "ChangeQuantity", "RemoveCartItem"),
                        listOf(event("CartItemAdded"), event("CartQuantityChanged"), event("CartItemRemoved")),
                    ),
                ),
            ),
        ),
    )

    /** The Manifest as the JSON a service's build writes. */
    val MANIFEST_TEXT: String get() = WowJson.mapper.writeValueAsString(ManifestTree.write(manifest))

    /**
     * The Manifest of a port that keeps the 9.x `archive_order` command as `ArchiveOrder`, whose 9.x event
     * `order_archived` it retired ([ORDER_ARCHIVED]) unless [retired] is false.
     */
    fun archivingManifest(retired: Boolean = true): Manifest = Manifest(
        contexts = manifest.contexts.map { context ->
            ContextManifest(
                name = context.name,
                aggregates = context.aggregates.map { aggregate ->
                    if (aggregate.name != "order") {
                        aggregate
                    } else {
                        AggregateManifest(
                            name = aggregate.name,
                            state = aggregate.state,
                            commands = aggregate.commands + CommandManifest("ArchiveOrder", schema = ref("ArchiveOrder")),
                            events = aggregate.events,
                            retired = if (retired) listOf("OrderArchived") else emptyList(),
                        )
                    }
                },
            )
        },
    )

    /** [Manifest] as the JSON a service's build writes. */
    fun manifestText(manifest: Manifest): String = WowJson.mapper.writeValueAsString(ManifestTree.write(manifest))

    /** The example's map with `archive_order` and its `order_archived` mapped as [event] gives it (JSON). */
    fun archivingMapText(event: String): String = MAP_TEXT
        .replace(
            """{ "from": "receipt_order", "to": "ReceiptOrder" }""",
            """{ "from": "receipt_order", "to": "ReceiptOrder" }, { "from": "archive_order", "to": "ArchiveOrder" }""",
        )
        .replace(
            """{ "from": "order_received", "to": "OrderReceived" }""",
            """{ "from": "order_received", "to": "OrderReceived" }, { "from": "order_archived", $event }""",
        )

    /** The JSON of a 9.x stream following [text]'s: the order archived, at the next version, by `order_archived` only. */
    fun archived(text: String): String {
        val stream = NineXStream.parse(text)
        return text
            .replace("\"${stream.id}\"", "\"archived-stream\"")
            .replace("\"${stream.commandId}\"", "\"archived-command\"")
            .replace("\"version\":${stream.version}", "\"version\":${stream.version + 1}")
            .replace("\"createTime\":${stream.createTime}", "\"createTime\":${stream.createTime + 1}")
            .replace("ship_order", "archive_order")
            .replace("order_shipped", "order_archived")
            .replace("OrderShipped", "OrderArchived")
    }

    val plan: MigrationPlan get() = MigrationPlan(map, manifest)

    private fun aggregate(name: String, commands: List<String>, events: List<EventManifest>) = AggregateManifest(
        name = name,
        state = StateManifest(ref("${name}State")),
        commands = commands.map { CommandManifest(it, schema = ref(it)) } +
            listOf(Lifecycle.DELETE_AGGREGATE, Lifecycle.TRANSFER_OWNER).map { SystemCommands.lifecycle(CONTEXT, name, it) },
        events = events,
    )

    private fun event(name: String) = EventManifest(name, schema = ref(name))

    private fun ref(name: String) = Manifest.schemaRef("example.$name")

    val orderModel: AggregateModel<OrderState> = orderModel(payable = false)

    /** The order's V10 model; [payable] adds the decision of `PayOrder`, for a service that runs it. */
    fun orderModel(payable: Boolean): AggregateModel<OrderState> = aggregate<OrderState>(CONTEXT, "order") {
        initial { OrderState(it.id) }
        evolve<OrderCreated>(revision = 2) { created ->
            address = created.address
            items = created.items
            totalAmount = created.items.map { it.totalPrice }.fold(BigDecimal.ZERO, BigDecimal::add)
            status = OrderStatus.CREATED
        }
        upgrade<OrderCreated>(1) { node -> (node as ObjectNode).apply { remove("orderId") } }
        evolve<AddressChanged> { address = it.shippingAddress }
        evolve<OrderPaid> { paid ->
            paidAmount += paid.amount
            if (paid.paid) {
                status = OrderStatus.PAID
            }
        }
        evolve<OrderShipped> { status = OrderStatus.SHIPPED }
        evolve<OrderReceived> { status = OrderStatus.RECEIVED }
        retired(ORDER_ARCHIVED)
        if (payable) {
            decide<PayOrder> { _, body -> Decision.events(OrderPaid(body.amount, paid = true)) }
        }
    }

    val cartModel: AggregateModel<CartState> = aggregate<CartState>(CONTEXT, "cart") {
        initial { CartState(it.id) }
        evolve<CartItemAdded> { items = items + it.added }
        evolve<CartQuantityChanged> { changed ->
            items = items.map { if (it.productId == changed.changed.productId) changed.changed else it }
        }
        evolve<CartItemRemoved> { removed -> items = items.filterNot { it.productId in removed.productIds } }
    }

    /**
     * Whether [left] and [right] are the same JSON value, numbers by value: the 9.x state wrote `25.00` where the
     * V10 one may write `25.0`, the same amount.
     */
    fun sameJson(left: JsonNode, right: JsonNode): Boolean = when {
        left.isNumber && right.isNumber -> left.decimalValue().compareTo(right.decimalValue()) == 0
        left.isObject && right.isObject -> left.size() == right.size() &&
            left.properties().all { (name, value) -> right.get(name)?.let { sameJson(value, it) } == true }

        left.isArray && right.isArray -> left.size() == right.size() &&
            (0 until left.size()).all { sameJson(left.get(it), right.get(it)) }

        else -> left == right
    }
}

internal enum class OrderStatus { CREATED, PAID, SHIPPED, RECEIVED }

internal data class ShippingAddress(
    val country: String,
    val province: String,
    val city: String,
    val district: String,
    val detail: String,
)

internal data class OrderItem(val id: String, val productId: String, val price: BigDecimal, val quantity: Int) {
    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}

/** Revision 2: the 9.x `orderId` is the stream's ref now. */
internal data class OrderCreated(val items: List<OrderItem>, val address: ShippingAddress, val fromCart: Boolean)

internal data class AddressChanged(val shippingAddress: ShippingAddress)

internal data class OrderPaid(val amount: BigDecimal, val paid: Boolean)

internal data class PayOrder(val amount: BigDecimal)

internal class OrderShipped

internal class OrderReceived

internal class OrderState(val id: String) {
    var items: List<OrderItem> = emptyList()
    var address: ShippingAddress? = null
    var totalAmount: BigDecimal = BigDecimal.ZERO
    var paidAmount: BigDecimal = BigDecimal.ZERO
    var status: OrderStatus = OrderStatus.CREATED
    val payable: BigDecimal
        get() = totalAmount.minus(paidAmount)
}

internal data class CartItem(val productId: String, val quantity: Int)

internal data class CartItemAdded(val added: CartItem)

internal data class CartQuantityChanged(val changed: CartItem)

internal data class CartItemRemoved(val productIds: Set<String>)

internal class CartState(val id: String) {
    var items: List<CartItem> = emptyList()
    val size: Int
        get() = items.size
}
