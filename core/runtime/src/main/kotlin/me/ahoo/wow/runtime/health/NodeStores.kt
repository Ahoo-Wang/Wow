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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.subscription.LeaseStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The stores a node calls, each with its [StoreActivity] (review R3.5-3): every context's event log, one per log
 * group (decision D177), and its lease store, which the node's ownerships and topic subscriptions call. The node
 * [track]s each context's backends once, as it opens them, so everything it runs calls them through the record.
 */
@InternalWowApi
class NodeStores internal constructor(private val nanos: () -> Long = System::nanoTime) {
    private val logStores = CopyOnWriteArrayList<LogStore>()
    private val leaseStores = CopyOnWriteArrayList<LeaseStoreOf>()

    /** Each event log of the node: its context, the store instance its log group is on, and what was seen of it. */
    val logs: List<LogStore> get() = logStores

    /** Each context's lease store and what was seen of it. */
    val leases: List<LeaseStoreOf> get() = leaseStores

    /** [backends] of [context], their logs and lease store recorded here. */
    internal fun track(context: String, backends: ContextBackends): ContextBackends {
        val activity = StoreActivity(StoreActivity.LEASES, context, nanos)
        val leases = trackedLeases(backends.leases, activity)
        leaseStores += LeaseStoreOf(context, leases, activity)
        return backends.copyWith(
            log = track(context, backends.store, backends.log, default = true),
            leases = leases,
            groups = backends.groups.map { group ->
                group.copyWith(log = track(context, group.store, group.log, default = false))
            },
        )
    }

    private fun track(context: String, store: String, log: EventLog, default: Boolean): EventLog {
        val activity = StoreActivity(StoreActivity.LOG, if (default) context else "$context#$store", nanos)
        val tracked = trackedLog(log, activity)
        logStores += LogStore(context, store, tracked, activity)
        return tracked
    }

    /** One event log of [context], on the store instance [store], called through the record of its [activity]. */
    @InternalWowApi
    class LogStore internal constructor(
        val context: String,
        val store: String,
        internal val log: EventLog,
        val activity: StoreActivity,
    )

    /** The lease store of [context], called through the record of its [activity]. */
    @InternalWowApi
    class LeaseStoreOf internal constructor(
        val context: String,
        internal val leases: LeaseStore,
        val activity: StoreActivity,
    )
}
