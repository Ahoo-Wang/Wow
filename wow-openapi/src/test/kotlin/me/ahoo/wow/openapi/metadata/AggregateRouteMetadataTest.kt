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
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import org.junit.jupiter.api.Test

class AggregateRouteMetadataTest {
    @Test
    fun equalTo() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        aggregateRouteMetadata.assert().isEqualTo(aggregateRouteMetadata)
    }

    @Test
    fun equalToAny() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        aggregateRouteMetadata.assert().isNotEqualTo(Any())
    }

    @Test
    fun equalToOther() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        val other = aggregateRouteMetadata<Order>()
        aggregateRouteMetadata.assert().isNotEqualTo(other)
    }

    @Test
    fun testHashCode() {
        val aggregateRouteMetadata = aggregateRouteMetadata<Cart>()
        aggregateRouteMetadata.hashCode().assert().isEqualTo(aggregateRouteMetadata.aggregateMetadata.hashCode())
    }
}
