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

package me.ahoo.wow.benchmark.fixture

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.test.aggregate.GivenInitializationCommand

object BenchmarkEvents {
    fun singleEventStream(
        aggregateId: AggregateId = BenchmarkAggregates.aggregateId(),
        aggregateVersion: Int = 0,
    ): DomainEventStream {
        val event = CartItemAdded(CartItem("productId"))
        return listOf<Any>(event).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = aggregateVersion,
        )
    }

    fun singleBodyEventStream(
        aggregateId: AggregateId = BenchmarkAggregates.aggregateId(),
    ): DomainEventStream {
        val event = CartItemAdded(CartItem("productId"))
        return event.toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
        )
    }

    fun eventStreams(
        aggregateId: AggregateId = BenchmarkAggregates.aggregateId(),
        eventCount: Int,
    ): List<DomainEventStream> {
        return (1..eventCount).map { version ->
            val event = CartItemAdded(CartItem("product-$version", version))
            listOf<Any>(event).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = version - 1,
            )
        }
    }
}
