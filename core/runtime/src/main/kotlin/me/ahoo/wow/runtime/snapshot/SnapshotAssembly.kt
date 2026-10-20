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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.state.StateViews
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.subscription.LeaseStore

/**
 * The snapshot parts of one bounded [context] on a node (decision D172), as the node assembles them: the [snapshotted]
 * models written to the [targets], a [SnapshotSubscription] per target store, the [readers] they are of the context's
 * state views, and the context's [SnapshotMaintenance], each over the context's [kernel], [log] and [codec]. Nothing
 * when the context has no targets.
 */
internal class SnapshotAssembly(
    private val context: String,
    private val targets: SnapshotTargets?,
    val snapshotted: List<AggregateModel<*>>,
    private val kernel: Kernel,
    private val log: EventLog,
    private val codec: EventCodec,
) {
    /** The aggregate types each target store keeps. */
    private val stores: Map<String, Set<AggregateType>> = targets?.storesOf(snapshotted.map { it.type }).orEmpty()

    /** The state view readers of the snapshot subscriptions: each target store's, of the types it keeps. */
    val readers: Map<String, Set<AggregateType>> =
        stores.entries.associate { (store, types) -> targets!!.subscriptionOf(store) to types }

    /** The subscription of each target store, reading [views], writing at most [writeBatch] snapshots at a time. */
    fun subscriptions(
        views: StateViews,
        writeBatch: Int = SnapshotOptions.DEFAULT_WRITE_BATCH
    ): List<SnapshotSubscription> =
        stores.map { (store, types) ->
            SnapshotSubscription(
                context,
                snapshotted.filter { it.type in types },
                targets!!.stores.getValue(store),
                kernel,
                log,
                codec,
                views,
                name = targets.subscriptionOf(store),
                answers = { type -> targets.placementOf(type).primary == store },
                writeBatch = writeBatch,
            )
        }

    /** The context's maintenance, in the lease [group] of the context, when it keeps snapshots. */
    fun maintenance(
        group: String,
        node: String,
        leases: LeaseStore,
        progress: List<LogProgress>,
        options: SnapshotOptions,
        address: () -> String,
    ): SnapshotMaintenance? = targets?.takeIf { snapshotted.isNotEmpty() }?.let {
        SnapshotMaintenance(context, group, node, snapshotted, it, kernel, codec, log, leases, progress, options, address)
    }
}
