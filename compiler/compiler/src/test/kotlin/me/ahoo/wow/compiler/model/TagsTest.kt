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

/** `@Tags` (decision D220) as the model processor finds, checks and generates it. */
@OptIn(ExperimentalCompilerApi::class)
class TagsTest {
    /** A shipment whose state declares [members] (Kotlin source inside the state class). */
    private fun shipment(members: String, root: String = ""): SourceFile = SourceFile.kotlin(
        "Shipment.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.Tags

        @CreateAggregate
        data class Ship(val country: String)
        data class Shipped(val country: String)

        @AggregateRoot
        class Shipment(private val state: ShipmentState) {
            fun onCommand(command: Ship): Shipped = Shipped(command.country)
            $root
        }

        class ShipmentState {
            var country: String = ""
            fun onSourcing(event: Shipped) { country = event.country }
            $members
        }
        """.trimIndent(),
    )

    private fun scenario(): SourceFile = SourceFile.kotlin(
        "ShipmentScenario.kt",
        """
        @file:OptIn(me.ahoo.wow.api.annotation.InternalWowApi::class)

        package sample

        import kotlinx.coroutines.runBlocking
        import me.ahoo.wow.api.decide.Command
        import me.ahoo.wow.kernel.CommandOutcome
        import me.ahoo.wow.kernel.Kernel
        import me.ahoo.wow.kernel.memory.InMemoryEventLog

        fun tags(): Map<String, List<String>> = runBlocking {
            val model = shipmentModel("logistics")
            val ref = model.type.ref("tenant", "s-1")
            val outcome = Kernel(InMemoryEventLog()).execute(model, Command("c", "r", ref, Ship("CN")))
            (outcome as CommandOutcome.Committed).tags
        }
        """.trimIndent(),
    )

    private fun generated(members: String): String {
        val (compilation, result) = compileModel("generate", shipment(members), scenario())
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        result.classLoader.loadClass("sample.ShipmentScenarioKt").getMethod("tags").invoke(null)
            .assert().isEqualTo(mapOf("country" to listOf("CN")))
        return compilation.workingDir.walkTopDown().single { it.name == "ShipmentModel.kt" }.readText()
    }

    @Test
    fun `a Tags function derives the tags of the generated model`() {
        generated("@Tags fun tags(): Map<String, List<String>> = mapOf(\"country\" to listOf(country))")
            .assert().contains("tags { state -> state.tags() }")
    }

    @Test
    fun `a Tags property with set values derives them too`() {
        generated("@Tags val abac: Map<String, Set<String>> get() = mapOf(\"country\" to setOf(country))")
            .assert().contains("tags { state -> state.abac }")
    }

    @Test
    fun `a state without Tags generates no call`() {
        val (compilation, result) = compileModel("generate", shipment(""))
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        compilation.workingDir.walkTopDown().single { it.name == "ShipmentModel.kt" }.readText()
            .assert().doesNotContain("tags {")
    }

    @Test
    fun `two Tags members are a compile error`() {
        val (_, result) = compileModel(
            "generate",
            shipment(
                """
                @Tags fun tags(): Map<String, List<String>> = emptyMap()
                @Tags val more: Map<String, List<String>> get() = emptyMap()
                """.trimIndent(),
            ),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("the state ShipmentState marks tags(), more @Tags: a state has at most one member deriving its tags")
    }

    @Test
    fun `a Tags function with parameters, suspending or private is a compile error`() {
        listOf(
            "@Tags fun tags(prefix: String): Map<String, List<String>> = emptyMap()" to "takes parameters",
            "@Tags suspend fun tags(): Map<String, List<String>> = emptyMap()" to "suspends: deriving tags does no I/O",
            "@Tags private fun tags(): Map<String, List<String>> = emptyMap()" to "is private",
            "@Tags fun String.tags(): Map<String, List<String>> = emptyMap()" to "is an extension",
        ).forEach { (member, problem) ->
            val (_, result) = compileModel("generate", shipment(member))
            result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
            result.messages.assert().contains("@Tags function tags $problem")
        }
    }

    @Test
    fun `a private Tags property is a compile error`() {
        val (_, result) = compileModel(
            "generate",
            shipment("@Tags private val tags: Map<String, List<String>> get() = emptyMap()"),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("@Tags property tags is private")
    }

    @Test
    fun `a Tags member of another return type is a compile error`() {
        listOf(
            "Map<String, String>" to "mapOf()",
            "Map<String, List<String>>?" to "null",
            "Map<String, List<Int>>" to "mapOf()",
            "Map<Int, List<String>>" to "mapOf()",
            "List<String>" to "listOf()",
            "Map<String, List<String?>>" to "mapOf()",
        ).forEach { (type, value) ->
            val (_, result) = compileModel("generate", shipment("@Tags fun tags(): $type = $value"))
            result.exitCode.assert().withFailMessage { type }.isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
            result.messages.assert().contains("@Tags function tags returns")
                .contains("not Map<String, List<String>> (or Set or Collection values)")
        }
    }

    @Test
    fun `Tags on the aggregate root rather than its state is a compile error`() {
        val (_, result) = compileModel(
            "generate",
            shipment("", root = "@Tags fun tags(): Map<String, List<String>> = emptyMap()"),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "aggregate root Shipment's function tags is annotated @Tags, which only a member of an aggregate's state takes",
        )
    }
}
