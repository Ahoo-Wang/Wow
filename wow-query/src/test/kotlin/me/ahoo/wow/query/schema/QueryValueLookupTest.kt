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
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryValueLookupTest {
    @Test
    fun `map array lookup captures one key and keeps each value fact`() {
        val city = scalar(nullable = false)
        val address = objectValue(properties = mapOf("city" to city))
        val addresses = array(address, nullable = false)
        val root = objectValue(additionalProperties = addresses)

        val entry = root.lookup(path("home")).single()
        (entry.value === addresses).assert().isTrue()
        entry.value.nullable.assert().isFalse()
        entry.elementAncestors.assert().isEmpty()
        val match = root.lookup(path("home", "city")).single()
        (match.value === city).assert().isTrue()
        match.keys.assert().isEqualTo(listOf(QueryPathSegment.Property("home")))
        match.elementAncestors.assert().isEqualTo(listOf(path("home")))
        match.complete.assert().isTrue()
        match.value.nullable.assert().isFalse()
        root.lookup(path("home", "city", "value")).assert().isEmpty()
    }

    @Test
    fun `nested maps preserve concrete and symbolic key capture order`() {
        val root = objectValue(additionalProperties = objectValue(additionalProperties = scalar()))
        val concrete = root.lookup(path("home", "value")).single()
        concrete.keys.assert().isEqualTo(
            listOf(QueryPathSegment.Property("home"), QueryPathSegment.Property("value")),
        )
        val symbolic = root.lookup(QueryPathTemplate(listOf(QueryPathSegment.Key(0), QueryPathSegment.Key(1)))).single()
        symbolic.keys.assert().isEqualTo(listOf(QueryPathSegment.Key(0), QueryPathSegment.Key(1)))
        assertThrows<IllegalArgumentException> {
            root.lookup(QueryPathTemplate(listOf(QueryPathSegment.Key(1), QueryPathSegment.Key(0))))
        }
        root.lookup(path("home", "value", "extra")).assert().isEmpty()
    }

    @Test
    fun `object and array alternatives preserve distinct scopes`() {
        val city = scalar()
        val address = objectValue(properties = mapOf("city" to city))
        val root = union(address, array(address))
        val matches = root.lookup(path("city"))

        matches.assert().hasSize(2)
        matches[0].elementAncestors.assert().isEmpty()
        matches[1].elementAncestors.assert().isEqualTo(listOf(QueryPathTemplate(emptyList())))
        matches.all { it.value === city && it.complete }.assert().isTrue()
        val container = root.lookup(QueryPathTemplate(emptyList())).single()
        (container.value === root).assert().isTrue()
        container.complete.assert().isTrue()
    }

    @Test
    fun `unknown suffix is an incomplete witness while closed missing contributes nothing`() {
        val known = objectValue(properties = mapOf("city" to scalar()))
        val unknown = QueryValueSchema(kind = QueryValueKind.UNKNOWN)
        val mixed = union(known, unknown).lookup(path("city"))
        mixed.map { it.complete }.assert().isEqualTo(listOf(true, false))
        (mixed.last().value === unknown).assert().isTrue()
        union(known, objectValue()).lookup(path("city")).assert().hasSize(1)
        unknown.lookup(path("city", "value")).single().complete.assert().isFalse()
        unknown.lookup(QueryPathTemplate(emptyList())).single().complete.assert().isTrue()
    }

    @Test
    fun `unnamed nested arrays preserve both ancestors and explicit item stops at direct item`() {
        val city = scalar()
        val address = objectValue(properties = mapOf("city" to city))
        val inner = array(address)
        val root = objectValue(properties = mapOf("addresses" to array(inner)))
        val firstItem = QueryPathTemplate(listOf(QueryPathSegment.Property("addresses"), QueryPathSegment.Item))

        val match = root.lookup(path("addresses", "city")).single()
        match.elementAncestors.assert().isEqualTo(listOf(path("addresses"), firstItem))
        (root.lookup(firstItem).single().value === inner).assert().isTrue()
        root.lookup(firstItem).single().elementAncestors.assert().isEqualTo(listOf(path("addresses")))
        scalar().lookup(QueryPathTemplate(listOf(QueryPathSegment.Item))).assert().isEmpty()
    }

    @Test
    fun `named override blocks fallback and symbolic key selects only the map default`() {
        val defaultCity = scalar()
        val default = objectValue(properties = mapOf("city" to defaultCity))
        val root = objectValue(properties = mapOf("home" to scalar()), additionalProperties = default)

        root.lookup(path("home", "city")).assert().isEmpty()
        root.lookup(path("work", "city")).assert().hasSize(1)
        val match = root.lookup(
            QueryPathTemplate(listOf(QueryPathSegment.Key(0), QueryPathSegment.Property("city"))),
        ).single()
        (match.value === defaultCity).assert().isTrue()
        match.keys.assert().isEqualTo(listOf(QueryPathSegment.Key(0)))
    }

    @Test
    fun `symbolic and concrete map segments retain valid array ancestor templates`() {
        val root = objectValue(additionalProperties = objectValue(additionalProperties = array(scalar())))
        val path = QueryPathTemplate(
            listOf(QueryPathSegment.Property("home"), QueryPathSegment.Key(0), QueryPathSegment.Item),
        )
        val match = root.lookup(path).single()

        match.keys.assert().isEqualTo(listOf(QueryPathSegment.Property("home"), QueryPathSegment.Key(0)))
        match.elementAncestors.assert().isEqualTo(
            listOf(QueryPathTemplate(listOf(QueryPathSegment.Property("home"), QueryPathSegment.Key(0)))),
        )
    }

    @Test
    fun `enumeration visits real nodes with explicit items and map slots`() {
        val leaf = scalar()
        val root = objectValue(
            properties = mapOf("fixed" to leaf),
            additionalProperties = array(objectValue(additionalProperties = leaf)),
        )
        val values = root.valuePaths()

        values.assert().hasSize(5)
        (values.first().second === root).assert().isTrue()
        values.last().first.assert().isEqualTo(
            QueryPathTemplate(listOf(QueryPathSegment.Key(0), QueryPathSegment.Item, QueryPathSegment.Key(1))),
        )
        (values.last().second === leaf).assert().isTrue()
        union(leaf, array(leaf)).valuePaths().assert().hasSize(4)
    }

    private fun path(vararg properties: String) = QueryPathTemplate(properties.map(QueryPathSegment::Property))

    private fun scalar(nullable: Boolean = true) = QueryValueSchema(
        kind = QueryValueKind.SCALAR,
        valueTypes = setOf(QueryValueType.STRING),
        nullable = nullable,
    )

    private fun objectValue(
        properties: Map<String, QueryValueSchema> = emptyMap(),
        additionalProperties: QueryValueSchema? = null,
    ) = QueryValueSchema(
        kind = QueryValueKind.OBJECT,
        properties = properties,
        additionalProperties = additionalProperties,
    )

    private fun array(items: QueryValueSchema, nullable: Boolean = true) = QueryValueSchema(
        kind = QueryValueKind.ARRAY,
        items = items,
        nullable = nullable,
    )

    private fun union(vararg alternatives: QueryValueSchema) = QueryValueSchema(
        kind = QueryValueKind.UNION,
        alternatives = alternatives.toList(),
    )
}
