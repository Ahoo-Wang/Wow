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

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.upgrader.EventUpgraderFactory
import me.ahoo.wow.infra.TypeNameMapper.asType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.asAggregateId

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
    MessageCommandIdRecord,
    MessageVersionRecord {
    val sequence: Int
        get() = actual[DomainEventRecords.SEQUENCE].asInt()
    val isLast: Boolean
        get() = actual[DomainEventRecords.IS_LAST].asBoolean()

    fun asAggregateId(): AggregateId {
        return MaterializedNamedAggregate(contextName, aggregateName)
            .asAggregateId(
                id = aggregateId,
                tenantId = tenantId,
            )
    }

    fun asDomainEvent(): DomainEvent<Any> {
        val upgradedRecord = EventUpgraderFactory.upgrade(this)
        return upgradedRecord.asDomainEventObject()
    }

    private fun asDomainEventObject(): DomainEvent<Any> {
        val aggregateId = asAggregateId()
        val bodyType = try {
            bodyType.asType<Any>()
        } catch (classNotFoundException: ClassNotFoundException) {
            @Suppress("UNCHECKED_CAST")
            return BodyTypeNotFoundDomainEvent(
                id = id,
                header = asMessageHeader(),
                body = body,
                aggregateId = aggregateId,
                version = version,
                sequence = sequence,
                isLast = isLast,
                revision = revision,
                commandId = commandId,
                name = name,
                createTime = createTime,
                cause = classNotFoundException
            ) as DomainEvent<Any>
        }
        return SimpleDomainEvent(
            id = id,
            header = asMessageHeader(),
            body = body.asObject(bodyType),
            aggregateId = aggregateId,
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

fun ObjectNode.asDomainEventRecord(): DomainEventRecord {
    return DelegatingDomainEventRecord(this)
}
