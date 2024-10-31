package me.ahoo.wow.webflux.route.state

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.state.AggregateTracingRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.test.aggregate.`when`
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test

class AggregateTracingHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val addCartItem = AddCartItem(
            id = generateGlobalId(),
            productId = "productId",
            quantity = 1,
        )
        aggregateVerifier<Cart, CartState>(eventStore = eventStore)
            .`when`(addCartItem)
            .expectNoError()
            .expectEventType(CartItemAdded::class.java)
            .expectState {
                assertThat(it.items, hasSize(1))
            }
            .verify()
        val handlerFunction = AggregateTracingHandlerFunctionFactory(eventStore, DefaultRequestExceptionHandler)
            .create(
                AggregateTracingRouteSpec(
                    aggregateMetadata<Cart, CartState>(),
                    aggregateMetadata<Cart, CartState>()
                )
            )

        val request = mockk<ServerRequest> {
            every { pathVariable(RoutePaths.ID_KEY) } returns addCartItem.id
            every { pathVariables()[MessageRecords.TENANT_ID] } returns TenantId.DEFAULT_TENANT_ID
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.OK))
            }.verifyComplete()
    }
}
