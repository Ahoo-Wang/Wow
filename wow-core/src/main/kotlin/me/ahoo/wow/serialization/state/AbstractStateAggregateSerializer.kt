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

package me.ahoo.wow.serialization.state

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate.Companion.asStateAggregate
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.asObject
import me.ahoo.wow.serialization.state.StateAggregateRecords.DELETED
import me.ahoo.wow.serialization.state.StateAggregateRecords.STATE

object StateAggregateRecords {
    const val STATE: String = "state"
    const val EVENT_ID: String = "eventId"
    const val FIRST_EVENT_TIME: String = "firstEventTime"
    const val EVENT_TIME: String = "eventTime"
    const val DELETED: String = "deleted"
}

abstract class AbstractStateAggregateSerializer<T : ReadOnlyStateAggregate<*>>(stateAggregateType: Class<T>) :
    StdSerializer<T>(stateAggregateType) {
    override fun serialize(value: T, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeStartObject()
        generator.writeStringField(MessageRecords.CONTEXT_NAME, value.aggregateId.contextName)
        generator.writeStringField(MessageRecords.AGGREGATE_NAME, value.aggregateId.aggregateName)
        generator.writeStringField(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringField(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeNumberField(MessageRecords.VERSION, value.version)
        generator.writeStringField(StateAggregateRecords.EVENT_ID, value.eventId)
        generator.writeNumberField(StateAggregateRecords.FIRST_EVENT_TIME, value.firstEventTime)
        generator.writeNumberField(StateAggregateRecords.EVENT_TIME, value.eventTime)
        generator.writePOJOField(STATE, value.state)
        writeExtend(value, generator, provider)
        generator.writeBooleanField(DELETED, value.deleted)
        generator.writeEndObject()
    }

    protected open fun writeExtend(value: T, generator: JsonGenerator, provider: SerializerProvider) = Unit
}

abstract class AbstractStateAggregateDeserializer<T : ReadOnlyStateAggregate<*>>(stateAggregateType: Class<T>) :
    StdDeserializer<T>(stateAggregateType) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        val stateRecord = p.codec.readTree<JsonNode>(p)
        val namedAggregate = MaterializedNamedAggregate(
            stateRecord[MessageRecords.CONTEXT_NAME].asText(),
            stateRecord[MessageRecords.AGGREGATE_NAME].asText(),
        )
        val metadata = namedAggregate.asRequiredAggregateType<Any>()
            .asAggregateMetadata<Any, Any>().state
        val version = stateRecord[MessageRecords.VERSION].asInt()
        val eventId = stateRecord.get(StateAggregateRecords.EVENT_ID)?.asText().orEmpty()
        val firstEventTime = stateRecord.get(StateAggregateRecords.FIRST_EVENT_TIME)?.asLong() ?: 0L
        val eventTime = stateRecord.get(StateAggregateRecords.EVENT_TIME)?.asLong() ?: 0L
        val deleted = stateRecord[DELETED].asBoolean()
        val stateRoot = stateRecord[STATE].asObject(metadata.aggregateType)

        val aggregateId = namedAggregate.asAggregateId(
            id = stateRecord[MessageRecords.AGGREGATE_ID].asText(),
            tenantId = stateRecord[MessageRecords.TENANT_ID].asText(),
        )
        val stateAggregate =
            metadata.asStateAggregate(
                aggregateId = aggregateId,
                state = stateRoot,
                version = version,
                eventId = eventId,
                firstEventTime = firstEventTime,
                eventTime = eventTime,
                deleted = deleted
            )
        return createStateAggregate(stateRecord, stateAggregate)
    }

    abstract fun createStateAggregate(
        stateRecord: JsonNode,
        stateAggregate: ReadOnlyStateAggregate<Any>
    ): T
}
