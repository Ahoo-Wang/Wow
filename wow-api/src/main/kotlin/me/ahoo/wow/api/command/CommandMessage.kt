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

package me.ahoo.wow.api.command

import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.messaging.NamedMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId

/**
 * Represents a complete command message that encapsulates a command to be executed against an aggregate in the Wow framework.
 *
 * This interface combines multiple concerns for command handling in a domain-driven design (DDD) context:
 * - Unique identification via [CommandId] and [RequestId]
 * - Aggregate targeting via [AggregateIdCapable] and [NamedAggregate]
 * - Message structure via [NamedMessage]
 * - Ownership context via [OwnerId]
 * - Copy capability for immutability patterns
 *
 * Command messages are the primary mechanism for initiating state changes in aggregates, supporting
 * both creation and modification operations with proper versioning and idempotency guarantees.
 *
 * @param C The type of the command payload, which contains the specific data for the operation
 *
 * @see CommandId for command uniqueness
 * @see AggregateId for aggregate targeting
 * @see NamedMessage for message structure
 *
 * Example usage:
 * ```kotlin
 * data class CreateOrderCommand(
 *     val customerId: String,
 *     val items: List<OrderItem>
 * ) : CommandMessage<CreateOrderCommand> {
 *     // Implement required properties...
 * }
 * ```
 */
interface CommandMessage<C : Any> :
    NamedMessage<CommandMessage<C>, C>,
    AggregateIdCapable,
    NamedAggregate,
    OwnerId,
    CommandId,
    RequestId,
    Copyable<CommandMessage<C>> {
    /**
     * Unique identifier for this command instance, delegated from the message ID.
     *
     * This property provides idempotency guarantees and enables command deduplication.
     * The value is automatically derived from the [NamedMessage.id] property.
     *
     * @return A globally unique string identifier for the command
     */
    override val commandId: String
        get() = id

    /**
     * The target aggregate identifier for this command.
     *
     * Specifies which aggregate instance this command should be applied to.
     * The aggregate ID includes both the aggregate type name and instance identifier.
     *
     * @return The [AggregateId] containing aggregate name and ID for routing
     *
     * @see AggregateId for the structure of aggregate identifiers
     */
    override val aggregateId: AggregateId

    /**
     * The expected version of the target aggregate for optimistic concurrency control.
     *
     * When specified, the command will only be applied if the aggregate's current version matches this value.
     * This prevents concurrent modification conflicts and ensures data consistency.
     *
     * @return The expected aggregate version, or null if version checking is not required
     *
     * @see AggregateVersionConflictException when version mismatch occurs
     */
    val aggregateVersion: Int?

    /**
     * Indicates whether this command is intended to create a new aggregate instance.
     *
     * Create commands initialize new aggregates and may have different validation rules
     * compared to commands that modify existing aggregates.
     *
     * @return true if this command creates a new aggregate, false for modification commands
     */
    val isCreate: Boolean

    /**
     * Indicates whether this command permits the creation of a new aggregate if one doesn't exist.
     *
     * When true, the command can create an aggregate if the target doesn't exist.
     * When false, the command will fail if the target aggregate is not found.
     *
     * @return true if aggregate creation is allowed, false otherwise
     */
    val allowCreate: Boolean

    /**
     * Indicates whether this command is a void command that doesn't expect a response.
     *
     * Void commands are typically used for fire-and-forget operations where the caller
     * doesn't need to wait for or process the command result.
     *
     * @return true if this is a void command, false if a result is expected
     */
    val isVoid: Boolean
}
