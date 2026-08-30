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

package me.ahoo.wow.query.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class QueryContextTest {

    @Test
    fun `should set and get query`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = singleQuery { }
        context.setQuery(query)
        context.getQuery().assert().isEqualTo(query)
    }

    @Test
    fun `should throw when get query without set`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            context.getQuery()
        }
    }

    @Test
    fun `should rewrite query`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = singleQuery {
            filter { id("id-1") }
        }
        context.setQuery(query)
        context.rewriteQuery {
            singleQuery {
                filter { id("id-2") }
            }
        }
        context.getQuery().filter.assert().isEqualTo(IdFilter("id-2"))
    }

    @Test
    fun `should set and get result`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val result: Mono<Any> = Mono.just("result")
        context.setResult(result)
        context.getRequiredResult().assert().isSameAs(result)
    }

    @Test
    fun `should set result from query handler`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<String>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = singleQuery { }
        context.setQuery(query)
        context.setResult { queryArg ->
            queryArg.filter.assert().isEqualTo(query.filter)
            Mono.just("handled")
        }
        context.getRequiredResult().block().assert().isEqualTo("handled")
    }

    @Test
    fun `should rewrite result`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<String>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setResult(Mono.just("original"))
        context.rewriteResult { it.map { "$it-modified" } }
        context.getRequiredResult().block().assert().isEqualTo("original-modified")
    }

    @Test
    fun `should set and get generic attributes`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setAttribute("key1", "value1")
        val value: String? = context.getAttribute("key1")
        value.assert().isEqualTo("value1")
    }

    @Test
    fun `should return null for missing attribute`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val value: String? = context.getAttribute("missing")
        value.assert().isNull()
    }

    @Test
    fun `should expose typed count context separately`() {
        val context = DefaultQueryContext<FilterExpression, Mono<Long>>(
            queryType = QueryType.COUNT,
            namedAggregate = MOCK_AGGREGATE_METADATA,
        ).setQuery(MatchAllFilter)

        context.asCountQuery().getQuery().assert().isSameAs(MatchAllFilter)
    }

    @Test
    fun `document operation contexts should expose only object node publishers`() {
        val node = JsonSerializer.createObjectNode().put("value", "raw")
        val context = DefaultQueryContext<ISingleQuery, Mono<ObjectNode>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA,
        ).setQuery(singleQuery { }).setResult(Mono.just(node))

        context.asSingleQuery().getRequiredResult().block().assert().isSameAs(node)
    }

    @Test
    fun `should expose typed aggregation context separately`() {
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
        val context = DefaultQueryContext<AggregationQuery, Flux<ObjectNode>>(
            queryType = QueryType.AGGREGATION,
            namedAggregate = MOCK_AGGREGATE_METADATA,
        ).setQuery(query)

        context.asAggregationQuery().getQuery().assert().isSameAs(query)
    }

    @Test
    fun `match all filter should append without redundant AND`() {
        val context = DefaultQueryContext<FilterExpression, Mono<Long>>(
            queryType = QueryType.COUNT,
            namedAggregate = MOCK_AGGREGATE_METADATA,
        ).setQuery(MatchAllFilter)
        val appended = IdFilter("id-1")

        context.appendFilter(appended)

        context.getQuery().assert().isSameAs(appended)
    }

    @Test
    fun `append filter should reject unsupported query type`() {
        val context = DefaultQueryContext<String, Mono<Long>>(
            queryType = QueryType.COUNT,
            namedAggregate = MOCK_AGGREGATE_METADATA,
        ).setQuery("unsupported")

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            context.appendFilter(MatchAllFilter)
        }
    }
}
