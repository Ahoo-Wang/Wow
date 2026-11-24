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

/** What the subscription resolver refuses ([SubscriptionResolver]): names, `@Name`s and processors the binding cannot build. */
@OptIn(ExperimentalCompilerApi::class)
class SubscriptionResolverTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

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
    fun `a processor the binding cannot construct or call should fail the compilation`() {
        val broken = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.DependencyName
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
            class Qualified(@DependencyName(" ") val service: Runnable) {
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
            .contains("processor Qualified's parameter service has a blank @DependencyName")
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

    @Test
    fun `a protected handler of any kind should fail the compilation, naming it`() {
        // The generated binding calls it from outside its class (#6833), as the aggregate side refuses it.
        val guarded = SourceFile.kotlin(
            "Guarded.kt",
            """
            package sample.guarded

            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnRejected
            import me.ahoo.wow.api.annotation.OnSchedule
            import me.ahoo.wow.api.annotation.Saga
            import me.ahoo.wow.api.schedule.ScheduleTick
            import me.ahoo.wow.api.subscribe.CommandReceipt
            import me.ahoo.wow.api.subscribe.StateEvent

            abstract class Base {
                @OnEvent
                protected open fun onEvent(event: StateEvent<*>) = Unit

                @OnRejected
                protected open fun onRejected(rejected: CommandReceipt.Rejected<*>) = Unit

                @OnSchedule(name = "sweep", cron = "@daily")
                protected open fun sweep(tick: ScheduleTick) = Unit
            }

            @Saga
            class GuardedSaga : Base()
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", options, guarded)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.lines().filter { it.startsWith("e: ") && "/ksp/sources/" in it }.assert().isEmpty()
        result.messages.assert().contains(
            "handler GuardedSaga.onEvent is protected: the generated subscription calls it from outside the class",
            "handler GuardedSaga.onRejected is protected",
            "handler GuardedSaga.sweep is protected",
        )
    }

    @Test
    fun `handlers should be told apart by their subscription names, not their function names`() {
        // A handler's subscription is its @Name, else its own name (#6830).
        val source = { functions: String ->
            SourceFile.kotlin(
                "Board.kt",
                """
                package sample.board

                import me.ahoo.wow.api.annotation.Name
                import me.ahoo.wow.api.annotation.OnEvent
                import me.ahoo.wow.api.annotation.OnRejected
                import me.ahoo.wow.api.annotation.Saga
                import me.ahoo.wow.api.subscribe.CommandReceipt
                import me.ahoo.wow.api.subscribe.StateEvent

                @Saga
                class Board {
                    $functions
                }
                """.trimIndent(),
            )
        }
        // Two overloads that a @Name keeps apart are two subscriptions; the generated calls resolve by type.
        val (compilation, apart) = compileModel(
            "generate",
            options,
            source(
                """
                @OnEvent fun on(event: StateEvent<*>) = Unit
                @OnRejected @Name("onRefused") fun on(rejected: CommandReceipt.Rejected<*>) = Unit
                """.trimIndent(),
            ),
        )
        apart.exitCode.assert().withFailMessage { apart.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().first { it.name == "BoardSubscriptions.kt" }.readText().assert()
            .contains("\"Board\", \"on\"", "\"Board\", \"onRefused\"")
        // Two functions that one @Name joins are one subscription name: refused, naming both.
        val (_, joined) = compileModel(
            "generate",
            options,
            source(
                """
                @OnEvent @Name("sync") fun placed(event: StateEvent<*>) = Unit
                @OnRejected @Name("sync") fun refused(rejected: CommandReceipt.Rejected<*>) = Unit
                """.trimIndent(),
            ),
        )
        joined.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        joined.messages.assert()
            .contains(
                "processor Board has two handlers named sync (placed, refused): each is the subscription Board.sync"
            )
    }
}
