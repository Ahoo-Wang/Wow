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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

internal class MetricDecoratorNamingBehaviorTest {

    @Test
    fun `event store decorator should name append load and last publishers`() {
        val aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1")
        val eventStore = MetricEventStore(NoOpEventStore)

        Scannable.from(eventStore.append(SimpleDomainEventStreamStub(aggregateId))).name()
            .assert().isEqualTo("wow.eventstore.append")
        Scannable.from(eventStore.load(aggregateId, 1, 2)).name()
            .assert().isEqualTo("wow.eventstore.load")
        Scannable.from(eventStore.load(aggregateId, 10L, 20L)).name()
            .assert().isEqualTo("wow.eventstore.load")
        Scannable.from(eventStore.last(aggregateId)).name()
            .assert().isEqualTo("wow.eventstore.last")
    }

    @Test
    fun `event store decorator should still delegate publisher completion`() {
        val aggregateId = MaterializedNamedAggregate("sales", "Order").aggregateId("order-1")

        StepVerifier.create(MetricEventStore(NoOpEventStore).append(SimpleDomainEventStreamStub(aggregateId)))
            .verifyComplete()
    }

    private object NoOpEventStore : EventStore {
        override fun append(eventStream: DomainEventStream): Mono<Void> = Mono.empty()

        override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> =
            Flux.empty()

        override fun load(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Flux<DomainEventStream> =
            Flux.empty()

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> = Mono.empty()
    }

    private data class SimpleDomainEventStreamStub(
        override val aggregateId: AggregateId,
    ) : DomainEventStream {
        override val id: String = "stream-1"
        override val requestId: String = "request-1"
        override val header = me.ahoo.wow.messaging.DefaultHeader.empty()
        override val body = emptyList<me.ahoo.wow.api.event.DomainEvent<*>>()
        override val contextName: String = aggregateId.contextName
        override val aggregateName: String = aggregateId.aggregateName
        override val ownerId: String = "owner-1"
        override val spaceId: String = "space-1"
        override val commandId: String = "command-1"
        override val version: Int = 1
        override val size: Int = 0
        override val createTime: Long = 1000

        override fun copy(): DomainEventStream = this
        override fun iterator(): Iterator<me.ahoo.wow.api.event.DomainEvent<*>> = body.iterator()
    }
}
