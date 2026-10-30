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
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ModelProvider
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import kotlin.time.Duration.Companion.seconds

/**
 * D372 S18: what compiles but is likely wrong is a warning (`@Blocking` over a publisher, a receipt handler of a
 * command the saga never sends); a handler may take the context its kind's extends; a processor's `@Retry` is
 * inherited from its nearest superclass, and an interface's is refused.
 */
@OptIn(ExperimentalCompilerApi::class)
class HandlerChecksTest {
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

        sealed interface OrderCommand

        @CreateAggregate
        data class PlaceOrder(val id: String) : OrderCommand
        data class CancelOrder(val reason: String) : OrderCommand
        data class ShipOrder(val id: String)
        data class OrderPlaced(val id: String)

        @AggregateRoot
        class Order(val id: String) {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.id)
            fun onCommand(command: CancelOrder): OrderPlaced = OrderPlaced(id)
            fun onCommand(command: ShipOrder): OrderPlaced = OrderPlaced(id)
            fun onSourcing(event: OrderPlaced) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `Blocking on a handler returning a publisher should be a warning`() {
        val handlers = SourceFile.kotlin(
            "Handlers.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.Blocking
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.StateEvent
            import reactor.core.publisher.Mono

            @Projection
            class Board {
                @Blocking
                @OnEvent
                fun onOrder(event: StateEvent<Order>): Mono<Void> = Mono.empty()

                @Blocking
                @OnEvent
                fun onPlain(event: StateEvent<Order>) = Unit
            }

            @Saga
            class Placing {
                @OnEvent
                fun onOrder(event: StateEvent<Order>): CommandRequest<PlaceOrder>? = null

                @Blocking
                @OnRejected
                fun onRejected(rejected: CommandReceipt.Rejected<PlaceOrder>): Mono<CommandRequest<CancelOrder>> =
                    Mono.empty()
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, handlers)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val warning = "is annotated @Blocking and returns a Reactor publisher: it is called on a virtual thread, " +
            "but a publisher should not block"
        result.messages.assert()
            .contains("sample.Board: onOrder $warning")
            .contains("sample.Placing: onRejected $warning")
            .doesNotContain("onPlain $warning")
    }

    @Test
    fun `a receipt handler of a command the saga never sends should be a warning`() {
        val handlers = SourceFile.kotlin(
            "Handlers.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnCommitted
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.annotation.Sends
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.StateEvent

            @Saga
            class Placing {
                @OnEvent
                fun onOrder(event: StateEvent<Order>): CommandRequest<PlaceOrder>? = null

                @OnEvent
                @Sends(derives = [CancelOrder::class])
                fun onCancel(event: StateEvent<Order>): CommandRequest<*>? = null

                // Taken: what onOrder and onCancel derive, and the sealed type they are.
                @OnRejected
                fun onPlaceRejected(rejected: CommandReceipt.Rejected<PlaceOrder>) = Unit

                @OnCommitted
                fun onCancelCommitted(committed: CommandReceipt.Committed<CancelOrder>) = Unit

                @OnCommitted
                fun onAnyCommitted(committed: CommandReceipt.Committed<OrderCommand>) = Unit

                @OnRejected
                fun onAnyRejected(rejected: CommandReceipt.Rejected<*>) = Unit

                // Never run: no handler sends a ShipOrder.
                @OnRejected
                @OnCommitted
                fun onShip(receipt: CommandReceipt<ShipOrder>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, handlers)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert()
            .contains(
                "sample.Placing: handler Placing.onShip takes the receipts of sample.ShipOrder, which no handler of " +
                    "the saga derives or calls: it never runs",
            )
            .doesNotContain("Placing.onPlaceRejected takes")
            .doesNotContain("Placing.onCancelCommitted takes")
            .doesNotContain("Placing.onAnyCommitted takes")
            .doesNotContain("Placing.onAnyRejected takes")
    }

    @Test
    fun `a handler should take the context its kind's extends, and no other`() {
        val handlers = SourceFile.kotlin(
            "Handlers.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.OnSchedule
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.schedule.ScheduleTick
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.StateEvent
            import me.ahoo.wow.api.subscribe.SubscriptionContext

            @Saga
            class Placing {
                @OnEvent
                fun onOrder(event: StateEvent<Order>, context: SubscriptionContext): CommandRequest<PlaceOrder> =
                    CommandRequest.of(PlaceOrder(context.invocationKey), event.ref.id)

                @OnRejected
                fun onRejected(rejected: CommandReceipt.Rejected<PlaceOrder>, context: SubscriptionContext) = Unit

                @OnSchedule(name = "nightly", cron = "0 0 3 * * *")
                fun onSchedule(tick: ScheduleTick, context: SubscriptionContext) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, handlers)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)

        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.SagaContext
            import me.ahoo.wow.api.subscribe.StateEvent

            @Projection
            class Board {
                @OnEvent
                fun onOrder(event: StateEvent<Order>, context: SagaContext) = Unit
            }
            """.trimIndent(),
        )
        val (_, failed) = compileModel("generate", options, model, broken)
        failed.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        failed.messages.assert().contains(
            "handler Board.onOrder takes me.ahoo.wow.api.subscribe.SagaContext: a @Projection handler takes a " +
                "me.ahoo.wow.api.subscribe.SubscriptionContext, or a context it extends",
        )
    }

    @Test
    fun `a processor should run under its nearest superclass's Retry, taken whole`() {
        val processors = SourceFile.kotlin(
            "Processors.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Retry
            import me.ahoo.wow.api.subscribe.StateEvent

            @Retry(maxAttempts = 4, timeout = "2s")
            abstract class Notifying {
                @OnEvent
                open fun onOrder(event: StateEvent<Order>) = Unit
            }

            abstract class Relaying : Notifying()

            @Reactor
            class Mailer : Relaying()

            @Reactor
            @Retry(maxAttempts = 2)
            class Pager : Notifying()
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", options, model, processors)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val services = compilation.workingDir.walkTopDown()
            .first { it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER }
        val loader = URLClassLoader(
            arrayOf(services.parentFile.parentFile.parentFile.toURI().toURL()),
            result.classLoader,
        )
        val bindings = ModelProvider.load(loader).single().processors(Dependencies.NONE).associateBy { it.name }
        bindings.getValue("Mailer.onOrder").retry.assert()
            .isEqualTo(HandlerRetry(maxAttempts = 4, timeout = 2.seconds))
        // Its own @Retry replaces its superclass's: no setting of Notifying's comes with it.
        bindings.getValue("Pager.onOrder").retry.assert().isEqualTo(HandlerRetry(maxAttempts = 2))
    }

    @Test
    fun `an interface's Retry, or an inherited one the runtime cannot take, should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Retry
            import me.ahoo.wow.api.subscribe.StateEvent

            @Retry(maxAttempts = 3)
            interface Notifying {
                @OnEvent
                fun onOrder(event: StateEvent<Order>) = Unit
            }

            @Reactor
            class Mailer : Notifying

            @Retry(maxAttempts = -1)
            abstract class Shaky {
                @OnEvent
                open fun onOrder(event: StateEvent<Order>) = Unit
            }

            @Reactor
            class Pager : Shaky()
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains(
                "processor Mailer implements sample.Notifying, which is annotated @Retry: a processor's budget is " +
                    "its class's or a superclass's, not an interface's",
            )
            .contains(
                "processor Pager's superclass Shaky is annotated @Retry(maxAttempts = -1): a call is attempted at " +
                    "least once",
            )
    }
}
