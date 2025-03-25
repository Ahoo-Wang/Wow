package me.ahoo.wow.openapi.route

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class AggregateRouteMetadataParserTest {
    @Test
    fun toAggregateRouteMetadata() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        assertThat(aggregateRouteMetadata.owner, equalTo(AggregateRoute.Owner.AGGREGATE_ID))
    }
}
