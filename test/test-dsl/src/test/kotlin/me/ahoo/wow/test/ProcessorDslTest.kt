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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import kotlin.time.Duration.Companion.minutes

/**
 * The processor specifications source an aggregate through its model, check rejection codes against the target's
 * contract, compare commands structurally and read a handler's retry budget (decision D340).
 */
class ProcessorDslTest {
    data class PlaceOrder(val total: Int)
    data class OrderPlaced(val total: Int)
    data class OrderPaid(val amount: Int)

    @Name("Reserve")
    data class ReserveStock(val orderId: String, val quantity: Int)
    data class Cancel(val reason: String)

    class Order(val id: String) {
        var total = 0
        var paid = 0
    }

    private val orderModel: AggregateModel<Order> = aggregate("shop", "order") {
        initial { Order(it.id) }
        evolve<OrderPlaced> { total = it.total }
        evolve<OrderPaid> { paid += it.amount }
    }

    private val stock = AggregateType("inventory", "stock")

    /** Reserves the unpaid part of an order's total, and cancels the order when the inventory refuses. */
    private fun sagaHandlers(retry: HandlerRetry? = null, fail: (Order) -> Unit = {}): List<ProcessorBinding<*>> {
        val rejections = listOf<RejectionBinding<*>>(
            RejectionBinding.of("OrderSaga", "onRejected", ReserveStock::class.java) { rejected, context ->
                listOf(context.command(rejected.executionId.ref, Cancel(rejected.error.code)))
            },
        )
        return listOf(
            ProcessorBinding.saga(
                "OrderSaga",
                "onOrder",
                Order::class.java,
                rejections = rejections,
                retry = retry,
            ) { event: StateEvent<Order>, context ->
                fail(event.state)
                val commands = listOf(context.command(stock.ref(event.ref.tenantId, "mug"), ReserveStock(event.ref.id, event.state.total - event.state.paid)))
                if (event.state.paid > 0) {
                    commands + context.schedule("reminder", ScheduleRule.after(30.minutes), event.ref, Cancel("unpaid"))
                } else {
                    commands
                }
            },
        )
    }

    private fun <T> withContracts(block: () -> T): T {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader =
            URLClassLoader(arrayOf(requireNotNull(javaClass.getResource("/commands/peer/"))), javaClass.classLoader)
        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    @Test
    fun `a given aggregate's next commit should carry the state its model sources and the command that made it`() =
        sagaSpec(sagaHandlers()) {
            val order = givenAggregate(orderModel, "order-1", OrderPlaced(10))
            order.version.assert().isEqualTo(1)
            order.state.total.assert().isEqualTo(10)
            whenEvent(OrderPaid(4), by = PlaceOrder::class) {
                expectCommands(stock.ref(DEFAULT_TENANT, "mug") to ReserveStock("order-1", 6))
            }
            // Each commit follows the history, not the commit before it.
            whenEvent(OrderPaid(1)) { expectCommand(ReserveStock("order-1", 9), to = stock.ref(DEFAULT_TENANT, "mug")) }
        }

    @Test
    fun `the commit of a given aggregate should be numbered and named after its history and command`() {
        val order = GivenAggregate(orderModel, orderModel.type.ref("acme", "order-1"), listOf(OrderPlaced(10)), Scope.NONE)
        val commit = order.commit(listOf(OrderPaid(4)), ReserveStock::class, null, mapOf("app_id" to "console"))
        commit.version.assert().isEqualTo(2)
        commit.state.paid.assert().isEqualTo(4)
        commit.commandType.assert().isEqualTo("shop.order.Reserve")
        commit.headers.assert().containsEntry("app_id", "console")
        order.commit(listOf(OrderPaid(1)), null, null, emptyMap()).commandType.assert().isEqualTo("shop.order.Command")
        assertThrownBy<IllegalArgumentException> { order.commit(emptyList(), null, null, emptyMap()) }
        assertThrownBy<IllegalArgumentException> {
            GivenAggregate(orderModel, stock.ref("acme", "mug"), emptyList(), Scope.NONE)
        }.hasMessageContaining("shop.order")
    }

    @Test
    fun `whenEvent of events should name the aggregate when none or several are given`() {
        assertThrownBy<IllegalArgumentException> {
            sagaSpec(sagaHandlers()) { whenEvent(OrderPaid(1)) { } }
        }.hasMessageContaining("No aggregate is given")
        assertThrownBy<IllegalArgumentException> {
            sagaSpec(sagaHandlers()) {
                givenAggregate(orderModel, "order-1", OrderPlaced(10))
                givenAggregate(orderModel, orderModel.type.ref(DEFAULT_TENANT, "order-2"), OrderPlaced(5))
                whenEvent(OrderPaid(1)) { }
            }
        }.hasMessageContaining("Several aggregates")
        sagaSpec(sagaHandlers()) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            val second = givenAggregate(orderModel, "order-2", OrderPlaced(5))
            fork("on the second") {
                whenEvent(OrderPaid(1), on = second) { expectCommand(ReserveStock("order-2", 4)) }
            }
        }
    }

