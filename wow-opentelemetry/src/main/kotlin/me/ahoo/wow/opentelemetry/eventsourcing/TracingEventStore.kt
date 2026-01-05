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

import io.opentelemetry.context.Context
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.opentelemetry.TraceFlux
import me.ahoo.wow.opentelemetry.TraceMono
import me.ahoo.wow.opentelemetry.Traced
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class TracingEventStore(override val delegate: EventStore) : Traced, EventStore, Decorator<EventStore> {
    override fun append(eventStream: DomainEventStream): Mono<Void> {
        return Mono.defer {
            val parentContext = Context.current()
            val source = delegate.append(eventStream)
            TraceMono(parentContext, EventStoreInstrumenter.APPEND_INSTRUMENTER, eventStream, source)
        }
    }

    override fun load(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        return Flux.defer {
            val parentContext = Context.current()
            val source = delegate.load(aggregateId, headVersion, tailVersion)
            TraceFlux(parentContext, EventStoreInstrumenter.LOAD_INSTRUMENTER, aggregateId, source)
        }
    }

    override fun load(aggregateId: AggregateId, headEventTime: Long, tailEventTime: Long): Flux<DomainEventStream> {
        return Flux.defer {
            val parentContext = Context.current()
            val source = delegate.load(aggregateId, headEventTime, tailEventTime)
            TraceFlux(parentContext, EventStoreInstrumenter.LOAD_INSTRUMENTER, aggregateId, source)
        }
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        return Mono.defer {
            val parentContext = Context.current()
            val source = delegate.last(aggregateId)
            TraceMono(parentContext, EventStoreInstrumenter.LOAD_INSTRUMENTER, aggregateId, source)
        }
    }
}
