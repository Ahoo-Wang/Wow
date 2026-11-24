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

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

/**
 * Deep review r1 (compiler): what the processor makes of a subscription handler's shape, against what the kernel and
 * the runtime expect of its binding. Each test states the expected behaviour and fails on `ac58da98ac`.
 */
@OptIn(ExperimentalCompilerApi::class)
class R1SubscriptionBindingsTest {
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

        data class OrderPlaced(val item: String)
        data class OrderPaid(val amount: Int)

        @CreateAggregate
        data class PlaceOrder(val item: String)
        data class ConfirmPayment(val amount: Int)

        @AggregateRoot
        class Order {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.item)
            fun onCommand(command: ConfirmPayment): OrderPaid = OrderPaid(command.amount)
            fun onSourcing(event: OrderPlaced) = Unit
            fun onSourcing(event: OrderPaid) = Unit
        }
        """.trimIndent(),
    )

    private fun processors(body: String) = SourceFile.kotlin("Processors.kt", "package sample\n\n" + body.trimIndent())

    private fun bindings(compilation: KotlinCompilation, result: JvmCompilationResult): List<ProcessorBinding<*>> {
        val services = compilation.workingDir.walkTopDown().first {
            it.isFile && it.name == ModelProviderSource.MODEL_PROVIDER
        }
        val loader =
            URLClassLoader(arrayOf(services.parentFile.parentFile.parentFile.toURI().toURL()), result.classLoader)
        return ModelProvider.load(loader).single().processors(Dependencies.NONE)
    }

    /**
     * `sameNames` (`SubscriptionResolver.kt:232`) groups a processor's handlers by function name, though its message
     * says each would be the subscription `processor.<name>`, and a handler's subscription name is its `@Name`. Two
     * overloads that `@Name` keeps apart are two subscriptions, and the generated calls resolve by argument type.
     */
    @Test
    fun `two overloads that a Name keeps apart should be two subscriptions`() {
        val source = processors(
            """
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            @Projection
            class Board {
                @OnEvent
                fun on(event: StateTypedEvent<Order, OrderPlaced>) = Unit

                @OnEvent
                @Name("onPaid")
                fun on(event: StateTypedEvent<Order, OrderPaid>) = Unit
            }
            """,
        )
        val (compilation, result) = compileModel("generate", options, model, source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        bindings(compilation, result).map { it.name }.assert().containsExactlyInAnyOrder("Board.on", "Board.onPaid")
    }

    /**
     * The other side of `sameNames`: two functions that one `@Name` joins are one subscription name, which the
     * compiler lets through. The node refuses it only when it assembles its bindings (`SubscriptionBindings.kt:103`).
     */
    @Test
    fun `two handlers that one Name joins should fail the compilation`() {
        val source = processors(
            """
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            @Projection
            class Board {
                @OnEvent
                @Name("sync")
                fun placed(event: StateTypedEvent<Order, OrderPlaced>) = Unit

                @OnEvent
                @Name("sync")
                fun paid(event: StateTypedEvent<Order, OrderPaid>) = Unit
            }
            """,
        )
        val (_, result) = compileModel("generate", options, model, source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("two handlers named sync")
    }

    /**
     * `ReceiptHandlerResolver.commandOf` (`ReceiptHandlerResolver.kt:167`) reads the receipt's command as a class
     * declaration and takes anything else for `*`. A `typealias` of the command is a `KSTypeAlias`, so the handler
     * is bound as one for every command: the generated `Rejected<kotlin.Any>` lambda does not compile against the
     * handler's `Rejected<ConfirmPayment>`. The rest of the compiler reads aliases through (`@Sends`, `@Retry`).
     */
    @Test
    fun `a rejection handler whose command is a typealias should be bound to that command`() {
        val source = processors(
            """
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            typealias Pay = ConfirmPayment

            @Saga
            class PaymentSaga {
                @OnEvent
                fun onPlaced(event: StateTypedEvent<Order, OrderPlaced>): CommandRequest<ConfirmPayment> =
                    CommandRequest.to(event.ref, ConfirmPayment(1))

                @OnRejected
                fun onPayRejected(rejected: CommandReceipt.Rejected<Pay>) = Unit
            }
            """,
        )
        val (compilation, result) = compileModel("generate", options, model, source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val saga = bindings(compilation, result).single()
        val confirm = result.classLoader.loadClass("sample.ConfirmPayment")
        saga.rejectionHandlerOf(confirm)!!.name.assert().isEqualTo("PaymentSaga.onPayRejected")
    }

    /**
     * The aggregate side refuses a Kotlin `protected` handler, naming it (`AggregateAnalyzer.kt:504`); the subscription
     * side checks `isPrivate()` alone (`SubscriptionResolver.kt:383`), so the generated top-level binding calls a
     * protected function and the generated file fails to compile, with no diagnostic that names the handler.
     */
    @Test
    fun `a protected subscription handler should be refused, naming it`() {
        val source = processors(
            """
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            abstract class Base {
                @OnEvent
                protected open fun onPlaced(event: StateTypedEvent<Order, OrderPlaced>) = Unit
            }

            @Projection
            class Board : Base()
            """,
        )
        val (_, result) = compileModel("generate", options, model, source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("onPlaced is protected")
    }

    /**
     * Processors are found by their `@OnEvent`, `@OnRejected` and `@OnCommitted` functions
     * (`ModelSymbolProcessor.kt:373`), and the "none of @Projection, @Reactor, @Saga" refusal reaches only them. A
     * class whose only handler is an `@OnSchedule` job, without `@Saga`, compiles with no binding: the job never runs.
     */
    @Test
    fun `a scheduled job in a class that is no saga should fail the compilation`() {
        val source = processors(
            """
            import me.ahoo.wow.api.annotation.OnSchedule
            import me.ahoo.wow.api.schedule.ScheduleTick

            object Nightly {
                @OnSchedule(name = "sweep", cron = "@daily")
                fun sweep(tick: ScheduleTick) = Unit
            }
            """,
        )
        val (compilation, result) = compileModel("generate", options, model, source)
        val bound = if (result.exitCode == KotlinCompilation.ExitCode.OK) {
            bindings(compilation, result).map { it.name }
        } else {
            emptyList()
        }
        result.exitCode.assert().withFailMessage { "compiled, bindings $bound" }
            .isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("Nightly")
    }

    /**
     * A handler of a whole stream refuses a state with type arguments (`SubscriptionResolver.kt:436`), but a typed
     * handler's state is taken by its declaration's name alone (`TypedEventResolver.kt:71`). A peer view such as
     * `Map<String, Any?>` becomes `ProcessorBinding.project<kotlin.collections.Map>(…)`, which does not compile, with
     * no diagnostic that names the handler.
     */
    @Test
    fun `a typed handler whose state takes type arguments should be refused, naming it`() {
        val billing = compileLibrary(
            SourceFile.kotlin(
                "BillingEvents.kt",
                """
                package billing

                data class Paid(val amount: Int)
                """.trimIndent(),
            ),
        )
        val source = processors(
            """
            import billing.Paid
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            @Projection
            class Ledger {
                @OnEvent(aggregates = ["billing.payment"])
                fun onPaid(event: StateTypedEvent<Map<String, Any?>, Paid>) = Unit
            }
            """,
        )
        val (_, result) = compileModelAgainst("generate", options, listOf(billing), model, source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("Ledger.onPaid", "an aggregate's state takes no type arguments")
    }

    /**
     * A handler's `@Name` goes into the generated string literals as it is (`SubscriptionSource.kt:151`), while the
     * processor's name goes through `escape`. A `$` in a `@Name` makes a string template of it in the generated file.
     */
    @Test
    fun `a dollar in a handler's Name should reach its binding as written`() {
        val source = processors(
            """
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateTypedEvent

            @Projection
            class Board {
                @OnEvent
                @Name("on\${'$'}placed")
                fun onPlaced(event: StateTypedEvent<Order, OrderPlaced>) = Unit
            }
            """,
        )
        val (compilation, result) = compileModel("generate", options, model, source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        bindings(compilation, result).single().name.assert().isEqualTo("Board.on\$placed")
    }
}
