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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

class ManifestTreeTest {
    private val mapper = jacksonObjectMapper()

    private val manifest = Manifests.fragment().copy(service = ServiceInfo("shop-service", "1.0.0"))

    @Test
    fun `a manifest should come back the same through its JSON text`() {
        val json = mapper.writeValueAsString(ManifestTree.write(manifest))
        ManifestTree.read(mapper.readValue(json, Map::class.java)).assert().isEqualTo(manifest)
    }

    @Test
    fun `the tree should name the fields as the design does and leave out absent values`() {
        val fragment = Manifests.fragment().copy(
            contexts = Manifests.fragment().contexts.map { it.copy(description = null) },
        )
        val tree = ManifestTree.write(fragment)
        tree.keys.assert().containsExactly("format", "contexts", "\$defs")
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        context.keys.assert().containsExactly("name", "aggregates", "subscriptions")
        val aggregate = (context["aggregates"] as List<*>).single() as Map<*, *>
        aggregate.keys.assert().containsExactly("name", "description", "state", "snapshot", "commands", "events")
        aggregate["state"].assert().isEqualTo(mapOf("schema" to "#/\$defs/sample.CartState", "fingerprint" to "abc123"))
        val commands = aggregate["commands"] as List<*>
        (commands[0] as Map<*, *>)["produces"].assert().isEqualTo(listOf("CartCreated"))
        (commands[1] as Map<*, *>).containsKey("produces").assert().isFalse()
        (commands[1] as Map<*, *>)["creation"].assert().isEqualTo("NONE")
    }

    @Test
    fun `reading should refuse another format`() {
        assertThrows<IllegalArgumentException> {
            ManifestTree.read(ManifestTree.write(manifest) + ("format" to 2))
        }.message.assert().contains("format 2")
    }

    @Test
    fun `reading should say which field is missing or mistyped`() {
        val tree = ManifestTree.write(manifest)
        val noName = tree + ("contexts" to listOf(mapOf("aggregates" to emptyList<Any>())))
        assertThrows<IllegalArgumentException> { ManifestTree.read(noName) }
            .message.assert().isEqualTo("manifest.contexts[0].name is missing.")
        val badAliases = tree + ("contexts" to listOf(mapOf("name" to "shop", "aggregates" to "cart")))
        assertThrows<IllegalArgumentException> { ManifestTree.read(badAliases) }
            .message.assert().contains("manifest.contexts[0].aggregates is not an array")
    }

    @Test
    fun `a schema reference should point into the defs`() {
        Manifest.schemaRef("a.B\$C").assert().isEqualTo("#/\$defs/a.B\$C")
        Manifest.schemaKey("#/\$defs/a.B\$C").assert().isEqualTo("a.B\$C")
        assertThrows<IllegalArgumentException> { Manifest.schemaKey("a.B") }
    }

    @Test
    fun `unresolved references should be those without a schema`() {
        val fragment = Manifests.fragment()
        fragment.schemaRefs.assert().hasSize(5)
        fragment.unresolvedSchemaRefs.assert().containsExactly(
            "#/\$defs/sample.CreateCart",
            "#/\$defs/sample.AddItem",
            "#/\$defs/sample.CartCreated",
            "#/\$defs/sample.ItemAdded",
        )
    }
}
