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
 * The events a model produces are its events (finding A4-9): one without a sourcing function is a compile error, and
 * a sourcing function of a sealed type evolves each of its subclasses.
 */
@OptIn(ExperimentalCompilerApi::class)
class ProducedEventsTest {
    private fun cart(root: String, state: String): SourceFile = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.lifecycle.AggregateDeleted

        @CreateAggregate
        data class Create(val id: String)
        data class Bump(val by: Int)
        data class Remove(val item: String)

        sealed interface CartEvent
        data class Created(val id: String) : CartEvent
        sealed interface Changed : CartEvent
        data class Bumped(val by: Int) : Changed
        data object Cleared : Changed
        data class Removed(val item: String)
        data class Audited(val by: String)

        @AggregateRoot
        class Cart(private val state: CartState) {
            $root
        }

        class CartState {
            var log = mutableListOf<String>()
            $state
        }
        """.trimIndent(),
    )

    private fun failing(root: String, state: String): String {
        val (_, result) = compileModel("generate", cart(root, state))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    @Test
    fun `a produced event without a sourcing function should be a compile error`() {
        failing(
            """
            fun onCommand(command: Create): Created = Created(command.id)
            fun onCommand(command: Remove): Removed = Removed(command.item)
            @AfterCommand fun audit(command: Any): Audited = Audited("x")
            """.trimIndent(),
            "fun onSourcing(event: Created) {}",
        ).assert().contains(
            "onCommand(sample.Remove) produces sample.Removed, which is not an event of the aggregate: no sourcing " +
                "function takes it; add one to the state, an empty one when the event changes nothing",
            "after hook audit produces sample.Audited, which is not an event of the aggregate",
        )
    }

    /** The compiler's messages for the command function [root], which compiles. */
    private fun compiled(root: String): String {
        val (_, result) = compileModel(
            "generate",
            cart(
                "@me.ahoo.wow.api.annotation.OnCommand(produces = [Created::class]) " +
                    "fun onCommand(command: Create): me.ahoo.wow.api.decide.Decision = " +
                    "me.ahoo.wow.api.decide.Decision.events(Created(command.id))\n$root",
                "fun onSourcing(event: Created) {}\nfun onSourcing(event: Bumped) {}",
            ),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return result.messages
    }

    @Test
    fun `a command function returning a Decision without produces should be warned of, naming the fix`() {
        compiled(
            "fun onCommand(command: Bump): me.ahoo.wow.api.decide.Decision = " +
                "me.ahoo.wow.api.decide.Decision.events(Bumped(command.by))",
        ).assert().contains(
            "onCommand returns Decision, which names no event it produces, and lists none " +
                "in @OnCommand(produces): the Manifest lists no event of it, so the kernel does not refuse an event " +
                "it was not meant to produce. List the events it may produce in @OnCommand(produces = [...]); a " +
                "function that only rejects or does nothing suppresses this with @Suppress(\"UnlistedEvents\")",
        )
    }

    @Test
    fun `a command function whose events produces lists, suppresses or its return type names should not be warned of`() {
        listOf(
            "@me.ahoo.wow.api.annotation.OnCommand(produces = [Bumped::class]) " +
                "fun onCommand(command: Bump): me.ahoo.wow.api.decide.Decision = " +
                "me.ahoo.wow.api.decide.Decision.events(Bumped(command.by))",
            "@Suppress(\"UnlistedEvents\") fun onCommand(command: Bump): me.ahoo.wow.api.decide.Decision = " +
                "me.ahoo.wow.api.decide.Decision.NoOp",
            "fun onCommand(command: Bump): Bumped = Bumped(command.by)",
        ).forEach { root ->
            compiled(root).assert().doesNotContain("names no event it produces")
        }
    }

    @Test
    fun `a suppression naming something else should still be warned of`() {
        compiled(
            "@Suppress(\"SomethingElse\") fun onCommand(command: Bump): me.ahoo.wow.api.decide.Decision = " +
                "me.ahoo.wow.api.decide.Decision.events(Bumped(command.by))",
        ).assert().contains("onCommand returns Decision, which names no event it produces")
    }

    @Test
    fun `a suppression should be found among other annotations and names`() {
        // @OnCommand is no suppression, and UnlistedEvents is one of the names @Suppress holds.
        compiled(
            "@me.ahoo.wow.api.annotation.OnCommand @Suppress(\"SomethingElse\", \"UnlistedEvents\") " +
                "fun onBump(command: Bump): me.ahoo.wow.api.decide.Decision = " +
                "me.ahoo.wow.api.decide.Decision.events(Bumped(command.by))",
        ).assert().doesNotContain("names no event it produces")
    }

    @Test
    fun `a suppression whose names have no value should still be warned of`() {
        // Not a constant: KSP evaluates no value for it, so it suppresses nothing (and the compiler refuses it).
        val (_, result) = compileModel(
            "generate",
            cart(
                "object Names { val unlisted: String = \"UnlistedEvents\" }\n" +
                    "@Suppress(Names.unlisted) fun onCommand(command: Bump): me.ahoo.wow.api.decide.Decision = " +
                    "me.ahoo.wow.api.decide.Decision.events(Bumped(command.by))",
                "fun onSourcing(event: Bumped) {}",
            ),
        )
        result.messages.assert()
            .contains("onCommand returns Decision, which names no event it produces")
            .doesNotContain("Exception")
    }

    @Test
    fun `a command function inherited from a library should not be warned of`() {
        // Its author, not the subclass's, lists what it produces, and its @Suppress does not reach the subclass.
        val library = compileLibrary(
            SourceFile.kotlin(
                "Base.kt",
                """
                package base

