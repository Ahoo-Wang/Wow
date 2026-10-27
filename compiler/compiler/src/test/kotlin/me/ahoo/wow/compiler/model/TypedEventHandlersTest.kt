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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLClassLoader

/**
 * Decision D264: handlers of each event of a type, taking a `StateTypedEvent<S, E>` or the event `E` itself, of a
 * saga, a projection and a reactor; the compiler infers the aggregate from `E` and writes its events into the
 * subscription's source, and the binding calls the function once per such event of a stream, in order.
 */
@OptIn(ExperimentalCompilerApi::class)
class TypedEventHandlersTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private val model = SourceFile.kotlin(
        "Model.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        /** Something an auditor reads, in two aggregates. */
        interface Audited

        sealed interface OrderEvent
        data class OrderPlaced(val item: String) : OrderEvent, Audited
        data class OrderCancelled(val reason: String) : OrderEvent
        data class OrderNoted(val note: String)

        @CreateAggregate
        data class PlaceOrder(val item: String)
        data class CancelOrder(val reason: String)

        @AggregateRoot
        class Order {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.item)
            fun onCommand(command: CancelOrder): List<Any> = listOf(OrderCancelled(command.reason), OrderNoted("n"))
            fun onSourcing(event: OrderPlaced) = Unit
            fun onSourcing(event: OrderCancelled) = Unit
            fun onSourcing(event: OrderNoted) = Unit
        }

        @CreateAggregate
        data class OpenCart(val id: String)
        data class CartOpened(val id: String) : Audited

        @AggregateRoot
        class Cart {
            fun onCommand(command: OpenCart): CartOpened = CartOpened(command.id)
            fun onSourcing(event: CartOpened) = Unit
        }
        """.trimIndent(),
    )

    private val processors = SourceFile.kotlin(
        "Processors.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.SubscriptionContext
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateTypedEvent

        object Seen {
            val calls = mutableListOf<String>()
        }

        @Saga
        class OrderSaga {
            @OnEvent
            fun onPlaced(event: StateTypedEvent<Order, OrderPlaced>, context: SagaContext): Command<CancelOrder> {
                Seen.calls += "saga:${'$'}{event.eventIndex}:${'$'}{event.event.item}:${'$'}{context.invocationKey}"
                return context.command(event.ref, CancelOrder(event.event.item))
            }

            @OnEvent
            suspend fun onCancelled(event: OrderCancelled) {
                Seen.calls += "bare:${'$'}{event.reason}"
            }
        }

        @Projection
        class OrderBoard {
            @OnEvent
            fun onOrder(event: OrderEvent) {
                Seen.calls += "board:${'$'}event"
            }
        }

        @Reactor
        class Auditor {
            @OnEvent
            fun onPlaced(event: StateTypedEvent<Order, Audited>, context: SubscriptionContext) = Unit

            @OnEvent
            fun onOpened(event: StateTypedEvent<Cart, Audited>) = Unit
        }
        """.trimIndent(),
    )

    @Suppress("LongMethod") // The fragment, then the bindings called.
    @Test
    fun `typed handlers should read their aggregate's events and be called once per event`(): Unit = runBlocking {
        val (compilation, result) = compileModel("generate", options, model, processors)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-domain.json" }
        val sources = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().subscriptions.associate { it.name to it.source }
        sources.getValue("OrderSaga.onPlaced").events.assert().containsExactly("OrderPlaced")
        sources.getValue("OrderSaga.onPlaced").aggregates.assert().containsExactly("order")
        sources.getValue("OrderSaga.onCancelled").events.assert().containsExactly("OrderCancelled")
        // A sealed event type stands for its events; S names the aggregate of a type several aggregates' events are.
        sources.getValue("OrderBoard.onOrder").events.assert().containsExactly("OrderCancelled", "OrderPlaced")
        sources.getValue("Auditor.onOpened").aggregates.assert().containsExactly("cart")
        sources.getValue("Auditor.onOpened").events.assert().containsExactly("CartOpened")

        val services = compilation.workingDir.walkTopDown().first {
            it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER
        }
        val loader =
            URLClassLoader(arrayOf(services.parentFile.parentFile.parentFile.toURI().toURL()), result.classLoader)
        val bindings = ModelProvider.load(loader).single().processors(Dependencies.NONE).associateBy { it.name }
        val placed = loader.loadClass("sample.OrderPlaced")
        val cancelled = loader.loadClass("sample.OrderCancelled")
        val noted = loader.loadClass("sample.OrderNoted")
        val events = listOf(
            placed.getConstructor(String::class.java).newInstance("mug"),
            noted.getConstructor(String::class.java).newInstance("n"),
            placed.getConstructor(String::class.java).newInstance("hat"),
            cancelled.getConstructor(String::class.java).newInstance("late"),
        )
        val stream = StateEvent(
            ref = AggregateType("shop", "order").ref("t", "o-1"),
            version = 2,
            commandId = "c",
            requestId = "r",
            commandType = "shop.order.PlaceOrder",
            commitTime = 1,
            events = events,
            state = loader.loadClass("sample.Order").getConstructor().newInstance(),
        )
        val seen = loader.loadClass("sample.Seen").getField("INSTANCE").get(null).let {
            it.javaClass.getMethod("getCalls").invoke(it) as MutableList<*>
        }
        val saga = bindings.getValue("OrderSaga.onPlaced")
        saga.eventType.assert().isEqualTo(placed)
        val derived = saga.invoke(stream, contextOf(FunctionKind.SAGA, saga.name, stream))
        // Two calls, one per OrderPlaced, in order; one context: the commands are the stream's 0th and 1st.
        derived.map { it.body.toString() }.assert().containsExactly(
            "CancelOrder(reason=mug)",
            "CancelOrder(reason=hat)"
        )
        derived.map { it.id }.distinct().assert().hasSize(2)
        val again = saga.invoke(stream, contextOf(FunctionKind.SAGA, saga.name, stream))
        again.map { it.id }.assert().isEqualTo(derived.map { it.id })
        seen.take(2).assert().containsExactly(
            "saga:0:mug:OrderSaga.onPlaced:shop.order/t/o-1@2:0",
            "saga:2:hat:OrderSaga.onPlaced:shop.order/t/o-1@2:2",
        )
        seen.clear()
        val bare = bindings.getValue("OrderSaga.onCancelled")
        bare.invoke(stream, contextOf(FunctionKind.SAGA, bare.name, stream)).assert().isEmpty()
        val board = bindings.getValue("OrderBoard.onOrder")
        board.invoke(stream, contextOf(FunctionKind.PROJECT, board.name, stream))
        seen.assert().containsExactly(
            "bare:late",
            "board:OrderPlaced(item=mug)",
            "board:OrderPlaced(item=hat)",
            "board:OrderCancelled(reason=late)",
        )
        // A stream without one of its events is skipped, its state undecoded.
        saga.skips(listOf(noted.getConstructor(String::class.java).newInstance("n"))).assert().isTrue()
        saga.skips(events).assert().isFalse()
        bindings.getValue("Auditor.onPlaced").eventType!!.name.assert().isEqualTo("sample.Audited")
    }

    @Test
    fun `an event no aggregate or several aggregates have, or with another context's source, should fail`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            data class Elsewhere(val id: String)

            @Projection
            class Broken {
                @OnEvent
                fun unknown(event: Elsewhere) = Unit

                @OnEvent
                fun several(event: Audited) = Unit

                @OnEvent
                fun wrongState(event: StateTypedEvent<Cart, OrderPlaced>) = Unit

                @OnEvent
                fun noType(event: StateTypedEvent<Order, *>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("handler Broken.unknown takes sample.Elsewhere, which is no event of an aggregate of this module")
            .contains("handler Broken.several takes sample.Audited, an event of the aggregates of the states")
            .contains(
                "handler Broken.wrongState takes sample.OrderPlaced, which is no event of an aggregate of this module whose state is sample.Cart"
            )
            .contains("handler Broken.noType takes a StateTypedEvent of no event type")
    }

    @Suppress("LongMethod") // The peer api, the processors and the fragment, whole.
    @Test
    fun `a handler of another context's events should take them by its api's classes`() {
        val billingApi = compileLibrary(
            SourceFile.kotlin(
                "BillingEvents.kt",
                """
                package billing

                import me.ahoo.wow.api.annotation.Name

                sealed interface PaymentEvent

                @Name("Settled")
                data class Paid(val amount: Int) : PaymentEvent
                data class Declined(val reason: String) : PaymentEvent

                interface Loose
                """.trimIndent(),
            ),
        )
        val peers = SourceFile.kotlin(
            "Peers.kt",
            """
            package sample

            import billing.Declined
            import billing.Loose
            import billing.Paid
            import billing.PaymentEvent
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            data class PaymentView(val amount: Int)

            @Projection
            class Ledger {
                @OnEvent(aggregates = ["billing.payment"])
                fun onPaid(event: StateTypedEvent<PaymentView, Paid>) = Unit

                @OnEvent(aggregates = ["billing.payment"])
                fun onAny(event: PaymentEvent) = Unit

                @OnEvent(aggregates = ["billing.payment"])
                fun onDeclined(event: StateTypedEvent<PaymentView?, Declined>) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModelAgainst("generate", options, listOf(billingApi), model, peers)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-domain.json" }
        val sources = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().subscriptions.associate { it.name to it.source }
        sources.getValue("Ledger.onPaid").events.assert().containsExactly("Settled")
        sources.getValue("Ledger.onPaid").context.assert().isEqualTo("billing")
        sources.getValue("Ledger.onPaid").nullableState.assert().isFalse()
        sources.getValue("Ledger.onAny").events.assert().containsExactly("Declined", "Settled")
        sources.getValue("Ledger.onAny").nullableState.assert().isTrue()
        sources.getValue("Ledger.onDeclined").nullableState.assert().isTrue()
        compilation.workingDir.walkTopDown().first { it.isFile && it.name == "LedgerSubscriptions.kt" }.readText()
            .assert().contains("eventType = billing.Paid::class.java")
            .contains("aggregates = listOf(\"billing.payment\")")

        val loose = SourceFile.kotlin(
            "Loose.kt",
            """
            package sample

            import billing.Loose
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection

            @Projection
            class Broken {
                @OnEvent(aggregates = ["billing.payment"])
                fun onLoose(event: Loose) = Unit
            }
            """.trimIndent(),
        )
        val (_, broken) = compileModelAgainst("generate", options, listOf(billingApi), model, loose)
        broken.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        broken.messages.assert().contains(
            "handler Broken.onLoose takes billing.Loose of the aggregate @OnEvent(aggregates) names: name an event " +
                "class of its context's api, or a sealed type of them",
        )
    }
}
