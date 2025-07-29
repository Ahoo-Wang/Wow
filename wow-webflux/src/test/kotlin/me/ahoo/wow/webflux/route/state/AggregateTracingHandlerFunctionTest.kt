package me.ahoo.wow.webflux.route.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
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
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
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
                items.assert().hasSize(1)
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
                    aggregateMetadata<Cart, CartState>().command.aggregateType.aggregateRouteMetadata(),
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
}
