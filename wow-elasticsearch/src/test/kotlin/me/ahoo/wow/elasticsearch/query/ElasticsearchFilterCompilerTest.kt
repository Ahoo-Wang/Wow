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

@file:Suppress("NoWildcardImports", "WildcardImport")

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.bool
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.exists
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.ids
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.multiMatch
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.nested
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.prefix
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.range
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.term
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.terms
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.termsSet
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.wildcard
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.*
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.util.UUID

class ElasticsearchFilterCompilerTest {
    @Test
    fun `raw snapshot compiler must not inject a deletion predicate`() {
        SnapshotFilterCompiler.compileAdmitted(MatchAllFilter)._kind().assert().isEqualTo(Query.Kind.MatchAll)
        assertQuery(SnapshotFilterCompiler.compileAdmitted(IdFilter("id-1")), ids { it.values("id-1") })
    }

    @Test
    fun `model level search should be lenient while explicit fields keep strict parsing`() {
        RawFilterCompiler.compileAdmitted(SearchFilter("value")).multiMatch().lenient().assert().isTrue()
        RawFilterCompiler.compileAdmitted(
            SearchFilter("value", setOf(QueryField("state.value"))),
        ).multiMatch().lenient().assert().isNull()
    }

    private fun assertQuery(actual: Query, expected: Query) {
        val actualGen = WowJsonpMapper.createBufferingGenerator()
        actual.serialize(actualGen, WowJsonpMapper)
        val expectedGen = WowJsonpMapper.createBufferingGenerator()
        expected.serialize(expectedGen, WowJsonpMapper)
        actualGen.jsonData.toJson().toString().assert().isEqualTo(expectedGen.jsonData.toJson().toString())
    }

