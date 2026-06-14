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
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.state.AggregateTracingHandlerFunction.Companion.trace
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

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
        tracedStates[0].state.items.map { it.productId }.assert().isEqualTo(listOf("product-1"))
        tracedStates[1].state.items.map { it.productId }.assert().isEqualTo(listOf("product-1", "product-2"))
        tracedStates[2].state.items.map { it.productId }.assert()
            .isEqualTo(listOf("product-1", "product-2", "product-3"))
        (tracedStates[0].state === tracedStates[2].state).assert().isFalse()
        stateAggregateFactory.createCount.assert().isEqualTo(1)
    }

    @Test
    fun `should handle aggregate tracing request`() {
        val eventStore = InMemoryEventStore()
        aggregateVerifier<MockCommandAggregate, MockStateAggregate>(eventStore = eventStore)
            .whenCommand(MockCreateAggregate(id = generateGlobalId(), data = "test-data"))
            .expectNoError()
            .expectEventType(MockAggregateCreated::class.java)
            .expectState {
                data.assert().isEqualTo("test-data")
            }
            .verify()
        val handlerFunction = AggregateTracingHandlerFunctionFactory(
            ConstructorStateAggregateFactory,
            eventStore,
            DefaultRequestExceptionHandler
        )
            .create(
                AggregateTracingRouteSpec(
                    MOCK_AGGREGATE_METADATA,
                    aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                    componentContext = OpenAPIComponentContext.default()
                )
            )

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .pathVariable(MessageRecords.TENANT_ID, TenantId.DEFAULT_TENANT_ID)
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }

    private companion object {
        val CART_AGGREGATE_METADATA = aggregateMetadata<Cart, CartState>()

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
