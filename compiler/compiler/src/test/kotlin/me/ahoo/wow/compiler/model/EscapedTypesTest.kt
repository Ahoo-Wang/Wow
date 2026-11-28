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
 * A package or a class a domain names in backticks, a keyword segment or a name with a space, is written by its quoted
 * name wherever the generated sources name it (#6388): KSP gives each segment without its backticks, and the generated
 * source quotes it again.
 */
@OptIn(ExperimentalCompilerApi::class)
class EscapedTypesTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    /** A counter whose package and classes have the names given, ordinary ones by default. */
    @Suppress("LongParameterList", "LongMethod")
    private fun compiles(
        packageName: String = "sample",
        command: String = "Open",
        event: String = "Opened",
        state: String = "CounterState",
        root: String = "Counter",
        service: String = "Clock",
        processor: String = "CounterProjection",
        routes: String = "CounterRoutes",
        body: String = "Summary",
        outer: String = "Commands",
    ) {
        val source = SourceFile.kotlin(
            "Counter.kt",
            """
            package $packageName

            import me.ahoo.wow.api.annotation.Access
            import me.ahoo.wow.api.annotation.AfterCommand
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.Name
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.RouteMethod
            import me.ahoo.wow.api.annotation.ServiceRoute
            import me.ahoo.wow.api.subscribe.StateEvent

            @Name("Open") @CreateAggregate data class `$command`(val value: Int)
            object `$outer` {
                @Name("Close") data class Close(val value: Int)
            }
            @Name("Opened") data class `$event`(val value: Int)
            @Name("Audited") data class Audited(val value: Int)
            fun interface `$service` { fun now(): Long }

            @AggregateRoot @Name("counter")
            class `$root`(private val state: `$state`) {
                fun onCommand(command: `$command`, clock: `$service`): `$event` = `$event`(command.value)
                fun onCommand(command: `$outer`.Close): `$event` = `$event`(command.value)
                @AfterCommand(include = [`$command`::class]) fun audit(command: `$command`): Audited = Audited(1)
            }

            class `$state` {
                var value: Int = 0
                @Access val team: String get() = value.toString()
                fun onSourcing(event: `$event`) { value = event.value }
                fun onSourcing(event: Audited) { value = event.value }
            }

            @Projection @Name("counter-projection")
            class `$processor`(private val clock: `$service`) {
                @OnEvent fun onEvent(event: StateEvent<`$state`>) = Unit
                // A handler of an event class: the processor finds the class by the event's qualified name.
                @OnEvent fun onOpened(event: `$event`) = Unit
            }

            data class `$body`(val value: Int)

            class `$routes` {
                @ServiceRoute(method = RouteMethod.GET, path = "summary", name = "summary")
                suspend fun summary(): `$body` = `$body`(1)

                @ServiceRoute(method = RouteMethod.POST, path = "count", name = "count")
                suspend fun count(request: `$body`): `$body` = request
            }
            """.trimIndent(),
        )
        compileLibrary(source) // The domain compiles without the processor: only its generated sources can fail.
        val (_, result) = compileModel("generate", options, source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }

    @Test
    fun `ordinary names should compile`() = compiles()

    @Test
    fun `a package with a keyword segment should compile`() = compiles(packageName = "sample.`when`")

    @Test
    fun `a package with a segment with a space should compile`() = compiles(packageName = "sample.`my shop`")

    @Test
    fun `a command class named by a keyword should compile`() = compiles(command = "when")

    @Test
    fun `an event class named with a space should compile`() = compiles(event = "Item opened")

    @Test
    fun `a state class named by a keyword should compile`() = compiles(state = "object")

    @Test
    fun `an aggregate root named with a space should compile`() = compiles(root = "My counter")

    @Test
    fun `a dependency class named by a keyword should compile`() = compiles(service = "in")

    @Test
    fun `a processor named with a space should compile`() = compiles(processor = "Counter projection")

    @Test
    fun `a route handler class named by a keyword should compile`() = compiles(routes = "is")

    @Test
    fun `a route body class named with a space should compile`() = compiles(body = "Counter summary")

    @Test
    fun `a nested command in a class named by a keyword should compile`() = compiles(outer = "typealias")
}
