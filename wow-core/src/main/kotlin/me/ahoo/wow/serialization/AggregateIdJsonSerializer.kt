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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords.OWNER_ID

object AggregateIdJsonSerializer : StdSerializer<AggregateId>(AggregateId::class.java) {
    override fun serialize(value: AggregateId, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeStartObject()
        generator.writeAggregateId(value)
        generator.writeEndObject()
    }
}

object AggregateIdJsonDeserializer : StdDeserializer<AggregateId>(AggregateId::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AggregateId {
        val aggregateIdRecord = p.codec.readTree<ObjectNode>(p)
        return aggregateIdRecord.toAggregateId()
    }
}

internal fun JsonGenerator.writeAggregateId(aggregateId: AggregateId, withName: Boolean = true) {
    if (withName) {
        writeStringField(MessageRecords.CONTEXT_NAME, aggregateId.contextName)
        writeStringField(MessageRecords.AGGREGATE_NAME, aggregateId.aggregateName)
    }
    writeStringField(MessageRecords.AGGREGATE_ID, aggregateId.id)
    writeStringField(MessageRecords.TENANT_ID, aggregateId.tenantId)
    writeStringField(OWNER_ID, aggregateId.ownerId)
}

internal val JsonNode.contextName: String
    get() = get(MessageRecords.CONTEXT_NAME).asText()

internal val JsonNode.aggregateName: String
    get() = get(MessageRecords.AGGREGATE_NAME).asText()

internal val JsonNode.namedAggregate: NamedAggregate
    get() = MaterializedNamedAggregate(contextName, aggregateName)

internal val JsonNode.aggregateId: String
    get() = get(MessageRecords.AGGREGATE_ID).asText()

internal val JsonNode.tenantId: String
    get() = get(MessageRecords.TENANT_ID).asText()

internal val JsonNode.ownerId: String
    get() = get(OWNER_ID)?.asText() ?: OwnerId.DEFAULT_OWNER_ID

internal fun JsonNode.toAggregateId(): AggregateId {
    return namedAggregate
        .aggregateId(
            id = aggregateId,
            tenantId = tenantId,
            ownerId = ownerId
        )
}
