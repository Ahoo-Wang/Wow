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

package me.ahoo.wow.event

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.inject

fun Any.toDomainEventStream(
    upstream: CommandMessage<*>,
    aggregateVersion: Int,
    stateOwnerId: String = OwnerId.DEFAULT_OWNER_ID,
    header: Header = DefaultHeader.empty()
): DomainEventStream {
    header.inject(upstream)
    val eventStreamId = generateGlobalId()
    val aggregateId = upstream.aggregateId
    val streamVersion = aggregateVersion + 1
    val streamOwnerId = upstream.ownerId.ifBlank {
        stateOwnerId
    }
    val createTime = System.currentTimeMillis()

    val events = when (this) {
        is Iterable<*> -> {
            toDomainEvents(
                streamVersion = streamVersion,
                aggregateId = aggregateId,
                command = upstream,
                ownerId = streamOwnerId,
                eventStreamHeader = header,
                createTime = createTime
            )
        }

        is Array<*> -> {
            toDomainEvents(
                streamVersion = streamVersion,
                aggregateId = aggregateId,
                command = upstream,
                ownerId = streamOwnerId,
                eventStreamHeader = header,
                createTime = createTime
            )
        }

        else -> {
            toDomainEvents(
                streamVersion = streamVersion,
                aggregateId = aggregateId,
                command = upstream,
                ownerId = streamOwnerId,
                eventStreamHeader = header,
                createTime = createTime
            )
        }
    }

    return SimpleDomainEventStream(
        id = eventStreamId,
        requestId = upstream.requestId,
        header = header,
        body = events,
    )
}

private fun Any.toDomainEvents(
    streamVersion: Int,
    aggregateId: AggregateId,
    command: CommandMessage<*>,
    ownerId: String,
    eventStreamHeader: Header,
    createTime: Long
): List<DomainEvent<Any>> {
    val domainEvent = this.toDomainEvent(
        id = generateGlobalId(),
        version = streamVersion,
        aggregateId = aggregateId,
        ownerId = ownerId,
        commandId = command.commandId,
        header = eventStreamHeader.copy(),
        createTime = createTime,
    )
    return listOf(domainEvent)
}

private fun Array<*>.toDomainEvents(
    streamVersion: Int,
    aggregateId: AggregateId,
    command: CommandMessage<*>,
    ownerId: String,
    eventStreamHeader: Header,
    createTime: Long
) = mapIndexed { index, event ->
    val sequence = (index + DEFAULT_EVENT_SEQUENCE)
    event!!.toDomainEvent(
        id = generateGlobalId(),
        version = streamVersion,
        sequence = sequence,
        isLast = sequence == this.size,
        aggregateId = aggregateId,
        ownerId = ownerId,
        commandId = command.commandId,
        header = eventStreamHeader.copy(),
        createTime = createTime,
    )
}.toList()

private fun Iterable<*>.toDomainEvents(
    streamVersion: Int,
    aggregateId: AggregateId,
    command: CommandMessage<*>,
    ownerId: String,
    eventStreamHeader: Header,
    createTime: Long
): List<DomainEvent<Any>> {
    val eventCount = count()
    return mapIndexed { index, event ->
        val sequence = (index + DEFAULT_EVENT_SEQUENCE)
        event!!.toDomainEvent(
            id = generateGlobalId(),
            version = streamVersion,
            sequence = sequence,
            isLast = sequence == eventCount,
            aggregateId = aggregateId,
            ownerId = ownerId,
            commandId = command.commandId,
            header = eventStreamHeader.copy(),
            createTime = createTime,
        )
    }.toList()
}
