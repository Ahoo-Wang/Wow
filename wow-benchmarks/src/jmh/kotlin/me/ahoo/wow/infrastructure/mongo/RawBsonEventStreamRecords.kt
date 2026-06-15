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

package me.ahoo.wow.infrastructure.mongo

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.event.DomainEventRecords
import me.ahoo.wow.serialization.event.JsonDomainEvent
import me.ahoo.wow.serialization.toJsonNode
import org.bson.BsonBinary
import org.bson.BsonBinaryWriter
import org.bson.BsonWriter
import org.bson.RawBsonDocument
import org.bson.io.BasicOutputBuffer
import org.bson.types.Decimal128
import tools.jackson.databind.JsonNode

object RawBsonEventStreamRecords {
    fun toRawBsonDocument(eventStream: DomainEventStream): RawBsonDocument {
        val output = BasicOutputBuffer()
        val writer = BsonBinaryWriter(output)
        try {
            writer.writeEventStream(eventStream)
        } finally {
            writer.close()
        }
        return RawBsonDocument(output.toByteArray())
    }

    private fun BsonWriter.writeEventStream(eventStream: DomainEventStream) {
        writeStartDocument()
        writeString(Documents.ID_FIELD, eventStream.id)
        writeString(MessageRecords.CONTEXT_NAME, eventStream.contextName)
        writeString(MessageRecords.AGGREGATE_NAME, eventStream.aggregateName)
        writeString(MessageRecords.AGGREGATE_ID, eventStream.aggregateId.id)
        writeString(MessageRecords.TENANT_ID, eventStream.aggregateId.tenantId)
        writeString(MessageRecords.OWNER_ID, eventStream.ownerId)
        writeString(MessageRecords.SPACE_ID, eventStream.spaceId)
        writeString(MessageRecords.COMMAND_ID, eventStream.commandId)
        writeString(MessageRecords.REQUEST_ID, eventStream.requestId)
        writeInt32(MessageRecords.VERSION, eventStream.version)
        writeHeader(eventStream)
        writeBody(eventStream)
        writeInt64(MessageRecords.CREATE_TIME, eventStream.createTime)
        writeInt32(Documents.SIZE_FIELD, eventStream.size)
        writeEndDocument()
    }

    private fun BsonWriter.writeHeader(eventStream: DomainEventStream) {
        writeStartDocument(MessageRecords.HEADER)
        eventStream.header.forEach { (key, value) ->
            writeString(key, value)
        }
        writeEndDocument()
    }

    private fun BsonWriter.writeBody(eventStream: DomainEventStream) {
        writeStartArray(MessageRecords.BODY)
        eventStream.body.forEach { domainEvent ->
            writeDomainEvent(domainEvent)
        }
        writeEndArray()
    }

    private fun BsonWriter.writeDomainEvent(domainEvent: DomainEvent<*>) {
        writeStartDocument()
        writeString(MessageRecords.ID, domainEvent.id)
        writeString(MessageRecords.NAME, domainEvent.name)
        writeString(DomainEventRecords.REVISION, domainEvent.revision)
        writeString(MessageRecords.BODY_TYPE, domainEvent.bodyType())
        writeJsonNode(MessageRecords.BODY, domainEvent.bodyNode())
        writeEndDocument()
    }

    private fun DomainEvent<*>.bodyType(): String =
        if (this is JsonDomainEvent) bodyType else body.javaClass.name

    private fun DomainEvent<*>.bodyNode(): JsonNode =
        if (this is JsonDomainEvent) body else body.toJsonNode()

    private fun BsonWriter.writeJsonNode(name: String, node: JsonNode) {
        writeName(name)
        writeJsonNodeValue(node)
    }

    private fun BsonWriter.writeJsonNodeValue(node: JsonNode) {
        when {
            node.isObject -> writeJsonObject(node)
            node.isArray -> writeJsonArray(node)
            else -> writeJsonScalar(node)
        }
    }

    private fun BsonWriter.writeJsonObject(node: JsonNode) {
        writeStartDocument()
        node.properties().forEach { (name, child) -> writeJsonNode(name, child) }
        writeEndDocument()
    }

    private fun BsonWriter.writeJsonArray(node: JsonNode) {
        writeStartArray()
        node.forEach { child -> writeJsonNodeValue(child) }
        writeEndArray()
    }

    private fun BsonWriter.writeJsonScalar(node: JsonNode) {
        when {
            node.isNull || node.isMissingNode -> writeNull()
            node.isString -> writeString(node.asString())
            node.isBoolean -> writeBoolean(node.asBoolean())
            node.isBinary -> writeBinaryData(BsonBinary(node.binaryValue()))
            else -> writeJsonNumber(node)
        }
    }

    private fun BsonWriter.writeJsonNumber(node: JsonNode) {
        when {
            node.isInt -> writeInt32(node.asInt())
            node.isLong -> writeInt64(node.asLong())
            node.isBigDecimal -> writeDecimal128(Decimal128(node.decimalValue()))
            node.isIntegralNumber && node.canConvertToLong() -> writeInt64(node.asLong())
            node.isFloatingPointNumber -> writeDouble(node.asDouble())
            else -> throw IllegalArgumentException(
                "Unsupported JsonNode type for Raw BSON event body: ${node.getNodeType()}",
            )
        }
    }
}
