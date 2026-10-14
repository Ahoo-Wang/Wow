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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.ExecutionId
import org.junit.jupiter.api.Test

/** Decision D192: the binding of a saga's `@OnCommitted` handlers and the context they derive commands in. */
class CommittedBindingTest {
    private val order = AggregateType("shop", "order")
    private val stock = AggregateType("inventory", "stock")

    private interface StockCommand
    private open class Reserve : StockCommand
    private class ReserveAll : Reserve()
    private class Release : StockCommand

    private val execution = ExecutionId("OrderSaga.onOrderPlaced", order.ref("t", "o-1"), 3)
    private val committed = CommandCommitted<Reserve>(
        commandId = "c-1",
        commandType = "inventory.stock.Reserve",
        ref = stock.ref("t", "s-1"),
        version = 4,
        result = CommandResult(
            commandId = "c-1",
            requestId = "c-1",
            correlationId = "root",
            ref = stock.ref("t", "s-1"),
            hop = Hop.committed("inventory"),
            function = FunctionInfo(FunctionKind.COMMAND, "inventory", "stock", "Reserve"),
            status = CommandResult.Status.SUCCEEDED,
            version = 4,
            signalTime = 1L,
        ),
        causation = Causation("root", "shop.order/t/o-1@3"),
        executionId = execution,
        operator = Operator("alice", "t"),
        scope = Scope("alice", "space-a"),
    )

    private fun handler(function: String, type: Class<*>?) =
        CommittedBinding.of("OrderSaga", function, type) { _, _ -> emptyList() }

    @Test
    fun `a binding should be named processor dot function and handle the commands of its type`() {
        val reserve = handler("onReserved", Reserve::class.java)
        reserve.name.assert().isEqualTo("OrderSaga.onReserved")
        reserve.processor.assert().isEqualTo("OrderSaga")
        reserve.function.assert().isEqualTo("onReserved")
        reserve.blocking.assert().isFalse()
        reserve.handles(ReserveAll::class.java).assert().isTrue()
        reserve.handles(Release::class.java).assert().isFalse()
        reserve.toString().assert().isEqualTo("CommittedBinding(OrderSaga.onReserved, ${Reserve::class.java.name})")
        val any = handler("onAny", null)
        any.handles(Release::class.java).assert().isTrue()
        any.toString().assert().isEqualTo("CommittedBinding(OrderSaga.onAny, *)")
        assertThrownBy<IllegalArgumentException> { handler(" ", null) }
        assertThrownBy<IllegalArgumentException> { CommittedBinding.of<Any>(" ", "f", null) { _, _ -> emptyList() } }
    }

    @Test
    fun `the handler of a receipt should be the most specific one, else the one of every command`() {
        val exact = handler("onReserveAll", ReserveAll::class.java)
        val reserve = handler("onReserve", Reserve::class.java)
        val stockCommand = handler("onStockCommand", StockCommand::class.java)
        val any = handler("onAny", null)
        val all = listOf(any, stockCommand, reserve, exact)
        CommittedBinding.handlerOf(all, ReserveAll::class.java).assert().isSameAs(exact)
        CommittedBinding.handlerOf(all, Reserve::class.java).assert().isSameAs(reserve)
        CommittedBinding.handlerOf(all, Release::class.java).assert().isSameAs(stockCommand)
        CommittedBinding.handlerOf(all, String::class.java).assert().isSameAs(any)
        CommittedBinding.handlerOf(listOf(reserve), String::class.java).assert().isNull()
    }

    @Test
    fun `a saga binding should carry its processor's committed handlers, one per command type`() {
        val reserve = handler("onReserve", Reserve::class.java)
        val saga = ProcessorBinding.saga<Any>("OrderSaga", "onOrderPlaced", null, commits = listOf(reserve)) { _, _ ->
            emptyList()
        }
        saga.commits.assert().containsExactly(reserve)
        saga.committedHandlerOf(ReserveAll::class.java).assert().isSameAs(reserve)
        saga.committedHandlerOf(Release::class.java).assert().isNull()
        ProcessorBinding.project<Any>("View", "onEvent", null) { _, _ -> }.commits.assert().isEmpty()
        ProcessorBinding.react<Any>("Mailer", "onEvent", null) { _, _ -> }.commits.assert().isEmpty()
        assertThrownBy<IllegalArgumentException> {
            ProcessorBinding.saga<Any>("OtherSaga", "f", null, commits = listOf(reserve)) { _, _ -> emptyList() }
        }.hasMessageContaining("is not a handler of OtherSaga")
        assertThrownBy<IllegalArgumentException> {
            ProcessorBinding.saga<Any>(
                "OrderSaga",
                "f",
                null,
                commits = listOf(handler("a", null), handler("b", null)),
            ) { _, _ -> emptyList() }
        }.hasMessageContaining("two @OnCommitted handlers of the receipts of every command")
    }

    @Test
    fun `invoking a binding should call its handler with the receipt and a context that derives deterministically`(): Unit =
        runBlocking {
            val handler = CommittedBinding.of("OrderSaga", "onReserved", Reserve::class.java) { receipt, context ->
                listOf(context.command(execution.ref, "confirm ${receipt.aggregateId}"))
            }
            val context = committedContextOf(handler, committed)
            context.executionId.assert().isEqualTo(ExecutionId("OrderSaga.onReserved", execution.ref, 3))
            val command = handler.invoke(committed, context).single()
            command.body.assert().isEqualTo("confirm s-1")
            command.id.assert().isEqualTo(ReceiptSagaContext.compensationId(context.executionId, "c-1", 0))
            command.requestId.assert().isEqualTo(command.id)
            command.causation.assert().isEqualTo(Causation("root", "c-1"))
            command.operator.assert().isEqualTo(committed.operator)
            command.scope.assert().isEqualTo(Scope("alice", "space-a"))
            // Handling the same receipt again derives the same request.
            handler.invoke(committed, committedContextOf(handler, committed)).single().id.assert().isEqualTo(command.id)
            // A receipt's handler does not schedule.
            assertThrownBy<UnsupportedOperationException> {
                context.schedule("k", ScheduleRule.After(1_000), execution.ref, "x")
            }
            assertThrownBy<UnsupportedOperationException> { context.cancel("k", execution.ref) }
        }
}
