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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId.Companion.orDefaultOwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable.Companion.orDefaultSpaceId
import me.ahoo.wow.api.modeling.TenantId.Companion.orDefaultTenantId
import me.ahoo.wow.command.annotation.commandMetadata
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.id.generateId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.propagate
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.ensureTraceId
import me.ahoo.wow.modeling.aggregateId

/**
 * Converts any object to a CommandMessage using reflection and metadata.
 *
 * This extension function analyzes the command object's class to extract metadata
 * and construct a properly configured CommandMessage. It uses command metadata
 * to determine aggregate targeting, property getters, and command characteristics.
 *
 * @param C the type of the command
 * @param id unique identifier for the command message (auto-generated if not provided)
 * @param requestId request identifier for tracking (defaults to id)
 * @param aggregateId target aggregate instance ID (optional, extracted from command if available)
 * @param tenantId tenant identifier (optional, extracted from command if available)
 * @param ownerId owner identifier (optional, extracted from command if available)
 * @param aggregateVersion expected aggregate version (optional, extracted from command if available)
 * @param namedAggregate named aggregate information (optional, extracted from command if available)
 * @param header message headers (default empty)
 * @param createTime creation timestamp (default current time)
 * @param upstream upstream domain event (optional)
 * @param ownerIdSameAsAggregateId whether owner ID should match aggregate ID
 * @return a properly configured CommandMessage
 * @throws IllegalArgumentException if no named aggregate can be determined
 * @see CommandMessage
 * @see commandMetadata
 */
@Suppress("LongParameterList")
fun <C : Any> C.toCommandMessage(
    id: String = generateGlobalId(),
    requestId: String? = null,
    aggregateId: String? = null,
    tenantId: String? = null,
    ownerId: String? = null,
    spaceId: SpaceId? = null,
    aggregateVersion: Int? = null,
    namedAggregate: NamedAggregate? = null,
    header: Header = DefaultHeader.empty(),
    createTime: Long = System.currentTimeMillis(),
    upstream: DomainEvent<*>? = null,
    ownerIdSameAsAggregateId: Boolean = false
): CommandMessage<C> {
    upstream?.let {
        header.propagate(it)
    }
    val metadata = javaClass.commandMetadata()
    val commandNamedAggregate = namedAggregate ?: metadata.namedAggregateGetter?.getNamedAggregate(this)
    requireNotNull(commandNamedAggregate) {
        "The command[$javaClass] must be associated with a named aggregate!"
    }
    val commandOwnerId = metadata.ownerIdGetter?.get(this) ?: ownerId
    val commandAggregateId =
        if (ownerIdSameAsAggregateId && commandOwnerId.isNullOrBlank().not()) {
            commandOwnerId
        } else {
            metadata.aggregateIdGetter?.get(this) ?: aggregateId ?: commandNamedAggregate.generateId()
        }

    val finalOwnerId =
        if (ownerIdSameAsAggregateId && commandOwnerId.isNullOrBlank()) {
            commandAggregateId
        } else {
            commandOwnerId
        }
    val commandTenantId = metadata.tenantIdGetter?.get(this) ?: tenantId.orDefaultTenantId()

    val targetAggregateId = commandNamedAggregate.aggregateId(id = commandAggregateId, tenantId = commandTenantId)
    val expectedAggregateVersion =
        if (metadata.isCreate) {
            Version.UNINITIALIZED_VERSION
        } else {
            metadata.aggregateVersionGetter?.get(this) ?: aggregateVersion
        }

    return SimpleCommandMessage(
        id = id,
        requestId = requestId ?: id,
        header = header,
        body = this,
        createTime = createTime,
        aggregateId = targetAggregateId,
        ownerId = finalOwnerId.orDefaultOwnerId(),
        spaceId = spaceId.orDefaultSpaceId(),
        aggregateVersion = expectedAggregateVersion,
        name = metadata.name,
        isCreate = metadata.isCreate,
        allowCreate = metadata.allowCreate,
        isVoid = metadata.isVoid,
    ).ensureTraceId()
}

/**
 * Converts a CommandBuilder to a CommandMessage.
 *
 * This extension function takes all the properties configured in the CommandBuilder
 * and creates a corresponding CommandMessage using the same logic as the command
 * object's toCommandMessage method.
 *
 * @param C the type of the command body
 * @return a CommandMessage with all builder properties applied
 * @see CommandBuilder
 * @see CommandMessage
 */
fun <C : Any> CommandBuilder.toCommandMessage(): CommandMessage<C> =
    this.bodyAs<C>().toCommandMessage(
        id = id,
        requestId = requestId,
        aggregateId = aggregateId,
        tenantId = tenantId,
        ownerId = ownerId,
        spaceId = spaceId,
        aggregateVersion = aggregateVersion,
        namedAggregate = namedAggregate,
        header = header,
        createTime = createTime,
        upstream = upstream,
        ownerIdSameAsAggregateId = ownerIdSameAsAggregateId,
    )
