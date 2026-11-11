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

package me.ahoo.wow.elasticsearch.query.schema

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.elasticsearch.query.ElasticsearchMappedField
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory

class ElasticsearchFieldKindsTest {
    private val nodes = JsonNodeFactory.instance
    private val string = scalar(QueryValueType.STRING)
    private val integer = scalar(QueryValueType.INTEGER)
    private val nullOnly = QueryValueSchema(QueryValueKind.NULL)
    private val objectValue = QueryValueSchema(QueryValueKind.OBJECT)

    @Test
    fun `a disabled field, a field with a null_value and a capped field of unbounded values support nothing`() {
        field(Property.Kind.Keyword).supports(QueryCapability.EXACT_MATCH, string).assert().isTrue()
        field(Property.Kind.Keyword, enabled = false).supports(QueryCapability.PRESENCE, string).assert().isFalse()
        field(Property.Kind.Keyword, nullValue = JsonData.of("n/a"))
            .supports(QueryCapability.EXACT_MATCH, string).assert().isFalse()
        field(Property.Kind.Keyword, ignoreAbove = 3).supports(QueryCapability.EXACT_MATCH, string).assert().isFalse()
        field(Property.Kind.Keyword, ignoreAbove = UNCAPPED_IGNORE_ABOVE)
            .supports(QueryCapability.EXACT_MATCH, string).assert().isTrue()
    }

