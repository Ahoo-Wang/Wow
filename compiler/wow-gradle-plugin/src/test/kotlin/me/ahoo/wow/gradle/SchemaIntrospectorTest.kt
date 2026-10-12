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

package me.ahoo.wow.gradle

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaIntrospectorTest {
    data class Money(val cents: Long) {
        @JsonValue
        fun text(): String = "$cents"
    }

    enum class Color { RED, GREEN }

    data class AddItem(
        @JsonProperty("sku") val productId: String,
        val quantity: Int = 1,
        val note: String?,
        val color: Color,
        val price: Money,
        val tags: List<Tag> = emptyList(),
        val mainTag: Tag? = null,
    )

    data class Tag(val name: String)

    class CartState(val id: String) {
        var owner: String? = null
        val items = mutableMapOf<String, Int>()
    }

    private fun fragment(): Manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = "shop",
                aggregates = listOf(
                    AggregateManifest(
                        name = "cart",
                        state = StateManifest(schemaRef(CartState::class.java.name)),
                        commands = listOf(CommandManifest("AddItem", schema = schemaRef(AddItem::class.java.name))),
                    ),
                ),
            ),
        ),
    )

    /** Money is written as its text (`@JsonValue`), which introspection cannot see: its schema is declared. */
    private val moneySchema = """{"type":"string","pattern":"^[0-9]+$"}"""

    private val introspector = SchemaIntrospector(javaClass.classLoader, mapOf(Money::class.java.name to moneySchema))

    @Suppress("UNCHECKED_CAST")
    private fun defOf(
        manifest: Manifest,
        type: Class<*>
    ): Map<String, Any?> = manifest.defs[type.name] as Map<String, Any?>

    @Test
    fun `schemas should follow what Jackson writes`() {
        val completed = introspector.complete(fragment())
        completed.unresolvedSchemaRefs.assert().isEmpty()
        val addItem = defOf(completed, AddItem::class.java)
        addItem["type"].assert().isEqualTo("object")
        val properties = addItem["properties"] as Map<*, *>
        properties.keys.assert()
            .containsExactlyInAnyOrder("sku", "quantity", "note", "color", "price", "tags", "mainTag")
        // Required: non-null constructor parameters without a default.
        (addItem["required"] as List<*>).assert().containsExactlyInAnyOrder("sku", "color", "price")
        properties["note"].assert().isEqualTo(mapOf("type" to listOf("string", "null")))
        properties["color"].assert().isEqualTo(mapOf("type" to "string", "enum" to listOf("RED", "GREEN")))
        // A type referred to more than once is a definition.
        properties["tags"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("\$ref" to schemaRef(Tag::class.java.name))),
        )
        defOf(completed, Tag::class.java).assert().isEqualTo(
            mapOf(
                "type" to "object",
                "properties" to mapOf("name" to mapOf("type" to "string")),
                "required" to listOf("name")
            ),
        )
        val items = (defOf(completed, CartState::class.java)["properties"] as Map<*, *>)["items"]
        items.assert().isEqualTo(mapOf("type" to "object", "additionalProperties" to mapOf("type" to "integer")))
        completed.defs.keys.assert().containsExactly(
            AddItem::class.java.name,
            CartState::class.java.name,
            Tag::class.java.name
        )
    }

    @Test
    fun `the state fingerprint should be the kernel's`() {
        val completed = introspector.complete(fragment())
        val model = AggregateModel(
            AggregateType("shop", "cart"),
            CartState::class.java,
            { CartState(it.id) },
            emptyMap(),
            emptyMap(),
            emptyList(),
            emptyList(),
        )
        completed.contexts.single().aggregates.single().state.fingerprint.assert()
            .isEqualTo(EventCodec().stateSchemaFingerprint(model))
    }

    @Test
    fun `an override should replace the schema of a type Jackson writes with a custom serializer`() {
        val properties = defOf(introspector.complete(fragment()), AddItem::class.java)["properties"] as Map<*, *>
        properties["price"].assert().isEqualTo(mapOf("type" to "string", "pattern" to "^[0-9]+$"))
    }

    @Test
    fun `a type Jackson does not write as an object should need an override`() {
        assertThrows<IllegalStateException> { SchemaIntrospector(javaClass.classLoader).complete(fragment()) }
            .message.assert().contains("Jackson does not write ${Money::class.java.name} as an object")
            .contains("schemaOverrides")
    }

    @Test
    fun `an override that is not a JSON object should be refused`() {
        assertThrows<IllegalArgumentException> {
            SchemaIntrospector(javaClass.classLoader, mapOf(Money::class.java.name to "[]")).complete(fragment())
        }
    }

    @Test
    fun `schemas already in the fragment should be kept`() {
        val known = fragment().copy(defs = mapOf(AddItem::class.java.name to mapOf("type" to "object")))
        val completed = introspector.complete(known)
        completed.defs[AddItem::class.java.name].assert().isEqualTo(mapOf("type" to "object"))
    }
}
