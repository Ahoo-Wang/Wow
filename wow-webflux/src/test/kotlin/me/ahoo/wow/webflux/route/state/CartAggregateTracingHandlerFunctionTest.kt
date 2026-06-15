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
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.policy.TracingPolicy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.kotlin.test.test

class CartAggregateTracingHandlerFunctionTest {

    companion object {
        val CART_AGGREGATE_METADATA = aggregateMetadata<Cart, CartState>()
        val CART_AGGREGATE_ROUTE_METADATA = Cart::class.java.aggregateRouteMetadata()
        val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }

    @Test
    fun `should trace cart aggregate events after AddCartItem`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = generateGlobalId()
        aggregateVerifier<Cart, CartState>(aggregateId, eventStore = eventStore)
            .givenOwnerId(aggregateId)
            .whenCommand(AddCartItem(productId = "product-1", quantity = 3))
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .verify()

        val handlerFunction = AggregateTracingHandlerFunctionFactory(
            ConstructorStateAggregateFactory,
            eventStore,
            WebFluxRequestExceptionHandler(),
            TracingPolicy(),
        ).create(
            AggregateTracingRouteSpec(
                CART_AGGREGATE_METADATA,
                aggregateRouteMetadata = CART_AGGREGATE_ROUTE_METADATA,
                componentContext = OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.TENANT_ID, me.ahoo.wow.api.modeling.TenantId.DEFAULT_TENANT_ID)
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())
                it.writeTo(exchange, SERVER_RESPONSE_CONTEXT)
                    .test()
                    .verifyComplete()
                exchange.response.bodyAsString.block()!!.assert().contains("product-1")
            }.verifyComplete()
    }

}
