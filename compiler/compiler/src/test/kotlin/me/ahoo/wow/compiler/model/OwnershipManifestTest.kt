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
import me.ahoo.wow.manifest.Ownership
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/** `@AggregateRoot(ownership)` (decision D189): the model and the Manifest fragment say who an aggregate belongs to. */
@OptIn(ExperimentalCompilerApi::class)
class OwnershipManifestTest {
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

    @Test
    fun `@AggregateRoot(ownership) should reach the model and the fragment, without a transfer of the owner`() {
        val catalog = SourceFile.kotlin(
            "Catalog.kt",
            """
            package sample

            import me.ahoo.wow.api.aggregate.Ownership
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.lifecycle.DeleteAggregate

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot(ownership = Ownership.TENANT)
            class Product(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot(ownership = Ownership.TENANT, lifecycle = [DeleteAggregate::class])
            class Brand(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }

            @AggregateRoot(ownership = Ownership.OWNER)
            class Basket(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", context, catalog)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val aggregates = read(fragments(compilation).single()).contexts.single().aggregates.associateBy { it.name }
        aggregates.getValue("product").ownership.assert().isEqualTo(Ownership.TENANT)
        aggregates.getValue("product").lifecycle.assert().containsExactly(
            "wow.lifecycle.ApplyTags",
            "wow.lifecycle.DeleteAggregate",
            "wow.lifecycle.RestoreAggregate",
            "wow.lifecycle.TransferSpace",
        )
        aggregates.getValue("brand").lifecycle.assert().containsExactly("wow.lifecycle.DeleteAggregate")
        aggregates.getValue("basket").ownership.assert().isEqualTo(Ownership.OWNER)
        aggregates.getValue("basket").lifecycle.assert().contains("wow.lifecycle.TransferOwner")
        val generated = compilation.workingDir.walkTopDown().filter { it.name == "ProductModel.kt" }.single().readText()
        generated.assert().contains("ownership(me.ahoo.wow.api.aggregate.Ownership.TENANT)")
        compilation.workingDir.walkTopDown().filter { it.name == "BasketModel.kt" }.single().readText()
            .assert().doesNotContain("ownership(")
    }

    @Test
    fun `an aggregate of its tenant that lists TransferOwner should fail the compilation`() {
        val catalog = SourceFile.kotlin(
            "Catalog.kt",
            """
            package sample

            import me.ahoo.wow.api.aggregate.Ownership
            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.lifecycle.TransferOwner

            data class Open(val id: String)
            data class Opened(val id: String)

            @AggregateRoot(ownership = Ownership.TENANT, lifecycle = [TransferOwner::class])
            class Product(val id: String) {
                fun onCommand(command: Open): Opened = Opened(command.id)
                fun onSourcing(event: Opened) = Unit
            }
            """.trimIndent(),
        )
        val (_, result) = compileModel("generate", context, catalog)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("lists TransferOwner, but the aggregate belongs to its tenant")
    }
}
