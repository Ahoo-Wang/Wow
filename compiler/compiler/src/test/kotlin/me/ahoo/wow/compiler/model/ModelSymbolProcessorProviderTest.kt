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

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * The provider leaves its processor out of KSP's new features ([ModelSymbolProcessorProvider] says why): a property's
 * `@field:` annotations stay the property's, those of a library's class included.
 */
@OptIn(ExperimentalCompilerApi::class)
class ModelSymbolProcessorProviderTest {
    @Test
    fun `the processor it creates should not be registered for KSP's new features`() {
        val registered = mutableListOf<SymbolProcessor>()
        val version = KotlinVersion(2, 3)
        val environment = SymbolProcessorEnvironment(
            options = mapOf(ModelSymbolProcessorProvider.OPTION to "generate"),
            kotlinVersion = version,
            codeGenerator = fake { error("No code is generated.") },
            logger = fake { error("Nothing is logged.") },
            apiVersion = version,
            compilerVersion = version,
            platforms = emptyList(),
            kspVersion = version,
            registerProcessorForNewFeatures = { registered += it },
        )
        ModelSymbolProcessorProvider().create(environment)
        registered.assert().isEmpty()
    }

    @Test
    fun `a library command's field annotation should be its property's`() {
        val api = compileLibrary(
            SourceFile.kotlin(
                "Api.kt",
                """
                package api

                import me.ahoo.wow.api.annotation.AggregateId

                data class Rename(@field:AggregateId val cartId: String, val name: String)
                data class Renamed(val name: String)
                """.trimIndent(),
            ),
        )
        val model = SourceFile.kotlin(
            "Cart.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot
            import me.ahoo.wow.api.annotation.BoundedContext

            @BoundedContext(name = "shop")
            object Shop

            @AggregateRoot
            class Cart(private val state: CartState) {
                fun onCommand(command: api.Rename): api.Renamed = api.Renamed(command.name)
            }

            class CartState(val id: String) {
                var name: String = ""

                fun onSourcing(event: api.Renamed) {
                    name = event.name
                }
            }
            """.trimIndent(),
        )
        val (compilation, result) = compileModelAgainst("generate", listOf(api), model)
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        val fragment = compilation.workingDir.walkTopDown()
            .single { it.extension == "json" && it.invariantSeparatorsPath.contains("META-INF/wow/manifest/") }
        fragment.readText().assert().contains("\"idProperty\" : \"cartId\"")
    }
}
