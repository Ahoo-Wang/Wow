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

package me.ahoo.wow.kernel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import java.util.TreeMap

/** A [FailureLedger] in memory, for tests and samples. Every operation is serialized by one lock. */
class InMemoryFailureLedger : FailureLedger {
    private val lock = Mutex()
    private val subscriptions = HashMap<String, TreeMap<AggregateRef, ParkedAggregate>>()

    override suspend fun park(entry: ParkedAggregate): Boolean = lock.withLock {
        val parked = subscriptions.getOrPut(entry.subscription) { TreeMap(REF_ORDER) }
        val existing = parked[entry.ref]
        if (existing != null && existing.fromVersion <= entry.fromVersion) {
            return false
        }
        parked[entry.ref] = entry
        true
    }

    override suspend fun repark(entry: ParkedAggregate): Boolean = lock.withLock {
        val parked = subscriptions[entry.subscription] ?: return false
        if (entry.ref !in parked) {
            return false
        }
        parked[entry.ref] = entry
        true
    }

    override suspend fun parked(subscription: String, ref: AggregateRef): ParkedAggregate? =
        lock.withLock { subscriptions[subscription]?.get(ref) }

    override suspend fun list(subscription: String, after: AggregateRef?, limit: Int): List<ParkedAggregate> {
        require(limit >= 1) { "A page holds at least one entry." }
        return lock.withLock {
            val parked = subscriptions[subscription] ?: return emptyList()
            val page = if (after == null) parked else parked.tailMap(after, false)
            page.values.take(limit)
        }
    }

    override suspend fun resolve(subscription: String, ref: AggregateRef): ParkedAggregate? =
        lock.withLock { subscriptions[subscription]?.remove(ref) }

    private companion object {
        /** The ledger's aggregate order: context, aggregate name, tenant, id. */
        val REF_ORDER: Comparator<AggregateRef> = compareBy<AggregateRef>(
            { it.type.context },
            { it.type.name },
            { it.tenantId },
            { it.id },
        )
    }
}
