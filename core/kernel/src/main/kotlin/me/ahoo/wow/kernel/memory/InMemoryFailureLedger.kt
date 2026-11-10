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

package me.ahoo.wow.kernel.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.amended
import me.ahoo.wow.spi.ledger.dueIn
import me.ahoo.wow.spi.ledger.placedAs
import me.ahoo.wow.spi.subscription.SubscriptionId
import java.util.TreeMap

/**
 * A [FailureLedger] in memory, for tests and samples, keyed by subscription ([SubscriptionId]) and aggregate. Every
 * operation is serialized by one lock.
 */
@InternalWowApi
class InMemoryFailureLedger : FailureLedger {
    private val lock = Mutex()
    private val subscriptions = HashMap<SubscriptionId, TreeMap<AggregateRef, ParkedAggregate>>()

    override suspend fun park(entry: ParkedAggregate): Boolean = lock.withLock {
        val parked = subscriptions.getOrPut(entry.subscription) { TreeMap(REF_ORDER) }
        val existing = parked[entry.ref]
        if (existing != null && existing.fromVersion <= entry.fromVersion) {
            return false
        }
        parked[entry.ref] = existing?.placedAs(entry) ?: entry.copyWith(revision = 0)
        true
    }

    override suspend fun repark(entry: ParkedAggregate, from: Int): Boolean = lock.withLock {
        val parked = subscriptions[entry.subscription] ?: return false
        val existing = parked[entry.ref]?.takeIf { it.partition == from } ?: return false
        parked[entry.ref] = existing.placedAs(entry)
        true
    }

    override suspend fun amend(
        subscription: SubscriptionId,
        ref: AggregateRef,
        amendment: ParkedAmendment,
    ): ParkedAggregate? = lock.withLock {
        val parked = subscriptions[subscription] ?: return null
        val amended = parked[ref]?.amended(amendment) ?: return null
        parked[ref] = amended
        amended
    }

    override suspend fun parked(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? =
        lock.withLock { subscriptions[subscription]?.get(ref) }

    override suspend fun list(
        subscription: SubscriptionId,
        after: AggregateRef?,
        limit: Int,
        tenantId: String?,
    ): List<ParkedAggregate> = page(subscription, after, limit) { tenantId == null || it.ref.tenantId == tenantId }

    override suspend fun due(
        subscription: SubscriptionId,
        partitions: Collection<Int>,
        now: Long,
        after: AggregateRef?,
        limit: Int,
    ): List<ParkedAggregate> = page(subscription, after, limit) { it.dueIn(partitions, now) }

    override suspend fun count(subscription: SubscriptionId, max: Int): Int {
        require(max >= 1) { "At least one parked entry is counted." }
        return lock.withLock { minOf(subscriptions[subscription]?.size ?: 0, max) }
    }

    /** Up to [limit] of [subscription]'s entries after [after] that [admits], in the ledger's order. */
    private suspend fun page(
        subscription: SubscriptionId,
        after: AggregateRef?,
        limit: Int,
        admits: (ParkedAggregate) -> Boolean,
    ): List<ParkedAggregate> {
        require(limit >= 1) { "A page holds at least one entry." }
        return lock.withLock {
            val parked = subscriptions[subscription] ?: return emptyList()
            val page = if (after == null) parked else parked.tailMap(after, false)
            page.values.asSequence().filter(admits).take(limit).toList()
        }
    }

    override suspend fun resolveAnywhere(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? =
        lock.withLock { subscriptions[subscription]?.remove(ref) }

    override suspend fun resolve(subscription: SubscriptionId, ref: AggregateRef, partition: Int): ParkedAggregate? =
        lock.withLock {
            val parked = subscriptions[subscription] ?: return null
            if (parked[ref]?.partition == partition) parked.remove(ref) else null
        }

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
