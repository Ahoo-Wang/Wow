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
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy.Companion.toFilterExpression
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.context.Context
import reactor.util.context.ContextView

class AbacQueryPolicyTest {
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
    fun `empty principal tags resolve to unrestricted scope`() {
        val context =
            QueryContext<me.ahoo.wow.api.query.FilterExpression>(MatchAllFilter, MOCK_AGGREGATE_METADATA, QUERY_SCHEMA)
        EmptyAbacQueryPolicy.evaluate(Context.empty(), context).test()
            .expectNext(MatchAllFilter).verifyComplete()
    }

    @Test
    fun `principal tags resolve independently from the caller query`() {
        val query = AggregationQuery(
            filter = ExistsFilter(QueryField("tenantId")),
            elements = listOf(AggregationElement(QueryField("state.items"), ExistsFilter(QueryField("sku")))),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val context = QueryContext(query, MOCK_AGGREGATE_METADATA, QUERY_SCHEMA)
        MockAbacQueryPolicy.evaluate(Context.empty(), context).test()
            .assertNext { it.assert().isInstanceOf(AndFilter::class.java) }.verifyComplete()
        context.query.assert().isSameAs(query)
    }

    @Test
    fun `absent principal tags resolve to match all`() {
        val policy = object : AbacQueryPolicy() {
            override fun getPrincipalTags(
                contextView: ContextView,
                context: QueryContext<*>
            ): Mono<AbacTags> = Mono.empty()
        }
        val context =
            QueryContext<me.ahoo.wow.api.query.FilterExpression>(MatchAllFilter, MOCK_AGGREGATE_METADATA, QUERY_SCHEMA)
        policy.evaluate(Context.empty(), context).test().expectNext(MatchAllFilter).verifyComplete()
    }

    @Test
    fun `event stream queries do not resolve snapshot principal tags`() {
        val policy = object : AbacQueryPolicy() {
            override fun getPrincipalTags(
                contextView: ContextView,
                context: QueryContext<*>
            ): Mono<AbacTags> = Mono.error(IllegalStateException("Snapshot tags are unavailable for event streams"))
        }
        val context = QueryContext<me.ahoo.wow.api.query.FilterExpression>(
            MatchAllFilter,
            MOCK_AGGREGATE_METADATA,
            me.ahoo.wow.query.gatewaySchema(QueryModel.EVENT_STREAM),
        )
        policy.evaluate(Context.empty(), context).test().expectNext(MatchAllFilter).verifyComplete()
    }

    object EmptyAbacQueryPolicy : AbacQueryPolicy() {
        override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> {
            return EMPTY_ABAC_TAGS.toMono()
        }
    }

    object MockAbacQueryPolicy : AbacQueryPolicy() {
        override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> {
            return mapOf(
                "dept" to listOf("eng"),
                "role" to listOf("admin"),
            ).toMono()
        }
    }
}

private val QUERY_SCHEMA = me.ahoo.wow.query.gatewaySchema(QueryModel.SNAPSHOT)
