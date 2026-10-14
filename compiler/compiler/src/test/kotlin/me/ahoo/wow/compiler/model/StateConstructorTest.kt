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

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** Decision D203: the state's constructor takes the id only when the domain needs it. */
@OptIn(ExperimentalCompilerApi::class)
class StateConstructorTest {
    private fun counter(state: String): SourceFile = SourceFile.kotlin(
        "Counter.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate

        @CreateAggregate
        data class Start(val by: Int)
        data class Started(val by: Int)

        @AggregateRoot
        class Counter(private val state: CounterState) {
            fun onCommand(command: Start): Started = Started(command.by)
        }

        $state
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
        import me.ahoo.wow.kernel.memory.InMemoryEventLog
        import me.ahoo.wow.kernel.Kernel

        fun run(): String = runBlocking {
            val model = counterModel("count")
            val kernel = Kernel(InMemoryEventLog())
            val outcome = kernel.execute(model, Command("c1", "r1", model.type.ref("t", "c-1"), Start(3)))
            ((outcome as CommandOutcome.Committed).state as CounterState).describe()
        }
        """.trimIndent(),
    )

    private fun generate(state: String): Pair<JvmCompilationResult, String> {
        val (compilation, result) = compileModel("generate", counter(state), scenario)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val source = compilation.workingDir.walkTopDown().single { it.name == "CounterModel.kt" }.readText()
        return result to source
    }

    private fun JvmCompilationResult.run(): Any? {
        val scenario = classLoader.loadClass("sample.ScenarioKt")
        return scenario.getMethod("run").invoke(null)
    }

    @Test
    fun `a state without a constructor parameter is built without arguments`() {
        val (result, source) = generate(
            """
            class CounterState {
                var count = 0
                fun onSourcing(event: Started) { count += event.by }
                fun describe() = "count=" + count
            }
            """,
        )
        source.assert().contains("initial { _ -> sample.CounterState() }")
        result.run().assert().isEqualTo("count=3")
    }

    @Test
    fun `a state whose parameters all have defaults is built without arguments`() {
        val (result, source) = generate(
            """
            class CounterState(var count: Int = 0, val label: String = "n") {
                fun onSourcing(event: Started) { count += event.by }
                fun describe() = label + "=" + count
            }
            """,
        )
        source.assert().contains("initial { _ -> sample.CounterState() }")
        result.run().assert().isEqualTo("n=3")
    }

    @Test
    fun `a state that takes the id is built with the ref's`() {
        val (result, source) = generate(
            """
            class CounterState(val id: String) {
                var count = 0
                fun onSourcing(event: Started) { count += event.by }
                fun describe() = id + "=" + count
            }
            """,
        )
        source.assert().contains("initial { ref -> sample.CounterState(ref.id) }")
        result.run().assert().isEqualTo("c-1=3")
    }

    @Test
    fun `a state that takes the id and the tenant id is built with the ref's`() {
        val (result, source) = generate(
            """
            class CounterState(val id: String, val tenantId: String) {
                var count = 0
                fun onSourcing(event: Started) { count += event.by }
                fun describe() = tenantId + "/" + id + "=" + count
            }
            """,
        )
        source.assert().contains("initial { ref -> sample.CounterState(ref.id, ref.tenantId) }")
        result.run().assert().isEqualTo("t/c-1=3")
    }

    @Test
    fun `a root whose parameters all have defaults is its own state`() {
        val root = SourceFile.kotlin(
            "Tally.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.CreateAggregate

            @CreateAggregate
            data class Open(val by: Int)
            data class Opened(val by: Int)

            @AggregateRoot
            class Tally(var marks: List<Int> = emptyList()) {
                fun onCommand(command: Open): Opened = Opened(command.by)
                fun onSourcing(event: Opened) { marks = marks + event.by }
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", root)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().single { it.name == "TallyModel.kt" }.readText().assert()
            .contains("me.ahoo.wow.kernel.aggregate<sample.Tally>(context, \"tally\")")
            .contains("initial { _ -> sample.Tally() }")
            .contains("state.onCommand(command)")
    }

    @Test
    fun `a state constructor of another shape is a problem`() {
        val (_, result) = compileModel(
            "generate",
            counter(
                """
                class CounterState(val count: Int) {
                    fun onSourcing(event: Started) = Unit
                }
                """,
            ),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "the state CounterState needs a constructor () (recommended: the aggregate's id is in its ref), or one " +
                "whose parameters all have default values; (id: String) and (id: String, tenantId: String) are " +
                "allowed when the domain needs the id",
        )
    }

    @Test
    fun `a state without a constructor is a problem`() {
        val (_, result) = compileModel(
            "generate",
            counter(
                """
                interface CounterState {
                    fun onSourcing(event: Started) = Unit
                }
                """,
            ),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("the state CounterState needs a constructor ()")
    }
}
