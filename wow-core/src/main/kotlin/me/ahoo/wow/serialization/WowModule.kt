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

package me.ahoo.wow.serialization

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.serialization.command.CommandJsonDeserializer
import me.ahoo.wow.serialization.command.CommandJsonSerializer
import me.ahoo.wow.serialization.event.DomainEventJsonDeserializer
import me.ahoo.wow.serialization.event.DomainEventJsonSerializer
import me.ahoo.wow.serialization.event.EventStreamJsonDeserializer
import me.ahoo.wow.serialization.event.EventStreamJsonSerializer
import me.ahoo.wow.serialization.event.StateEventJsonDeserializer
import me.ahoo.wow.serialization.event.StateEventJsonSerializer
import me.ahoo.wow.serialization.state.SnapshotDeserializer
import me.ahoo.wow.serialization.state.SnapshotSerializer
import me.ahoo.wow.serialization.state.StateAggregateDeserializer
import me.ahoo.wow.serialization.state.StateAggregateSerializer
import tools.jackson.databind.module.SimpleModule

class WowModule : SimpleModule() {
    init {
        addSerializer(AggregateId::class.java, AggregateIdJsonSerializer)
        addDeserializer(AggregateId::class.java, AggregateIdJsonDeserializer)

        addSerializer(CommandMessage::class.java, CommandJsonSerializer)
        addDeserializer(CommandMessage::class.java, CommandJsonDeserializer)

        addSerializer(DomainEventStream::class.java, EventStreamJsonSerializer)
        addDeserializer(DomainEventStream::class.java, EventStreamJsonDeserializer)

        addSerializer(DomainEvent::class.java, DomainEventJsonSerializer)
        addDeserializer(DomainEvent::class.java, DomainEventJsonDeserializer)

        addSerializer(StateAggregate::class.java, StateAggregateSerializer)
        addDeserializer(StateAggregate::class.java, StateAggregateDeserializer)

        addSerializer(Snapshot::class.java, SnapshotSerializer)
        addDeserializer(Snapshot::class.java, SnapshotDeserializer)

        addSerializer(StateEvent::class.java, StateEventJsonSerializer)
        addDeserializer(StateEvent::class.java, StateEventJsonDeserializer)
    }
}
