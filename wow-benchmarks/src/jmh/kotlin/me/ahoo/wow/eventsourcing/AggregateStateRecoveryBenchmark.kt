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

package me.ahoo.wow.eventsourcing

import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
open class AggregateStateRecoveryBenchmark {
    @Param("10", "50", "100", "500")
    var eventCount: Int = 10

    private lateinit var eventStreams: List<DomainEventStream>
    private lateinit var aggregateId: me.ahoo.wow.api.modeling.AggregateId

    @Setup
    fun setup() {
        aggregateId = cartAggregateMetadata.aggregateId()
        eventStreams = (1..eventCount).map { index ->
            val event = CartItemAdded(CartItem("product-$index", index))
            listOf<Any>(event).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
            )
        }
    }

    @Benchmark
    fun recoverFromEvents(blackhole: Blackhole) {
        val aggregate = ConstructorStateAggregateFactory.create(
            cartAggregateMetadata.state,
            aggregateId,
        )
        for (eventStream in eventStreams) {
            aggregate.onSourcing(eventStream)
        }
        blackhole.consume(aggregate)
    }
}
