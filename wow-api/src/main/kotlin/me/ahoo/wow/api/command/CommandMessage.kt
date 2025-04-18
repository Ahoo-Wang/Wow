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
 * Represents a command message that can be sent to an aggregate. This interface extends several other interfaces to provide a comprehensive set of properties and methods for handling
 *  commands within a domain-driven design (DDD) context.
 *
 * The `CommandMessage` interface ensures that each command has a unique identifier, is associated with an aggregate, and can be copied. It also provides properties to manage the state and behavior of the
 *  command, such as whether it is a create command or if it allows the creation of a new aggregate.
 *
 * @param C the type of the command payload
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
     * Represents a unique identifier for a command. This identifier is crucial for ensuring that each command can be uniquely identified, which is particularly useful in scenarios
     *  where idempotency of commands needs to be
     *  guaranteed or when tracking and correlating commands across system boundaries.
     *
     * The `commandId` property delegates to the `id` property of the implementing class, ensuring that the command has a unique identifier.
     */
    override val commandId: String
        get() = id

    /**
     * Represents the unique identifier of the aggregate to which this command message is directed.
     * This property is essential for identifying the specific aggregate that the command should be applied to, ensuring that commands are correctly routed and processed within the system.
     */
    override val aggregateId: AggregateId

    /**
     * Represents the version of the aggregate. This value is used to ensure that commands are applied to the correct version of an aggregate, which is essential for maintaining consistency and preventing
     *  conflicts in concurrent environments. A `null` value indicates that the version is not specified or relevant.
     */
    val aggregateVersion: Int?

    /**
     * Indicates whether the command is intended for creating a new aggregate. This property is crucial in determining the nature of the command, specifically if it's meant to initialize a new aggregate
     *  instance.
     */
    val isCreate: Boolean

    /**
     * Indicates whether the creation of a new aggregate is allowed by this command. This property is useful in scenarios where certain commands should only be applied to existing aggregates
     *  or when there are specific conditions under which an aggregate can be created.
     */
    val allowCreate: Boolean

    /**
     * Indicates whether the command message is a void command. A void command does not expect a return value and is typically used for operations that do not require a response.
     */
    val isVoid: Boolean
}
