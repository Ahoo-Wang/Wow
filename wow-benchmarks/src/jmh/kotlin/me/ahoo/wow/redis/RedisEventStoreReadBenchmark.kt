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

package me.ahoo.wow.redis

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.redis.eventsourcing.RedisEventStore
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole

@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(value = 2)
@Threads(5)
@State(Scope.Benchmark)
open class RedisEventStoreReadBenchmark {
    @Param("10", "100")
    var eventCount: Int = 10

    private lateinit var redis: RedisBenchmarkFixture
    private lateinit var eventStore: EventStore
    private lateinit var aggregateId: AggregateId

    @Setup
    fun setup() {
        redis = RedisBenchmarkFixture()
        eventStore = RedisEventStore(redis.redisTemplate)
        aggregateId = cartAggregateMetadata.aggregateId()
        for (eventStream in createEventStreams()) {
            eventStore.append(eventStream).block()
        }
    }

    @TearDown
    fun tearDown() {
        redis.close()
    }

    private fun createEventStreams(): List<DomainEventStream> {
        return (1..eventCount).map { version ->
            val event = CartItemAdded(CartItem("product-$version", version))
            listOf<Any>(event).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = version - 1,
            )
        }
    }

    @Benchmark
    fun loadAll(blackHole: Blackhole) {
        val eventStreams = eventStore.load(aggregateId, 1, eventCount).collectList().block()
        blackHole.consume(eventStreams)
    }

    @Benchmark
    fun single(blackHole: Blackhole) {
        val eventStream = eventStore.single(aggregateId, eventCount).block()
        blackHole.consume(eventStream)
    }

    @Benchmark
    fun last(blackHole: Blackhole) {
        val eventStream = eventStore.last(aggregateId).block()
        blackHole.consume(eventStream)
    }
}
