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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.CallRejectedHandlingException
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.UndeclaredSendException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** The processor specifications fork and run as trees of tests too (decision D245). */
class ProcessorForkTest {
    data class OrderPlaced(val total: Int)
    data class Reserve(val orderId: String, val quantity: Int)
    data class Cancel(val reason: String)
    data class Confirm(val version: Int)
    data class Check(val orderId: String)

    class Order(val id: String)

    /** A domain service the saga calls: a test mocks it. */
    fun interface Quantities {
        fun of(total: Int): Int
    }

    private val order = AggregateType("shop", "order").ref("tenant", "order-1")
    private val stock = AggregateType("inventory", "stock").ref("tenant", "apple")

    private fun sagaHandlers(quantities: Quantities): List<ProcessorBinding<*>> {
        val rejections = listOf<RejectionBinding<*>>(
            RejectionBinding.of(
                "OrderSaga",
                "onRejected",
                Reserve::class.java,
                derives = listOf(Cancel::class.java),
            ) { rejected, context ->
                listOf(CommandRequest.to(rejected.source, Cancel(rejected.error.message)))
            },
        )
        val commits = listOf<CommittedBinding<*>>(
            CommittedBinding.of(
                "OrderSaga",
                "onReserved",
                Reserve::class.java,
                derives = listOf(Confirm::class.java),
            ) { committed, context ->
                listOf(CommandRequest.to(committed.source, Confirm(committed.version)))
            },
        )
        return listOf(
            ProcessorBinding.saga(
                "OrderSaga",
                "onOrder",
                Order::class.java,
                rejections = rejections,
                commits = commits,
                derives = listOf(Reserve::class.java),
            ) { event: StateEvent<Order>, context ->
                val placed = event.events.filterIsInstance<OrderPlaced>().single()
                listOf(CommandRequest.to(stock, Reserve(event.ref.id, quantities.of(placed.total))))
            },
        )
    }

    private fun placed(total: Int, version: Int = 1) = stateEvent(
        order,
        Order("order-1"),
        OrderPlaced(total),
        version = version
    )

    @Test
    fun `a saga forks after a handling without handling it again`() {
        val quantities = mockk<Quantities>()
        every { quantities.of(any()) } returns 2
        sagaSpec(sagaHandlers(quantities)) {
            whenEvent(placed(4)) {
                expectCommands(Reserve("order-1", 2))
                val reserve = commands.single()
                fork("out of stock") {
                    whenRejected(reserve, code = "stock.insufficient", message = "Only 1.") {
                        expectCommand(Cancel("Only 1."), to = order)
                    }
                }
                fork("reserved") {
                    whenCommitted(reserve, version = 3) { expectCommand(Confirm(3), to = order) }
                }
            }
        }
        verify(exactly = 1) { quantities.of(4) }
    }

    @Test
    fun `a saga's derived commands should be counted`() = sagaSpec(sagaHandlers { it }) {
        whenEvent(placed(1)) {
            expectCommandCount(1)
            assertThrownBy<AssertionError> { expectCommandCount(2) }
                .hasMessageContaining("Expected 2 commands but got 1")
        }
    }

    @Test
    fun `a fork keeps what the specification derived, and what it derives stays in it`() =
        sagaSpec(sagaHandlers { it }) {
            whenEvent(placed(1)) { }
            lateinit var inFork: Command<*>
            fork {
                whenEvent(placed(2, version = 2)) { inFork = commands.single() }
                whenCommitted(inFork) { expectCommand(Confirm(1)) }
            }
            assertThrownBy<IllegalArgumentException> { whenCommitted(inFork) { } }
        }

    @Test
    fun `a fork answers calls as the specification did, until it answers them otherwise`() {
        val calling = listOf(
            ProcessorBinding.saga(
                "Checker",
                "onOrder",
                Order::class.java,
                calls = listOf(Check::class.java),
            ) { event: StateEvent<Order>, context ->
                context.call(CommandRequest.to(stock, Check(event.ref.id)))
                emptyList()
            },
        )
        sagaSpec(calling) {
            answerCalls { rejectedResult(it, "stock.closed") }
            fork {
                whenEvent(placed(1)) { expectError<CallRejectedHandlingException>() }
                answerCalls { committedResult(it) }
                whenEvent(placed(1)) { expectCalls(Check("order-1")) }
            }
            whenEvent(placed(1)) { expectError<CallRejectedHandlingException>() }
        }
    }

