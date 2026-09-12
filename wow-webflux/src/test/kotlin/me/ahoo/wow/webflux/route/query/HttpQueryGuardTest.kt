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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.webflux.route.query

import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class HttpQueryGuardTest {
    private val request = MockServerRequest.builder().build()
    private val sseRequest = MockServerRequest.builder()
        .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
        .build()

    @Test
    fun rejectsInvalidConfiguration() {
        listOf(-1, -1, -1, -1, -1).forEachIndexed { index, value ->
            assertThrows<IllegalArgumentException> {
                HttpQueryGuard(
                    maxListSize = if (index == 0) value else 1,
                    maxPageSize = if (index == 1) value else 1,
                    maxPageWindow = if (index == 2) value.toLong() else 1,
                    maxFilterNodes = if (index == 3) value else 1,
                    maxFilterValues = if (index == 4) value else 1,
                )
            }
        }
        assertThrows<IllegalArgumentException> { HttpQueryGuard(idleTimeout = Duration.ofMillis(-1)) }
    }

    @Test
    fun enforcesListAndFilterBudgetsBeforeInvokingGateway() {
        val allowed = ListQuery(
            filterExpression { repeat(127) { MessageRecords.AGGREGATE_ID eq it } },
            limit = 1,
        )
        expectAllowed(QueryType.LIST, allowed, guard(idleTimeout = Duration.ZERO))

        val rejected = listOf(
            ListQuery(MatchAllFilter),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.containsText("wow") }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.endsWithText("wow") }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.startsWithText("") }, limit = 1),
            ListQuery(
                filterExpression {
                    MessageRecords.AGGREGATE_ID.startsWithText("wow", StringComparison.CASE_INSENSITIVE)
                },
                limit = 1,
            ),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID ne "aggregate-id" }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID notIn listOf("aggregate-id") }, limit = 1),
            ListQuery(filterExpression { nor { MessageRecords.AGGREGATE_ID eq "aggregate-id" } }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.isNull() }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.isNotNull() }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.notExists() }, limit = 1),
            ListQuery(IsEmptyFilter(QueryField("state.items")), limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID isIn List(1001) { it } }, limit = 1),
            ListQuery(IdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(AggregateIdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(filterExpression { repeat(128) { MessageRecords.AGGREGATE_ID eq it } }, limit = 1),
        )
        rejected.forEach { expectRejected(QueryType.LIST, it) }
    }

    @Test
    fun enforcesPageCursorAndCountingBudgets() {
        listOf(
            Pagination(index = 0, size = 1),
            Pagination(index = 1, size = 101),
            Pagination(index = 101, size = 100),
            Pagination(index = Int.MAX_VALUE, size = 100),
        ).forEach { pagination ->
            expectRejected(QueryType.PAGED, PagedQuery(MatchAllFilter, pagination = pagination))
        }
        expectRejected(
            QueryType.PAGED,
            PagedQuery(MatchAllFilter, pagination = Pagination(index = 1_500_000_000, size = 2)),
            guard(maxPageSize = 0, maxPageWindow = Long.MAX_VALUE),
        )
        expectAllowed(
            QueryType.CURSOR,
            CursorQuery(MatchAllFilter, size = 2),
            guard(maxPageSize = 2, maxPageWindow = 1),
        )
        expectRejected(QueryType.CURSOR, CursorQuery(MatchAllFilter, size = 3), guard(maxPageSize = 2))
        expectRejected(
            QueryType.CURSOR,
            CursorQuery(ContainsFilter(QueryField("state.name"), "x")),
        )

        expectRejected(QueryType.COUNT, MatchAllFilter)
        expectRejected(QueryType.COUNT, DeletionFilter(DeletionState.ALL))
        expectAllowed(QueryType.COUNT, MatchAllFilter, scope = IdFilter("aggregate-id"))
        expectAllowed(QueryType.COUNT, MatchAllFilter, guard(allowExpensiveOperators = true))
    }

    @Test
    fun validatesAggregationWorkAndCombinedScopeQuota() {
        val metricSort = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("count"), Sort.Direction.ASC)),
            limit = 1,
        )
        val computed = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        AggregationExpression.Field(QueryField("state.price")),
                        AggregationExpression.Field(QueryField("state.quantity")),
                    ),
                    "total",
                ),
            ),
            limit = 1,
        )
        val element = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("state.orders"))),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 1,
        )
        listOf(metricSort, computed, element).forEach {
            expectRejected(QueryType.AGGREGATION, it)
        }

        val scoped = AggregationQuery(
            filter = filterExpression { "state.status" eq "ACTIVE" },
            elements = listOf(
                AggregationElement(
                    path = QueryField("state.orders"),
                    filter = filterExpression { "status" eq "PAID" },
                ),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 1,
        )
        expectRejected(
            QueryType.AGGREGATION,
            scoped,
            guard(maxFilterNodes = 2, allowExpensiveOperators = true),
            TenantIdFilter("tenant-id"),
        )

        val valueHeavy = scoped.copy(
            filter = filterExpression { "state.status" isIn listOf("ACTIVE", "PAID") },
            elements = emptyList(),
        )
        expectRejected(
            QueryType.AGGREGATION,
            valueHeavy,
            guard(maxFilterValues = 1, allowExpensiveOperators = true),
        )
    }

    @Test
    fun `aggregation metric filters count toward filter value limits`() {
        expectRejected(
            QueryType.AGGREGATION,
            aggregation {
                count("statuses") { "status" isIn listOf("PAID", "SHIPPED", "CANCELLED") }
            },
            guard(maxFilterValues = 2),
        )
    }

    @Test
    fun `having values count toward filter value limits`() {
        expectAllowed(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { "c".isIn(listOf(1.0, 2.0)) }
            },
            guard(maxFilterValues = 2),
        )
        expectRejected(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { "c".isIn(listOf(1.0, 2.0, 3.0)) }
            },
            guard(maxFilterValues = 2),
        )
    }

    @Test
    fun `having nodes count toward filter node limits`() {
        expectAllowed(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { ("c" gt 1.0) and ("c" lt 2.0) }
            },
            guard(maxFilterNodes = 3),
        )
        expectRejected(
            QueryType.AGGREGATION,
            aggregation {
                terms("state.status", "status")
                count("c")
                having { ("c" gt 1.0) and (("c" lt 2.0) and ("c" ne 3.0)) }
            },
            guard(maxFilterNodes = 2),
        )
    }

    @Test
    fun rejectsArithmeticExpressionsOnAllExpressionBearingMetrics() {
        val distinctCountArithmetic = AggregationQuery(
            metrics = listOf(
                AggregationMetric.DistinctCount(
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.ADD,
                        AggregationExpression.Field(QueryField("state.amount")),
                        AggregationExpression.Constant(0.0),
                    ),
                    "amounts",
                ),
            ),
            limit = 1,
        )
        val percentileArithmetic = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Percentile(
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        AggregationExpression.Field(QueryField("state.amount")),
                        AggregationExpression.Constant(1.0),
                    ),
                    95.0,
                    "p95",
                ),
            ),
            limit = 1,
        )
        listOf(distinctCountArithmetic, percentileArithmetic).forEach {
            expectRejected(QueryType.AGGREGATION, it)
        }

        val fieldOnly = AggregationQuery(
            metrics = listOf(
                AggregationMetric.DistinctCount(
                    AggregationExpression.Field(QueryField("state.amount")),
                    "amounts",
                ),
                AggregationMetric.Percentile(
                    AggregationExpression.Field(QueryField("state.amount")),
                    95.0,
                    "p95",
                ),
            ),
            limit = 1,
        )
        expectAllowed(QueryType.AGGREGATION, fieldOnly)
    }

    @Test
    fun `derived metrics count as arithmetic expressions`() {
        val derived = aggregation {
            count("total")
            derived("half") { ref("total") / constant(2.0) }
        }
        expectRejected(QueryType.AGGREGATION, derived, guard(allowExpensiveOperators = false))
        expectAllowed(QueryType.AGGREGATION, derived, guard(allowExpensiveOperators = true))
    }

    @Test
    fun timesOutTheWholeMonoAndIdleFluxPublisher() {
        StepVerifier.withVirtualTime {
            guard(idleTimeout = Duration.ofSeconds(1)).mono(QueryType.SINGLE, IdFilter("id")) {
                Mono.delay(Duration.ofSeconds(2)).thenReturn(1)
            }
        }.thenAwait(Duration.ofSeconds(1))
            .expectError(TimeoutException::class.java)
            .verify()

        StepVerifier.withVirtualTime {
            guard(idleTimeout = Duration.ofSeconds(1)).flux(
                QueryType.LIST,
                ListQuery(IdFilter("id"), limit = 2),
                sseRequest,
            ) {
                Flux.concat(Mono.just(1), Mono.delay(Duration.ofSeconds(2)).thenReturn(2))
            }
        }.expectNext(1)
            .thenAwait(Duration.ofSeconds(1))
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun continuousSseMayRunLongerThanTheIdleTimeout() {
        StepVerifier.withVirtualTime {
            guard(idleTimeout = Duration.ofSeconds(1)).flux(
                QueryType.LIST,
                ListQuery(IdFilter("id"), limit = 5),
                sseRequest,
            ) {
                Flux.interval(Duration.ofMillis(500)).take(5)
            }
        }.thenAwait(Duration.ofSeconds(3))
            .expectNext(0, 1, 2, 3, 4)
            .verifyComplete()
    }

    @Test
    fun propagatesCancellationToTheGatewayPublisher() {
        val cancelled = AtomicBoolean()
        StepVerifier.withVirtualTime {
            guard().flux(
                QueryType.LIST,
                ListQuery(IdFilter("id"), limit = 1),
                sseRequest,
            ) {
                Flux.never<Int>().doOnCancel { cancelled.set(true) }
            }
        }.thenCancel().verify()

        cancelled.get().assert().isTrue()
    }

    @Test
    fun buffersJsonButStreamsSseBeforeALateFailure() {
        val failure = IllegalStateException("late failure")
        val query = ListQuery(IdFilter("id"), limit = 2)

        guard(idleTimeout = Duration.ZERO).flux(QueryType.LIST, query, request) {
            Flux.concat(Flux.just(1), Flux.error(failure))
        }.test()
            .expectErrorMatches { it === failure }
            .verify()

        guard(idleTimeout = Duration.ZERO).flux(QueryType.LIST, query, sseRequest) {
            Flux.concat(Flux.just(1), Flux.error(failure))
        }.test()
            .expectNext(1)
            .expectErrorMatches { it === failure }
            .verify()
    }

    @Test
    fun enforcesActualOutputSizesAndCancelsExcessFlux() {
        val cancelled = AtomicBoolean()
        val bounded = guard(maxListSize = 2, idleTimeout = Duration.ZERO)
        bounded.flux(
            QueryType.LIST,
            ListQuery(IdFilter("id"), limit = 2),
            request,
        ) {
            Flux.range(1, 3).doOnCancel { cancelled.set(true) }
        }.test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
        cancelled.get().assert().isTrue()

        val boundedPage = guard(maxPageSize = 2, idleTimeout = Duration.ZERO)
        boundedPage.mono(QueryType.PAGED, PagedQuery(IdFilter("id"))) {
            Mono.just(PagedList(3, listOf(1, 2, 3)))
        }.test().expectError(IllegalArgumentException::class.java).verify()

        boundedPage.mono(QueryType.CURSOR, CursorQuery(IdFilter("id"))) {
            Mono.just(CursorPage(listOf(1, 2, 3), null))
        }.test().expectError(IllegalArgumentException::class.java).verify()
    }

    @Test
    fun zeroCapsExplicitlyDisableInputAndOutputLimits() {
        val guard = guard(maxListSize = 0, maxPageSize = 0, idleTimeout = Duration.ZERO)
        guard.flux(QueryType.LIST, ListQuery(MatchAllFilter, limit = 0), request) {
            Flux.range(1, 1001)
        }.test().expectNextCount(1001).verifyComplete()

        guard.mono(QueryType.PAGED, PagedQuery(IdFilter("id"), pagination = Pagination(size = 1000))) {
            Mono.just(PagedList(1000, List(1000) { it }))
        }.test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun rejectedHandlerInputNeverInvokesGateway() {
        val gateway = mockk<QueryGateway<Any>>()
        val handler = ListQueryHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            queryGateway = gateway,
            queryRequestScope = QueryRequestScope { _, _ -> MatchAllFilter },
            exceptionHandler = WebFluxRequestExceptionHandler(),
            guard = guard(maxListSize = 1),
            rewriteResult = { it },
        )
        val response = handler.handle(
            MockServerRequest.builder().body(ListQuery(MatchAllFilter, limit = 2).toMono()),
        ).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        verify(exactly = 0) { gateway.dynamicList(any()) }
    }

    private fun guard(
        maxListSize: Int = 1000,
        maxPageSize: Int = 100,
        maxPageWindow: Long = 10_000,
        maxFilterNodes: Int = HttpQueryGuard.DEFAULT_MAX_FILTER_NODES,
        maxFilterValues: Int = 1000,
        allowExpensiveOperators: Boolean = false,
        idleTimeout: Duration = Duration.ofSeconds(10),
    ) = HttpQueryGuard(
        maxListSize = maxListSize,
        maxPageSize = maxPageSize,
        maxPageWindow = maxPageWindow,
        maxFilterNodes = maxFilterNodes,
        maxFilterValues = maxFilterValues,
        allowExpensiveOperators = allowExpensiveOperators,
        idleTimeout = idleTimeout,
    )

    private fun expectRejected(
        queryType: QueryType,
        query: Any,
        guard: HttpQueryGuard = guard(),
        scope: FilterExpression = MatchAllFilter,
    ) {
        val invoked = AtomicBoolean()
        guard.mono(queryType, query, scope) {
            invoked.set(true)
            Mono.just(1)
        }.test().expectError(IllegalArgumentException::class.java).verify()
        invoked.get().assert().isFalse()
    }

    private fun expectAllowed(
        queryType: QueryType,
        query: Any,
        guard: HttpQueryGuard = guard(),
        scope: FilterExpression = MatchAllFilter,
    ) {
        guard.mono(queryType, query, scope) { Mono.just(1) }
            .test().expectNext(1).verifyComplete()
    }

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
