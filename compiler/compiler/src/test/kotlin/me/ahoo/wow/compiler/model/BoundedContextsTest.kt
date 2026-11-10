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
import me.ahoo.wow.compiler.model.ManifestFragmentFixtures.fragments
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** The bounded contexts' names ([BoundedContexts]): the rule they follow, checked when the processor generates. */
@OptIn(ExperimentalCompilerApi::class)
class BoundedContextsTest {
    @Test
    fun `a bounded context whose name breaks the rule should fail the compilation`() {
        val dotted = SourceFile.kotlin(
            "Catalog.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "eshop.catalog")
            object CatalogContext
            """.trimIndent(),
        )
        val (compilation, result) = compileModel("generate", dotted, cart)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert()
            .contains("@BoundedContext: context name \"eshop.catalog\" is not valid")
            .contains("[a-z][a-z0-9-]*")
        fragments(compilation).assert().isEmpty()
    }

    @Test
    fun `a context option whose name breaks the rule should fail the compilation`() {
        val options = mapOf(ModelSymbolProcessorProvider.CONTEXT_OPTION to "Store_Front")
        val (_, result) = compileModel("generate", options, cart)
        result.exitCode.assert().isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
        result.messages.assert().contains("KSP option wow.context: context name \"Store_Front\" is not valid")
    }

    @Test
    fun `report mode should not check the context name`() {
        val upper = SourceFile.kotlin(
            "Upper.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "Upper")
            object UpperContext
            """.trimIndent(),
        )
        val (_, result) = compileModel("report", upper, cart)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
    }
}
