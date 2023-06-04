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
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.annotation.asCommandMetadata
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.naming.annotation.asName

data class SimpleCommandMessage<C : Any>(
    override val id: String = GlobalIdGenerator.generateAsString(),
    override val header: Header = DefaultHeader.empty(),
    override val body: C,
    override val aggregateId: AggregateId,
    override val requestId: String = id,
    override val aggregateVersion: Int? = null,
    override val name: String = body.javaClass.asName(),
    override val isCreate: Boolean = false,
    override val allowCreate: Boolean = false,
    override val createTime: Long = System.currentTimeMillis()
) : CommandMessage<C>, NamedAggregate by aggregateId

@Suppress("LongParameterList")
fun <C : Any> C.asCommandMessage(
    id: String = GlobalIdGenerator.generateAsString(),
    requestId: String? = null,
    aggregateId: String? = null,
    tenantId: String? = null,
    aggregateVersion: Int? = null,
    namedAggregate: NamedAggregate? = null,
    header: Header = DefaultHeader.empty(),
    createTime: Long = System.currentTimeMillis()
): CommandMessage<C> {
    val metadata = javaClass.asCommandMetadata()
    val commandNamedAggregate = namedAggregate ?: metadata.namedAggregateGetter?.getNamedAggregate(this)
    requireNotNull(commandNamedAggregate) {
        "The command[$javaClass] must be associated with a named aggregate!"
    }
    val commandAggregateId = metadata.aggregateIdGetter?.get(this) ?: aggregateId ?: commandNamedAggregate.generateId()
    val commandTenantId = metadata.tenantIdGetter?.get(this) ?: tenantId ?: TenantId.DEFAULT_TENANT_ID
    val targetAggregateId = commandNamedAggregate.asAggregateId(id = commandAggregateId, tenantId = commandTenantId)
    val expectedAggregateVersion = if (metadata.isCreate) {
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
        aggregateVersion = expectedAggregateVersion,
        name = metadata.name,
        isCreate = metadata.isCreate,
        allowCreate = metadata.allowCreate,
    )
}
