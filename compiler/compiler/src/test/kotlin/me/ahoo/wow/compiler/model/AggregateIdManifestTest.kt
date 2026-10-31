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
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * `@AggregateId`: a command's property, on its class or an interface it implements, stored or computed, holds the id
 * of the aggregate it acts on. The generated model reads it, and the Manifest names its JSON property and routes the
 * command by no id alone.
 */
@OptIn(ExperimentalCompilerApi::class)
class AggregateIdManifestTest {
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

    private fun read(file: File): Manifest = ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java))

    private fun generated(compilation: KotlinCompilation, name: String): String =
        compilation.workingDir.walkTopDown().filter { it.name == name }.single().readText()

    /** A stock, its id a product's, with [commands] and the root annotated [root]. */
    private fun stock(commands: String, root: String = "@AggregateRoot", functions: String = ""): SourceFile =
        SourceFile.kotlin(
            "Stock.kt",
            """
            package sample

            import com.fasterxml.jackson.annotation.JsonIgnore
            import com.fasterxml.jackson.annotation.JsonIgnoreProperties
            import com.fasterxml.jackson.annotation.JsonProperty
            import me.ahoo.wow.api.aggregate.AggregateIdSource
            import me.ahoo.wow.api.annotation.AggregateId
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.AllowCreate
            import me.ahoo.wow.api.annotation.CreateAggregate
            import me.ahoo.wow.api.annotation.Unaddressed

            $commands
            data class Stocked(val quantity: Int)

            $root
            class Stock(val id: String) {
                $functions
                fun onSourcing(event: Stocked) = Unit
            }
            """.trimIndent(),
        )

    @Test
    fun `a command's aggregate id property should be read by the model and named by the Manifest, routed by no id`() {
        val (compilation, result) = compileModel(
            "generate",
            context,
            stock(
                commands = """
                @CreateAggregate
                data class OpenStock(@AggregateId val productId: String?, val quantity: Int)
                data class ReserveStock(@AggregateId val productId: String, val quantity: Int)
                interface OfProduct {
                    @AggregateId
                    val productId: String
                }
                data class Restock(override val productId: String, val quantity: Int) : OfProduct
                data class Adjust(val warehouse: String, val sku: String) {
                    @AggregateId
                    val productId: String get() = "${'$'}warehouse-${'$'}sku"
                }
                data class Count(@AggregateId @JsonProperty("product") val productId: String)
                data class Close(val reason: String)
                """.trimIndent(),
                functions = """
                fun onCommand(command: OpenStock): Stocked = Stocked(command.quantity)
                fun onCommand(command: ReserveStock): Stocked = Stocked(command.quantity)
                fun onCommand(command: Restock): Stocked = Stocked(command.quantity)
                fun onCommand(command: Adjust): Stocked = Stocked(0)
                fun onCommand(command: Count): Stocked = Stocked(0)
                fun onCommand(command: Close): Stocked = Stocked(0)
                """.trimIndent(),
            ),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "StockModel.kt").assert()
            .contains("aggregateId<sample.ReserveStock> { it.`productId` }")
            .contains("aggregateId<sample.OpenStock> { it.`productId` }")
            .contains("aggregateId<sample.Restock> { it.`productId` }")
            .contains("aggregateId<sample.Adjust> { it.`productId` }")
            .contains("aggregateId<sample.Count> { it.`productId` }")
            .doesNotContain("aggregateId<sample.Close>")
        val commands = read(fragments(compilation).single()).contexts.single().aggregates.single().commands
            .associateBy { it.name }
        commands.getValue("ReserveStock").routes.assert()
            .containsExactly(RouteManifest("POST", "/shop/stock/reserve-stock"))
        commands.getValue("OpenStock").routes.assert().containsExactly(RouteManifest("POST", "/shop/stock/open-stock"))
        listOf("ReserveStock", "OpenStock", "Restock", "Adjust").forEach {
            commands.getValue(it).idProperty.assert().describedAs(it).isEqualTo("productId")
        }
        // Its name in JSON, which a sender without the class reads.
        commands.getValue("Count").idProperty.assert().isEqualTo("product")
        commands.getValue("Close").idProperty.assert().isNull()
        commands.getValue("Close").routes.assert().containsExactly(RouteManifest("POST", "/shop/stock/{id}/close"))
        // The lifecycle commands keep their routes by id.
        commands.getValue("wow.lifecycle.DeleteAggregate").routes.assert()
            .containsExactly(RouteManifest("DELETE", "/shop/stock/{id}"))
    }

    @Test
    fun `an aggregate id property that cannot hold the id should fail the compilation`() {
        val (_, result) = compileModel(
            "generate",
            context,
            stock(
                commands = """
                data class Twice(@AggregateId val productId: String, @AggregateId val sku: String)
                data class Numbered(@AggregateId val productId: Long)
                data class Optional(@AggregateId val productId: String?)
                data class Hidden(@property:AggregateId private val productId: String) {
                    fun id() = productId
                }
                data class Ignored(@AggregateId @get:JsonIgnore val productId: String)
                @JsonIgnoreProperties("productId")
                data class Unwritten(@AggregateId val productId: String)
                @Unaddressed
                data class Found(@AggregateId val productId: String)
                """.trimIndent(),
                functions = """
                fun onCommand(command: Twice): Stocked = Stocked(0)
                fun onCommand(command: Numbered): Stocked = Stocked(0)
                fun onCommand(command: Optional): Stocked = Stocked(0)
                fun onCommand(command: Hidden): Stocked = Stocked(0)
                fun onCommand(command: Ignored): Stocked = Stocked(0)
                fun onCommand(command: Unwritten): Stocked = Stocked(0)
                fun onCommand(command: Found): Stocked = Stocked(0)
                """.trimIndent(),
            ),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("command Twice has several @AggregateId properties (productId, sku)")
            .contains("command Numbered's @AggregateId property productId is a kotlin.Long: an aggregate id is a")
            .contains(
                "command Optional's @AggregateId property productId is nullable: only a command that creates its " +
                    "aggregate may leave its id out",
            )
            .contains("command Hidden's @AggregateId property productId is not visible to the generated model")
            .contains("command Ignored's @AggregateId property productId is not written by Wow's JSON")
            .contains("command Unwritten's @AggregateId property productId is not written by Wow's JSON")
            .contains("command Found is @Unaddressed and has an @AggregateId property")
    }

    @Test
    fun `an aggregate whose id is its owner's should take no command whose body names its aggregate`() {
        val (_, result) = compileModel(
            "generate",
            context,
            stock(
                commands = "data class ReserveStock(@AggregateId val productId: String, val quantity: Int)",
                root = "@AggregateRoot(idSource = AggregateIdSource.OWNER)",
                functions = "fun onCommand(command: ReserveStock): Stocked = Stocked(command.quantity)",
            ),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "command ReserveStock has an @AggregateId property, but Stock's id is its owner's (idSource = OWNER): " +
                "its commands name no aggregate",
        )
    }
}
