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

package me.ahoo.wow.elasticsearch.query.gateway

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.MatchNone
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.RelativeTimeExpression
import me.ahoo.wow.api.query.RelativeTimeOperator
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.QueryException
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigInteger

class ElasticsearchQueryCompilerTest {
    private val aggregateId = LogicalField("aggregateId")
    private val version = LogicalField("version")
    private val deleted = LogicalField("deleted")
    private val eventTime = LogicalField("eventTime")
    private val data = LogicalField("state.data")
    private val labels = LogicalField("state.labels")
    private val items = LogicalField("state.items")
    private val sku = LogicalField("state.items.sku")
    private val schema = JacksonQuerySchemaProvider(JsonSerializer).getSchema(MOCK_AGGREGATE_METADATA).let { base ->
        QuerySchema(
            base.fields + mapOf(
                labels to QueryFieldSchema(
                    labels,
                    QueryValueKind.STRING,
                    nullable = false,
                    collectionKind = QueryCollectionKind.SCALAR,
                    sortable = false
                ),
                items to QueryFieldSchema(
                    items,
                    QueryValueKind.OBJECT,
                    nullable = false,
                    collectionKind = QueryCollectionKind.OBJECT,
                    queryable = true,
                    sortable = false
                ),
                sku to QueryFieldSchema(sku, QueryValueKind.STRING, nullable = false)
            )
        )
    }
    private val compiler = ElasticsearchQueryCompiler(
        ElasticsearchExecutionSnapshot(
            indices = listOf("wow.mock.snapshot"),
            fields = mapOf(
                aggregateId to binding("aggregateId"),
                version to binding("version"),
                deleted to binding("deleted"),
                eventTime to binding("eventTime"),
                data to binding("state.data", exact = "state.data.raw", search = "state.data"),
                labels to binding("state.labels"),
                items to binding("state.items", exact = null, nested = "state.items"),
                sku to binding("state.items.sku")
            ),
            schema = schema
        )
    )

    @Test
    fun `should compile portable expressions`() {
        val expressions = listOf(
            MatchAll,
            MatchNone,
            LogicalExpression(LogicalOperator.AND, listOf(eq(aggregateId, text("id")), isFalse())),
            LogicalExpression(LogicalOperator.OR, listOf(eq(aggregateId, text("id")), MatchNone)),
            LogicalExpression(LogicalOperator.NOR, listOf(eq(aggregateId, text("other")))),
            eq(aggregateId, text("id")),
            predicate(version, PredicateOperator.GT, number(1)),
            predicate(version, PredicateOperator.LT, number(2)),
            predicate(version, PredicateOperator.GTE, number(1)),
            predicate(version, PredicateOperator.LTE, number(2)),
            predicate(
                data,
                PredicateOperator.CONTAINS,
                text("a*b?c\\d"),
                stringComparison = StringComparison.CASE_INSENSITIVE
            ),
            predicate(aggregateId, PredicateOperator.IN, text("id"), text("other")),
            predicate(version, PredicateOperator.BETWEEN, number(1), number(2)),
            predicate(labels, PredicateOperator.CONTAINS_ALL, text("a")),
            predicate(labels, PredicateOperator.CONTAINS_ALL, text("a"), text("b")),
            predicate(data, PredicateOperator.STARTS_WITH, text("pre")),
            predicate(data, PredicateOperator.ENDS_WITH, text("post")),
            predicate(deleted, PredicateOperator.IS_TRUE),
            isFalse(),
            predicate(eventTime, PredicateOperator.GTE, text("2026-08-20T00:00:00Z")),
            ElementMatchExpression(items, eq(LogicalField("sku"), text("sku-1"))),
            SearchExpression("phone", setOf(data))
        )

        expressions.forEach { expression -> compiler.query(expression).assert().isNotNull() }
    }

    @Test
    fun `should reject unsupported and unmapped expressions`() {
        val value = JsonNodeFactory.instance.stringNode("value")
        val unsupported = listOf(
            PredicateOperator.NE,
            PredicateOperator.NOT_IN,
            PredicateOperator.IS_NULL,
            PredicateOperator.IS_NOT_NULL,
            PredicateOperator.EXISTS,
            PredicateOperator.IS_EMPTY
        ).map { operator ->
            if (operator in NO_VALUE_OPERATORS) predicate(data, operator) else predicate(data, operator, value)
        } + listOf(
            eq(data, JsonNodeFactory.instance.nullNode()),
            predicate(data, PredicateOperator.IN, value, JsonNodeFactory.instance.nullNode()),
            eq(
                version,
                JsonNodeFactory.instance.numberNode(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))
            )
        )

        unsupported.forEach { expression ->
            assertThrows<QueryException> { compiler.query(expression) }
                .code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_QUERY)
        }
        assertThrows<QueryException> {
            compiler.query(RelativeTimeExpression(eventTime, RelativeTimeOperator.TODAY))
        }.code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
        assertThrows<QueryException> {
            compiler.query(eq(LogicalField("state.unknown"), value))
        }.code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
    }

    @Test
    fun `should compile explicit sort and PIT tie breaker`() {
        val query = mockk<SecuredQuery>()
        every { query.sort } returns listOf(
            me.ahoo.wow.api.query.QuerySort(aggregateId, QuerySortDirection.ASC),
            me.ahoo.wow.api.query.QuerySort(version, QuerySortDirection.DESC)
        )

        compiler.sort(query, pit = false).assert().hasSize(2)
        compiler.sort(query, pit = true).assert().hasSize(3)
    }

    private fun eq(field: LogicalField, value: JsonNode): PredicateExpression =
        predicate(field, PredicateOperator.EQ, value)

    private fun isFalse(): PredicateExpression = predicate(deleted, PredicateOperator.IS_FALSE)

    private fun predicate(
        field: LogicalField,
        operator: PredicateOperator,
        vararg values: JsonNode,
        stringComparison: StringComparison = StringComparison.DEFAULT
    ): PredicateExpression = PredicateExpression(field, operator, values.toList(), stringComparison)

    private fun text(value: String): JsonNode = JsonNodeFactory.instance.stringNode(value)

    private fun number(value: Int): JsonNode = JsonNodeFactory.instance.numberNode(value)

    private fun binding(
        source: String,
        exact: String? = source,
        search: String? = null,
        nested: String? = null
    ): ElasticsearchFieldBinding = ElasticsearchFieldBinding(source, exact, search, exact, nested)

    private companion object {
        val NO_VALUE_OPERATORS = setOf(
            PredicateOperator.IS_NULL,
            PredicateOperator.IS_NOT_NULL,
            PredicateOperator.EXISTS,
            PredicateOperator.IS_EMPTY
        )
    }
}
