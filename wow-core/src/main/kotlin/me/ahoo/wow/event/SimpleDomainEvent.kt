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

import me.ahoo.wow.api.event.DEFAULT_EVENT_SEQUENCE
import me.ahoo.wow.api.event.DEFAULT_REVISION
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.naming.annotation.toName

/**
 * Simple implementation of DomainEvent.
 *
 * This data class provides a concrete implementation of the DomainEvent interface,
 * representing a domain event with all required metadata and payload information.
 *
 * @param T The type of the event body
 * @property id The unique identifier of this event (default: generated global ID)
 * @property header The message header containing metadata (default: empty header)
 * @property body The event payload data
 * @property aggregateId The aggregate ID this event belongs to
 * @property ownerId The owner identifier (default: DEFAULT_OWNER_ID)
 * @property version The aggregate version when this event was created
 * @property sequence The sequence number within the event stream (default: DEFAULT_EVENT_SEQUENCE)
 * @property revision The event schema revision (default: DEFAULT_REVISION)
 * @property commandId The ID of the command that triggered this event
 * @property name The event name (default: derived from body class name)
 * @property isLast Whether this is the last event in the stream (default: true)
 * @property createTime The timestamp when the event was created (default: current time)
 *
 * @constructor Creates a new SimpleDomainEvent with the specified properties
 *
 * @see DomainEvent
 * @see NamedAggregate
 * @see AggregateId
 * @see Header
 */
data class SimpleDomainEvent<T : Any>(
    override val id: String = generateGlobalId(),
    override val header: Header = DefaultHeader.empty(),
    override val body: T,
    override val aggregateId: AggregateId,
    override val ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    override val version: Int,
    override val sequence: Int = DEFAULT_EVENT_SEQUENCE,
    override val revision: String = DEFAULT_REVISION,
    override val commandId: String,
    override val name: String = body.javaClass.toName(),
    override val isLast: Boolean = true,
    override val createTime: Long = System.currentTimeMillis()
) : DomainEvent<T>,
    NamedAggregate by aggregateId
