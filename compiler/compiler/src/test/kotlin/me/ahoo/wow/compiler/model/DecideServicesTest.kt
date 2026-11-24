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
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.compiler.model.symbol.ModelNames
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * A command function, or an after hook, takes the node's services as it takes the domain's own: `QueryApi`,
 * `NodeQueryApi` and `CommandClient` are Wow types, but every host binds them. A type only 9.x has is still refused as
 * code written for 9.x, and another Wow type, which no host binds, as no service.
 */
@OptIn(ExperimentalCompilerApi::class)
class DecideServicesTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "library",
        ModelSymbolProcessorProvider.MODULE_OPTION to "library-domain",
    )

    /** The guide's two reads of the read side, a caller's and a trusted one, and an after hook taking the client. */
    private val library = SourceFile.kotlin(
        "Library.kt",
        """
        package sample.library

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnCommand
        import me.ahoo.wow.api.caller.Caller
        import me.ahoo.wow.api.client.CommandClient
        import me.ahoo.wow.api.decide.DecideScope
        import me.ahoo.wow.api.decide.reject
        import me.ahoo.wow.api.query.dsl.countQuery
        import me.ahoo.wow.api.read.NodeQueryApi
        import me.ahoo.wow.api.read.QueryApi
        import me.ahoo.wow.api.read.snapshots

        const val MAX_BOOKS = 3

        @CreateAggregate
        data class LendBook(val accountId: String)

        @CreateAggregate
        data class ReserveBook(val accountId: String)
        data class BookLent(val accountId: String)
        data class AccountState(val id: String)

        @AggregateRoot
        class Book(private val state: BookState) {
            @OnCommand
            suspend fun onCommand(command: LendBook, queries: QueryApi, scope: DecideScope): BookLent {
                val admitted = scope.command
                val caller = Caller.of(admitted.ref.tenantId, admitted.scope, admitted.operator?.id)
                queries.of(caller).snapshots<AccountState>().state(command.accountId)
                    ?: reject("library.book.no-account", "No account ${'$'}{command.accountId}.")
                return BookLent(command.accountId)
            }

            @OnCommand
            suspend fun onCommand(command: ReserveBook, queries: NodeQueryApi, scope: DecideScope): BookLent {
                val lent = queries.trusted().snapshots<BookState>().count(
                    countQuery {
                        filter {
                            tenantId(scope.command.ref.tenantId)
                            "state.account" eq command.accountId
                        }
                    },
                )
                if (lent.count >= MAX_BOOKS) {
                    reject("library.book.too-many", "Account ${'$'}{command.accountId} has ${'$'}MAX_BOOKS books.")
                }
                return BookLent(command.accountId)
            }

            @AfterCommand
            fun afterCommand(commands: CommandClient) = Unit
        }

        class BookState(val id: String) {
            var account: String? = null
            fun onSourcing(event: BookLent) { account = event.accountId }
        }
        """.trimIndent(),
    )

    /**
     * Loads the module's provider as a host would, with the node's services as stand-ins that answer every read with
     * no account and five books lent, and runs both commands.
     */
    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample.library

        import java.lang.reflect.Proxy
        import java.lang.reflect.Type
        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.caller.Caller
        import me.ahoo.wow.api.client.CommandClient
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.api.query.CountResult
        import me.ahoo.wow.api.read.NodeQueryApi
        import me.ahoo.wow.api.read.Queries
        import me.ahoo.wow.api.read.QueryApi
        import me.ahoo.wow.api.read.SnapshotQueries
        import me.ahoo.wow.api.read.TenantSnapshotQueries
        import me.ahoo.wow.api.read.TrustedQueries
        import me.ahoo.wow.kernel.AggregateModel
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Dependencies
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.ModelProvider
        import me.ahoo.wow.kernel.memory.InMemoryEventLog

        val reads = mutableListOf<String>()

        /** A stand-in of [type] that records each call and answers it with [answer]. */
        fun <T> standIn(type: Class<T>, answer: (String, Array<out Any?>) -> Any? = { _, _ -> null }): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                    if (method.name == "toString") {
                        type.simpleName
                    } else {
                        reads += type.simpleName + "." + method.name
                        answer(method.name, args.orEmpty())
                    }
                },
            )

        val queries = standIn(QueryApi::class.java) { _, args ->
            reads += "caller of " + (args[0] as Caller).tenantId
            standIn(Queries::class.java) { _, _ -> standIn(TenantSnapshotQueries::class.java) }
        }
        val nodeQueries = standIn(NodeQueryApi::class.java) { _, _ ->
            standIn(TrustedQueries::class.java) { _, _ ->
                standIn(SnapshotQueries::class.java) { _, _ -> CountResult(5, exact = true) }
            }
        }
        val commands = standIn(CommandClient::class.java)

        val services = object : Dependencies {
            override fun find(type: Class<*>, name: String?): Any? = when (type) {
                QueryApi::class.java -> queries
                NodeQueryApi::class.java -> nodeQueries
                CommandClient::class.java -> commands
                else -> null
            }

            override fun find(type: Type, name: String?): Any? = null
        }

        fun run(loader: ClassLoader): List<String> = runBlocking {
            @Suppress("UNCHECKED_CAST")
            val book = ModelProvider.load(loader).single().models(services).single() as AggregateModel<BookState>
            val kernel = Kernel(InMemoryEventLog())
            listOf(LendBook("a-1"), ReserveBook("a-1")).mapIndexed { index, body ->
                val ref = book.type.ref("t-1", "b-" + index)
                when (val outcome = kernel.execute(book, Command("c-" + index, "r-" + index, ref, body))) {
                    is CommandOutcome.Rejected -> outcome.rejection.code
                    else -> outcome.toString()
                }
            } + reads
        }
        """.trimIndent(),
    )

    private fun generated(compilation: KotlinCompilation, name: String): File =
        compilation.workingDir.walkTopDown().first { it.isFile && it.name == name }

    @Test
    fun `the node's services should be dependencies the host gives the model factory`() {
        val (compilation, result) = compileModel("generate", options, library, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "BookModel.kt").readText().assert().contains(
            "queryApi: me.ahoo.wow.api.read.QueryApi",
            "nodeQueryApi: me.ahoo.wow.api.read.NodeQueryApi",
            "commandClient: me.ahoo.wow.api.client.CommandClient",
        )

        val resources = generated(compilation, ModelProviderSource.MODEL_PROVIDER).parentFile.parentFile.parentFile
        val loader = URLClassLoader(arrayOf(resources.toURI().toURL()), result.classLoader)
        val run = result.classLoader.loadClass("sample.library.ScenarioKt").getMethod("run", ClassLoader::class.java)
        run.invoke(null, loader).assert().isEqualTo(
            listOf(
                "library.book.no-account",
                "library.book.too-many",
                "QueryApi.of",
                "caller of t-1",
                "Queries.snapshots",
                "TenantSnapshotQueries.state",
                "NodeQueryApi.trusted",
                "TrustedQueries.snapshots",
                "SnapshotQueries.count",
            ),
        )
    }

    /** A cart whose command functions take [services], declared in [types] beside it. */
    private fun cart(types: String, services: String) = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample.cart

        import me.ahoo.wow.api.annotation.AggregateId
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class Open(@AggregateId val id: String)
        data class Opened(val id: String)

        $types

        @AggregateRoot
        class Cart(private val state: CartState) {
            $services
        }

        class CartState {
            fun onSourcing(event: Opened) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `an internal domain service should make the model factory internal`() {
        // A public factory would expose its internal parameter type (#6833).
        val (compilation, result) = compileModel(
            "generate",
            options,
            cart(
                "internal class Pricing",
                "internal fun onCommand(command: Open, pricing: Pricing) = Opened(command.id)"
            ),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "CartModel.kt").readText().assert().contains("internal fun cartModel(")
        // A public service of an internal type argument exposes it as well.
        val (boxed, boxedResult) = compileModel(
            "generate",
            options,
            cart(
                "internal class Pricing\nclass Box<T>",
                "internal fun onCommand(command: Open, box: Box<Pricing>) = Opened(command.id)",
            ),
        )
        boxedResult.exitCode.assert().withFailMessage { boxedResult.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(boxed, "CartModel.kt").readText().assert().contains("internal fun cartModel(")
    }

    @Test
    fun `a domain service should take a parameter name of its own, never one the factory binds`() {
        // Named after its class, a service would clash with the factory's own names, or with another's suffix (#6833).
        val (reserved, named) = compileModel(
            "generate",
            options,
            cart(
                "class Context\nclass State\nclass Type\nclass Command",
                "fun onCommand(command: Open, context: Context, state: State, type: Type, other: Command) = Opened(command.id)",
            ),
        )
        named.exitCode.assert().withFailMessage { named.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(reserved, "CartModel.kt").readText().assert().contains(
            "context: kotlin.String",
            "context2: sample.cart.Context",
            "state2: sample.cart.State",
            "type2: sample.cart.Type",
            "command2: sample.cart.Command",
        )
        // The count suffix of b.Foo, foo2, is a.Foo2's: the next free name.
        val (counted, suffixed) = compileModel(
            "generate",
            options,
            SourceFile.kotlin("A.kt", "package sample.a\n\nclass Foo\nclass Foo2"),
            SourceFile.kotlin("B.kt", "package sample.b\n\nclass Foo"),
            cart(
                "",
                "fun onCommand(command: Open, a: sample.a.Foo, a2: sample.a.Foo2, b: sample.b.Foo) = Opened(command.id)"
            ),
        )
        suffixed.exitCode.assert().withFailMessage { suffixed.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(counted, "CartModel.kt").readText().assert()
            .contains("foo: sample.a.Foo", "foo2: sample.a.Foo2", "foo3: sample.b.Foo")
    }

    @Test
    fun `two services of one simple name should keep the parameter names they had`() {
        // A free name stays: a test that calls the factory by its parameters' names keeps compiling.
        val (compilation, result) = compileModel(
            "generate",
            options,
            SourceFile.kotlin("A.kt", "package sample.a\n\nclass Foo"),
            SourceFile.kotlin("B.kt", "package sample.b\n\nclass Foo"),
            cart("", "fun onCommand(command: Open, a: sample.a.Foo, b: sample.b.Foo) = Opened(command.id)"),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "CartModel.kt").readText().assert().contains("foo: sample.a.Foo", "foo1: sample.b.Foo")
    }

    @Test
    fun `a type only 9x has should be refused as 9x, another Wow type as no service`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample.library

            import me.ahoo.wow.api.aggregate.AggregateRef
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.command.CommandResultAccessor

            @CreateAggregate
            data class LendBook(val accountId: String)

            @CreateAggregate
            data class ReserveBook(val accountId: String)
            data class BookLent(val accountId: String)

            @AggregateRoot
            class Book(val id: String) {
                fun onCommand(command: LendBook, accessor: CommandResultAccessor): BookLent = BookLent(command.accountId)

                fun onCommand(command: ReserveBook, ref: AggregateRef): BookLent = BookLent(command.accountId)

                fun onSourcing(event: BookLent) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, broken)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains(
                "onCommand takes me.ahoo.wow.api.command.CommandResultAccessor, a 9.x framework type with no " +
                    "Wow 10 counterpart",
            )
            .contains(
                "onCommand takes me.ahoo.wow.api.aggregate.AggregateRef, a Wow type no host binds: after its " +
                    "command, a function takes the decide scope, the services every host binds (QueryApi, " +
                    "NodeQueryApi, CommandClient) and the domain's own",
            )
    }

    @Test
    fun `no type of Wow 10's api should be taken for a 9x one`() {
        val classes = listOf(QueryApi::class.java, CountResult::class.java, AggregateRoot::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()) }.distinct()
            .flatMap(::classNames)
        classes.assert().isNotEmpty().anyMatch { it == "me.ahoo.wow.api.read.QueryApi" }
        classes.filter { name -> ModelNames.NINE_X_ONLY.any { name.startsWith(it) } }.assert().isEmpty()
        ModelNames.SERVICES.filterNot { it in classes }.assert().isEmpty()
    }

    /** The binary names, dots for slashes, of the classes in [location], a jar or a directory of classes. */
    private fun classNames(location: File): List<String> {
        val paths = if (location.isDirectory) {
            location.walkTopDown().filter { it.isFile }.map { it.relativeTo(location).invariantSeparatorsPath }.toList()
        } else {
            JarFile(location).use { jar -> jar.entries().toList().map { it.name } }
        }
        return paths.filter { it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') }
    }
}
