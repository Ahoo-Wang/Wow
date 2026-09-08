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

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.queryScope
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.event.EventStreamAggregationHandlerFunction
import me.ahoo.wow.webflux.route.snapshot.SnapshotAggregationHandlerFunction
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import tools.jackson.databind.node.JsonNodeFactory

class QueryRequestScopeHandlerTest {

    @Test
    @Suppress("LongMethod") // One shared fixture verifies every query route against the same scope.
    fun wiresIndependentScopeAcrossAllSevenQueryRoutes() {
        val scope = TenantIdFilter("tenant-id")
        val scopes = mutableListOf<FilterExpression>()
        val requestScope = QueryRequestScope { _, _ -> scope }
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val guard = HttpQueryGuard(idleTimeout = java.time.Duration.ZERO)
        val row = JsonNodeFactory.instance.objectNode().put("value", "ok")

        val singleSlot = slot<ISingleQuery>()
        val listSlot = slot<IListQuery>()
        val pagedSlot = slot<IPagedQuery>()
        val cursorSlot = slot<ICursorQuery>()
        val countSlot = slot<FilterExpression>()
        val snapshotAggregationSlot = slot<AggregationQuery>()
        val eventAggregationSlot = slot<AggregationQuery>()
        val snapshotGateway = mockk<SnapshotQueryGateway<Any>> {
            every { dynamicSingle(capture(singleSlot)) } returns Mono.deferContextual {
                scopes += it.queryScope()
                Mono.just(row)
            }
            every { dynamicList(capture(listSlot)) } returns Flux.deferContextual {
                scopes += it.queryScope()
                Flux.just(row)
            }
            every { dynamicPaged(capture(pagedSlot)) } returns Mono.deferContextual {
                scopes += it.queryScope()
                Mono.just(PagedList(1, listOf(row)))
            }
            every { dynamicCursor(capture(cursorSlot)) } returns Mono.deferContextual {
                scopes += it.queryScope()
                Mono.just(CursorPage(listOf(row), null))
            }
            every { count(capture(countSlot)) } returns Mono.deferContextual {
                scopes += it.queryScope()
                Mono.just(1)
            }
            every { aggregate(capture(snapshotAggregationSlot)) } returns Flux.deferContextual {
                scopes += it.queryScope()
                Flux.just(row)
            }
        }
        val eventGateway = mockk<EventStreamQueryGateway> {
            every { aggregate(capture(eventAggregationSlot)) } returns Flux.deferContextual {
                scopes += it.queryScope()
                Flux.just(row)
            }
        }

        val filter = IdFilter("user-id")
        val single = SingleQuery(filter)
        val list = ListQuery(filter, limit = 1)
        val paged = PagedQuery(filter)
        val cursor = CursorQuery(filter)
        val snapshotAggregation = AggregationQuery(
            filter = filter,
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 1,
        )
        val eventAggregation = snapshotAggregation.copy()

        execute(
            SingleQueryHandlerFunction(
                MOCK_AGGREGATE_METADATA,
                snapshotGateway,
                requestScope,
                exceptionHandler,
                guard,
                { it },
            ),
            single,
        )
        execute(
            ListQueryHandlerFunction(
                MOCK_AGGREGATE_METADATA,
                snapshotGateway,
                requestScope,
                exceptionHandler,
                guard,
                { it },
            ),
            list,
        )
        execute(
            PagedQueryHandlerFunction(
                MOCK_AGGREGATE_METADATA,
                snapshotGateway,
                requestScope,
                exceptionHandler,
                guard,
                { it },
            ),
            paged,
        )
        execute(
            CursorQueryHandlerFunction(
                MOCK_AGGREGATE_METADATA,
                snapshotGateway,
                requestScope,
                exceptionHandler,
                guard,
            ),
            cursor,
        )
        execute(
            CountQueryHandlerFunction(
                MOCK_AGGREGATE_METADATA,
                snapshotGateway,
                requestScope,
                exceptionHandler,
                guard,
            ),
            filter,
        )
        execute(
            SnapshotAggregationHandlerFunction(
                MOCK_AGGREGATE_METADATA,
                snapshotGateway,
                requestScope,
                exceptionHandler,
                guard,
            ),
            snapshotAggregation,
        )
        execute(
            EventStreamAggregationHandlerFunction(
                MOCK_AGGREGATE_METADATA,
                eventGateway,
                requestScope,
                exceptionHandler,
                guard,
            ),
            eventAggregation,
        )

        singleSlot.captured.assert().isSameAs(single)
        listSlot.captured.assert().isSameAs(list)
        pagedSlot.captured.assert().isSameAs(paged)
        cursorSlot.captured.assert().isSameAs(cursor)
        countSlot.captured.assert().isSameAs(filter)
        snapshotAggregationSlot.captured.assert().isSameAs(snapshotAggregation)
        eventAggregationSlot.captured.assert().isSameAs(eventAggregation)
        scopes.assert().hasSize(7)
        scopes.forEach { it.assert().isEqualTo(scope) }
    }

    private fun execute(handler: HandlerFunction<ServerResponse>, body: Any) {
        val response = handler.handle(MockServerRequest.builder().body(body.toMono())).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.OK)
    }

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
