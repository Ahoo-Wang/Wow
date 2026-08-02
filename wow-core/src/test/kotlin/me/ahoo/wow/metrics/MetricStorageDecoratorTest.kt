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
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MetricStorageDecoratorTest {
    private val aggregate = MaterializedNamedAggregate("sales", "Order")
    private val aggregateId = aggregate.aggregateId("order-1")

    @Test
    fun `event store should delegate every operation and expose semantic names`() {
        val registry = SimpleMeterRegistry()
        val stream = mockk<DomainEventStream> {
            every { contextName } returns aggregate.contextName
            every { aggregateName } returns aggregate.aggregateName
        }
        val delegate = mockk<EventStore> {
            every { append(stream) } returns Mono.empty()
            every { load(aggregateId, 1, 10) } returns Flux.just(stream)
            every { load(aggregateId, 100L, 200L) } returns Flux.just(stream)
            every { existsRequestId(aggregateId, "request-1") } returns Mono.just(true)
            every { last(aggregateId) } returns Mono.just(stream)
            every { scanAggregateId(aggregate, "after-id", 10) } returns Flux.just(aggregateId)
            every { close() } just Runs
        }
        val eventStore = MetricEventStore(delegate, WowMetrics(registry), "mongo")

        StepVerifier.create(eventStore.append(stream)).verifyComplete()
        StepVerifier.create(eventStore.load(aggregateId, 1, 10)).expectNext(stream).verifyComplete()
        StepVerifier.create(eventStore.load(aggregateId, 100L, 200L)).expectNext(stream).verifyComplete()
        StepVerifier.create(eventStore.existsRequestId(aggregateId, "request-1")).expectNext(true).verifyComplete()
        StepVerifier.create(eventStore.last(aggregateId)).expectNext(stream).verifyComplete()
        StepVerifier.create(eventStore.scanAggregateId(aggregate, "after-id", 10))
            .expectNext(aggregateId)
            .verifyComplete()
        eventStore.close()

        registry.successfulOperations("event_store", "mongo")
            .assert()
            .containsExactlyInAnyOrder(
                "append",
                "load_by_version",
                "load_by_time",
                "exists_request_id",
                "last",
                "scan_aggregate_id",
            )
        verify(exactly = 1) {
            delegate.append(stream)
            delegate.load(aggregateId, 1, 10)
            delegate.load(aggregateId, 100L, 200L)
            delegate.existsRequestId(aggregateId, "request-1")
            delegate.last(aggregateId)
            delegate.scanAggregateId(aggregate, "after-id", 10)
            delegate.close()
        }
    }

    @Test
    fun `snapshot store should delegate identity lifecycle and operations`() {
        val registry = SimpleMeterRegistry()
        val snapshot = mockk<Snapshot<Any>> {
            every { aggregateId } returns this@MetricStorageDecoratorTest.aggregateId
        }
        val delegate = mockk<SnapshotStore> {
            every { name } returns "snapshot-store"
            every { load<Any>(aggregateId) } returns Mono.just(snapshot)
            every { getVersion(aggregateId) } returns Mono.just(7)
            every { save(snapshot) } returns Mono.empty()
            every { close() } just Runs
        }
        val snapshotStore = MetricSnapshotStore(delegate, WowMetrics(registry), "mongo")

        snapshotStore.name.assert().isEqualTo("snapshot-store")
        StepVerifier.create(snapshotStore.load<Any>(aggregateId)).expectNext(snapshot).verifyComplete()
        StepVerifier.create(snapshotStore.getVersion(aggregateId)).expectNext(7).verifyComplete()
        StepVerifier.create(snapshotStore.save(snapshot)).verifyComplete()
        snapshotStore.close()

        registry.successfulOperations("snapshot_store", "mongo")
            .assert()
            .containsExactlyInAnyOrder("load", "get_version", "save")
        verify(exactly = 1) {
            delegate.name
            delegate.load<Any>(aggregateId)
            delegate.getVersion(aggregateId)
            delegate.save(snapshot)
            delegate.close()
        }
    }

    private fun SimpleMeterRegistry.successfulOperations(
        component: String,
        source: String,
    ): List<String> = meters
        .map { it.id }
        .filter { it.name == WowMetricNames.OPERATION }
        .filter { it.getTag(MetricDescriptor.COMPONENT_TAG) == component }
        .filter { it.getTag(MetricDescriptor.SOURCE_TAG) == source }
        .filter { it.getTag(MetricDescriptor.OUTCOME_TAG) == MetricOutcome.SUCCESS.metricValue }
        .mapNotNull { it.getTag(MetricDescriptor.OPERATION_TAG) }
}
