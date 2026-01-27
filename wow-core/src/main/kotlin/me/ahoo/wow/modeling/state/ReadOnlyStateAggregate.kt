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
package me.ahoo.wow.modeling.state

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.DeletedCapable
import me.ahoo.wow.api.modeling.EventIdCapable
import me.ahoo.wow.api.modeling.EventTimeCapable
import me.ahoo.wow.api.modeling.FirstEventTimeCapable
import me.ahoo.wow.api.modeling.FirstOperatorCapable
import me.ahoo.wow.api.modeling.OperatorCapable
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.StateCapable

/**
 * Read-only interface for state aggregates that support read operations only.
 *
 * This interface represents a state aggregate that has been loaded from a snapshot or event store
 * and is intended for read-only operations. It provides access to all aggregate state and metadata
 * without supporting command processing or state modification.
 *
 * Key characteristics:
 * - Supports read operations for querying aggregate state
 * - Provides access to version, timing, and operator information
 * - Does not support command processing or event sourcing
 * - Can be converted to a writable [StateAggregate] when needed
 *
 * Implementations should ensure that:
 * - State cannot be modified through this interface
 * - All read operations return consistent, accurate data
 * - Version information reflects the current aggregate state
 *
 * @param S The type of the state data held by this aggregate.
 *
 * @see StateAggregate for the writable counterpart
 * @see ReadOnlyStateAggregateAware for state objects that can be aware of their read-only aggregate
 *
 * @since 1.0.0
 */
interface ReadOnlyStateAggregate<S : Any> :
    AggregateIdCapable,
    StateCapable<S>,
    OwnerId,
    SpaceIdCapable,
    Version,
    FirstOperatorCapable,
    OperatorCapable,
    FirstEventTimeCapable,
    EventTimeCapable,
    EventIdCapable,
    DeletedCapable {
    /**
     * The unique identifier of this aggregate.
     *
     * This provides access to both the aggregate ID string and its associated tenant context.
     */
    override val aggregateId: AggregateId

    /**
     * The current version of this aggregate's state.
     *
     * Used for generating domain event version numbers during state reconstruction.
     * The version starts at [Version.UNINITIALIZED_VERSION] and increments with each event applied.
     */
    override val version: Int

    /**
     * The expected version for the next event to be applied.
     *
     * This is a convenience property that returns [version] + 1, representing
     * the version number that the next sourced event should carry.
     *
     * @return The next version number to assign to an event.
     */
    val expectedNextVersion: Int
        get() = version + 1
}
