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

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.wow.infra.TypeNameMapper.asType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.asStateAggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregate.Companion.asStateAggregate
import me.ahoo.wow.serialization.StateAggregateRecords.DELETED
import me.ahoo.wow.serialization.StateAggregateRecords.STATE
import me.ahoo.wow.serialization.StateAggregateRecords.STATE_TYPE

object StateAggregateRecords {
    const val STATE_TYPE: String = "stateType"
    const val STATE: String = "state"
    const val DELETED: String = "deleted"
}

abstract class AbstractStateAggregateSerializer<T : StateAggregate<*>>(stateAggregateType: Class<T>) :
    StdSerializer<T>(stateAggregateType) {
    override fun serialize(value: T, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeStartObject()
        generator.writeStringField(MessageRecords.CONTEXT_NAME, value.aggregateId.contextName)
        generator.writeStringField(MessageRecords.AGGREGATE_NAME, value.aggregateId.aggregateName)
        generator.writeStringField(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringField(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeNumberField(MessageRecords.VERSION, value.version)
        generator.writeStringField(STATE_TYPE, value.stateRoot.javaClass.name)
        generator.writePOJOField(STATE, value.stateRoot)
        writeExtend(value, generator, provider)
        generator.writeBooleanField(DELETED, value.deleted)
        generator.writeEndObject()
    }

    protected open fun writeExtend(value: T, generator: JsonGenerator, provider: SerializerProvider) = Unit
}

abstract class AbstractStateAggregateDeserializer<T : StateAggregate<*>>(stateAggregateType: Class<T>) :
    StdDeserializer<T>(stateAggregateType) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        val stateRecord = p.codec.readTree<JsonNode>(p)
        val metadata = stateRecord[STATE_TYPE].asText().asType<Any>()
            .asStateAggregateMetadata()
        val version = stateRecord[MessageRecords.VERSION].asInt()
        val deleted = stateRecord[DELETED].asBoolean()
        val stateRoot = stateRecord[STATE].asObject(metadata.aggregateType)
        val namedAggregate = MaterializedNamedAggregate(
            stateRecord[MessageRecords.CONTEXT_NAME].asText(),
            stateRecord[MessageRecords.AGGREGATE_NAME].asText(),
        )
        val aggregateId = namedAggregate.asAggregateId(
            id = stateRecord[MessageRecords.AGGREGATE_ID].asText(),
            tenantId = stateRecord[MessageRecords.TENANT_ID].asText(),
        )
        val stateAggregate = metadata.asStateAggregate(aggregateId, stateRoot, version, deleted)
        return createStateAggregate(stateRecord, stateAggregate)
    }

    abstract fun createStateAggregate(
        stateRecord: JsonNode,
        stateAggregate: StateAggregate<Any>,
    ): T
}
