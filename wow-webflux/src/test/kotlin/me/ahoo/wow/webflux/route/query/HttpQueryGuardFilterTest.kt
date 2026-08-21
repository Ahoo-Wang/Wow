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
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter
import me.ahoo.wow.query.snapshot.filter.DefaultSnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class HttpQueryGuardFilterTest {
    private val request = mockk<ServerRequest>()

    @Test
    fun `should reject unsafe list queries before backend invocation`() {
        listOf(
            ListQuery(Condition.ALL),
            ListQuery(Condition.raw(mapOf("script" to "unsafe")), limit = 1),
            ListQuery(Condition.contains("state.name", "wow"), limit = 1),
            ListQuery(Condition.endsWith("state.name", "wow"), limit = 1),
            ListQuery(Condition.and(List(65) { Condition.eq("state.value$it", it) }), limit = 1),
        ).forEach { query ->
            guard().filter(listContext(query), unexpectedBackend())
                .writeRawRequest(request)
                .test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
        }
    }

    @Test
    fun `should enforce page size and window without integer overflow`() {
        listOf(
            Pagination(index = 0, size = 1),
            Pagination(index = 1, size = 101),
            Pagination(index = 101, size = 100),
            Pagination(index = Int.MAX_VALUE, size = 100),
        ).forEach { pagination ->
            guard().filter(pagedContext(PagedQuery(Condition.ALL, pagination = pagination)), unexpectedBackend())
                .writeRawRequest(request)
                .test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
        }
    }

    @Test
    fun `should keep trusted non-http query behavior`() {
        val context = listContext(ListQuery(Condition.raw("{}")))
        guard().filter(
            context,
            FilterChain {
                it.asListQuery<Any>().setResult(Flux.empty())
                Mono.empty()
            },
        ).test().verifyComplete()

        context.getRequiredResult().test().verifyComplete()
    }

    @Test
    fun `should allow explicitly enabled legacy http behavior`() {
        val context = listContext(ListQuery(Condition.raw("{}")))
        guard(
            maxListSize = 0,
            maxConditionNodes = 0,
            allowRaw = true,
            allowExpensiveOperators = true,
            idleTimeout = Duration.ZERO,
        ).filter(
            context,
            FilterChain {
                it.asListQuery<Any>().setResult(Flux.empty())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test().verifyComplete()
    }

    @Test
    fun `should apply idle timeout after backend result is installed`() {
        val context = pagedContext(PagedQuery(Condition.ALL))
        guard(idleTimeout = Duration.ofMillis(10)).filter(
            context,
            FilterChain {
                it.asPagedQuery<Any>().setResult(Mono.never())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test()
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun `should run before concrete abac filters in the real snapshot chain`() {
        val handler = snapshotQueryHandler(
            guard = guard(maxConditionNodes = 1),
            abacQueryFilter = TestAbacQueryFilter,
        )

        handler.dynamicList(
            MOCK_AGGREGATE_METADATA,
            ListQuery(Condition.eq("state.status", "ACTIVE"), limit = 1),
        ).writeRawRequest(request).test().verifyComplete()
    }

    @Test
    fun `built-in http route should map default unlimited list to bad request`() {
        val response = listHandler(snapshotQueryHandler()).handle(
            MockServerRequest.builder().body(ListQuery(Condition.ALL).toMono()),
        ).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `built-in http route should map idle timeout and cancel backend`() {
        val cancelled = AtomicBoolean()
        val service = mockk<SnapshotQueryService<Any>> {
            io.mockk.every { dynamicList(any()) } returns Flux.never<DynamicDocument>().doOnCancel {
                cancelled.set(true)
            }
        }
        val factory = mockk<SnapshotQueryServiceFactory> {
            io.mockk.every { create<Any>(any()) } returns service
        }
        val response = listHandler(
            snapshotQueryHandler(
                guard = guard(idleTimeout = Duration.ofMillis(10)),
                queryServiceFactory = factory,
            ),
        ).handle(
            MockServerRequest.builder().body(ListQuery(Condition.ALL, limit = 1).toMono()),
        ).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.REQUEST_TIMEOUT)
        cancelled.get().assert().isTrue()
    }

    private fun guard(
        maxListSize: Int = 1000,
        maxConditionNodes: Int = 64,
        allowRaw: Boolean = false,
        allowExpensiveOperators: Boolean = false,
        idleTimeout: Duration = Duration.ofSeconds(10),
    ) = HttpQueryGuardFilter(
        maxListSize = maxListSize,
        maxConditionNodes = maxConditionNodes,
        allowRaw = allowRaw,
        allowExpensiveOperators = allowExpensiveOperators,
        idleTimeout = idleTimeout,
    )

    private fun listContext(query: IListQuery): QueryContext<IListQuery, Flux<Any>> =
        DefaultQueryContext<IListQuery, Flux<Any>>(
            QueryType.LIST,
            MOCK_AGGREGATE_METADATA,
        ).setQuery(query)

    private fun pagedContext(query: IPagedQuery): QueryContext<IPagedQuery, Mono<PagedList<Any>>> =
        DefaultQueryContext<IPagedQuery, Mono<PagedList<Any>>>(
            QueryType.PAGED,
            MOCK_AGGREGATE_METADATA,
        ).setQuery(query)

    private fun unexpectedBackend(): FilterChain<QueryContext<*, *>> = FilterChain {
        error("Backend must not be invoked.")
    }

    private fun snapshotQueryHandler(
        guard: HttpQueryGuardFilter = guard(),
        queryServiceFactory: SnapshotQueryServiceFactory = NoOpSnapshotQueryServiceFactory,
        abacQueryFilter: AbacQueryFilter? = null,
    ): SnapshotQueryHandler {
        val filters = buildList {
            add(guard)
            if (abacQueryFilter != null) add(abacQueryFilter)
            add(TailSnapshotQueryFilter<Any>(queryServiceFactory))
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(filters)
            .filterCondition(SnapshotQueryHandler::class)
            .build()
        return DefaultSnapshotQueryHandler(chain, LogErrorHandler())
    }

    private fun listHandler(queryHandler: SnapshotQueryHandler) = ListQueryHandlerFunctionFactory(
        handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
        queryHandler = queryHandler,
        rewriteRequestCondition = DefaultRewriteRequestCondition,
        exceptionHandler = WebFluxRequestExceptionHandler(),
    ).create(
        testAggregateRouteContract(
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
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
