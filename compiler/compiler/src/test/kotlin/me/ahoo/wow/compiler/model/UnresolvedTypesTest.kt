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
 * A parameter whose type does not resolve, such as 9.x's `DomainEvent<E>` left in a state migrated to Wow 10, is a
 * problem that names it, not a crash of the processor: the root is checked for unresolved types before it is
 * analyzed, but its state and the classes it extends are not.
 */
@OptIn(ExperimentalCompilerApi::class)
class UnresolvedTypesTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    @Test
    fun `a sourcing function taking a type that does not resolve should say what a sourcing function takes`() {
        val state = SourceFile.kotlin(
            "Account.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.OnSourcing

            @CreateAggregate
            data class Open(val holder: String)
            data class Opened(val holder: String)
            data class Closed(val holder: String)

            @AggregateRoot
            class Account(private val state: AccountState) {
                fun onCommand(command: Open): Opened = Opened(command.holder)
            }

            class AccountState {
                fun <E : Any> onSourcing(event: DomainEvent<E>) = Unit

                @OnSourcing
                fun closed(event: Closed, commit: EventCommit) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, state)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        val hint = "which does not resolve: a sourcing function takes (event[, commit: EventStream]), the event's " +
            "own class and, to read when it was committed and by whom, its me.ahoo.wow.api.read.EventStream; " +
            "9.x's DomainEvent<E> is (event: E, commit: EventStream)"
        result.messages.assert()
            .doesNotContain("NullPointerException")
            .contains("sourcing function onSourcing takes DomainEvent, $hint")
            .contains("sourcing function closed takes EventCommit, $hint")
    }

    @Test
    fun `a dependency of a type that does not resolve should be named`() {
        val root = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Open(val holder: String)
            data class Opened(val holder: String)

            abstract class Base {
                fun onCommand(command: Open, prices: PriceBook): Opened = Opened(command.holder)
            }

            @AggregateRoot
            class Cart(val id: String) : Base() {
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, root)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .doesNotContain("NullPointerException")
            .contains("onCommand's parameter prices is of PriceBook, which does not resolve")
    }
}
