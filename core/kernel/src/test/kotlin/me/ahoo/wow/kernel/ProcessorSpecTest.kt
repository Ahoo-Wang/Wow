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

import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.test.DEFAULT_START
import me.ahoo.wow.kernel.test.projectionSpec
import me.ahoo.wow.kernel.test.publishedEvent
import me.ahoo.wow.kernel.test.reactorSpec
import me.ahoo.wow.kernel.test.sagaSpec
import me.ahoo.wow.kernel.test.stateEvent
import me.ahoo.wow.spi.state.StateEventPayload
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** The processor test DSL (P.5, decision D184): sagaSpec, projectionSpec and reactorSpec. */
class ProcessorSpecTest {
    data class OrderPlaced(val total: Int)
    data class OrderCancelled(val reason: String)
    data class Reserve(val orderId: String, val quantity: Int)
    data class Release(val orderId: String)
    data class Cancel(val reason: String)
    data class Remind(val orderId: String)

    class Order(val id: String) {
        var total = 0
    }

    class Payment(val orderId: String)

    data class Quote(val total: Int = 0)

    private val order = AggregateType("shop", "order").ref("tenant", "order-1")
    private val stock = AggregateType("inventory", "stock").ref("tenant", "apple")
    private val payment = AggregateType("pay", "payment").ref("tenant", "order-1")

    /** A saga, as a user writes it. */
    class OrderSaga {
        fun onOrder(event: StateEvent<Order>, context: SagaContext): List<Command<*>> {
            val placed = event.events.filterIsInstance<OrderPlaced>().firstOrNull() ?: return emptyList()
            require(placed.total >= 0) { "A total is never negative." }
            return listOf(
                context.command(STOCK, Reserve(event.ref.id, placed.total)),
                context.schedule("remind", ScheduleRule.after(10.minutes), event.ref, Remind(event.ref.id)),
            )
        }

        fun onCancelled(event: StateEvent<Order>, context: SagaContext): List<Command<*>> {
            if (event.events.none { it is OrderCancelled }) return emptyList()
            return listOf(context.cancel("remind", event.ref), context.command(STOCK, Release(event.ref.id)))
        }

        fun onQuote(event: StateEvent<Quote>, context: SagaContext): Command<Cancel>? =
            if (event.events.any { it is StateEventPayload && it.name == "pay.payment.Declined" }) {
                context.command(ORDER.ref(event.ref.tenantId, event.ref.id), Cancel("declined"))
            } else {
                null
            }

        fun onReserveRejected(rejected: CommandRejected<Reserve>, context: SagaContext): Command<Cancel> =
            context.command(rejected.executionId.ref, Cancel(rejected.error.message))

        companion object {
            val STOCK = AggregateType("inventory", "stock").ref("tenant", "apple")
            val ORDER = AggregateType("shop", "order")
        }
    }

    /** What the compiler generates for [OrderSaga]: `orderSagaHandlers(dependencies)`. */
    private fun orderSagaHandlers(): List<ProcessorBinding<*>> {
        val processor = OrderSaga()
        val rejections = listOf<RejectionBinding<*>>(
            RejectionBinding.of("OrderSaga", "onReserveRejected", Reserve::class.java) { rejected, context ->
                derivedOf(processor.onReserveRejected(rejected, context))
            },
        )
        return listOf(
            ProcessorBinding.saga(
                "OrderSaga",
                "onOrder",
                Order::class.java,
                rejections = rejections
            ) { event, context ->
                derivedOf(processor.onOrder(event, context))
            },
            ProcessorBinding.saga("OrderSaga", "onCancelled", Order::class.java, rejections = rejections) { e, c ->
                derivedOf(processor.onCancelled(e, c))
            },
            ProcessorBinding.saga(
                "OrderSaga",
                "onQuote",
                Quote::class.java,
                rejections = rejections,
                aggregates = listOf("pay.payment"),
            ) { e, c -> derivedOf(processor.onQuote(e, c)) },
        )
    }

    @Test
    fun `a saga derives its commands and schedules from a commit`() = sagaSpec(orderSagaHandlers()) {
        processor.assert().isEqualTo("OrderSaga")
        whenEvent(stateEvent(order, Order("order-1"), OrderPlaced(2), version = 3)) {
            expectHandledBy("onOrder", "onCancelled")
            expectHandledBy("OrderSaga.onOrder", "OrderSaga.onCancelled")
            expectCommands(Reserve("order-1", 2))
            expectCommandTypes(Reserve::class)
            expectCommand(Reserve("order-1", 2), to = stock)
            expectCommand<Reserve> {
                ref.assert().isEqualTo(stock)
                causation.assert().isEqualTo(Causation("command-3", "shop.order/tenant/order-1@3"))
                id.assert().isEqualTo(ExecutionSagaContext.derivedId(ExecutionId("OrderSaga.onOrder", order, 3), 0))
            }
            expectScheduled("remind", Remind("order-1"), ScheduleRule.after(10.minutes), target = order)
            expectScheduled("remind", Remind("order-1"))
            schedules.single().id.assert()
                .isEqualTo(ExecutionSagaContext.derivedId(ExecutionId("OrderSaga.onOrder", order, 3), 1))
            expectNoError()
            expectDeterministic()
        }
    }

