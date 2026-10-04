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

package me.ahoo.wow.openapi.metadata

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCommandAggregate
import org.junit.jupiter.api.Test

internal class AggregateRouteMetadataParserTest {

    @Test
    fun `should parse aggregate route metadata with default values when no annotation`() {
        val metadata = aggregateRouteMetadata<MockCommandAggregate>()
        metadata.enabled.assert().isTrue()
        metadata.owner.assert().isEqualTo(AggregateRoute.Owner.NEVER)
        metadata.spaced.assert().isFalse()
        metadata.resourceName.assert().isEqualTo("mock_aggregate")
    }

    @Test
    fun `should parse spaced from the annotation`() {
        val metadata = aggregateRouteMetadata<Order>()
        metadata.spaced.assert().isTrue()
        metadata.owner.assert().isEqualTo(AggregateRoute.Owner.ALWAYS)
        metadata.resourceName.assert().isEqualTo("sales-order")
    }

    @Test
    fun `route spaced is the aggregate metadata's own flag`() {
        aggregateRouteMetadata<Order>().spaced.assert().isEqualTo(aggregateMetadata<Order, OrderState>().spaced)
        aggregateRouteMetadata<MockCommandAggregate>().spaced.assert().isEqualTo(MOCK_AGGREGATE_METADATA.spaced)
    }

    @Test
    fun `route owner policy is the aggregate metadata's own policy`() {
        aggregateRouteMetadata<Order>().ownerPolicy.assert().isEqualTo(aggregateMetadata<Order, OrderState>().owner)
        aggregateMetadata<Order, OrderState>().owner.assert().isEqualTo(OwnerPolicy.ALWAYS)
        aggregateRouteMetadata<Cart>().ownerPolicy.assert().isEqualTo(OwnerPolicy.AGGREGATE_ID)
        aggregateMetadata<Cart, CartState>().owner.assert().isEqualTo(OwnerPolicy.AGGREGATE_ID)
        aggregateRouteMetadata<MockCommandAggregate>().ownerPolicy.assert().isEqualTo(OwnerPolicy.NEVER)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `owner policy constructor mirrors the deprecated owner`() {
        val metadata = AggregateRouteMetadata(
            enabled = true,
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            resourceName = "mock_aggregate",
            spaced = false,
            ownerPolicy = OwnerPolicy.AGGREGATE_ID
        )
        metadata.ownerPolicy.assert().isEqualTo(OwnerPolicy.AGGREGATE_ID)
        metadata.owner.assert().isEqualTo(AggregateRoute.Owner.AGGREGATE_ID)
        metadata.copy(owner = AggregateRoute.Owner.ALWAYS).ownerPolicy.assert().isEqualTo(OwnerPolicy.ALWAYS)
    }
}
