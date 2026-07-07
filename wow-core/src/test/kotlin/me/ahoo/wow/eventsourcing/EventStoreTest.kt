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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class EventStoreTest {

    @Test
    fun `default exists request id scans loaded event streams`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val eventStore: EventStore = ScanningEventStore(
            listOf(
                eventStream(aggregateId, "request-1"),
                eventStream(aggregateId, "request-2"),
            )
        )

        StepVerifier.create(eventStore.existsRequestId(aggregateId, "request-2"))
            .expectNext(true)
            .verifyComplete()
        StepVerifier.create(eventStore.existsRequestId(aggregateId, "request-3"))
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun `default load uses full version range`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val scanningEventStore = ScanningEventStore(emptyList())
        val eventStore: EventStore = scanningEventStore

        StepVerifier.create(eventStore.load(aggregateId))
            .verifyComplete()

        scanningEventStore.lastHeadVersion.assert().isEqualTo(EventStore.DEFAULT_HEAD_VERSION)
        scanningEventStore.lastTailVersion.assert().isEqualTo(EventStore.DEFAULT_TAIL_VERSION)
    }

    @Test
    fun `default single loads requested version`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")
        val eventStream = eventStream(aggregateId, "request-1")
        val scanningEventStore = ScanningEventStore(listOf(eventStream))
        val eventStore: EventStore = scanningEventStore

        StepVerifier.create(eventStore.single(aggregateId, version = 2))
            .expectNext(eventStream)
            .verifyComplete()

        scanningEventStore.lastHeadVersion.assert().isEqualTo(2)
        scanningEventStore.lastTailVersion.assert().isEqualTo(2)
    }

    @Test
    fun `default scan aggregate id fails when not implemented`() {
        val eventStore: EventStore = ScanningEventStore(emptyList())

        StepVerifier.create(eventStore.scanAggregateId(MOCK_AGGREGATE_METADATA.materialize()))
            .expectError(UnsupportedOperationException::class.java)
            .verify()

        StepVerifier.create(
            eventStore.scanAggregateId(
                MOCK_AGGREGATE_METADATA.materialize(),
                afterId = "aggregate-1",
                limit = 1,
            )
        )
            .expectErrorSatisfies {
                it.assert().isInstanceOf(UnsupportedOperationException::class.java)
                it.message.assert()
                    .isEqualTo(
                        "EventStore scanAggregateId is not supported. EventStore: ${eventStore::class.java.name}",
                    )
            }
            .verify()
    }

    private fun eventStream(
        aggregateId: AggregateId,
        requestId: String,
    ): DomainEventStream =
        MockAggregateChanged(requestId).toDomainEventStream(
            upstream = GivenInitializationCommand(
                aggregateId = aggregateId,
                requestId = requestId,
            ),
            aggregateVersion = 0,
        )

    private class ScanningEventStore(
        private val eventStreams: List<DomainEventStream>
    ) : EventStore {
        var lastHeadVersion: Int? = null
        var lastTailVersion: Int? = null

        override fun append(eventStream: DomainEventStream): Mono<Void> = Mono.empty()

        override fun load(
            aggregateId: AggregateId,
            headVersion: Int,
            tailVersion: Int
        ): Flux<DomainEventStream> {
            lastHeadVersion = headVersion
            lastTailVersion = tailVersion
            return Flux.fromIterable(eventStreams)
        }

        override fun load(
            aggregateId: AggregateId,
            headEventTime: Long,
            tailEventTime: Long
        ): Flux<DomainEventStream> = Flux.empty()

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = Mono.empty()
    }
}
