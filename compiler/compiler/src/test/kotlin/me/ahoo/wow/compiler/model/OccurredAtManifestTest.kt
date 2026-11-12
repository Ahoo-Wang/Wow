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
import java.io.File

/**
 * `@AggregateId`: a command's property, on its class or an interface it implements, stored or computed, holds the id
 * of the aggregate it acts on. The generated model reads it, and the Manifest names its JSON property and routes the
 * command by no id alone.
 */
@OptIn(ExperimentalCompilerApi::class)
class OccurredAtManifestTest {
    private val context = SourceFile.kotlin(
        "ShopContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "shop")
        object ShopContext
        """.trimIndent(),
    )

    private fun fragments(compilation: KotlinCompilation): List<File> =
        compilation.workingDir.walkTopDown()
            .filter { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            .toList()

    private fun generated(compilation: KotlinCompilation, name: String): String =
        compilation.workingDir.walkTopDown().filter { it.name == name }.single().readText()

    /** An order with [commands]. */
    private fun order(commands: String, functions: String): SourceFile = SourceFile.kotlin(
        "Order.kt",
        """
        package sample

        import com.fasterxml.jackson.annotation.JsonIgnore
        import com.fasterxml.jackson.annotation.JsonProperty
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.AllowCreate
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.InternalCommand
        import me.ahoo.wow.api.annotation.OccurredAt
        import java.time.Instant

        $commands
        data class OrderImported(val source: String)

        @AggregateRoot
        class Order(val id: String) {
            $functions
            fun onSourcing(event: OrderImported) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `a creation's occurred-at property should be read by the model and named by the Manifest`() {
        val (compilation, result) = compileModel(
            "generate",
            context,
            order(
                commands = """
                @CreateAggregate
                @InternalCommand
                data class ImportOrder(val source: String, @OccurredAt val placedAt: Long)
                @CreateAggregate
                data class OpenOrder(val source: String, @OccurredAt @JsonProperty("placed") val placedAt: Long?)
                @CreateAggregate
                data class PlaceOrder(val source: String)
                """.trimIndent(),
                functions = """
                fun onCommand(command: ImportOrder): OrderImported = OrderImported(command.source)
                fun onCommand(command: OpenOrder): OrderImported = OrderImported(command.source)
                fun onCommand(command: PlaceOrder): OrderImported = OrderImported(command.source)
                """.trimIndent(),
            ),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "OrderModel.kt").assert()
            .contains("occurredAt<sample.ImportOrder> { it.`placedAt` }")
            .contains("occurredAt<sample.OpenOrder> { it.`placedAt` }")
            .doesNotContain("occurredAt<sample.PlaceOrder>")
        val commands = ManifestFragmentFixtures.read(fragments(compilation).single()).contexts.single().aggregates
            .single().commands.associateBy { it.name }
        commands.getValue("ImportOrder").occurredAtProperty.assert().isEqualTo("placedAt")
        // Its name in JSON, which a sender without the class writes.
        commands.getValue("OpenOrder").occurredAtProperty.assert().isEqualTo("placed")
        commands.getValue("PlaceOrder").occurredAtProperty.assert().isNull()
    }

    @Test
    fun `an occurred-at property that cannot declare the creation's time should fail the compilation`() {
        val (_, result) = compileModel(
            "generate",
            context,
            order(
                commands = """
                @CreateAggregate
                data class Twice(@OccurredAt val placedAt: Long, @OccurredAt val paidAt: Long)
                @CreateAggregate
                data class Dated(@OccurredAt val placedAt: Instant)
                data class Ship(@OccurredAt val shippedAt: Long)
                @AllowCreate
                data class Upsert(@OccurredAt val placedAt: Long)
                @CreateAggregate
                data class Hidden(@property:OccurredAt private val placedAt: Long) {
                    fun at() = placedAt
                }
                @CreateAggregate
                data class Ignored(@OccurredAt @get:JsonIgnore val placedAt: Long)
                """.trimIndent(),
                functions = """
                fun onCommand(command: Twice): OrderImported = OrderImported("")
                fun onCommand(command: Dated): OrderImported = OrderImported("")
                fun onCommand(command: Ship): OrderImported = OrderImported("")
                fun onCommand(command: Upsert): OrderImported = OrderImported("")
                fun onCommand(command: Hidden): OrderImported = OrderImported("")
                fun onCommand(command: Ignored): OrderImported = OrderImported("")
                """.trimIndent(),
            ),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("command Twice has several @OccurredAt properties (placedAt, paidAt)")
            .contains("command Dated's @OccurredAt property placedAt is a java.time.Instant: a time is a Long of epoch")
            .contains(
                "command Ship's @OccurredAt property shippedAt: only a command that creates its aggregate " +
                    "(@CreateAggregate) declares when it occurred, and Ship does not",
            )
            .contains("and Upsert may also act on one that exists")
            .contains("command Hidden's @OccurredAt property placedAt is not visible to the generated model")
            .contains("command Ignored's @OccurredAt property placedAt is not written by Wow's JSON")
    }
}
