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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader

/** Marker object representing an initialization command for testing purposes. */
object GivenInitialization

/**
 * A command message used for initializing aggregates with given events during testing.
 *
 * This command is used internally by the testing framework to set up aggregate state
 * by replaying domain events. It implements the CommandMessage interface and provides
 * default values suitable for testing scenarios.
 *
 * @property aggregateId the identifier of the aggregate to initialize
 * @property id unique identifier for this command (auto-generated)
 * @property ownerId the owner of the aggregate (defaults to system owner)
 * @property requestId unique identifier for the request (auto-generated)
 * @property isCreate indicates this is a creation command
 * @property allowCreate allows aggregate creation if it doesn't exist
 * @property isVoid indicates if this command produces no events
 * @property header command header with default empty header
 */
data class GivenInitializationCommand(
    override val aggregateId: AggregateId,
    override val id: String = generateGlobalId(),
    override val ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    override val requestId: String = generateGlobalId(),
    override val isCreate: Boolean = true,
    override val allowCreate: Boolean = false,
    override val isVoid: Boolean = false,
    override val header: Header = DefaultHeader.empty()
) : CommandMessage<GivenInitialization>,
    NamedAggregate by aggregateId {
    /** The command body, which is the GivenInitialization marker object. */
    override val body: GivenInitialization = GivenInitialization

    /** The aggregate version (null for initialization commands). */
    override val aggregateVersion: Int? = null

    /** The name of this command class. */
    override val name: String = GivenInitializationCommand::class.simpleName!!

    /** The timestamp when this command was created. */
    override val createTime: Long = System.currentTimeMillis()

    /**
     * Creates a copy of this command with a new header.
     *
     * This method ensures that mutable header state is properly copied
     * to prevent unintended sharing between command instances.
     *
     * @return a new command instance with copied header
     */
    override fun copy(): CommandMessage<GivenInitialization> = copy(header = header.copy())
}
