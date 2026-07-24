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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.runtime.AdaptiveMapFirstSourcingFunctionRegistry
import me.ahoo.wow.benchmark.runtime.AdaptiveSourcingFunctionRegistry
import me.ahoo.wow.benchmark.runtime.EagerSourcingFunctionRegistry
import me.ahoo.wow.benchmark.runtime.SourcingFunctionRegistryVariant
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartItemRemoved
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import me.ahoo.wow.example.domain.cart.CartState
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

private enum class SourcingRegistryPolicy {
    EAGER,
    ADAPTIVE,
}

private enum class SourcingFunctionCachePolicy {
    MAP_FIRST,
    SINGLE_ENTRY,
}

private fun sourcingRegistryFactory(
    sourcingRegistryPolicy: String,
    functionCachePolicy: String
): (CartState) -> SourcingFunctionRegistryVariant<CartState> {
    return when (SourcingRegistryPolicy.valueOf(sourcingRegistryPolicy)) {
        SourcingRegistryPolicy.EAGER -> {
            { state ->
                EagerSourcingFunctionRegistry(
                    metadata = BenchmarkAggregates.cartMetadata.state,
                    state = state,
                )
            }
        }

        SourcingRegistryPolicy.ADAPTIVE -> {
            when (SourcingFunctionCachePolicy.valueOf(functionCachePolicy)) {
                SourcingFunctionCachePolicy.MAP_FIRST -> {
                    { state ->
                        AdaptiveMapFirstSourcingFunctionRegistry(
                            metadata = BenchmarkAggregates.cartMetadata.state,
                            state = state,
                        )
                    }
                }

                SourcingFunctionCachePolicy.SINGLE_ENTRY -> {
                    { state ->
                        AdaptiveSourcingFunctionRegistry(
                            metadata = BenchmarkAggregates.cartMetadata.state,
                            state = state,
                        )
                    }
                }
            }
        }
    }
}

@State(Scope.Thread)
open class AdaptiveSourcingRegistryConstructionComponentBenchmark {
    @Param("EAGER", "ADAPTIVE")
    lateinit var sourcingRegistryPolicy: String

    @Param("MAP_FIRST", "SINGLE_ENTRY")
    lateinit var functionCachePolicy: String

    private lateinit var registryFactory: (CartState) -> SourcingFunctionRegistryVariant<CartState>
    private lateinit var singleTypeRegistry: SourcingFunctionRegistryVariant<CartState>
    private var sequence: Int = 0

    @Setup
    fun setup() {
        registryFactory = sourcingRegistryFactory(sourcingRegistryPolicy, functionCachePolicy)
        singleTypeRegistry = registryFactory(CartState(BenchmarkAggregates.FIXED_AGGREGATE_ID))
        check(singleTypeRegistry[CartItemAdded::class.java] != null)
    }

    @Benchmark
    fun createAndResolveSingle(): Any {
        val registry = registryFactory(CartState(BenchmarkAggregates.FIXED_AGGREGATE_ID))
        return checkNotNull(registry[CartItemAdded::class.java])
    }

    @Benchmark
    fun createAndResolveAll(): Int {
        val registry = registryFactory(CartState(BenchmarkAggregates.FIXED_AGGREGATE_ID))
        checkNotNull(registry[CartItemAdded::class.java])
        checkNotNull(registry[CartQuantityChanged::class.java])
        checkNotNull(registry[CartItemRemoved::class.java])
        return registry.cachedFunctionCount
    }

    @Benchmark
    fun singleTypeHit(): Any {
        sequence++
        return checkNotNull(singleTypeRegistry[CartItemAdded::class.java])
    }
}

private enum class SourcingEventPattern {
    SINGLE_TYPE,
    MIXED_TWO_TYPES,
}

@State(Scope.Thread)
open class AdaptiveSourcingRegistryReplayComponentBenchmark {
    @Param("EAGER", "ADAPTIVE")
    lateinit var sourcingRegistryPolicy: String

    @Param("MAP_FIRST", "SINGLE_ENTRY")
    lateinit var functionCachePolicy: String

    @Param("SINGLE_TYPE", "MIXED_TWO_TYPES")
    lateinit var eventPattern: String

    @Param("10", "500")
    var eventCount: Int = 10

    private lateinit var registryFactory: (CartState) -> SourcingFunctionRegistryVariant<CartState>
    private lateinit var events: List<DomainEvent<*>>

    @Setup
    fun setup() {
        registryFactory = sourcingRegistryFactory(sourcingRegistryPolicy, functionCachePolicy)
        val aggregateId = BenchmarkAggregates.aggregateId()
        val mixedEvents = BenchmarkEvents.constantSizeEventStreams(aggregateId, eventCount)
            .flatMap { it.body }
        events = when (SourcingEventPattern.valueOf(eventPattern)) {
            SourcingEventPattern.SINGLE_TYPE -> {
                val changed = checkNotNull(
                    mixedEvents.firstOrNull { it.body is CartQuantityChanged }
                )
                List(eventCount) { changed }
            }

            SourcingEventPattern.MIXED_TWO_TYPES -> mixedEvents
        }
        check(events.size == eventCount)
    }

    @Benchmark
    fun replayConstantSizeEvents(blackhole: Blackhole) {
        val state = CartState(BenchmarkAggregates.FIXED_AGGREGATE_ID)
        val registry = registryFactory(state)
        for (event in events) {
            registry[event.body.javaClass]?.invoke(SimpleDomainEventExchange(event))
        }
        blackhole.consume(state)
    }
}
