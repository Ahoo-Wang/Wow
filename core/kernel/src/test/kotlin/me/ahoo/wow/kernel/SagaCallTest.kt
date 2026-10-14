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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.api.subscribe.CallRejectedException
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import org.junit.jupiter.api.Test

/** Decision D193: a saga's synchronous call goes through the context's [CommandCalls], its failures typed. */
class SagaCallTest {
    private val order = AggregateType("shop", "order")
    private val stock = AggregateType("inventory", "stock")
    private val event = StateEvent(
        order.ref("t", "o-1"),
        3,
        "c-3",
        "r-3",
        "shop.order.Place",
        1L,
        listOf("placed"),
        "state",
        scope = Scope("alice"),
        causation = Causation("root", "shop.order/t/o-0@1"),
        operator = Operator("alice", "t"),
    )
    private val execution = ExecutionId("OrderSaga.onPlaced", event.ref, 3)

    private fun result(command: Command<*>, status: CommandResult.Status) = CommandResult(
        commandId = command.id,
        requestId = command.requestId,
        correlationId = "root",
        ref = command.ref,
        hop = Hop.committed("inventory"),
        function = FunctionInfo(FunctionKind.COMMAND, "inventory", "stock", "Reserve"),
        status = status,
        version = 2,
        error = if (status == CommandResult.Status.SUCCEEDED) null else ErrorInfo("inventory.stock.short", "Short."),
        signalTime = 1L,
    )

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        return failure as? T ?: throw AssertionError("Expected a ${T::class.simpleName} but got $failure.", failure)
    }

    @Test
    fun `a call should return the target's result when it succeeded`(): Unit = runBlocking {
        val called = mutableListOf<Command<*>>()
        val context = contextOf(
            FunctionKind.SAGA,
            execution.subscription,
            event,
            calls = CommandCalls {
                called += it
                result(it, CommandResult.Status.SUCCEEDED)
            },
        ) as SagaContext
        val command = context.command(stock.ref("t", "mug"), "reserve")
        val answer = context.call(command)
        answer.version.assert().isEqualTo(2)
        called.single().assert().isSameAs(command)
        // The call is a derived command of the execution: deterministic, the commit's operator and scope.
        command.id.assert().isEqualTo(ExecutionSagaContext.derivedId(execution, 0))
        command.operator.assert().isEqualTo(Operator("alice", "t"))
        command.scope.assert().isEqualTo(Scope("alice"))
    }

    @Test
    fun `a call the target refused should be a typed rejection`(): Unit = runBlocking {
        val context = ExecutionSagaContext(
            execution,
            event,
            calls = CommandCalls { result(it, CommandResult.Status.REJECTED) },
        )
        val command = context.command(stock.ref("t", "mug"), "reserve")
        val rejected = thrown<CallRejectedException> { runBlocking { context.call(command) } }
        rejected.command.assert().isSameAs(command)
        rejected.code.assert().isEqualTo("inventory.stock.short")
        rejected.message!!.assert().contains("inventory.stock.short")
    }

    @Test
    fun `a call that failed at the target or reached no result should be a typed failure`(): Unit = runBlocking {
        val failing = ExecutionSagaContext(
            execution,
            event,
            calls = CommandCalls { result(it, CommandResult.Status.FAILED) },
        )
        val failed = thrown<CallFailedException> {
            runBlocking { failing.call(failing.command(stock.ref("t", "mug"), "reserve")) }
        }
        failed.result!!.status.assert().isEqualTo(CommandResult.Status.FAILED)
        // Without a client, a call names the configuration it lacks.
        val unconfigured = ExecutionSagaContext(execution, event)
        val missing = thrown<CallFailedException> {
            runBlocking { unconfigured.call(unconfigured.command(stock.ref("t", "mug"), "reserve")) }
        }
        missing.message!!.assert().contains("wow.calls.contexts.inventory.url")
        missing.result.assert().isNull()
    }

    @Test
    fun `a rejected call result should need a REJECTED status`() {
        val command = Command("c", "r", stock.ref("t", "mug"), "reserve")
        assertThrownBy<IllegalArgumentException> {
            CallRejectedException(command, result(command, CommandResult.Status.FAILED))
        }
    }

    @Test
    fun `a receipt's handler should not call`() {
        val handler = RejectionBinding.of<Any>("OrderSaga", "onRejected", null) { _, _ -> emptyList() }
        val rejected = CommandRejected<Any>(
            commandId = "c",
            commandType = "inventory.stock.Reserve",
            ref = stock.ref("t", "mug"),
            error = ErrorInfo("inventory.stock.short", "Short."),
            causation = Causation("root", "c"),
            executionId = execution,
        )
        val context = rejectionContextOf(handler, rejected)
        assertThrownBy<UnsupportedOperationException> {
            runBlocking { context.call(context.command(stock.ref("t", "mug"), "release")) }
        }
    }
}
