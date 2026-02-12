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
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.MessageSerializer

abstract class AbstractEventStreamJsonSerializer<M : DomainEventStream>(messageType: Class<M>) :
    MessageSerializer<M>(messageType) {

    override fun writeExtendedInfo(generator: JsonGenerator, value: M) {
        generator.writeStringField(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringField(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeStringField(MessageRecords.OWNER_ID, value.ownerId)
        generator.writeStringField(MessageRecords.SPACE_ID, value.spaceId)
        generator.writeStringField(MessageRecords.COMMAND_ID, value.commandId)
        generator.writeStringField(MessageRecords.REQUEST_ID, value.requestId)
        generator.writeNumberField(MessageRecords.VERSION, value.version)
    }

    override fun writeBodyType(generator: JsonGenerator, value: M) = Unit

    override fun writeBody(generator: JsonGenerator, value: M) {
        generator.writeFieldName(MessageRecords.BODY)
        generator.writeStartArray()
        value.body.forEach {
            generator.writeStartObject()
            generator.writeStringField(MessageRecords.ID, it.id)
            generator.writeStringField(MessageRecords.NAME, it.name)
            generator.writeStringField(DomainEventRecords.REVISION, it.revision)
            generator.writeStringField(MessageRecords.BODY_TYPE, it.body.javaClass.name)
            generator.writePOJOField(MessageRecords.BODY, it.body)
            generator.writeEndObject()
        }
        generator.writeEndArray()
    }
}
