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
 * This data class implements multiple interfaces to provide version, materialization, first event time, and state information.
 *
 * @param version The version of the snapshot, used to indicate the version of the state.
 * @param firstEventTime The timestamp of the first event, used to record when the state was first changed.
 * @param state The current state, with a generic type.
 */
data class SlimMaterializedSnapshot<S : Any>(
    override val version: Int,
    override val firstEventTime: Long,
    override val state: S
) : Version, Materialized, FirstEventTimeCapable, StateCapable<S>

/**
 * Converts a materialized snapshot into a simplified snapshot form.
 * This function is used to transform a full snapshot into a simplified snapshot with transformed state.
 *
 * @param S The state type of the original snapshot.
 * @param D The state type of the transformed snapshot.
 * @param materialize A function that transforms the original state into a new state.
 * @return Returns a transformed simplified materialized snapshot.
 */
fun <S : Any, D : Any> MaterializedSnapshot<S>.toSlim(materialize: (S) -> D): SlimMaterializedSnapshot<D> {
    return SlimMaterializedSnapshot(
        version = version,
        firstEventTime = firstEventTime,
        state = materialize(state)
    )
}
