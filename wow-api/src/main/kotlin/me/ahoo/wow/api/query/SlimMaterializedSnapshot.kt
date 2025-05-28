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
import me.ahoo.wow.api.modeling.FirstEventTimeCapable
import me.ahoo.wow.api.modeling.StateCapable
import me.ahoo.wow.api.naming.Materialized

/**
 * Represents a simplified materialized snapshot with generic state.
 * A materialized snapshot is a point-in-time representation of an object's state, including its ownership, version, state, and time-related information.
 * This class implements several interfaces to provide information about ownership ID, version, materialization, event time, state, and deletion status.
 *
 * @param version The version number of the snapshot, indicating the sequence or revision of the snapshot.
 * @param firstEventTime The timestamp of the first event, marking when the first change occurred to the state.
 * @param eventTime The timestamp of the last event, indicating the last time the state was updated.
 * @param state The state object, with generic type S, representing the specific state of the snapshot.
 * @param deleted A boolean flag indicating whether the snapshot is considered deleted.
 *
 * The class inherits from several interfaces to ensure the snapshot can provide necessary information in a standardized way:
 * - Version provides version information.
 * - Materialized indicates that the snapshot is a materialized representation of the state.
 * - FirstEventTimeCapable allows querying the timestamp of the first event.
 * - EventTimeCapable allows querying the timestamp of the last event.
 * - StateCapable provides access to the state object.
 * - DeletedCapable indicates whether the snapshot is deleted.
 */
data class SlimMaterializedSnapshot<S : Any>(
    override val version: Int,
    override val firstEventTime: Long,
    override val state: S
) : Version, Materialized, FirstEventTimeCapable, StateCapable<S>

fun <S : Any, D : Any> MaterializedSnapshot<S>.toSlim(materialize: (S) -> D): SlimMaterializedSnapshot<D> {
    return SlimMaterializedSnapshot(
        version = version,
        firstEventTime = firstEventTime,
        state = materialize(state)
    )
}
