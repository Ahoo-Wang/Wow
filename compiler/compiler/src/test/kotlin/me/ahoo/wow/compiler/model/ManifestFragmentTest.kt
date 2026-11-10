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
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.cart
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.context
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.fragments
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.read
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** Roadmap 5a.1: the model processor writes the module's Manifest fragment (decisions D73, D74). */
@OptIn(ExperimentalCompilerApi::class)
class ManifestFragmentTest {
    @Test
    fun `a module with nothing to describe should write no fragment`() {
        val (compilation, result) = compileModel("generate", context)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragments(compilation).assert().isEmpty()
    }

    @Test
    fun `a model with problems should write no fragment`() {
        val brokenProcessor = SourceFile.kotlin(
            "Broken.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.Projection

            @Projection
            class Broken {
                fun onEvent(event: String) = Unit
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", context, cart, brokenProcessor)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        fragments(compilation).assert().isEmpty()

        val brokenRoot = SourceFile.kotlin(
            "BrokenRoot.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            @AggregateRoot
            class Lone(private val state: LoneState, private val extra: String)

            class LoneState(val id: String)
            """.trimIndent(),
        )
        val (rootCompilation, rootResult) = compileModel("generate", context, brokenRoot)
        rootResult.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        fragments(rootCompilation).assert().isEmpty()
    }

    @Test
    fun `two bounded contexts and no option should write no fragment`() {
        val another = SourceFile.kotlin(
            "Billing.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "billing")
            object BillingContext
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", context, another, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragments(compilation).assert().isEmpty()
        result.messages.assert().contains(
            "No Manifest fragment and no model provider: the module declares 2 @BoundedContext"
        )
    }

    @Test
    fun `the options should name the context and the fragment`() {
        val options = mapOf(
            ModelSymbolProcessorProvider.CONTEXT_OPTION to "store",
            ModelSymbolProcessorProvider.MODULE_OPTION to "store-domain",
        )
        val (compilation, result) = compileModel("generate", options, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = fragments(compilation).single()
        fragment.name.assert().isEqualTo("store-domain.json")
        read(fragment).contexts.single().let {
            it.name.assert().isEqualTo("store")
            it.description.assert().isNull()
        }
    }

    @Test
    fun `blank options should count as absent`() {
        val options = mapOf(
            ModelSymbolProcessorProvider.CONTEXT_OPTION to " ",
            ModelSymbolProcessorProvider.MODULE_OPTION to ""
        )
        val (compilation, result) = compileModel("generate", options, context, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragments(compilation).single().name.assert().isEqualTo("shop.json")
    }

    @Test
    fun `without a bounded context there should be no fragment, and a warning`() {
        val (compilation, result) = compileModel("generate", cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragments(compilation).assert().isEmpty()
        result.messages.assert().contains(
            "No Manifest fragment and no model provider: the module declares 0 @BoundedContext"
        )
    }

    @Test
    fun `report mode should write no fragment`() {
        val (compilation, result) = compileModel("report", context, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        fragments(compilation).assert().isEmpty()
    }
}
