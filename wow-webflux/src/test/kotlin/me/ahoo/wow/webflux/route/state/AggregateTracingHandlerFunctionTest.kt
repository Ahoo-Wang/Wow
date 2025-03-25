package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.aggregate.state.AggregateTracingRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class AggregateTracingHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val addCartItem = AddCartItem(
            productId = "productId",
            quantity = 1,
        )
        aggregateVerifier<Cart, CartState>(eventStore = eventStore)
            .whenCommand(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                assertThat(it.items, hasSize(1))
            }
            .verify()
        val handlerFunction = AggregateTracingHandlerFunctionFactory(
            ConstructorStateAggregateFactory,
            eventStore,
            DefaultRequestExceptionHandler
        )
            .create(
                AggregateTracingRouteSpec(
                    aggregateMetadata<Cart, CartState>(),
                    aggregateMetadata<Cart, CartState>().command.aggregateType.aggregateRouteMetadata()
                )
            )

        val request = MockServerRequest.builder()
            .pathVariable(RoutePaths.ID_KEY, generateGlobalId())
            .pathVariable(MessageRecords.TENANT_ID, TenantId.DEFAULT_TENANT_ID)
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
