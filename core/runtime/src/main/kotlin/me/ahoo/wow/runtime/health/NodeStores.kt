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

package me.ahoo.wow.runtime.health

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.subscription.LeaseStore

/**
 * The stores a node calls, each with its [StoreActivity]: every context's event log, one per log
 * group, and its lease store, which the node's ownerships and topic subscriptions call. The node
 * [track]s each context's backends once, as it opens them, so everything it runs calls them through the record; the
 * health model finds the records in the node's contexts ([of]).
 */
internal class NodeStores(
    /** Each event log of the node, called through the record of its activity. */
    val logs: List<LogStore>,
    /** Each context's lease store, called through the record of its activity. */
    val leases: List<LeaseStoreOf>,
    /** Each store instance placed on the node, probed for its health, each once. */
    val placed: List<PlacedStore> = emptyList(),
) {
    /**
     * A store instance placed on the node, probed through the record of its [activity]: one on its [writePath] (a log
     * or a type's primary snapshots are on it) decides whether the node is ready; any other is shown only.
     */
    class PlacedStore(val store: Store, val writePath: Boolean, val activity: StoreActivity)

    /** One event log of [context], on the store instance [store], called through the record of its [activity]. */
    class LogStore(val context: String, val store: String, val log: EventLog, val activity: StoreActivity)

    /** The lease store of [context], called through the record of its [activity]. */
    class LeaseStoreOf(val context: String, val leases: LeaseStore, val activity: StoreActivity)

    companion object {
        /**
         * [backends] of [context], their logs and lease store called through records of their calls, each log's
         * append latency in its store's [pressures].
         */
        fun track(
            context: String,
            backends: ContextBackends,
            pressures: WritePressures = WritePressures(),
        ): ContextBackends {
            val log = trackedLog(backends.log, StoreActivity(StoreActivity.LOG, context), pressures.of(backends.store))
            val groups = backends.groups.map { group ->
                group.copyWith(
                    log = trackedLog(
                        group.log,
                        StoreActivity(StoreActivity.LOG, "$context#${group.store}"),
                        pressures.of(group.store),
                    ),
                )
            }
            // A one-round-trip read is a call of the log of its type's group.
            fun activityOf(type: AggregateType?): StoreActivity =
                activityOf(groups.firstOrNull { type != null && type in it.types }?.log ?: log)!!
            return backends.copyWith(
                log = log,
                leases = trackedLeases(backends.leases, StoreActivity(StoreActivity.LEASES, context)),
                groups = groups,
                snapshotTails = backends.snapshotTails.map { type, reader -> trackedTails(reader, activityOf(type)) },
            )
        }

        /** The tracked stores of [node]'s contexts. */
        fun of(node: ServiceNode): NodeStores {
            val contexts = node.contexts.values
            val logs = contexts.flatMap { context ->
                val backends = context.backends
                (listOf(backends.store to backends.log) + backends.groups.map { it.store to it.log })
                    .mapNotNull { (store, log) ->
                        activityOf(log)?.let { LogStore(context.context, store, log, it) }
                    }
            }
            val leases = contexts.mapNotNull { context ->
                activityOf(context.backends.leases)?.let { LeaseStoreOf(context.context, context.backends.leases, it) }
            }
            val writePath = contexts.flatMap { it.backends.stores.writePath }.toSet()
            val placed = contexts.flatMap { it.backends.stores.all }.distinct().map { store ->
                PlacedStore(store, store in writePath, StoreActivity(StoreActivity.STORE, store.name))
            }
            return NodeStores(logs, leases, placed)
        }
    }
}
