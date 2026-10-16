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
 * Whether a root is its own state is decided explicitly (finding A4-4): a root with a constructor taking one state
 * class, the class with the sourcing functions, is built from it, whatever its other constructors or default values.
 */
@OptIn(ExperimentalCompilerApi::class)
class RootStateTest {
    private val types = SourceFile.kotlin(
        "Types.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class Create(val id: String)
        data class Created(val id: String)
        data class Add(val item: String)
        data class Added(val item: String)

        class CartState {
            val items = mutableListOf<String>()
            fun onSourcing(event: Created) { items += "created" }
            fun onSourcing(event: Added) { items += event.item }
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
            kernel.execute(model, Command("c1", "r1", ref, Create("cart-1")))
            val outcome = kernel.execute(model, Command("c2", "r2", ref, Add("apple"))) as CommandOutcome.Committed
            model.stateType.simpleName + (outcome.state as CartState).items
        }
        """.trimIndent(),
    )

    private fun assertBuiltFromState(root: SourceFile) {
        val (compilation, result) = compileModel("generate", types, root, scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().single { it.name == "CartModel.kt" }.readText()
            .assert().contains("aggregate<sample.CartState>", "sample.Cart(state).onCommand(command)")
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null)
            .assert().isEqualTo("CartState[created, apple]")
    }

    @Test
    fun `a Kotlin root whose state parameter has a default value should be built from the state`() {
        assertBuiltFromState(
            SourceFile.kotlin(
                "Cart.kt",
                """
                package sample

                import me.ahoo.wow.api.annotation.AggregateRoot

                @AggregateRoot
                class Cart(private val state: CartState = CartState()) {
                    fun onCommand(command: Create): Created = Created(command.id)
                    fun onCommand(command: Add): Added = Added(command.item)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a Java root with a no-argument constructor first should be built from the state`() {
        assertBuiltFromState(
            SourceFile.java(
                "Cart.java",
                """
                package sample;

                import me.ahoo.wow.api.annotation.AggregateRoot;

                @AggregateRoot
                public class Cart {
                    private final CartState state;

                    public Cart() {
                        this(new CartState());
                    }

                    public Cart(CartState state) {
                        this.state = state;
                    }

                    public Created onCommand(Create command) {
                        return new Created(command.getId());
                    }

                    public Added onCommand(Add command) {
                        return new Added(command.getItem());
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a root that is its own state should stay its own state`() {
        val root = SourceFile.kotlin(
            "Counter.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Start(val at: Int)
            data class Started(val at: Int)

            @AggregateRoot
            class Counter(var count: Int = 0) {
                fun onCommand(command: Start): Started = Started(command.at)
                fun onSourcing(event: Started) { count = event.at }
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", root)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().single { it.name == "CounterModel.kt" }.readText()
            .assert().contains("aggregate<sample.Counter>", "state.onCommand(command)")
    }

    @Test
    fun `constructors taking two different states should be a compile error`() {
        val root = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            class OtherState {
                fun onSourcing(event: Added) {}
            }

            @AggregateRoot
            class Cart(private val state: CartState) {
                constructor(other: OtherState) : this(CartState())
                fun onCommand(command: Create): Created = Created(command.id)
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", types, root)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "the root's constructors take the states CartState, OtherState: a root is built from one state",
        )
    }

    @Test
    fun `a constructor taking its state and more should be a compile error`() {
        val root = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            @AggregateRoot
            class Cart(private val state: CartState, private val label: String) {
                fun onCommand(command: Create): Created = Created(command.id)
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", types, root)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("the root's constructor must take only its state")
    }
}
