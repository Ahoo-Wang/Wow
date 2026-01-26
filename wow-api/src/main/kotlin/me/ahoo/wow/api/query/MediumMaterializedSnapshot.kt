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

package me.ahoo.wow.api.query

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.EventIdCapable
import me.ahoo.wow.api.modeling.EventTimeCapable
import me.ahoo.wow.api.modeling.FirstEventTimeCapable
import me.ahoo.wow.api.modeling.FirstOperatorCapable
import me.ahoo.wow.api.modeling.OperatorCapable
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.TenantId

/**
 * Represents a materialized snapshot for medium data, implementing multiple capabilities through inheritance.
 * This class is designed to be generic, capable of holding state data of any type.
 * Each snapshot corresponds to a specific version of the state within a tenant and owner context,
 * and records information such as event IDs and operation times to support tracing and auditing.
 *
 * @param tenantId Identifier for the tenant to which the snapshot belongs, used for multi-tenant differentiation.
 * @param ownerId Identifier for the owner of the snapshot, usually representing the creator or responsible person.
 * @param version The version number of the snapshot, used to track changes over time.
 * @param eventId The ID of the event that triggered the creation of this snapshot.
 * @param firstOperator The first operator who performed an operation on the state.
 * @param operator The operator who last performed an operation on the state.
 * @param firstEventTime The timestamp of the first event, marking the beginning of the state's history.
 * @param eventTime The timestamp of the last event, marking the last update time of the state.
 * @param state The actual state data, its type is generic, allowing for different types of state data.
 */
data class MediumMaterializedSnapshot<S : Any>(
    override val tenantId: String,
    override val ownerId: String,
    override val spaceId: SpaceId,
    override val version: Int,
    override val eventId: String,
    override val firstOperator: String,
    override val operator: String,
    override val firstEventTime: Long,
    override val eventTime: Long,
    override val state: S
) : IMaterializedSnapshot<MediumMaterializedSnapshot<S>, S>,
    TenantId,
    OwnerId,
    SpaceIdCapable,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable {
    override fun withState(state: S): MediumMaterializedSnapshot<S> {
        return copy(state = state)
    }
}

fun <S : Any, D : Any> MaterializedSnapshot<S>.toMedium(materialize: (S) -> D): MediumMaterializedSnapshot<D> {
    return MediumMaterializedSnapshot(
        tenantId = tenantId,
        ownerId = ownerId,
        spaceId = spaceId,
        version = version,
        eventId = eventId,
        firstOperator = firstOperator,
        operator = operator,
        firstEventTime = firstEventTime,
        eventTime = eventTime,
        state = materialize(state)
    )
}
