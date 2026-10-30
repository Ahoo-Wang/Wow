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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URLClassLoader

/**
 * Decision D259 (DX-1 ③ and ⑥, DX gaps G26 and G29): `@OnEvent(derives)` names command classes, a sealed type standing
 * for its concrete subclasses, of the module or of a peer's api (a library); a returned `Command<S>` of a sealed `S`
 * derives the subclasses of `S`; a saga's handler, and its `@OnRejected` and `@OnCommitted` handlers, may return `Unit`,
 * deriving none.
 */
@OptIn(ExperimentalCompilerApi::class)
class DerivedCommandsTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    /** A peer context's api: its commands, read by the processor from the class path. */
    private val peerApi by lazy {
        compileLibrary(
            SourceFile.kotlin(
                "PaymentCommands.kt",
                """
                package peer

                sealed interface PaymentOutcome
                data class ConfirmPayment(val id: String) : PaymentOutcome
                data class FailPayment(val reason: String) : PaymentOutcome

                sealed class StockCommand {
                    data class Reserve(val quantity: Int) : StockCommand()
                    data class Release(val quantity: Int) : StockCommand()
                    sealed class Adjust : StockCommand() {
                        data class Up(val quantity: Int) : Adjust()
                    }
                    abstract class Custom : StockCommand()
                }

                sealed interface NoCommand

                data object Refresh

                typealias Pay = ConfirmPayment
                """.trimIndent(),
            ),
        )
    }

    private val model = SourceFile.kotlin(
        "Model.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        sealed interface OrderCommand

        @CreateAggregate
        data class PlaceOrder(val id: String) : OrderCommand
        data class CancelOrder(val reason: String) : OrderCommand
        data class OrderPlaced(val id: String)
        data class OrderCancelled(val reason: String)

        @AggregateRoot
        class Order(val id: String) {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.id)
            fun onCommand(command: CancelOrder): OrderCancelled = OrderCancelled(command.reason)
            fun onSourcing(event: OrderPlaced) = Unit
            fun onSourcing(event: OrderCancelled) = Unit
        }
        """.trimIndent(),
    )

    private val saga = SourceFile.kotlin(
        "PaymentSaga.kt",
        """
        package sample

        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.emptyFlow
        import me.ahoo.wow.api.annotation.OnCommitted
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnRejected
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.CommandReceipt
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import peer.ConfirmPayment
        import peer.PaymentOutcome
        import peer.StockCommand

        @Saga
        class PaymentSaga {
            @OnEvent
            fun inferred(event: StateEvent<Order>, context: SagaContext): CommandRequest<PaymentOutcome>? = null

            @OnEvent
            fun nested(event: StateEvent<Order>): List<CommandRequest<StockCommand>> = emptyList()

            @OnEvent
            fun local(event: StateEvent<Order>): CommandRequest<OrderCommand>? = null

            @OnEvent(derives = [PaymentOutcome::class])
            fun declaredSealed(event: StateEvent<Order>): CommandRequest<*>? = null

            @OnEvent(derives = [ConfirmPayment::class, CancelOrder::class])
            fun declaredClasses(event: StateEvent<Order>): List<CommandRequest<*>> = emptyList()

            @OnEvent(derives = [PaymentOutcome::class])
            fun narrower(event: StateEvent<Order>): CommandRequest<ConfirmPayment>? = null

            @OnEvent
            fun sequence(event: StateEvent<Order>): Sequence<CommandRequest<ConfirmPayment>> = emptySequence()

            @OnEvent
            fun flow(event: StateEvent<Order>): Flow<CommandRequest<CancelOrder>> = emptyFlow()

            @OnEvent
            fun unknown(event: StateEvent<Order>): List<CommandRequest<*>> = emptyList()

            @OnEvent
            suspend fun nothing(event: StateEvent<Order>, context: SagaContext) = Unit

            @OnRejected
            fun onRejected(rejected: CommandReceipt.Rejected<ConfirmPayment>) = Unit

            @OnCommitted
            suspend fun onCommitted(committed: CommandReceipt.Committed<ConfirmPayment>, context: SagaContext) {
                context.executionId
            }
        }
        """.trimIndent(),
    )

    private fun ref(type: String) = Manifest.schemaRef(type)

    @Test
    fun `derives should name classes, sealed types should stand for their subclasses and Unit for none`() {
        val (compilation, result) = compileModelAgainst("generate", options, listOf(peerApi), model, saga)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-domain.json" }
        val subscriptions = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().subscriptions.associateBy { it.name.substringAfter('.') }
        val payment = listOf(ref("peer.ConfirmPayment"), ref("peer.FailPayment"))

        subscriptions.getValue("inferred").derives.assert().isEqualTo(payment)
        // Nested sealed types are read through; an abstract class that is not sealed is no command.
        subscriptions.getValue("nested").derives.assert().isEqualTo(
            listOf(
                ref("peer.StockCommand\$Adjust\$Up"),
                ref("peer.StockCommand\$Release"),
                ref("peer.StockCommand\$Reserve"),
            ),
        )
        subscriptions.getValue("local").derives.assert()
            .isEqualTo(listOf("shop.order.CancelOrder", "shop.order.PlaceOrder"))
        subscriptions.getValue("declaredSealed").derives.assert().isEqualTo(payment)
        subscriptions.getValue("declaredClasses").derives.assert()
            .isEqualTo(listOf(ref("peer.ConfirmPayment"), "shop.order.CancelOrder"))
        // A handler may derive fewer commands than it declares.
        subscriptions.getValue("narrower").derives.assert().isEqualTo(payment)
        subscriptions.getValue("sequence").derives.assert().isEqualTo(listOf(ref("peer.ConfirmPayment")))
        subscriptions.getValue("flow").derives.assert().isEqualTo(listOf("shop.order.CancelOrder"))
        // A return type that names no class leaves the Manifest short of what the handler derives: a warning.
        subscriptions.getValue("unknown").derives.assert().isNull()
        result.messages.assert().contains(
            "handler PaymentSaga.unknown returns command requests of no named class and declares no @OnEvent(derives)",
        )
        result.messages.assert().doesNotContain("handler PaymentSaga.inferred returns command requests of no named")
        subscriptions.getValue("nothing").derives.assert().isEqualTo(emptyList<String>())
        subscriptions.getValue("onRejected").derives.assert().isEqualTo(emptyList<String>())
        subscriptions.getValue("onRejected").rejected.assert().isEqualTo(listOf(ref("peer.ConfirmPayment")))
        subscriptions.getValue("onCommitted").derives.assert().isEqualTo(emptyList<String>())
        subscriptions.getValue("onCommitted").committed.assert().isEqualTo(listOf(ref("peer.ConfirmPayment")))
    }

    @Test
    fun `derives naming no command class, or declared by a handler returning Unit, should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.ConfirmPayment
            import peer.NoCommand
            import peer.StockCommand

            @Saga
            class BrokenSaga {
                @OnEvent(derives = [NoCommand::class, StockCommand.Custom::class])
                fun noCommand(event: StateEvent<Order>): CommandRequest<*>? = null

                @OnEvent(derives = [ConfirmPayment::class])
                fun nothing(event: StateEvent<Order>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModelAgainst("generate", options, listOf(peerApi), model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("handler BrokenSaga.noCommand derives peer.NoCommand, which is no command class")
            .contains("handler BrokenSaga.noCommand derives peer.StockCommand.Custom, which is no command class")
            .contains(
                "handler BrokenSaga.nothing returns Unit, so it derives no commands, but declares @OnEvent(derives)"
            )
    }

    @Test
    fun `objects and type aliases should name commands, and a sealed type without subclasses none`() {
        val extras = SourceFile.kotlin(
            "Extras.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.NoCommand
            import peer.Pay
            import peer.Refresh

            @Saga
            class Extras {
                @OnEvent(derives = [Refresh::class, Pay::class])
                fun objects(event: StateEvent<Order>): CommandRequest<*>? = null

                @OnEvent
                fun none(event: StateEvent<Order>): CommandRequest<NoCommand>? = null
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModelAgainst("generate", options, listOf(peerApi), model, extras)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-domain.json" }
        val subscriptions = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().subscriptions.associateBy { it.name.substringAfter('.') }
        subscriptions.getValue("objects").derives.assert()
            .isEqualTo(listOf(ref("peer.ConfirmPayment"), ref("peer.Refresh")))
        // A sealed type no class implements names no command: the return type does not say.
        subscriptions.getValue("none").derives.assert().isNull()
    }

    @Test
    fun `derives on a processor of no kind should fail with its kind`() {
        val kindless = SourceFile.kotlin(
            "Kindless.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.ConfirmPayment

            class Kindless {
                @OnEvent(derives = [ConfirmPayment::class])
                fun onEvent(event: StateEvent<Order>): CommandRequest<ConfirmPayment>? = null
            }
            """.trimIndent(),
        )
        val (_, result) = compileModelAgainst("generate", options, listOf(peerApi), model, kindless)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("processor Kindless is none of @Projection, @Reactor, @Saga")
    }

    @Test
    fun `a returned command its derives does not list should fail the compilation`() {
        val undeclared = SourceFile.kotlin(
            "Undeclared.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.ConfirmPayment
            import peer.PaymentOutcome

            @Saga
            class Undeclared {
                @OnEvent(derives = [ConfirmPayment::class])
                fun onEvent(event: StateEvent<Order>): CommandRequest<PaymentOutcome>? = null
            }
            """.trimIndent(),
        )
        val (_, result) = compileModelAgainst("generate", options, listOf(peerApi), model, undeclared)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "handler Undeclared.onEvent of sample.Undeclared returns peer.FailPayment, which its @OnEvent(derives) " +
                "does not list",
        )
    }

    @Test
    fun `calls should be listed in the fragment and bound as the only classes the handler may call`() {
        val calling = SourceFile.kotlin(
            "CheckoutSaga.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.subscribe.SagaContext
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.ConfirmPayment
            import peer.StockCommand

            @Saga
            class CheckoutSaga {
                @OnEvent(calls = [ConfirmPayment::class, StockCommand.Adjust::class])
                suspend fun onPlaced(event: StateEvent<Order>, context: SagaContext) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModelAgainst("generate", options, listOf(peerApi), model, calling)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown().first { it.isFile && it.name == "shop-domain.json" }
        val subscription = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().subscriptions.single()
        subscription.calls.assert()
            .isEqualTo(listOf(ref("peer.ConfirmPayment"), ref("peer.StockCommand\$Adjust\$Up")))
        // A call is no derived command, and needs no topic.
        subscription.derives.assert().isEqualTo(emptyList<String>())
        val services = compilation.workingDir.walkTopDown().first { it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER }
        val loader = URLClassLoader(
            arrayOf(services.parentFile.parentFile.parentFile.toURI().toURL()),
            result.classLoader
        )
        val binding = ModelProvider.load(loader).single().processors(Dependencies.NONE).single()
        binding.calls.map { it.name }.assert().containsExactly("peer.ConfirmPayment", "peer.StockCommand\$Adjust\$Up")
    }

    @Test
    fun `calls of a non-saga handler or of the module's own commands should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.ConfirmPayment

            @Reactor
            class Notifier {
                @OnEvent(calls = [ConfirmPayment::class])
                fun onEvent(event: StateEvent<Order>) = Unit
            }

            @Saga
            class SelfCalling {
                @OnEvent(calls = [Any::class])
                fun onEvent(event: StateEvent<Order>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModelAgainst("generate", options, listOf(peerApi), model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains(
                "handler Notifier.onEvent declares @OnEvent(calls), but a @Reactor handler calls no other context"
            )
            .contains("handler SelfCalling.onEvent calls kotlin.Any, which is no command class")

        val own = SourceFile.kotlin(
            "Own.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.subscribe.StateEvent

            @Saga
            class SelfCalling {
                @OnEvent(calls = [CancelOrder::class])
                fun onEvent(event: StateEvent<Order>) = Unit
            }
            """.trimIndent(),
        )
        val (_, ownResult) = compileModelAgainst("generate", options, listOf(peerApi), model, own)
        ownResult.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        ownResult.messages.assert().contains(
            "handler SelfCalling.onEvent of sample.SelfCalling calls shop.order.CancelOrder, a command of its own " +
                "context: derive it (return it) instead of calling it",
        )
    }
}
