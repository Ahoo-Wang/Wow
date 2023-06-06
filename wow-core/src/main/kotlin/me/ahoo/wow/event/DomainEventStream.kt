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

import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.RequestId
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.NamedBoundedContextMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.wait.extractWaitStrategy
import me.ahoo.wow.command.wait.injectWaitStrategy
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader

/**
 * Event Stream .
 * Relation: `Event Stream` 1:1 `CommandId`.
 *
 * 必须保证按照版本号升序排序，且版本号单调递增.
 * @author ahoo wang
 */
interface DomainEventStream :
    NamedBoundedContextMessage<DomainEventStream, List<DomainEvent<*>>>,
    RequestId,
    CommandId,
    NamedAggregate,
    Version,
    Iterable<DomainEvent<*>>,
    AggregateIdCapable,
    Copyable<DomainEventStream> {
    override val aggregateId: AggregateId
    val size: Int
}

data class SimpleDomainEventStream(
    override val id: String = GlobalIdGenerator.generateAsString(),
    override val requestId: String,
    override val header: Header = DefaultHeader.empty(),
    override val body: List<DomainEvent<*>>
) :
    DomainEventStream,
    Iterable<DomainEvent<*>> by body {
    override val aggregateId: AggregateId

    override val contextName: String
        get() = aggregateId.contextName
    override val aggregateName: String
        get() = aggregateId.aggregateName

    override val commandId: String
    override val version: Int
    override fun copy(): DomainEventStream {
        return copy(header = header.copy())
    }

    override val size: Int
    override val createTime: Long

    init {
        require(body.isNotEmpty()) { "events can not be empty." }
        body.first().let {
            aggregateId = it.aggregateId
            commandId = it.commandId
            version = it.version
            createTime = it.createTime
        }
        size = body.size
    }
}

fun Any.asDomainEventStream(
    command: CommandMessage<*>,
    aggregateVersion: Int,
    header: Header = DefaultHeader.empty()
): DomainEventStream {
    command.header.extractWaitStrategy()?.let {
        header.injectWaitStrategy(it.commandWaitEndpoint, it.stage)
    }
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
