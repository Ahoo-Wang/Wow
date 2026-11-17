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
 * A type argument is written in full wherever the generated sources name a type (#6389): its nullability and its
 * variance with it, at every depth. `Box<String?>` written as `Box<String>` is another type of an invariant `Box`, which
 * the function taking it does not accept.
 */
@OptIn(ExperimentalCompilerApi::class)
class TypeArgumentsTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    private enum class Taker { DECIDE, AFTER, PROCESSOR }

    /** A counter whose [taker] takes a dependency of [type]. */
    private fun compiles(type: String, taker: Taker = Taker.DECIDE): KotlinCompilation {
        val decide = if (taker == Taker.DECIDE) ", dependency: $type" else ""
        val after = if (taker == Taker.AFTER) ", dependency: $type" else ""
        val processor = if (taker == Taker.PROCESSOR) "(private val dependency: $type)" else ""
        val source = SourceFile.kotlin(
            "Counter.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AfterCommand
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.RouteMethod
            import me.ahoo.wow.api.annotation.ServiceRoute
            import me.ahoo.wow.api.subscribe.StateEvent

            class Box<T>(val value: T)
            @CreateAggregate data class Open(val value: Int)
            data class Opened(val value: Int)
            data class Audited(val value: Int)

            @AggregateRoot
            class Counter(private val state: CounterState) {
                fun onCommand(command: Open$decide): Opened = Opened(command.value)
                @AfterCommand fun audit(command: Open$after): Audited = Audited(command.value)
            }

            class CounterState {
                var value: Int = 0
                fun onSourcing(event: Opened) { value = event.value }
                fun onSourcing(event: Audited) { value = event.value }
            }

            @Projection
            class CounterProjection$processor {
                @OnEvent fun onEvent(event: StateEvent<CounterState>) = Unit
            }

            data class Name(val value: String)
            data class Count(val value: Int)

            class CounterRoutes {
                @ServiceRoute(method = RouteMethod.POST, path = "names", name = "names")
                suspend fun names(names: List<Name?>): Count = Count(names.size)
            }
            """.trimIndent(),
        )
        compileLibrary(source) // The domain compiles without the processor: only its generated sources can fail.
        val (compilation, result) = compileModel("generate", options, source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        return compilation
    }

    @Test
    fun `a plain argument should compile`() {
        compiles("Box<String>")
    }

    @Test
    fun `a nullable argument should compile`() {
        compiles("Box<String?>")
    }

    @Test
    fun `a nullable argument of a nested argument should compile`() {
        compiles("Box<Box<String?>>")
    }

    @Test
    fun `a covariant argument of a nested argument should compile`() {
        compiles("Box<MutableList<out Number>>")
    }

    @Test
    fun `a contravariant argument of a nested argument should compile`() {
        compiles("Box<Box<in Int>>")
    }

    @Test
    fun `a star projection of a nested argument should compile`() {
        compiles("Box<Box<*>>")
    }

    @Test
    fun `a nullable argument of a nullable type should compile`() {
        compiles("Box<String?>?")
    }

    @Test
    fun `a function type taking a nullable value should compile`() {
        compiles("(String?) -> Unit")
    }

    @Test
    fun `an after hook's nullable argument should compile`() {
        compiles("Box<Box<String?>>", Taker.AFTER)
    }

    @Test
    fun `a processor's covariant argument of a nested argument should compile`() {
        compiles("Box<MutableList<out Number>>", Taker.PROCESSOR)
    }

    @Test
    fun `a processor's nullable argument should compile, as before`() {
        compiles("Box<String?>", Taker.PROCESSOR)
    }

    @Test
    fun `a route's body should be read with its element's nullability`() {
        val compilation = compiles("Box<String>")
        val provider = compilation.workingDir.walkTopDown().first { it.name == "ShopDomainModelProvider.kt" }
        provider.readText().assert()
            .contains("kotlin.reflect.typeOf<kotlin.collections.List<sample.Name?>>()")
    }
}
