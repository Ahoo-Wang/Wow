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

package me.ahoo.wow.runtime.snapshot

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.snapshot.RebuildSnapshot
import me.ahoo.wow.api.snapshot.SnapshotRebuildRequested
import me.ahoo.wow.api.snapshot.SnapshotRebuilds
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.FrameworkCodes

/** The state of one rebuild instance: how many rebuild requests it took. */
@InternalWowApi
class RebuildState {
    var requests: Long = 0
}

/**
 * The built-in aggregate that takes a context's snapshot rebuild requests (decision D172): `<context>.snapshot`, one
 * instance per snapshotted aggregate type in each tenant, whose id is the aggregate type's name. It belongs to its
 * tenant (no owner), has no lifecycle commands and no snapshots of its own, and decides `wow.snapshot.Rebuild(ids)`:
 * it commits `wow.SnapshotRebuildRequested(ids)`, each id once, which the built-in rebuild subscription
 * ([SnapshotMaintenance.REBUILD_SUBSCRIPTION]) carries out. A request to an instance that is no snapshotted aggregate
 * type of the context is rejected with [FrameworkCodes.INVALID].
 */
@InternalWowApi
object SnapshotRebuildModel {
    /** Whether [model] is a context's built-in rebuild aggregate. */
    @JvmStatic
    fun isRebuild(model: AggregateModel<*>): Boolean = model.stateType == RebuildState::class.java

    /** The model of [context]'s rebuild aggregate, taking the requests of its snapshotted [aggregates], by name. */
    @JvmStatic
    fun model(context: String, aggregates: Set<String>): AggregateModel<RebuildState> =
        aggregate<RebuildState>(context, SnapshotRebuilds.AGGREGATE) {
            initial { RebuildState() }
            lifecycle()
            ownership(Ownership.TENANT)
            decide<RebuildSnapshot>(Creation.ALLOWED, SnapshotRebuilds.REBUILD) { _, body ->
                val aggregate = command.ref.id
                if (aggregate in aggregates) {
                    Decision.events(SnapshotRebuildRequested(body.ids.distinct()))
                } else {
                    Decision.reject(
                        FrameworkCodes.INVALID,
                        "$context.$aggregate keeps no snapshots to rebuild; the context's are ${aggregates.sorted()}.",
                    )
                }
            }
            evolve<SnapshotRebuildRequested>(SnapshotRebuilds.REBUILD_REQUESTED) { requests++ }
        }
}
