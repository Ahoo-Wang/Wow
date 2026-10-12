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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.runtime.subscription.StartFrom
import org.junit.jupiter.api.Test

/** The pieces a generated binding builds on: handlers, their contexts, derived ids and providers. */
class BoundHandlerTest {
    private val event = StateEvent(
        ORDER.ref(TENANT, "o-1"),
        3,
        "c-3",
        "r-3",
        "shop.order.Place",
        1L,
        listOf(Placed(listOf("apple"))),
        OrderState(),
        causation = Causation("root", "shop.order/t/o-0@1"),
    )

    @Test
    fun `a handler should be named processor dot function, of its kind`() {
        val project = BoundHandler.project("View", "onEvent", OrderState::class.java) { _, _ -> }
        project.name.assert().isEqualTo("View.onEvent")
        project.kind.assert().isEqualTo(FunctionKind.PROJECT)
        project.toString().assert().isEqualTo("BoundHandler(View.onEvent, PROJECT)")
        BoundHandler.react<Any>("Mail", "send", null) { _, _ -> }.kind.assert().isEqualTo(FunctionKind.REACT)
        BoundHandler.saga<Any>(
            "Saga",
            "derive",
            null
        ) { _, _ -> emptyList() }.kind.assert().isEqualTo(FunctionKind.SAGA)
        assertThrownBy<IllegalArgumentException> { BoundHandler.project<Any>(" ", "f", null) { _, _ -> } }
        assertThrownBy<IllegalArgumentException> { BoundHandler.project<Any>("p", " ", null) { _, _ -> } }
    }

    @Test
    fun `a blocking handler should run on a virtual thread, the others inline`(): Unit = runBlocking {
        var virtual: Boolean? = null
        val execution = ExecutionId("View.onEvent", event.ref, event.version)
        BoundHandler.project("View", "onEvent", OrderState::class.java, blocking = true) { _, _ ->
            virtual = Thread.currentThread().isVirtual
        }.handle(event, RuntimeProjectContext(execution))
        virtual.assert().isEqualTo(true)
        BoundHandler.react("View", "onEvent", OrderState::class.java) { _, _ ->
            virtual = Thread.currentThread().isVirtual
        }.handle(event, RuntimeReactContext(execution))
        virtual.assert().isEqualTo(false)
    }

    @Test
    fun `a saga context should derive deterministic requests linked to the commit`() {
        val execution = ExecutionId("CartSaga.onOrderPlaced", event.ref, event.version)
        val first = RuntimeSagaContext(execution, event)
        val again = RuntimeSagaContext(execution, event)
        val commands =
            listOf(
                first.command(CART.ref(TENANT, "alice"), RemoveItems(listOf("apple"))),
                first.command(CART.ref(TENANT, "bob"), "x")
            )
        val replayed =
            listOf(
                again.command(CART.ref(TENANT, "alice"), RemoveItems(listOf("apple"))),
                again.command(CART.ref(TENANT, "bob"), "x")
            )
        commands.map { it.requestId }.assert().isEqualTo(replayed.map { it.requestId })
        commands.map { it.id }.distinct().assert().hasSize(2)
        commands.forEach {
            it.id.assert().isEqualTo(it.requestId)
            it.causation.assert().isEqualTo(Causation("root", "shop.order/t/o-1@3"))
        }
        commands[0].id.assert().isEqualTo(RuntimeSagaContext.derivedId(execution, 0))
        RuntimeSagaContext.derivedId(execution.copy(version = 4), 0).assert().isNotEqualTo(commands[0].id)
        RuntimeSagaContext.derivedId(
            execution.copy(subscription = "Other.saga"),
            0
        ).assert().isNotEqualTo(commands[0].id)
        assertThrownBy<IllegalArgumentException> { RuntimeSagaContext.derivedId(execution, -1) }
        assertThrownBy<IllegalArgumentException> { RuntimeSagaContext(execution.copy(version = 2), event) }
        // A command a user sends directly is its own root: its derived commands' root is that command.
        val root = event.copy(causation = null)
        RuntimeSagaContext(execution, root).command(CART.ref(TENANT, "alice"), "x").causation!!.correlationId
            .assert().isEqualTo("c-3")
    }

    @Test
    fun `what a saga returns should be read as the commands it derives`() {
        val command = Command("c", "r", CART.ref(TENANT, "alice"), "x")
        derivedOf(null).assert().isEmpty()
        derivedOf(Unit).assert().isEmpty()
        derivedOf(command).assert().containsExactly(command)
        derivedOf(listOf(command, command)).assert().containsExactly(command, command)
        derivedOf(setOf<Command<*>>()).assert().isEmpty()
        assertThrownBy<IllegalStateException> { derivedOf("x") }.hasMessageContaining("A saga derives commands, not x")
        assertThrownBy<IllegalStateException> { derivedOf(listOf(command, 1)) }
            .hasMessageContaining("A saga derives commands, not 1")
    }

    @Test
    fun `bindings should come from the providers, by context, with unique names`() {
        val view = BoundHandler.project("View", "onEvent", OrderState::class.java) { _, _ -> }
        val saga = BoundHandler.saga("Saga", "onEvent", OrderState::class.java) { _, _ -> emptyList() }
        val provider = object : SubscriptionProvider {
            override val context: String = SHOP

            override fun handlers(dependencies: Dependencies): List<BoundHandler<*>> =
                listOf(view, saga).also { dependencies.get(String::class.java) }
        }
        val bindings = SubscriptionBindings.of(Dependencies.of("service"), listOf(provider))
        bindings.viewTypes(SHOP, listOf(orderModel(), cartModel())).assert().containsExactly(ORDER)
        bindings.viewTypes("other", listOf(orderModel())).assert().isEmpty()
        assertThrownBy<IllegalArgumentException> {
            SubscriptionBindings(mapOf(SHOP to listOf(view, view)))
        }.hasMessageContaining("Context shop has two handlers named View.onEvent")
        assertThrownBy<IllegalArgumentException> {
            SubscriptionBindings(mapOf(SHOP to listOf(view))).viewTypes(SHOP, listOf(cartModel()))
        }.hasMessageContaining("View.onEvent reads the state ${OrderState::class.java.name}, which no aggregate")
        SubscriptionBindings.defaultStartFrom(view).assert().isEqualTo(StartFrom.BEGINNING)
        SubscriptionBindings.defaultStartFrom(saga).assert().isEqualTo(StartFrom.END)
        SubscriptionProvider.load().assert().isEmpty()
        SubscriptionBindings.of(Dependencies.NONE).viewTypes(SHOP, listOf(orderModel())).assert().isEmpty()
    }

    @Test
    fun `a rejected derived command should say what was rejected`() {
        val command = Command("c-1", "r-1", CART.ref(TENANT, "alice"), "x")
        val failure = DerivedCommandRejectedException(command, me.ahoo.wow.api.decide.Rejection(FORBIDDEN, "No."))
        failure.message.assert().isEqualTo(
            "The derived command c-1 on shop.cart/t/alice was rejected: cart.forbidden No."
        )
        failure.rejection.code.assert().isEqualTo(FORBIDDEN)
        failure.command.assert().isSameAs(command)
    }
}
