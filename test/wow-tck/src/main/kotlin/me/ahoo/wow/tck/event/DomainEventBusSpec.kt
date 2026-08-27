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

package me.ahoo.wow.tck.event

import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.messaging.MessageBusSpec
import me.ahoo.wow.tck.mock.MockAggregateCreated

abstract class DomainEventBusSpec : MessageBusSpec<DomainEventStream, EventStreamExchange, DomainEventBus>() {
    override val topicKind: TopicKind
        get() = TopicKind.EVENT_STREAM
    override val namedAggregate: NamedAggregate
        get() = requiredNamedAggregate<MockAggregateCreated>()

    override fun createMessage(): DomainEventStream {
        return MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(generateGlobalId()),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated(generateGlobalId()) },
        )
    }
}
