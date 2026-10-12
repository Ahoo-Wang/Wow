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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.MissingDependencyException
import me.ahoo.wow.runtime.binding.BoundHandler
import me.ahoo.wow.runtime.binding.RuntimeProjectContext
import me.ahoo.wow.runtime.binding.RuntimeReactContext
import me.ahoo.wow.runtime.binding.RuntimeSagaContext
import me.ahoo.wow.runtime.binding.SubscriptionProvider
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
        import me.ahoo.wow.api.annotation.Projection
        import me.ahoo.wow.api.annotation.Reactor
        import me.ahoo.wow.api.annotation.Saga
        import me.ahoo.wow.api.aggregate.AggregateType
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.subscribe.ProjectContext
        import me.ahoo.wow.api.subscribe.ReactContext
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
            suspend fun withContext(event: StateEvent<OrderState>, context: ProjectContext) {
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

            fun onEvent(event: StateEvent<OrderState>, context: ReactContext) { seen += context.executionId.subscription }
        }

        @Saga
        class CartSaga {
            fun onEvent(event: StateEvent<OrderState>, context: SagaContext): List<Command<*>> =
                listOf(context.command(CART.ref(event.ref.tenantId, event.state.owner), Remove("p-1")),
                    context.command(CART.ref(event.ref.tenantId, event.state.owner), Remove("p-2")))

            @OnEvent
            fun single(event: StateEvent<OrderState>, context: SagaContext): Command<Remove>? =
                context.command(CART.ref(event.ref.tenantId, event.state.owner), Remove("p-3"))

            @OnEvent
            suspend fun none(event: StateEvent<OrderState>): List<Command<*>>? = null

            @OnEvent
            fun flux(event: StateEvent<OrderState>, context: SagaContext): Flux<Command<Remove>> =
                Flux.just(context.command(CART.ref(event.ref.tenantId, "bob"), Remove("p-4")))
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

    /** The compiled classes with the services file KSP generated, as a jar would hold them. */
    private fun provider(compilation: KotlinCompilation, result: JvmCompilationResult): SubscriptionProvider {
        val services = generated(compilation, SubscriptionSource.SUBSCRIPTION_PROVIDER)!!
        val root = services.parentFile.parentFile.parentFile
        return SubscriptionProvider.load(URLClassLoader(arrayOf(root.toURI().toURL()), result.classLoader)).single()
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

    private fun execution(handler: BoundHandler<*>, event: StateEvent<*>) = ExecutionId(
        handler.name,
        event.ref,
        event.version
    )

    @Test
    fun `each handler should be bound to its processor, built from the dependencies once`() {
        val (compilation, result) = compile(processors)
        generated(compilation, SubscriptionSource.SUBSCRIPTION_PROVIDER)!!.readText()
            .assert().isEqualTo("sample.shop.ShopDomainSubscriptionProvider\n")
        generated(compilation, "OrderProjectionSubscriptions.kt")!!.readText().assert().contains(
            "sample.shop.OrderProjection(dependencies.get(sample.shop.Rows::class.javaObjectType), " +
                "dependencies.get(sample.shop.Rows::class.javaObjectType, \"audit\"))",
        )
        val provider = provider(compilation, result)
        provider.context.assert().isEqualTo("shop")
        val rows = linkedMapOf<String, String>()
        val audit = linkedMapOf<String, String>()
        val handlers = provider.handlers(Dependencies.of(mapOf("audit" to rows(result, audit)), rows(result, rows)))
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
                it.handle(event, RuntimeProjectContext(execution(it, event))).assert().isEmpty()
            }
            val notifier = handlers.single { it.kind == FunctionKind.REACT }
            notifier.handle(event, RuntimeReactContext(execution(notifier, event))).assert().isEmpty()
        }
        rows.assert().isEqualTo(
            mapOf("sync" to "o-1", "mono" to "shop.order/t/o-1@2", "blocking" to "true"),
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
        ).handlers(Dependencies.of(mapOf("audit" to rows(result, Rows())), rows(result, Rows())))
            .filter { it.kind == FunctionKind.SAGA }.associateBy { it.function }
        val event = event(result)
        fun derive(function: String): List<Command<*>> = runBlocking {
            val handler = handlers.getValue(function)
            handler.handle(event, RuntimeSagaContext(execution(handler, event), event))
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
    fun `a missing dependency should fail the binding, naming its type`() {
        val (compilation, result) = compile(processors)
        assertThrownBy<MissingDependencyException> { provider(compilation, result).handlers(Dependencies.NONE) }
            .hasMessageContaining("sample.shop.Rows")
    }

    @Test
    fun `a module of aggregates only should have no subscription provider`() {
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
        val (compilation, _) = compile(account)
        generated(compilation, SubscriptionSource.SUBSCRIPTION_PROVIDER).assert().isNull()
        generated(compilation, ModelProviderSource.MODEL_PROVIDER).assert().isNotNull()
    }

    @Test
    fun `a module without wow-runtime should only check its processors, with a warning`() {
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
            { !it.invariantSeparatorsPath.contains("core/runtime/") && !it.name.startsWith("runtime") },
            view,
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert().contains(
            "sample.view.View: its subscriptions are not bound: the module does not depend on wow-runtime",
        )
        generated(compilation, "ViewSubscriptions.kt").assert().isNull()
        generated(compilation, SubscriptionSource.SUBSCRIPTION_PROVIDER).assert().isNull()
        generated(compilation, ModelProviderSource.MODEL_PROVIDER).assert().isNotNull()
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
}
