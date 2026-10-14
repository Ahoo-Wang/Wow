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
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import org.junit.jupiter.api.Test

/** Decisions D136, D153: the binding of a saga's `@OnRejected` handlers and the context they compensate in. */
class RejectionBindingTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")

    private interface CartCommand
    private open class Remove : CartCommand
    private class RemoveAll : Remove()
    private class Add : CartCommand

    private val execution = ExecutionId("CartSaga.onOrderPlaced", order.ref("t", "o-1"), 3)
    private val rejected = CommandRejected<Remove>(
        "c-1",
        "shop.cart.Remove",
        cart.ref("t", "alice"),
        ErrorInfo("cart.forbidden", "No."),
        Causation("root", "shop.order/t/o-1@3"),
        execution,
        Operator("alice", "t"),
        Scope("alice", "space-a"),
    )

    private fun handler(function: String, type: Class<*>?) =
        RejectionBinding.of("CartSaga", function, type) { _, _ -> emptyList() }

    @Test
    fun `a binding should be named processor dot function and handle the commands of its type`() {
        val remove = handler("onRemoveRejected", Remove::class.java)
        remove.name.assert().isEqualTo("CartSaga.onRemoveRejected")
        remove.processor.assert().isEqualTo("CartSaga")
        remove.function.assert().isEqualTo("onRemoveRejected")
        remove.blocking.assert().isFalse()
        remove.handles(Remove::class.java).assert().isTrue()
        remove.handles(RemoveAll::class.java).assert().isTrue()
        remove.handles(Add::class.java).assert().isFalse()
        remove.toString().assert().isEqualTo("RejectionBinding(CartSaga.onRemoveRejected, ${Remove::class.java.name})")
        val any = handler("onAnyRejected", null)
        any.handles(Add::class.java).assert().isTrue()
        any.toString().assert().isEqualTo("RejectionBinding(CartSaga.onAnyRejected, *)")
        assertThrownBy<IllegalArgumentException> { handler(" ", null) }
        assertThrownBy<IllegalArgumentException> { RejectionBinding.of<Any>(" ", "f", null) { _, _ -> emptyList() } }
    }

    @Test
    fun `the handler of a rejection should be the most specific one, else the one of every command`() {
        val exact = handler("onRemoveAll", RemoveAll::class.java)
        val remove = handler("onRemove", Remove::class.java)
        val cartCommand = handler("onCartCommand", CartCommand::class.java)
        val any = handler("onAny", null)
        val all = listOf(any, cartCommand, remove, exact)
        RejectionBinding.handlerOf(all, RemoveAll::class.java).assert().isSameAs(exact)
        RejectionBinding.handlerOf(all, Remove::class.java).assert().isSameAs(remove)
        RejectionBinding.handlerOf(all, Add::class.java).assert().isSameAs(cartCommand)
        RejectionBinding.handlerOf(all, String::class.java).assert().isSameAs(any)
        RejectionBinding.handlerOf(listOf(remove), String::class.java).assert().isNull()
        RejectionBinding.handlerOf(emptyList(), String::class.java).assert().isNull()
    }

    @Test
    fun `a saga binding should carry its processor's rejection handlers, one per command type`() {
        val remove = handler("onRemove", Remove::class.java)
        val saga = ProcessorBinding.saga<Any>("CartSaga", "onOrderPlaced", null, rejections = listOf(remove)) { _, _ ->
            emptyList()
        }
        saga.rejections.assert().containsExactly(remove)
        saga.rejectionHandlerOf(RemoveAll::class.java).assert().isSameAs(remove)
        saga.rejectionHandlerOf(Add::class.java).assert().isNull()
        ProcessorBinding.project<Any>("View", "onEvent", null) { _, _ -> }.rejections.assert().isEmpty()
        assertThrownBy<IllegalArgumentException> {
            ProcessorBinding.saga<Any>("OtherSaga", "f", null, rejections = listOf(remove)) { _, _ -> emptyList() }
        }.hasMessageContaining("is not a handler of OtherSaga")
        assertThrownBy<IllegalArgumentException> {
            ProcessorBinding.saga<Any>(
                "CartSaga",
                "f",
                null,
                rejections = listOf(handler("a", null), handler("b", null)),
            ) { _, _ -> emptyList() }
        }.hasMessageContaining("two @OnRejected handlers of the rejections of every command")
    }

    @Test
    fun `invoking a binding should call its handler with the rejection and a context that compensates deterministically`(): Unit =
        runBlocking {
            val handler = RejectionBinding.of(
                "CartSaga",
                "onRemoveRejected",
                Remove::class.java
            ) { rejection, context ->
                listOf(
                    context.command(rejection.ref, "restore"),
                    context.command(rejection.ref, "notify"),
                )
            }
            val context = rejectionContextOf(handler, rejected)
            context.executionId.assert().isEqualTo(ExecutionId("CartSaga.onRemoveRejected", execution.ref, 3))
            val commands: List<Command<*>> = handler.invoke(rejected, context)
            commands.map { it.id }.distinct().assert().hasSize(2)
            commands.forEach {
                it.requestId.assert().isEqualTo(it.id)
                it.ref.assert().isEqualTo(rejected.ref)
                it.causation.assert().isEqualTo(Causation("root", "c-1"))
                it.operator.assert().isEqualTo(rejected.operator)
                it.scope.assert().isEqualTo(Scope("alice", "space-a"))
            }
            commands[0].id.assert().isEqualTo(RejectionSagaContext.compensationId(context.executionId, "c-1", 0))
            // Handling the same rejection again derives the same requests; another rejection, others.
            handler.invoke(rejected, rejectionContextOf(handler, rejected)).map { it.id }
                .assert().isEqualTo(commands.map { it.id })
            handler.invoke(
                rejected.copy(commandId = "c-2"),
                rejectionContextOf(handler, rejected.copy(commandId = "c-2"))
            )
                .map { it.id }.assert().doesNotContainAnyElementsOf(commands.map { it.id })
            // Not the ids the saga's execution derives itself.
            commands[0].id.assert().isNotEqualTo(ExecutionSagaContext.derivedId(execution, 0))
            assertThrownBy<IllegalArgumentException> {
                RejectionSagaContext.compensationId(context.executionId, "c-1", -1)
            }
        }
}
