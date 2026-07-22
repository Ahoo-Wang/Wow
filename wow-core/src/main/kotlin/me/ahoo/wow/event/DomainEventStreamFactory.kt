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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable
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
    aggregateVersion: Int = Version.UNINITIALIZED_VERSION,
    stateOwnerId: String = OwnerId.DEFAULT_OWNER_ID,
    stateSpaceId: String = SpaceIdCapable.DEFAULT_SPACE_ID,
    header: Header = DefaultHeader.empty(),
    createTime: Long = System.currentTimeMillis()
): DomainEventStream {
    header.propagate(upstream)
    val eventStreamId = generateGlobalId()
    val aggregateId = upstream.aggregateId
    val streamVersion = aggregateVersion + 1
    val streamOwnerId = upstream.ownerId.ifBlank {
        stateOwnerId
    }
    val streamSpaceId = upstream.spaceId.ifBlank {
        stateSpaceId
    }
    val commandId = upstream.commandId
    val events = flatEvent().mapIndexedWithLast { index, event, isLast ->
        val nonNullEvent = requireNotNull(event) {
            "Domain event at index[$index] must not be null."
        }
        nonNullEvent.toDomainEvent(
            id = generateGlobalId(),
            version = streamVersion,
            sequence = index + DEFAULT_EVENT_SEQUENCE,
            isLast = isLast,
            aggregateId = aggregateId,
            ownerId = streamOwnerId,
            spaceId = streamSpaceId,
            commandId = commandId,
            header = header.copy(),
            createTime = createTime,
        )
    }

    return SimpleDomainEventStream(
        id = eventStreamId,
        requestId = upstream.requestId,
        header = header,
        body = events,
    )
}

private inline fun <T, R> Iterable<T>.mapIndexedWithLast(
    transform: (index: Int, value: T, isLast: Boolean) -> R
): List<R> {
    val iterator = iterator()
    val result = if (this is Collection<*>) ArrayList<R>(size) else ArrayList()
    if (!iterator.hasNext()) {
        return result
    }

    var index = 0
    var value = iterator.next()
    while (iterator.hasNext()) {
        result += transform(index, value, false)
        value = iterator.next()
        index++
    }
    result += transform(index, value, true)
    return result
}
