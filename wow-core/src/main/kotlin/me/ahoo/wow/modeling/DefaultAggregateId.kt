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

/**
 * Represents a default implementation of [AggregateId], which uniquely identifies an aggregate instance within a bounded context.
 *
 * This data class provides a concrete implementation of the AggregateId interface, encapsulating the necessary
 * information to identify an aggregate: the named aggregate, the instance ID, and the tenant ID.
 *
 * @property namedAggregate The [NamedAggregate] that this ID belongs to, defining the context and aggregate names.
 * @property id The unique identifier for the aggregate instance as a string.
 * @property tenantId The tenant identifier as a string, defaults to [TenantId.DEFAULT_TENANT_ID] for single-tenant scenarios.
 *
 * @constructor Creates a new DefaultAggregateId with the specified named aggregate, ID, and optional tenant ID.
 */
data class DefaultAggregateId(
    override val namedAggregate: NamedAggregate,
    override val id: String,
    override val tenantId: String = TenantId.DEFAULT_TENANT_ID
) : AggregateId {
    @Transient
    private val hashCode: Int = hash()

    override fun equals(other: Any?): Boolean = equalTo(other)

    override fun hashCode(): Int = hashCode

    override fun toString(): String = "AggregateId(contextName=$contextName, aggregateName=$aggregateName, tenantId=$tenantId, id=$id)"
}

/**
 * Creates an [AggregateId] for this [NamedAggregate] with the specified parameters.
 *
 * This extension function provides a convenient way to generate an aggregate ID for a named aggregate,
 * automatically materializing the named aggregate if necessary.
 *
 * @param id The unique identifier for the aggregate instance. Defaults to a generated ID using [generateId].
 * @param tenantId The tenant identifier. Defaults to [TenantId.DEFAULT_TENANT_ID].
 * @return A new [DefaultAggregateId] instance.
 *
 * @see generateId
 * @see NamedAggregate.materialize
 */
fun NamedAggregate.aggregateId(
    id: String = generateId(),
    tenantId: String = TenantId.DEFAULT_TENANT_ID
) = DefaultAggregateId(
    namedAggregate = materialize(),
    id = id,
    tenantId = tenantId,
)

/**
 * Creates an [AggregateId] for this [AggregateMetadata] with the specified parameters.
 *
 * This extension function delegates to the named aggregate's aggregateId function, using the static tenant ID
 * from the metadata if available, otherwise defaulting to the standard tenant ID.
 *
 * @param id The unique identifier for the aggregate instance. Defaults to a generated ID using [generateId].
 * @param tenantId The tenant identifier. Defaults to the static tenant ID from this metadata or [TenantId.DEFAULT_TENANT_ID].
 * @return A new [AggregateId] instance created by the named aggregate.
 *
 * @see NamedAggregate.aggregateId
 * @see AggregateMetadata.staticTenantId
 */
fun AggregateMetadata<*, *>.aggregateId(
    id: String = generateId(),
    tenantId: String = staticTenantId ?: TenantId.DEFAULT_TENANT_ID
) = namedAggregate.aggregateId(id, tenantId)
