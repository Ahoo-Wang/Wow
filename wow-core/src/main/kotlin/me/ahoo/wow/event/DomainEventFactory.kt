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
import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.event.annotation.toEventMetadata
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId

/**
 * Converts an object to a domain event with full parameter control.
 *
 * This extension function creates a DomainEvent from any object, allowing complete
 * customization of all event properties. The event metadata is automatically
 * derived from the object's class annotations.
 *
 * @param T The type of the event body
 * @param aggregateId The aggregate ID this event belongs to
 * @param commandId The ID of the command that triggered this event
 * @param id The unique event ID (default: generated global ID)
 * @param version The aggregate version for this event (default: INITIAL_VERSION)
 * @param ownerId The owner ID of the event (default: DEFAULT_OWNER_ID)
 * @param sequence The sequence number within the event stream (default: DEFAULT_EVENT_SEQUENCE)
 * @param isLast Whether this is the last event in the stream (default: true)
 * @param header The event header containing metadata (default: empty header)
 * @param createTime The timestamp when the event was created (default: current time)
 * @return A new DomainEvent instance
 *
 * @see DomainEvent
 * @see AggregateId
 * @see Header
 */
@Suppress("LongParameterList")
fun <T : Any> T.toDomainEvent(
    aggregateId: AggregateId,
    commandId: String,
    id: String = generateGlobalId(),
    version: Int = Version.INITIAL_VERSION,
    ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
    sequence: Int = DEFAULT_EVENT_SEQUENCE,
    isLast: Boolean = true,
    header: Header = DefaultHeader.empty(),
    createTime: Long = System.currentTimeMillis()
): DomainEvent<T> {
    val metadata = javaClass.toEventMetadata()

    return SimpleDomainEvent(
        id = id,
        version = version,
        revision = metadata.revision,
        aggregateId = aggregateId,
        ownerId = ownerId,
        spaceId = spaceId,
        commandId = commandId,
        name = metadata.name,
        sequence = sequence,
        isLast = isLast,
        header = header,
        body = this,
        createTime = createTime,
    )
}

/**
 * Converts an object to a domain event using string aggregate identifiers.
 *
 * This extension function creates a DomainEvent from any object, using string
 * identifiers for aggregate ID and tenant. The named aggregate information is
 * derived from the object's class annotations.
 *
 * @param T The type of the event body
 * @param aggregateId The string identifier of the aggregate
 * @param tenantId The tenant identifier
 * @param commandId The ID of the command that triggered this event
 * @param ownerId The owner ID of the event (default: DEFAULT_OWNER_ID)
 * @param id The unique event ID (default: generated global ID)
 * @param version The aggregate version for this event (default: INITIAL_VERSION)
 * @param sequence The sequence number within the event stream (default: DEFAULT_EVENT_SEQUENCE)
 * @param isLast Whether this is the last event in the stream (default: true)
 * @param header The event header containing metadata (default: empty header)
 * @param createTime The timestamp when the event was created (default: current time)
 * @return A new DomainEvent instance
 * @throws IllegalStateException if the event type doesn't have a named aggregate getter
 *
 * @see DomainEvent
 * @see NamedAggregate
 * @see Header
 */
@Suppress("LongParameterList")
fun <T : Any> T.toDomainEvent(
    aggregateId: String,
    tenantId: String,
    commandId: String,
    ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
    id: String = generateGlobalId(),
    version: Int = Version.INITIAL_VERSION,
    sequence: Int = DEFAULT_EVENT_SEQUENCE,
    isLast: Boolean = true,
    header: Header = DefaultHeader.empty(),
    createTime: Long = System.currentTimeMillis()
): DomainEvent<T> {
    val metadata = javaClass.toEventMetadata()
    checkNotNull(metadata.namedAggregateGetter)
    val namedAggregate = metadata.namedAggregateGetter.getNamedAggregate(this)
    return SimpleDomainEvent(
        id = id,
        version = version,
        revision = metadata.revision,
        aggregateId = namedAggregate.aggregateId(id = aggregateId, tenantId = tenantId),
        ownerId = ownerId,
        spaceId = spaceId,
        commandId = commandId,
        name = metadata.name,
        sequence = sequence,
        isLast = isLast,
        header = header,
        body = this,
        createTime = createTime,
    )
}
