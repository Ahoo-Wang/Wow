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

package me.ahoo.wow.kernel

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.test.SequentialIds
import me.ahoo.wow.kernel.test.aggregateSpec
import me.ahoo.wow.kernel.test.sagaSpec
import me.ahoo.wow.kernel.test.stateEvent
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import org.junit.jupiter.api.Test
import java.util.UUID

/** The ids domain code takes from its decide scope and its saga context (decision D171). */
class DomainIdsTest {
    data class PlaceOrder(val lines: List<String>)
    data class OrderPlaced(val lines: Map<String, String>, val invoice: String)
    data class CreateShipment(val orderId: String)

    class Order(val id: String) {
        val lines = LinkedHashMap<String, String>()
        var invoice: String? = null
    }

    private val model = aggregate<Order>("shop", "order") {
        initial { Order(it.id) }
        decide<PlaceOrder>(Creation.ALLOWED) { _, body ->
            Decision.events(OrderPlaced(body.lines.associateBy { ids.next() }, ids.next(INVOICE)))
        }
        evolve<OrderPlaced> {
            lines += it.lines
            invoice = it.invoice
        }
    }

    @Test
    fun `a decide function should take its ids from the kernel's generators`() {
        var global = 0
        val ids = IdGenerators(
            GlobalIdGenerator { "g-${++global}" },
            AggregateIdGenerator { type -> "${type.context}-${type.name}" },
        )
        val kernel = Kernel(InMemoryEventLog(), ids = ids)
        val ref = model.type.ref("tenant", "order-1")
        val outcome = runBlocking {
            kernel.execute(model, Command("c-1", "r-1", ref, PlaceOrder(listOf("apple", "pear"))))
        } as CommandOutcome.Committed
        val state = outcome.state as Order
        state.lines.assert().containsExactlyEntriesOf(mapOf("g-1" to "apple", "g-2" to "pear"))
        state.invoice.assert().isEqualTo("billing-invoice")
    }

    @Test
    fun `a kernel without generators should give UUIDv7 ids`() {
        val ref = model.type.ref("tenant", "order-1")
        val outcome = runBlocking {
            Kernel(InMemoryEventLog()).execute(model, Command("c-1", "r-1", ref, PlaceOrder(listOf("apple"))))
        } as CommandOutcome.Committed
        val state = outcome.state as Order
        UUID.fromString(state.lines.keys.single()).version().assert().isEqualTo(7)
        UUID.fromString(state.invoice).version().assert().isEqualTo(7)
    }

    @Test
    fun `an aggregate specification should give fixed ids, counted across it and kept by a fork`() =
        aggregateSpec(model) {
            whenCommand(PlaceOrder(listOf("apple", "pear"))) {
                expectEvents(OrderPlaced(mapOf("id-1" to "apple", "id-2" to "pear"), "invoice-1"))
            }
            ref("placed")
            whenCommand(PlaceOrder(listOf("plum"))) {
                expectEvents(OrderPlaced(mapOf("id-3" to "plum"), "invoice-2"))
            }
            fork(from = "placed") {
                whenCommand(PlaceOrder(listOf("fig"))) {
                    expectEvents(OrderPlaced(mapOf("id-3" to "fig"), "invoice-2"))
                }
            }
        }

    /** A saga that opens a shipment, an aggregate of a new id, for each order placed. */
    class ShipmentSaga {
        fun onPlaced(event: StateEvent<Order>, context: SagaContext): Command<CreateShipment>? {
            if (event.events.none { it is OrderPlaced }) return null
            val shipment = SHIPMENT.ref(event.ref.tenantId, context.ids.next(SHIPMENT))
            return context.command(shipment, CreateShipment(event.ref.id))
        }

        fun onCreateRejected(rejected: CommandRejected<CreateShipment>, context: SagaContext): Command<CreateShipment> =
            context.command(
                SHIPMENT.ref(rejected.ref.tenantId, context.ids.next(SHIPMENT)),
                CreateShipment(context.ids.next()),
            )

        companion object {
            val SHIPMENT = AggregateType("shop", "shipment")
        }
    }

    private fun shipmentSagaHandlers(): List<ProcessorBinding<*>> {
        val saga = ShipmentSaga()
        val rejections = listOf<RejectionBinding<*>>(
            RejectionBinding.of("ShipmentSaga", "onCreateRejected", CreateShipment::class.java) { rejected, context ->
                derivedOf(saga.onCreateRejected(rejected, context))
            },
        )
        return listOf(
            ProcessorBinding.saga("ShipmentSaga", "onPlaced", Order::class.java, rejections = rejections) { e, c ->
                derivedOf(saga.onPlaced(e, c))
            },
        )
    }

