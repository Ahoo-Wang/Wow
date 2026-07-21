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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AggregateEventStoreRegistry
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.RoutingEventStore
import me.ahoo.wow.eventsourcing.snapshot.AggregateSnapshotStoreRegistry
import me.ahoo.wow.eventsourcing.snapshot.RoutingSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import io.micrometer.core.instrument.Metrics as MicrometerMetrics

class MetricStorageOwnershipTest {

    private val aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1")

    @Test
    fun `routing event store should preserve leaf metric ownership`() {
        withMeterRegistry { meterRegistry ->
            val leafStore = LeafEventStore().metrizable()
            val routingStore = RoutingEventStore(
                AggregateEventStoreRegistry(
                    defaultEventStore = leafStore,
                    routes = emptyMap(),
                ),
            )

            val metricStore = routingStore.metrizable()
            metricStore.assert().isSameAs(routingStore)
            metricStore.load(aggregateId).blockLast()

            meterRegistry.sources("wow.eventstore.load")
                .assert().containsExactly(LeafEventStore::class.java.simpleName)
        }
    }

    @Test
    fun `routing snapshot store should preserve leaf metric ownership`() {
        withMeterRegistry { meterRegistry ->
            val leafStore = LeafSnapshotStore().metrizable()
            val routingStore = RoutingSnapshotStore.create(
                AggregateSnapshotStoreRegistry(
                    defaultSnapshotStore = leafStore,
                    routes = emptyMap(),
                ),
            )

            val metricStore = routingStore.metrizable()
            metricStore.assert().isSameAs(routingStore)
            metricStore.getVersion(aggregateId).block()

            meterRegistry.sources("wow.snapshot.getVersion")
                .assert().containsExactly(LeafSnapshotStore::class.java.simpleName)
        }
    }

    private fun withMeterRegistry(block: (SimpleMeterRegistry) -> Unit) {
        val meterRegistry = SimpleMeterRegistry()
        MicrometerMetrics.addRegistry(meterRegistry)
        try {
            block(meterRegistry)
        } finally {
            MicrometerMetrics.removeRegistry(meterRegistry)
            meterRegistry.close()
        }
    }

    private fun SimpleMeterRegistry.sources(metricName: String): Set<String> =
        meters
            .map { it.id }
            .filter { it.name.startsWith(metricName) }
            .mapNotNull { it.getTag(Metrics.SOURCE_KEY) }
            .toSet()

    private class LeafEventStore : EventStore {
        override fun append(eventStream: DomainEventStream): Mono<Void> = Mono.empty()

        override fun load(
            aggregateId: AggregateId,
            headVersion: Int,
            tailVersion: Int,
        ): Flux<DomainEventStream> = Flux.empty()

        override fun load(
            aggregateId: AggregateId,
            headEventTime: Long,
            tailEventTime: Long,
        ): Flux<DomainEventStream> = Flux.empty()

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = Mono.empty()
    }

    private class LeafSnapshotStore : SnapshotStore {
        override val name: String = "leaf"

        override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> = Mono.empty()

        override fun getVersion(aggregateId: AggregateId): Mono<Int> = Mono.just(1)

        override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> = Mono.empty()
    }
}
