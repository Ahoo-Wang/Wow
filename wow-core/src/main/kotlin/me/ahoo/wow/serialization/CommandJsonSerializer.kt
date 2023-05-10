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
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.infra.TypeNameMapper.asType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.serialization.CommandRecords.AGGREGATE_VERSION
import me.ahoo.wow.serialization.CommandRecords.ALLOW_CREATE
import me.ahoo.wow.serialization.CommandRecords.IS_CREATE

object CommandJsonSerializer : MessageSerializer<CommandMessage<*>>(CommandMessage::class.java) {

    override fun writeExtendedInfo(generator: JsonGenerator, value: CommandMessage<*>) {
        generator.writeStringField(MessageRecords.AGGREGATE_ID, value.aggregateId.id)
        generator.writeStringField(MessageRecords.TENANT_ID, value.aggregateId.tenantId)
        generator.writeStringField(MessageRecords.REQUEST_ID, value.requestId)
        value.aggregateVersion?.let {
            generator.writeNumberField(AGGREGATE_VERSION, it)
        }
        generator.writeBooleanField(IS_CREATE, value.isCreate)
        generator.writeBooleanField(ALLOW_CREATE, value.allowCreate)
    }
}

object CommandJsonDeserializer : StdDeserializer<CommandMessage<*>>(CommandMessage::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): CommandMessage<*> {
        val commandRecord = p.codec.readTree<ObjectNode>(p).asCommandRecord()
        val contextName = commandRecord.contextName
        val aggregateName = commandRecord.aggregateName
        val aggregateId = MaterializedNamedAggregate(contextName, aggregateName)
            .asAggregateId(
                id = commandRecord.aggregateId,
                tenantId = commandRecord.tenantId,
            )
        val bodyType = commandRecord.bodyType.asType<Any>()
        return SimpleCommandMessage(
            id = commandRecord.id,
            header = commandRecord.asMessageHeader(),
            body = commandRecord.body.asObject(bodyType),
            aggregateId = aggregateId,
            requestId = commandRecord.requestId,
            aggregateVersion = commandRecord.aggregateVersion,
            name = commandRecord.name,
            isCreate = commandRecord.isCreate,
            allowCreate = commandRecord.allowCreate,
            createTime = commandRecord.createTime,
        )
    }
}
