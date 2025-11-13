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
import me.ahoo.wow.api.modeling.DeletedCapable
import me.ahoo.wow.api.modeling.EventIdCapable
import me.ahoo.wow.api.modeling.EventTimeCapable
import me.ahoo.wow.api.modeling.FirstEventTimeCapable
import me.ahoo.wow.api.modeling.FirstOperatorCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OperatorCapable
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SnapshotTimeCapable
import me.ahoo.wow.api.modeling.StateCapable
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.naming.Materialized

/**
 * Interface for materialized snapshots that contain state data and metadata.
 *
 * A materialized snapshot represents the current state of an aggregate at a specific point in time,
 * including all necessary metadata for tracking and versioning. This interface provides a generic
 * way to work with snapshots of different state types.
 *
 * @param SOURCE The concrete type of the snapshot implementation.
 * @param S The type of the state data contained in the snapshot.
 */
interface IMaterializedSnapshot<SOURCE : IMaterializedSnapshot<SOURCE, S>, S : Any> :
    Materialized,
    StateCapable<S> {
    /**
     * Creates a new snapshot with the specified state.
     *
     * This method allows creating a modified copy of the snapshot with different state data,
     * while preserving all other metadata.
     *
     * @param state The new state data for the snapshot.
     * @return A new snapshot instance with the updated state.
     */
    fun withState(state: S): SOURCE
}

/**
 * Data class representing a complete materialized snapshot of an aggregate's state.
 *
 * This class contains all the information about an aggregate's state at a specific point in time,
 * including metadata about the aggregate, versioning information, event tracking, and the actual state data.
 * It implements multiple interfaces to provide comprehensive access to all snapshot properties.
 *
 * @param S The type of the state data.
 * @property contextName The name of the context this aggregate belongs to.
 * @property aggregateName The name of the aggregate.
 * @property tenantId The identifier of the tenant that owns this aggregate.
 * @property ownerId The identifier of the owner of this aggregate. Defaults to the default owner ID.
 * @property aggregateId The unique identifier of this aggregate instance.
 * @property version The version number of this snapshot.
 * @property eventId The identifier of the last event that led to this snapshot.
 * @property firstOperator The identifier of the first operator who performed an operation on this aggregate.
 * @property operator The identifier of the last operator who performed an operation on this aggregate.
 * @property firstEventTime The timestamp of the first event for this aggregate.
 * @property eventTime The timestamp of the last event for this aggregate.
 * @property state The actual state data of the aggregate.
 * @property snapshotTime The timestamp when this snapshot was created.
 * @property deleted Whether this aggregate has been marked as deleted.
 */
data class MaterializedSnapshot<S : Any>(
    override val contextName: String,
    override val aggregateName: String,
    override val tenantId: String,
    override val ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    val aggregateId: String,
    override val version: Int,
    override val eventId: String,
    override val firstOperator: String,
    override val operator: String,
    override val firstEventTime: Long,
    override val eventTime: Long,
    override val state: S,
    override val snapshotTime: Long,
    override val deleted: Boolean
) : IMaterializedSnapshot<MaterializedSnapshot<S>, S>,
    NamedAggregate,
    TenantId,
    OwnerId,
    Version,
    EventIdCapable,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    SnapshotTimeCapable,
    DeletedCapable {
    /**
     * Creates a new MaterializedSnapshot with the specified state.
     *
     * @param state The new state data for the snapshot.
     * @return A new MaterializedSnapshot with the updated state.
     */
    override fun withState(state: S): MaterializedSnapshot<S> = copy(state = state)
}
