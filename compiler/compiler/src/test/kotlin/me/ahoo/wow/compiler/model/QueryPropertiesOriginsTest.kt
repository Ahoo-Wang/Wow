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

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compiler.model.aggregate.RootStateResolver
import me.ahoo.wow.compiler.model.source.QueryPropertiesSource
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.OutputStream

/**
 * The origins a state's `<State>Properties` declares to KSP: every file its paths are read from, the root's, the
 * state's, and each class a path walks into with its supertypes, so an incremental build writes it again when any of
 * them changes.
 */
@OptIn(ExperimentalCompilerApi::class)
class QueryPropertiesOriginsTest {
    private fun source(name: String, content: String) = SourceFile.kotlin(name, content.trimIndent())

    private val sources = listOf(
        source(
            "Cargo.kt",
            """
            package sample

            import me.ahoo.wow.api.annotation.AggregateRoot

            data class Load(val sku: String)
            data class Loaded(val sku: String)

            @AggregateRoot
            class Cargo(private val state: CargoState) {
                fun onCommand(command: Load): Loaded = Loaded(command.sku)
            }
            """,
        ),
        source(
            "CargoState.kt",
            """
            package sample

            class CargoState(val id: String) {
                var item: Item? = null
                    private set

                fun onSourcing(event: Loaded) { item = Item(event.sku, 1) }
            }
            """,
        ),
        source("Item.kt", "package sample\n\nclass Item(val sku: String, override val count: Int) : Counted\n"),
        source("Counted.kt", "package sample\n\ninterface Counted {\n    val count: Int\n}\n"),
        source("Unrelated.kt", "package sample\n\nclass Unrelated(val note: String)\n"),
    )

    /** A code generator that records the origins each file it writes declares, by file name. */
    private class Recording(private val delegate: CodeGenerator) : CodeGenerator by delegate {
        val origins = linkedMapOf<String, List<String>>()

        override fun createNewFile(
            dependencies: Dependencies,
            packageName: String,
            fileName: String,
            extensionName: String,
        ): OutputStream {
            origins[fileName] = dependencies.originatingFiles.map { it.fileName }
            return delegate.createNewFile(dependencies, packageName, fileName, extensionName)
        }
    }

    /** Writes the `<State>Properties` of each aggregate root, as the model processor does, through [Recording]. */
    private class Writer(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
        override fun process(resolver: Resolver): List<KSAnnotated> {
            val recording = Recording(environment.codeGenerator)
            resolver.getSymbolsWithAnnotation("me.ahoo.wow.api.annotation.AggregateRoot")
                .filterIsInstance<KSClassDeclaration>().forEach { root ->
                    val state = RootStateResolver.stateOf(root)
                    QueryPropertiesSource.generate(recording, state, root, mutableSetOf()).assert().isEmpty()
                }
            recorded += recording.origins
            return emptyList()
        }
    }

    private companion object {
        val recorded = linkedMapOf<String, List<String>>()
    }

    @Test
    fun `a state's query fields should declare every file their paths are read from`() {
        recorded.clear()
        val compilation = KotlinCompilation().apply {
            inheritClassPath = true
            this.sources = this@QueryPropertiesOriginsTest.sources
            configureKsp { symbolProcessorProviders += SymbolProcessorProvider(::Writer) }
            jvmTarget = "21"
        }
        val result = compilation.compile()
        result.exitCode.assert().withFailMessage { result.messages }.isEqualTo(KotlinCompilation.ExitCode.OK)
        // The root's file, the state's, the class a path walks into and that class's supertype; not a file of the
        // module no path reads.
        recorded.getValue("CargoStateProperties").assert()
            .containsExactlyInAnyOrder("Cargo.kt", "CargoState.kt", "Item.kt", "Counted.kt")
    }
}
