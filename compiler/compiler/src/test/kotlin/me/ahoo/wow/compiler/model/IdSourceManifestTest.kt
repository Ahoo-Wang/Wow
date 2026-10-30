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
import me.ahoo.wow.manifest.AggregateIdSource
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * `@AggregateRoot(idSource)`: an aggregate whose id is its owner's reaches the model and the Manifest fragment, every
 * command of it routed by its id and by none, and its owner never transferred.
 */
@OptIn(ExperimentalCompilerApi::class)
class IdSourceManifestTest {
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

    private fun basket(aggregateRoot: String, imports: String = ""): SourceFile = SourceFile.kotlin(
        "Basket.kt",
        """
        package sample

        import me.ahoo.wow.api.aggregate.AggregateIdSource
        import me.ahoo.wow.api.aggregate.Ownership
        import me.ahoo.wow.api.annotation.AggregateRoot
        import me.ahoo.wow.api.annotation.CreateAggregate
        $imports

        @CreateAggregate
        data class Open(val id: String)
        data class PutItem(val sku: String)
        data class Opened(val id: String)
        data class ItemPut(val sku: String)

        $aggregateRoot
        class Basket(val id: String) {
            fun onCommand(command: Open): Opened = Opened(command.id)
            fun onCommand(command: PutItem): ItemPut = ItemPut(command.sku)
            fun onSourcing(event: Opened) = Unit
            fun onSourcing(event: ItemPut) = Unit
        }
        """.trimIndent(),
    )

    @Test
    fun `an aggregate whose id is its owner's should reach the model and route every command by no id`() {
        val (compilation, result) = compileModel(
            "generate",
            context,
            basket("@AggregateRoot(idSource = AggregateIdSource.OWNER)"),
        )
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "BasketModel.kt").assert()
            .contains("id(me.ahoo.wow.api.aggregate.AggregateIdSource.OWNER)")
            .doesNotContain("ownership(")
        val basket = read(fragments(compilation).single()).contexts.single().aggregates.single()
        basket.idSource.assert().isEqualTo(AggregateIdSource.OWNER)
        basket.lifecycle.assert().containsExactly(
            "wow.lifecycle.ApplyTags",
            "wow.lifecycle.DeleteAggregate",
            "wow.lifecycle.RestoreAggregate",
            "wow.lifecycle.TransferSpace",
        )
        val routes = basket.commands.associate { command -> command.name to command.routes }
        routes.getValue("Open").assert().containsExactly(
            RouteManifest("POST", "/shop/basket/{id}/open"),
            RouteManifest("POST", "/shop/basket/open"),
        )
        routes.getValue("PutItem").assert().containsExactly(
            RouteManifest("POST", "/shop/basket/{id}/put-item"),
            RouteManifest("POST", "/shop/basket/put-item"),
        )
        routes.getValue("wow.lifecycle.DeleteAggregate").assert().containsExactly(
            RouteManifest("DELETE", "/shop/basket/{id}"),
            RouteManifest("DELETE", "/shop/basket"),
        )
        listOf(
            "ApplyTags" to "apply-tags",
            "RestoreAggregate" to "restore-aggregate",
            "TransferSpace" to "transfer-space"
        )
            .forEach { (name, segment) ->
                routes.getValue("wow.lifecycle.$name").assert().containsExactly(
                    RouteManifest("POST", "/shop/basket/{id}/wow.lifecycle.$segment"),
                    RouteManifest("POST", "/shop/basket/wow.lifecycle.$segment"),
                )
            }
        // The snapshot rebuild is not the aggregate's own command: it keeps its route.
        routes.getValue("wow.snapshot.Rebuild").assert().hasSize(1)
    }

    @Test
    fun `an aggregate of assigned ids should keep its routes by id and its transfer of the owner`() {
        val (compilation, result) = compileModel("generate", context, basket("@AggregateRoot"))
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        generated(compilation, "BasketModel.kt").assert().doesNotContain("AggregateIdSource")
        val basket = read(fragments(compilation).single()).contexts.single().aggregates.single()
        basket.idSource.assert().isEqualTo(AggregateIdSource.ASSIGNED)
        basket.lifecycle.assert().contains("wow.lifecycle.TransferOwner")
        val routes = basket.commands.associate { command -> command.name to command.routes }
        routes.getValue("PutItem").assert().containsExactly(RouteManifest("POST", "/shop/basket/{id}/put-item"))
        routes.getValue("wow.lifecycle.DeleteAggregate").assert()
            .containsExactly(RouteManifest("DELETE", "/shop/basket/{id}"))
        routes.getValue("Open").assert().containsExactly(
            RouteManifest("POST", "/shop/basket/{id}/open"),
            RouteManifest("POST", "/shop/basket/open"),
        )
    }

    @Test
    fun `an id that is its owner's on an aggregate of its tenant should fail the compilation`() {
        val (_, result) = compileModel(
            "generate",
            context,
            basket("@AggregateRoot(idSource = AggregateIdSource.OWNER, ownership = Ownership.TENANT)"),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "@AggregateRoot(idSource = OWNER) needs an aggregate that belongs to its owner, but it belongs to its " +
                "tenant (ownership = TENANT): it has no owner to take its id from",
        )
    }

    @Test
    fun `an aggregate whose id is its owner's that lists TransferOwner should fail the compilation`() {
        val (_, result) = compileModel(
            "generate",
            context,
            basket(
                "@AggregateRoot(idSource = AggregateIdSource.OWNER, lifecycle = [TransferOwner::class, DeleteAggregate::class])",
                "import me.ahoo.wow.api.lifecycle.DeleteAggregate\nimport me.ahoo.wow.api.lifecycle.TransferOwner",
            ),
        )
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains(
            "@AggregateRoot(lifecycle) lists TransferOwner, but the aggregate's id is its owner's (idSource = OWNER): " +
                "its owner cannot be transferred",
        )
    }
}
