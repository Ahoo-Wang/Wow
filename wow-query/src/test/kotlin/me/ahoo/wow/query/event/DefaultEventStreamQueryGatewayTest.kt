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

package me.ahoo.wow.query.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.filter.TailEventStreamQueryFilter
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class DefaultEventStreamQueryGatewayTest {
    private val tailSnapshotQueryFilter = TailEventStreamQueryFilter(NoOpEventStreamQueryServiceFactory)
    private val queryFilterChain = FilterChainBuilder<QueryContext<*, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter))
        .filterCondition(EventStreamQueryGateway::class)
        .build()
    private val queryGateway = DefaultEventStreamQueryGateway(
        queryFilterChain,
        LogErrorHandler()
    )

    @Test
    fun `should execute single event stream query`() {
        val query = singleQuery {
        }

        queryGateway.single(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic single event stream query`() {
        val query = singleQuery {
        }

        queryGateway.dynamicSingle(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute list event stream query`() {
        val query = listQuery { }
        queryGateway.list(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic list event stream query`() {
        val query = listQuery { }
        queryGateway.dynamicList(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute paged event stream query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryGateway.paged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute dynamic paged event stream query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryGateway.dynamicPaged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute count event stream query`() {
        val condition = condition {
            id("1")
        }
        queryGateway.count(MOCK_AGGREGATE_METADATA, condition.toFilterExpression())
            .test()
            .consumeNextWith {
                it.assert().isZero()
            }
            .verifyComplete()
        queryGateway.count(MOCK_AGGREGATE_METADATA, MatchAllFilter)
            .test()
            .expectNext(0)
            .verifyComplete()
    }

    @Test
    fun `event stream tail should execute aggregation queries`() {
        val row = mutableMapOf<String, Any?>("count" to 0L).toDynamicDocument()
        val queryService = object : EventStreamQueryService by NoOpEventStreamQueryService(MOCK_AGGREGATE_METADATA) {
            override fun aggregate(query: AggregationQuery): Flux<DynamicDocument> = Flux.just(row)
        }
        val context = DefaultQueryContext<AggregationQuery, Flux<DynamicDocument>>(
            QueryType.AGGREGATION,
            MOCK_AGGREGATE_METADATA,
        ).setQuery(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))

        TailEventStreamQueryFilter(EventStreamQueryServiceFactory { queryService })
            .filter(context, FilterChain { Mono.empty() })
            .block()

        context.getRequiredResult().test().expectNext(row).verifyComplete()
    }
}
