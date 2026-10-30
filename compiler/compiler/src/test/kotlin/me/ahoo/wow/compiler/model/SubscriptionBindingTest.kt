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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.compiler.model.symbol.blocking
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ExecutionContext
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.rejectionContextOf
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/** The bindings of `@Projection`, `@Reactor` and `@Saga` processors (design 04 §1, decision D37). */
@OptIn(ExperimentalCompilerApi::class)
class SubscriptionBindingTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private val processors = SourceFile.kotlin(
        "Processors.kt",
        """
        package sample.shop

        import me.ahoo.wow.api.annotation.Blocking
        import me.ahoo.wow.api.annotation.Name
        import me.ahoo.wow.api.annotation.OnEvent
        import me.ahoo.wow.api.annotation.OnRejected
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.aggregate.AggregateType
        import me.ahoo.wow.api.client.CommandRequest
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.CommandRejected
        import me.ahoo.wow.api.subscribe.ReceiptContext
        import me.ahoo.wow.api.subscribe.SubscriptionContext
        import me.ahoo.wow.api.subscribe.SagaContext
        import me.ahoo.wow.api.subscribe.StateEvent
        import reactor.core.publisher.Flux
        import reactor.core.publisher.Mono

        class OrderState(val id: String) { var owner: String = "alice" }
        data class Remove(val productId: String)
        fun interface Rows { fun put(key: String, value: String) }

        val CART = AggregateType("shop", "cart")

        @Projection
        class OrderProjection(private val rows: Rows, @Name("audit") private val audit: Rows) {
            @OnEvent
            fun onEvent(event: StateEvent<OrderState>) { rows.put("sync", event.state.id) }

            @OnEvent
            suspend fun withContext(event: StateEvent<OrderState>, context: SubscriptionContext) {
                audit.put("suspend", context.executionId.toString())
            }

            @OnEvent
            fun reactive(event: StateEvent<*>): Mono<Void> = Mono.fromRunnable { rows.put("mono", event.id) }

            @Blocking
            @OnEvent
            fun blocking(event: StateEvent<OrderState>) {
                rows.put("blocking", Thread.currentThread().isVirtual.toString())
            }
        }

        @Reactor
        @Name("notifier")
        object Notifier {
            val seen = mutableListOf<String>()

            @OnEvent
            fun onEvent(event: StateEvent<OrderState>, context: SubscriptionContext) { seen += context.executionId.subscription }
        }

        @Saga
        class CartSaga {
            @OnEvent
            fun onEvent(event: StateEvent<OrderState>, context: SagaContext): List<CommandRequest<*>> =
                listOf(CommandRequest.to(CART.ref(event.ref.tenantId, event.state.owner), Remove("p-1")),
                    CommandRequest.to(CART.ref(event.ref.tenantId, event.state.owner), Remove("p-2")))

            @OnEvent
            fun single(event: StateEvent<OrderState>, context: SagaContext): CommandRequest<Remove>? =
                CommandRequest.to(CART.ref(event.ref.tenantId, event.state.owner), Remove("p-3"))

            @OnEvent
            suspend fun none(event: StateEvent<OrderState>): List<CommandRequest<*>>? = null

            @OnEvent
            fun flux(event: StateEvent<OrderState>, context: SagaContext): Flux<CommandRequest<Remove>> =
                Flux.just(CommandRequest.to(CART.ref(event.ref.tenantId, "bob"), Remove("p-4")))

            @OnRejected
            fun onRemoveRejected(rejected: CommandRejected<Remove>, context: ReceiptContext): CommandRequest<Restore> =
                CommandRequest.to(rejected.ref, Restore(rejected.commandId))

            @Blocking
            @OnRejected
            suspend fun onAnyRejected(rejected: CommandRejected<*>): Flux<CommandRequest<*>> = Flux.empty()
        }

        data class Restore(val commandId: String)
        """.trimIndent(),
    )

    private fun compile(vararg sources: SourceFile): Pair<KotlinCompilation, JvmCompilationResult> {
        val (compilation, result) = compileModel("generate", options, *sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return compilation to result
    }

    private fun generated(compilation: KotlinCompilation, name: String): File? =
        compilation.workingDir.walkTopDown().firstOrNull { it.isFile && it.name == name }

    /** The compiled classes with the services file KSP generated, as a jar would hold them. */
    private fun provider(compilation: KotlinCompilation, result: JvmCompilationResult): ModelProvider {
        val services = generated(compilation, ModelProviderSource.MODEL_PROVIDER)!!
        val root = services.parentFile.parentFile.parentFile
        return ModelProvider.load(URLClassLoader(arrayOf(root.toURI().toURL()), result.classLoader)).single()
    }

    private class Rows : HashMap<String, String>()

    private fun rows(result: JvmCompilationResult, into: MutableMap<String, String>): Any {
        val rows = result.classLoader.loadClass("sample.shop.Rows")
        return java.lang.reflect.Proxy.newProxyInstance(result.classLoader, arrayOf(rows)) { _, method, arguments ->
            if (method.name == "put") {
                into[arguments[0] as String] = arguments[1] as String
            }
            null
        }
    }

    private fun event(result: JvmCompilationResult): StateEvent<Any> {
        val state = result.classLoader.loadClass("sample.shop.OrderState").getConstructor(String::class.java)
            .newInstance("o-1")
        return StateEvent(
            AggregateType("shop", "order").ref("t", "o-1"),
            2,
            "c-2",
            "r-2",
            "Pay",
            1L,
            listOf("e"),
            state
        )
    }

    private fun execution(handler: ProcessorBinding<*>, event: StateEvent<*>) = ExecutionId(
        handler.name,
        event.ref,
        event.version
    )

    @Test
    fun `each handler should be bound to its processor, built from the dependencies once`() {
        val (compilation, result) = compile(processors)
        // The model provider lists the processors (decision D149): no other binding index.
        generated(compilation, ModelProviderSource.MODEL_PROVIDER)!!.readText()
            .assert().isEqualTo("sample.shop.ShopDomainModelProvider\n")
        generated(compilation, "ShopDomainModelProvider.kt")!!.readText().assert().contains(
            "override fun processors(",
            "sample.shop.cartSagaHandlers(dependencies),",
        )
        // The handlers built from the host's Dependencies, by type and @Name, and from the services themselves,
        // named as the constructor names them.
        generated(compilation, "OrderProjectionSubscriptions.kt")!!.readText().assert().contains(
            "dependencies.get(sample.shop.Rows::class.javaObjectType, null, \"OrderProjection\"),",
            "dependencies.get(sample.shop.Rows::class.javaObjectType, \"audit\", \"OrderProjection\"),",
            "public fun orderProjectionHandlers(\n    rows: sample.shop.Rows,\n    audit: sample.shop.Rows,\n)",
            "val processor = sample.shop.OrderProjection(rows, audit)",
        )
        // A processor that takes no service, an object or a class, is built with none.
        generated(compilation, "NotifierSubscriptions.kt")!!.readText().assert().contains(
            "): List<me.ahoo.wow.kernel.ProcessorBinding<*>> = notifierHandlers()",
            "public fun notifierHandlers(\n): List<me.ahoo.wow.kernel.ProcessorBinding<*>> {",
            "val processor = sample.shop.Notifier",
        )
        generated(compilation, "CartSagaSubscriptions.kt")!!.readText().assert()
            .contains("val processor = sample.shop.CartSaga()")
        val provider = provider(compilation, result)
        provider.context.assert().isEqualTo("shop")
        val rows = linkedMapOf<String, String>()
        val audit = linkedMapOf<String, String>()
        val handlers = provider.processors(Dependencies.of(mapOf("audit" to rows(result, audit)), rows(result, rows)))
        handlers.map { "${it.name}:${it.kind}:${it.stateType?.simpleName}:${it.blocking}" }.assert().containsExactlyInAnyOrder(
            "CartSaga.onEvent:SAGA:OrderState:false",
            "CartSaga.single:SAGA:OrderState:false",
            "CartSaga.none:SAGA:OrderState:false",
            "CartSaga.flux:SAGA:OrderState:false",
            "notifier.onEvent:REACT:OrderState:false",
            "OrderProjection.onEvent:PROJECT:OrderState:false",
            "OrderProjection.withContext:PROJECT:OrderState:false",
            "OrderProjection.reactive:PROJECT:null:false",
            "OrderProjection.blocking:PROJECT:OrderState:true",
        )
        val event = event(result)
        runBlocking {
            handlers.filter { it.kind == FunctionKind.PROJECT }.forEach {
                it.invoke(event, ExecutionContext(execution(it, event))).assert().isEmpty()
            }
            val notifier = handlers.single { it.kind == FunctionKind.REACT }
            notifier.invoke(event, ExecutionContext(execution(notifier, event))).assert().isEmpty()
        }
        // A binding calls its handler in the caller's coroutine: moving a blocking one to a virtual thread is the
        // runtime's, which reads the binding's blocking flag.
        rows.assert().isEqualTo(
            mapOf("sync" to "o-1", "mono" to "shop.order/t/o-1@2", "blocking" to "false"),
        )
        audit.assert().isEqualTo(mapOf("suspend" to "OrderProjection.withContext:shop.order/t/o-1@2"))
        val seen = result.classLoader.loadClass("sample.shop.Notifier").getField("INSTANCE").get(null)
            .let { it.javaClass.getMethod("getSeen").invoke(it) as List<*> }
        seen.assert().containsExactly("notifier.onEvent")
    }

    @Test
    fun `a saga's handler should return the commands it derives, in every call form`() {
        val (compilation, result) = compile(processors)
        val handlers = provider(
            compilation,
            result
        ).processors(Dependencies.of(mapOf("audit" to rows(result, Rows())), rows(result, Rows())))
            .filter { it.kind == FunctionKind.SAGA }.associateBy { it.function }
        val event = event(result)
        fun derive(function: String): List<Command<*>> = runBlocking {
            val handler = handlers.getValue(function)
            handler.invoke(event, ExecutionSagaContext(execution(handler, event), event))
        }
        val derived = derive("onEvent")
        derived.map { it.ref.toString() }.assert().containsExactly("shop.cart/t/alice", "shop.cart/t/alice")
        derived.map { it.body.toString() }.assert().containsExactly("Remove(productId=p-1)", "Remove(productId=p-2)")
        derived.map { it.id }.distinct().assert().hasSize(2)
        derived.all { it.id == it.requestId && it.causation?.causationId == "shop.order/t/o-1@2" }.assert().isTrue()
        // A second execution of the same commit derives the same requests.
        derive("onEvent").map { it.requestId }.assert().isEqualTo(derived.map { it.requestId })
        derive("single").map { it.body.toString() }.assert().containsExactly("Remove(productId=p-3)")
        derive("none").assert().isEmpty()
        derive("flux").map { it.ref.id }.assert().containsExactly("bob")
    }

    @Test
    fun `a saga's handlers should carry its @OnRejected handlers, each calling its function`(): Unit = runBlocking {
        val (compilation, result) = compile(processors)
        generated(compilation, "CartSagaSubscriptions.kt")!!.readText().assert().contains(
            "val rejections = listOf<me.ahoo.wow.kernel.RejectionBinding<*>>(",
            "me.ahoo.wow.kernel.RejectionBinding.of<sample.shop.Remove>(\"CartSaga\", \"onRemoveRejected\", " +
                "sample.shop.Remove::class.java, false)",
            "me.ahoo.wow.kernel.RejectionBinding.of<Any>(\"CartSaga\", \"onAnyRejected\", null, true)",
            "rejections = rejections) {",
        )
        val handlers = provider(compilation, result)
            .processors(Dependencies.of(mapOf("audit" to rows(result, Rows())), rows(result, Rows())))
        val sagas = handlers.filter { it.kind == FunctionKind.SAGA }
        sagas.map { it.rejections }.distinct().assert().hasSize(1)
        val remove = result.classLoader.loadClass("sample.shop.Remove")
        val onRemove = sagas.first().rejectionHandlerOf(remove)!!
        onRemove.name.assert().isEqualTo("CartSaga.onRemoveRejected")
        val onAny = sagas.first().rejectionHandlerOf(String::class.java)!!
        onAny.name.assert().isEqualTo("CartSaga.onAnyRejected")
        onAny.blocking.assert().isTrue()
        val event = event(result)
        val rejected = CommandRejected<Any>(
            "c-9",
            "shop.cart.Remove",
            AggregateType("shop", "cart").ref("t", "alice"),
            ErrorInfo("cart.forbidden", "No."),
            Causation("root", "shop.order/t/o-1@2"),
            ExecutionId("CartSaga.onEvent", event.ref, event.version),
        )
        val compensations = onRemove.invoke(rejected, rejectionContextOf(onRemove, rejected))
        compensations.single().body.toString().assert().isEqualTo("Restore(commandId=c-9)")
        compensations.single().causation!!.causationId.assert().isEqualTo("c-9")
        onAny.invoke(rejected, rejectionContextOf(onAny, rejected)).assert().isEmpty()
        handlers.filter { it.kind != FunctionKind.SAGA }.forEach { it.rejections.assert().isEmpty() }
    }

    @Test
    fun `a missing dependency should fail the binding, naming its type`() {
        val (compilation, result) = compile(processors)
        assertThrownBy<MissingDependencyException> { provider(compilation, result).processors(Dependencies.NONE) }
            .hasMessageContaining("No dependency of sample.shop.Rows to build OrderProjection with")
    }

    @Test
    fun `an internal processor should take its services through an internal function`() {
        val internal = SourceFile.kotlin(
            "Audit.kt",
            """
            package sample.audit

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateEvent

            internal fun interface Journal {
                fun write(line: String)
            }

            @Projection
            internal class AuditProjection(private val journal: Journal) {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = journal.write(event.ref.id)
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(internal)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "AuditProjectionSubscriptions.kt")!!.readText().assert().contains(
            "public fun auditProjectionHandlers(\n    dependencies: me.ahoo.wow.kernel.Dependencies,",
            "internal fun auditProjectionHandlers(\n    journal: sample.audit.Journal,\n)",
        )
    }

    @Test
    fun `a processor with an internal constructor should take its services through an internal function`() {
        val sources = SourceFile.kotlin(
            "Idle.kt",
            """
            package sample.idle

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateEvent

            fun interface Journal {
                fun write(line: String)
            }

            @Projection
            class GuardedProjection internal constructor(private val journal: Journal) {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = journal.write(event.ref.id)
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(sources)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "GuardedProjectionSubscriptions.kt")!!.readText().assert()
            .contains("internal fun guardedProjectionHandlers(\n    journal: sample.idle.Journal,\n)")
    }

    @Test
    fun `a handler's @Name should name its subscription, the generated binding calling its function`() {
        val renamed = SourceFile.kotlin(
            "Renamed.kt",
            """
            package sample.renamed

            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.subscribe.CommandRejected
            import me.ahoo.wow.api.subscribe.StateEvent

            @Saga
            class RenamedSaga {
                @OnEvent
                @Name("onPlaced")
                fun onOrderPlaced(event: StateEvent<*>) = Unit

                @OnRejected
                @Name("onRefused")
                fun onAnyRejected(rejected: CommandRejected<*>) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(renamed)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val source = generated(compilation, "RenamedSagaSubscriptions.kt")!!.readText()
        source.assert().contains(
            "\"RenamedSaga\", \"onPlaced\"",
            "processor.onOrderPlaced(",
            "\"RenamedSaga\", \"onRefused\""
        )
        source.assert().contains("processor.onAnyRejected(")
        provider(compilation, result).processors(Dependencies.NONE).map { it.name }.assert()
            .containsExactly("RenamedSaga.onPlaced")
    }

    @Test
    fun `a processor named with Wow's reserved prefix should fail the compilation`() {
        val reserved = SourceFile.kotlin(
            "Reserved.kt",
            """
            package sample.reserved

            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateEvent

            @Projection
            @Name("wow.snapshot")
            class ReservedProjection {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, reserved)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("processor ReservedProjection is named 'wow.snapshot'")
    }

    @Test
    fun `a handler's blank @Name should fail the compilation`() {
        val blank = SourceFile.kotlin(
            "Blank.kt",
            """
            package sample.blank

            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateEvent

            @Projection
            class BlankProjection {
                @OnEvent
                @Name(" ")
                fun onEvent(event: StateEvent<*>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, blank)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("handler BlankProjection.onEvent has a blank @Name")
    }

    @Test
    fun `a module of aggregates only should list no processors`() {
        val account = SourceFile.kotlin(
            "Account.kt",
            """
            package sample.account

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Open(val amount: Int)
            data class Opened(val amount: Int)

            @AggregateRoot
            class Account(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.amount)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compile(account)
        generated(compilation, ModelProviderSource.MODEL_PROVIDER).assert().isNotNull()
        generated(compilation, "ShopDomainModelProvider.kt")!!.readText().assert().doesNotContain("processors")
        provider(compilation, result).processors(Dependencies.NONE).assert().isEmpty()
    }

    @Test
    fun `a module of processors should compile against wow-kernel alone`() {
        val view = SourceFile.kotlin(
            "View.kt",
            """
            package sample.view

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.subscribe.StateEvent

            @Projection
            class View {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel(
            "generate",
            options,
            { file ->
                val path = file.invariantSeparatorsPath
                listOf("core/runtime/", "core/reactor/").none { it in path } &&
                    !file.name.startsWith("runtime") && !file.name.startsWith("reactor")
            },
            view,
        )
        // Decision D149: a domain module binds its processors with wow-kernel on its class path, not the runtime.
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert().doesNotContain("not bound")
        generated(compilation, "ViewSubscriptions.kt")!!.readText()
            .assert().contains("me.ahoo.wow.kernel.ProcessorBinding.project<Any>(\"View\", \"onEvent\", null, false)")
        val processors = provider(compilation, result).processors(Dependencies.NONE)
        processors.map { it.name }.assert().containsExactly("View.onEvent")
    }

    @Test
    fun `a processor the binding cannot construct or call should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.subscribe.StateEvent

            class Box<T>(val id: String)

            @Projection
            abstract class Abstract {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }

            @Projection
            private class Hidden {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }

            @Reactor
            class Sealed private constructor() {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }

            @Reactor
            class Generic<T> {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }

            @Reactor
            class Qualified(@Name(" ") val service: Runnable) {
                @OnEvent
                fun onEvent(event: StateEvent<Box<String>>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("processor Abstract is not a concrete class or an object")
            .contains("processor Hidden is private")
            .contains("processor Sealed's constructor is private")
            .contains("processor Generic has type parameters")
            .contains("processor Qualified's parameter service has a blank @Name")
            .contains(
                "handler Qualified.onEvent takes a StateEvent of sample.Box<kotlin.String>: an aggregate's state " +
                    "takes no type arguments",
            )
    }

    @Test
    fun `a processor that takes the host's Dependencies should fail the compilation`() {
        val source = SourceFile.kotlin(
            "TakesDependencies.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Reactor
            import me.ahoo.wow.api.subscribe.StateEvent

            @Reactor
            class TakesDependencies(val dependencies: me.ahoo.wow.kernel.Dependencies) {
                @OnEvent
                fun onEvent(event: StateEvent<*>) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("processor TakesDependencies takes the host's Dependencies")
    }
}
