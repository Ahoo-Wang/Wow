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

import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.MessageSerializer
import tools.jackson.core.JsonGenerator

abstract class AbstractEventStreamJsonSerializer<M : DomainEventStream>(messageType: Class<M>) :
    MessageSerializer<M>(messageType) {

    override fun writeExtendedInfo(generator: JsonGenerator, value: M) {
        generator.writeStringProperty(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringProperty(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeStringProperty(MessageRecords.OWNER_ID, value.ownerId)
        generator.writeStringProperty(MessageRecords.SPACE_ID, value.spaceId)
        generator.writeStringProperty(MessageRecords.COMMAND_ID, value.commandId)
        generator.writeStringProperty(MessageRecords.REQUEST_ID, value.requestId)
        generator.writeNumberProperty(MessageRecords.VERSION, value.version)
    }

    override fun writeBodyType(generator: JsonGenerator, value: M) = Unit

    override fun writeBody(generator: JsonGenerator, value: M) {
        generator.writeName(MessageRecords.BODY)
        generator.writeStartArray()
        value.body.forEach {
            generator.writeStartObject()
            generator.writeStringProperty(MessageRecords.ID, it.id)
            generator.writeStringProperty(MessageRecords.NAME, it.name)
            generator.writeStringProperty(DomainEventRecords.REVISION, it.revision)
            generator.writeStringProperty(MessageRecords.BODY_TYPE, it.body.javaClass.name)
            generator.writePOJOProperty(MessageRecords.BODY, it.body)
            generator.writeEndObject()
        }
        generator.writeEndArray()
    }
}
