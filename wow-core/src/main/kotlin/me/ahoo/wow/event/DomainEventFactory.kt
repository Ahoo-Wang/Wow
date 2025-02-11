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
import me.ahoo.wow.event.annotation.toEventMetadata
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.aggregateId

@Suppress("LongParameterList")
fun <T : Any> T.toDomainEvent(
    aggregateId: AggregateId,
    ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    commandId: String,
    id: String = generateGlobalId(),
    version: Int = Version.INITIAL_VERSION,
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
        commandId = commandId,
        name = metadata.name,
        sequence = sequence,
        isLast = isLast,
        header = header,
        body = this,
        createTime = createTime,
    )
}

@Suppress("LongParameterList")
fun <T : Any> T.toDomainEvent(
    aggregateId: String,
    tenantId: String,
    ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    commandId: String,
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
        commandId = commandId,
        name = metadata.name,
        sequence = sequence,
        isLast = isLast,
        header = header,
        body = this,
        createTime = createTime,
    )
}
