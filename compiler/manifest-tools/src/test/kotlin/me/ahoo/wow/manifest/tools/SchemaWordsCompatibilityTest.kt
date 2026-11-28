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
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.STRING
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.def
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.schema
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.stateFragment
import org.junit.jupiter.api.Test

/**
 * Wording is no contract (#7262): a `title` or a `description` added, changed or removed, of a type or of a property,
 * is no difference for the compatibility gate, and the annotations they come from do not move a state's fingerprint.
 */
class SchemaWordsCompatibilityTest {
    private val item = Manifest.refSchema("shop.Item")

    /** The baseline with a state, a command and an event that each hold a text and a reference to `shop.Item`. */
    private fun manifest(
        type: Map<String, Any?> = emptyMap(),
        text: Map<String, Any?> = emptyMap(),
        reference: Map<String, Any?> = emptyMap(),
        definition: Map<String, Any?> = emptyMap(),
    ): Manifest {
        val shape = schema("productId" to STRING + text, "item" to item + reference, required = listOf("productId")) + type
        return BASELINE.def("shop.Item", schema("sku" to STRING) + definition)
            .def("shop.CartState", shape).def("shop.AddItem", shape).def("shop.ItemAdded", shape)
    }

    private fun words(title: String, description: String) = mapOf("title" to title, "description" to description)

    private val plain = manifest()
    private val worded = manifest(
        type = words("Add an item", "Adds an item to the cart."),
        text = words("Product", "The product's id."),
        reference = words("Its item", "What is added."),
        definition = words("Item", "An item of the catalog."),
    )
    private val reworded = manifest(
        type = words("Add", "Puts an item into the cart."),
        text = words("Product id", "The id of the product."),
        reference = words("Item", "The item."),
        definition = words("An item", "What the catalog sells."),
    )

    @Test
    fun `words added to a type, a property and beside a reference should be no difference for the gate`() {
        ManifestCompatibility.compare(plain, worded).changes.assert().isEmpty()
    }

    @Test
    fun `words changed or removed should be no difference for the gate`() {
        ManifestCompatibility.compare(worded, reworded).changes.assert().isEmpty()
        ManifestCompatibility.compare(worded, plain).changes.assert().isEmpty()
    }

    @Test
    fun `a summary that moves from the description to the title should be no difference for the gate`() {
        val before = manifest(type = mapOf("description" to "Add an item"), definition = mapOf("description" to "Item"))
        val after = manifest(type = mapOf("title" to "Add an item"), definition = mapOf("title" to "Item"))
        ManifestCompatibility.compare(before, after).changes.assert().isEmpty()
    }

    data class Line(val sku: String, val quantity: Int)

    class Plain(val id: String, val line: Line) {
        var note: String? = null
        val lines: Int get() = 1
    }

    @Summary("Line")
    @Description("A line of the cart.")
    data class WordedLine(
        @Summary("SKU") @Description("The stock keeping unit.") val sku: String,
        @field:Summary("Quantity") val quantity: Int,
    )

    @Summary("Cart")
    @Description("What a buyer collects.")
    class Worded(
        @Summary("Id") val id: String,
        @Summary("Its line") @Description("The one line.") val line: WordedLine,
    ) {
        @Description("A note of the buyer.")
        var note: String? = null

        @Summary("Lines")
        val lines: Int get() = 1
    }

    @Test
    fun `the words of a state and of its properties should not move its fingerprint`() {
        fun completed(state: Class<*>) = SchemaIntrospector(javaClass.classLoader).complete(stateFragment(state))
        fun fingerprint(manifest: Manifest) = manifest.contexts.single().aggregates.single().state.fingerprint
        val plain = completed(Plain::class.java)
        val worded = completed(Worded::class.java)
        // The words are there, and the state's shape is told apart by none of them.
        defOf(worded, Worded::class.java)["title"].assert().isEqualTo("Cart")
        defOf(plain, Plain::class.java).containsKey("title").assert().isFalse()
        fingerprint(worded).assert().isNotNull().isEqualTo(fingerprint(plain))
    }
}
