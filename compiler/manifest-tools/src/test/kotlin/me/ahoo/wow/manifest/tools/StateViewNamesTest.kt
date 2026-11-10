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

package me.ahoo.wow.manifest.tools

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.shadowing.CatalogState
import me.ahoo.wow.manifest.tools.shadowing.EntryState
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/** A view spells each type as its own scope sees it, so the generated source compiles whatever the domain names. */
@OptIn(ExperimentalCompilerApi::class)
class StateViewNamesTest {
    /** The view of [state], generated as the plugin does, with no domain class on the api's class path. */
    private fun view(state: Class<*>): ContractSources.Source {
        val fragment = Manifest(
            contexts = listOf(
                ContextManifest(
                    "audit",
                    aggregates = listOf(
                        AggregateManifest(
                            "catalog",
                            state = StateManifest(Manifest.schemaRef(state.name)),
                            commands = listOf(
                                CommandManifest("Create", schema = Manifest.schemaRef("audit.api.Create"))
                            ),
                        ),
                    ),
                ),
            ),
        ).copyWith(defs = mapOf("audit.api.Create" to mapOf("type" to "object")))
        val contract = SchemaIntrospector(javaClass.classLoader).complete(fragment)
        return StateViewSources(contract.defs) { false }.of(contract.contexts.single()).single()
    }

    /** Compiles [source] against the test's class path, as an api module compiles its generated views. */
    private fun compile(source: ContractSources.Source) {
        val result = KotlinCompilation().apply {
            inheritClassPath = true
            sources = listOf(SourceFile.kotlin(source.path.substringAfterLast('/'), source.text))
            jvmTarget = "21"
        }.compile()
        result.exitCode.assert().withFailMessage { result.messages + "\n" + source.text }
            .isEqualTo(KotlinCompilation.ExitCode.OK)
    }

    @Test
    fun `a copied class named as a Kotlin type should leave that type spelled by its qualified name`() {
        val view = view(CatalogState::class.java)
        view.text.assert()
            .contains("val grouping: CatalogStateView.List? = null,")
            .contains("val tag: CatalogStateView.String? = null,")
            .contains("val values: kotlin.collections.List<kotlin.String>,")
            // A Kotlin type no copy hides keeps its simple name.
            .contains("val codes: Set<Int>,")
            .contains("val counts: Map<kotlin.String, Long>,")
            .contains("public data class List(\n        val title: kotlin.String,")
        compile(view)
    }

    @Test
    fun `a view whose copies hide no Kotlin type should spell Kotlin's types by their simple names`() {
        val view = view(EntryState::class.java)
        view.text.assert()
            .contains("val grouping: EntryStateView.Entry? = null,")
            .contains("val values: List<String>,")
            .doesNotContain("kotlin.")
        compile(view)
    }
}
