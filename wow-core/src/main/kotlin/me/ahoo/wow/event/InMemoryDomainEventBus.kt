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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.InMemoryMessageBus
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.Many

class InMemoryDomainEventBus(
    override val sinkSupplier: (NamedAggregate) -> Many<DomainEventStream> = {
        Sinks.many().multicast().onBackpressureBuffer()
    }
) : LocalDomainEventBus, InMemoryMessageBus<DomainEventStream, EventStreamExchange>() {

    override fun DomainEventStream.createExchange(): EventStreamExchange {
        return SimpleEventStreamExchange(this)
    }
}
