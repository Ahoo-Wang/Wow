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

package me.ahoo.wow.modeling

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.modeling.equalTo
import me.ahoo.wow.api.modeling.hash
import me.ahoo.wow.id.generateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata

data class DefaultAggregateId(
    override val namedAggregate: NamedAggregate,
    override val id: String,
    override val tenantId: String = TenantId.DEFAULT_TENANT_ID
) : AggregateId {

    @Transient
    private val hashCode: Int = hash()
    override fun equals(other: Any?): Boolean = equalTo(other)

    override fun hashCode(): Int = hashCode
    override fun toString(): String {
        return "AggregateId(contextName=$contextName, aggregateName=$aggregateName, tenantId=$tenantId, id=$id)"
    }
}

fun NamedAggregate.aggregateId(
    id: String = generateId(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID
) =
    DefaultAggregateId(
        namedAggregate = materialize(),
        id = id,
        tenantId = tenantId,
    )

fun AggregateMetadata<*, *>.aggregateId(
    id: String = generateId(),
    tenantId: String = staticTenantId ?: TenantId.DEFAULT_TENANT_ID
) = namedAggregate.aggregateId(id, tenantId)
