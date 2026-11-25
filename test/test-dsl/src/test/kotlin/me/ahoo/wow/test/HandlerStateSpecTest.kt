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

package me.ahoo.wow.test

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A processor spec's handler reads what a node's does (#6964): the state decoded from its JSON, a copy of its own, in
 * the context it is bound in.
 */
class HandlerStateSpecTest {
    data class Placed(val total: Int)
    data class Paid(val amount: Int)
    data class CreateShipment(val orderId: String)

    class Order(val id: String) {
        var total = 0
        var paid = 0

        /** What is left to ship: kept in memory only, so a state decoded from its JSON has none. */
        @get:JsonIgnore
        var unshipped = 0
    }

    private val orderModel: AggregateModel<Order> = aggregate("shop", "order") {
        initial { Order(it.id) }
        evolve<Placed> {
            total = it.total
            unshipped = it.total
        }
        evolve<Paid> { paid += it.amount }
    }

    private val shipment = AggregateType("logistics", "shipment")

    @Test
    fun `a handler should read the state decoded from its JSON, without what the JSON does not keep`() {
        // As a node hands each handler a state decoded from the view's JSON (SubscriptionBindings.stateEventOf).
        // Moved from review/r1-testkit (DslReviewR1Test), where the spec handed the state object it replayed.
        val shipping = ProcessorBinding.saga(
            "ShippingSaga",
            "onOrder",
            Order::class.java,
            derives = listOf(CreateShipment::class.java),
        ) { event: StateEvent<Order>, _ ->
            val target = shipment.ref(event.ref.tenantId, "shipment-1")
            listOf(CommandRequest.to(target, CreateShipment(event.ref.id))).filter { event.state.unshipped > 0 }
        }
        sagaSpec(listOf(shipping)) {
            val order = givenAggregate(orderModel, "order-1", Placed(10))
            // The state as a node's view decodes it, with the kernel's codec.
            val codec = EventCodec()
            codec.decodeState(orderModel, codec.stateText(order.state)).unshipped.assert().isEqualTo(0)
            whenEvent(Paid(1)) { expectNoCommand() }
        }
    }

    data class Reserve(val orderId: String, val quantity: Int)

    private val stock = AggregateType("inventory", "stock")

    @Test
    fun `a handler of another context's stream should derive its ids in the context it is bound in`() {
        // As the runtime names a handler's execution by the context it binds the handler in, which every derived id
        // encodes (SubscriptionBindings.executionOf), and the compiler gives every binding it generates. PaymentSaga is
        // bound in a payment context, as the eShop's OrderPaymentSaga is. Moved from review/r1-testkit (DslReviewR1Test),
        // where the spec named the execution by the commit's context.
        fun payment(context: String?) = ProcessorBinding.saga(
            "PaymentSaga",
            "onOrder",
            Order::class.java,
            aggregates = listOf("shop.order"),
            derives = listOf(Reserve::class.java),
            context = context,
        ) { event: StateEvent<Order>, _ ->
            listOf(CommandRequest.to(stock.ref(event.ref.tenantId, "mug"), Reserve(event.ref.id, 1)))
        }
        val target = stock.ref(DEFAULT_TENANT, "mug")
        fun derivedIn(context: String, saga: ProcessorBinding<*>, order: GivenAggregate<*>) =
            ExecutionSagaContext.derivedId(ExecutionId(context, saga.name, order.ref, 2), target, Reserve::class.java)
        val bound = payment("payment")
        sagaSpec(listOf(bound)) {
            val order = givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) {
                commands.single().id.assert().isEqualTo(derivedIn("payment", bound, order))
                commands.single().id.assert().isNotEqualTo(derivedIn("shop", bound, order))
            }
        }
        // A binding written by hand that names no context runs in the context of the commit.
        val unbound = payment(null)
        sagaSpec(listOf(unbound)) {
            val order = givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1)) { commands.single().id.assert().isEqualTo(derivedIn("shop", unbound, order)) }
        }
    }

    @Test
    fun `a commit without a state should be handed over as it is`() {
        val seen = CopyOnWriteArrayList<Any?>()
        val any = ProcessorBinding.project<Any>("AnyView", "onAny", null) { event, _ -> seen += event.state }
        projectionSpec(listOf(any)) {
            whenEvent(stateEvent(orderModel.type.ref(DEFAULT_TENANT, "order-1"), null, Paid(1)))
        }
        seen.assert().containsExactly(null)
    }

    @Test
    fun `each handler should read a state of its own, as each decodes it`() {
        // A node decodes the state for each handler, so what one handler changes on it another never reads.
        val seen = CopyOnWriteArrayList<Int>()
        val first = ProcessorBinding.project("OrderView", "first", Order::class.java) { event: StateEvent<Order>, _ ->
            event.state.paid = -1
        }
        val second = ProcessorBinding.project("OrderView", "second", Order::class.java) { event: StateEvent<Order>, _ ->
            seen += event.state.paid
        }
        projectionSpec(listOf(first, second)) {
            givenAggregate(orderModel, "order-1", Placed(10))
            whenEvent(Paid(1))
        }
        seen.assert().containsExactly(1)
    }
}
