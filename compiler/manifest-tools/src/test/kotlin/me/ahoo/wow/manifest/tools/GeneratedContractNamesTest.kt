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

/**
 * The contract and its state views name Kotlin's types by their qualified names (#5527, #5666), so the generated
 * sources compile whatever the domain names its classes and aggregates: a copied `List` or `String`, an aggregate
 * `string`, whose object in the contract is `String`.
 */
@OptIn(ExperimentalCompilerApi::class)
class GeneratedContractNamesTest {
    /** The contract of the aggregate [aggregate] of [state], completed as the plugin does. */
    private fun contract(state: Class<*>, aggregate: String): Manifest {
        val fragment = Manifest(
            contexts = listOf(
                ContextManifest(
                    "audit",
                    aggregates = listOf(
                        AggregateManifest(
                            aggregate,
                            state = StateManifest(Manifest.schemaRef(state.name)),
                            commands = listOf(
                                CommandManifest("Create", schema = Manifest.schemaRef("audit.api.Create"))
                            ),
                        ),
                    ),
                ),
            ),
        ).copyWith(defs = mapOf("audit.api.Create" to mapOf("type" to "object")))
        return SchemaIntrospector(javaClass.classLoader).complete(fragment)
    }

    /** The contract's sources, its query fields included, with no domain class on the api's class path. */
    private fun sources(state: Class<*>, aggregate: String): List<ContractSources.Source> =
        ContractSources.of(contract(state, aggregate), queryFields = true) { false }

    /** Compiles [sources] against the test's class path, as an api module compiles its generated contract. */
    private fun compile(sources: List<ContractSources.Source>) {
        val result = KotlinCompilation().apply {
            inheritClassPath = true
            this.sources = sources.map { SourceFile.kotlin(it.path.substringAfterLast('/'), it.text) }
            jvmTarget = "21"
        }.compile()
        result.exitCode.assert().withFailMessage { result.messages + "\n" + sources.joinToString("\n") { it.text } }
            .isEqualTo(KotlinCompilation.ExitCode.OK)
    }

    @Test
    fun `classes and aggregates named as Kotlin's types should leave those types spelled by their qualified names`() {
        val sources = sources(CatalogState::class.java, "string")
        sources.single { it.path.endsWith("CatalogStateView.kt") }.text.assert()
            .contains("val grouping: CatalogStateView.List? = null,")
            .contains("val tag: CatalogStateView.String? = null,")
            .contains("val values: kotlin.collections.List<kotlin.String>,")
            .contains("val codes: kotlin.collections.Set<kotlin.Int>,")
            .contains("val counts: kotlin.collections.Map<kotlin.String, kotlin.Long>,")
            .contains("public data class List(\n        val title: kotlin.String,")
        sources.single { it.path.endsWith("AuditContract.kt") }.text.assert()
            .contains("public object String : SnapshotProperties()")
            .contains("public const val NAME: kotlin.String = \"string\"")
        compile(sources)
    }

    @Test
    fun `a view should spell Kotlin's types by their qualified names whatever it copies`() {
        val sources = sources(EntryState::class.java, "catalog")
        sources.single { it.path.endsWith("EntryStateView.kt") }.text.assert()
            .contains("val grouping: EntryStateView.Entry? = null,")
            .contains("val values: kotlin.collections.List<kotlin.String>,")
        compile(sources)
    }
}