    @Test
    fun `snapshot metadata filters should use document ids`() {
        assertQuery(SnapshotFilterCompiler.compileAdmitted(IdFilter("id-1")), ids { it.values("id-1") })
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(AggregateIdFilter("aggregate-1")),
            ids { it.values("aggregate-1") },
        )
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(IdsFilter(listOf("id-1", "id-2"))),
            ids { it.values("id-1", "id-2") },
        )
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(AggregateIdsFilter(listOf("aggregate-1", "aggregate-2"))),
            ids { it.values("aggregate-1", "aggregate-2") },
        )
    }

    @Test
    fun `metadata scope filters should use source metadata fields`() {
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(TenantIdFilter("tenant-1")),
            term { it.field(MessageRecords.TENANT_ID).value("tenant-1") },
        )
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(OwnerIdFilter("owner-1")),
            term { it.field(MessageRecords.OWNER_ID).value("owner-1") },
        )
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(SpaceIdFilter("space-1")),
            term { it.field(MessageRecords.SPACE_ID).value("space-1") },
        )
    }

    @Test
    fun `generic document id predicates should preserve exact id queries`() {
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(EqualFilter(QueryField("_id"), json("id-1"))),
            ids { it.values("id-1") },
        )
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(InFilter(QueryField("_id"), listOf(json("id-1"), json("id-2")))),
            ids { it.values("id-1", "id-2") },
        )
    }

    @Test
    fun `equality rejects arrays and preserves scalar serialized runtime POJOs`() {
        val nativeValue = UUID.fromString("f0191fbe-b181-4531-84be-4e8609e32966")
        val arrayValue = listOf("a", "b")

        org.junit.jupiter.api.assertThrows<me.ahoo.wow.query.schema.QuerySchemaValidationException> {
            SnapshotFilterCompiler.compileAdmitted(EqualFilter(QueryField("state.tags"), json(arrayValue)))
        }
        org.junit.jupiter.api.assertThrows<me.ahoo.wow.query.schema.QuerySchemaValidationException> {
            SnapshotFilterCompiler.compileAdmitted(
                EqualFilter(QueryField("state.number"), JsonNodeFactory.instance.numberNode(Double.NaN))
            )
        }

        val pojoQuery = SnapshotFilterCompiler.compileAdmitted(
            EqualFilter(QueryField("state.native"), JsonNodeFactory.instance.pojoNode(nativeValue)),
        ).term()
        pojoQuery.value().stringValue().assert().isEqualTo(nativeValue.toString())
    }

    @Test
    @Suppress("LongMethod")
    fun `should compile typed filter operators`() {
        val field = QueryField("state.value")
        val textField = QueryField("state.text")
        val tagsField = QueryField("state.tags")
        val one = json(1)
        val two = json(2)
        val text = json("value")
        val cases = listOf(
            AndFilter(listOf(EqualFilter(field, one), EqualFilter(field, two))) to
                bool {
                    it.filter(term { term -> term.field("state.value").value(1) })
                        .filter(term { term -> term.field("state.value").value(2) })
                },
            OrFilter(listOf(EqualFilter(field, one), EqualFilter(field, two))) to
                bool {
                    it.should(term { term -> term.field("state.value").value(1) })
                        .should(term { term -> term.field("state.value").value(2) }).minimumShouldMatch("1")
                },
            NorFilter(listOf(EqualFilter(field, one))) to
                bool { it.mustNot(term { term -> term.field("state.value").value(1) }) },
            NotEqualFilter(field, one) to
                bool { it.mustNot(term { term -> term.field("state.value").value(1) }) },
            GreaterThanFilter(field, one) to
                range { it.untyped { range -> range.field("state.value").gt(JsonData.of(1)) } },
            GreaterThanOrEqualFilter(field, one) to
                range { it.untyped { range -> range.field("state.value").gte(JsonData.of(1)) } },
            LessThanFilter(field, one) to
                range { it.untyped { range -> range.field("state.value").lt(JsonData.of(1)) } },
            LessThanOrEqualFilter(field, one) to
                range { it.untyped { range -> range.field("state.value").lte(JsonData.of(1)) } },
            ContainsFilter(textField, "value*?\\tail", StringComparison.CASE_INSENSITIVE) to
                wildcard { it.field("state.text").value("*value\\*\\?\\\\tail*").caseInsensitive(true) },
            StartsWithFilter(textField, "value", StringComparison.CASE_INSENSITIVE) to
                prefix { it.field("state.text").value("value").caseInsensitive(true) },
            EndsWithFilter(textField, "value*?\\tail", StringComparison.CASE_INSENSITIVE) to
                wildcard { it.field("state.text").value("*value\\*\\?\\\\tail").caseInsensitive(true) },
            InFilter(field, listOf(one, two)) to
                terms {
                    it.field("state.value").terms { values ->
                        values.value(listOf(FieldValue.of(1), FieldValue.of(2)))
                    }
                },
            NotInFilter(field, listOf(one, two)) to
                bool {
                    it.mustNot(
                        terms { terms ->
                            terms.field("state.value")
                                .terms { values -> values.value(listOf(FieldValue.of(1), FieldValue.of(2))) }
                        }
                    )
                },
            BetweenFilter(field, one, two) to
                range {
                    it.untyped { range ->
                        range.field("state.value")
                            .gte(JsonData.of(1)).lte(JsonData.of(2))
                    }
                },
            ContainsAllFilter(tagsField, listOf(one, two)) to
                termsSet {
                    it.field("state.tags").terms(FieldValue.of(1), FieldValue.of(2))
                        .minimumShouldMatch("2")
                },
            IsEmptyFilter(tagsField) to bool { it.mustNot(exists { exists -> exists.field("state.tags") }) },
            IsNullFilter(field) to bool { it.mustNot(exists { exists -> exists.field("state.value") }) },
            IsNotNullFilter(field) to exists { it.field("state.value") },
            ExistsFilter(field) to exists { it.field("state.value") },
            NotExistsFilter(field) to bool { it.mustNot(exists { exists -> exists.field("state.value") }) },
            ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("name"), text)) to
                nested {
                    it.path("storage.items").query(term { term -> term.field("storage.items.name").value("value") })
                },
            SearchFilter("value", linkedSetOf(field)) to
                multiMatch { it.query("value").fields("state.value") },
            SearchFilter("event sourcing", linkedSetOf(field), SearchMode.PHRASE) to
                multiMatch { it.query("event sourcing").fields("state.value").type(TextQueryType.Phrase) },
        )

        cases.forEach { (filter, expected) -> assertQuery(RawFilterCompiler.compileAdmitted(filter), expected) }
    }

    @Test
    fun `deletion compilation should preserve explicitly requested scopes`() {
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(DeletionFilter(DeletionState.ACTIVE)),
            term { it.field(StateAggregateRecords.DELETED).value(false) },
        )
        SnapshotFilterCompiler.compileAdmitted(MatchNoneFilter)._kind().assert().isEqualTo(Query.Kind.MatchNone)
        SnapshotFilterCompiler.compileAdmitted(DeletionFilter(DeletionState.ALL))._kind().assert().isEqualTo(
            Query.Kind.MatchAll,
        )
        assertQuery(
            SnapshotFilterCompiler.compileAdmitted(
                AndFilter(
                    listOf(
                        DeletionFilter(DeletionState.DELETED),
                        EqualFilter(QueryField("state.name"), json("Wow")),
                    ),
                ),
            ),
            bool {
                it.filter(term { term -> term.field(StateAggregateRecords.DELETED).value(true) })
                    .filter(term { term -> term.field("state.name").value("Wow") })
            },
        )
    }

    @Test
    fun `relative time filter should normalize before compilation`() {
        SnapshotFilterCompiler.compileAdmitted(TodayFilter(QueryField("state.time")))._kind().assert()
            .isEqualTo(Query.Kind.Bool)
    }

    @Test
    fun `element predicates compile the physical paths admission resolved`() {
        val query = RawFilterCompiler.compileAdmitted(
            filter { "state.items".elementMatch { "name" eq "value" } },
        )

        query.nested().path().assert().isEqualTo("storage.items")
        query.nested().query().term().field().assert().isEqualTo("storage.items.name")
    }

    @Test
    fun `document id shortcut stays outside nested queries`() {
        val query = RawFilterCompiler.compileAdmitted(
            ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("_id"), json("line-1"))),
        )

        query.nested().query().term().field().assert().isEqualTo("storage.items._id")
    }

    companion object {
        private val ANY = QueryValueType("ANY")

        /** An admissible schema: `state.items` binds to another physical path to show compilers use resolutions. */
        private val SCHEMA = nativeSchema(
            capabilities = setOf(QueryCapability.FULL_TEXT_TERMS),
            fields = buildMap {
                listOf(
                    MessageRecords.AGGREGATE_ID,
                    MessageRecords.TENANT_ID,
                    MessageRecords.OWNER_ID,
                    MessageRecords.SPACE_ID,
                    StateAggregateRecords.DELETED,
                    "_id",
                    "state.name",
                    "state.number",
                    "state.native",
                ).forEach { put(QueryField(it), nativeBindings(QueryField(it), QueryCapability.EXACT_MATCH)) }
                put(
                    QueryField("state.value"),
                    nativeBindings(
                        QueryField("state.value"),
                        QueryCapability.EXACT_MATCH,
                        QueryCapability.RANGE,
                        QueryCapability.PRESENCE,
                        QueryCapability.FULL_TEXT_TERMS,
                        QueryCapability.FULL_TEXT_PHRASE,
                    ),
                )
                put(QueryField("state.text"), nativeBindings(QueryField("state.text"), QueryCapability.LITERAL_MATCH))
                put(
                    QueryField("state.tags"),
                    nativeBindings(QueryField("state.tags"), QueryCapability.EXACT_MATCH, QueryCapability.PRESENCE),
                )
                put(QueryField("state.time"), nativeBindings(QueryField("state.time"), QueryCapability.RANGE))
                put(
                    QueryField("state.items"),
                    nativeBindings(QueryField("storage.items"), QueryCapability.ELEMENT_SCOPE),
                )
                put(
                    QueryField("state.items.name"),
                    nativeBindings(QueryField("storage.items.name"), QueryCapability.EXACT_MATCH),
                )
                put(
                    QueryField("state.items._id"),
                    nativeBindings(QueryField("storage.items._id"), QueryCapability.EXACT_MATCH),
                )
            },
            semanticTypes = mapOf(QueryField("state.time") to Temporal.Date),
            values = buildMap {
                listOf("state.value", "state.number", "state.native").forEach {
                    put(QueryField(it), QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(ANY)))
                }
                put(
                    QueryField("state.tags"),
                    QueryValueSchema(
                        QueryValueKind.ARRAY,
                        items = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(ANY)),
                    ),
                )
            },
        )

        private fun json(value: Any?): JsonNode = JsonSerializer.valueToTree(value)

        /** Admits [filter] against [SCHEMA] and compiles it, exactly as a backend does. */
        private fun AbstractElasticsearchFilterCompiler.compileAdmitted(filter: FilterExpression): Query =
            compile(filter, SCHEMA)
    }

    private object RawFilterCompiler : AbstractElasticsearchFilterCompiler()
}
