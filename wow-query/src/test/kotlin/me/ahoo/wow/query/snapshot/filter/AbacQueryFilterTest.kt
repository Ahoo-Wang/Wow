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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTagValue
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.EMPTY_ABAC_TAGS
import me.ahoo.wow.api.abac.wildcard
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter.Companion.toFilterExpression
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.context.ContextView

class AbacQueryFilterTest {
    @Test
    fun `empty AbacTags should return match all filter`() {
        EMPTY_ABAC_TAGS.toFilterExpression().assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `non-empty AbacTags should return AND filter`() {
        val filter = mapOf("dept" to listOf("eng"), "role" to listOf("admin")).toFilterExpression()

        filter.assert().isInstanceOf(AndFilter::class.java)
        (filter as AndFilter).operands.assert().hasSize(2)
    }

    @Test
    fun `principal tag values should remain backend neutral`() {
        mapOf("department" to listOf("x".repeat(9000))).toFilterExpression()
            .assert().isInstanceOf(AndFilter::class.java)
    }

    @Test
    fun `tag key equal to tags path should remain relative`() {
        val filter = mapOf("tags" to listOf("*")).entries.first().toFilterExpression()

        (filter as ExistsFilter).field.assert().isEqualTo(QueryField("tags.tags"))
    }

    @Test
    fun `wildcard extension property should return true for wildcard value`() {
        val wildcardValue: AbacTagValue = listOf("*")
        wildcardValue.wildcard.assert().isTrue()
    }

    @Test
    fun `wildcard extension property should return false for non-wildcard value`() {
        val nonWildcardValue: AbacTagValue = listOf("eng", "pm")
        nonWildcardValue.wildcard.assert().isFalse()
    }

    @Test
    fun `wildcard extension property should return false for empty list`() {
        val emptyValue: AbacTagValue = emptyList()
        emptyValue.wildcard.assert().isFalse()
    }

    @Test
    fun `filter for EmptyAbacQueryFilter`() {
        val context = DefaultQueryContext<me.ahoo.wow.api.query.FilterExpression, Any>(
            queryType = QueryType.COUNT,
            namedAggregate = MOCK_AGGREGATE_METADATA,
            schema = QUERY_SCHEMA,
        ).setQuery(MatchAllFilter)
        val chain = FilterChain<QueryContext<*, *>> {
            it.getQuery().assert().isSameAs(MatchAllFilter)
            Mono.empty()
        }
        EmptyAbacQueryFilter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `filter for MockAbacQueryFilter`() {
        val context = DefaultQueryContext<me.ahoo.wow.api.query.FilterExpression, Any>(
            queryType = QueryType.COUNT,
            namedAggregate = MOCK_AGGREGATE_METADATA,
            schema = QUERY_SCHEMA,
        ).setQuery(MatchAllFilter)
        val chain = FilterChain<QueryContext<*, *>> {
            it.getQuery().assert().isInstanceOf(AndFilter::class.java)
            Mono.empty()
        }
        MockAbacQueryFilter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `aggregation should append ABAC filter only to root filter`() {
        val query = AggregationQuery(
            filter = ExistsFilter(QueryField("tenantId")),
            elements = listOf(AggregationElement(QueryField("state.items"), ExistsFilter(QueryField("sku")))),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val context = DefaultQueryContext<AggregationQuery, Any>(
            queryType = QueryType.AGGREGATION,
            namedAggregate = MOCK_AGGREGATE_METADATA,
            schema = QUERY_SCHEMA,
        ).setQuery(query)
        val chain = FilterChain<QueryContext<*, *>> {
            val rewritten = it.getQuery() as AggregationQuery
            rewritten.filter.assert().isInstanceOf(AndFilter::class.java)
            rewritten.elements.assert().isEqualTo(query.elements)
            Mono.empty()
        }

        MockAbacQueryFilter.filter(context, chain).test().verifyComplete()
    }

    object EmptyAbacQueryFilter : AbacQueryFilter() {
        override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*, *>): Mono<AbacTags> {
            return EMPTY_ABAC_TAGS.toMono()
        }
    }

    object MockAbacQueryFilter : AbacQueryFilter() {
        override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*, *>): Mono<AbacTags> {
            return mapOf(
                "dept" to listOf("eng"),
                "role" to listOf("admin"),
            ).toMono()
        }
    }
}

private val QUERY_SCHEMA = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())
