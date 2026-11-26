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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.ledger.ParkedAggregate

/**
 * The versions a worker saw last of the aggregates it saw most recently, on a source whose aggregates' entries come
 * at every version ([EntrySource.contiguous]: a state topic, whose transport sink publishes every commit). An entry
 * above the version after the last one seen of its aggregate is a gap: a record the source did not deliver, which
 * the worker parks the aggregate at ([VersionGapException]) rather than handle the entry past it.
 *
 * It counts every entry the worker admits, taken or passed over, so a record of an event a subscription does not take
 * is no gap for it. It remembers [REMEMBERED] aggregates in memory, the least recently seen forgotten first: an
 * aggregate it does not remember (first seen since the worker started, or forgotten) is not checked, and its entry's
 * version is where the next one is checked from.
 */
internal class VersionGaps {
    private val seen = recentlySeen<Int>()

    /**
     * Called under the worker's monitor, for each entry it admits in the source's order, an undecodable one aside:
     * records [version] of [ref], and returns the version missing before it, null when none is missing or none can be
     * told. A copy (at or below the last version seen) changes nothing; an entry its aggregate's park [held] back is
     * counted, the retry of the park checking what comes after it.
     */
    fun admit(ref: AggregateRef, version: Int, held: Boolean): Int? {
        val last = seen[ref]
        if (last != null && version <= last) {
            return null
        }
        seen[ref] = version
        return (last?.plus(1))?.takeIf { !held && version > it }
    }

    companion object {
        /**
         * The version a retry's entries are expected to start at: the one an aggregate parked at a gap is parked
         * from, which [scanned] (its re-scan in the source's order) starts above, the record that showed the gap
         * being the first; null for any other park, whose first entry is the one it is parked at.
         */
        fun expectedFirst(scanned: List<SourcedEntry>, parked: ParkedAggregate): Int? =
            scanned.firstOrNull()?.takeUnless { UndecodableMessage.isUndecodable(it) }
                ?.takeIf { it.stream.version > parked.fromVersion }?.let { parked.fromVersion }

        /**
         * [scanned] in version order, as a retry handles them: a late record, delivered after the one that showed its
         * gap, comes before it; an undecodable entry, of no version, comes last.
         */
        fun ordered(scanned: List<SourcedEntry>): List<SourcedEntry> =
            scanned.sortedBy { if (UndecodableMessage.isUndecodable(it)) Int.MAX_VALUE else it.stream.version }
    }
}

/**
 * How a retry walks [parked]'s entries: in the order [scanned] gives them, but on a source whose entries come at every
 * version ([contiguous]), those of a park at a gap in version order ([VersionGaps.ordered]), from the version missing,
 * each expected at the version after the one before.
 */
internal class RetryWalk(
    scanned: List<SourcedEntry>,
    parked: ParkedAggregate,
    private val contiguous: Boolean,
    private val acceptGaps: Boolean,
    private val subscription: String,
) {
    private var expected: Int? = if (contiguous) VersionGaps.expectedFirst(scanned, parked) else null

    /** The entries in the order the retry handles them. */
    val entries: List<SourcedEntry> = if (expected == null) scanned else VersionGaps.ordered(scanned)

    /**
     * The version missing before [entry], which the retry parks the aggregate at again; null when none is, or when an
     * operator's retry accepts the gap, which is logged with the versions it passes. [entry] is walked past.
     */
    fun gapBefore(entry: SourcedEntry): Int? {
        if (!contiguous || UndecodableMessage.isUndecodable(entry)) {
            return null
        }
        val version = entry.stream.version
        val gap = expected?.takeIf { version > it }
        expected = version + 1
        if (gap != null && acceptGaps) {
            logger.warn {
                "Subscription $subscription goes on past versions $gap to ${version - 1} of ${entry.stream.ref}, " +
                    "which the topic did not deliver: an operator's retry accepts the gap."
            }
            return null
        }
        return gap
    }
}

/**
 * The topic did not deliver [missing], an earlier version of the aggregate than the entry it met: the subscription
 * parks the aggregate at it. Waiting lets a late record resolve it in order at the next automatic retry; an
 * operator's retry accepts the gap and goes on from the next version present; a skip drops what was held behind it.
 */
internal class VersionGapException(val missing: Int) :
    IllegalStateException("An earlier version of the aggregate was not delivered by the topic: $CODE.") {
    companion object {
        /** What names the failure in its parked reason. */
        const val CODE: String = "wow.subscription.version-gap"
    }
}

/** An access-ordered map of the [REMEMBERED] aggregates used most recently, the least recently used dropped first. */
internal fun <V> recentlySeen(): LinkedHashMap<AggregateRef, V> =
    object : LinkedHashMap<AggregateRef, V>(REMEMBERED, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, V>?): Boolean = size > REMEMBERED
    }

/** The worker's: an operator filters a subscription's lines by it. */
private val logger = KotlinLogging.logger(SubscriptionWorker::class.java.name)

/** How many aggregates a worker remembers the last version of: handled, of a source that repeats; seen, for gaps. */
internal const val REMEMBERED = 4_096
private const val LOAD_FACTOR = 0.75f
