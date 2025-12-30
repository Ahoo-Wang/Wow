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

package me.ahoo.wow.eventsourcing.mock

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.infra.Decorator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

class DelayEventStore(
    private val delaySupplier: () -> Duration = { Duration.ofMillis(5) },
    override val delegate: EventStore = InMemoryEventStore()
) : EventStore,
    Decorator<EventStore> {
    override fun append(eventStream: DomainEventStream): Mono<Void> {
        return delegate.append(eventStream).delaySubscription(delaySupplier())
    }

    override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        return delegate.load(aggregateId, headVersion, tailVersion).delaySubscription(delaySupplier())
    }

    override fun load(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Flux<DomainEventStream> {
        return delegate.load(aggregateId, headEventTime, tailEventTime).delaySubscription(delaySupplier())
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        return delegate.last(aggregateId).delaySubscription(delaySupplier())
    }
}
