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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * A model symbol that never validates is deferred to the end of KSP, and nothing of it is generated: the compilation
 * fails with an error naming it and the types of it that did not resolve, rather than leaving the module's Manifest
 * fragment and contract without it.
 */
@OptIn(ExperimentalCompilerApi::class)
class NeverValidatedTest {
    private val context = SourceFile.kotlin(
        "ShopContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "shop")
        object ShopContext
        """.trimIndent(),
    )

    @Test
    fun `an aggregate root naming types that do not resolve should fail, naming them`() {
        val cart = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot
            class Cart(private val state: CartState) {
                val note: Absent? = null

                fun onCommand(command: Open, tags: List<Unknown>, @Suppress("unused") any: List<*>): Missing = TODO()

                class Box<T : Gone>
            }

            class CartState {
                fun onSourcing(event: Opened) {}
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", context, cart)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "sample.Cart never validated, so nothing of it was generated: Absent, Gone, Missing, Unknown did not resolve " +
                "on the class path KSP read. If the sources compile without it, KSP's environment failed to read the " +
                "class path: rerun the build, or upgrade KSP",
        )
        compilation.workingDir.walkTopDown()
            .none { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            .assert().isTrue()
    }

    @Test
    fun `a symbol whose unresolved type is in an annotation argument should fail, naming no type`() {
        val cart = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.OnCommand
            import me.ahoo.wow.api.decide.Decision

            @CreateAggregate
            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot
            class Cart(private val state: CartState) {
                @OnCommand(produces = [Missing::class])
                fun onOpen(command: Open): Decision = Decision.NoOp
            }

            class CartState {
                fun onSourcing(event: Opened) {}
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", context, cart)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "sample.Cart never validated, so nothing of it was generated: a type it names did not resolve",
        )
    }

    @Test
    fun `a subscription processor whose event does not resolve should fail, naming it`() {
        val saga = SourceFile.kotlin(
            "Notifier.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.StatelessSaga

            @StatelessSaga
            class Notifier {
                fun onEvent(event: Shipped) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", context, saga)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "sample.Notifier never validated, so nothing of it was generated: Shipped did not resolve",
        )
    }
}
