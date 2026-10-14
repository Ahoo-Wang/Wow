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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.committedContextOf
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URLClassLoader
import me.ahoo.wow.api.result.FunctionKind as ResultKind

/** Decision D192: a saga's `@OnCommitted` handlers in the fragment, their bindings and their problems. */
@OptIn(ExperimentalCompilerApi::class)
class CommittedManifestTest {
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

        @CreateAggregate
        data class PlaceOrder(val id: String)
        data class ConfirmStock(val productId: String)
        data class OrderPlaced(val id: String)
        data class StockConfirmed(val productId: String)

        @AggregateRoot
        class Order(val id: String) {
            fun onCommand(command: PlaceOrder): OrderPlaced = OrderPlaced(command.id)
            fun onCommand(command: ConfirmStock): StockConfirmed = StockConfirmed(command.productId)
            fun onSourcing(event: OrderPlaced) = Unit
            fun onSourcing(event: StockConfirmed) = Unit
        }

        /** A command of another context, which no aggregate of this module decides. */
        data class ReserveStock(val quantity: Int)
        """.trimIndent(),
    )

    private val saga = SourceFile.kotlin(
        "OrderSaga.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.Blocking
        import me.ahoo.wow.api.annotation.OnCommitted
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnRejected
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.aggregate.AggregateType
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.CommandCommitted
        import me.ahoo.wow.api.subscribe.CommandRejected
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import reactor.core.publisher.Mono

        val STOCK = AggregateType("inventory", "stock")

        @Saga
        class OrderSaga {
            @OnEvent
            fun onPlaced(event: StateEvent<Order>, context: SagaContext): List<Command<ReserveStock>> =
                listOf(context.command(STOCK.ref(event.ref.tenantId, "s-1"), ReserveStock(1)))

            @OnCommitted
            fun onReserved(committed: CommandCommitted<ReserveStock>, context: SagaContext): Command<ConfirmStock> =
                context.command(committed.executionId.ref, ConfirmStock(committed.aggregateId))

            @Blocking
            @OnCommitted
            fun onAnyCommitted(committed: CommandCommitted<*>): Mono<Command<*>> = Mono.empty()

            @OnRejected
            fun onReserveRejected(rejected: CommandRejected<ReserveStock>): Command<*>? = null
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

    @Test
    fun `the fragment should list a saga's @OnCommitted handlers with the commands they take`() {
        val (compilation, _) = compile(model, saga)
        val subscriptions = fragment(compilation).contexts.single().subscriptions.associateBy { it.name }
        subscriptions.getValue("OrderSaga.onReserved").assert().isEqualTo(
            SubscriptionManifest(
                name = "OrderSaga.onReserved",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(),
                function = FunctionManifest(FunctionKind.SAGA, "shop", "OrderSaga", "onReserved"),
                derives = listOf("shop.order.ConfirmStock"),
                committed = listOf(Manifest.schemaRef("sample.ReserveStock")),
            ),
        )
        subscriptions.getValue("OrderSaga.onAnyCommitted").let {
            it.committed.assert().isEqualTo(emptyList<String>())
            it.rejected.assert().isNull()
            it.derives.assert().isNull()
            it.isReceiptHandler.assert().isTrue()
        }
        subscriptions.getValue("OrderSaga.onReserveRejected").committed.assert().isNull()
    }

    @Test
    fun `every handler of a saga should carry its @OnCommitted handlers, each calling its function`(): Unit =
        runBlocking {
            val (compilation, result) = compile(model, saga)
            generated(compilation, "OrderSagaSubscriptions.kt")!!.readText().assert().contains(
                "val commits = listOf<me.ahoo.wow.kernel.CommittedBinding<*>>(",
                "me.ahoo.wow.kernel.CommittedBinding.of<sample.ReserveStock>(\"OrderSaga\", \"onReserved\", " +
                    "sample.ReserveStock::class.java, false)",
                "me.ahoo.wow.kernel.CommittedBinding.of<Any>(\"OrderSaga\", \"onAnyCommitted\", null, true)",
                "rejections = rejections, commits = commits) {",
            )
            val saga = provider(compilation, result).processors(Dependencies.NONE).single()
            val reserve = result.classLoader.loadClass("sample.ReserveStock")
            val onReserved = saga.committedHandlerOf(reserve)!!
            onReserved.name.assert().isEqualTo("OrderSaga.onReserved")
            saga.committedHandlerOf(String::class.java)!!.blocking.assert().isTrue()
            saga.rejectionHandlerOf(reserve)!!.name.assert().isEqualTo("OrderSaga.onReserveRejected")
            val order = AggregateType("shop", "order").ref("t", "o-1")
            val stock = AggregateType("inventory", "stock").ref("t", "s-1")
            val committed = CommandCommitted<Any>(
                commandId = "c-9",
                commandType = "inventory.stock.ReserveStock",
                ref = stock,
                version = 2,
                result = CommandResult(
                    commandId = "c-9",
                    requestId = "c-9",
                    correlationId = "root",
                    ref = stock,
                    hop = Hop.committed("inventory"),
                    function = FunctionInfo(ResultKind.COMMAND, "inventory", "stock", "ReserveStock"),
                    status = CommandResult.Status.SUCCEEDED,
                    version = 2,
                    signalTime = 1L,
                ),
                causation = Causation("root", "$order@1"),
                executionId = ExecutionId("OrderSaga.onPlaced", order, 1),
            )
            val derived = onReserved.invoke(committed, committedContextOf(onReserved, committed)).single()
            derived.ref.assert().isEqualTo(order)
            derived.body.toString().assert().isEqualTo("ConfirmStock(productId=s-1)")
            derived.causation!!.causationId.assert().isEqualTo("c-9")
            val any = saga.committedHandlerOf(String::class.java)!!
            any.invoke(committed, committedContextOf(any, committed)).assert().isEmpty()
        }

    @Suppress("LongMethod") // The broken sources, whole.
    @Test
    fun `a malformed @OnCommitted handler should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnCommitted
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Retry
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.CommandCommitted
            import me.ahoo.wow.api.subscribe.CommandRejected
            import me.ahoo.wow.api.subscribe.ProjectContext
            import me.ahoo.wow.api.subscribe.StateEvent

            @Saga
            class BrokenSaga {
                @OnEvent
                fun onEvent(event: StateEvent<Order>): Command<*>? = null

                @OnCommitted
                fun first(committed: CommandCommitted<ReserveStock>): Command<*>? = null

                @OnCommitted
                fun second(committed: CommandCommitted<ReserveStock>): Command<*>? = null

                @OnCommitted
                fun any(committed: CommandCommitted<*>): Command<*>? = null

                @OnCommitted
                fun anyAgain(committed: CommandCommitted<Any>): Command<*>? = null

                @OnEvent
                @OnCommitted
                fun both(committed: CommandCommitted<PlaceOrder>): Command<*>? = null

                @OnRejected
                @OnCommitted
                fun twoReceipts(committed: CommandCommitted<ConfirmStock>): Command<*>? = null

                @Retry(maxAttempts = 2)
                @OnCommitted
                fun retried(committed: CommandCommitted<OrderPlaced>): Command<*>? = null

                @OnCommitted
                fun wrongFirst(rejected: CommandRejected<StockConfirmed>): Command<*>? = null

                @OnCommitted
                fun wrongContext(committed: CommandCommitted<Order>, context: ProjectContext): Command<*>? = null

                @OnCommitted
                fun tooMany(committed: CommandCommitted<String>, a: Int, b: Int): Command<*>? = null

                @OnCommitted
                fun wrongReturn(committed: CommandCommitted<Int>): String = ""
            }

            @Projection
            class Board {
                @OnEvent
                fun onEvent(event: StateEvent<Order>) = Unit

                @OnCommitted
                fun onCommitted(committed: CommandCommitted<*>): Command<*>? = null
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, model, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains(
                "processor BrokenSaga has two @OnCommitted handlers of the receipts of sample.ReserveStock: first, second",
            )
            .contains("processor BrokenSaga has two @OnCommitted handlers of the receipts of every command")
            .contains("handler BrokenSaga.both is both @OnEvent and @OnCommitted")
            .contains("handler BrokenSaga.twoReceipts is both @OnRejected and @OnCommitted")
            .contains("handler BrokenSaga.retried is annotated @Retry: an @OnCommitted handler runs in")
            .contains("handler BrokenSaga.wrongFirst must take a me.ahoo.wow.api.subscribe.CommandCommitted first")
            .contains("handler BrokenSaga.wrongContext takes me.ahoo.wow.api.subscribe.ProjectContext")
            .contains("handler BrokenSaga.tooMany takes 3 parameters: an @OnCommitted handler takes")
            .contains("handler BrokenSaga.wrongReturn returns kotlin.String")
            .contains("processor Board is a @Projection with @OnCommitted handlers")
            .doesNotContain("two handlers named twoReceipts")
    }
}
