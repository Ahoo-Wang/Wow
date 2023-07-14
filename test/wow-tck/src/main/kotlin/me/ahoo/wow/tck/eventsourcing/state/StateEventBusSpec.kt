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

package me.ahoo.wow.tck.eventsourcing.state

import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventData
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams
import me.ahoo.wow.tck.messaging.MessageBusSpec
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate

abstract class StateEventBusSpec : MessageBusSpec<StateEvent<*>, StateEventExchange<*>, StateEventBus>() {
    override val topicKind: TopicKind
        get() = TopicKind.STATE_EVENT
    override val namedAggregate: NamedAggregate
        get() = requiredNamedAggregate<MockAggregateCreated>()

    override fun createMessage(): StateEvent<*> {
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.asAggregateId(GlobalIdGenerator.generateAsString()),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated(GlobalIdGenerator.generateAsString()) },
        )
        val state = MockStateAggregate(eventStream.aggregateId.id)
        return StateEventData(delegate = eventStream, state = state)
    }
}
