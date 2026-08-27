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

package me.ahoo.wow.eventsourcing.state

import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.messaging.TopicKindCapable
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.LocalMessageBus
import me.ahoo.wow.messaging.MessageBus

/**
 * Message bus for publishing and subscribing to state events.
 * State events combine domain events with the resulting aggregate state,
 * enabling subscribers to react to both the event and the current state.
 *
 * This bus is specifically designed for state events with topic kind STATE_EVENT.
 */
interface StateEventBus :
    MessageBus<StateEvent<*>, StateEventExchange<*>>,
    TopicKindCapable {
    /**
     * The topic kind for state events.
     */
    override val topicKind: TopicKind
        get() = TopicKind.STATE_EVENT
}

/**
 * Local state event bus that handles message routing within the same JVM instance.
 * Messages are processed synchronously without network communication.
 */
interface LocalStateEventBus :
    StateEventBus,
    LocalMessageBus<StateEvent<*>, StateEventExchange<*>>

/**
 * Distributed state event bus that handles message routing across multiple instances or services.
 * Messages are published to a distributed messaging system for cross-instance communication.
 */
interface DistributedStateEventBus :
    StateEventBus,
    DistributedMessageBus<StateEvent<*>, StateEventExchange<*>>