    @Test
    fun `a call of a class the handler does not declare should fail its handling`() {
        val undeclared = listOf(
            ProcessorBinding.saga("Checker", "onOrder", Order::class.java) { event: StateEvent<Order>, context ->
                context.call(CommandRequest.to(stock, Check(event.ref.id)))
                emptyList()
            },
        )
        sagaSpec(undeclared) {
            answerCalls { committedResult(it) }
            whenEvent(placed(1)) { expectError<UndeclaredSendException>() }
        }
    }

    @Test
    fun `a failure in a saga's fork names it`() {
        assertThrownBy<ForkAssertionError> {
            sagaSpec(sagaHandlers { it }) {
                fork("outer") {
                    whenEvent(placed(1)) { fork("inner") { whenEvent(placed(2)) { expectNoCommand() } } }
                }
            }
        }.hasMessageStartingWith("In fork \"outer > inner\": ")
    }

    @Test
    fun `a saga's tree of tests reports each handling and each fork on its own`() {
        val results = sagaSpecTests(sagaHandlers { it }) {
            whenEvent(placed(1)) {
                val reserve = commands.single()
                fork("fails") { whenRejected(reserve, code = "x") { expectNoCommand() } }
                fork("passes") { whenCommitted(reserve) { expectCommand(Confirm(1)) } }
                fork { }
            }
            whenRejected(
                commandRejected(Command("c", "r", stock, Reserve("o", 1)), executionOf(), "y"),
                Reserve::class
            ) { }
            whenCommitted(
                commandCommitted(Command("c", "r", stock, Reserve("o", 1)), executionOf()),
                Reserve::class
            ) { }
            whenEvent(stateEvent(order, Order("order-1"), Cancel("no"))) { }
        }.results()
        results.paths.assert().containsExactly(
            "whenEvent(OrderPlaced)",
            "fails/whenRejected(Reserve, x)",
            "passes/whenCommitted(Reserve)",
            "whenRejected(Reserve, y)",
            "whenCommitted(Reserve)",
            "whenEvent(Cancel)",
        )
        results.failed.assert()
            .containsExactly("fails/whenRejected(Reserve, x)", "whenEvent(Cancel)")
    }

    private fun executionOf() = ExecutionId("OrderSaga.onOrder", order, 1)

    @Test
    fun `projections and reactors run as trees of tests`() {
        val totals = HashMap<String, Int>()
        val projection = listOf(
            ProcessorBinding.project("View", "onOrder", Order::class.java) { event: StateEvent<Order>, _ ->
                event.events.filterIsInstance<OrderPlaced>().forEach { totals[event.ref.id] = it.total }
            },
        )
        projectionSpecTests(projection) {
            whenEvent(placed(5)) { expectHandledBy("onOrder") }
            fork("again") { whenEvent(placed(6)) { totals["order-1"].assert().isEqualTo(6) } }
        }.results().failed.assert().isEmpty()
        val reactor = listOf(
            ProcessorBinding.react("Mailer", "onOrder", Order::class.java) { _: StateEvent<Order>, _ ->
                error("The mail server is down.")
            },
        )
        reactorSpecTests(reactor) {
            whenEvent(placed(1)) { expectError<IllegalStateException>() }
            whenEvent(placed(1)) { }
        }.results().failed.assert().containsExactly("whenEvent(OrderPlaced)")
    }

    @TestFactory
    fun `a saga as a tree of tests`() = sagaSpecTests(sagaHandlers { it * 2 }) {
        whenEvent(placed(2)) {
            expectCommands(Reserve("order-1", 4))
            val reserve = commands.single()
            fork("reserved") { whenCommitted(reserve, version = 2) { expectCommand(Confirm(2)) } }
        }
    }
}
