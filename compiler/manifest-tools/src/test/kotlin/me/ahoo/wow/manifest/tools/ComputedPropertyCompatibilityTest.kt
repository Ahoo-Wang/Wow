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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.BASELINE
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.CART
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.STRING
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.def
import me.ahoo.wow.manifest.tools.CompatibilityFixtures.schema
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * A computed property (`readOnly`: Wow's JSON writes it and reads nothing into it) is required by what writes it, an
 * output, and by no reader: neither an input, which old clients send, nor a stored record, which the service reads back.
 */
class ComputedPropertyCompatibilityTest {
    private val computed = STRING + ("readOnly" to true)

    /** A command `C` holding a value type `P`, the two schemas of [before] and [after] for `P`. */
    private fun nested(direction: SchemaDirection, before: Map<String, Any?>, after: Map<String, Any?>): List<String> {
        val command = schema("product" to mapOf("\$ref" to "#/\$defs/P"), required = listOf("product"))
        return SchemaCompatibility(mapOf("C" to command, "P" to before), mapOf("C" to command, "P" to after))
            .compare("C", "#/\$defs/C", "#/\$defs/C", direction = direction)
            .map { it.toString() }
    }

    private val plain = schema("code" to STRING, required = listOf("code"))
    private val withComputed = schema(
        "code" to STRING,
        "searchCode" to computed,
        required = listOf("code", "searchCode")
    )
    private val withField = schema("code" to STRING, "searchCode" to STRING, required = listOf("code", "searchCode"))

    @ParameterizedTest
    @EnumSource(SchemaDirection::class)
    fun `a computed property added should be additive, at any depth, in every direction`(direction: SchemaDirection) {
        val expected = if (direction == SchemaDirection.OUTPUT) "required field added" else "optional field added"
        nested(direction, plain, withComputed).assert().containsExactly("ADDITIVE C.product.searchCode: $expected")
    }

    @ParameterizedTest
    @EnumSource(value = SchemaDirection::class, names = ["INPUT", "STORED"])
    fun `a computed property made a required field should break a reader, which never had to have it`(
        direction: SchemaDirection,
    ) {
        nested(direction, withComputed, withField)
            .assert().containsExactly("BREAKING C.product.searchCode: field made required")
        nested(direction, withField, withComputed)
            .assert().containsExactly("ADDITIVE C.product.searchCode: field made optional")
    }

    @ParameterizedTest
    @EnumSource(SchemaDirection::class)
    fun `a computed property removed should still be a property removed`(direction: SchemaDirection) {
        nested(direction, withComputed, plain).assert().containsExactly("BREAKING C.product.searchCode: field removed")
    }

    @Test
    fun `a computed property made a field or a field made computed should be no change to an output`() {
        nested(SchemaDirection.OUTPUT, withComputed, withField).assert().isEmpty()
        nested(SchemaDirection.OUTPUT, withField, withComputed).assert().isEmpty()
    }

    @ParameterizedTest
    @EnumSource(value = SchemaDirection::class, names = ["INPUT", "STORED"])
    fun `a nullable computed property, never required, should read as before`(direction: SchemaDirection) {
        val nullable = mapOf("type" to listOf("string", "null"), "readOnly" to true)
        nested(direction, plain, schema("code" to STRING, "initial" to nullable, required = listOf("code")))
            .assert().containsExactly("ADDITIVE C.product.initial: optional field added")
    }

    @ParameterizedTest
    @EnumSource(value = SchemaDirection::class, names = ["INPUT", "STORED"])
    fun `a property whose schema is no object, which cannot be readOnly, should be required as listed`(
        direction: SchemaDirection,
    ) {
        nested(direction, schema("any" to true), schema("any" to true, required = listOf("any")))
            .assert().containsExactly("BREAKING C.product.any: field made required")
    }

    @Test
    fun `a computed property added to a command's body and to a state should be additive in a Manifest`() {
        val current = BASELINE
            .def(
                "shop.AddItem",
                schema("productId" to STRING, "key" to computed, required = listOf("productId", "key"))
            )
            .def(
                "shop.CartState",
                schema(
                    "items" to mapOf("type" to "array", "items" to STRING),
                    "count" to mapOf("type" to "integer", "readOnly" to true),
                    required = listOf("count"),
                ),
            )
        changes(BASELINE, current).assert().containsExactlyInAnyOrder(
            "ADDITIVE $CART.commands[AddItem].schema.key: optional field added",
            "ADDITIVE $CART.state.count: optional field added",
        )
    }

    private fun changes(baseline: Manifest, current: Manifest): List<String> =
        ManifestCompatibility.compare(baseline, current).changes.map { it.toString() }
}
