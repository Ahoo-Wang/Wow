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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory

class QueryValueSchemaTest {
    @Test
    fun `template segments follow QueryField grammar without requiring standalone fields`() {
        listOf(
            emptyList(),
            listOf(QueryPathSegment.Item),
            listOf(QueryPathSegment.Property("123"))
        ).forEach { segments ->
            val template = QueryPathTemplate(segments)
            assertThrows<IllegalArgumentException> { template.field(emptyList()) }
        }
        listOf("name", "_id", "@type", "123", "a-b", "a1", "@a-b").forEach { segment ->
            QueryPathTemplate(listOf(QueryPathSegment.Property("root"), QueryPathSegment.Property(segment)))
                .field(emptyList()).assert().isEqualTo(QueryField("root.$segment"))
        }
        listOf("", "@", "@123", "1a", "é", "a.b", "a b", "-a", "a@b").forEach { segment ->
            assertThrows<IllegalArgumentException> {
                QueryPathTemplate(listOf(QueryPathSegment.Property(segment)))
            }
        }
    }

    @Test
    fun `template inserts nested map keys before the native suffix and preserves array positions`() {
        val segments = mutableListOf<QueryPathSegment>(
            QueryPathSegment.Property("state"),
            QueryPathSegment.Property("names"),
            QueryPathSegment.Key(0),
            QueryPathSegment.Item,
            QueryPathSegment.Item,
            QueryPathSegment.Key(1),
            QueryPathSegment.Property("keyword"),
        )
        val template = QueryPathTemplate(segments)
        segments.clear()

        template.field(listOf("en", "primary")).assert().isEqualTo(QueryField("state.names.en.primary.keyword"))
        template.field(listOf("123", "primary")).assert().isEqualTo(QueryField("state.names.123.primary.keyword"))
        template.segments.count { it == QueryPathSegment.Item }.assert().isEqualTo(2)
        QueryPathTemplate(listOf(QueryPathSegment.Property("_id"))).field(emptyList())
            .assert().isEqualTo(QueryField("_id"))
    }

    @Test
    fun `template rejects ambiguous slots and keys that escape one path segment`() {
        val state = QueryPathSegment.Property("state")
        assertThrows<IllegalArgumentException> { QueryPathTemplate(listOf(state, QueryPathSegment.Key(-1))) }
        assertThrows<IllegalArgumentException> { QueryPathTemplate(listOf(state, QueryPathSegment.Key(1))) }
        assertThrows<IllegalArgumentException> {
            QueryPathTemplate(listOf(state, QueryPathSegment.Key(0), QueryPathSegment.Key(0)))
        }
        assertThrows<IllegalArgumentException> { QueryPathTemplate(listOf(QueryPathSegment.Property("state.names"))) }
        val template = QueryPathTemplate(listOf(state, QueryPathSegment.Key(0)))
        listOf(emptyList(), listOf("en", "primary"), listOf("en.primary"), listOf("*"), listOf("")).forEach { keys ->
            assertThrows<IllegalArgumentException> { template.field(keys) }
        }
    }

    @Test
    fun `binding facts snapshot collections and compare physical templates structurally`() {
        val path = QueryPathTemplate(listOf(QueryPathSegment.Property("state"), QueryPathSegment.Key(0)))
        val storageTypes = mutableSetOf(QueryStorageType("int"), QueryStorageType("long"))
        val binding = QueryFieldBindingTemplate(path, storageTypes)
        val bindings = mutableMapOf(QueryCapability.EXACT_MATCH to binding)
        val valueBindings = QueryValueBindings(bindings, projectionPath = path, responsePath = path)
        storageTypes.clear()
        bindings.clear()

        valueBindings.bindings.getValue(QueryCapability.EXACT_MATCH).storageTypes
            .assert().isEqualTo(setOf(QueryStorageType("int"), QueryStorageType("long")))
        binding.assert().isEqualTo(
            QueryFieldBindingTemplate(
                QueryPathTemplate(listOf(QueryPathSegment.Property("state"), QueryPathSegment.Key(0))),
                setOf(QueryStorageType("int"), QueryStorageType("long")),
            ),
        )
        assertThrows<IllegalArgumentException> { QueryFieldBindingTemplate(path, emptySet()) }
    }