    @Test
    fun `a saga's handler is called alone when it is named`() = sagaSpec(orderSagaHandlers()) {
        whenEvent(stateEvent(order, Order("order-1"), OrderCancelled("no")), handler = "onCancelled") {
            handled.assert().containsExactly("OrderSaga.onCancelled")
            expectCommand(Release("order-1"), to = stock)
            expectScheduleCancelled("remind", target = order)
            expectScheduleCancelled("remind")
        }
        whenEvent(stateEvent(order, Order("order-1"), OrderCancelled("no")), handler = "OrderSaga.onOrder") {
            expectNoCommand()
            expectNoSchedule()
        }
    }

    @Test
    fun `a saga reads a stream another context publishes`() = sagaSpec(orderSagaHandlers()) {
        whenEvent(stateEvent(payment, Quote(3), publishedEvent("pay.payment.Declined", mapOf("why" to "card")))) {
            expectHandledBy("onQuote")
            expectCommand(Cancel("declined"), to = order)
        }
        publishedEvent("pay.payment.Declined", mapOf("why" to "card")).payload["why"].asString()
            .assert().isEqualTo("card")
    }

    @Test
    fun `a rejection of a command the saga derived goes to its OnRejected handler`() = sagaSpec(orderSagaHandlers()) {
        whenEvent(stateEvent(order, Order("order-1"), OrderPlaced(2))) {
            whenRejected(commands.single(), code = "inventory.stock.insufficient", message = "Only 1.") {
                handled.assert().containsExactly("OrderSaga.onReserveRejected")
                expectCommand(Cancel("Only 1."), to = order)
                expectDeterministic()
                expectNoSchedule()
            }
        }
        val rejected = CommandRejected<Reserve>(
            commandId = "derived-1",
            commandType = "inventory.stock.Reserve",
            ref = stock,
            error = ErrorInfo("inventory.stock.insufficient", "None."),
            causation = Causation("command-1", "shop.order/tenant/order-1@1"),
            executionId = ExecutionId("OrderSaga.onOrder", order, 1),
        )
        whenRejected(rejected, Reserve::class) { expectCommands(Cancel("None.")) }
        assertThrownBy<AssertionError> { whenRejected(rejected, Release::class) { } }
        assertThrownBy<IllegalArgumentException> {
            whenRejected(rejected.copy(executionId = ExecutionId("Other.onOrder", order, 1)), Reserve::class) { }
        }
        assertThrownBy<IllegalArgumentException> {
            whenRejected(Command("x", "x", stock, Reserve("order-1", 1)), code = "c") { }
        }
    }

    @Test
    fun `an exception of a handler is expected by its type`() = sagaSpec(orderSagaHandlers()) {
        whenEvent(stateEvent(order, Order("order-1"), OrderPlaced(-1)), handler = "onOrder") {
            expectError<IllegalArgumentException> { message.assert().isEqualTo("A total is never negative.") }
            error.assert().isNotNull()
            assertThrownBy<AssertionError> { commands }
            assertThrownBy<AssertionError> { expectNoError() }
            assertThrownBy<AssertionError> { expectError<IllegalStateException>() }
            assertThrownBy<AssertionError> { expectDeterministic() }
        }
        assertThrownBy<IllegalArgumentException> {
            whenEvent(stateEvent(order, Order("order-1"), OrderPlaced(-1)), handler = "onOrder") { }
        }
    }

