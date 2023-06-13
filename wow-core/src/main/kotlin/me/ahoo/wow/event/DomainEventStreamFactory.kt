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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.CommandMessage
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.wait.propagateWaitStrategy
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader

fun Any.asDomainEventStream(
    command: CommandMessage<*>,
    aggregateVersion: Int,
    header: Header = DefaultHeader.empty()
): DomainEventStream {
    header.propagateWaitStrategy(command.header)
    command.header.operator?.let {
        header.withOperator(it)
    }
    val eventStreamId = GlobalIdGenerator.generateAsString()
    val aggregateId = command.aggregateId
    val streamVersion = aggregateVersion + 1
    val createTime = System.currentTimeMillis()

    val events = when (this) {
        is Iterable<*> -> {
            asDomainEvents(streamVersion, aggregateId, command, header, createTime)
        }

        is Array<*> -> {
            asDomainEvents(streamVersion, aggregateId, command, header, createTime)
        }

        else -> {
            asDomainEvents(streamVersion, aggregateId, command, header, createTime)
        }
    }

    return SimpleDomainEventStream(
        id = eventStreamId,
        requestId = command.requestId,
        header = header,
        body = events,
    )
}

private fun Any.asDomainEvents(
    streamVersion: Int,
    aggregateId: AggregateId,
    command: CommandMessage<*>,
    eventStreamHeader: Header,
    createTime: Long
): List<DomainEvent<Any>> {
    val domainEvent = this.asDomainEvent(
        id = GlobalIdGenerator.generateAsString(),
        version = streamVersion,
        aggregateId = aggregateId,
        commandId = command.commandId,
        header = eventStreamHeader.copy(),
        createTime = createTime,
    )
    return listOf(domainEvent)
}

private fun Array<*>.asDomainEvents(
    streamVersion: Int,
    aggregateId: AggregateId,
    command: CommandMessage<*>,
    eventStreamHeader: Header,
    createTime: Long
) = mapIndexed { index, event ->
    val sequence = (index + DEFAULT_EVENT_SEQUENCE)
    event!!.asDomainEvent(
        id = GlobalIdGenerator.generateAsString(),
        version = streamVersion,
        sequence = sequence,
        isLast = sequence == this.size,
        aggregateId = aggregateId,
        commandId = command.commandId,
        header = eventStreamHeader.copy(),
        createTime = createTime,
    )
}.toList()

private fun Iterable<*>.asDomainEvents(
    streamVersion: Int,
    aggregateId: AggregateId,
    command: CommandMessage<*>,
    eventStreamHeader: Header,
    createTime: Long
): List<DomainEvent<Any>> {
    val eventCount = count()
    return mapIndexed { index, event ->
        val sequence = (index + DEFAULT_EVENT_SEQUENCE)
        event!!.asDomainEvent(
            id = GlobalIdGenerator.generateAsString(),
            version = streamVersion,
            sequence = sequence,
            isLast = sequence == eventCount,
            aggregateId = aggregateId,
            commandId = command.commandId,
            header = eventStreamHeader.copy(),
            createTime = createTime,
        )
    }.toList()
}
