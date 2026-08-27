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
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test
import java.net.URI

class CartStateHandlerFunctionTest {

    companion object {
        val CART_AGGREGATE_METADATA = aggregateMetadata<Cart, CartState>()
        val CART_AGGREGATE_ROUTE_METADATA = Cart::class.java.aggregateRouteMetadata()
    }

    @Test
    fun `should load cart state with items after AddCartItem command`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = generateGlobalId()
        aggregateVerifier<Cart, CartState>(aggregateId, eventStore = eventStore)
            .givenOwnerId(aggregateId)
            .whenCommand(AddCartItem(productId = "product-1", quantity = 3))
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                items.assert().hasSize(1)
                items[0].productId.assert().isEqualTo("product-1")
                items[0].quantity.assert().isEqualTo(3)
            }
            .verify()

        val handlerFunction = LoadAggregateHandlerFunctionFactory(
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotStore = NoOpSnapshotStore,
                eventStore = eventStore,
            ),
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE,
                aggregateRouteMetadata = CART_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("http://localhost"))
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.OWNER_ID, aggregateId)
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `should return not found when loading non-existent cart`() {
        val handlerFunction = LoadAggregateHandlerFunctionFactory(
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotStore = NoOpSnapshotStore,
                eventStore = InMemoryEventStore(),
            ),
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE,
                aggregateRouteMetadata = CART_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("http://localhost"))
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.NOT_FOUND)
            }.verifyComplete()
    }
}
