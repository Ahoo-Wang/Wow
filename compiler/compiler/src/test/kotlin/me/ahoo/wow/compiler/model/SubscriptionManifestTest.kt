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
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SubscriptionStart
import me.ahoo.wow.manifest.copyWith
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URLClassLoader

/**
 * Roadmap 6.0a: the subscriptions' M6 fields in the fragment, `derives` and cross-context sources (decision D138),
 * `@OnRejected` handlers (decisions D136, D153) and the start (decision D154).
 */
@OptIn(ExperimentalCompilerApi::class)
class SubscriptionManifestTest {
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
        import me.ahoo.wow.api.annotation.Name

        @CreateAggregate
        data class PlaceOrder(val id: String)
        @Name("Cancel")
        data class CancelOrder(val reason: String)
        data class OrderPlaced(val id: String)
        data class OrderCancelled(val reason: String)

        @AggregateRoot
        class Order(val id: String) {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.id)
            fun onCommand(command: CancelOrder): OrderCancelled = OrderCancelled(command.reason)
            fun onSourcing(event: OrderPlaced) = Unit
            fun onSourcing(event: OrderCancelled) = Unit
        }

        /** A command of another context, which no aggregate of this module decides. */
        data class ReserveStock(val quantity: Int)

        /** The subscriber's view of another context's state. */
        data class StockView(val available: Int)
        """.trimIndent(),
    )

    private val saga = SourceFile.kotlin(
        "OrderSaga.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnRejected
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.aggregate.AggregateType
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.CommandRejected
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import reactor.core.publisher.Flux

        val STOCK = AggregateType("inventory", "stock")

        @Saga
        class OrderSaga {
            @OnEvent
            fun onPlaced(event: StateEvent<Order>, context: SagaContext): List<Command<ReserveStock>> =
                listOf(context.command(STOCK.ref(event.ref.tenantId, "s-1"), ReserveStock(1)))

            @OnEvent(derives = ["inventory.stock.ReserveStock", "inventory.stock.ReleaseStock"])
            fun declared(event: StateEvent<Order>, context: SagaContext): List<Command<*>> = emptyList()

            @OnEvent
            fun untyped(event: StateEvent<Order>): Command<*>? = null

            @OnEvent(aggregates = ["inventory.stock"])
            fun onStock(event: StateEvent<StockView>, context: SagaContext): Flux<Command<CancelOrder>> =
                Flux.empty()

            @OnRejected
            fun onReserveRejected(rejected: CommandRejected<ReserveStock>, context: SagaContext): Command<CancelOrder>? =
                null

            @OnRejected
            suspend fun onAnyRejected(rejected: CommandRejected<*>): List<Command<*>> = emptyList()
        }

        @Projection
        class StockBoard {
            @OnEvent(aggregates = ["inventory.stock"])
            fun onEvent(event: StateEvent<StockView>) = Unit

            @OnEvent(aggregates = ["shop.order"])
            fun onOrder(event: StateEvent<Order>) = Unit
        }

        @Projection
        class StockOnly {
            @OnEvent(aggregates = ["inventory.stock"])
            fun onEvent(event: StateEvent<StockView?>) = Unit
        }
        """.trimIndent(),
    )

    private fun compile(vararg sources: SourceFile): Pair<KotlinCompilation, JvmCompilationResult> {
        val (compilation, result) = compileModel("generate", options, *sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return compilation to result
    }

    private fun generated(compilation: KotlinCompilation, name: String): File? =
        compilation.workingDir.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun fragment(compilation: KotlinCompilation): Manifest = ManifestTree.read(
        jacksonObjectMapper().readValue(generated(compilation, "shop-domain.json")!!, Map::class.java),
    )

    private fun provider(compilation: KotlinCompilation, result: JvmCompilationResult): ModelProvider {
        val services = generated(compilation, ModelProviderSource.MODEL_PROVIDER)!!
        val root = services.parentFile.parentFile.parentFile
        return ModelProvider.load(URLClassLoader(arrayOf(root.toURI().toURL()), result.classLoader)).single()
    }

    private fun saga(function: String) = FunctionManifest(FunctionKind.SAGA, "shop", "OrderSaga", function)

    @Suppress("LongMethod") // The expected subscriptions, whole.
    @Test
    fun `the fragment should list what a saga derives, the sources of other contexts and the rejection handlers`() {
        val (compilation, _) = compile(model, saga)
        val context = fragment(compilation).contexts.single()
        val order = SubscriptionSource(context = "shop", aggregates = listOf("order"))
        val stock = SubscriptionSource(context = "inventory", aggregates = listOf("stock"))
        context.subscriptions.assert().containsExactly(
            SubscriptionManifest(
                name = "OrderSaga.declared",
                kind = SubscriptionKind.SAGA,
                source = order,
                function = saga("declared"),
                derives = listOf("inventory.stock.ReleaseStock", "inventory.stock.ReserveStock"),
                start = SubscriptionStart.END,
            ),
            SubscriptionManifest(
                name = "OrderSaga.onAnyRejected",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(),
                function = saga("onAnyRejected"),
                derives = null,
                rejected = emptyList(),
            ),
            SubscriptionManifest(
                name = "OrderSaga.onPlaced",
                kind = SubscriptionKind.SAGA,
                source = order,
                function = saga("onPlaced"),
                // A command no aggregate of the module decides is named by its schema, for the peer Manifests.
                derives = listOf(Manifest.schemaRef("sample.ReserveStock")),
                start = SubscriptionStart.END,
            ),
            SubscriptionManifest(
                name = "OrderSaga.onReserveRejected",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(),
                function = saga("onReserveRejected"),
                derives = listOf("shop.order.Cancel"),
                rejected = listOf(Manifest.schemaRef("sample.ReserveStock")),
            ),
            SubscriptionManifest(
                name = "OrderSaga.onStock",
                kind = SubscriptionKind.SAGA,
                source = stock,
                function = saga("onStock"),
                derives = listOf("shop.order.Cancel"),
                // Decision D194: a saga of another context's stream starts from its beginning.
                start = SubscriptionStart.BEGINNING,
            ),
            SubscriptionManifest(
                name = "OrderSaga.untyped",
                kind = SubscriptionKind.SAGA,
                source = order,
                function = saga("untyped"),
                derives = null,
                start = SubscriptionStart.END,
            ),
            SubscriptionManifest(
                name = "StockBoard.onEvent",
                kind = SubscriptionKind.PROJECTION,
                source = stock,
                function = FunctionManifest(FunctionKind.PROJECT, "shop", "StockBoard", "onEvent"),
                start = SubscriptionStart.BEGINNING,
            ),
            SubscriptionManifest(
                name = "StockBoard.onOrder",
                kind = SubscriptionKind.PROJECTION,
                source = order,
                function = FunctionManifest(FunctionKind.PROJECT, "shop", "StockBoard", "onOrder"),
                start = SubscriptionStart.BEGINNING,
            ),
            SubscriptionManifest(
                name = "StockOnly.onEvent",
                kind = SubscriptionKind.PROJECTION,
                // Decision D140: a handler of a nullable state may read a stream published without it.
                source = stock.copyWith(nullableState = true),
                function = FunctionManifest(FunctionKind.PROJECT, "shop", "StockOnly", "onEvent"),
                start = SubscriptionStart.BEGINNING,
            ),
        )
    }

    @Test
    fun `handlers of another context should be bound with their source and state nullability, rejections one each`() {
        val (compilation, result) = compile(model, saga)
        val processors = provider(compilation, result).processors(Dependencies.NONE)
        processors.map { it.name }.assert().containsExactlyInAnyOrder(
            "OrderSaga.onPlaced",
            "OrderSaga.declared",
            "OrderSaga.untyped",
            "OrderSaga.onStock",
            "StockBoard.onEvent",
            "StockBoard.onOrder",
            "StockOnly.onEvent",
        )
        // Decisions D134, D138: a handler of another context's aggregates reads their published streams.
        val stockOnly = processors.single { it.name == "StockOnly.onEvent" }
        stockOnly.aggregates.assert().containsExactly("inventory.stock")
        stockOnly.isSourced("shop").assert().isTrue()
        stockOnly.stateType!!.name.assert().isEqualTo("sample.StockView")
        // Decision D140: StateEvent<StockView?> is recorded, StateEvent<StockView> is not nullable.
        stockOnly.nullableState.assert().isTrue()
        processors.single { it.name == "StockBoard.onEvent" }.nullableState.assert().isFalse()
        processors.single { it.name == "StockBoard.onOrder" }.isSourced("shop").assert().isFalse()
        processors.single { it.name == "OrderSaga.onStock" }.aggregates.assert().containsExactly("inventory.stock")
        generated(compilation, "StockOnlySubscriptions.kt")!!.readText().assert()
            .contains(
                "ProcessorBinding.project<sample.StockView?>(\"StockOnly\", \"onEvent\", sample.StockView::class.java"
            )
            .contains("aggregates = listOf(\"inventory.stock\"), nullableState = true)")
        result.messages.assert()
            .doesNotContain("of another context")
            .doesNotContain("@OnRejected handler OrderSaga.onReserveRejected is in the Manifest")
        // Decision D153: every handler of the saga carries its @OnRejected handlers, bound once.
        val sagas = processors.filter { it.processor == "OrderSaga" }
        sagas.forEach { saga ->
            saga.rejections.map { "${it.name}:${it.commandType?.name}" }.assert().containsExactlyInAnyOrder(
                "OrderSaga.onReserveRejected:sample.ReserveStock",
                "OrderSaga.onAnyRejected:null",
            )
            saga.rejections.assert().isEqualTo(sagas.first().rejections)
        }
        processors.filter { it.processor == "StockBoard" }.forEach { it.rejections.assert().isEmpty() }
    }

    @Suppress("LongMethod") // The sources and the expected starts, whole.
    @Test
    fun `a handler's declared start should be in the fragment and its binding, over its kind and source`() {
        val starts = SourceFile.kotlin(
            "Starts.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.subscribe.StateEvent

            @Saga
            class Replaying {
                @OnEvent(start = OnEvent.Start.BEGINNING)
                fun onOrder(event: StateEvent<Order>): Command<*>? = null

                @OnEvent(aggregates = ["inventory.stock"], start = OnEvent.Start.END)
                fun onStock(event: StateEvent<StockView>): Command<*>? = null

                @OnEvent(aggregates = ["inventory.stock"], start = OnEvent.Start.DEFAULT)
                fun onStockAgain(event: StateEvent<StockView>): Command<*>? = null

                @OnEvent(aggregates = ["shop.order"])
                fun onOwnOrder(event: StateEvent<Order>): Command<*>? = null
            }

            @Projection
            class Recent {
                @OnEvent(start = OnEvent.Start.END)
                fun onOrder(event: StateEvent<Order>) = Unit
            }

            @Reactor
            class Mailer {
                @OnEvent
                fun onOrder(event: StateEvent<Order>) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(model, starts)
        fragment(compilation).contexts.single().subscriptions.associate { it.name to it.start }.assert().isEqualTo(
            mapOf(
                "Mailer.onOrder" to SubscriptionStart.END,
                "Recent.onOrder" to SubscriptionStart.END,
                "Replaying.onOrder" to SubscriptionStart.BEGINNING,
                "Replaying.onOwnOrder" to SubscriptionStart.END,
                "Replaying.onStock" to SubscriptionStart.END,
                "Replaying.onStockAgain" to SubscriptionStart.BEGINNING,
            ),
        )
        val processors = provider(compilation, result).processors(Dependencies.NONE).associateBy { it.name }
        processors.mapValues { it.value.startIn("shop") }.assert().isEqualTo(
            mapOf(
                "Mailer.onOrder" to StartFrom.END,
                "Recent.onOrder" to StartFrom.END,
                "Replaying.onOrder" to StartFrom.BEGINNING,
                "Replaying.onOwnOrder" to StartFrom.END,
                "Replaying.onStock" to StartFrom.END,
                "Replaying.onStockAgain" to StartFrom.BEGINNING,
            ),
        )
        // Only a declared start is generated; the others are left to the runtime's default.
        processors.getValue("Mailer.onOrder").startFrom.assert().isNull()
        processors.getValue("Replaying.onStockAgain").startFrom.assert().isNull()
        generated(compilation, "ReplayingSubscriptions.kt")!!.readText().assert()
            .contains("startFrom = me.ahoo.wow.kernel.StartFrom.BEGINNING")
            .contains("startFrom = me.ahoo.wow.kernel.StartFrom.END")
    }

    @Test
    fun `two @OnRejected handlers of the same command should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Twice.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.CommandRejected
            import me.ahoo.wow.api.subscribe.StateEvent

            @Saga
            class TwiceSaga {
                @OnEvent
                fun onEvent(event: StateEvent<Order>): Command<*>? = null

                @OnRejected
                fun first(rejected: CommandRejected<ReserveStock>): Command<*>? = null

                @OnRejected
                fun second(rejected: CommandRejected<ReserveStock>): Command<*>? = null

                @OnRejected
                fun any(rejected: CommandRejected<*>): Command<*>? = null

                @OnRejected
                fun anyAgain(rejected: CommandRejected<Any>): Command<*>? = null
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains(
                "processor TwiceSaga has two @OnRejected handlers of the rejections of sample.ReserveStock: first, second"
            )
            .contains("processor TwiceSaga has two @OnRejected handlers of the rejections of every command")
    }

    @Test
    fun `a malformed source or derives should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent

            @Reactor
            class Broken {
                @OnEvent(aggregates = ["stock"], derives = ["inventory.stock.ReserveStock"])
                fun onEvent(event: StateEvent<StockView>) = Unit

                @OnEvent(aggregates = ["inventory.stock", "billing.account"])
                fun twoContexts(event: StateEvent<StockView>) = Unit
            }

            @Saga
            class BadDerives {
                @OnEvent(derives = ["ReserveStock"])
                fun onEvent(event: StateEvent<Order>): Command<ReserveStock>? = null
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains(
                "handler Broken.onEvent reads the aggregate \"stock\": @OnEvent(aggregates) names each as context.aggregate"
            )
            .contains("handler Broken.onEvent declares @OnEvent(derives), but a @Reactor handler derives no commands")
            .contains("handler Broken.twoContexts reads aggregates of the contexts inventory and billing")
            .contains("handler BadDerives.onEvent derives \"ReserveStock\": @OnEvent(derives) names each command")
    }

    @Test
    fun `a source of this context should be an aggregate of the module with the handler's state`() {
        val wrong = SourceFile.kotlin(
            "Wrong.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.StateEvent

            @Projection
            class Wrong {
                @OnEvent(aggregates = ["shop.cart"])
                fun missing(event: StateEvent<Order>) = Unit

                @OnEvent(aggregates = ["shop.order"])
                fun otherState(event: StateEvent<StockView>) = Unit
            }

            @Saga
            class Undeclared {
                @OnEvent(derives = ["inventory.stock.ReserveStock"])
                fun onEvent(event: StateEvent<Order>): Command<CancelOrder>? = null
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", options, model, wrong)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("handler Wrong.missing of sample.Wrong reads shop.cart, which is not an aggregate of this module")
            .contains("handler Wrong.otherState of sample.Wrong reads shop.order, whose state is sample.Order")
            .contains(
                "handler Undeclared.onEvent of sample.Undeclared returns shop.order.Cancel, which its " +
                    "@OnEvent(derives) does not list"
            )
        generated(compilation, "shop-domain.json").assert().isNull()
    }

    @Test
    fun `an @OnRejected handler should be a saga's, take a rejection and not be an @OnEvent handler too`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.subscribe.CommandRejected
            import me.ahoo.wow.api.subscribe.ProjectContext
            import me.ahoo.wow.api.subscribe.StateEvent

            @Projection
            class Board {
                @OnEvent
                fun onEvent(event: StateEvent<Order>) = Unit

                @OnRejected
                fun onRejected(rejected: CommandRejected<*>) = Unit
            }

            @Saga
            class BadSaga {
                @OnEvent
                fun onEvent(event: StateEvent<Order>) = Unit

                @OnRejected
                fun onEvent(rejected: CommandRejected<*>, other: String) = Unit

                @OnRejected
                fun takesEvent(event: StateEvent<Order>) = Unit

                @OnRejected
                fun wrongContext(rejected: CommandRejected<*>, context: ProjectContext) = Unit

                @OnRejected
                fun returnsText(rejected: CommandRejected<*>): String = ""

                @OnEvent
                @OnRejected
                fun both(rejected: CommandRejected<*>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("processor Board is a @Projection with @OnRejected handlers")
            .contains("processor BadSaga has two handlers named onEvent")
            .contains("handler BadSaga.onEvent takes kotlin.String: an @OnRejected handler takes a")
            .contains("handler BadSaga.takesEvent must take a me.ahoo.wow.api.subscribe.CommandRejected first")
            .contains("handler BadSaga.wrongContext takes me.ahoo.wow.api.subscribe.ProjectContext")
            .contains("handler BadSaga.returnsText returns kotlin.String")
            .contains("handler BadSaga.both is both @OnEvent and @OnRejected")
    }
}
