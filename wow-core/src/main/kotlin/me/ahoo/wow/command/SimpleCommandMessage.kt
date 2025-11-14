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

package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.naming.annotation.toName

/**
 * Simple implementation of CommandMessage with default values for most properties.
 * This class provides a convenient way to create command messages with sensible defaults.
 *
 * @param C The type of the command body.
 * @param id Unique identifier for the command message. Defaults to a generated global ID.
 * @param header Message header containing metadata. Defaults to an empty header.
 * @param body The actual command payload.
 * @param aggregateId Identifier of the aggregate this command targets.
 * @param ownerId Identifier of the owner/user initiating the command. Defaults to default owner.
 * @param requestId Identifier for request deduplication. Defaults to the message ID.
 * @param aggregateVersion Expected version of the aggregate for optimistic concurrency. Null means no version check.
 * @param name Human-readable name of the command. Defaults to the simple class name of the body.
 * @param isCreate Whether this command creates a new aggregate instance.
 * @param allowCreate Whether creation is allowed if the aggregate doesn't exist.
 * @param isVoid Whether this is a void command that doesn't produce events.
 * @param createTime Timestamp when the command was created. Defaults to current time.
 */
data class SimpleCommandMessage<C : Any>(
    override val id: String = generateGlobalId(),
    override val header: Header = DefaultHeader.empty(),
    override val body: C,
    override val aggregateId: AggregateId,
    override val ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    override val requestId: String = id,
    override val aggregateVersion: Int? = null,
    override val name: String = body.javaClass.toName(),
    override val isCreate: Boolean = false,
    override val allowCreate: Boolean = false,
    override val isVoid: Boolean = false,
    override val createTime: Long = System.currentTimeMillis()
) : CommandMessage<C>,
    NamedAggregate by aggregateId {
    /**
     * Creates a copy of this command message with a deep copy of the header.
     * This ensures header modifications don't affect the original message.
     *
     * @return A new CommandMessage instance with copied header.
     */
    override fun copy(): CommandMessage<C> = copy(header = header.copy())
}
