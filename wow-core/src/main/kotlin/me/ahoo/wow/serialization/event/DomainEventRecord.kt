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

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.upgrader.EventUpgraderFactory
import me.ahoo.wow.infra.TypeNameMapper.toType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageAggregateIdRecord
import me.ahoo.wow.serialization.MessageAggregateNameRecord
import me.ahoo.wow.serialization.MessageBodyRecord
import me.ahoo.wow.serialization.MessageBodyTypeRecord
import me.ahoo.wow.serialization.MessageCommandIdRecord
import me.ahoo.wow.serialization.MessageIdRecord
import me.ahoo.wow.serialization.MessageNameRecord
import me.ahoo.wow.serialization.MessageVersionRecord
import me.ahoo.wow.serialization.NamedBoundedContextMessageRecord
import me.ahoo.wow.serialization.OwnerIdRecord
import me.ahoo.wow.serialization.SpaceIdRecord
import me.ahoo.wow.serialization.toObject

object DomainEventRecords {
    const val SEQUENCE = "sequence"
    const val REVISION = "revision"
    const val IS_LAST = "isLast"
}

interface StreamEventRecord : MessageIdRecord, MessageBodyTypeRecord, MessageBodyRecord, MessageNameRecord {
    val revision: String
        get() = actual[DomainEventRecords.REVISION].asText()
}

interface DomainEventRecord :
    StreamEventRecord,
    NamedBoundedContextMessageRecord,
    MessageAggregateNameRecord,
    MessageAggregateIdRecord,
    OwnerIdRecord,
    SpaceIdRecord,
    MessageCommandIdRecord,
    MessageVersionRecord {
    val sequence: Int
        get() = actual[DomainEventRecords.SEQUENCE].asInt()
    val isLast: Boolean
        get() = actual[DomainEventRecords.IS_LAST].asBoolean()

    fun toAggregateId(): AggregateId {
        return MaterializedNamedAggregate(contextName, aggregateName)
            .aggregateId(
                id = aggregateId,
                tenantId = tenantId,
            )
    }

    fun toDomainEvent(): DomainEvent<Any> {
        val upgradedRecord = EventUpgraderFactory.upgrade(this)
        return upgradedRecord.toDomainEventObject()
    }

    private fun toDomainEventObject(): DomainEvent<Any> {
        val aggregateId = toAggregateId()
        val bodyType = try {
            bodyType.toType<Any>()
        } catch (classNotFoundException: ClassNotFoundException) {
            @Suppress("UNCHECKED_CAST")
            return JsonDomainEvent(
                id = id,
                header = toMessageHeader(),
                bodyType = bodyType,
                body = body,
                aggregateId = aggregateId,
                ownerId = ownerId,
                spaceId = spaceId,
                version = version,
                sequence = sequence,
                isLast = isLast,
                revision = revision,
                commandId = commandId,
                name = name,
                createTime = createTime,
            ) as DomainEvent<Any>
        }
        return SimpleDomainEvent(
            id = id,
            header = toMessageHeader(),
            body = body.toObject(bodyType),
            aggregateId = aggregateId,
            ownerId = ownerId,
            spaceId = spaceId,
            version = version,
            sequence = sequence,
            isLast = isLast,
            revision = revision,
            commandId = commandId,
            name = name,
            createTime = createTime,
        )
    }
}

class DelegatingDomainEventRecord(override val actual: ObjectNode) : DomainEventRecord

fun ObjectNode.toDomainEventRecord(): DomainEventRecord {
    return DelegatingDomainEventRecord(this)
}
