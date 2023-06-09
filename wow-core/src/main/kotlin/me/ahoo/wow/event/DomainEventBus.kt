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
package me.ahoo.wow.event

import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.messaging.TopicKindCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.LocalMessageBus
import me.ahoo.wow.messaging.MessageBus
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Domain Event Bus.
 *
 * 1. 领域事件发布有序性(per AggregateId)
 * 2. 领域事件处理有序性
 *
 */
interface DomainEventBus : MessageBus<DomainEventStream, EventStreamExchange>, TopicKindCapable {
    override val topicKind: TopicKind
        get() = TopicKind.EVENT_STREAM
}

interface LocalDomainEventBus : DomainEventBus, LocalMessageBus<DomainEventStream, EventStreamExchange>

interface DistributedDomainEventBus : DomainEventBus, DistributedMessageBus<DomainEventStream, EventStreamExchange>

object NoOpDomainEventBus : DomainEventBus {
    override fun send(message: DomainEventStream): Mono<Void> {
        return Mono.empty()
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return Flux.empty()
    }
}
