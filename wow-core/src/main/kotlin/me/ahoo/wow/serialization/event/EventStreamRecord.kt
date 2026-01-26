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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageAggregateIdRecord
import me.ahoo.wow.serialization.MessageAggregateNameRecord
import me.ahoo.wow.serialization.MessageCommandIdRecord
import me.ahoo.wow.serialization.MessageRequestIdRecord
import me.ahoo.wow.serialization.MessageVersionRecord
import me.ahoo.wow.serialization.NamedBoundedContextMessageRecord
import me.ahoo.wow.serialization.OwnerIdRecord
import me.ahoo.wow.serialization.SpaceIdRecord

interface EventStreamRecord :
    NamedBoundedContextMessageRecord,
    MessageCommandIdRecord,
    MessageVersionRecord,
    MessageRequestIdRecord,
    MessageAggregateIdRecord,
    OwnerIdRecord,
    SpaceIdRecord,
    MessageAggregateNameRecord {
    fun toAggregateId(): AggregateId {
        return MaterializedNamedAggregate(contextName, aggregateName)
            .aggregateId(
                id = aggregateId,
                tenantId = tenantId,
            )
    }

    fun toDomainEventStream(): DomainEventStream {
        val id = id
        val commandId = commandId
        val requestId = requestId
        val version = version
        val ownerId = ownerId
        val spaceId = spaceId
        val header = toMessageHeader()
        val createTime = createTime
        val aggregateId = toAggregateId()
        val eventCount = body.size()
        val events = body.mapIndexed { index, eventNode ->
            val sequence = (index + DEFAULT_EVENT_SEQUENCE)
            StreamDomainEventRecord(
                actual = eventNode as ObjectNode,
                streamedAggregateId = aggregateId,
                streamedVersion = version,
                streamedOwnerId = ownerId,
                streamedSpaceId = spaceId,
                streamedHeader = header,
                streamedCommandId = commandId,
                sequence = sequence,
                isLast = sequence == eventCount,
                streamedCreateTime = createTime,
            ).toDomainEvent()
        }.toList()

        return SimpleDomainEventStream(
            id = id,
            requestId = requestId,
            header = header,
            body = events,
        )
    }
}

class DelegatingEventStreamRecord(override val actual: ObjectNode) : EventStreamRecord

fun ObjectNode.toEventStreamRecord(): EventStreamRecord {
    return DelegatingEventStreamRecord(this)
}

@Suppress("LongParameterList")
class FlatEventStreamRecord(
    override val id: String,
    private val rawAggregateId: AggregateId,
    override val header: ObjectNode,
    override val version: Int,
    override val ownerId: String,
    override val spaceId: SpaceId,
    override val commandId: String,
    override val requestId: String,
    override val body: JsonNode,
    override val createTime: Long
) : EventStreamRecord {

    override val actual: ObjectNode
        get() = throw UnsupportedOperationException()
    override val contextName: String
        get() = rawAggregateId.contextName
    override val aggregateName: String
        get() = rawAggregateId.aggregateName
    override val aggregateId: String
        get() = rawAggregateId.id
    override val tenantId: String
        get() = rawAggregateId.tenantId

    override fun toAggregateId(): AggregateId {
        return rawAggregateId
    }
}

@Suppress("LongParameterList")
class StreamDomainEventRecord(
    override val actual: ObjectNode,
    private val streamedAggregateId: AggregateId,
    private val streamedVersion: Int,
    private val streamedOwnerId: String,
    private val streamedSpaceId: SpaceId,
    private val streamedHeader: Header,
    private val streamedCommandId: String,
    override val sequence: Int,
    override val isLast: Boolean,
    private val streamedCreateTime: Long
) : DomainEventRecord {
    override val contextName: String
        get() = streamedAggregateId.contextName
    override val aggregateName: String
        get() = streamedAggregateId.aggregateName

    override val aggregateId: String
        get() = streamedAggregateId.id

    override val tenantId: String
        get() = streamedAggregateId.tenantId

    override val version: Int
        get() = streamedVersion
    override val ownerId: String
        get() = streamedOwnerId
    override val spaceId: SpaceId
        get() = streamedSpaceId
    override val commandId: String
        get() = streamedCommandId

    override val createTime: Long
        get() = streamedCreateTime

    override fun toMessageHeader(): Header {
        return streamedHeader
    }

    override fun toAggregateId(): AggregateId {
        return streamedAggregateId
    }
}
