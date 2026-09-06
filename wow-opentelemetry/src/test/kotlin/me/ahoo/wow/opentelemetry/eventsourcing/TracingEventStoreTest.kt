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

package me.ahoo.wow.opentelemetry.eventsourcing

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.GlobalOpenTelemetry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.metrics.metered
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.opentelemetry.snapshot.TracingSnapshotStore
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class TracingEventStoreTest {

    @Test
    fun `tracing and metrics preserve native request lookup`() {
        val aggregateId = MaterializedNamedAggregate("test", "aggregate").aggregateId("id", "tenant")
        val candidates = setOf("current", "legacy")
        val stream = mockk<DomainEventStream>()
        val delegate = mockk<EventStore>()
        every { delegate.loadByRequestIds(aggregateId, candidates) } returns Flux.just(stream)
        val store = TracingEventStore(delegate.metered(WowMetrics(SimpleMeterRegistry()), "eventStore"))

        try {
            store.loadByRequestIds(aggregateId, candidates).test().expectNext(stream).verifyComplete()
            verify(exactly = 1) { delegate.loadByRequestIds(aggregateId, candidates) }
            verify(exactly = 0) { delegate.load(any(), any<Int>(), any<Int>()) }
        } finally {
            GlobalOpenTelemetry.resetForTest()
        }
    }

    @Test
    fun `decorator chain should close the original EventStore exactly once`() {
        val delegate = CloseCountingEventStore()
        val eventStore: EventStore = TracingEventStore(
            delegate.metered(WowMetrics(SimpleMeterRegistry()), "eventStore")
        )

        eventStore.close()

        delegate.closeCount.assert().isEqualTo(1)
    }

    @Test
    fun `decorator chain should close the original SnapshotStore exactly once`() {
        val delegate = CloseCountingSnapshotStore()
        val snapshotStore: SnapshotStore = TracingSnapshotStore(
            delegate.metered(WowMetrics(SimpleMeterRegistry()), "snapshotStore")
        )

        snapshotStore.close()

        delegate.closeCount.assert().isEqualTo(1)
    }

    private class CloseCountingEventStore : EventStore {
        var closeCount: Int = 0
            private set

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

        override fun close() {
            closeCount++
        }
    }

    private class CloseCountingSnapshotStore : SnapshotStore by NoOpSnapshotStore {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
        }
    }
}
