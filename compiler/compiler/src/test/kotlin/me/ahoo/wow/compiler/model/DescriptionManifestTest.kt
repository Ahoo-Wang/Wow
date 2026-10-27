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
import me.ahoo.wow.compiler.model.symbol.Descriptions
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Roadmap 5a.1c: the fragment describes the commands, the events and the aggregate by their `@Description`, else the
 * summary of their KDoc, else their `@Summary`.
 */
@OptIn(ExperimentalCompilerApi::class)
class DescriptionManifestTest {
    private val context = SourceFile.kotlin(
        "ShopContext.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.BoundedContext

        @BoundedContext(name = "shop")
        object ShopContext
        """.trimIndent(),
    )

    private fun fragment(compilation: KotlinCompilation): Manifest {
        val file = compilation.workingDir.walkTopDown()
            .filter { it.isFile && it.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) }
            .single()
        return ManifestTree.read(jacksonObjectMapper().readValue(file, Map::class.java))
    }

    private val cart = SourceFile.kotlin(
        "Cart.kt",
        """
        package sample

        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        import me.ahoo.wow.api.annotation.Description
        import me.ahoo.wow.api.annotation.MountedCommand
        import me.ahoo.wow.api.annotation.Summary

        /**
         * Opens the cart of [owner], a buyer
         * of the [shop][ShopContext].
         *
         * A second paragraph that is not the summary.
         */
        @CreateAggregate
        data class Open(val owner: String)

        /**
         * Adds an item.
         * @param sku what is added
         */
        @Description("Puts an item into the cart")
        @Summary("Add an item")
        data class Add(val sku: String)

        @Summary("Clear the cart")
        data class Clear(val all: Boolean = true)

        data class Close(val reason: String)

        /** Imports a cart from elsewhere. */
        @MountedCommand(aggregate = "cart")
        data class ImportCart(val source: String)

        /** The cart was opened. */
        data class Opened(val owner: String)

        @Description("   ")
        data class Added(val sku: String)

        /**
         * @since 1.0
         */
        data class Cleared(val all: Boolean)

        data class Closed(val reason: String)

        /** A buyer's cart. */
        @AggregateRoot
        class Cart(val id: String) {
            fun onCommand(command: Open): Opened = Opened(command.owner)
            fun onCommand(command: Add): Added = Added(command.sku)
            fun onCommand(command: Clear): Cleared = Cleared(command.all)
            fun onCommand(command: Close): Closed = Closed(command.reason)
            fun onSourcing(event: Opened) = Unit
            fun onSourcing(event: Added) = Unit
            fun onSourcing(event: Cleared) = Unit
            fun onSourcing(event: Closed) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `commands, events and the aggregate should carry what their classes say they are`() {
        val (compilation, result) = compileModel("generate", context, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val aggregate = fragment(compilation).contexts.single().aggregates.single()
        aggregate.description.assert().isEqualTo("A buyer's cart.")
        aggregate.domainCommands.associate { it.name to it.description }.assert().isEqualTo(
            mapOf(
                "Add" to "Puts an item into the cart",
                "Clear" to "Clear the cart",
                "Close" to null,
                "ImportCart" to "Imports a cart from elsewhere.",
                "Open" to "Opens the cart of owner, a buyer of the shop.",
            ),
        )
        aggregate.events.associate { it.name to it.description }.assert().isEqualTo(
            mapOf("Added" to null, "Cleared" to null, "Closed" to null, "Opened" to "The cart was opened."),
        )
    }

    @Test
    fun `a KDoc summary should be its first paragraph on one line, without links`() {
        Descriptions.summary(null).assert().isNull()
        Descriptions.summary("").assert().isNull()
        Descriptions.summary("/**\n * @param a the a\n */").assert().isNull()
        Descriptions.summary("/** Sends [it] to [the shop][Shop]. */").assert().isEqualTo("Sends it to the shop.")
        Descriptions.summary(" First\n line.\n\n Second.").assert().isEqualTo("First line.")
        Descriptions.summary("* Starred\n* lines.\n@see Other").assert().isEqualTo("Starred lines.")
    }
}
