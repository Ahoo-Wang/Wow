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

package me.ahoo.wow.webflux.route.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObjectNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunction.Companion.trace
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class AggregateTracingHandlerFunctionTest {

    @Test
    fun `trace should source event streams once and snapshot each historical state`() {
        val eventStreams = cartEventStreams(eventCount = 3)
        val stateAggregateFactory = CountingStateAggregateFactory()

        val tracedStates = CART_AGGREGATE_METADATA.state.trace(
            stateAggregateFactory = stateAggregateFactory,
            eventStreams = eventStreams,
        )

        tracedStates.assert().hasSize(3)
        val firstState = tracedStates[0].state.assertJsonState()
        val secondState = tracedStates[1].state.assertJsonState()
        val thirdState = tracedStates[2].state.assertJsonState()

        firstState.itemProductIds().assert().isEqualTo(listOf("product-1"))
        secondState.itemProductIds().assert().isEqualTo(listOf("product-1", "product-2"))
        thirdState.itemProductIds().assert().isEqualTo(listOf("product-1", "product-2", "product-3"))
        (firstState === thirdState).assert().isFalse()
        stateAggregateFactory.createCount.assert().isEqualTo(1)
    }

    @Test
    fun `trace should serialize json snapshot state with existing response shape`() {
        val tracedStates = CART_AGGREGATE_METADATA.state.trace(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = cartEventStreams(eventCount = 2),
        )

        val serializedState = tracedStates.last().serializedState()

        (serializedState["actual"] == null).assert().isTrue()
        serializedState.itemProductIds().assert().isEqualTo(listOf("product-1", "product-2"))
    }

    @Test
    fun `windowed trace should replay prefix and emit only requested versions`() {
        val eventStreams = cartEventStreams(eventCount = 3)

        CART_AGGREGATE_METADATA.state.trace(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = eventStreams,
            emitHeadVersion = 2,
            tailVersion = 3,
        ).collectList()
            .test()
            .consumeNextWith { tracedStates ->
                tracedStates.assert().hasSize(2)
                tracedStates[0].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2"))
                tracedStates[1].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2", "product-3"))
            }.verifyComplete()
    }

    @Test
    fun `should handle aggregate tracing request`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = generateGlobalId()
        aggregateVerifier<MockCommandAggregate, MockStateAggregate>(eventStore = eventStore)
            .whenCommand(MockCreateAggregate(id = aggregateId, data = "test-data"))
            .expectNoError()
            .expectEventType(MockAggregateCreated::class.java)
            .expectState {
                data.assert().isEqualTo("test-data")
            }
            .verify()
        val handlerFunction = AggregateTracingHandlerFunctionFactory(
            ConstructorStateAggregateFactory,
            eventStore,
            WebFluxRequestExceptionHandler()
        )
            .create(
                AggregateTracingRouteSpec(
                    MOCK_AGGREGATE_METADATA,
                    aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                    componentContext = OpenAPIComponentContext.default()
                )
            )

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.TENANT_ID, TenantId.DEFAULT_TENANT_ID)
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                val body = it.writeToString()
                body.assert().contains("test-data")
            }.verifyComplete()
    }

    @Test
    fun `handler tracing response should remain streaming server response`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = generateGlobalId()
        aggregateVerifier<MockCommandAggregate, MockStateAggregate>(eventStore = eventStore)
            .whenCommand(MockCreateAggregate(id = aggregateId, data = "test-data"))
            .expectNoError()
            .expectEventType(MockAggregateCreated::class.java)
            .verify()
        val handlerFunction = AggregateTracingHandlerFunctionFactory(
            ConstructorStateAggregateFactory,
            eventStore,
            WebFluxRequestExceptionHandler()
        ).create(
            AggregateTracingRouteSpec(
                MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                componentContext = OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.TENANT_ID, TenantId.DEFAULT_TENANT_ID)
            .build()

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it::class.java.name.assert().contains("StreamingJsonArrayResponse")
            }
            .verifyComplete()
    }

    private companion object {
        val CART_AGGREGATE_METADATA = aggregateMetadata<Cart, CartState>()
        val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }

        fun cartEventStreams(eventCount: Int): List<DomainEventStream> {
            val aggregateId = CART_AGGREGATE_METADATA.aggregateId("trace-cart")
            val upstream = GivenInitializationCommand(aggregateId)
            return (1..eventCount).map { version ->
                CartItemAdded(CartItem(productId = "product-$version", quantity = version))
                    .toDomainEventStream(
                        upstream = upstream,
                        aggregateVersion = version - 1,
                    )
            }
        }

        fun Any.assertJsonState(): ObjectNode {
            this.assert().isInstanceOf(ObjectNode::class.java)
            return this as ObjectNode
        }

        fun StateEvent<*>.serializedState(): ObjectNode {
            return toJsonString().toObjectNode()["state"] as ObjectNode
        }

        fun ObjectNode.itemProductIds(): List<String> {
            val items = this["items"]
            return (0 until items.size()).map { index ->
                items[index]["productId"].asString()
            }
        }

        fun ServerResponse.writeToString(): String {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
            writeTo(exchange, SERVER_RESPONSE_CONTEXT)
                .test()
                .verifyComplete()
            return exchange.response.bodyAsString.block()!!
        }
    }

    private class CountingStateAggregateFactory(
        private val delegate: StateAggregateFactory = ConstructorStateAggregateFactory
    ) : StateAggregateFactory {
        var createCount: Int = 0
            private set

        override fun <S : Any> create(
            metadata: StateAggregateMetadata<S>,
            aggregateId: AggregateId
        ): StateAggregate<S> {
            createCount++
            return delegate.create(metadata, aggregateId)
        }
    }
}
