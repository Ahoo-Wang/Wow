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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/** A Manifest compared with itself reports no change: the property every comparison rests on. */
class SelfComparisonTest {
    /** A Manifest JSON file of the repository: a service's baseline, a module's fragment, a peer contract. */
    private fun isManifest(file: File): Boolean = file.isFile && file.extension == "json" && (
        file.name == "baseline.json" && file.parentFile.name == "manifest" ||
            file.parentFile.invariantSeparatorsPath.endsWith(Manifest.FRAGMENT_DIRECTORY) ||
            file.invariantSeparatorsPath.endsWith(CONTRACT)
        )

    @TestFactory
    fun `every Manifest of the repository should have no change against itself`(): List<DynamicTest> {
        val root = File(requireNotNull(System.getProperty(ROOT)) { "System property $ROOT is not set." })
        val manifests = root.walkTopDown()
            .onEnter { it.name !in SKIPPED }
            .filter(::isManifest)
            .mapNotNull { file ->
                // A fixture of a refusal (a malformed Manifest, another format) is not one the gate ever compares.
                val manifest = runCatching { ManifestJson.read(file) }.getOrNull() ?: return@mapNotNull null
                file.relativeTo(root).invariantSeparatorsPath to manifest
            }
            .sortedBy { it.first }
            .toList()
        manifests.map { it.first }.assert().contains("examples/eshop/ordering/ordering-server/manifest/baseline.json")
        return manifests.map { (path, manifest) ->
            DynamicTest.dynamicTest(path) {
                ManifestCompatibility.compare(manifest, manifest).changes.assert().isEmpty()
            }
        }
    }

    data class Node(val value: String, val next: Node? = null)

    @Test
    fun `a nullable recursive model should have no change against itself and report its own`() {
        val manifest = SchemaIntrospector(javaClass.classLoader).complete(
            Manifest(
                contexts = listOf(
                    ContextManifest(
                        "audit",
                        aggregates = listOf(
                            AggregateManifest("node", state = StateManifest(Manifest.schemaRef(Node::class.java.name))),
                        ),
                    ),
                ),
            ),
        )
        ManifestCompatibility.compare(manifest, manifest).changes.assert().isEmpty()
        // The recursion ends, and a change beside it is still reported: `value` made optional, at the state and at
        // the node its nullable `next` refers to, a reference of its own, after which the recursion ends.
        val node = manifest.defs.getValue(Node::class.java.name) as Map<*, *>
        val changed = manifest.copyWith(defs = manifest.defs + (Node::class.java.name to node - "required"))
        ManifestCompatibility.compare(manifest, changed).changes.map { it.toString() }.assert()
            .containsExactlyInAnyOrder(
                "ADDITIVE contexts[audit].aggregates[node].state.value: field made optional",
                "ADDITIVE contexts[audit].aggregates[node].state.next.value: field made optional",
            )
    }

    private companion object {
        const val ROOT = "wow-test.repository.root"
        const val CONTRACT = "META-INF/wow/contract.json"
        val SKIPPED = setOf("build", "node_modules", ".git", ".gradle", ".claude", ".idea", "dist")
    }
}
