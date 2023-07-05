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
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.asAggregateId

object AggregateIdJsonSerializer : StdSerializer<AggregateId>(AggregateId::class.java) {
    override fun serialize(value: AggregateId, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeStartObject()
        generator.writeStringField(MessageRecords.CONTEXT_NAME, value.contextName)
        generator.writeStringField(MessageRecords.AGGREGATE_NAME, value.aggregateName)
        generator.writeStringField(MessageRecords.AGGREGATE_ID, value.id)
        generator.writeStringField(MessageRecords.TENANT_ID, value.tenantId)
        generator.writeEndObject()
    }
}

object AggregateIdJsonDeserializer : StdDeserializer<AggregateId>(AggregateId::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AggregateId {
        val aggregateIdRecord = p.codec.readTree<ObjectNode>(p)
        val contextName = aggregateIdRecord[MessageRecords.CONTEXT_NAME].asText()
        val aggregateName = aggregateIdRecord[MessageRecords.AGGREGATE_NAME].asText()
        return MaterializedNamedAggregate(contextName, aggregateName)
            .asAggregateId(
                id = aggregateIdRecord[MessageRecords.AGGREGATE_ID].asText(),
                tenantId = aggregateIdRecord[MessageRecords.TENANT_ID].asText(),
            )
    }
}
