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

package me.ahoo.wow.webflux.route.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackend
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

class EventStreamSchemaHandlerFunctionTest {
    @Test
    fun `get should return event stream schema`() {
        val provider = RecordingSchemaProvider()
        val factory = RecordingEventStreamQueryBackendFactory(provider)
        val handler = EventStreamSchemaHandlerFunctionFactory(
            eventStreamQueryBackendFactory = factory,
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Event.SCHEMA))

        val model = client(handler).get().uri("/").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .toJsonNode<tools.jackson.databind.JsonNode>()["model"]
            .stringValue()

        model.assert().isEqualTo("EVENT_STREAM")
        factory.namedAggregate.assert()
            .isSameAs(RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA.aggregateMetadata)
        provider.schemaCalls.get().assert().isOne()
        provider.refreshCalls.get().assert().isZero()
    }

    @Test
    fun `unavailable provider should return unavailable error`() {
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val factory = RecordingEventStreamQueryBackendFactory(
            UnavailableSchemaProvider,
        )
        val schemaHandler = EventStreamSchemaHandlerFunctionFactory(
            eventStreamQueryBackendFactory = factory,
            exceptionHandler = exceptionHandler,
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Event.SCHEMA))
        val refreshHandler = EventStreamSchemaRefreshHandlerFunctionFactory(
            eventStreamQueryBackendFactory = factory,
            exceptionHandler = exceptionHandler,
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Event.SCHEMA_REFRESH))

        client(schemaHandler).get().uri("/").exchange()
            .expectStatus().isEqualTo(503)
        client(refreshHandler).post().uri("/").exchange()
            .expectStatus().isEqualTo(503)
    }

    @Test
    fun `refresh should refresh event stream schema`() {
        val provider = RecordingSchemaProvider()
        val handler = EventStreamSchemaRefreshHandlerFunctionFactory(
            eventStreamQueryBackendFactory = RecordingEventStreamQueryBackendFactory(provider),
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(
            testAggregateRouteContract(
                BuiltInHttpRouteHandlerKeys.Event.SCHEMA_REFRESH,
            )
        )

        client(handler).post().uri("/").exchange()
            .expectStatus().isOk

        provider.schemaCalls.get().assert().isZero()
        provider.refreshCalls.get().assert().isOne()
    }

    private fun client(handler: HandlerFunction<*>) = WebTestClient.bindToRouterFunction(
        RouterFunctions.route()
            .GET("/") { request -> handler.handle(request) }
            .POST("/") { request -> handler.handle(request) }
            .build()
    ).build()

    private class RecordingEventStreamQueryBackendFactory(
        private val schemaProvider: QueryModelSchemaProvider,
    ) : EventStreamQueryBackendFactory {
        lateinit var namedAggregate: NamedAggregate

        override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<EventStreamQueryBackend> {
            this.namedAggregate = namedAggregate
            return QueryBackendBinding(NoOpEventStreamQueryBackend(namedAggregate), schemaProvider)
        }
    }

    private class RecordingSchemaProvider : QueryModelSchemaProvider {
        val schemaCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()

        override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier {
            schemaCalls.incrementAndGet()
            SCHEMA
        }

        override fun refresh(): Mono<QueryModelSchema> = Mono.fromSupplier {
            refreshCalls.incrementAndGet()
            SCHEMA
        }
    }

    private object UnavailableSchemaProvider : QueryModelSchemaProvider {
        override fun schema(): Mono<QueryModelSchema> = Mono.error(
            QuerySchemaUnavailableException("Schema unavailable."),
        )

        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    private companion object {
        val SCHEMA = QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap())
    }
}
