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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

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

        import me.ahoo.wow.api.annotation.OnCommitted
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnRejected
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.CommandCommitted
        import me.ahoo.wow.api.subscribe.CommandRejected
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import peer.ConfirmPayment
        import peer.PaymentOutcome
        import peer.StockCommand

        @Saga
        class PaymentSaga {
            @OnEvent
            fun inferred(event: StateEvent<Order>, context: SagaContext): Command<PaymentOutcome>? = null

            @OnEvent
            fun nested(event: StateEvent<Order>): List<Command<StockCommand>> = emptyList()

            @OnEvent
            fun local(event: StateEvent<Order>): Command<OrderCommand>? = null

            @OnEvent(derives = [PaymentOutcome::class])
            fun declaredSealed(event: StateEvent<Order>): Command<*>? = null

            @OnEvent(derives = [ConfirmPayment::class, CancelOrder::class])
            fun declaredClasses(event: StateEvent<Order>): List<Command<*>> = emptyList()

            @OnEvent(derives = [PaymentOutcome::class])
            fun narrower(event: StateEvent<Order>): Command<ConfirmPayment>? = null

            @OnEvent
            suspend fun nothing(event: StateEvent<Order>, context: SagaContext) = Unit

            @OnRejected
            fun onRejected(rejected: CommandRejected<ConfirmPayment>) = Unit

            @OnCommitted
            suspend fun onCommitted(committed: CommandCommitted<ConfirmPayment>, context: SagaContext) {
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
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.ConfirmPayment
            import peer.NoCommand
            import peer.StockCommand

            @Saga
            class BrokenSaga {
                @OnEvent(derives = [NoCommand::class, StockCommand.Custom::class])
                fun noCommand(event: StateEvent<Order>): Command<*>? = null

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
    fun `a returned command its derives does not list should fail the compilation`() {
        val undeclared = SourceFile.kotlin(
            "Undeclared.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent
            import peer.ConfirmPayment
            import peer.PaymentOutcome

            @Saga
            class Undeclared {
                @OnEvent(derives = [ConfirmPayment::class])
                fun onEvent(event: StateEvent<Order>): Command<PaymentOutcome>? = null
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
}
