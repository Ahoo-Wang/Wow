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
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.propagate

/**
 * Flattens an object into an iterable of events.
 *
 * This extension function converts various types of objects into an iterable
 * collection of events. It handles single objects, arrays, and iterables,
 * normalizing them into a consistent Iterable<Any> format for event stream creation.
 *
 * @receiver The object to flatten into events
 * @return An iterable collection of event objects
 *
 * @see Iterable
 * @see Array
 */
@Suppress("UNCHECKED_CAST")
fun Any.flatEvent(): Iterable<Any> =
    when (this) {
        is Iterable<*> -> {
            this as Iterable<Any>
        }

        is Array<*> -> {
            this.asIterable() as Iterable<Any>
        }

        else -> {
            listOf(this)
        }
    }

/**
 * Converts an object to a domain event stream based on a command message.
 *
 * This extension function creates a DomainEventStream from any object, using
 * information from the upstream command message. The object is flattened into
 * individual events, each converted to a domain event with proper sequencing
 * and metadata.
 *
 * @receiver The object containing event data (can be single event, array, or iterable)
 * @param upstream The command message that triggered these events
 * @param aggregateVersion The current version of the aggregate
 * @param stateOwnerId The owner ID from the current state (default: DEFAULT_OWNER_ID)
 * @param header The header to propagate to the event stream (default: empty header)
 * @param createTime The timestamp for event creation (default: current time)
 * @return A new DomainEventStream containing the converted events
 *
 * @see DomainEventStream
 * @see CommandMessage
 * @see Header
 * @see flatEvent
 */
fun Any.toDomainEventStream(
    upstream: CommandMessage<*>,
    aggregateVersion: Int,
    stateOwnerId: String = OwnerId.DEFAULT_OWNER_ID,
    header: Header = DefaultHeader.empty(),
    createTime: Long = System.currentTimeMillis()
): DomainEventStream {
    header.propagate(upstream)
    val eventStreamId = generateGlobalId()
    val aggregateId = upstream.aggregateId
    val streamVersion = aggregateVersion + 1
    val streamOwnerId =
        upstream.ownerId.ifBlank {
            stateOwnerId
        }
    val events =
        flatEvent().toDomainEvents(
            streamVersion = streamVersion,
            aggregateId = aggregateId,
            command = upstream,
            ownerId = streamOwnerId,
            eventStreamHeader = header,
            createTime = createTime,
        )

    return SimpleDomainEventStream(
        id = eventStreamId,
        requestId = upstream.requestId,
        header = header,
        body = events,
    )
}

/**
 * Converts an iterable of event objects to a list of domain events.
 *
 * This internal function processes a collection of event objects, converting each
 * one to a DomainEvent with proper sequencing, versioning, and metadata. Events
 * are numbered sequentially starting from DEFAULT_EVENT_SEQUENCE.
 *
 * @param streamVersion The version number for the event stream
 * @param aggregateId The aggregate ID for all events
 * @param command The command that triggered these events
 * @param ownerId The owner ID for the events
 * @param eventStreamHeader The header to attach to each event
 * @param createTime The creation timestamp for all events
 * @return A list of domain events with proper sequencing
 *
 * @see DomainEvent
 * @see AggregateId
 * @see CommandMessage
 * @see DEFAULT_EVENT_SEQUENCE
 */
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
