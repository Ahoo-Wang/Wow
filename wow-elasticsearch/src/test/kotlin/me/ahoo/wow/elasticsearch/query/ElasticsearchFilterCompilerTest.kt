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
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.util.UUID

class ElasticsearchFilterCompilerTest {
    @Test
    fun `model level search should be lenient while explicit fields keep strict parsing`() {
        RawFilterCompiler.compile(SearchFilter("value")).multiMatch().lenient().assert().isTrue()
        RawFilterCompiler.compile(
            SearchFilter("value", setOf(QueryField("state.value"))),
        ).multiMatch().lenient().assert().isNull()
    }

    private fun assertCompiled(actual: Query, expected: Query) {
        actual._kind().assert().isEqualTo(Query.Kind.Bool)
        val filters = actual.bool().filter()
        filters.first().term().field().assert().isEqualTo(StateAggregateRecords.DELETED)
        filters.first().term().value().booleanValue().assert().isFalse()
        assertQuery(filters.last(), expected)
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
        assertCompiled(SnapshotFilterCompiler.compile(IdFilter("id-1")), ids { it.values("id-1") })
        assertCompiled(
            SnapshotFilterCompiler.compile(AggregateIdFilter("aggregate-1")),
            ids { it.values("aggregate-1") },
        )
        assertCompiled(
            SnapshotFilterCompiler.compile(IdsFilter(listOf("id-1", "id-2"))),
            ids { it.values("id-1", "id-2") },
        )
        assertCompiled(
            SnapshotFilterCompiler.compile(AggregateIdsFilter(listOf("aggregate-1", "aggregate-2"))),
            ids { it.values("aggregate-1", "aggregate-2") },
        )
    }

    @Test
    fun `metadata scope filters should use source metadata fields`() {
        assertCompiled(
            SnapshotFilterCompiler.compile(TenantIdFilter("tenant-1")),
            term { it.field(MessageRecords.TENANT_ID).value("tenant-1") },
        )
        assertCompiled(
            SnapshotFilterCompiler.compile(OwnerIdFilter("owner-1")),
            term { it.field(MessageRecords.OWNER_ID).value("owner-1") },
        )
        assertCompiled(
            SnapshotFilterCompiler.compile(SpaceIdFilter("space-1")),
            term { it.field(MessageRecords.SPACE_ID).value("space-1") },
        )
    }

    @Test
    fun `generic document id predicates should preserve exact id queries`() {
        assertCompiled(
            SnapshotFilterCompiler.compile(EqualFilter(QueryField("_id"), json("id-1"))),
            ids { it.values("id-1") },
        )
        assertCompiled(
            SnapshotFilterCompiler.compile(InFilter(QueryField("_id"), listOf(json("id-1"), json("id-2")))),
            ids { it.values("id-1", "id-2") },
        )
    }

    @Test
    fun `equality filters should preserve scalar arrays and runtime POJOs`() {
        val nativeValue = UUID.fromString("f0191fbe-b181-4531-84be-4e8609e32966")
        val arrayValue = listOf("a", "b")

        assertCompiled(
            SnapshotFilterCompiler.compile(EqualFilter(QueryField("state.tags"), json(arrayValue))),
            term { it.field("state.tags").value(FieldValue.of(arrayValue)) },
        )

        val pojoQuery = SnapshotFilterCompiler.compile(
            EqualFilter(QueryField("state.native"), JsonNodeFactory.instance.pojoNode(nativeValue)),
        ).bool().filter().last().term()
        pojoQuery.value().isAny.assert().isTrue()
        pojoQuery.value().anyValue().toJson(WowJsonpMapper).toString().assert()
            .isEqualTo("\"f0191fbe-b181-4531-84be-4e8609e32966\"")
    }

    @Test
    @Suppress("LongMethod")
    fun `should compile typed filter operators`() {
        val field = QueryField("state.value")
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
            ContainsFilter(field, "value*?\\tail", StringComparison.CASE_INSENSITIVE) to
                wildcard { it.field("state.value").value("*value\\*\\?\\\\tail*").caseInsensitive(true) },
            StartsWithFilter(field, "value", StringComparison.CASE_INSENSITIVE) to
                prefix { it.field("state.value").value("value").caseInsensitive(true) },
            EndsWithFilter(field, "value*?\\tail", StringComparison.CASE_INSENSITIVE) to
                wildcard { it.field("state.value").value("*value\\*\\?\\\\tail").caseInsensitive(true) },
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
            ContainsAllFilter(field, listOf(one, two)) to
                termsSet {
                    it.field("state.value").terms(FieldValue.of(1), FieldValue.of(2))
                        .minimumShouldMatch("2")
                },
            IsEmptyFilter(field) to bool { it.mustNot(exists { exists -> exists.field("state.value") }) },
            IsNullFilter(field) to bool { it.mustNot(exists { exists -> exists.field("state.value") }) },
            IsNotNullFilter(field) to exists { it.field("state.value") },
            ExistsFilter(field) to exists { it.field("state.value") },
            NotExistsFilter(field) to bool { it.mustNot(exists { exists -> exists.field("state.value") }) },
            ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("name"), text)) to
                nested { it.path("state.items").query(term { term -> term.field("state.items.name").value("value") }) },
            SearchFilter("value", linkedSetOf(field)) to
                multiMatch { it.query("value").fields("state.value") },
            SearchFilter("event sourcing", linkedSetOf(field), SearchMode.PHRASE) to
                multiMatch { it.query("event sourcing").fields("state.value").type(TextQueryType.Phrase) },
        )

        cases.forEach { (filter, expected) -> assertQuery(RawFilterCompiler.compile(filter), expected) }
    }

    @Test
    fun `deletion normalization should preserve explicit and default scopes`() {
        assertQuery(
            SnapshotFilterCompiler.compile(MatchAllFilter),
            term { it.field(StateAggregateRecords.DELETED).value(false) },
        )
        SnapshotFilterCompiler.compile(MatchNoneFilter)._kind().assert().isEqualTo(Query.Kind.MatchNone)
        SnapshotFilterCompiler.compile(DeletionFilter(DeletionState.ALL))._kind().assert().isEqualTo(
            Query.Kind.MatchAll,
        )
        assertQuery(
            SnapshotFilterCompiler.compile(
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
        SnapshotFilterCompiler.compile(TodayFilter(QueryField("state.time")))._kind().assert()
            .isEqualTo(Query.Kind.Bool)
    }

    @Test
    fun `scoped filter fields should be prefixed with parent`() {
        val query = SnapshotFilterCompiler.compile(filter { "quantity" gt 1 }, "state.orders.lines")

        query.bool().filter().last().range().untyped().field().assert().isEqualTo("state.orders.lines.quantity")
    }

    companion object {
        private fun json(value: Any?): JsonNode = JsonSerializer.valueToTree(value)
    }

    private object RawFilterCompiler : AbstractElasticsearchFilterCompiler(defaultDeletionState = null)
}
