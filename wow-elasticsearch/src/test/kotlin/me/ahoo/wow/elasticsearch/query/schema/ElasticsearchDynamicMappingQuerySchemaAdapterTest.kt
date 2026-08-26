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

import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping
import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate
import co.elastic.clients.elasticsearch._types.mapping.MatchType
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.util.NamedValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import org.junit.jupiter.api.Test

class ElasticsearchDynamicMappingQuerySchemaAdapterTest {
    @Test
    fun `dynamic object exact should require a complete keyword template`() {
        dynamicObjectBindings(textTemplate()).assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(keywordTemplate()).assert().containsExactlyInAnyOrder(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
        )
        dynamicObjectBindings(keywordTemplate(pathMatch = "tags.*")).assert().containsExactlyInAnyOrder(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
        )
    }

    @Test
    fun `possible string templates should obey first match order`() {
        dynamicObjectBindings(
            textTemplate(name = "omitted_type", matchMappingTypes = emptyList()),
            keywordTemplate(name = "fallback"),
        ).assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(
            textTemplate(name = "all_types", matchMappingTypes = listOf("*")),
            keywordTemplate(name = "fallback"),
        ).assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(
            textTemplate(name = "string_or_long", matchMappingTypes = listOf("string", "long")),
            keywordTemplate(name = "fallback"),
        ).assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `string only exact should require disabled date and numeric detection`() {
        dynamicObjectBindings(keywordTemplate(), dateDetection = null)
            .assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(keywordTemplate(), numericDetection = true)
            .assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(keywordTemplate(), dateDetection = false, numericDetection = false)
            .assert().containsExactlyInAnyOrder(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH)
        dynamicObjectBindings(
            keywordTemplate(matchMappingTypes = listOf("*")),
            dateDetection = null,
            numericDetection = true,
        ).assert().containsExactlyInAnyOrder(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH)
    }

    @Test
    fun `detection type templates should precede a wildcard exact fallback`() {
        dynamicObjectBindings(
            textTemplate(name = "detected_date", matchMappingTypes = listOf("date")),
            keywordTemplate(name = "fallback", matchMappingTypes = listOf("*")),
            dateDetection = null,
        ).assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(
            textTemplate(name = "detected_number", matchMappingTypes = listOf("long", "double")),
            keywordTemplate(name = "fallback", matchMappingTypes = listOf("*")),
            numericDetection = true,
        ).assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `partial unrelated or excluding templates should not prove every dynamic child exact`() {
        dynamicObjectBindings(keywordTemplate(pathMatch = "other.*"))
            .assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(keywordTemplate(pathMatch = "tags.allowed*"))
            .assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(keywordTemplate(pathMatch = "tags.*", unmatch = "secret"))
            .assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(keywordTemplate(pathMatch = "tags.*", matchPattern = MatchType.Regex))
            .assert().containsExactly(QueryCapability.PRESENCE)
        dynamicObjectBindings(
            textTemplate(name = "secret_text", match = "secret"),
            keywordTemplate(name = "all_keywords"),
        ).assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `flattened dynamic children should ignore root strict but still require indexing`() {
        val indexed = TypeMapping.of { type ->
            type.dynamic(DynamicMapping.Strict)
                .properties("tags") { it.flattened { flattened -> flattened } }
        }
        val unindexed = TypeMapping.of { type ->
            type.dynamic(DynamicMapping.True)
                .properties("tags") { it.flattened { flattened -> flattened.index(false) } }
        }

        bind(indexed).assert().containsExactlyInAnyOrder(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
        )
        bind(unindexed).assert().isEmpty()
    }

    @Test
    fun `nested dynamic children should require a complete exact template`() {
        val mapping = TypeMapping.of { type ->
            type.dateDetection(false)
                .properties("tags") { it.nested { nested -> nested.dynamic(DynamicMapping.True) } }
                .dynamicTemplates(keywordTemplate(pathMatch = "tags.*"))
        }

        bind(mapping, QueryCardinality.MANY).assert().containsExactlyInAnyOrder(
            QueryCapability.PRESENCE,
            QueryCapability.EXACT_MATCH,
            QueryCapability.ELEMENT_SCOPE,
        )
    }

    @Test
    fun `alias and multi-field should not bypass dynamic child proof`() {
        val mapping = TypeMapping.of { type ->
            type.properties("source") { it.`object` { objectField -> objectField.dynamic(DynamicMapping.True) } }
                .properties("tags") { it.alias { alias -> alias.path("source") } }
                .properties("codes") {
                    it.text { text -> text.fields("keyword") { keyword -> keyword.keyword { field -> field } } }
                }
        }

        bind(mapping, field = "tags").assert().isEmpty()
        bind(mapping, field = "codes").assert().isEmpty()
    }

    @Test
    fun `explicit text child should remove inherited exact even with a keyword multi-field`() {
        val mapping = dynamicObjectMapping { objectField ->
            objectField.properties("title") {
                it.text { text -> text.fields("keyword") { keyword -> keyword.keyword { field -> field } } }
            }
        }

        bind(mapping).assert().containsExactly(QueryCapability.PRESENCE)
    }

    @Test
    fun `unindexed or container child should remove inherited presence and exact`() {
        val unindexed = dynamicObjectMapping { objectField ->
            objectField.properties("hidden") { it.keyword { keyword -> keyword.index(false) } }
        }
        val nested = dynamicObjectMapping { objectField ->
            objectField.properties("scope") { it.nested { child -> child } }
        }

        bind(unindexed).assert().isEmpty()
        bind(nested).assert().isEmpty()
    }

    @Test
    fun `safe keyword children should retain inherited bindings with exact segment boundaries`() {
        val mapping = TypeMapping.of { type ->
            type.dateDetection(false)
                .properties("tags") {
                    it.`object` { objectField ->
                        objectField.dynamic(DynamicMapping.True)
                            .properties("department") { child -> child.keyword { keyword -> keyword } }
                    }
                }.properties("tagsExtra") {
                    it.text { text -> text.fields("keyword") { keyword -> keyword.keyword { field -> field } } }
                }.dynamicTemplates(keywordTemplate(pathMatch = "tags.*"))
        }

        bind(mapping).assert().containsExactlyInAnyOrder(QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH)
    }

    private fun dynamicObjectBindings(
        vararg templates: NamedValue<DynamicTemplate>,
        dateDetection: Boolean? = false,
        numericDetection: Boolean? = null,
    ): Set<QueryCapability> {
        val mapping = TypeMapping.of { type ->
            type.properties("tags") { it.`object` { objectField -> objectField.dynamic(DynamicMapping.True) } }
            dateDetection?.let(type::dateDetection)
            numericDetection?.let(type::numericDetection)
            templates.forEach(type::dynamicTemplates)
            type
        }
        return bind(mapping)
    }

    private fun dynamicObjectMapping(
        children: (co.elastic.clients.elasticsearch._types.mapping.ObjectProperty.Builder) -> Unit,
    ): TypeMapping = TypeMapping.of { type ->
        type.dateDetection(false)
            .properties("tags") {
                it.`object` { objectField ->
                    objectField.dynamic(DynamicMapping.True).also(children)
                }
            }.dynamicTemplates(keywordTemplate(pathMatch = "tags.*"))
    }

    private fun bind(
        mapping: TypeMapping,
        cardinality: QueryCardinality = QueryCardinality.SINGLE,
        field: String = "tags",
    ): Set<QueryCapability> {
        val logical = LogicalQuerySchema(
            mapOf(
                LogicalField(field) to LogicalQueryFieldSchema(
                    title = null,
                    description = null,
                    enumValues = null,
                    valueTypes = setOf(QueryValueType.OBJECT),
                    nullable = false,
                    required = true,
                    cardinality = cardinality,
                    semanticType = null,
                    dynamicChildren = true,
                ),
            ),
        )
        return ElasticsearchQuerySchemaAdapter.bind(logical, ElasticsearchIndexMapping.from(INDEX, mapping))
            .fields.getValue(LogicalField(field)).bindings.keys
    }

    private fun keywordTemplate(
        name: String = "keyword",
        pathMatch: String? = null,
        match: String? = null,
        unmatch: String? = null,
        matchPattern: MatchType? = null,
        matchMappingTypes: List<String> = listOf("string"),
    ): NamedValue<DynamicTemplate> = NamedValue.of(
        name,
        DynamicTemplate.of { template ->
            pathMatch?.let(template::pathMatch)
            match?.let(template::match)
            unmatch?.let(template::unmatch)
            matchPattern?.let(template::matchPattern)
            if (matchMappingTypes.isNotEmpty()) template.matchMappingType(matchMappingTypes)
            template.mapping { it.keyword { keyword -> keyword } }
        },
    )

    private fun textTemplate(
        name: String = "text",
        match: String? = null,
        matchMappingTypes: List<String> = listOf("string"),
    ): NamedValue<DynamicTemplate> = NamedValue.of(
        name,
        DynamicTemplate.of { template ->
            match?.let(template::match)
            if (matchMappingTypes.isNotEmpty()) template.matchMappingType(matchMappingTypes)
            template.mapping { it.text { text -> text } }
        },
    )

    companion object {
        private const val INDEX = "index"
    }
}