    @Test
    fun `wrong expectations fail`() = sagaSpec(orderSagaHandlers()) {
        whenEvent(stateEvent(order, Order("order-1"), OrderPlaced(2))) {
            assertThrownBy<AssertionError> { expectHandledBy("onOrder") }
            assertThrownBy<AssertionError> { expectNoCommand() }
            assertThrownBy<AssertionError> { expectCommands(Reserve("order-1", 3)) }
            assertThrownBy<AssertionError> { expectCommandTypes(Release::class) }
            assertThrownBy<AssertionError> { expectCommand(Reserve("order-1", 3)) }
            assertThrownBy<AssertionError> { expectCommand(Reserve("order-1", 2), to = order) }
            assertThrownBy<AssertionError> { expectCommand<Release>() }
            assertThrownBy<AssertionError> { expectScheduled("other", Remind("order-1")) }
            assertThrownBy<AssertionError> { expectScheduled("remind", Remind("order-2")) }
            assertThrownBy<AssertionError> { expectScheduled("remind", Remind("order-1"), ScheduleRule.after(1.minutes)) }
            assertThrownBy<AssertionError> { expectScheduleCancelled("remind") }
            assertThrownBy<AssertionError> { expectNoSchedule() }
            assertThrownBy<AssertionError> { expectError<IllegalStateException>() }
        }
        whenEvent(stateEvent(order, Order("order-1"), OrderCancelled("x")), handler = "onCancelled") {
            assertThrownBy<AssertionError> { expectCommand<Release> { body.orderId.assert().isEqualTo("x") } }
        }
        assertThrownBy<AssertionError> { whenEvent(stateEvent(stock, "a string", OrderPlaced(1))) { } }
        assertThrownBy<IllegalArgumentException> { whenEvent(stateEvent(order, Order("o"), 1), handler = "nope") { } }
    }

    @Test
    fun `a saga that derives other commands on a retry is caught`() {
        var calls = 0
        val handlers = listOf(
            ProcessorBinding.saga<Any>("Flaky", "onAny", null) { event, context ->
                listOf(context.command(event.ref, Remind("${calls++}")))
            },
        )
        sagaSpec(handlers) {
            whenEvent(stateEvent(order, null, 1)) {
                assertThrownBy<AssertionError> { expectDeterministic() }
            }
        }
        val scheduling = listOf(
            ProcessorBinding.saga<Any>("Flaky", "onAny", null) { event, context ->
                listOf(context.schedule("k", ScheduleRule.after((calls++).minutes), event.ref, Remind("r")))
            },
        )
        sagaSpec(scheduling) {
            whenEvent(stateEvent(order, null, 1)) {
                assertThrownBy<AssertionError> { expectDeterministic() }
            }
        }
    }

    @Test
    fun `a spec takes the handlers of one processor of its kind`() {
        assertThrownBy<IllegalArgumentException> { sagaSpec(emptyList()) { } }
        val project = ProcessorBinding.project<Any>("View", "on", null) { _, _ -> }
        assertThrownBy<IllegalArgumentException> { sagaSpec(listOf(project)) { } }
        assertThrownBy<IllegalArgumentException> {
            projectionSpec(listOf(project, ProcessorBinding.project<Any>("Other", "on", null) { _, _ -> })) { }
        }
        assertThrownBy<IllegalArgumentException> {
            projectionSpec(listOf(project)) {
                whenRejected(
                    CommandRejected<Any>(
                        "c",
                        "t",
                        order,
                        ErrorInfo("e", "m"),
                        Causation("c", "c"),
                        ExecutionId("View.on", order, 1),
                    ),
                    Any::class,
                ) { }
            }
        }
    }

    /** A projection's port, faked by the test. */
    class OrderViews {
        val totals = LinkedHashMap<String, Int>()
    }

    @Test
    fun `a projection writes what its port's fake then holds`() {
        val views = OrderViews()
        val handlers = listOf(
            ProcessorBinding.project("OrderView", "onOrder", Order::class.java) { event, _: ProjectContext ->
                event.events.filterIsInstance<OrderPlaced>().forEach { views.totals[event.ref.id] = it.total }
            },
        )
        projectionSpec(handlers) {
            whenEvent(stateEvent(order, Order("order-1"), OrderPlaced(5))) {
                expectHandledBy("onOrder")
                expectNoCommand()
                views.totals.assert().containsEntry("order-1", 5)
            }
        }
    }

    @Test
    fun `a reactor's side effects are checked on its fake`() {
        val sent = ArrayList<String>()
        val handlers = listOf(
            ProcessorBinding.react<Order?>("Mailer", "onOrder", Order::class.java, nullableState = true) { event, _ ->
                check(event.events.none { it is OrderCancelled }) { "The mail server is down." }
                sent += "${event.ref.id}@${event.version}"
            },
        )
        reactorSpec(handlers) {
            whenEvent(
                stateEvent<Order?>(order, null, OrderPlaced(1), version = 2)
            ) { sent.assert().containsExactly("order-1@2") }
            whenEvent(stateEvent(order, Order("order-1"), OrderCancelled("x"))) {
                expectError<IllegalStateException> { message.assert().isEqualTo("The mail server is down.") }
            }
        }
    }
}