    @Test
    fun `a capped keyword proves only string enums whose every declared value fits`() {
        val capped = field(Property.Kind.Keyword, ignoreAbove = 3)
        fun proves(value: QueryValueSchema) = capped.supports(QueryCapability.EXACT_MATCH, value)

        proves(stringEnum(nodes.stringNode("yes"), nodes.stringNode("no"))).assert().isTrue()
        proves(union(stringEnum(nodes.stringNode("yes")), nullOnly)).assert().isTrue()
        proves(stringEnum(nodes.stringNode("yes"), nodes.nullNode())).assert().isTrue()
        proves(stringEnum(nodes.stringNode("longer"))).assert().isFalse()
        proves(stringEnum(nodes.stringNode("yes"), nodes.numberNode(1))).assert().isFalse()
        proves(stringEnum()).assert().isFalse()
        proves(string).assert().isFalse()
        val integerEnum = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.INTEGER),
            enumValues = listOf(nodes.numberNode(1)),
        )
        proves(integerEnum).assert().isFalse()
        proves(objectValue).assert().isFalse()
    }

    @Test
    fun `presence needs an index or doc values, an uncapped flattened field, or an object`() {
        field(Property.Kind.Keyword, indexed = false).supports(QueryCapability.PRESENCE, string).assert().isTrue()
        field(Property.Kind.Keyword, indexed = false, sortable = false)
            .supports(QueryCapability.PRESENCE, string).assert().isFalse()
        field(Property.Kind.Text, indexed = false).supports(QueryCapability.PRESENCE, string).assert().isFalse()
        field(Property.Kind.Flattened).supports(QueryCapability.PRESENCE, string).assert().isTrue()
        field(Property.Kind.Flattened, ignoreAbove = UNCAPPED_IGNORE_ABOVE)
            .supports(QueryCapability.PRESENCE, string).assert().isFalse()
        field(Property.Kind.Object, indexed = false, sortable = false)
            .supports(QueryCapability.PRESENCE, objectValue).assert().isTrue()
        val normalized = field(Property.Kind.Keyword, normalizer = "lowercase")
        normalized.supports(QueryCapability.PRESENCE, string).assert().isTrue()
        normalized.supports(QueryCapability.EXACT_MATCH, string).assert().isFalse()
    }

    @Test
    fun `a sort needs doc values on an exact kind, or an indexed text field`() {
        field(Property.Kind.Keyword).supports(QueryCapability.SORT, string).assert().isTrue()
        field(Property.Kind.Keyword, sortable = false).supports(QueryCapability.SORT, string).assert().isFalse()
        field(Property.Kind.Text).supports(QueryCapability.SORT, string).assert().isTrue()
        field(Property.Kind.Text, indexed = false).supports(QueryCapability.SORT, string).assert().isFalse()
        field(Property.Kind.Nested).supports(QueryCapability.SORT, objectValue).assert().isFalse()

        field(Property.Kind.Keyword).supports(QueryCapability.CURSOR_SORT, string).assert().isTrue()
        field(Property.Kind.Keyword, sortable = false).supports(QueryCapability.CURSOR_SORT, string).assert().isFalse()
        field(Property.Kind.Text).supports(QueryCapability.CURSOR_SORT, string).assert().isTrue()
        field(Property.Kind.Text, indexed = false).supports(QueryCapability.CURSOR_SORT, string).assert().isFalse()
        field(Property.Kind.UnsignedLong).supports(QueryCapability.SORT, integer).assert().isTrue()
        field(Property.Kind.UnsignedLong).supports(QueryCapability.CURSOR_SORT, integer).assert().isFalse()
    }

    @Test
    fun `terms aggregate over an aggregatable exact or text field`() {
        field(Property.Kind.Keyword).supports(QueryCapability.AGGREGATE_TERMS, string).assert().isTrue()
        field(Property.Kind.Keyword, aggregatable = false)
            .supports(QueryCapability.AGGREGATE_TERMS, string).assert().isFalse()
        field(Property.Kind.Text).supports(QueryCapability.AGGREGATE_TERMS, string).assert().isTrue()
        field(Property.Kind.Nested).supports(QueryCapability.AGGREGATE_TERMS, objectValue).assert().isFalse()
    }

    @Test
    fun `a phrase needs an indexed, uncapped search kind other than semantic text`() {
        field(Property.Kind.Text).supports(QueryCapability.FULL_TEXT_PHRASE, string).assert().isTrue()
        field(Property.Kind.Text, indexed = false).supports(QueryCapability.FULL_TEXT_PHRASE, string).assert().isFalse()
        field(Property.Kind.Text, ignoreAbove = UNCAPPED_IGNORE_ABOVE)
            .supports(QueryCapability.FULL_TEXT_PHRASE, string).assert().isFalse()
        field(Property.Kind.SemanticText).supports(QueryCapability.FULL_TEXT_PHRASE, string).assert().isFalse()
    }

    @Test
    fun `a flattened descendant matches exactly only a declared string`() {
        val flattened = field(Property.Kind.Flattened)
        flattened.supports(QueryCapability.EXACT_MATCH, string).assert().isFalse()
        flattened.supports(QueryCapability.EXACT_MATCH, string, flattenedDescendant = true).assert().isTrue()
        flattened.supports(QueryCapability.EXACT_MATCH, integer, flattenedDescendant = true).assert().isFalse()
        flattened.supports(QueryCapability.EXACT_MATCH, union(string, nullOnly), flattenedDescendant = true)
            .assert().isFalse()
        flattened.supports(QueryCapability.SORT, string, flattenedDescendant = true).assert().isFalse()
    }

    @Test
    fun `a value proves a kind through each of its scalar branches, array items included`() {
        field(Property.Kind.Nested).supports(QueryCapability.ELEMENT_SCOPE, objectValue).assert().isTrue()
        field(Property.Kind.Keyword).supports(QueryCapability.EXACT_MATCH, nullOnly).assert().isFalse()
        field(Property.Kind.Keyword).supports(QueryCapability.EXACT_MATCH, objectValue).assert().isFalse()
        field(Property.Kind.Keyword).supports(QueryCapability.EXACT_MATCH, array(string)).assert().isTrue()
        array(union(string, integer)).scalarValueTypes().assert()
            .containsExactly(QueryValueType.STRING, QueryValueType.INTEGER)

        string.provesSomeKind(QueryCapability.RANGE).assert().isTrue()
        scalar(QueryValueType.BOOLEAN).provesSomeKind(QueryCapability.RANGE).assert().isFalse()
        nullOnly.provesSomeKind(QueryCapability.EXACT_MATCH).assert().isFalse()
        union(objectValue, string).provesSomeKind(QueryCapability.EXACT_MATCH).assert().isFalse()
    }

    @Test
    fun `a formatted temporal string ranges only on a keyword kind`() {
        val formatted = QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            semanticType = Temporal.Formatted.of("yyyy-MM-dd"),
        )
        formatted.storageKinds(QueryCapability.RANGE).assert().containsExactly(KEYWORD_KINDS)
        field(Property.Kind.Keyword).supports(QueryCapability.RANGE, formatted).assert().isTrue()
        field(Property.Kind.Ip).supports(QueryCapability.RANGE, formatted).assert().isFalse()
    }

    @Test
    fun `a value is searched by words only when every string it holds is marked as prose`() {
        val marked = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), text = true)
        val plain = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
        val absent = QueryValueSchema(QueryValueKind.NULL)
        marked.searchedByWords().assert().isTrue()
        plain.searchedByWords().assert().isFalse()
        QueryValueSchema(QueryValueKind.ARRAY, items = marked).searchedByWords().assert().isTrue()
        QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(marked, absent)
        ).searchedByWords().assert().isTrue()
        QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(marked, plain)
        ).searchedByWords().assert().isFalse()
        absent.searchedByWords().assert().isFalse()
    }

    private fun field(
        kind: Property.Kind,
        indexed: Boolean = true,
        sortable: Boolean = true,
        aggregatable: Boolean = true,
        ignoreAbove: Int? = null,
        nullValue: JsonData? = null,
        normalizer: String? = null,
        enabled: Boolean = true,
    ) = ElasticsearchMappedField(
        physicalPath = "value",
        kind = kind,
        indexed = indexed,
        sortable = sortable,
        aggregatable = aggregatable,
        multiFields = emptySet(),
        projectionPath = "value",
        ignoreAbove = ignoreAbove,
        nullValue = nullValue,
        normalizer = normalizer,
        enabled = enabled,
    )

    private fun scalar(type: QueryValueType) = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(type))
    private fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)
    private fun union(vararg values: QueryValueSchema) =
        QueryValueSchema(QueryValueKind.UNION, alternatives = values.toList())
    private fun stringEnum(vararg values: JsonNode) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), enumValues = values.toList())
}