    @Test
    fun `value tree keeps nullable map array and item boundaries independent`() {
        val city = QueryValueSchema(
            kind = QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
        )
        val properties = mutableMapOf("city" to city)
        val address =
            QueryValueSchema(kind = QueryValueKind.OBJECT, properties = properties, nullable = true)
        val addresses = QueryValueSchema(kind = QueryValueKind.ARRAY, items = address, nullable = false)
        val byName =
            QueryValueSchema(
                kind = QueryValueKind.OBJECT,
                additionalProperties = addresses,
                nullable = true
            )
        properties.clear()

        val storedAddresses = checkNotNull(byName.additionalProperties)
        val storedAddress = checkNotNull(storedAddresses.items)
        byName.nullable.assert().isTrue()
        storedAddresses.nullable.assert().isFalse()
        storedAddress.nullable.assert().isTrue()
        storedAddress.properties.getValue("city").nullable.assert().isFalse()
        byName.cardinality.assert().isEqualTo(QueryCardinality.SINGLE)
        addresses.cardinality.assert().isEqualTo(QueryCardinality.MANY)
        addresses.valueTypes.assert().isEmpty()
    }

    @Test
    fun `union retains branches and exposes unknown cardinality instead of borrowing one branch`() {
        val scalar = QueryValueSchema(
            kind = QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
        )
        val array = QueryValueSchema(kind = QueryValueKind.ARRAY, items = scalar, nullable = true)
        val alternatives = mutableListOf(scalar, array)
        val union = QueryValueSchema(kind = QueryValueKind.UNION, alternatives = alternatives)
        alternatives.clear()

        union.alternatives.assert().hasSize(2)
        union.nullable.assert().isTrue()
        union.cardinality.assert().isNull()
        val nonNullUnion = QueryValueSchema(
            kind = QueryValueKind.UNION,
            alternatives = listOf(
                scalar,
                QueryValueSchema(kind = QueryValueKind.OBJECT, nullable = false)
            ),
        )
        nonNullUnion.nullable.assert().isFalse()
        nonNullUnion.cardinality.assert().isEqualTo(QueryCardinality.SINGLE)
        QueryValueSchema(kind = QueryValueKind.UNKNOWN).cardinality.assert().isNull()
        QueryValueSchema(
            kind = QueryValueKind.NULL,
        ).cardinality.assert().isEqualTo(QueryCardinality.SINGLE)
    }

    @Test
    fun `value metadata snapshots mutable enum nodes and type sets`() {
        val value = JsonNodeFactory.instance.objectNode().put("code", "before")
        val enums = mutableListOf<tools.jackson.databind.JsonNode>(value)
        val types = mutableSetOf(QueryValueType.OBJECT)
        val schema =
            QueryValueSchema(kind = QueryValueKind.OBJECT, enumValues = enums, valueTypes = types)
        value.put("code", "after")
        enums.clear()
        types.clear()

        schema.enumValues!!.single().get("code").stringValue().assert().isEqualTo("before")
        schema.valueTypes.assert().isEqualTo(setOf(QueryValueType.OBJECT))
        (schema.enumValues!!.single() as tools.jackson.databind.node.ObjectNode).put("code", "reader mutation")
        schema.enumValues!!.single().get("code").stringValue().assert().isEqualTo("before")
    }

    @Test
    fun `contradictory shapes fail at the value boundary`() {
        val unknown = QueryValueSchema(kind = QueryValueKind.UNKNOWN)
        assertThrows<IllegalArgumentException> { QueryValueSchema(kind = QueryValueKind.ARRAY) }
        assertThrows<IllegalArgumentException> { QueryValueSchema(kind = QueryValueKind.SCALAR) }
        assertThrows<IllegalArgumentException> {
            QueryValueSchema(kind = QueryValueKind.OBJECT, items = unknown)
        }
        assertThrows<IllegalArgumentException> {
            QueryValueSchema(kind = QueryValueKind.UNION, alternatives = listOf(unknown))
        }
        assertThrows<IllegalArgumentException> {
            QueryValueSchema(kind = QueryValueKind.NULL, nullable = false)
        }
    }
}
