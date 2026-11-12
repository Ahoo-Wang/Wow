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
}
