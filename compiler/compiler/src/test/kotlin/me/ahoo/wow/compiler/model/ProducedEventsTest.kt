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
        val aggregate = ManifestTree.read(jacksonObjectMapper().readValue(fragment, Map::class.java))
            .contexts.single().aggregates.single()
        aggregate.events.map { it.name }.assert().containsExactly("Bumped", "Cleared", "Created")
        aggregate.commands.single { it.name == "Bump" }.produces.assert().isEqualTo(listOf("Bumped", "Cleared"))
    }
}
