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
 * A function or property a domain names in backticks, a keyword or a name with a space, is called by its quoted name
 * (#6282): KSP gives the name without its backticks, and the generated call quotes it again.
 */
@OptIn(ExperimentalCompilerApi::class)
class EscapedNamesTest {
    private val options = mapOf(
        ModelSymbolProcessorProvider.CONTEXT_OPTION to "shop",
        ModelSymbolProcessorProvider.MODULE_OPTION to "shop-domain",
    )

    /** A counter whose functions and access attribute have the names given, ordinary ones by default. */
    @Suppress("LongParameterList")
    private fun compiles(
        decide: String = "onCommand",
        after: String = "audit",
        sourcing: String = "onSourcing",
        upgrade: String = "addSource",
        access: String = "team",
        projection: String = "onEvent",
        route: String = "summary",
    ) {
        val source = SourceFile.kotlin(
            "Counter.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.Access
            import me.ahoo.wow.api.annotation.AfterCommand
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.OnCommand
            import me.ahoo.wow.api.annotation.OnEvent
            import me.ahoo.wow.api.annotation.OnSourcing
            import me.ahoo.wow.api.annotation.Projection
            import me.ahoo.wow.api.annotation.Revision
            import me.ahoo.wow.api.annotation.RouteMethod
            import me.ahoo.wow.api.annotation.ServiceRoute
            import me.ahoo.wow.api.annotation.Upgrade
            import me.ahoo.wow.api.subscribe.StateEvent
            import tools.jackson.databind.node.ObjectNode

            @CreateAggregate data class Open(val value: Int)
            @Revision(2) data class Opened(val value: Int)
            data class Audited(val value: Int)

            @AggregateRoot
            class Counter(private val state: CounterState) {
                @OnCommand fun `$decide`(command: Open): Opened = Opened(command.value)
                @AfterCommand fun `$after`(command: Open): Audited = Audited(command.value)
            }

            class CounterState {
                var value: Int = 0
                @Access val `$access`: String get() = value.toString()
                @OnSourcing fun `$sourcing`(event: Opened) { value = event.value }
                fun onSourcing(event: Audited) { value = event.value }
            }

            @Upgrade(event = Opened::class, from = 1)
            fun `$upgrade`(node: ObjectNode): ObjectNode = node

            @Projection
            class CounterProjection {
                @OnEvent fun `$projection`(event: StateEvent<CounterState>) = Unit
            }

            data class Summary(val value: Int)

            class CounterRoutes {
                @ServiceRoute(method = RouteMethod.GET, path = "summary", name = "summary")
                suspend fun `$route`(): Summary = Summary(1)
            }
            """.trimIndent(),
        )
        compileLibrary(source) // The domain compiles without the processor: only its generated calls can fail.
        val (_, result) = compileModel("generate", options, source)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }

    @Test
    fun `ordinary names should compile`() = compiles()

    @Test
    fun `a decide function named by a keyword should compile`() = compiles(decide = "when")

    @Test
    fun `an after hook named with a space should compile`() = compiles(after = "audit it")

    @Test
    fun `a sourcing function named with a space should compile`() = compiles(sourcing = "apply event")

    @Test
    fun `an upgrade named by a keyword should compile`() = compiles(upgrade = "object")

    @Test
    fun `an access attribute named by a keyword should compile`() = compiles(access = "in")

    @Test
    fun `an event handler named by a keyword should compile`() = compiles(projection = "when")

    @Test
    fun `a service route function named by a keyword should compile`() = compiles(route = "when")
}
