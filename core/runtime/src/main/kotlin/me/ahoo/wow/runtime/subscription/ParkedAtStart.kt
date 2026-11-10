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

package me.ahoo.wow.runtime.subscription

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import java.util.concurrent.ConcurrentHashMap

/**
 * The aggregates a [SubscriptionRuntime]'s subscriptions hold back in the partitions it starts, with the ordinal each is
 * parked from: the ledger's entries of those partitions ([FailureLedger.due] at [ParkedAggregate.NEVER]).
 *
 * Of a source whose partitions grow ([EntrySource.partitionsGrow]), also the entries parked in another partition than
 * the one their aggregate's key maps to now: the topic gained partitions while they were parked. The owner of the
 * partition the key maps to now holds back their later entries there, and re-homes the entry to it ([rehomed],
 * [FailureLedger.repark] from the partition it left), so its due scan, an operator's request and a skip reach the
 * owner that holds them; the entry cannot be retried, only skipped. Those entries are looked for once per subscription
 * in one run of the runtime, a start or a join again after the partitions grew: one paged listing of the
 * subscription's ledger, the first time a partition of it starts, and one [EntrySource.partitionOf] per entry, never
 * per entry read. A run starts and stops partitions, but the number of partitions its source maps keys to does not
 * change during it, so no entry parked during the run is in another partition than its key's.
 *
 * The run owns each listing: it runs in the run's [scope], whichever start asks first, so a start that fails for a
 * reason of its own cancels only its own wait, and the other starts get the listing or its own failure. A listing that
 * fails is taken out, and the next start lists again.
 */
internal class ParkedAtStart(
    private val source: EntrySource,
    private val ledger: FailureLedger,
    private val scope: CoroutineScope,
) {
    /** Per subscription, its entries parked in another partition than its key's now, with that partition. */
    private val moved = ConcurrentHashMap<String, Deferred<List<Moved>>>()

    private class Moved(val entry: ParkedAggregate, val now: Int)

    /** [subscription]'s parked aggregates in [partitions], by partition, with the ordinal each is parked from. */
    suspend fun parkedIn(subscription: Subscription, partitions: Collection<Int>): Map<Int, Map<AggregateRef, Long>> {
        val parked = HashMap<Int, HashMap<AggregateRef, Long>>()
        var after: AggregateRef? = null
        do {
            val page = ledger.due(subscription.name, partitions, ParkedAggregate.NEVER, after)
            page.forEach { parked.getOrPut(it.partition) { HashMap() }[it.ref] = source.ordinalOf(it) }
            after = page.lastOrNull()?.ref
        } while (page.size == FailureLedger.DEFAULT_PAGE)
        if (source.partitionsGrow) {
            movedOf(subscription.name).filter { it.now in partitions }.forEach { moved ->
                val entry = rehomed(checkNotNull(source.topic), moved.entry, moved.now)
                // A false repark: resolved, or re-homed by another start, since the listing.
                if (ledger.repark(entry, from = moved.entry.partition)) {
                    logger.warn { "${subscription.name} re-homes ${entry.ref} to partition ${moved.now}: ${entry.reason}" }
                    parked.getOrPut(moved.now) { HashMap() }[entry.ref] = source.ordinalOf(entry)
                }
            }
        }
        return parked
    }

    /** [subscription]'s moved entries, listed once for this run; a listing that fails is listed again by the next start. */
    private suspend fun movedOf(subscription: String): List<Moved> {
        val listing = moved.computeIfAbsent(subscription) { name ->
            scope.async(start = CoroutineStart.LAZY) { listMoved(name) }
        }
        // Registered before the wait starts the listing: a failed one is always taken out.
        listing.invokeOnCompletion { failure -> if (failure != null) moved.remove(subscription, listing) }
        return listing.await()
    }

    private suspend fun listMoved(subscription: String): List<Moved> = buildList {
        var after: AggregateRef? = null
        do {
            val page = ledger.list(subscription, after)
            page.forEach { entry ->
                val now = source.partitionOf(entry.ref)
                if (now != entry.partition) {
                    add(Moved(entry, now))
                }
            }
            after = page.lastOrNull()?.ref
        } while (page.size == FailureLedger.DEFAULT_PAGE)
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
