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

package me.ahoo.wow.serialization.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.infra.TypeNameMapper.toType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.MessageSerializer
import me.ahoo.wow.serialization.command.CommandRecords.AGGREGATE_VERSION
import me.ahoo.wow.serialization.command.CommandRecords.ALLOW_CREATE
import me.ahoo.wow.serialization.command.CommandRecords.IS_CREATE
import me.ahoo.wow.serialization.command.CommandRecords.IS_VOID
import me.ahoo.wow.serialization.toObject
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.node.ObjectNode

object CommandJsonSerializer : MessageSerializer<CommandMessage<*>>(CommandMessage::class.java) {

    override fun writeExtendedInfo(generator: JsonGenerator, value: CommandMessage<*>) {
        generator.writeStringProperty(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeStringProperty(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringProperty(MessageRecords.OWNER_ID, value.ownerId)
        generator.writeStringProperty(MessageRecords.SPACE_ID, value.spaceId)
        generator.writeStringProperty(MessageRecords.REQUEST_ID, value.requestId)
        value.aggregateVersion?.let {
            generator.writeNumberProperty(AGGREGATE_VERSION, it)
        }
        generator.writeBooleanProperty(IS_CREATE, value.isCreate)
        generator.writeBooleanProperty(IS_VOID, value.isVoid)
        generator.writeBooleanProperty(ALLOW_CREATE, value.allowCreate)
    }
}

object CommandJsonDeserializer : StdDeserializer<CommandMessage<*>>(CommandMessage::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): CommandMessage<*> {
        val commandRecord = p.objectReadContext().readTree<ObjectNode>(p).toCommandRecord()
        val contextName = commandRecord.contextName
        val aggregateName = commandRecord.aggregateName
        val aggregateId = MaterializedNamedAggregate(contextName, aggregateName)
            .aggregateId(
                id = commandRecord.aggregateId,
                tenantId = commandRecord.tenantId,
            )
        val bodyType = commandRecord.bodyType.toType<Any>()
        return SimpleCommandMessage(
            id = commandRecord.id,
            header = commandRecord.toMessageHeader(),
            body = commandRecord.body.toObject(bodyType),
            aggregateId = aggregateId,
            ownerId = commandRecord.ownerId,
            spaceId = commandRecord.spaceId,
            requestId = commandRecord.requestId,
            aggregateVersion = commandRecord.aggregateVersion,
            name = commandRecord.name,
            isCreate = commandRecord.isCreate,
            allowCreate = commandRecord.allowCreate,
            isVoid = commandRecord.isVoid,
            createTime = commandRecord.createTime,
        )
    }
}