                import me.ahoo.wow.api.annotation.CreateAggregate
                import me.ahoo.wow.api.annotation.OnCommand
                import me.ahoo.wow.api.decide.Decision

                @CreateAggregate
                data class Open(val id: String)
                data class Opened(val id: String)

                abstract class Base<S : Any>(protected val state: S) {
                    @OnCommand
                    fun onOpen(command: Open): Decision = Decision.events(Opened(command.id))
                }
                """.trimIndent(),
            ),
        )
        val (_, result) = compileModelAgainst(
            "generate",
            listOf(library),
            SourceFile.kotlin(
                "Desk.kt",
                """
                package sample

                import base.Base
                import base.Opened
                import me.ahoo.wow.api.annotation.AggregateRoot

                @AggregateRoot
                class Desk(state: DeskState) : Base<DeskState>(state)

                class DeskState {
                    fun onSourcing(event: Opened) {}
                }
                """.trimIndent(),
            ),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert().doesNotContain("names no event it produces")
    }

    @Test
    fun `a sourcing function of an interface that is not sealed should be a compile error`() {
        failing(
            "fun onCommand(command: Create): Created = Created(command.id)",
            """
            fun onSourcing(event: Created) {}
            fun onSourcing(event: java.io.Serializable) {}
            """.trimIndent(),
        ).assert().contains(
            "sourcing function onSourcing takes java.io.Serializable, which is not a concrete class",
        )
    }

    @Test
    fun `sourcing functions of overlapping sealed types or of Any should be compile errors`() {
        failing(
            "fun onCommand(command: Create): Created = Created(command.id)",
            """
            fun onSourcing(event: CartEvent) {}
            @me.ahoo.wow.api.annotation.OnSourcing fun onChanged(event: Changed) {}
            @me.ahoo.wow.api.annotation.OnSourcing fun onAnything(event: Any) {}
            """.trimIndent(),
        ).assert().contains(
            "the sourcing functions onSourcing, onChanged both take sample.Bumped, through sealed types",
            "sourcing function onAnything takes kotlin.Any: a sourcing function takes the class of its event",
        )
    }

    @Test
    fun `a sourcing function of a sealed type should evolve each of its subclasses`() {
        val scenario = SourceFile.kotlin(
            "Scenario.kt",
            """
            @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

            package sample

            import kotlinx.coroutines.runBlocking
            import me.ahoo.wow.api.decide.Command
            import me.ahoo.wow.api.lifecycle.DeleteAggregate
            import me.ahoo.wow.kernel.CommandOutcome
            import me.ahoo.wow.kernel.Kernel
            import me.ahoo.wow.kernel.memory.InMemoryEventLog

            fun run(): String = runBlocking {
                val model = cartModel("shop")
                val kernel = Kernel(InMemoryEventLog())
                val ref = model.type.ref("tenant", "cart-1")
                listOf(Create("cart-1"), Bump(2), Bump(0)).forEachIndexed { index, body ->
                    kernel.execute(model, Command("c" + index, "r" + index, ref, body))
                }
                val outcome = kernel.execute(model, Command("c9", "r9", ref, DeleteAggregate)) as CommandOutcome.Committed
                (outcome.state as CartState).log.toString()
            }
            """.trimIndent(),
        )
        val source = cart(
            """
            fun onCommand(command: Create): Created = Created(command.id)
            fun onCommand(command: Bump): Changed = if (command.by == 0) Cleared else Bumped(command.by)
            """.trimIndent(),
            """
            fun onSourcing(event: CartEvent) { log.add(event.toString()) }
            fun onSourcing(event: Created) { log.add("created") }
            """.trimIndent(),
        )
        val options = mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop")
        val (compilation, result) = compileModel("generate", options, source, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null)
            .assert().isEqualTo("[created, Bumped(by=2), Cleared]")
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
        val aggregate = ManifestTree.read(
            jacksonObjectMapper().readValue(fragment, Map::class.java),
            ManifestTree.REFUSE
        )
            .contexts.single().aggregates.single()
        aggregate.events.map { it.name }.assert().containsExactly("Bumped", "Cleared", "Created")
        aggregate.commands.single { it.name == "Bump" }.produces.assert().isEqualTo(listOf("Bumped", "Cleared"))
    }

    @Test
    fun `a sourcing function taking no event or suspending should be a compile error`() {
        failing(
            "fun onCommand(command: Create): Created = Created(command.id)",
            """
            @me.ahoo.wow.api.annotation.OnSourcing fun onNothing() {}
            fun onSourcing(event: Created) {}
            suspend fun onSourcing(event: Bumped) {}
            """.trimIndent(),
        ).assert().contains(
            "sourcing function onNothing must take its event, and may take its commit " +
                "(me.ahoo.wow.api.read.EventStream) second",
            "sourcing function onSourcing suspends: evolving does no I/O",
        )
    }
}
