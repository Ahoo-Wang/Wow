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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * `@Blocking` on command functions and after hooks (decisions D36, D227, finding A4-1): the generated model registers
 * them with `decideBlocking` and `afterBlocking`, so the kernel calls them on a virtual thread, while an unannotated
 * function stays inline on the caller's thread. Where no I/O belongs, it is a compile error.
 */
@OptIn(ExperimentalCompilerApi::class)
class BlockingTest {
    private val cart = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AfterCommand
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.Blocking
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnCommand

        object Threads {
            val seen = mutableListOf<String>()
            fun record(function: String) {
                seen += function + ":" + Thread.currentThread().isVirtual
            }
        }

        @CreateAggregate
        data class Create(val id: String)
        data class Created(val id: String)
        data class Add(val item: String)
        data class Added(val item: String)
        data class Remove(val item: String)
        data class Removed(val item: String)
        data class Audited(val by: String)

        abstract class Base(protected val state: CartState) {
            @Blocking
            @OnCommand
            open fun onRemove(command: Remove): Removed = Removed(command.item)
        }

        @AggregateRoot
        class Cart(state: CartState) : Base(state) {
            @OnCommand
            fun onCreate(command: Create): Created {
                Threads.record("onCreate")
                return Created(command.id)
            }

            @Blocking
            @OnCommand
            fun onAdd(command: Add): Added {
                Threads.record("onAdd")
                return Added(command.item)
            }

            // Overrides a @Blocking function without repeating the annotation: still blocking.
            override fun onRemove(command: Remove): Removed {
                Threads.record("onRemove")
                return super.onRemove(command)
            }

            @Blocking
            @AfterCommand(include = [Add::class])
            fun audit(command: Add): Audited {
                Threads.record("audit")
                return Audited("audit")
            }
        }

        class CartState {
            val items = mutableListOf<String>()
            fun onSourcing(event: Created) {}
            fun onSourcing(event: Added) { items += event.item }
            fun onSourcing(event: Removed) { items -= event.item }
            fun onSourcing(event: Audited) {}
        }
        """.trimIndent(),
    )

    private val scenario = SourceFile.kotlin(
        "Scenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample

        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.memory.InMemoryEventLog

        fun run(): String = runBlocking {
            val model = cartModel("shop")
            val kernel = Kernel(InMemoryEventLog())
            val ref = model.type.ref("tenant", "cart-1")
            val outcomes = listOf(Create("cart-1"), Add("apple"), Remove("apple")).mapIndexed { index, body ->
                kernel.execute(model, Command("c" + index, "r" + index, ref, body))
            }
            outcomes.joinToString(",") { (it as CommandOutcome.Committed).stream.events.size.toString() } +
                " " + Threads.seen
        }
        """.trimIndent(),
    )

    @Test
    fun `a Blocking command function and after hook should run on a virtual thread and the others inline`() {
        val (compilation, result) = compileModel("generate", cart, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val source = compilation.workingDir.walkTopDown().single { it.name == "CartModel.kt" }.readText()
        source.assert().contains("decideBlocking<sample.Add>").contains("decideBlocking<sample.Remove>")
            .contains("decide<sample.Create>").contains("afterBlocking(")
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null).assert().isEqualTo(
            "1,2,1 [onCreate:false, onAdd:true, audit:true, onRemove:true]",
        )
    }

    private fun failing(members: String, state: String = "", topLevel: String = ""): String {
        val source = SourceFile.kotlin(
            "Order.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.Blocking
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.Tags
            import me.ahoo.wow.api.annotation.Upgrade

            @CreateAggregate
            data class Place(val id: String)
            data class Placed(val id: String)

            @AggregateRoot
            class Order(private val state: OrderState) {
                fun onCommand(command: Place): Placed = Placed(command.id)
                $members
            }

            class OrderState {
                $state
            }

            $topLevel
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        return result.messages
    }

    @Test
    fun `Blocking on a sourcing function should be a compile error`() {
        failing("", state = "@Blocking fun onSourcing(event: Placed) {}")
            .assert().contains("sourcing function onSourcing is annotated @Blocking: evolving does no I/O")
    }

    @Test
    fun `Blocking on a Tags function should be a compile error`() {
        failing(
            "",
            state = """
                fun onSourcing(event: Placed) {}
                @Blocking @Tags fun tags(): Map<String, List<String>> = emptyMap()
            """.trimIndent(),
        ).assert().contains("@Tags function tags is annotated @Blocking: deriving tags does no I/O")
    }

    @Test
    fun `Blocking on an upgrade should be a compile error`() {
        failing(
            "",
            state = "fun onSourcing(event: Placed) {}",
            topLevel = """
                @Blocking
                @Upgrade(event = Placed::class, from = 1)
                fun upgradePlaced(node: tools.jackson.databind.JsonNode): tools.jackson.databind.JsonNode = node
            """.trimIndent(),
        ).assert().contains("upgrade sample.upgradePlaced is annotated @Blocking: an upgrade does no I/O")
    }

    @Test
    fun `Blocking on a function returning a Reactor publisher should be a warning`() {
        val source = SourceFile.kotlin(
            "Order.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.Blocking
            import me.ahoo.wow.api.annotation.CreateAggregate
            import reactor.core.publisher.Mono

            @CreateAggregate
            data class Place(val id: String)
            data class Placed(val id: String)

            @AggregateRoot
            class Order(private val state: OrderState) {
                @Blocking
                fun onCommand(command: Place): Mono<Placed> = Mono.just(Placed(command.id))
            }

            class OrderState {
                fun onSourcing(event: Placed) {}
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.messages.assert().contains(
            "onCommand is annotated @Blocking and returns a Reactor publisher: it is called on a virtual thread, " +
                "but a publisher should not block",
        )
        compilation.workingDir.walkTopDown().single { it.name == "OrderModel.kt" }.readText()
            .assert().contains("decideBlocking<sample.Place>")
    }
}
