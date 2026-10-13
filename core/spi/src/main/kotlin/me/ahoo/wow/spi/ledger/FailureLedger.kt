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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
 *
 * An entry also carries its long-horizon retry state (roadmap P.1, decision D184): the automatic retries made so far
 * and when the last one started, the retry spec an operator applied to it, whether it is recoverable, and the request
 * an operator left for the owner of its partition (decision D141). [park] and [repark] write only where the aggregate
 * is parked and why (a new entry's retry state comes with it); the retry state changes only through [amend].
 */
interface FailureLedger {
    /**
     * Parks [entry]'s aggregate for its subscription. When the aggregate is already parked, the entry with the
     * earlier from version is kept (on a tie, the one already there), its from position with it; an entry replaced
     * this way keeps its retry state, a new one takes [entry]'s.
     *
     * @return true when the ledger changed: the aggregate was not parked, or is now parked from an earlier version.
     */
    suspend fun park(entry: ParkedAggregate): Boolean

    /**
     * Replaces the from version and position, reason and failure time of the entry parking [entry]'s aggregate with
     * [entry]'s, when the aggregate is parked; it never parks one that is not, and keeps the entry's retry state.
     * Unlike [park] it may move the from version later: a resume that handled the aggregate's streams up to a version
     * and failed again there records where it now stands and why, so the next resume starts at that stream rather
     * than handling the earlier ones again. Only the subscription resuming the aggregate calls it, while no live entry
     * of that aggregate is handled.
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

    /**
     * Changes the retry state of the entry parking [ref] for [subscription] as [amendment] says, in one write. A
     * claim of an automatic retry ([ParkedAmendment.ClaimRetry]) or the take of a request
     * ([ParkedAmendment.TakeRequest]) applies only while the entry still holds what the caller read, so of two nodes
     * that both read it (an owner handing its partition off and the next one) only one claims or takes it.
     *
     * @return the amended entry; null when the aggregate is not parked or the amendment's condition does not hold.
     */
    suspend fun amend(subscription: String, ref: AggregateRef, amendment: ParkedAmendment): ParkedAggregate?

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
 * which reads an aggregate's streams by version. A topic of commands or receipts (decisions D135–D137) commits
 * nothing, so its subscription parks at [fromVersion] 1 and goes by [fromPosition] alone.
 *
 * An entry whose source no longer retains [fromPosition] cannot be resumed, only skipped: the runtime marks it
 * [unrecoverable] by its [reason], which then starts with [UNRECOVERABLE].
 *
 * Its long-horizon retry state (roadmap P.1): the owner of the aggregate's partition retries the entry automatically
 * by a [ParkedRetrySpec] ([retrySpec] when an operator applied one, else the subscription's), [retries] times so far,
 * the last one started at [retryAt] (epoch milliseconds). [recoverable] is what an operator marked it: an entry marked
 * [Recoverable.UNRECOVERABLE] is not retried automatically, though it can still be resumed by hand. [request] is an
 * operator's resume or skip that the partition's owner has not taken yet (decision D141).
 */
data class ParkedAggregate(
    val subscription: String,
    val ref: AggregateRef,
    val fromVersion: Int,
    val reason: String,
    val failedAt: Long,
    val fromPosition: Long? = null,
    val retries: Int = 0,
    val retryAt: Long? = null,
    val retrySpec: ParkedRetrySpec? = null,
    val recoverable: Recoverable = Recoverable.UNKNOWN,
    val request: LedgerRequest? = null,
) {
    init {
        require(subscription.isNotBlank()) { "A parked aggregate needs a subscription." }
        require(fromVersion >= 1) { "An event stream's version starts at 1." }
        require(fromPosition == null || fromPosition >= 0) { "A source position is never negative." }
        require(retries >= 0) { "The retries of a parked aggregate are never negative." }
    }

    /** Whether the source no longer retains [fromPosition]: the entry can only be skipped. */
    val unrecoverable: Boolean get() = reason.startsWith(UNRECOVERABLE)

    /** Whether [spec]'s retries are spent: the entry is not retried automatically any more (9.x: not retryable). */
    fun exhausted(spec: ParkedRetrySpec): Boolean = retries >= spec.maxRetries

    /**
     * Whether the entry is retried automatically under [spec]: its source still holds it, no operator marked it
     * unrecoverable, and [spec]'s retries are not spent.
     */
    fun retryable(spec: ParkedRetrySpec): Boolean =
        !unrecoverable && recoverable != Recoverable.UNRECOVERABLE && !exhausted(spec)

    /**
     * When the next automatic retry under [spec] is due (epoch milliseconds), as 9.x computes it: [spec]'s minimum
     * backoff, doubled per retry made, after the last retry started (after the failure, before the first one), and
     * not before the last retry's execution timeout passed, so a retry that may still run on the partition's former
     * owner is not started twice. Null when the entry is not [retryable].
     */
    fun nextRetryAt(spec: ParkedRetrySpec): Long? {
        if (!retryable(spec)) {
            return null
        }
        val next = plus(retryAt ?: failedAt, spec.backoff(retries))
        val timeout = retryAt?.let { plus(it, spec.executionTimeout) } ?: next
        return maxOf(next, timeout)
    }

    companion object {
        /** The start of the [reason] of an entry that can no longer be resumed, only skipped. */
        const val UNRECOVERABLE: String = "wow.subscription.unrecoverable"

        /** [at] plus [duration] in epoch milliseconds, [Long.MAX_VALUE] when it overflows. */
        private fun plus(at: Long, duration: Duration): Long {
            val millis = duration.inWholeMilliseconds
            return if (at > Long.MAX_VALUE - millis) Long.MAX_VALUE else at + millis
        }
    }
}

/**
 * How a parked entry is retried over the long horizon (roadmap P.1, 9.x `RetrySpec`): at most [maxRetries] automatic
 * retries, the first [minBackoff] after the failure and each next one twice as long after the previous one started;
 * [executionTimeout] is how long a retry may run before another one may start, such as on the partition's next owner.
 * A [maxRetries] of 0 turns automatic retries off.
 */
data class ParkedRetrySpec(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val minBackoff: Duration = DEFAULT_MIN_BACKOFF,
    val executionTimeout: Duration = DEFAULT_EXECUTION_TIMEOUT,
) {
    init {
        require(maxRetries >= 0) { "The retries of a parked entry are never negative." }
        require(!minBackoff.isNegative() && minBackoff.isFinite()) { "A backoff is finite and never negative." }
        require(!executionTimeout.isNegative() && executionTimeout.isFinite()) {
            "An execution timeout is finite and never negative."
        }
    }

    /** The backoff after [retries] retries: [minBackoff] doubled [retries] times, at most [Long.MAX_VALUE] ms. */
    fun backoff(retries: Int): Duration {
        val millis = minBackoff.inWholeMilliseconds
        val doublings = retries.coerceIn(0, MAX_DOUBLINGS)
        val backoff = if (millis > Long.MAX_VALUE shr doublings) Long.MAX_VALUE else millis shl doublings
        return backoff.milliseconds
    }

    companion object {
        /** 9.x `@Retry`'s defaults: ten retries, the first three minutes after the failure, two minutes each. */
        const val DEFAULT_MAX_RETRIES: Int = 10
        val DEFAULT_MIN_BACKOFF: Duration = 3.minutes
        val DEFAULT_EXECUTION_TIMEOUT: Duration = 2.minutes
        private const val MAX_DOUBLINGS = Long.SIZE_BITS - 2
    }
}

/** What an operator marked a parked entry (9.x `RecoverableType`); only [UNRECOVERABLE] stops its automatic retries. */
enum class Recoverable {
    /** Nobody said: retried automatically. */
    UNKNOWN,

    /** Retried automatically. */
    RECOVERABLE,

    /** Not retried automatically; an operator can still resume or skip it. */
    UNRECOVERABLE,
}

/**
 * An operator's request on a parked entry, left in the ledger by a node that does not own the entry's partition
 * (decision D141): the owner takes it ([ParkedAmendment.TakeRequest]) and runs it.
 */
enum class LedgerRequest {
    /** Handle the aggregate again from where it is parked. */
    RESUME,

    /** Resolve the entry without handling what was parked. */
    SKIP,
}

/** A change of a parked entry's retry state ([FailureLedger.amend]). */
sealed interface ParkedAmendment {
    /**
     * Claims an automatic retry: when the entry has still made [retries] retries, it has made one more, started [at]
     * (epoch milliseconds).
     */
    data class ClaimRetry(val retries: Int, val at: Long) : ParkedAmendment {
        init {
            require(retries >= 0) { "The retries of a parked aggregate are never negative." }
        }
    }

    /** Marks the entry [recoverable] (9.x `MarkRecoverable`). */
    data class MarkRecoverable(val recoverable: Recoverable) : ParkedAmendment

    /** Applies [spec] to the entry, or the subscription's spec again when null (9.x `ApplyRetrySpec`). */
    data class ApplyRetrySpec(val spec: ParkedRetrySpec?) : ParkedAmendment

    /** Leaves [request] for the partition's owner, replacing one it has not taken yet. */
    data class Request(val request: LedgerRequest) : ParkedAmendment

    /** Takes [request]: when the entry holds it, it no longer does. */
    data class TakeRequest(val request: LedgerRequest) : ParkedAmendment
}

/**
 * This entry with [amendment] applied, or null when its condition does not hold: the reference
 * semantics of [FailureLedger.amend], for a backend that changes the entry in memory.
 */
fun ParkedAggregate.amended(amendment: ParkedAmendment): ParkedAggregate? = when (amendment) {
    is ParkedAmendment.ClaimRetry ->
        if (retries == amendment.retries) copy(retries = retries + 1, retryAt = amendment.at) else null

    is ParkedAmendment.MarkRecoverable -> copy(recoverable = amendment.recoverable)
    is ParkedAmendment.ApplyRetrySpec -> copy(retrySpec = amendment.spec)
    is ParkedAmendment.Request -> copy(request = amendment.request)
    is ParkedAmendment.TakeRequest -> if (request == amendment.request) copy(request = null) else null
}
