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

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.MessageSerializer
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.node.ObjectNode

object DomainEventJsonSerializer : MessageSerializer<DomainEvent<*>>(DomainEvent::class.java) {

    override fun writeExtendedInfo(generator: JsonGenerator, value: DomainEvent<*>) {
        generator.writeStringProperty(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeStringProperty(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringProperty(MessageRecords.OWNER_ID, value.ownerId)
        generator.writeStringProperty(MessageRecords.SPACE_ID, value.spaceId)
        generator.writeStringProperty(MessageRecords.COMMAND_ID, value.commandId)
        generator.writeNumberProperty(MessageRecords.VERSION, value.version)
        generator.writeNumberProperty(DomainEventRecords.SEQUENCE, value.sequence)
        generator.writeStringProperty(DomainEventRecords.REVISION, value.revision)
        generator.writeBooleanProperty(DomainEventRecords.IS_LAST, value.isLast)
    }
}

object DomainEventJsonDeserializer : StdDeserializer<DomainEvent<*>>(DomainEvent::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DomainEvent<*> {
        return p.objectReadContext().readTree<ObjectNode>(p).toDomainEventRecord()
            .toDomainEvent()
    }
}
