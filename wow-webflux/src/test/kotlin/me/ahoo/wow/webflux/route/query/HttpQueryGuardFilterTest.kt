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

package me.ahoo.wow.webflux.route.query

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.filter.EmptyFilterChain
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.BatchComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackendFactory
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
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
import reactor.util.context.ContextView
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("LargeClass")
class HttpQueryGuardFilterTest {
    private val request = MockServerRequest.builder().build()

    @Test
    fun `should allow 128 filter nodes by default`() {
        val guard = HttpQueryGuardFilter(idleTimeout = Duration.ZERO)
        val allowed = ListQuery(
            filterExpression { repeat(127) { MessageRecords.AGGREGATE_ID eq it } },
            limit = 1,
        )
        val rejected = ListQuery(
            filterExpression { repeat(128) { MessageRecords.AGGREGATE_ID eq it } },
            limit = 1,
        )

        guard.filter(listContext(allowed), FilterChain { Mono.empty() })
            .writeRawRequest(request)
            .test()
            .verifyComplete()
        guard.filter(listContext(rejected), unexpectedBackend())
            .writeRawRequest(request)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `should reject unsafe list queries before backend invocation`() {
        listOf(
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
            ListQuery(
                filterExpression { nor { MessageRecords.AGGREGATE_ID eq "aggregate-id" } },
                limit = 1,
            ),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.isNull() }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.isNotNull() }, limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID.notExists() }, limit = 1),
            ListQuery(IsEmptyFilter(QueryField("state.items")), limit = 1),
            ListQuery(filterExpression { MessageRecords.AGGREGATE_ID isIn List(1001) { it } }, limit = 1),
            ListQuery(IdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(AggregateIdsFilter(List(1001) { it.toString() }), limit = 1),
            ListQuery(filterExpression { repeat(128) { MessageRecords.AGGREGATE_ID eq it } }, limit = 1),
        ).forEach { query ->
            guard().filter(listContext(query), unexpectedBackend())
                .writeRawRequest(request)
                .test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
        }
    }

    @Test
    fun `aggregation Guard should reuse existing limits and reject expensive work`() {
        val oversized = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("state.orders"))),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 101,
        )
        guard(maxListSize = 100).filter(aggregationContext(oversized), unexpectedBackend())
            .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()

        val expensive = oversized.copy(limit = 1)
        guard(allowExpensiveOperators = false).filter(aggregationContext(expensive), unexpectedBackend())
            .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()

        val metricSort = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("count"), Sort.Direction.ASC)),
        )
        guard(allowExpensiveOperators = false).filter(aggregationContext(metricSort), unexpectedBackend())
            .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()

        val filtered = AggregationQuery(
            filter = filterExpression {
                "state.customer" eq "customer-1"
                "state.status" eq "ACTIVE"
            },
            metrics = listOf(AggregationMetric.Count("count")),
        )
        guard(
            maxFilterNodes = 1,
            idleTimeout = Duration.ZERO
        ).filter(aggregationContext(filtered), unexpectedBackend())
            .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()
    }

    @Test
    fun `aggregation Guard should treat any alias sorting as expensive but allow plain any`() {
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.productId"), "productId")),
            metrics = listOf(AggregationMetric.Any(QueryField("state.productName"), "productName")),
            sort = listOf(Sort(QueryField("productName"), Sort.Direction.ASC)),
        )

        guard(allowExpensiveOperators = false)
            .filter(aggregationContext(query), unexpectedBackend())
            .writeRawRequest(request).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()

        guard(allowExpensiveOperators = false, idleTimeout = Duration.ZERO)
            .filter(
                aggregationContext(query.copy(sort = emptyList())),
                FilterChain { context ->
                    context.asAggregationQuery().setResult(Flux.empty())
                    Mono.empty()
                },
            ).writeRawRequest(request).test().verifyComplete()
    }

    @Test
    fun `aggregation Guard should control computed expressions as expensive work`() {
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
        )

        guard(allowExpensiveOperators = false).filter(aggregationContext(computed), unexpectedBackend())
            .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()

        val row = JsonNodeFactory.instance.objectNode().put("total", 10.0)
        guard(allowExpensiveOperators = true, idleTimeout = Duration.ZERO)
            .filter(
                aggregationContext(computed),
                FilterChain {
                    it.asAggregationQuery().setResult(Flux.just(row))
                    Mono.empty()
                },
            ).writeRawRequest(request).test().verifyComplete()
    }

    @Test
    fun `aggregation Guard should share the filter node budget across root and elements`() {
        val query = AggregationQuery(
            filter = filterExpression { "state.status" eq "ACTIVE" },
            elements = listOf(
                AggregationElement(
                    path = QueryField("state.orders"),
                    filter = filterExpression { "status" eq "PAID" },
                ),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        guard(maxFilterNodes = 1, allowExpensiveOperators = true)
            .filter(aggregationContext(query), unexpectedBackend())
            .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()
    }

    @Test
    fun `aggregation Guard should enforce filter value limits in every scope`() {
        val rootIn = AggregationQuery(
            filter = filterExpression { "state.status" isIn listOf("ACTIVE", "PAID") },
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val elementIn = AggregationQuery(
            elements = listOf(
                AggregationElement(
                    path = QueryField("state.orders"),
                    filter = filterExpression { "status" isIn listOf("ACTIVE", "PAID") },
                ),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val rootMetadata = AggregationQuery(
            filter = AggregateIdsFilter(listOf("order-1", "order-2")),
            metrics = listOf(AggregationMetric.Count("count")),
        )

        listOf(rootIn, elementIn, rootMetadata).forEach { query ->
            guard(maxFilterValues = 1, allowExpensiveOperators = true)
                .filter(aggregationContext(query), unexpectedBackend())
                .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()
        }
    }

    @Test
    fun `aggregation guard should allow safe group sorting without expensive operators`() {
        val row = JsonNodeFactory.instance.objectNode().put("status", "ACTIVE").put("count", 1L)
        val context = aggregationContext(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("status"), Sort.Direction.ASC)),
            ),
        )

        guard(maxListSize = 0, idleTimeout = Duration.ZERO).filter(
            context,
            FilterChain {
                it.asAggregationQuery().setResult(Flux.just(row))
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test().expectNext(row).verifyComplete()
    }

    @Test
    fun `should enforce page size and window without integer overflow`() {
        listOf(
            Pagination(index = 0, size = 1),
            Pagination(index = 1, size = 101),
            Pagination(index = 101, size = 100),
            Pagination(index = Int.MAX_VALUE, size = 100),
        ).forEach { pagination ->
            guard().filter(pagedContext(PagedQuery(MatchAllFilter, pagination = pagination)), unexpectedBackend())
                .writeRawRequest(request)
                .test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
        }

        guard(maxPageSize = 0, maxPageWindow = Long.MAX_VALUE).filter(
            pagedContext(PagedQuery(MatchAllFilter, pagination = Pagination(index = 1_500_000_000, size = 2))),
            unexpectedBackend(),
        ).writeRawRequest(request).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `should keep trusted non-http query behavior`() {
        val context = listContext(ListQuery(MatchAllFilter))
        guard().filter(
            context,
            FilterChain {
                it.asListQuery().setResult(Flux.empty())
                Mono.empty()
            },
        ).test().verifyComplete()

        context.getRequiredResult().test().verifyComplete()

        val propagatedContext = listContext(ListQuery(MatchAllFilter))
        guard().filter(
            propagatedContext,
            FilterChain {
                it.asListQuery().setResult(Flux.empty())
                Mono.empty()
            },
        ).writeRawRequest(Any()).test().verifyComplete()
    }

    @Test
    fun `should allow expensive http operators by default`() {
        val context = listContext(ListQuery(IsNotNullFilter(QueryField("state.status")), limit = 1))
        HttpQueryGuardFilter(idleTimeout = Duration.ZERO).filter(
            context,
            FilterChain {
                it.asListQuery().setResult(Flux.empty())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test().verifyComplete()
    }

    @Test
    fun `should allow unfiltered counting queries by default`() {
        val context = countContext(MatchAllFilter)
        HttpQueryGuardFilter(idleTimeout = Duration.ZERO).filter(
            context,
            FilterChain {
                it.asCountQuery().setResult(Mono.just(0))
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test().expectNext(0).verifyComplete()
    }

    @Test
    fun `plural metadata filters should respect max filter values`() {
        listOf<FilterExpression>(
            IdsFilter(listOf("id-1", "id-2")),
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
        ).forEach { filter ->
            guard(maxFilterValues = 1).filter(countContext(filter), unexpectedBackend())
                .writeRawRequest(request)
                .test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
        }
    }

    @Test
    fun `should allow application sort and filter fields`() {
        val filter = filterExpression {
            "state.status" eq "ACTIVE"
            spaceId("space-id")
            MessageRecords.VERSION eq 1
        }
        val context = pagedContext(
            PagedQuery(
                filter,
                sort = listOf(Sort(QueryField(MessageRecords.AGGREGATE_ID), Sort.Direction.DESC)),
            ),
        )
        guard().filter(
            context,
            FilterChain {
                it.asPagedQuery().setResult(Mono.empty())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()
    }

    @Test
    fun `should allow canonicalized element match`() {
        val filter = filterExpression {
            "state.items".elementMatch { "productId" eq "product-1" }
        }
        guard().filter(
            listContext(ListQuery(filter, limit = 1)),
            FilterChain {
                it.asListQuery().setResult(Flux.empty())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy element match should canonicalize before guard`() {
        val condition = Condition(
            field = "state.unindexed",
            operator = Operator.ELEM_MATCH,
            children = listOf(Condition.ALL, Condition.eq("productId", "product-1")),
        )
        guard().filter(
            listContext(ListQuery(condition, limit = 1)),
            FilterChain {
                it.asListQuery().setResult(Flux.empty())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()
    }

    @Test
    fun `should reject unfiltered counting queries`() {
        guard().filter(countContext(MatchAllFilter), unexpectedBackend())
            .writeRawRequest(request)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()

        guard().filter(
            countContext(
                filterExpression {
                    or {
                        matchAll()
                        MessageRecords.AGGREGATE_ID eq "aggregate-id"
                    }
                },
            ),
            unexpectedBackend(),
        ).writeRawRequest(request).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()

        guard().filter(
            countContext(DeletionFilter(DeletionState.ALL)),
            unexpectedBackend(),
        ).writeRawRequest(request).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()

        guard().filter(
            countContext(filterExpression { nor { nor { matchAll() } } }),
            unexpectedBackend(),
        ).writeRawRequest(request).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()

        val countBackend = FilterChain<QueryContext<*, *>> {
            it.asCountQuery().setResult(Mono.just(0))
            Mono.empty()
        }
        val scopedFilter = filterExpression {
            matchAll()
            MessageRecords.AGGREGATE_ID eq "aggregate-id"
        }
        guard().filter(countContext(scopedFilter), countBackend)
            .writeRawRequest(request)
            .test()
            .verifyComplete()

        guard().filter(pagedContext(PagedQuery(MatchAllFilter)), unexpectedBackend())
            .writeRawRequest(request)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `cursor should use max page size without page window or counting rejection`() {
        val filter = HttpQueryGuardFilter(maxPageSize = 2, maxPageWindow = 1, allowExpensiveOperators = false)

        filter.filter(cursorContext(CursorQuery(MatchAllFilter, size = 2)), EmptyFilterChain.instance())
            .writeRawRequest(request)
            .test()
            .verifyComplete()
    }

    @Test
    fun `cursor should reject oversized pages and expensive filters`() {
        guard(maxPageSize = 2).validateForTest(CursorQuery(MatchAllFilter, size = 3))
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
        guard(allowExpensiveOperators = false).validateForTest(
            CursorQuery(ContainsFilter(QueryField("state.name"), "x")),
        ).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `empty string guard should only reject the negative operator`() {
        fun filter(operator: String) = JsonSerializer.readValue(
            """{"op":"$operator","field":"state.name"}""",
            FilterExpression::class.java,
        )

        guard(allowExpensiveOperators = false)
            .validateForTest(CursorQuery(filter("IS_EMPTY_STRING")))
            .test()
            .verifyComplete()
        guard(allowExpensiveOperators = false)
            .validateForTest(CursorQuery(filter("IS_NOT_EMPTY_STRING")))
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `cursor should apply idle timeout after backend result is installed`() {
        val context = cursorContext(CursorQuery(IdFilter("aggregate-id")))
        guard(idleTimeout = Duration.ofMillis(10)).filter(
            context,
            FilterChain {
                it.asCursorQuery().setResult(Mono.never())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test()
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy empty NOT_IN should canonicalize to match all before guard`() {
        guard().filter(
            countContext(Condition.notIn(MessageRecords.AGGREGATE_ID, emptyList()).toFilterExpression()),
            unexpectedBackend(),
        ).writeRawRequest(request).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun `should apply idle timeout after backend result is installed`() {
        val context = pagedContext(PagedQuery(IdFilter("aggregate-id")))
        guard(idleTimeout = Duration.ofMillis(10)).filter(
            context,
            FilterChain {
                it.asPagedQuery().setResult(Mono.never())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test()
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun `should apply idle timeout while downstream filters are running`() {
        guard(idleTimeout = Duration.ofMillis(10)).filter(
            listContext(ListQuery(MatchAllFilter, limit = 1)),
            FilterChain { Mono.never() },
        ).writeRawRequest(request).test()
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun `json list should time out before response when a later result stalls`() {
        val context = listContext(ListQuery(MatchAllFilter, limit = 2))
        guard(idleTimeout = Duration.ofMillis(10)).filter(
            context,
            FilterChain {
                it.asListQuery().setResult(
                    Flux.concat(
                        Mono.just(JsonNodeFactory.instance.objectNode().put("value", "first")),
                        Mono.delay(Duration.ofMillis(50))
                            .thenReturn(JsonNodeFactory.instance.objectNode().put("value", "second")),
                    ),
                )
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test()
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun `json aggregation should time out before emitting a partial response`() {
        val row = JsonNodeFactory.instance.objectNode().put("count", 1L)
        val context = aggregationContext(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
        guard(idleTimeout = Duration.ofMillis(10)).filter(
            context,
            FilterChain {
                it.asAggregationQuery().setResult(Flux.concat(Flux.just(row), Flux.never()))
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test()
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun `event stream aggregation should emit rows before an idle timeout`() {
        val row = JsonNodeFactory.instance.objectNode().put("count", 1L)
        val context = aggregationContext(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
        val eventStreamRequest = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
        guard(idleTimeout = Duration.ofMillis(10)).filter(
            context,
            FilterChain {
                it.asAggregationQuery().setResult(Flux.concat(Flux.just(row), Flux.never()))
                Mono.empty()
            },
        ).writeRawRequest(eventStreamRequest).test().verifyComplete()

        context.getRequiredResult().test()
            .expectNext(row)
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun `should run before concrete abac filters in the real snapshot chain`() {
        val gateway = snapshotQueryGateway(
            guard = guard(maxFilterNodes = 1),
            abacQueryFilter = TestAbacQueryFilter,
        )

        gateway.dynamicList(ListQuery(filterExpression { "state.status" eq "ACTIVE" }, limit = 1))
            .writeRawRequest(request).test().verifyComplete()
    }

    @Test
    fun `built-in http route should map default unlimited list to bad request`() {
        val response = listHandler(snapshotQueryGateway()).handle(
            MockServerRequest.builder().body(ListQuery(MatchAllFilter).toMono()),
        ).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `built-in http route should buffer json and map a later idle timeout`() {
        val cancelled = AtomicBoolean()
        val backend = mockk<SnapshotQueryBackend> {
            io.mockk.every { list(any()) } returns Flux.concat(
                Flux.just(JsonNodeFactory.instance.objectNode()),
                Flux.never(),
            ).doOnCancel { cancelled.set(true) }
        }
        val factory = mockk<SnapshotQueryBackendFactory> {
            io.mockk.every { create(any()) } returns QueryBackendBinding(
                backend,
                RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA_PROVIDER,
            )
        }
        val response = listHandler(
            snapshotQueryGateway(
                guard = guard(idleTimeout = Duration.ofMillis(10)),
                queryBackendFactory = factory,
            ),
        ).handle(
            MockServerRequest.builder().body(ListQuery(MatchAllFilter, limit = 1).toMono()),
        ).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.REQUEST_TIMEOUT)
        cancelled.get().assert().isTrue()
    }

    @Test
    fun `built-in event load route should enforce list limit`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .pathVariable(BatchComponent.PathVariable.HEAD_VERSION, "0")
            .pathVariable(BatchComponent.PathVariable.TAIL_VERSION, "1000")
            .build()
        val response = loadEventStreamHandler(eventStreamQueryGateway()).handle(request).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `built-in event load route should allow aggregate-scoped version range`() {
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .pathVariable(BatchComponent.PathVariable.HEAD_VERSION, "0")
            .pathVariable(BatchComponent.PathVariable.TAIL_VERSION, "1")
            .build()
        val response = loadEventStreamHandler(eventStreamQueryGateway()).handle(request).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `built-in snapshot load route should apply idle timeout`() {
        val backend = mockk<SnapshotQueryBackend> {
            io.mockk.every { single(any()) } returns Mono.never()
        }
        val factory = mockk<SnapshotQueryBackendFactory> {
            io.mockk.every { create(any()) } returns QueryBackendBinding(
                backend,
                RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA_PROVIDER,
            )
        }
        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .build()

        val response = loadSnapshotHandler(
            snapshotQueryGateway(
                guard = guard(idleTimeout = Duration.ofMillis(10)),
                queryBackendFactory = factory,
            ),
        ).handle(request).block()!!

        response.statusCode().assert().isEqualTo(HttpStatus.REQUEST_TIMEOUT)
    }

    private fun guard(
        maxListSize: Int = 1000,
        maxPageSize: Int = 100,
        maxPageWindow: Long = 10_000,
        maxFilterNodes: Int = HttpQueryGuardFilter.DEFAULT_MAX_FILTER_NODES,
        maxFilterValues: Int = 1000,
        allowExpensiveOperators: Boolean = false,
        idleTimeout: Duration = Duration.ofSeconds(10),
    ) = HttpQueryGuardFilter(
        maxListSize = maxListSize,
        maxPageSize = maxPageSize,
        maxPageWindow = maxPageWindow,
        maxFilterNodes = maxFilterNodes,
        maxFilterValues = maxFilterValues,
        allowExpensiveOperators = allowExpensiveOperators,
        idleTimeout = idleTimeout,
    )

    private fun listContext(query: IListQuery): QueryContext<IListQuery, Flux<ObjectNode>> =
        DefaultQueryContext<IListQuery, Flux<ObjectNode>>(
            QueryType.LIST,
            MOCK_AGGREGATE_METADATA,
            RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA,
        ).setQuery(query)

    private fun pagedContext(query: IPagedQuery): QueryContext<IPagedQuery, Mono<PagedList<ObjectNode>>> =
        DefaultQueryContext<IPagedQuery, Mono<PagedList<ObjectNode>>>(
            QueryType.PAGED,
            MOCK_AGGREGATE_METADATA,
            RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA,
        ).setQuery(query)

    private fun cursorContext(query: ICursorQuery): QueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>> =
        DefaultQueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>>(
            QueryType.CURSOR,
            MOCK_AGGREGATE_METADATA,
            RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA,
        ).setQuery(query).setResult(Mono.just(CursorPage(emptyList(), null)))

    private fun HttpQueryGuardFilter.validateForTest(query: ICursorQuery): Mono<Void> =
        filter(cursorContext(query), EmptyFilterChain.instance()).writeRawRequest(request)

    private fun countContext(filter: FilterExpression): QueryContext<FilterExpression, Mono<Long>> =
        DefaultQueryContext<FilterExpression, Mono<Long>>(
            QueryType.COUNT,
            MOCK_AGGREGATE_METADATA,
            RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA,
        ).setQuery(filter)

    private fun aggregationContext(query: AggregationQuery): QueryContext<AggregationQuery, Flux<ObjectNode>> =
        DefaultQueryContext<AggregationQuery, Flux<ObjectNode>>(
            QueryType.AGGREGATION,
            MOCK_AGGREGATE_METADATA,
            RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA,
        ).setQuery(query)

    private fun unexpectedBackend(): FilterChain<QueryContext<*, *>> = FilterChain {
        error("Backend must not be invoked.")
    }

    private fun snapshotQueryGateway(
        guard: HttpQueryGuardFilter = guard(),
        queryBackendFactory: SnapshotQueryBackendFactory = NoOpSnapshotQueryBackendFactory,
        abacQueryFilter: AbacQueryFilter? = null,
    ): SnapshotQueryGateway<Any> {
        val filters = buildList {
            add(guard)
            if (abacQueryFilter != null) add(abacQueryFilter)
        }
        return DefaultSnapshotQueryGateway(
            namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
            backend = queryBackendFactory.create(MOCK_AGGREGATE_METADATA.namedAggregate).backend,
            schemaProvider = RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA_PROVIDER,
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
            targetType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                Any::class.java,
            ),
            filters = filters,
        )
    }

    private fun eventStreamQueryGateway(guard: HttpQueryGuardFilter = guard()): EventStreamQueryGateway {
        return DefaultEventStreamQueryGateway(
            namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
            backend = NoOpEventStreamQueryBackendFactory.create(MOCK_AGGREGATE_METADATA.namedAggregate).backend,
            schemaProvider = RouteTestFixtures.EVENT_STREAM_QUERY_SCHEMA_PROVIDER,
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
            filters = listOf(guard),
        )
    }

    private fun listHandler(queryGateway: SnapshotQueryGateway<Any>) = ListQueryHandlerFunctionFactory(
        handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
        queryGateway = { queryGateway },
        rewriteRequestFilter = DefaultRewriteRequestFilter,
        exceptionHandler = WebFluxRequestExceptionHandler(),
    ).create(
        testAggregateRouteContract(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
        ),
    )

    private fun loadEventStreamHandler(queryGateway: EventStreamQueryGateway) =
        LoadEventStreamHandlerFunctionFactory({ queryGateway }, WebFluxRequestExceptionHandler()).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Event.LOAD,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            ),
        )

    private fun loadSnapshotHandler(queryGateway: SnapshotQueryGateway<Any>) =
        LoadSnapshotHandlerFunctionFactory({ queryGateway }, WebFluxRequestExceptionHandler()).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LOAD,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            ),
        )

    private object TestAbacQueryFilter : AbacQueryFilter() {
        override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*, *>): Mono<AbacTags> =
            mapOf("role" to listOf("reader")).toMono()
    }

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
