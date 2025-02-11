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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.naming.Materialized

data class MaterializedSnapshot<S : Any>(
    override val contextName: String,
    override val aggregateName: String,
    override val tenantId: String,
    override val ownerId: String = OwnerId.DEFAULT_OWNER_ID,
    val aggregateId: String,
    override val version: Int,
    val eventId: String,
    val firstOperator: String,
    val operator: String,
    val firstEventTime: Long,
    val eventTime: Long,
    val state: S,
    val snapshotTime: Long,
    val deleted: Boolean
) : NamedAggregate, TenantId, OwnerId, Version, Materialized
