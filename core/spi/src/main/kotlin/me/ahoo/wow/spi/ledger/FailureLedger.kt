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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The failure ledger of one service (documentation/designs/04-subscriptions.md §2).
 *
 * When a subscription has retried an aggregate's entry past its limit, it parks **the aggregate**: the ledger records
 * (subscription, aggregate, from version, reason) and the subscription moves on with every other aggregate, its
 * checkpoint passing over the parked aggregate's later entries. Nothing of those entries is copied: to retry, the
 * subscription reads the aggregate's streams from the log from [ParkedAggregate.fromVersion] on, so the ledger grows
 * with the number of parked aggregates, not with what was written while they were parked.
 *
 * The ledger lives in the failing service's own storage; wow-ops only aggregates it. Its writes are idempotent:
 * parking an aggregate that is already parked keeps the earliest from version, so a replayed or
 * duplicated park never moves the retry point past an entry that was not handled.
 *
 * An entry also carries its long-horizon retry state: the automatic retries made so far
 * and when the last one started, the retry spec an operator applied to it, whether it is recoverable, the request
 * an operator left for the owner of its partition, and when that owner next looks at it ([ParkedAggregate.nextAttemptAt]).
 * [park] and [repark] write only where the aggregate is parked and why (a new entry's retry state comes with it); the
 * retry state changes only through [amend], where the import of 9.x compensations also restates why
 * ([ParkedAmendment.Restate]).
 *
 * Every stored change of an entry gives it the next [ParkedAggregate.revision], the ledger's concurrency token: a new
 * entry is at 0, and a [repark], a [park] that replaces an entry, and every amendment add one. An amendment that acts
 * on what its caller read ([ParkedAmendment.ClaimRetry], [ParkedAmendment.Schedule], [ParkedAmendment.TakeRequest],
 * [ParkedAmendment.Restate]) names the revision read and applies only at it, so nothing written since, an operator's change or a repark among
 * them, is ever covered by a decision made before it. Every change but a schedule and a take also clears
 * [ParkedAggregate.nextAttemptAt], so an owner's write that loses to one finds the entry due on its next scan.
 *
 * The owner of a partition reads only what is due there ([due]), and the gauges count the entries without reading
 * them ([count]): a ledger answers both from an index, on (subscription, partition, next attempt), so neither grows
 * with the entries that are not due, nor with the nodes of the service. A listing of one tenant's entries
 * ([list] with a tenant) is answered from an index too.
 */
@WowSpi
interface FailureLedger {
    /**
     * Parks [entry]'s aggregate for its subscription. When the aggregate is already parked, the entry with the
     * earlier from version is kept (on a tie, the one already there), its partition and from position with it; an
     * entry replaced this way keeps its retry state, takes the next revision and is no longer scheduled; a new one
     * takes [entry]'s retry state, at revision 0.
     *
     * @return true when the ledger changed: the aggregate was not parked, or is now parked from an earlier version.
     */
    suspend fun park(entry: ParkedAggregate): Boolean

    /**
     * Replaces where the entry parking [entry]'s aggregate stands (its partition, from version and position), why
     * (its reason) and when (its failure time) with [entry]'s, while the aggregate is parked in partition [from]; it
     * never parks one that is not. It keeps the entry's retry state, gives it the next revision and clears its
     * schedule. Its callers:
     *
     * - **The worker that retried the aggregate**, in the partition it read the entry in ([from], the entry's own
     *   partition by default): a retry that handled the aggregate's streams up to a version and failed again there
     *   records where it now stands and why, so the next retry starts at that stream rather than handling the
     *   earlier ones again; unlike [park] it may move the from version later. It is called while no live entry of
     *   that aggregate is handled.
     * - **The owner of the partition the aggregate's key maps to now**, after its source gained partitions: it
     *   re-homes the entry there ([entry]'s partition), [from] the partition it parked in, so the due scan, an
     *   operator's request and a skip reach the owner that holds the aggregate's later entries back.
     *
     * Fenced by the partition, not by the revision: a worker whose retry ends after the entry was re-homed writes
     * nothing (the entry is no longer in [from]), while an operator's change during the retry, which does not move the
     * entry, never voids the retry's own result.
     *
     * @return true when an entry was replaced; false when the aggregate is not parked in [from] (it was resolved, or
     * re-homed, meanwhile).
     */
    suspend fun repark(entry: ParkedAggregate, from: Int = entry.partition): Boolean

    /** The entry parking [ref] for [subscription], if any. */
    suspend fun parked(subscription: String, ref: AggregateRef): ParkedAggregate?

    /** Whether [subscription] has parked [ref]: its entries are not handled until the entry is resolved. */
    suspend fun isParked(subscription: String, ref: AggregateRef): Boolean = parked(subscription, ref) != null

    /**
     * Up to [limit] aggregates [subscription] has parked, ordered by aggregate (context, aggregate name, tenant, id,
     * each compared by code point), starting after [after] (exclusive; null from the start), only [tenantId]'s when
     * given. Pass the last entry's ref as [after] to read the next page.
     */
    suspend fun list(
        subscription: String,
        after: AggregateRef? = null,
        limit: Int = DEFAULT_PAGE,
        tenantId: String? = null,
    ): List<ParkedAggregate>

    /**
     * Up to [limit] of [subscription]'s entries in [partitions] that the owner of their partition looks at by [now]
     * (epoch milliseconds): those whose [ParkedAggregate.nextAttemptAt] is null (new, retried, or changed by an
     * operator since the owner last looked) or not after [now]; at [ParkedAggregate.NEVER], every entry of
     * [partitions], as a node that acquires a partition reads them. Ordered as [list] orders them, after [after]
     * (exclusive; null from the start): pass the last entry's ref as [after] to read the next page.
     */
    suspend fun due(
        subscription: String,
        partitions: Collection<Int>,
        now: Long,
        after: AggregateRef? = null,
        limit: Int = DEFAULT_PAGE,
    ): List<ParkedAggregate>

    /** How many aggregates [subscription] has parked, counted up to [max]: [max] when there are more. */
    suspend fun count(subscription: String, max: Int): Int

    /**
     * Resolves the entry parking [ref] for [subscription] by removing it, after the aggregate was retried
     * successfully or the operator chose to skip it: either way the subscription handles the aggregate's entries
     * again. Which of the two happened is the caller's to publish; the ledger only holds what is still parked.
     *
     * It removes the entry wherever it is parked, for the callers that act on the ledger without a worker of the
     * aggregate's partition: an operator's skip on a node where no worker holds the aggregate, the timers' parked
     * entries, and the import of 9.x compensations. A worker resolves in its own partition ([resolve]).
     *
     * @return the removed entry, or null when the aggregate was not parked.
     */
    suspend fun resolveAnywhere(subscription: String, ref: AggregateRef): ParkedAggregate?

    /**
     * Resolves the entry parking [ref] for [subscription], as [resolveAnywhere] does, while the aggregate is parked in
     * [partition]: the worker of that partition resolves it after its retry handled every parked stream it read
     * there, or as an operator's skip. An entry re-homed to another partition meanwhile ([repark]) stays: its streams
     * there are that partition's worker's.
     *
     * @return the removed entry, or null when the aggregate is not parked in [partition].
     */
    suspend fun resolve(subscription: String, ref: AggregateRef, partition: Int): ParkedAggregate?

    /**
     * Changes the retry state of the entry parking [ref] for [subscription] as [amendment] says, in one write, giving
     * it the next revision; a restate ([ParkedAmendment.Restate]) also changes why it is parked. A claim of an
     * automatic retry ([ParkedAmendment.ClaimRetry]), a schedule ([ParkedAmendment.Schedule]), the take of a request
     * ([ParkedAmendment.TakeRequest]) or a restate applies only at the revision its caller read, so of two nodes that
     * both read it (an owner handing its partition off and the next one) only one claims or takes it, and nothing
     * written since the read is covered.
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
 * One parked aggregate: [subscription] stopped handling [ref], in its source's [partition], at its stream
 * [fromVersion], at [failedAt] (epoch milliseconds), because of [reason]. The owner of [partition] retries it.
 *
 * [fromPosition] is where that stream lies in the subscription's source when the source cannot be read by aggregate:
 * the offset of its record in another context's topic, whose partition the ref's key always maps to.
 * A retry re-scans that partition from it, keeping the records keyed by [ref]. Null for a subscription of the log,
 * which reads an aggregate's streams by version. A topic of commands or receipts (–) commits
 * nothing, so its subscription parks at [fromVersion] 1 and goes by [fromPosition] alone.
 *
 * An entry whose source no longer retains [fromPosition] cannot be retried, only skipped: the runtime marks it
 * [unrecoverable] by its [reason], which then starts with [UNRECOVERABLE].
 *
 * Its long-horizon retry state: the owner of the aggregate's partition retries the entry automatically
 * by a [ParkedRetrySpec] ([retrySpec] when an operator applied one, else the subscription's), [retries] times so far,
 * the last one started at [retryAt] (epoch milliseconds). [recoverable] is what an operator marked it: an entry marked
 * [Recoverable.UNRECOVERABLE] is not retried automatically, though it can still be retried by hand. [request] is an
 * operator's retry or skip that the partition's owner has not taken yet.
 *
 * [nextAttemptAt] (epoch milliseconds) is when the owner next looks at the entry: the next automatic retry it
 * computed by the spec, [NEVER] for one that is not retried automatically, null until it has looked. Only the owner
 * sets it ([ParkedAmendment.Schedule]); a retry claimed, and an operator's change of the entry, clear it, so the
 * owner looks at the entry again on its next scan ([FailureLedger.due]). A retry spec changed in the host's
 * configuration applies to an entry from the retry after the one it is scheduled for.
 *
 * [revision] is the ledger's concurrency token, not an operator's fact: the number of stored changes of the entry
 * since it parked ([FailureLedger]). A new entry is at 0, whatever it is constructed with.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class ParkedAggregate @InternalWowApi constructor(
    val subscription: String,
    val ref: AggregateRef,
    val partition: Int,
    val fromVersion: Int,
    val reason: String,
    val failedAt: Long,
    val fromPosition: Long? = null,
    val retries: Int = 0,
    val retryAt: Long? = null,
    val retrySpec: ParkedRetrySpec? = null,
    val recoverable: Recoverable = Recoverable.UNKNOWN,
    val request: LedgerRequest? = null,
    val nextAttemptAt: Long? = null,
    val revision: Long = 0,
) {
    init {
        require(subscription.isNotBlank()) { "A parked aggregate needs a subscription." }
        require(partition >= 0) { "A partition is never negative." }
        require(fromVersion >= 1) { "An event stream's version starts at 1." }
        require(fromPosition == null || fromPosition >= 0) { "A source position is never negative." }
        require(retries >= 0) { "The retries of a parked aggregate are never negative." }
        require(revision >= 0) { "A ledger revision is never negative." }
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
     * not before the last retry's claim expired ([ParkedRetrySpec.claimTtl]), so a retry that may still run on the
     * partition's former owner is not started twice. Null when the entry is not [retryable].
     */
    fun nextRetryAt(spec: ParkedRetrySpec): Long? {
        if (!retryable(spec)) {
            return null
        }
        val next = plus(retryAt ?: failedAt, spec.backoff(retries))
        val claimed = retryAt?.let { plus(it, spec.claimTtl) } ?: next
        return maxOf(next, claimed)
    }

    /** This ParkedAggregate with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        subscription: String = this.subscription,
        ref: AggregateRef = this.ref,
        partition: Int = this.partition,
        fromVersion: Int = this.fromVersion,
        reason: String = this.reason,
        failedAt: Long = this.failedAt,
        fromPosition: Long? = this.fromPosition,
        retries: Int = this.retries,
        retryAt: Long? = this.retryAt,
        retrySpec: ParkedRetrySpec? = this.retrySpec,
        recoverable: Recoverable = this.recoverable,
        request: LedgerRequest? = this.request,
        nextAttemptAt: Long? = this.nextAttemptAt,
        revision: Long = this.revision,
    ): ParkedAggregate =
        ParkedAggregate(
            subscription,
            ref,
            partition,
            fromVersion,
            reason,
            failedAt,
            fromPosition,
            retries,
            retryAt,
            retrySpec,
            recoverable,
            request,
            nextAttemptAt,
            revision,
        )

    override fun equals(other: Any?): Boolean =
        this === other || other is ParkedAggregate && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ParkedAggregate(subscription=$subscription, ref=$ref, partition=$partition, fromVersion=$fromVersion, " +
            "reason=$reason, failedAt=$failedAt, fromPosition=$fromPosition, retries=$retries, retryAt=$retryAt, " +
            "retrySpec=$retrySpec, recoverable=$recoverable, request=$request, nextAttemptAt=$nextAttemptAt, " +
            "revision=$revision)"

    private fun valueFields(): List<Any?> = listOf(
        subscription,
        ref,
        partition,
        fromVersion,
        reason,
        failedAt,
        fromPosition,
        retries,
        retryAt,
        retrySpec,
        recoverable,
        request,
        nextAttemptAt,
        revision,
    )

    companion object {
        /** The start of the [reason] of an entry that can no longer be retried, only skipped. */
        const val UNRECOVERABLE: String = "wow.subscription.unrecoverable"

        /** The [nextAttemptAt] of an entry that is not retried automatically: never due until it changes. */
        const val NEVER: Long = Long.MAX_VALUE

        /** [at] plus [duration] in epoch milliseconds, [Long.MAX_VALUE] when it overflows. */
        private fun plus(at: Long, duration: Duration): Long {
            val millis = duration.inWholeMilliseconds
            return if (at > Long.MAX_VALUE - millis) Long.MAX_VALUE else at + millis
        }

        /** A ParkedAggregate of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            subscription: String,
            ref: AggregateRef,
            partition: Int,
            fromVersion: Int,
            reason: String,
            failedAt: Long,
            fromPosition: Long? = null,
            retries: Int = 0,
            retryAt: Long? = null,
            retrySpec: ParkedRetrySpec? = null,
            recoverable: Recoverable = Recoverable.UNKNOWN,
            request: LedgerRequest? = null,
            nextAttemptAt: Long? = null,
            revision: Long = 0,
        ): ParkedAggregate =
            ParkedAggregate(
                subscription,
                ref,
                partition,
                fromVersion,
                reason,
                failedAt,
                fromPosition,
                retries,
                retryAt,
                retrySpec,
                recoverable,
                request,
                nextAttemptAt,
                revision,
            )
    }
}

/**
 * How a parked entry is retried over the long horizon (9.x `RetrySpec`): at most [maxRetries] automatic
 * retries, the first [initialBackoff] after the failure and each next one twice as long after the previous one started;
 * [claimTtl] is how long a retry's claim on the entry holds before another retry may start, such as on the
 * partition's next owner: it cancels nothing, the retry runs on. A [maxRetries] of 0 turns automatic retries off.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class ParkedRetrySpec @InternalWowApi constructor(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
    val claimTtl: Duration = DEFAULT_CLAIM_TTL,
) {
    init {
        require(maxRetries >= 0) { "The retries of a parked entry are never negative." }
        require(!initialBackoff.isNegative() && initialBackoff.isFinite()) { "A backoff is finite and never negative." }
        require(!claimTtl.isNegative() && claimTtl.isFinite()) {
            "A retry's claim TTL is finite and never negative."
        }
    }

    /** The backoff after [retries] retries: [initialBackoff] doubled [retries] times, at most [Long.MAX_VALUE] ms. */
    fun backoff(retries: Int): Duration {
        val millis = initialBackoff.inWholeMilliseconds
        val doublings = retries.coerceIn(0, MAX_DOUBLINGS)
        val backoff = if (millis > Long.MAX_VALUE shr doublings) Long.MAX_VALUE else millis shl doublings
        return backoff.milliseconds
    }

    /** This ParkedRetrySpec with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        maxRetries: Int = this.maxRetries,
        initialBackoff: Duration = this.initialBackoff,
        claimTtl: Duration = this.claimTtl,
    ): ParkedRetrySpec = ParkedRetrySpec(maxRetries, initialBackoff, claimTtl)

    override fun equals(other: Any?): Boolean =
        this === other || other is ParkedRetrySpec && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ParkedRetrySpec(maxRetries=$maxRetries, initialBackoff=$initialBackoff, claimTtl=$claimTtl)"

    private fun valueFields(): List<Any?> = listOf(maxRetries, initialBackoff, claimTtl)

    companion object {
        /**
         * 9.x `@Retry`'s defaults: ten retries, the first three minutes after the failure, each claiming the entry for
         * two minutes (9.x's execution timeout).
         */
        const val DEFAULT_MAX_RETRIES: Int = 10
        val DEFAULT_INITIAL_BACKOFF: Duration = 3.minutes
        val DEFAULT_CLAIM_TTL: Duration = 2.minutes
        private const val MAX_DOUBLINGS = Long.SIZE_BITS - 2

        /** A ParkedRetrySpec of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            maxRetries: Int = DEFAULT_MAX_RETRIES,
            initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
            claimTtl: Duration = DEFAULT_CLAIM_TTL,
        ): ParkedRetrySpec = ParkedRetrySpec(maxRetries, initialBackoff, claimTtl)
    }
}

/** What an operator marked a parked entry (9.x `RecoverableType`); only [UNRECOVERABLE] stops its automatic retries. */
enum class Recoverable {
    /** Nobody said: retried automatically. */
    UNKNOWN,

    /** Retried automatically. */
    RECOVERABLE,

    /** Not retried automatically; an operator can still retry or skip it. */
    UNRECOVERABLE,
}

/**
 * An operator's request on a parked entry, left in the ledger by a node that does not own the entry's partition:
 * the owner takes it ([ParkedAmendment.TakeRequest]) and runs it.
 */
enum class LedgerRequest {
    /** Handle the aggregate again from where it is parked. */
    RETRY,

    /** Resolve the entry without handling what was parked. */
    SKIP,
}

/**
 * This entry with [amendment] applied at the next revision, or null when the amendment names a revision read that the
 * entry is no longer at: the reference semantics of [FailureLedger.amend], for a backend that changes the entry in
 * memory. Every amendment but a [ParkedAmendment.Schedule] and a [ParkedAmendment.TakeRequest] clears
 * [ParkedAggregate.nextAttemptAt], so the owner looks at the entry again on its next scan.
 */
fun ParkedAggregate.amended(amendment: ParkedAmendment): ParkedAggregate? {
    val read = when (amendment) {
        is ParkedAmendment.ClaimRetry -> amendment.revision
        is ParkedAmendment.Schedule -> amendment.revision
        is ParkedAmendment.TakeRequest -> amendment.revision
        is ParkedAmendment.Restate -> amendment.revision
        is ParkedAmendment.MarkRecoverable, is ParkedAmendment.ApplyRetrySpec, is ParkedAmendment.Request -> revision
    }
    if (read != revision) {
        return null
    }
    val next = revision + 1
    return when (amendment) {
        is ParkedAmendment.ClaimRetry ->
            copyWith(retries = retries + 1, retryAt = amendment.at, nextAttemptAt = null, revision = next)
        is ParkedAmendment.MarkRecoverable ->
            copyWith(recoverable = amendment.recoverable, nextAttemptAt = null, revision = next)
        is ParkedAmendment.ApplyRetrySpec -> copyWith(retrySpec = amendment.spec, nextAttemptAt = null, revision = next)
        is ParkedAmendment.Request -> copyWith(request = amendment.request, nextAttemptAt = null, revision = next)
        is ParkedAmendment.Schedule -> copyWith(nextAttemptAt = amendment.at, revision = next)
        is ParkedAmendment.TakeRequest -> copyWith(request = null, revision = next)
        is ParkedAmendment.Restate -> copyWith(
            reason = amendment.reason,
            failedAt = amendment.failedAt,
            retries = amendment.retries,
            retryAt = amendment.retryAt,
            retrySpec = amendment.retrySpec,
            recoverable = amendment.recoverable,
            nextAttemptAt = null,
            revision = next,
        )
    }
}

/**
 * This entry where [entry] parks the aggregate (its partition, from version and position) and why (its reason and
 * failure time), with this entry's retry state, at the next revision and no longer scheduled: the reference semantics
 * of a [FailureLedger.repark], and of a [FailureLedger.park] that replaces an entry.
 */
fun ParkedAggregate.placedAs(entry: ParkedAggregate): ParkedAggregate = copyWith(
    partition = entry.partition,
    fromVersion = entry.fromVersion,
    reason = entry.reason,
    failedAt = entry.failedAt,
    fromPosition = entry.fromPosition,
    nextAttemptAt = null,
    revision = revision + 1,
)

/**
 * Whether this entry is due at [now] in one of [partitions] ([FailureLedger.due]): the reference semantics, for a
 * backend that filters in memory.
 */
fun ParkedAggregate.dueIn(partitions: Collection<Int>, now: Long): Boolean =
    partition in partitions && (nextAttemptAt == null || nextAttemptAt <= now)
