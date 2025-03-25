package me.ahoo.wow.openapi.route

import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class AggregateRouteMetadataTest {
    @Test
    fun equalTo() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        assertThat(aggregateRouteMetadata, equalTo(aggregateRouteMetadata))
    }

    @Test
    fun equalToAny() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        assertThat(aggregateRouteMetadata, not(Any()))
    }

    @Test
    fun equalToOther() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        val other = aggregateRouteMetadata<Order>()
        assertThat(aggregateRouteMetadata, not(other))
    }
}
