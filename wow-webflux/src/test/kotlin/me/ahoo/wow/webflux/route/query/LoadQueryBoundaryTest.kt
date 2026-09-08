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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackend
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunction
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunction
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class LoadQueryBoundaryTest {
    @Test
    fun eventLoadRejectsRequestedRangeOverDefaultCapBeforeGateway() {
        val invoked = AtomicBoolean()
        val handler = eventHandler(
            Flux.defer {
                invoked.set(true)
                Flux.just(row())
            }
        )
        val exchange = exchange()
        write(handler, request(tail = 1000), exchange).block()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        invoked.get().assert().isFalse()
    }

    @Test
    fun eventLoadBuffersWithinDefaultActualRowCapAndCancelsExcessRows() {
        val cancelled = AtomicBoolean()
        val exchange = exchange()
        write(
            eventHandler(Flux.range(0, 1001).map { row() }.doOnCancel { cancelled.set(true) }),
            request(tail = 999),
            exchange,
        ).block()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.BAD_REQUEST)
        exchange.response.bodyAsString.block()!!.contains("\"row\"").assert().isFalse()
        cancelled.get().assert().isTrue()
    }

    @Test
    fun eventLoadAcceptsExactlyDefaultRowCap() {
        val exchange = exchange()
        write(eventHandler(Flux.range(0, 1000).map { row() }), request(tail = 999), exchange).block()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.OK)
        JsonSerializer.readTree(exchange.response.bodyAsString.block()!!).size().assert().isEqualTo(1000)
    }

    @Test
    fun bothLoadPublishersTimeOutAtDefaultTenSecondsAndCancelUpstream() {
        listOf(false, true).forEach { event ->
            val cancelled = AtomicBoolean()
            val exchange = exchange()
            StepVerifier.withVirtualTime {
                val handler = if (event) {
                    eventHandler(Flux.never<ObjectNode>().doOnCancel { cancelled.set(true) })
                } else {
                    snapshotHandler(Mono.never<ObjectNode>().doOnCancel { cancelled.set(true) })
                }
                write(handler, request(), exchange)
            }.expectSubscription()
                .expectNoEvent(Duration.ofSeconds(9))
                .thenAwait(Duration.ofSeconds(1))
                .expectComplete()
                .verify(Duration.ofSeconds(5))
            exchange.response.statusCode.assert().isEqualTo(HttpStatus.REQUEST_TIMEOUT)
            cancelled.get().assert().isTrue()
        }
    }

    @Test
    fun eventJsonLateFailureDoesNotCommitPartialSuccess() {
        val exchange = exchange()
        write(
            eventHandler(Flux.concat(Flux.just(row()), Flux.error(IllegalStateException("late failure")))),
            request(),
            exchange,
        ).block()
        exchange.response.statusCode!!.isError.assert().isTrue()
        exchange.response.bodyAsString.block()!!.contains("\"row\"").assert().isFalse()
    }

    @Test
    fun bothLoadResponsesPropagateClientCancellation() {
        listOf(false, true).forEach { event ->
            val cancelled = AtomicBoolean()
            val handler = if (event) {
                eventHandler(Flux.never<ObjectNode>().doOnCancel { cancelled.set(true) })
            } else {
                snapshotHandler(Mono.never<ObjectNode>().doOnCancel { cancelled.set(true) })
            }
            StepVerifier.create(write(handler, request(), exchange())).thenCancel().verify()
            cancelled.get().assert().isTrue()
        }
    }

    @Test
    fun snapshotRouteIdentitySurvivesPrepareReplacingFilterAndKeepsActiveDeletion() {
        listOf("trusted-tenant", "").forEach { tenant ->
            var received: FilterExpression? = null
            val backend = object : SnapshotQueryBackend by NoOpSnapshotQueryBackend(
                MOCK_AGGREGATE_METADATA.namedAggregate
            ) {
                override fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode> {
                    received = query.filter
                    return Mono.empty()
                }
            }
            val gateway = DefaultSnapshotQueryGateway<Any>(
                namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
                binding = QueryBackendBinding(backend, RouteTestFixtures.SNAPSHOT_QUERY_SCHEMA_PROVIDER),
                targetType = JsonSerializer.typeFactory.constructParametricType(
                    MaterializedSnapshot::class.java,
                    Any::class.java
                ),
                filters = listOf(object : QueryFilter {
                    override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> =
                        Mono.just(context.query.withFilter(MatchAllFilter))
                }),
            )
            val handler = LoadSnapshotHandlerFunction(
                RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                gateway,
                WebFluxRequestExceptionHandler(),
                queryRequestScope = QueryRequestScope { _, _ -> SpaceIdFilter("trusted-space") },
            )
            val request = MockServerRequest.builder().pathVariable("id", "specific-record")
                .pathVariable("ownerId", "trusted-owner").pathVariable("tenantId", tenant).build()
            val exchange = exchange()
            write(handler, request, exchange).block()
            exchange.response.statusCode.assert().isEqualTo(HttpStatus.NOT_FOUND)
            leaves(requireNotNull(received)).toSet().assert().isEqualTo(
                setOf(
                    TenantIdFilter(tenant.ifEmpty { "(0)" }),
                    OwnerIdFilter("trusted-owner"),
                    IdFilter("specific-record"),
                    SpaceIdFilter("trusted-space"),
                    DeletionFilter(DeletionState.ACTIVE)
                ),
            )
        }
    }

    @Test
    fun eventRouteIdentityAndVersionRangeSurvivePrepareReplacingFilter() {
        var received: FilterExpression? = null
        val backend = object : EventStreamQueryBackend by NoOpEventStreamQueryBackend(
            MOCK_AGGREGATE_METADATA.namedAggregate
        ) {
            override fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode> {
                received = query.filter
                return Flux.empty()
            }
        }
        val gateway = DefaultEventStreamQueryGateway(
            namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
            binding = QueryBackendBinding(backend, RouteTestFixtures.EVENT_STREAM_QUERY_SCHEMA_PROVIDER),
            filters = listOf(object : QueryFilter {
                override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> =
                    Mono.just(context.query.withFilter(MatchAllFilter))
            }),
        )
        val handler = LoadEventStreamHandlerFunction(
            MOCK_AGGREGATE_METADATA,
            gateway,
            WebFluxRequestExceptionHandler(),
            queryRequestScope = QueryRequestScope { _, _ -> SpaceIdFilter("trusted-space") },
        )
        val request = MockServerRequest.builder().pathVariable("id", "specific-record")
            .pathVariable("tenantId", "trusted-tenant").pathVariable("ownerId", "trusted-owner")
            .pathVariable("headVersion", "3").pathVariable("tailVersion", "5").build()
        val exchange = exchange()
        write(handler, request, exchange).block()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.OK)
        leaves(requireNotNull(received)).toSet().assert().isEqualTo(
            setOf(
                TenantIdFilter("trusted-tenant"),
                OwnerIdFilter("trusted-owner"),
                EqualFilter(QueryField("aggregateId"), JsonNodeFactory.instance.stringNode("specific-record")),
                BetweenFilter(
                    QueryField("version"),
                    JsonNodeFactory.instance.numberNode(3),
                    JsonNodeFactory.instance.numberNode(5)
                ),
                SpaceIdFilter("trusted-space"),
            ),
        )
    }

    @Test
    fun eventSseKeepsStreamingRowsAndReportsLateFailure() {
        val failure = IllegalStateException("late failure")
        val exchange = exchange()
        val chunks = mutableListOf<String>()
        exchange.response.setWriteHandler { body ->
            body.doOnNext { buffer ->
                chunks += buffer.toString(StandardCharsets.UTF_8)
                DataBufferUtils.release(buffer)
            }.then()
        }
        StepVerifier.withVirtualTime {
            write(
                eventHandler(
                    Flux.concat(Flux.just(row()), Mono.delay(Duration.ofSeconds(1)).then(Mono.error(failure)))
                ),
                request(sse = true),
                exchange,
            )
        }.then { chunks.joinToString("").contains("data:{\"row\":1}").assert().isTrue() }
            .thenAwait(Duration.ofSeconds(1))
            .expectErrorMatches { it === failure }
            .verify(Duration.ofSeconds(5))
        exchange.response.headers.contentType!!.isCompatibleWith(MediaType.TEXT_EVENT_STREAM).assert().isTrue()
        chunks.joinToString("").contains("late failure").assert().isTrue()
    }

    private fun eventHandler(result: Flux<ObjectNode>): HandlerFunction<ServerResponse> {
        val gateway = mockk<EventStreamQueryGateway> { every { dynamicList(any()) } returns result }
        return LoadEventStreamHandlerFunction(MOCK_AGGREGATE_METADATA, gateway, WebFluxRequestExceptionHandler())
    }

    private fun snapshotHandler(result: Mono<ObjectNode>): HandlerFunction<ServerResponse> {
        val gateway = mockk<SnapshotQueryGateway<Any>> { every { dynamicSingle(any()) } returns result }
        return LoadSnapshotHandlerFunction(
            RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
            gateway,
            WebFluxRequestExceptionHandler()
        )
    }

    private fun request(tail: Int = 1, sse: Boolean = false): ServerRequest = MockServerRequest.builder()
        .pathVariable(
            "id",
            "specific-record"
        ).pathVariable("headVersion", "0").pathVariable("tailVersion", tail.toString())
        .header(
            HttpHeaders.ACCEPT,
            if (sse) MediaType.TEXT_EVENT_STREAM_VALUE else MediaType.APPLICATION_JSON_VALUE
        ).build()

    private fun row(): ObjectNode = JsonNodeFactory.instance.objectNode().put("row", 1)

    private fun exchange() = MockServerWebExchange.from(MockServerHttpRequest.get("/load").build())

    private fun write(handler: HandlerFunction<ServerResponse>, request: ServerRequest, exchange: MockServerWebExchange): Mono<Void> =
        handler.handle(request).flatMap { it.writeTo(exchange, RESPONSE_CONTEXT) }

    private fun leaves(filter: FilterExpression): List<FilterExpression> =
        if (filter is AndFilter) filter.operands.flatMap(::leaves) else listOf(filter)

    private companion object {
        val RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
