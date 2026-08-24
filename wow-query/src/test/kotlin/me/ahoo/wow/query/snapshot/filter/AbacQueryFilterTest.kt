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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
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
    fun `tag key equal to tags path should remain relative`() {
        val filter = mapOf("tags" to listOf("*")).entries.first().toFilterExpression()

        (filter as ExistsFilter).field.assert().isEqualTo(LogicalField("tags.tags"))
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
            MOCK_AGGREGATE_METADATA
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
            MOCK_AGGREGATE_METADATA
        ).setQuery(MatchAllFilter)
        val chain = FilterChain<QueryContext<*, *>> {
            it.getQuery().assert().isInstanceOf(AndFilter::class.java)
            Mono.empty()
        }
        MockAbacQueryFilter.filter(context, chain).test().verifyComplete()
    }

    @Test
    fun `aggregation ABAC should rewrite only root filter`() {
        val elementFilter = filter { "state.orders.status" eq "PAID" }
        val aggregationFilter = object : AbacQueryFilter() {
            override fun getPrincipalTags(
                contextView: ContextView,
                context: QueryContext<*, *>,
            ): Mono<AbacTags> {
                context.queryType.assert().isEqualTo(QueryType.DYNAMIC_LIST)
                return MockAbacQueryFilter.getPrincipalTags(contextView, context)
            }
        }
        val context = SnapshotAggregationQueryContext(
            MOCK_AGGREGATE_METADATA,
            AggregationQuery(
                elements = listOf(AggregationElement("state.orders", elementFilter)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        AggregationAbacQueryFilter(listOf(aggregationFilter)).filter(
            context,
            FilterChain {
                check(context.query.filter !== MatchAllFilter)
                context.query.elements.single().filter.assert().isEqualTo(elementFilter)
                Mono.empty()
            },
        ).test().verifyComplete()
    }

    @Test
    fun `aggregation ABAC should reject an empty authorization result`() {
        val aggregationFilter = object : AbacQueryFilter() {
            override fun getPrincipalTags(
                contextView: ContextView,
                context: QueryContext<*, *>,
            ): Mono<AbacTags> = EMPTY_ABAC_TAGS.toMono()

            override fun resolveAggregationFilter(
                contextView: ContextView,
                context: SnapshotAggregationQueryContext,
            ): Mono<FilterExpression> = Mono.empty()
        }
        val context = SnapshotAggregationQueryContext(
            MOCK_AGGREGATE_METADATA,
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
        )
        var nextCalled = false

        AggregationAbacQueryFilter(listOf(aggregationFilter)).filter(
            context,
            FilterChain {
                nextCalled = true
                Mono.empty()
            },
        ).test()
            .expectError(IllegalStateException::class.java)
            .verify()

        nextCalled.assert().isFalse()
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
