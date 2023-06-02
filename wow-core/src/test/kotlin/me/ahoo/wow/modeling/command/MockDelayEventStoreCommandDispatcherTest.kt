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
package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.tck.modeling.command.CommandDispatcherSpec
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

internal class MockDelayEventStoreCommandDispatcherTest : CommandDispatcherSpec(), EventStore {
    val delayDuration = Duration.ofMillis(100)
    val delegate: EventStore = InMemoryEventStore()

    override fun createEventStore(): EventStore {
        return this
    }

    override fun append(eventStream: DomainEventStream): Mono<Void> {
        return delegate.append(eventStream).delaySubscription(delayDuration)
    }

    override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        return delegate.load(aggregateId, headVersion, tailVersion).delaySubscription(delayDuration)
    }

    override fun scanAggregateId(namedAggregate: NamedAggregate, cursorId: String, limit: Int): Flux<AggregateId> {
        return delegate.scanAggregateId(namedAggregate, cursorId, limit).delaySubscription(delayDuration)
    }
}
