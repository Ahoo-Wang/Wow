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

import me.ahoo.wow.api.modeling.OwnerId.Companion.orDefaultOwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable.Companion.orDefaultSpaceId
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords.DELETED
import me.ahoo.wow.serialization.state.StateAggregateRecords.STATE
import me.ahoo.wow.serialization.toObject
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

object StateAggregateRecords {
    const val STATE: String = "state"
    const val EVENT_ID: String = "eventId"
    const val FIRST_OPERATOR: String = "firstOperator"
    const val OPERATOR: String = "operator"
    const val FIRST_EVENT_TIME: String = "firstEventTime"
    const val EVENT_TIME: String = "eventTime"
    const val DELETED: String = "deleted"
}

abstract class AbstractStateAggregateSerializer<T : ReadOnlyStateAggregate<*>>(stateAggregateType: Class<T>) :
    StdSerializer<T>(stateAggregateType) {
    override fun serialize(value: T, generator: JsonGenerator, provider: SerializationContext) {
        generator.writeStartObject()
        generator.writeStringProperty(MessageRecords.CONTEXT_NAME, value.aggregateId.contextName)
        generator.writeStringProperty(MessageRecords.AGGREGATE_NAME, value.aggregateId.aggregateName)
        generator.writeStringProperty(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringProperty(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeStringProperty(MessageRecords.OWNER_ID, value.ownerId)
        generator.writeStringProperty(MessageRecords.SPACE_ID, value.spaceId)
        generator.writeNumberProperty(MessageRecords.VERSION, value.version)
        generator.writeStringProperty(StateAggregateRecords.EVENT_ID, value.eventId)
        generator.writeStringProperty(StateAggregateRecords.FIRST_OPERATOR, value.firstOperator)
        generator.writeStringProperty(StateAggregateRecords.OPERATOR, value.operator)
        generator.writeNumberProperty(StateAggregateRecords.FIRST_EVENT_TIME, value.firstEventTime)
        generator.writeNumberProperty(StateAggregateRecords.EVENT_TIME, value.eventTime)
        generator.writePOJOProperty(STATE, value.state)
        writeExtend(value, generator, provider)
        generator.writeBooleanProperty(DELETED, value.deleted)
        generator.writeEndObject()
    }

    protected open fun writeExtend(value: T, generator: JsonGenerator, provider: SerializationContext) = Unit
}

abstract class AbstractStateAggregateDeserializer<T : ReadOnlyStateAggregate<*>>(stateAggregateType: Class<T>) :
    StdDeserializer<T>(stateAggregateType) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        val stateRecord = p.objectReadContext().readTree<JsonNode>(p)
        val namedAggregate = MaterializedNamedAggregate(
            stateRecord[MessageRecords.CONTEXT_NAME].asString(),
            stateRecord[MessageRecords.AGGREGATE_NAME].asString(),
        )
        val metadata = namedAggregate.requiredAggregateType<Any>()
            .aggregateMetadata<Any, Any>().state
        val version = stateRecord[MessageRecords.VERSION].asInt()
        val ownerId = stateRecord[MessageRecords.OWNER_ID]?.asString().orDefaultOwnerId()
        val spaceId = stateRecord[MessageRecords.SPACE_ID]?.asString().orDefaultSpaceId()
        val eventId = stateRecord.get(StateAggregateRecords.EVENT_ID)?.asString().orEmpty()
        val firstOperator = stateRecord.get(StateAggregateRecords.FIRST_OPERATOR)?.asString().orEmpty()
        val operator = stateRecord.get(StateAggregateRecords.OPERATOR)?.asString().orEmpty()
        val firstEventTime = stateRecord.get(StateAggregateRecords.FIRST_EVENT_TIME)?.asLong() ?: 0L
        val eventTime = stateRecord.get(StateAggregateRecords.EVENT_TIME)?.asLong() ?: 0L
        val deleted = stateRecord[DELETED].asBoolean()
        val stateRoot = stateRecord[STATE].toObject(metadata.aggregateType)

        val aggregateId = namedAggregate.aggregateId(
            id = stateRecord[MessageRecords.AGGREGATE_ID].asString(),
            tenantId = stateRecord[MessageRecords.TENANT_ID].asString(),
        )
        val stateAggregate =
            metadata.toStateAggregate(
                aggregateId = aggregateId,
                ownerId = ownerId,
                spaceId = spaceId,
                state = stateRoot,
                version = version,
                eventId = eventId,
                firstOperator = firstOperator,
                operator = operator,
                firstEventTime = firstEventTime,
                eventTime = eventTime,
                deleted = deleted,
            )
        return createStateAggregate(stateRecord, stateAggregate)
    }

    abstract fun createStateAggregate(
        stateRecord: JsonNode,
        stateAggregate: ReadOnlyStateAggregate<Any>
    ): T
}
