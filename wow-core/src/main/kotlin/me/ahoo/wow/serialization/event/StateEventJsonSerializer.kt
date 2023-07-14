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

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.asStateEvent
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.serialization.asObject
import me.ahoo.wow.serialization.state.StateAggregateRecords.DELETED
import me.ahoo.wow.serialization.state.StateAggregateRecords.FIRST_EVENT_TIME
import me.ahoo.wow.serialization.state.StateAggregateRecords.FIRST_OPERATOR
import me.ahoo.wow.serialization.state.StateAggregateRecords.OPERATOR
import me.ahoo.wow.serialization.state.StateAggregateRecords.STATE

object StateEventJsonSerializer :
    AbstractEventStreamJsonSerializer<StateEvent<*>>(StateEvent::class.java) {
    override fun writeExtendedInfo(generator: JsonGenerator, value: StateEvent<*>) {
        super.writeExtendedInfo(generator, value)
        generator.writeStringField(FIRST_OPERATOR, value.firstOperator)
        generator.writeStringField(OPERATOR, value.operator)
        generator.writeNumberField(FIRST_EVENT_TIME, value.firstEventTime)
        generator.writePOJOField(STATE, value.state)
        generator.writeBooleanField(DELETED, value.deleted)
    }
}

object StateEventJsonDeserializer : StdDeserializer<StateEvent<*>>(StateEvent::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): StateEvent<*> {
        val stateEventRecord = p.codec.readTree<ObjectNode>(p)
        val eventStream = stateEventRecord.asEventStreamRecord()
            .asDomainEventStream()
        val metadata = eventStream.asRequiredAggregateType<Any>()
            .asAggregateMetadata<Any, Any>().state
        val firstOperator = stateEventRecord.get(FIRST_OPERATOR)?.asText().orEmpty()
        val operator = stateEventRecord.get(OPERATOR)?.asText().orEmpty()
        val firstEventTime = stateEventRecord.get(FIRST_EVENT_TIME)?.asLong() ?: 0L
        val deleted = stateEventRecord[DELETED].asBoolean()
        val stateRoot = stateEventRecord[STATE].asObject(metadata.aggregateType)
        return eventStream.asStateEvent(
            state = stateRoot,
            firstOperator = firstOperator,
            operator = operator,
            firstEventTime = firstEventTime,
            deleted = deleted
        )
    }
}
