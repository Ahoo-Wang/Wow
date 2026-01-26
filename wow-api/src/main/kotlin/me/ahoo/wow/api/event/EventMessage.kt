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

package me.ahoo.wow.api.event

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.messaging.NamedBoundedContextMessage
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable

/**
 * Represents a domain event message within the Wow framework's event sourcing architecture.
 *
 * This interface serves as the base marker for all domain events, providing a comprehensive set of
 * identifiers and metadata necessary for event processing, correlation, and tracking in distributed
 * systems. It combines multiple capability interfaces to provide a complete event representation.
 *
 * The [EventMessage] is the fundamental unit of state change in the event sourcing paradigm. Each
 * event represents a fact that has happened in the domain, and together they form an immutable
 * sequence of state changes for aggregates.
 *
 * ## Inherited Capabilities
 *
 * The interface extends multiple capability interfaces, each serving a specific purpose:
 * - [NamedBoundedContextMessage]: Identifies the bounded context that produced this event
 * - [CommandId]: Correlates the event with the command that triggered it
 * - [NamedAggregate]: Identifies the aggregate type that this event belongs to
 * - [Version]: Tracks the aggregate's version after applying this event
 * - [AggregateIdCapable]: Identifies the specific aggregate instance
 * - [OwnerId]: Tracks the owner who created/modified this event
 * - [SpaceIdCapable]: Supports namespace-based data layering within tenant context
 *
 * ## Usage Example
 *
 * ```kotlin
 * data class OrderCreatedEvent(
 *     override val aggregateId: String,
 *     override val aggregateVersion: Int,
 *     override val commandId: String,
 *     override val tenantId: String,
 *     override val ownerId: String,
 *     override val spaceId: String,
 *     val orderId: String,
 *     val customerId: String,
 *     val totalAmount: BigDecimal
 * ) : EventMessage<OrderCreatedEvent, OrderCreated>
 *
 * interface OrderCreated
 * ```
 *
 * @param SOURCE The concrete type of this event message, used for self-referential typing
 * @param T The event type marker, typically an empty interface for type-safe event handling
 *
 * @see me.ahoo.wow.api.event.DomainEvent for concrete event implementation
 * @see me.ahoo.wow.api.command.CommandMessage for the command counterpart
 *
 * @since 1.0.0
 */
interface EventMessage<SOURCE : EventMessage<SOURCE, T>, out T> :
    NamedBoundedContextMessage<SOURCE, T>,
    CommandId,
    NamedAggregate,
    Version,
    AggregateIdCapable,
    OwnerId,
    SpaceIdCapable
