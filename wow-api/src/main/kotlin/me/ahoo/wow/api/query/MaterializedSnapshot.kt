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

interface IMaterializedSnapshot<SOURCE : IMaterializedSnapshot<SOURCE, S>, S : Any> : Materialized, StateCapable<S> {

    fun withState(state: S): SOURCE
}

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
    override fun withState(state: S): MaterializedSnapshot<S> {
        return copy(state = state)
    }
}
