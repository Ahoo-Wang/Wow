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

package me.ahoo.wow.spi.ledger

import me.ahoo.wow.api.aggregate.AggregateRef

/**
 * The failure ledger of one service (documentation/designs/v10/04-subscriptions.md §2, decision D14).
 *
 * When a subscription has retried an aggregate's entry past its limit, it parks **the aggregate**: the ledger records
 * (subscription, aggregate, from version, reason) and the subscription moves on with every other aggregate, its
 * checkpoint passing over the parked aggregate's later entries. Nothing of those entries is copied: to resume, the
 * subscription reads the aggregate's streams from the log from [ParkedAggregate.fromVersion] on, so the ledger grows
 * with the number of parked aggregates, not with what was written while they were parked.
 *
 * The ledger lives in the failing service's own storage; wow-ops only aggregates it. Its writes are idempotent
 * (decision D45): parking an aggregate that is already parked keeps the earliest from version, so a replayed or
 * duplicated park never moves the resume point past an entry that was not handled.
 */
interface FailureLedger {
    /**
     * Parks [entry]'s aggregate for its subscription. When the aggregate is already parked, the entry with the
     * earlier from version is kept (on a tie, the one already there), its from position with it.
     *
     * @return true when the ledger changed: the aggregate was not parked, or is now parked from an earlier version.
     */
    suspend fun park(entry: ParkedAggregate): Boolean

    /**
     * Replaces the entry parking [entry]'s aggregate with [entry] (its from version and position, reason and failure
     * time), when the aggregate is parked; it never parks one that is not. Unlike [park] it may move the from version
     * later: a resume that handled the aggregate's streams up to a version and failed again there records where it
     * now stands and why, so the next resume starts at that stream rather than handling the earlier ones again. Only
     * the subscription resuming the aggregate calls it, while no live entry of that aggregate is handled.
     *
     * @return true when an entry was replaced; false when the aggregate is not parked (it was resolved meanwhile).
     */
    suspend fun repark(entry: ParkedAggregate): Boolean

    /** The entry parking [ref] for [subscription], if any. */
    suspend fun parked(subscription: String, ref: AggregateRef): ParkedAggregate?

    /** Whether [subscription] has parked [ref]: its entries are not handled until the entry is resolved. */
    suspend fun isParked(subscription: String, ref: AggregateRef): Boolean = parked(subscription, ref) != null

    /**
     * Up to [limit] aggregates [subscription] has parked, ordered by aggregate (context, aggregate name, tenant, id,
     * each compared by code point), starting after [after] (exclusive; null from the start). Pass the last entry's
     * ref as [after] to read the next page.
     */
    suspend fun list(
        subscription: String,
        after: AggregateRef? = null,
        limit: Int = DEFAULT_PAGE
    ): List<ParkedAggregate>

    /**
     * Resolves the entry parking [ref] for [subscription] by removing it, after the aggregate was retried
     * successfully or the operator chose to skip it: either way the subscription handles the aggregate's entries
     * again. Which of the two happened is the caller's to publish; the ledger only holds what is still parked.
     *
     * @return the removed entry, or null when the aggregate was not parked.
     */
    suspend fun resolve(subscription: String, ref: AggregateRef): ParkedAggregate?

    companion object {
        /** The page size of [list] when none is given. */
        const val DEFAULT_PAGE = 100
    }
}

/**
 * One parked aggregate: [subscription] stopped handling [ref] at its stream [fromVersion], at [failedAt] (epoch
 * milliseconds), because of [reason].
 *
 * [fromPosition] is where that stream lies in the subscription's source when the source cannot be read by aggregate
 * (decision D134): the offset of its record in another context's topic, whose partition the ref's key always maps to.
 * A resume re-scans that partition from it, keeping the records keyed by [ref]. Null for a subscription of the log,
 * which reads an aggregate's streams by version.
 *
 * An entry whose source no longer retains [fromPosition] cannot be resumed, only skipped: the runtime marks it
 * [unrecoverable] by its [reason], which then starts with [UNRECOVERABLE].
 */
data class ParkedAggregate(
    val subscription: String,
    val ref: AggregateRef,
    val fromVersion: Int,
    val reason: String,
    val failedAt: Long,
    val fromPosition: Long? = null,
) {
    init {
        require(subscription.isNotBlank()) { "A parked aggregate needs a subscription." }
        require(fromVersion >= 1) { "An event stream's version starts at 1." }
        require(fromPosition == null || fromPosition >= 0) { "A source position is never negative." }
    }

    /** Whether the source no longer retains [fromPosition]: the entry can only be skipped. */
    val unrecoverable: Boolean get() = reason.startsWith(UNRECOVERABLE)

    companion object {
        /** The start of the [reason] of an entry that can no longer be resumed, only skipped. */
        const val UNRECOVERABLE: String = "wow.subscription.unrecoverable"
    }
}
