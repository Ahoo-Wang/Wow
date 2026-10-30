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
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.compiler.model.symbol.blocking
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.committedContextOf
import me.ahoo.wow.kernel.rejectionContextOf
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

        /** Commands of another context, which no aggregate of this module decides. */
        data class ReserveStock(val quantity: Int)
        data class ReleaseStock(val quantity: Int)
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
        import me.ahoo.wow.api.annotation.Sends
        import me.ahoo.wow.api.aggregate.AggregateType
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.CommandReceipt
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import reactor.core.publisher.Mono

        val STOCK = AggregateType("inventory", "stock")

        @Saga
        class OrderSaga {
            @OnEvent
            fun onPlaced(event: StateEvent<Order>, context: SagaContext): List<CommandRequest<ReserveStock>> =
                listOf(CommandRequest.to(STOCK.ref(event.ref.tenantId, "s-1"), ReserveStock(1)))

            @OnCommitted
            fun onReserved(committed: CommandReceipt.Committed<ReserveStock>, context: SagaContext): CommandRequest<ConfirmStock> =
                CommandRequest.to(committed.source, ConfirmStock(committed.ref.id))

            @Blocking
            @OnCommitted
            fun onAnyCommitted(committed: CommandReceipt.Committed<*>): Mono<CommandRequest<ConfirmStock>> = Mono.empty()

            @OnRejected
            fun onReserveRejected(rejected: CommandReceipt.Rejected<ReserveStock>) = Unit

            @OnRejected
            @OnCommitted
            @Sends(derives = [ConfirmStock::class])
            fun onReleased(receipt: CommandReceipt<ReleaseStock>, context: SagaContext): CommandRequest<*>? =
                when (receipt) {
                    is CommandReceipt.Rejected -> null
                    is CommandReceipt.Committed -> CommandRequest.to(receipt.source, ConfirmStock(receipt.ref.id))
                }
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
            it.derives.assert().isEqualTo(listOf("shop.order.ConfirmStock"))
            it.isReceiptHandler.assert().isTrue()
        }
        subscriptions.getValue("OrderSaga.onReserveRejected").committed.assert().isNull()
        // A handler of both receipts is one subscription, which takes both.
        subscriptions.getValue("OrderSaga.onReleased").assert().isEqualTo(
            SubscriptionManifest(
                name = "OrderSaga.onReleased",
                kind = SubscriptionKind.SAGA,
                source = SubscriptionSource(),
                function = FunctionManifest(FunctionKind.SAGA, "shop", "OrderSaga", "onReleased"),
                derives = listOf("shop.order.ConfirmStock"),
                rejected = listOf(Manifest.schemaRef("sample.ReleaseStock")),
                committed = listOf(Manifest.schemaRef("sample.ReleaseStock")),
            ),
        )
        fragment(compilation).contexts.single().subscriptions.count { it.name == "OrderSaga.onReleased" }
            .assert().isEqualTo(1)
    }

    @Test
    fun `every handler of a saga should carry its @OnCommitted handlers, each calling its function`(): Unit =
        runBlocking {
            val (compilation, result) = compile(model, saga)
            generated(compilation, "OrderSagaSubscriptions.kt")!!.readText().assert().contains(
                "val commits = listOf<me.ahoo.wow.kernel.CommittedBinding<*>>(",
                "me.ahoo.wow.kernel.CommittedBinding.of<sample.ReserveStock>(\"OrderSaga\", \"onReserved\", " +
                    "sample.ReserveStock::class.java, false, derives = listOf(sample.ConfirmStock::class.java))",
                "me.ahoo.wow.kernel.CommittedBinding.of<Any>(\"OrderSaga\", \"onAnyCommitted\", null, true, " +
                    "derives = listOf(sample.ConfirmStock::class.java))",
                "rejections = rejections, commits = commits, derives = listOf(sample.ReserveStock::class.java)) {",
            )
            val saga = provider(compilation, result).processors(Dependencies.NONE).single()
            val reserve = result.classLoader.loadClass("sample.ReserveStock")
            val onReserved = saga.committedHandlerOf(reserve)!!
            onReserved.name.assert().isEqualTo("OrderSaga.onReserved")
            saga.committedHandlerOf(String::class.java)!!.blocking.assert().isTrue()
            saga.rejectionHandlerOf(reserve)!!.name.assert().isEqualTo("OrderSaga.onReserveRejected")
            val order = AggregateType("shop", "order").ref("t", "o-1")
            val stock = AggregateType("inventory", "stock").ref("t", "s-1")
            val committed = CommandReceipt.Committed<Any>(
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

    @Test
    fun `a handler of both receipts should be bound on each path, telling them apart itself`(): Unit = runBlocking {
        val (compilation, result) = compile(model, saga)
        generated(compilation, "OrderSagaSubscriptions.kt")!!.readText().assert().contains(
            "me.ahoo.wow.kernel.RejectionBinding.of<sample.ReleaseStock>(\"OrderSaga\", \"onReleased\", " +
                "sample.ReleaseStock::class.java, false, derives = listOf(sample.ConfirmStock::class.java))",
            "me.ahoo.wow.kernel.CommittedBinding.of<sample.ReleaseStock>(\"OrderSaga\", \"onReleased\", " +
                "sample.ReleaseStock::class.java, false, derives = listOf(sample.ConfirmStock::class.java))",
        )
        val saga = provider(compilation, result).processors(Dependencies.NONE).single()
        val release = result.classLoader.loadClass("sample.ReleaseStock")
        val onCommitted = saga.committedHandlerOf(release)!!
        onCommitted.name.assert().isEqualTo("OrderSaga.onReleased")
        val onRejected = saga.rejectionHandlerOf(release)!!
        onRejected.name.assert().isEqualTo("OrderSaga.onReleased")
        val order = AggregateType("shop", "order").ref("t", "o-1")
        val stock = AggregateType("inventory", "stock").ref("t", "s-1")
        val causation = Causation("root", "$order@1")
        val execution = ExecutionId("OrderSaga.onPlaced", order, 1)
        val committed = CommandReceipt.Committed<Any>(
            commandId = "c-9",
            commandType = "inventory.stock.ReleaseStock",
            ref = stock,
            version = 2,
            result = CommandResult(
                commandId = "c-9",
                requestId = "c-9",
                correlationId = "root",
                ref = stock,
                hop = Hop.committed("inventory"),
                function = FunctionInfo(ResultKind.COMMAND, "inventory", "stock", "ReleaseStock"),
                status = CommandResult.Status.SUCCEEDED,
                version = 2,
                signalTime = 1L,
            ),
            causation = causation,
            executionId = execution,
        )
        onCommitted.invoke(committed, committedContextOf(onCommitted, committed)).single()
            .body.toString().assert().isEqualTo("ConfirmStock(productId=s-1)")
        val rejected = CommandReceipt.Rejected<Any>(
            commandId = "c-10",
            commandType = "inventory.stock.ReleaseStock",
            ref = stock,
            error = ErrorInfo("stock.locked", "Locked."),
            causation = causation,
            executionId = execution,
        )
        onRejected.invoke(rejected, rejectionContextOf(onRejected, rejected)).assert().isEmpty()
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
            import me.ahoo.wow.api.client.CommandRequest
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.SubscriptionContext
            import me.ahoo.wow.api.subscribe.StateEvent

            @Saga
            class BrokenSaga {
                @OnEvent
                fun onEvent(event: StateEvent<Order>) = Unit

                @OnCommitted
                fun first(committed: CommandReceipt.Committed<ReserveStock>) = Unit

                @OnCommitted
                fun second(committed: CommandReceipt.Committed<ReserveStock>) = Unit

                @OnCommitted
                fun any(committed: CommandReceipt.Committed<*>) = Unit

                @OnCommitted
                fun anyAgain(committed: CommandReceipt.Committed<Any>) = Unit

                @OnEvent
                @OnCommitted
                fun both(committed: CommandReceipt.Committed<PlaceOrder>) = Unit

                @OnRejected
                @OnCommitted
                fun twoReceipts(committed: CommandReceipt.Committed<ConfirmStock>) = Unit

                @OnCommitted
                fun eitherOnCommitted(receipt: CommandReceipt<ConfirmStock>) = Unit

                @OnRejected
                fun eitherOnRejected(receipt: CommandReceipt<PlaceOrder>) = Unit

                @OnRejected
                fun rejectedTakesCommitted(committed: CommandReceipt.Committed<OrderPlaced>) = Unit

                @Retry(maxAttempts = 2)
                @OnCommitted
                fun retried(committed: CommandReceipt.Committed<OrderPlaced>) = Unit

                @OnCommitted
                fun wrongFirst(rejected: CommandReceipt.Rejected<StockConfirmed>) = Unit

                @OnCommitted
                fun wrongContext(committed: CommandReceipt.Committed<Order>, context: SubscriptionContext) = Unit

                @OnCommitted
                fun tooMany(committed: CommandReceipt.Committed<String>, a: Int, b: Int) = Unit

                @OnCommitted
                fun wrongReturn(committed: CommandReceipt.Committed<Int>): String = ""
            }

            @Projection
            class Board {
                @OnEvent
                fun onEvent(event: StateEvent<Order>) = Unit

                @OnCommitted
                fun onCommitted(committed: CommandReceipt.Committed<*>) = Unit
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
            // Each annotation declares one path; the parameter type alone picks none.
            .contains(
                "handler BrokenSaga.twoReceipts is @OnRejected and @OnCommitted, so it takes a CommandReceipt<C> " +
                    "first, not me.ahoo.wow.api.subscribe.CommandReceipt.Committed",
            )
            .contains(
                "handler BrokenSaga.eitherOnCommitted takes a CommandReceipt<C>, the receipt of either outcome, but " +
                    "is only @OnCommitted: annotate it @OnRejected too, or take a CommandReceipt.Committed<C>",
            )
            .contains(
                "handler BrokenSaga.eitherOnRejected takes a CommandReceipt<C>, the receipt of either outcome, but " +
                    "is only @OnRejected: annotate it @OnCommitted too, or take a CommandReceipt.Rejected<C>",
            )
            .contains(
                "handler BrokenSaga.rejectedTakesCommitted is @OnRejected but takes a CommandReceipt.Committed<C>: " +
                    "annotate it @OnCommitted instead, or take a CommandReceipt.Rejected<C>",
            )
            .contains(
                "handler BrokenSaga.retried is annotated @Retry: an @OnCommitted handler takes the budget of where"
            )
            .contains(
                "handler BrokenSaga.wrongFirst is @OnCommitted but takes a CommandReceipt.Rejected<C>: annotate it " +
                    "@OnRejected instead, or take a CommandReceipt.Committed<C>",
            )
            .contains("handler BrokenSaga.wrongContext takes me.ahoo.wow.api.subscribe.SubscriptionContext")
            .contains("handler BrokenSaga.tooMany takes 3 parameters: an @OnCommitted handler takes")
            .contains("handler BrokenSaga.wrongReturn returns kotlin.String")
            .contains("processor Board is a @Projection with @OnCommitted handlers")
            .doesNotContain("two handlers named twoReceipts")
    }
}
