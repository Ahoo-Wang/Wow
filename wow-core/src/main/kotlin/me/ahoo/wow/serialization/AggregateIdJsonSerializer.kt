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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

/**
 * Jackson JSON serializer for [AggregateId] objects.
 *
 * This serializer converts an [AggregateId] instance into a JSON object containing
 * the context name, aggregate name, aggregate ID, and tenant ID using predefined field names
 * from [MessageRecords].
 *
 * Example usage:
 * ```kotlin
 * val aggregateId = MaterializedNamedAggregate("context", "aggregate").aggregateId("id", "tenant")
 * val json = ObjectMapper().writeValueAsString(aggregateId)
 * // Produces: {"contextName":"context","aggregateName":"aggregate","aggregateId":"id","tenantId":"tenant"}
 * ```
 */
object AggregateIdJsonSerializer : StdSerializer<AggregateId>(AggregateId::class.java) {
    /**
     * Serializes the given [AggregateId] to JSON.
     *
     * Writes a JSON object with fields for context name, aggregate name, aggregate ID, and tenant ID.
     *
     * @param value The [AggregateId] instance to serialize. Must not be null.
     * @param generator The [JsonGenerator] used to write JSON output. Must not be null.
     * @param provider The [SerializerProvider] for accessing serialization context. Must not be null.
     */
    override fun serialize(
        value: AggregateId,
        generator: JsonGenerator,
        provider: SerializationContext
    ) {
        generator.writeStartObject()
        generator.writeStringProperty(MessageRecords.CONTEXT_NAME, value.contextName)
        generator.writeStringProperty(MessageRecords.AGGREGATE_NAME, value.aggregateName)
        generator.writeStringProperty(MessageRecords.AGGREGATE_ID, value.id)
        generator.writeStringProperty(MessageRecords.TENANT_ID, value.tenantId)
        generator.writeEndObject()
    }
}

/**
 * Jackson JSON deserializer for [AggregateId] objects.
 *
 * This deserializer converts a JSON object back into an [AggregateId] instance,
 * extracting the context name, aggregate name, aggregate ID, and tenant ID from
 * predefined field names in [MessageRecords].
 *
 * Example usage:
 * ```kotlin
 * val json = """{"contextName":"context","aggregateName":"aggregate","aggregateId":"id","tenantId":"tenant"}"""
 * val aggregateId = ObjectMapper().readValue<AggregateId>(json)
 * ```
 */
object AggregateIdJsonDeserializer : StdDeserializer<AggregateId>(AggregateId::class.java) {
    /**
     * Deserializes JSON into an [AggregateId] instance.
     *
     * Reads a JSON object and constructs an [AggregateId] using the extracted field values.
     *
     * @param p The [JsonParser] providing access to the JSON input. Must not be null.
     * @param ctxt The [DeserializationContext] for accessing deserialization context. Must not be null.
     * @return The deserialized [AggregateId] instance.
     * @throws com.fasterxml.jackson.core.JsonProcessingException if the JSON is malformed or missing required fields.
     */
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext
    ): AggregateId {
        val aggregateIdRecord = p.objectReadContext().readTree<JsonNode>(p)
        val contextName = aggregateIdRecord[MessageRecords.CONTEXT_NAME].asString()
        val aggregateName = aggregateIdRecord[MessageRecords.AGGREGATE_NAME].asString()
        return MaterializedNamedAggregate(contextName, aggregateName)
            .aggregateId(
                id = aggregateIdRecord[MessageRecords.AGGREGATE_ID].asString(),
                tenantId = aggregateIdRecord[MessageRecords.TENANT_ID].asString(),
            )
    }
}
