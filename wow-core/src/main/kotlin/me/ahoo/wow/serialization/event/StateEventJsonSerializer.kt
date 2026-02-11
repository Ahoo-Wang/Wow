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

package me.ahoo.wow.serialization.event

import me.ahoo.wow.configuration.aggregateType
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.state.StateAggregateRecords.DELETED
import me.ahoo.wow.serialization.state.StateAggregateRecords.FIRST_EVENT_TIME
import me.ahoo.wow.serialization.state.StateAggregateRecords.FIRST_OPERATOR
import me.ahoo.wow.serialization.state.StateAggregateRecords.STATE
import me.ahoo.wow.serialization.toObject
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.node.ObjectNode

object StateEventJsonSerializer :
    AbstractEventStreamJsonSerializer<StateEvent<*>>(StateEvent::class.java) {
    override fun writeExtendedInfo(generator: JsonGenerator, value: StateEvent<*>) {
        super.writeExtendedInfo(generator, value)
        generator.writeStringProperty(FIRST_OPERATOR, value.firstOperator)
        generator.writeNumberProperty(FIRST_EVENT_TIME, value.firstEventTime)
        generator.writePOJOProperty(STATE, value.state)
        generator.writeBooleanProperty(DELETED, value.deleted)
    }
}

object StateEventJsonDeserializer : StdDeserializer<StateEvent<*>>(StateEvent::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): StateEvent<*> {
        val stateEventRecord = p.objectReadContext().readTree<ObjectNode>(p)
        val eventStream = stateEventRecord.toEventStreamRecord()
            .toDomainEventStream()
        val firstOperator = stateEventRecord.get(FIRST_OPERATOR)?.asText().orEmpty()
        val firstEventTime = stateEventRecord.get(FIRST_EVENT_TIME)?.asLong() ?: 0L
        val deleted = stateEventRecord[DELETED].asBoolean()
        val stateRecord = stateEventRecord[STATE] as ObjectNode
        val aggregateType = eventStream.aggregateType<Any>()
        val stateRoot = if (aggregateType == null) {
            StateJsonRecord(stateRecord)
        } else {
            val stateMetadata = aggregateType.aggregateMetadata<Any, Any>().state
            stateRecord.toObject(stateMetadata.aggregateType)
        }
        return eventStream.toStateEvent(
            state = stateRoot,
            firstOperator = firstOperator,
            firstEventTime = firstEventTime,
            deleted = deleted
        )
    }
}