    private val order = model.type.ref("tenant", "order-1")
    private val placed = OrderPlaced(emptyMap(), "invoice-1")
    private val onPlaced = ExecutionId("ShipmentSaga.onPlaced", order, 1)
    private val firstShipment = derivedShipment(onPlaced.toString(), 0)

    private fun derivedShipment(seed: String, index: Int) =
        ShipmentSaga.SHIPMENT.ref("tenant", HandlingIds.idOf(seed, ShipmentSaga.SHIPMENT, index))

    private fun rejectionOf(command: Command<*>) = CommandRejected<CreateShipment>(
        commandId = command.id,
        commandType = "shop.shipment.CreateShipment",
        ref = command.ref,
        error = ErrorInfo("shop.shipment.exists", "exists"),
        causation = Causation("command-1", command.id),
        executionId = onPlaced,
    )

    @Test
    fun `a saga specification should give the ids of the handling, the same on every handling`() =
        sagaSpec(shipmentSagaHandlers()) {
            whenEvent(stateEvent(order, Order("order-1"), placed)) {
                expectCommand(CreateShipment("order-1"), to = firstShipment)
                expectDeterministic()
                val rejected = commands.single()
                whenRejected(rejected, code = "shop.shipment.exists") {
                    val seed = "${ExecutionId("ShipmentSaga.onCreateRejected", order, 1)}!${rejected.id}"
                    expectCommand(
                        CreateShipment(HandlingIds.idOf(seed, null, 1)),
                        to = derivedShipment(seed, 0),
                    )
                    expectDeterministic()
                }
            }
        }

    @Test
    fun `handling the same commit twice should derive the same ids, whatever the node's generators`() {
        val event = stateEvent(order, Order("order-1"), placed)
        val saga = ShipmentSaga()
        val first = saga.onPlaced(event, contextOf(FunctionKind.SAGA, onPlaced.subscription, event) as SagaContext)!!
        val again = saga.onPlaced(event, ExecutionSagaContext(onPlaced, event))!!
        first.assert().isEqualTo(again)
        first.ref.assert().isEqualTo(firstShipment)
        UUID.fromString(first.ref.id).version().assert().isEqualTo(5)
        val later = stateEvent(order, Order("order-1"), placed, version = 2)
        saga.onPlaced(later, ExecutionSagaContext(onPlaced.copy(version = 2), later))!!.ref
            .assert().isNotEqualTo(first.ref)
        val handler = shipmentSagaHandlers().single().rejectionHandlerOf(CreateShipment::class.java)!!
        val rejected = rejectionOf(first)
        val compensation = saga.onCreateRejected(rejected, rejectionContextOf(handler, rejected))
        compensation.assert().isEqualTo(saga.onCreateRejected(rejected, rejectionContextOf(handler, rejected)))
        compensation.ref.assert().isNotEqualTo(first.ref)
        val other = rejectionOf(first.copy(id = "other"))
        saga.onCreateRejected(other, rejectionContextOf(handler, other)).ref.assert().isNotEqualTo(compensation.ref)
    }

    @Test
    fun `a handling's ids should count its calls, global and per type, as version 5 UUIDs`() {
        val ids = HandlingIds("seed")
        val global = ids.next()
        val shipment = ids.next(ShipmentSaga.SHIPMENT)
        global.assert().isEqualTo(HandlingIds.idOf("seed", null, 0))
        shipment.assert().isEqualTo(HandlingIds.idOf("seed", ShipmentSaga.SHIPMENT, 1))
        HandlingIds.idOf("seed", ShipmentSaga.SHIPMENT, 0).assert().isNotEqualTo(HandlingIds.idOf("seed", null, 0))
        HandlingIds.idOf("other", null, 0).assert().isNotEqualTo(global)
        UUID.fromString(global).let {
            it.version().assert().isEqualTo(5)
            it.variant().assert().isEqualTo(2)
        }
        assertThrownBy<IllegalArgumentException> { HandlingIds.idOf("seed", null, -1) }
        assertThrownBy<IllegalArgumentException> { HandlingIds(" ") }
    }

    @Test
    fun `fixed ids should count from 1, the global ones and each aggregate type's apart`() {
        val ids = SequentialIds()
        ids.next().assert().isEqualTo("id-1")
        ids.next(ShipmentSaga.SHIPMENT).assert().isEqualTo("shipment-1")
        ids.next(model.type).assert().isEqualTo("order-1")
        ids.next(ShipmentSaga.SHIPMENT).assert().isEqualTo("shipment-2")
        ids.next().assert().isEqualTo("id-2")
    }

    private companion object {
        val INVOICE = AggregateType("billing", "invoice")
    }
}
