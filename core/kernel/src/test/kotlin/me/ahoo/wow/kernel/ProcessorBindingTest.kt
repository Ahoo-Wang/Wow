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
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import org.junit.jupiter.api.Test

/** Decision D149: the runtime-agnostic pieces a generated processor binding builds on. */
class ProcessorBindingTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")
    private val event = StateEvent(
        order.ref("t", "o-1"),
        3,
        "c-3",
        "r-3",
        "shop.order.Place",
        1L,
        listOf("placed"),
        CartState("o-1"),
        scope = Scope("alice", "space-a"),
        causation = Causation("root", "shop.order/t/o-0@1"),
        operator = Operator("alice", "t"),
    )

    @Test
    fun `a handler of another context's stream should start from the beginning unless it declares a start`() {
        // Decision D194: a service that joins later still gets what the other context published before.
        val sourced = ProcessorBinding.saga<Any>("Stock", "onOrder", null, aggregates = ORDERS) { _, _ -> emptyList() }
        sourced.startIn("inventory").assert().isEqualTo(StartFrom.BEGINNING)
        // Its own context's aggregates, named, are read from the log: a saga starts at its end.
        sourced.startIn("shop").assert().isEqualTo(StartFrom.END)
        val end = StartFrom.END
        val mail = ProcessorBinding.react<Any>("Mail", "on", null, startFrom = end, aggregates = ORDERS) { _, _ -> }
        mail.startIn("inventory").assert().isEqualTo(StartFrom.END)
    }

    @Test
    fun `a binding should be named processor dot function, of its kind, starting where its kind starts`() {
        val project = ProcessorBinding.project("View", "onEvent", CartState::class.java) { _, _ -> }
        project.name.assert().isEqualTo("View.onEvent")
        project.processor.assert().isEqualTo("View")
        project.function.assert().isEqualTo("onEvent")
        project.kind.assert().isEqualTo(FunctionKind.PROJECT)
        project.stateType.assert().isEqualTo(CartState::class.java)
        project.startFrom.assert().isNull()
        project.startIn("shop").assert().isEqualTo(StartFrom.BEGINNING)
        project.blocking.assert().isFalse()
        project.toString().assert().isEqualTo("ProcessorBinding(View.onEvent, PROJECT)")
        val react = ProcessorBinding.react<Any>("Mail", "send", null, blocking = true) { _, _ -> }
        react.kind.assert().isEqualTo(FunctionKind.REACT)
        react.startFrom.assert().isNull()
        react.startIn("shop").assert().isEqualTo(StartFrom.END)
        react.blocking.assert().isTrue()
        react.stateType.assert().isNull()
        val saga = ProcessorBinding.saga<Any>("Saga", "derive", null) { _, _ -> emptyList() }
        saga.kind.assert().isEqualTo(FunctionKind.SAGA)
        saga.startIn("shop").assert().isEqualTo(StartFrom.END)
        ProcessorBinding.saga<Any>("Saga", "replay", null, startFrom = StartFrom.BEGINNING) { _, _ -> emptyList() }
            .startIn("shop").assert().isEqualTo(StartFrom.BEGINNING)
        assertThrownBy<IllegalArgumentException> { ProcessorBinding.project<Any>(" ", "f", null) { _, _ -> } }
        assertThrownBy<IllegalArgumentException> { ProcessorBinding.project<Any>("p", " ", null) { _, _ -> } }
    }

    @Test
    fun `a binding should name the aggregates it reads, of one context, and whether its state is nullable`() {
        val local = ProcessorBinding.project("View", "onEvent", CartState::class.java) { _, _ -> }
        local.aggregates.assert().isEmpty()
        local.sourceContext.assert().isNull()
        local.isSourced("shop").assert().isFalse()
        local.nullableState.assert().isFalse()
        // StateEvent<*> takes any state, null included (decision D140).
        ProcessorBinding.react<Any>("Mail", "send", null) { _, _ -> }.nullableState.assert().isTrue()
        val sourced = ProcessorBinding.saga<CartState?>(
            "Saga",
            "onCart",
            CartState::class.java,
            aggregates = listOf("shop.cart"),
            nullableState = true,
        ) { _, _ -> emptyList() }
        sourced.sourceContext.assert().isEqualTo("shop")
        sourced.isSourced("shop").assert().isFalse()
        sourced.isSourced("ordering").assert().isTrue()
        sourced.nullableState.assert().isTrue()
        assertThrownBy<IllegalArgumentException> {
            ProcessorBinding.project<Any>("p", "f", null, aggregates = listOf("cart")) { _, _ -> }
        }
        assertThrownBy<IllegalArgumentException> {
            ProcessorBinding.project<Any>("p", "f", null, aggregates = listOf("shop.cart", "sales.order")) { _, _ -> }
        }
    }

    @Test
    fun `invoking a binding should call its handler with the context of its kind`(): Unit = runBlocking {
        val seen = mutableListOf<Any>()
        val derived = Command("d", "d", cart.ref("t", "alice"), "x")
        val bindings = listOf(
            ProcessorBinding.project("View", "onEvent", CartState::class.java) { e, c ->
                seen += c
                seen += e.state.id
            },
            ProcessorBinding.react("Mail", "send", CartState::class.java) { _, c -> seen += c },
            ProcessorBinding.saga("Saga", "derive", CartState::class.java) { _, c ->
                seen += c
                listOf(derived)
            },
        )
        val results = bindings.map { it.invoke(event, contextOf(it.kind, it.name, event)) }
        results.assert().isEqualTo(listOf(emptyList(), emptyList(), listOf(derived)))
        (seen[0] as ProjectContext).executionId.assert().isEqualTo(ExecutionId("View.onEvent", event.ref, 3))
        seen[1].assert().isEqualTo("o-1")
        (seen[2] as ReactContext).executionId.subscription.assert().isEqualTo("Mail.send")
        (seen[3] as SagaContext).executionId.subscription.assert().isEqualTo("Saga.derive")
        assertThrownBy<IllegalArgumentException> { contextOf(FunctionKind.SNAPSHOT, "snapshot", event) }
    }

    @Test
    fun `a retry whose reads answer in another order should derive each target's request again`() {
        val execution = ExecutionId("CartSaga.onOrderPlaced", event.ref, event.version)

        // The handler derives one command per target a read returns: the read answers in another order on the retry.
        fun derive(targets: List<String>) = ExecutionSagaContext(execution, event).let { context ->
            targets.associateWith { context.command(cart.ref("t", it), "restock").requestId }
        }
        val first = derive(listOf("alice", "bob", "carol"))
        val retry = derive(listOf("carol", "alice", "bob"))
        // Each target is asked by its own request again, so the kernel answers each as its own duplicate.
        retry.assert().isEqualTo(first)
        first.values.distinct().assert().hasSize(3)
        // A retry that derives one target fewer keeps the others' requests.
        derive(listOf("bob", "carol")).assert().isEqualTo(first - "alice")
    }

    @Test
    fun `two commands of one type to one target in one execution should be two requests`() {
        val execution = ExecutionId("CartSaga.onOrderPlaced", event.ref, event.version)
        fun derive() = ExecutionSagaContext(execution, event).let { context ->
            listOf(
                context.command(cart.ref("t", "alice"), "add"),
                context.command(cart.ref("t", "alice"), "add"),
                context.command(cart.ref("t", "alice"), 1),
                context.command(cart, "new"),
                context.command(cart, "new"),
            ).map { it.requestId }
        }
        val ids = derive()
        ids.distinct().assert().hasSize(5)
        derive().assert().isEqualTo(ids)
        ids[1].assert()
            .isEqualTo(ExecutionSagaContext.derivedId(execution, cart.ref("t", "alice"), String::class.java, 1))
        // Two creations without an id: two requests, each of which keeps the aggregate it created on a retry.
        ids[4].assert().isEqualTo(
            ExecutionSagaContext.derivedId(execution, cart.unassigned("t"), String::class.java, 1)
        )
    }

    @Test
    fun `a saga context should derive deterministic requests linked to the commit`() {
        val execution = ExecutionId("CartSaga.onOrderPlaced", event.ref, event.version)
        val first = ExecutionSagaContext(execution, event)
        val again = ExecutionSagaContext(execution, event)
        val commands = listOf(first.command(cart.ref("t", "alice"), "a"), first.command(cart.ref("t", "bob"), "x"))
        val replayed = listOf(again.command(cart.ref("t", "alice"), "a"), again.command(cart.ref("t", "bob"), "x"))
        commands.map { it.requestId }.assert().isEqualTo(replayed.map { it.requestId })
        commands.map { it.id }.distinct().assert().hasSize(2)
        commands.forEach {
            it.id.assert().isEqualTo(it.requestId)
            it.causation.assert().isEqualTo(Causation("root", "shop.order/t/o-1@3"))
            // The root command's operator, not the saga's (decision D163), and the commit's scope (decision D189).
            it.operator.assert().isEqualTo(Operator("alice", "t"))
            it.scope.assert().isEqualTo(Scope("alice", "space-a"))
        }
        val alice = cart.ref("t", "alice")
        commands[0].id.assert().isEqualTo(ExecutionSagaContext.derivedId(execution, alice, String::class.java))
        // A creation that leaves its id to the target's admission (decision D191), in the commit's tenant.
        val creation = first.command(cart, "new")
        creation.ref.assert().isEqualTo(cart.unassigned("t"))
        creation.id.assert().isEqualTo(
            ExecutionSagaContext.derivedId(execution, cart.unassigned("t"), String::class.java)
        )
        ExecutionSagaContext.derivedId(
            execution.copy(version = 4),
            alice,
            String::class.java
        ).assert().isNotEqualTo(commands[0].id)
        ExecutionSagaContext.derivedId(execution.copy(subscription = "Other.saga"), alice, String::class.java)
            .assert().isNotEqualTo(commands[0].id)
        assertThrownBy<IllegalArgumentException> {
            ExecutionSagaContext.derivedId(execution, alice, String::class.java, -1)
        }
        assertThrownBy<IllegalArgumentException> { ExecutionSagaContext(execution.copy(version = 2), event) }
        // A command a user sends directly is its own root: its derived commands' root is that command.
        val root = event.copyWith(causation = null)
        ExecutionSagaContext(execution, root).command(cart.ref("t", "alice"), "x").causation!!.correlationId
            .assert().isEqualTo("c-3")
    }

    @Test
    fun `a saga's command should inherit only the commit's request facts that propagate`() {
        val execution = ExecutionId("CartSaga.onOrderPlaced", event.ref, event.version)
        val requested = event.copyWith(
            headers = mapOf(
                RequestFacts.APP_ID to "console",
                RequestFacts.REMOTE_IP to "10.0.0.7",
                RequestFacts.USER_AGENT to "shop-app/1.0",
                "X-Trace" to "t-1",
            ),
        )
        ExecutionSagaContext(execution, requested).command(cart.ref("t", "alice"), "x").headers.assert()
            .isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
        ExecutionSagaContext(execution, event.copyWith(headers = emptyMap())).command(cart.ref("t", "alice"), "x")
            .headers.assert().isEmpty()
    }

    @Test
    fun `what a saga returns should be read as the commands it derives`() {
        val command = Command("c", "r", cart.ref("t", "alice"), "x")
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
    fun `a model provider should list no processors unless it declares some`() {
        val provider = object : ModelProvider {
            override val context: String = "shop"
            override val binding: Int = ModelProvider.BINDING
            override val fragment: String = "META-INF/wow/manifest/shop.json"

            override fun models(dependencies: Dependencies): List<AggregateModel<*>> = emptyList()
        }
        provider.processors(Dependencies.NONE).assert().isEmpty()
    }
}

private val ORDERS = listOf("shop.order")
