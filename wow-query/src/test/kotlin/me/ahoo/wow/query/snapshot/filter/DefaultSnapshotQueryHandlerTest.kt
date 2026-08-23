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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class DefaultSnapshotQueryHandlerTest {
    private val tailSnapshotQueryFilter = TailSnapshotQueryFilter<Any>(NoOpSnapshotQueryServiceFactory)
    private val snapshotQueryFilterChain = FilterChainBuilder<QueryContext<*, *>>()
        .addFilters(listOf(LegacyExhaustiveSnapshotQueryFilter, tailSnapshotQueryFilter))
        .filterCondition(SnapshotQueryHandler::class)
        .build()
    private val aggregationQueryFilterChain = FilterChainBuilder<SnapshotAggregationQueryContext>()
        .addFilters(listOf(TailSnapshotAggregationQueryFilter(NoOpSnapshotQueryServiceFactory)))
        .build()
    private val queryHandler = DefaultSnapshotQueryHandler(
        snapshotQueryFilterChain,
        LogErrorHandler(),
        aggregationQueryFilterChain,
    )

    @Test
    fun `aggregation should remain a source-compatible default method`() {
        val method = SnapshotQueryHandler::class.java.getMethod(
            "aggregate",
            NamedAggregate::class.java,
            AggregationQuery::class.java,
        )
        method.isDefault.assert().isTrue()
        val handler = Proxy.newProxyInstance(
            SnapshotQueryHandler::class.java.classLoader,
            arrayOf(SnapshotQueryHandler::class.java),
        ) { proxy, invoked, arguments ->
            InvocationHandler.invokeDefault(proxy, invoked, *(arguments ?: emptyArray()))
        } as SnapshotQueryHandler

        handler.aggregate(
            MOCK_AGGREGATE_METADATA,
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
        ).test()
            .expectError(UnsupportedOperationException::class.java)
            .verify()
    }

    @Test
    fun `should execute single query`() {
        val query = singleQuery {
        }

        queryHandler.single(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic single query`() {
        val query = singleQuery {
        }

        queryHandler.dynamicSingle(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute list query`() {
        val query = listQuery { }
        queryHandler.list(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic list query`() {
        val query = listQuery { }
        queryHandler.dynamicList(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute paged query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.paged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute dynamic paged query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.dynamicPaged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute count query`() {
        val condition = condition {
            id("1")
        }
        queryHandler.count(MOCK_AGGREGATE_METADATA, condition)
            .test()
            .consumeNextWith {
                it.assert().isZero()
            }
            .verifyComplete()
        queryHandler.count(MOCK_AGGREGATE_METADATA, MatchAllFilter)
            .test()
            .expectNext(0)
            .verifyComplete()
    }

    @Test
    fun `should execute aggregation query`() {
        queryHandler.aggregate(
            MOCK_AGGREGATE_METADATA,
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
        ).test()
            .assertNext { row -> row.getValue<Long>("count").assert().isZero() }
            .verifyComplete()
    }

    @Test
    fun `aggregation context should fail when the chain provides no result`() {
        val error = runCatching {
            SnapshotAggregationQueryContext(
                MOCK_AGGREGATE_METADATA,
                AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
            ).getRequiredResult()
        }.exceptionOrNull()

        error.assert().isInstanceOf(IllegalStateException::class.java)
        error!!.message.assert().contains("did not provide a result")
    }

    private object LegacyExhaustiveSnapshotQueryFilter : SnapshotQueryFilter {
        override fun filter(
            context: QueryContext<*, *>,
            next: FilterChain<QueryContext<*, *>>,
        ) = when (context.queryType) {
            QueryType.SINGLE,
            QueryType.DYNAMIC_SINGLE,
            QueryType.LIST,
            QueryType.DYNAMIC_LIST,
            QueryType.PAGED,
            QueryType.DYNAMIC_PAGED,
            QueryType.COUNT,
            -> next.filter(context)
        }
    }
}
