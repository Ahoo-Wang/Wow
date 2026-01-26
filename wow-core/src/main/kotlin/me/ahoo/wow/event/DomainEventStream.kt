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
import me.ahoo.wow.api.command.RequestId
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.event.EventMessage
import me.ahoo.wow.api.event.IgnoreSourcing
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.NamedBoundedContextMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader

/**
 * Domain Event Stream interface representing a sequence of domain events.
 *
 * A domain event stream contains a collection of domain events that were generated
 * as a result of a single command execution. The relationship is 1:1 between
 * event streams and command IDs.
 *
 * Key requirements:
 * - Events must be sorted in ascending order by version number
 * - Version numbers must be monotonically increasing
 * - All events in a stream belong to the same aggregate
 * - Events are immutable once created
 *
 * @property aggregateId The aggregate ID this event stream belongs to
 * @property size The number of events in this stream
 *
 * @see DomainEvent
 * @see NamedBoundedContextMessage
 * @see AggregateIdCapable
 * @see Copyable
 */
interface DomainEventStream :
    EventMessage<DomainEventStream, List<DomainEvent<*>>>,
    RequestId,
    Iterable<DomainEvent<*>>,
    Copyable<DomainEventStream> {
    override val aggregateId: AggregateId
    val size: Int
}

/**
 * Simple implementation of DomainEventStream.
 *
 * This data class provides a concrete implementation of the DomainEventStream interface,
 * containing a list of domain events with associated metadata.
 *
 * @property id The unique identifier of this event stream (default: generated global ID)
 * @property requestId The request ID that initiated this event stream
 * @property header The message header containing metadata (default: empty header)
 * @property body The list of domain events in this stream
 * @property aggregateId The aggregate ID (derived from the first event)
 * @property contextName The bounded context name (derived from aggregateId)
 * @property aggregateName The aggregate name (derived from aggregateId)
 * @property ownerId The owner ID (derived from the first event)
 * @property commandId The command ID (derived from the first event)
 * @property version The aggregate version (derived from the first event)
 * @property size The number of events in the stream
 * @property createTime The creation timestamp (derived from the first event)
 *
 * @constructor Creates a new SimpleDomainEventStream
 * @param id The stream ID
 * @param requestId The request ID
 * @param header The message header
 * @param body The list of domain events (must not be empty)
 * @throws IllegalArgumentException if the event list is empty
 *
 * @see DomainEventStream
 * @see DomainEvent
 * @see Header
 */
data class SimpleDomainEventStream(
    override val id: String = generateGlobalId(),
    override val requestId: String,
    override val header: Header = DefaultHeader.empty(),
    override val body: List<DomainEvent<*>>
) : DomainEventStream,
    Iterable<DomainEvent<*>> by body {
    override val aggregateId: AggregateId

    override val contextName: String
        get() = aggregateId.contextName
    override val aggregateName: String
        get() = aggregateId.aggregateName
    override val ownerId: String
    override val spaceId: SpaceId
    override val commandId: String
    override val version: Int

    override fun copy(): DomainEventStream = copy(header = header.copy())

    override val size: Int
    override val createTime: Long

    init {
        require(body.isNotEmpty()) { "events can not be empty." }
        body.first().let {
            aggregateId = it.aggregateId
            ownerId = it.ownerId
            spaceId = it.spaceId
            commandId = it.commandId
            version = it.version
            createTime = it.createTime
        }
        size = body.size
    }
}

/**
 * Determines if this event stream should be ignored during event sourcing.
 *
 * This function checks if the event stream contains only events that should be
 * ignored during the event sourcing process. An event stream is considered
 * ignorable if it represents the initial version and all events in the stream
 * are marked with IgnoreSourcing and contain ErrorInfo.
 *
 * @return true if the event stream should be ignored during sourcing, false otherwise
 *
 * @see IgnoreSourcing
 * @see ErrorInfo
 * @see Version.isInitialVersion
 */
fun DomainEventStream.ignoreSourcing(): Boolean {
    if (!isInitialVersion) {
        return false
    }
    return body.all {
        it.body is IgnoreSourcing && it.body is ErrorInfo
    }
}
