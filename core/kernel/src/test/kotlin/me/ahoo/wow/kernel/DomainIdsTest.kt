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
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.test.SequentialIds
import me.ahoo.wow.test.aggregateSpec
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
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

    @Test
    fun `a saga specification should give fixed ids, the same on every handling`() =
        sagaSpec(shipmentSagaHandlers()) {
            val order = model.type.ref("tenant", "order-1")
            whenEvent(stateEvent(order, Order("order-1"), OrderPlaced(emptyMap(), "invoice-1"))) {
                expectCommand(CreateShipment("order-1"), to = ShipmentSaga.SHIPMENT.ref("tenant", "shipment-1"))
                expectDeterministic()
                whenRejected(commands.single(), code = "shop.shipment.exists") {
                    expectCommand(CreateShipment("id-1"), to = ShipmentSaga.SHIPMENT.ref("tenant", "shipment-1"))
                    expectDeterministic()
                }
            }
        }

    @Test
    fun `a saga's context should take its ids from the generators it is given, UUIDv7 by default`() {
        val order = model.type.ref("tenant", "order-1")
        val event = stateEvent(order, Order("order-1"), OrderPlaced(emptyMap(), "invoice-1"))
        val execution = ExecutionId("ShipmentSaga.onPlaced", order, 1)
        val fixed = IdGenerators(GlobalIdGenerator { "g" }, AggregateIdGenerator { "a-${it.name}" })
        val context = contextOf(FunctionKind.SAGA, execution.subscription, event, fixed) as SagaContext
        context.ids.assert().isSameAs(fixed)
        ShipmentSaga().onPlaced(event, context)!!.ref.id.assert().isEqualTo("a-shipment")
        UUID.fromString(ExecutionSagaContext(execution, event).ids.next()).version().assert().isEqualTo(7)
        val handler = shipmentSagaHandlers().single().rejectionHandlerOf(CreateShipment::class.java)!!
        val rejected = CommandRejected<CreateShipment>(
            commandId = "c-1",
            commandType = "shop.shipment.CreateShipment",
            ref = ShipmentSaga.SHIPMENT.ref("tenant", "s-1"),
            error = ErrorInfo("shop.shipment.exists", "exists"),
            causation = Causation("c-0", "c-1"),
            executionId = execution,
        )
        rejectionContextOf(handler, rejected, fixed).ids.assert().isSameAs(fixed)
        rejectionContextOf(handler, rejected).ids.assert().isSameAs(IdGenerators.DEFAULT)
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
