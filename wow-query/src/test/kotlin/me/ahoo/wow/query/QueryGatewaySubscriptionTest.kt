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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.filter.EmptyFilterChain
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.Filter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.SimpleFilterChain
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class QueryGatewaySubscriptionTest {
    @Suppress("DEPRECATION")
    @Test
    fun `custom list query should retain implementation during executable conversion`() {
        lateinit var captured: IListQuery
        val chain = FilterChain<QueryContext<*, *>> { context ->
            val listContext = context.asListQuery<String>()
            captured = listContext.getQuery()
            listContext.setResult(Flux.empty())
            Mono.empty()
        }
        val handler = TestQueryGateway(chain)

        handler.list(
            MOCK_AGGREGATE_METADATA,
            CustomListQuery(Condition.tenantId("tenant-1").toFilterExpression(), marker = "custom"),
        ).test().verifyComplete()

        (captured as CustomListQuery).marker.assert().isEqualTo("custom")
        captured.filter.assert().isEqualTo(TenantIdFilter("tenant-1"))
    }

    @Test
    fun `should isolate all query operations when repeated`() {
        val filter = NonIdempotentTestFilter()
        val handler = TestQueryGateway(filter.chain())
        val publishers = operationPublishers(handler)
        filter.assertNoContexts()

        publishers.forEach { (queryType, publisher) ->
            Flux.from(publisher)
                .repeat(1)
                .test()
                .expectNextCount(2)
                .verifyComplete()

            filter.assertIsolated(queryType, 2)
        }
    }

    @Test
    fun `should isolate context when retried`() {
        val filter = NonIdempotentTestFilter(failures = 1)
        val handler = TestQueryGateway(filter.chain())

        handler.single(MOCK_AGGREGATE_METADATA, singleQuery { })
            .retry(1)
            .test()
            .expectNext(RESULT)
            .verifyComplete()

        filter.assertIsolated(QueryType.SINGLE, 2)
    }

    @Test
    fun `should isolate concurrent subscriptions`() {
        val filter = NonIdempotentTestFilter()
        val handler = TestQueryGateway(filter.chain())
        val publisher = handler.dynamicList(MOCK_AGGREGATE_METADATA, listQuery { })

        Flux.merge(
            publisher.subscribeOn(Schedulers.parallel()),
            publisher.subscribeOn(Schedulers.parallel()),
        ).test()
            .expectNextCount(2)
            .verifyComplete()

        filter.assertIsolated(QueryType.DYNAMIC_LIST, 2)
    }

    private fun operationPublishers(handler: TestQueryGateway): List<Pair<QueryType, Publisher<*>>> =
        listOf(
            QueryType.SINGLE to handler.single(MOCK_AGGREGATE_METADATA, singleQuery { }),
            QueryType.DYNAMIC_SINGLE to handler.dynamicSingle(MOCK_AGGREGATE_METADATA, singleQuery { }),
            QueryType.LIST to handler.list(MOCK_AGGREGATE_METADATA, listQuery { }),
            QueryType.DYNAMIC_LIST to handler.dynamicList(MOCK_AGGREGATE_METADATA, listQuery { }),
            QueryType.PAGED to handler.paged(MOCK_AGGREGATE_METADATA, pagedQuery { }),
            QueryType.DYNAMIC_PAGED to handler.dynamicPaged(MOCK_AGGREGATE_METADATA, pagedQuery { }),
            QueryType.AGGREGATION to handler.aggregate(
                MOCK_AGGREGATE_METADATA,
                AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
            ),
            QueryType.COUNT to handler.count(MOCK_AGGREGATE_METADATA, MatchAllFilter),
        )

    private class TestQueryGateway(chain: FilterChain<QueryContext<*, *>>) :
        AbstractQueryGateway<String>(
            chain,
            ErrorHandler<QueryContext<*, *>> { _, error -> Mono.error(error) }
        )

    private class NonIdempotentTestFilter(
        private val failures: AtomicInteger = AtomicInteger(),
    ) : Filter<QueryContext<*, *>> {
        constructor(failures: Int) : this(AtomicInteger(failures))

        private val contexts = ConcurrentLinkedQueue<QueryContext<*, *>>()

        fun chain(): FilterChain<QueryContext<*, *>> =
            SimpleFilterChain(this, SimpleFilterChain(ResultTestFilter, EmptyFilterChain.instance()))

        override fun filter(
            context: QueryContext<*, *>,
            next: FilterChain<QueryContext<*, *>>
        ): Mono<Void> {
            contexts.add(context)
            context.appendFilter(APPENDED_FILTER)
            return next.filter(context).then(
                Mono.defer {
                    if (context.queryType == QueryType.COUNT) {
                        return@defer Mono.empty()
                    }
                    val maskCount = (context.getAttribute<Int>(MASK_COUNT_KEY) ?: 0) + 1
                    check(maskCount == 1) { "Result was masked more than once." }
                    context.setAttribute(MASK_COUNT_KEY, maskCount)
                    if (failures.getAndDecrement() > 0) {
                        Mono.error(IllegalStateException("Retry query."))
                    } else {
                        Mono.empty()
                    }
                }
            )
        }

        fun assertIsolated(queryType: QueryType, expected: Int) {
            val matchedContexts = contexts.filter { it.queryType == queryType }
            matchedContexts.assert().hasSize(expected)
            matchedContexts.toSet().assert().hasSize(expected)
            matchedContexts.forEach { context ->
                queryFilter(context).assert().isEqualTo(APPENDED_FILTER)
                if (queryType == QueryType.COUNT) {
                    context.getAttribute<Int>(MASK_COUNT_KEY).assert().isNull()
                } else {
                    context.getAttribute<Int>(MASK_COUNT_KEY).assert().isOne()
                }
            }
        }

        fun assertNoContexts() {
            contexts.assert().isEmpty()
        }
    }

    private object ResultTestFilter : Filter<QueryContext<*, *>> {
        override fun filter(
            context: QueryContext<*, *>,
            next: FilterChain<QueryContext<*, *>>
        ): Mono<Void> {
            when (context.queryType) {
                QueryType.SINGLE -> context.asSingleQuery<String>().setResult(Mono.just(RESULT))
                QueryType.DYNAMIC_SINGLE -> context.asSingleQuery<DynamicDocument>().setResult(
                    Mono.just(mutableMapOf("result" to RESULT).toDynamicDocument())
                )

                QueryType.LIST -> context.asListQuery<String>().setResult(Flux.just(RESULT))
                QueryType.DYNAMIC_LIST -> context.asListQuery<DynamicDocument>().setResult(
                    Flux.just(mutableMapOf("result" to RESULT).toDynamicDocument())
                )

                QueryType.PAGED -> context.asPagedQuery<String>().setResult(
                    Mono.just(PagedList(1, listOf(RESULT)))
                )

                QueryType.DYNAMIC_PAGED -> context.asPagedQuery<DynamicDocument>().setResult(
                    Mono.just(PagedList(1, listOf(mutableMapOf("result" to RESULT).toDynamicDocument())))
                )

                QueryType.COUNT -> context.asCountQuery().setResult(Mono.just(1L))
                QueryType.AGGREGATION -> context.asAggregationQuery().setResult(
                    Flux.just(mutableMapOf("count" to 1L).toDynamicDocument())
                )
            }
            return next.filter(context)
        }
    }

    private data class CustomListQuery(
        override val filter: FilterExpression,
        val marker: String,
        override val projection: Projection = Projection.ALL,
        override val sort: List<Sort> = emptyList(),
        override val limit: Int = 0,
    ) : IListQuery {
        override fun withFilter(newFilter: FilterExpression): IListQuery = copy(filter = newFilter)

        override fun withProjection(newProjection: Projection): IListQuery = copy(projection = newProjection)
    }

    private companion object {
        const val RESULT = "result"
        const val MASK_COUNT_KEY = "maskCount"
        val APPENDED_FILTER = IdFilter("subscription")

        fun queryFilter(context: QueryContext<*, *>): me.ahoo.wow.api.query.FilterExpression =
            when (val query = context.getQuery()) {
                is me.ahoo.wow.api.query.FilterExpression -> query
                is me.ahoo.wow.api.query.FilterCapable<*> -> query.filter
                else -> error("Unsupported query type: ${query::class}.")
            }
    }
}
