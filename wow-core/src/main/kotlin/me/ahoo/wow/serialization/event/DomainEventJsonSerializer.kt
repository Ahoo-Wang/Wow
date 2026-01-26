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
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.MessageSerializer

object DomainEventJsonSerializer : MessageSerializer<DomainEvent<*>>(DomainEvent::class.java) {

    override fun writeExtendedInfo(generator: JsonGenerator, value: DomainEvent<*>) {
        generator.writeStringField(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeStringField(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringField(MessageRecords.OWNER_ID, value.ownerId)
        generator.writeStringField(MessageRecords.SPACE_ID, value.spaceId)
        generator.writeStringField(MessageRecords.COMMAND_ID, value.commandId)
        generator.writeNumberField(MessageRecords.VERSION, value.version)
        generator.writeNumberField(DomainEventRecords.SEQUENCE, value.sequence)
        generator.writeStringField(DomainEventRecords.REVISION, value.revision)
        generator.writeBooleanField(DomainEventRecords.IS_LAST, value.isLast)
    }
}

object DomainEventJsonDeserializer : StdDeserializer<DomainEvent<*>>(DomainEvent::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DomainEvent<*> {
        return p.codec.readTree<ObjectNode>(p).toDomainEventRecord()
            .toDomainEvent()
    }
}
