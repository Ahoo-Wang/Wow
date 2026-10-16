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
 * A model the generated code could not compile is reported on the user's declaration, not inside the generated file
 * (finding A4-11), or generated so that it compiles.
 */
@OptIn(ExperimentalCompilerApi::class)
class DeclarationErrorsTest {
    private val options = mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop")

    private fun cart(rootModifier: String, root: String): SourceFile = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.OnCommand

        @CreateAggregate
        data class Create(val id: String)
        data class Created(val id: String)

        @AggregateRoot
        $rootModifier class Cart($rootModifier val state: CartState) {
            $root
        }

        $rootModifier class CartState {
            var id: String = ""
            fun onSourcing(event: Created) { id = event.id }
        }
        """.trimIndent(),
    )

    @Test
    fun `an internal root and state should get an internal factory`() {
        val scenario = SourceFile.kotlin(
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
                val outcome = Kernel(InMemoryEventLog())
                    .execute(model, Command("c", "r", model.type.ref("t", "cart-1"), Create("cart-1")))
                ((outcome as CommandOutcome.Committed).state as CartState).id
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel(
            "generate",
            options,
            cart("internal", "fun onCommand(command: Create): Created = Created(command.id)"),
            scenario,
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().single { it.name == "CartModel.kt" }.readText()
            .assert().contains("internal fun cartModel(")
        result.classLoader.loadClass("sample.ScenarioKt").getMethod("run").invoke(null).assert().isEqualTo("cart-1")
    }

    private fun failing(root: String): String {
        val (_, result) = compileModel("generate", cart("", root))
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().doesNotContain("CartModel.kt")
        return result.messages
    }

    @Test
    fun `a protected handler should be a compile error on the root`() {
        failing("@OnCommand protected fun onCreate(command: Create): Created = Created(command.id)").assert().contains(
            "onCreate is protected: the generated model calls it from outside the class, so it is public or internal",
        )
    }

    @Test
    fun `a parameter with a default value should be a compile error on the root`() {
        failing("fun onCommand(command: Create, strict: Boolean = true): Created = Created(command.id)")
            .assert().contains(
                "onCommand's parameter strict has a default value: the generated model passes every parameter " +
                    "after the command",
            )
    }

    @Test
    fun `an event above revision 1 without an upgrade should say where the upgrade goes`() {
        val source = SourceFile.kotlin(
            "Order.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.Revision

            @CreateAggregate
            data class Place(val id: String)
            @Revision(2)
            data class Placed(val id: String)

            @AggregateRoot
            class Order(private val state: OrderState) {
                fun onCommand(command: Place): Placed = Placed(command.id)
            }

            class OrderState {
                fun onSourcing(event: Placed) {}
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", source)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "in this module, the aggregate's: the processor reads no @Upgrade of a dependency, such as the module of " +
                "the events",
        )
    }
}
