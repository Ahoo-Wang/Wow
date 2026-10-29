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
 * checkpoint passing over the parked aggregate's later entries. Nothing of those entries is copied: to resume, the
 * subscription reads the aggregate's streams from the log from [ParkedAggregate.fromVersion] on, so the ledger grows
 * with the number of parked aggregates, not with what was written while they were parked.
 *
 * The ledger lives in the failing service's own storage; wow-ops only aggregates it. Its writes are idempotent:
 * parking an aggregate that is already parked keeps the earliest from version, so a replayed or
 * duplicated park never moves the resume point past an entry that was not handled.
 *
 * An entry also carries its long-horizon retry state: the automatic retries made so far
 * and when the last one started, the retry spec an operator applied to it, whether it is recoverable, the request
 * an operator left for the owner of its partition, and when that owner next looks at it ([ParkedAggregate.nextAttemptAt]).
 * [park] and [repark] write only where the aggregate is parked and why (a new entry's retry state comes with it); the
 * retry state changes only through [amend].
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
 * One parked aggregate: [subscription] stopped handling [ref], in its source's [partition], at its stream
 * [fromVersion], at [failedAt] (epoch milliseconds), because of [reason]. The owner of [partition] retries it.
 *
 * [fromPosition] is where that stream lies in the subscription's source when the source cannot be read by aggregate:
 * the offset of its record in another context's topic, whose partition the ref's key always maps to.
 * A resume re-scans that partition from it, keeping the records keyed by [ref]. Null for a subscription of the log,
 * which reads an aggregate's streams by version. A topic of commands or receipts (–) commits
 * nothing, so its subscription parks at [fromVersion] 1 and goes by [fromPosition] alone.
 *
 * An entry whose source no longer retains [fromPosition] cannot be resumed, only skipped: the runtime marks it
 * [unrecoverable] by its [reason], which then starts with [UNRECOVERABLE].
 *
 * Its long-horizon retry state: the owner of the aggregate's partition retries the entry automatically
 * by a [ParkedRetrySpec] ([retrySpec] when an operator applied one, else the subscription's), [retries] times so far,
 * the last one started at [retryAt] (epoch milliseconds). [recoverable] is what an operator marked it: an entry marked
 * [Recoverable.UNRECOVERABLE] is not retried automatically, though it can still be resumed by hand. [request] is an
 * operator's resume or skip that the partition's owner has not taken yet.
 *
 * [nextAttemptAt] (epoch milliseconds) is when the owner next looks at the entry: the next automatic retry it
 * computed by the spec, [NEVER] for one that is not retried automatically, null until it has looked. Only the owner
 * sets it ([ParkedAmendment.Schedule]); a retry claimed, and an operator's change of the entry, clear it, so the
 * owner looks at the entry again on its next scan ([FailureLedger.due]). A retry spec changed in the host's
 * configuration applies to an entry from the retry after the one it is scheduled for.
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
) {
    init {
        require(subscription.isNotBlank()) { "A parked aggregate needs a subscription." }
        require(partition >= 0) { "A partition is never negative." }
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
     * not before the last retry's timeout passed, so a retry that may still run on the partition's former
     * owner is not started twice. Null when the entry is not [retryable].
     */
    fun nextRetryAt(spec: ParkedRetrySpec): Long? {
        if (!retryable(spec)) {
            return null
        }
        val next = plus(retryAt ?: failedAt, spec.backoff(retries))
        val timeout = retryAt?.let { plus(it, spec.timeout) } ?: next
        return maxOf(next, timeout)
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
        )

    override fun equals(other: Any?): Boolean =
        this === other || other is ParkedAggregate && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ParkedAggregate(subscription=$subscription, ref=$ref, partition=$partition, fromVersion=$fromVersion, " +
            "reason=$reason, failedAt=$failedAt, fromPosition=$fromPosition, retries=$retries, retryAt=$retryAt, " +
            "retrySpec=$retrySpec, recoverable=$recoverable, request=$request, nextAttemptAt=$nextAttemptAt)"

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
    )

    companion object {
        /** The start of the [reason] of an entry that can no longer be resumed, only skipped. */
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
            )
    }
}

/**
 * How a parked entry is retried over the long horizon (9.x `RetrySpec`): at most [maxRetries] automatic
 * retries, the first [initialBackoff] after the failure and each next one twice as long after the previous one started;
 * [timeout] is how long a retry may run before another one may start, such as on the partition's next owner.
 * A [maxRetries] of 0 turns automatic retries off.
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class ParkedRetrySpec @InternalWowApi constructor(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        require(maxRetries >= 0) { "The retries of a parked entry are never negative." }
        require(!initialBackoff.isNegative() && initialBackoff.isFinite()) { "A backoff is finite and never negative." }
        require(!timeout.isNegative() && timeout.isFinite()) {
            "A retry's timeout is finite and never negative."
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
        timeout: Duration = this.timeout,
    ): ParkedRetrySpec = ParkedRetrySpec(maxRetries, initialBackoff, timeout)

    override fun equals(other: Any?): Boolean =
        this === other || other is ParkedRetrySpec && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ParkedRetrySpec(maxRetries=$maxRetries, initialBackoff=$initialBackoff, timeout=$timeout)"

    private fun valueFields(): List<Any?> = listOf(maxRetries, initialBackoff, timeout)

    companion object {
        /** 9.x `@Retry`'s defaults: ten retries, the first three minutes after the failure, two minutes each. */
        const val DEFAULT_MAX_RETRIES: Int = 10
        val DEFAULT_INITIAL_BACKOFF: Duration = 3.minutes
        val DEFAULT_TIMEOUT: Duration = 2.minutes
        private const val MAX_DOUBLINGS = Long.SIZE_BITS - 2

        /** A ParkedRetrySpec of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            maxRetries: Int = DEFAULT_MAX_RETRIES,
            initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
            timeout: Duration = DEFAULT_TIMEOUT,
        ): ParkedRetrySpec = ParkedRetrySpec(maxRetries, initialBackoff, timeout)
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
 * An operator's request on a parked entry, left in the ledger by a node that does not own the entry's partition:
 * the owner takes it ([ParkedAmendment.TakeRequest]) and runs it.
 */
enum class LedgerRequest {
    /** Handle the aggregate again from where it is parked. */
    RESUME,

    /** Resolve the entry without handling what was parked. */
    SKIP,
}

/** A change of a parked entry's retry state ([FailureLedger.amend]). */
@WowSpi
sealed interface ParkedAmendment {
    /**
     * Claims an automatic retry: when the entry has still made [retries] retries, it has made one more, started [at]
     * (epoch milliseconds).
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class ClaimRetry @InternalWowApi constructor(val retries: Int, val at: Long) : ParkedAmendment {
        init {
            require(retries >= 0) { "The retries of a parked aggregate are never negative." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is ClaimRetry && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ClaimRetry(retries=$retries, at=$at)"

        private fun valueFields(): List<Any?> = listOf(retries, at)

        companion object {
            /** A ClaimRetry of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(retries: Int, at: Long): ClaimRetry = ClaimRetry(retries, at)
        }
    }

    /**
     * Marks the entry [recoverable] (9.x `MarkRecoverable`).
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class MarkRecoverable @InternalWowApi constructor(val recoverable: Recoverable) : ParkedAmendment {
        override fun equals(other: Any?): Boolean =
            this === other || other is MarkRecoverable && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "MarkRecoverable(recoverable=$recoverable)"

        private fun valueFields(): List<Any?> = listOf(recoverable)

        companion object {
            /** A MarkRecoverable of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(recoverable: Recoverable): MarkRecoverable = MarkRecoverable(recoverable)
        }
    }

    /**
     * Applies [spec] to the entry, or the subscription's spec again when null (9.x `ApplyRetrySpec`).
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class ApplyRetrySpec @InternalWowApi constructor(val spec: ParkedRetrySpec?) : ParkedAmendment {
        override fun equals(other: Any?): Boolean =
            this === other || other is ApplyRetrySpec && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ApplyRetrySpec(spec=$spec)"

        private fun valueFields(): List<Any?> = listOf(spec)

        companion object {
            /** An ApplyRetrySpec of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(spec: ParkedRetrySpec?): ApplyRetrySpec = ApplyRetrySpec(spec)
        }
    }

    /**
     * Leaves [request] for the partition's owner, replacing one it has not taken yet.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class Request @InternalWowApi constructor(val request: LedgerRequest) : ParkedAmendment {
        override fun equals(other: Any?): Boolean =
            this === other || other is Request && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Request(request=$request)"

        private fun valueFields(): List<Any?> = listOf(request)

        companion object {
            /** A Request of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(request: LedgerRequest): Request = Request(request)
        }
    }

    /**
     * Records when the owner of the entry's partition next looks at it: when the entry has still made [retries]
     * retries and is still scheduled at [from] (null when it was not), it is scheduled [at] (epoch milliseconds,
     * [ParkedAggregate.NEVER] for an entry that is not retried automatically). Conditional on both, so a claim or an
     * operator's change made since the owner read the entry is never covered by a schedule computed before it.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class Schedule @InternalWowApi constructor(val retries: Int, val from: Long?, val at: Long) : ParkedAmendment {
        init {
            require(retries >= 0) { "The retries of a parked aggregate are never negative." }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Schedule && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Schedule(retries=$retries, from=$from, at=$at)"

        private fun valueFields(): List<Any?> = listOf(retries, from, at)

        companion object {
            /** A Schedule of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(retries: Int, from: Long?, at: Long): Schedule = Schedule(retries, from, at)
        }
    }

    /**
     * Takes [request]: when the entry holds it, it no longer does.
     *
     * Fields may be added: construct one with [of]; the constructor is internal to Wow.
     */
    class TakeRequest @InternalWowApi constructor(val request: LedgerRequest) : ParkedAmendment {
        override fun equals(other: Any?): Boolean =
            this === other || other is TakeRequest && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "TakeRequest(request=$request)"

        private fun valueFields(): List<Any?> = listOf(request)

        companion object {
            /** A TakeRequest of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(request: LedgerRequest): TakeRequest = TakeRequest(request)
        }
    }
}

/**
 * This entry with [amendment] applied, or null when its condition does not hold: the reference
 * semantics of [FailureLedger.amend], for a backend that changes the entry in memory. Every amendment but a
 * [ParkedAmendment.Schedule] and a [ParkedAmendment.TakeRequest] clears [ParkedAggregate.nextAttemptAt], so the
 * owner looks at the entry again on its next scan.
 */
fun ParkedAggregate.amended(amendment: ParkedAmendment): ParkedAggregate? = when (amendment) {
    is ParkedAmendment.ClaimRetry -> if (retries == amendment.retries) {
        copyWith(retries = retries + 1, retryAt = amendment.at, nextAttemptAt = null)
    } else {
        null
    }

    is ParkedAmendment.MarkRecoverable -> copyWith(recoverable = amendment.recoverable, nextAttemptAt = null)
    is ParkedAmendment.ApplyRetrySpec -> copyWith(retrySpec = amendment.spec, nextAttemptAt = null)
    is ParkedAmendment.Request -> copyWith(request = amendment.request, nextAttemptAt = null)
    is ParkedAmendment.Schedule -> if (retries == amendment.retries && nextAttemptAt == amendment.from) {
        copyWith(nextAttemptAt = amendment.at)
    } else {
        null
    }

    is ParkedAmendment.TakeRequest -> if (request == amendment.request) copyWith(request = null) else null
}

/**
 * Whether this entry is due at [now] in one of [partitions] ([FailureLedger.due]): the reference semantics, for a
 * backend that filters in memory.
 */
fun ParkedAggregate.dueIn(partitions: Collection<Int>, now: Long): Boolean =
    partition in partitions && (nextAttemptAt == null || nextAttemptAt <= now)
