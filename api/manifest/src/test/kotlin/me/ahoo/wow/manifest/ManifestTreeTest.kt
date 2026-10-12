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
        aggregate.keys.assert().containsExactly(
            "name",
            "description",
            "state",
            "snapshot",
            "commands",
            "events",
            "lifecycle",
        )
        aggregate["lifecycle"].assert().isEqualTo(Manifests.LIFECYCLE)
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
    fun `a minimal manifest should read with every default`() {
        val tree = mapOf(
            "format" to 1,
            "contexts" to listOf(
                mapOf(
                    "name" to "shop",
                    "aggregates" to listOf(mapOf("name" to "cart", "state" to mapOf("schema" to "#/\$defs/s"))),
                ),
            ),
        )
        val expected = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(AggregateManifest(name = "cart", state = StateManifest("#/\$defs/s")))
                ),
            ),
        )
        ManifestTree.read(tree).assert().isEqualTo(expected)
        expected.service.assert().isNull()
        expected.contexts.single().let { context ->
            context.description.assert().isNull()
            context.subscriptions.assert().isEmpty()
            context.aggregates.single().let { aggregate ->
                aggregate.description.assert().isNull()
                aggregate.snapshot.enabled.assert().isTrue()
                aggregate.commands.assert().isEmpty()
                aggregate.events.assert().isEmpty()
                aggregate.lifecycle.assert().isEmpty()
                aggregate.state.fingerprint.assert().isNull()
            }
        }
        Manifest().defs.assert().isEmpty()
        ServiceInfo("shop").version.assert().isNull()
        CommandManifest("Add", schema = "#/\$defs/a").let {
            it.aliases.assert().isEmpty()
            it.creation.assert().isEqualTo(Creation.NONE)
            it.produces.assert().isNull()
        }
        EventManifest("Added", schema = "#/\$defs/a").revision.assert().isEqualTo(1)
        SubscriptionSource().let {
            it.context.assert().isNull()
            it.aggregates.assert().isEmpty()
            it.events.assert().isEmpty()
        }
    }

    @Test
    fun `reading should refuse an array element of the wrong type`() {
        val tree = ManifestTree.write(manifest)
        val context = (tree["contexts"] as List<*>).single() as Map<*, *>
        val aggregate = (context["aggregates"] as List<*>).single() as Map<*, *>
        val command = (aggregate["commands"] as List<*>).first() as Map<*, *>
        val badAlias = tree + (
            "contexts" to listOf(
                context + ("aggregates" to listOf(aggregate + ("commands" to listOf(command + ("aliases" to listOf(1)))))),
            )
            )
        assertThrows<IllegalArgumentException> { ManifestTree.read(badAlias) }
            .message.assert().isEqualTo("manifest.contexts[0].aggregates[0].commands[0].aliases[0] is not a string: 1")
        val badContext = tree + ("contexts" to listOf("shop"))
        assertThrows<IllegalArgumentException> { ManifestTree.read(badContext) }
            .message.assert().isEqualTo("manifest.contexts[0] is not an object: shop")
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
