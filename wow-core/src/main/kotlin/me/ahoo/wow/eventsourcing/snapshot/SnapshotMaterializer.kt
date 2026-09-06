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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.MediumMaterializedSnapshot
import me.ahoo.wow.api.query.SmallMaterializedSnapshot
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate

fun <S : Any, D : Any> ReadOnlyStateAggregate<S>.toSmall(materialize: (S) -> D): SmallMaterializedSnapshot<D> {
    return SmallMaterializedSnapshot(
        version = version,
        firstEventTime = firstEventTime,
        state = materialize(state)
    )
}

fun <S : Any, D : Any> ReadOnlyStateAggregate<S>.toMedium(materialize: (S) -> D): MediumMaterializedSnapshot<D> {
    return MediumMaterializedSnapshot(
        tenantId = aggregateId.tenantId,
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

fun <S : Any, D : Any> Snapshot<S>.materialize(materialize: (S) -> D): MaterializedSnapshot<D> {
    return MaterializedSnapshot(
        contextName = aggregateId.contextName,
        aggregateName = aggregateId.aggregateName,
        tenantId = aggregateId.tenantId,
        ownerId = ownerId,
        aggregateId = aggregateId.id,
        version = version,
        eventId = eventId,
        firstOperator = firstOperator,
        operator = operator,
        firstEventTime = firstEventTime,
        eventTime = eventTime,
        state = materialize(state),
        snapshotTime = snapshotTime,
        deleted = deleted
    )
}
