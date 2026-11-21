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
import me.ahoo.wow.manifest.tools.shadowing.EntryState
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test

/**
 * A package with a segment that is a Kotlin keyword but a Java identifier (`object`, `in`, `fun`, …) is a valid Java
 * package, so the generated sources quote the segment ([KotlinNames]) and compile: the contract in its contract package,
 * a view in the package its aggregate's classes share.
 */
@OptIn(ExperimentalCompilerApi::class)
class KeywordPackagesTest {
    /** The sources of a context whose command is `audit.object.Create`, its contract package [contractPackage]. */
    private fun sources(contractPackage: String): List<ContractSources.Source> {
        val fragment = Manifest(
            contexts = listOf(
                ContextManifest(
                    "audit",
                    aggregates = listOf(
                        AggregateManifest(
                            "catalog",
                            state = StateManifest(Manifest.schemaRef(EntryState::class.java.name)),
                            commands = listOf(
                                CommandManifest("Create", schema = Manifest.schemaRef("audit.object.Create"))
                            ),
                        ),
                    ),
                ),
            ),
        ).copyWith(defs = mapOf("audit.object.Create" to mapOf("type" to "object")))
        val contract = SchemaIntrospector(javaClass.classLoader).complete(fragment)
        return ContractSources.of(contract, mapOf("audit" to contractPackage), queryFields = true) { false }
    }

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
    fun `a view in a package of a Kotlin keyword should compile`() {
        // The aggregate's command is in audit.object, so its view is too; the contract is in a plain package.
        val sources = sources("audit.api")
        val view = sources.single { it.path.endsWith("EntryStateView.kt") }
        view.path.assert().isEqualTo("audit/object/EntryStateView.kt")
        view.text.assert().contains("package audit.`object`\n")
        // The contract's query fields type the view's values by the view's qualified name.
        sources.single { it.path.endsWith("AuditContract.kt") }.text.assert().contains("audit.`object`.EntryStateView")
        compile(sources)
    }

    @Test
    fun `a contract in a package of Kotlin keywords should compile`() {
        val sources = sources("audit.fun.in")
        val contract = sources.single { it.path.endsWith("AuditContract.kt") }
        contract.path.assert().isEqualTo("audit/fun/in/AuditContract.kt")
        contract.text.assert().contains("package audit.`fun`.`in`\n")
        compile(sources)
    }
}