    @Test
    fun `a command that differs should list each difference by its path`() = sagaSpec(sagaHandlers()) {
        givenAggregate(orderModel, "order-1", OrderPlaced(10))
        whenEvent(OrderPaid(4)) {
            assertThrownBy<AssertionError> { expectCommands(ReserveStock("order-1", 5)) }
                .hasMessage("The commands differ:\n  [0].quantity: expected 5, was 6")
            assertThrownBy<AssertionError> { expectCommands(stock.ref("other", "mug") to ReserveStock("order-1", 6)) }
                .hasMessage("The commands differ:\n  [0].to.tenantId: expected \"other\", was \"(0)\"")
            assertThrownBy<AssertionError> { expectCommands(ReserveStock("order-1", 6), Cancel("x")) }
                .hasMessageContaining("expected 2 commands, was 1").hasMessageContaining("[1]: missing Cancel(reason=x)")
            assertThrownBy<AssertionError> { expectCommands(*emptyArray<Any>()) }
                .hasMessageContaining("[0]: unexpected ReserveStock(orderId=order-1, quantity=6) to")
            assertThrownBy<AssertionError> { expectCommand(Cancel("x")) }
                .hasMessage("The command differs:\n  [0]: expected a Cancel, was a ReserveStock")
            assertThrownBy<AssertionError> { expectScheduled("reminder", Cancel("paid")) }
                .hasMessage("The schedule reminder differs:\n  command.reason: expected \"paid\", was \"unpaid\"")
            assertThrownBy<AssertionError> { expectScheduled("reminder", Cancel("unpaid"), ScheduleRule.after(1.minutes)) }
                .hasMessageStartingWith("The schedule reminder differs:\n  rule.")
            expectScheduled("reminder", Cancel("unpaid"), ScheduleRule.after(30.minutes))
            assertThrownBy<AssertionError> { expectCalls(Cancel("x")) }
                .hasMessageContaining("The calls differ:").hasMessageContaining("[0]: missing Cancel(reason=x)")
        }
    }

    @Test
    fun `a rejection's code should be one the target's contract declares or the framework's`() = withContracts {
        sagaSpec(sagaHandlers()) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPlaced(10)) {
                val reserve = commands.single()
                // A local name is qualified with the target's type, as the target answers it.
                whenRejected(reserve, "insufficient") { expectCommand(Cancel("inventory.stock.insufficient")) }
                whenRejected(reserve, FrameworkCodes.AGGREGATE_NOT_FOUND) { expectCommand(Cancel(FrameworkCodes.AGGREGATE_NOT_FOUND)) }
                assertThrownBy<IllegalArgumentException> { whenRejected(reserve, "inventory.stock.gone") { } }
                    .hasMessage(
                        "inventory declares no rejection code inventory.stock.gone: its codes are " +
                            "[inventory.stock.insufficient, inventory.stock.unknown-sku], beside the framework's (FrameworkCodes).",
                    )
            }
        }
    }

    @Test
    fun `a rejection of a target whose contract is not on the class path should not be checked`() =
        sagaSpec(sagaHandlers()) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPlaced(10)) {
                whenRejected(commands.single(), "anything") { expectCommand(Cancel("inventory.stock.anything")) }
            }
        }

    @Test
    fun `a handler's failure should be retried within its budget and parked past it or when unrecoverable`() {
        val retry = HandlerRetry.of(3, "", "", "", listOf(IllegalStateException::class.java), listOf(IllegalArgumentException::class.java))
        sagaSpec(sagaHandlers(retry) { error("down") }) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPaid(1)) {
                expectRetried().assert().isInstanceOf(IllegalStateException::class.java)
                expectRetried(attempt = 2)
                expectParked(attempt = 3)
                assertThrownBy<AssertionError> { expectParked() }
                    .hasMessageContaining("attempt 1 of 3 leaves some of its budget")
                assertThrownBy<AssertionError> { expectRetried(attempt = 3) }
                    .hasMessageContaining("attempt 3 spends its 3 attempts")
                assertThrownBy<IllegalArgumentException> { expectRetried(attempt = 0) }
            }
        }
        sagaSpec(sagaHandlers(retry) { require(false) { "bad" } }) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPaid(1)) {
                expectParked()
                assertThrownBy<AssertionError> { expectRetried() }
                    .hasMessageContaining("its @Retry takes IllegalArgumentException for unrecoverable")
            }
        }
        // Without a budget of its own, a handler has the runtime's default.
        sagaSpec(sagaHandlers { error("down") }) {
            givenAggregate(orderModel, "order-1", OrderPlaced(10))
            whenEvent(OrderPaid(1)) {
                expectRetried(attempt = HandlerRetry.DEFAULT_MAX_ATTEMPTS - 1)
                expectParked(attempt = HandlerRetry.DEFAULT_MAX_ATTEMPTS)
            }
        }
    }

    @Test
    fun `a handling that ended should be neither retried nor parked`() = sagaSpec(sagaHandlers()) {
        givenAggregate(orderModel, "order-1", OrderPlaced(10))
        whenEvent(OrderPaid(1)) {
            assertThrownBy<AssertionError> { expectParked() }.hasMessage("Expected the handling to fail but it ended.")
        }
    }
}
