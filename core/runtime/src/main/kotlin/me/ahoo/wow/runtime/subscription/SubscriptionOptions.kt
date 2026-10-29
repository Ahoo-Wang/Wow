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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a failing call is retried: at most [maxAttempts] calls in all, waiting [initialBackoff] after the first
 * failure and [multiplier] times longer after each next one, never longer than [maxBackoff]. A handler's attempt
 * that runs longer than [timeout] is cancelled and counts as failed; infinite by default.
 */
class RetryPolicy @InternalWowApi constructor(
    val maxAttempts: Int = HandlerRetry.DEFAULT_MAX_ATTEMPTS,
    val initialBackoff: Duration = 100.milliseconds,
    val maxBackoff: Duration = 5.seconds,
    val multiplier: Double = 2.0,
    val timeout: Duration = Duration.INFINITE,
) {
    init {
        require(maxAttempts >= 1) { "A call is attempted at least once." }
        require(!initialBackoff.isNegative() && initialBackoff <= maxBackoff) {
            "The initial backoff lies between zero and the maximum backoff."
        }
        require(multiplier >= 1.0) { "A backoff never shrinks." }
        require(timeout.isPositive()) { "The timeout of an attempt is positive." }
    }

    /**
     * This policy with the settings [retry] gives in place of its own. An initial backoff longer than this
     * policy's maximum raises the maximum to it, unless [retry] gives the maximum too.
     */
    fun with(retry: HandlerRetry?): RetryPolicy {
        if (retry == null || retry.isEmpty) {
            return this
        }
        val initial = retry.initialBackoff ?: initialBackoff
        return RetryPolicy(
            maxAttempts = retry.maxAttempts ?: maxAttempts,
            initialBackoff = initial,
            maxBackoff = retry.maxBackoff ?: maxBackoff.coerceAtLeast(initial),
            multiplier = retry.multiplier ?: multiplier,
            timeout = retry.timeout ?: timeout,
        )
    }

    /** The wait after [failures] failed calls (1 for the first). */
    fun backoff(failures: Int): Duration {
        var backoff = initialBackoff
        repeat(failures - 1) {
            backoff = (backoff * multiplier).coerceAtMost(maxBackoff)
        }
        return backoff
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is RetryPolicy && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "RetryPolicy(" +
        "maxAttempts=$maxAttempts, initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, multiplier=$multiplier, timeout=$timeout)"

    private fun valueFields(): List<Any?> = listOf(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)

    /** This RetryPolicy with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        maxAttempts: Int = this.maxAttempts,
        initialBackoff: Duration = this.initialBackoff,
        maxBackoff: Duration = this.maxBackoff,
        multiplier: Double = this.multiplier,
        timeout: Duration = this.timeout,
    ): RetryPolicy = RetryPolicy(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)

    companion object {
        /** A RetryPolicy of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            maxAttempts: Int = HandlerRetry.DEFAULT_MAX_ATTEMPTS,
            initialBackoff: Duration = 100.milliseconds,
            maxBackoff: Duration = 5.seconds,
            multiplier: Double = 2.0,
            timeout: Duration = Duration.INFINITE
        ): RetryPolicy = RetryPolicy(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)
    }
}

/**
 * The tuning of a [SubscriptionRuntime].
 *
 * - [maxInFlight]: the entries one subscription may hold in one partition between its checkpoint and the last entry
 *   it took. It bounds both memory and what a crash replays; a subscription that reaches it stops taking
 *   entries from the shared tail and catches up on its own once it has room.
 * - [retry]: how a failing handler is retried before its aggregate is parked, unless its subscription has a budget of
 *   its own ([retryOf]).
 * - [checkpointInterval]: how often each subscription saves its checkpoint; [SubscriptionRuntime.stop] saves too.
 * - [stopTimeout]: how long a stop waits for the handlers in flight before abandoning them; an abandoned entry is
 *   above the saved checkpoint, so it is handled again after the restart.
 * - [readRetry]: the backoff of a log read or a store write that failed; they are retried until the runtime stops.
 *   Its [RetryPolicy.maxAttempts] is the attempts after which the failure is logged as an error and retried at the
 *   maximum backoff ([retryForever]).
 * - [starts]: by subscription ([SubscriptionId]), where a subscription without a checkpoint in a partition starts
 *   instead of where its handler says (a projection from the beginning, a reactor or a saga from the end), as
 *   `wow.subscriptions.[<context>.<name>].start` configures it. A subscription with a checkpoint goes on from it.
 * - [parked]: how the parked entries are retried over the long horizon.
 * - [retries]: by subscription ([SubscriptionId]), the in-process retry budget the host gives it, as
 *   `wow.subscriptions.[<context>.<name>].retry.*` configures it: each setting given replaces the handler's own (`@Retry`,
 *   [Subscription.retry]), each one not given is the handler's, else [retry]'s.
 * - [systemic]: when a subscription takes its failures for systemic and pauses its partition instead of charging and
 *   parking the aggregates.
 * - [backpressure]: how a subscription retries an entry whose downstream asked it to come back later, and throttles
 *   its partition meanwhile.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class SubscriptionOptions @InternalWowApi constructor(
    val maxInFlight: Int = 1_024,
    val retry: RetryPolicy = RetryPolicy(),
    val checkpointInterval: Duration = 1.seconds,
    val stopTimeout: Duration = 10.seconds,
    val readRetry: RetryPolicy = RetryPolicy(
        maxAttempts = 5,
        initialBackoff = 100.milliseconds,
        maxBackoff = 5.seconds
    ),
    val starts: Map<SubscriptionId, SubscriptionStart> = emptyMap(),
    val parked: ParkedRetryOptions = ParkedRetryOptions(),
    val retries: Map<SubscriptionId, HandlerRetry> = emptyMap(),
    val systemic: SystemicFailureOptions = SystemicFailureOptions(),
    val backpressure: BackpressureOptions = BackpressureOptions(),
) {
    init {
        require(maxInFlight >= 1) { "A subscription holds at least one entry in flight." }
        require(checkpointInterval.isPositive()) { "The checkpoint interval is positive." }
        require(!stopTimeout.isNegative()) { "The stop timeout is never negative." }
    }

    /**
     * How [subscription]'s failing handler is retried before its aggregate is parked: the host's
     * [retries] for its name, over its handler's own [Subscription.retry], over [retry].
     */
    @InternalWowApi
    fun retryOf(subscription: Subscription): RetryPolicy {
        val configured = retries[subscription.id]
        val own = configured?.over(subscription.retry) ?: subscription.retry
        return try {
            retry.with(own)
        } catch (invalid: IllegalArgumentException) {
            throw IllegalArgumentException(
                "The retry budget of subscription ${subscription.name}: ${invalid.message}",
                invalid
            )
        }
    }

    /** This SubscriptionOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        maxInFlight: Int = this.maxInFlight,
        retry: RetryPolicy = this.retry,
        checkpointInterval: Duration = this.checkpointInterval,
        stopTimeout: Duration = this.stopTimeout,
        readRetry: RetryPolicy = this.readRetry,
        starts: Map<SubscriptionId, SubscriptionStart> = this.starts,
        parked: ParkedRetryOptions = this.parked,
        retries: Map<SubscriptionId, HandlerRetry> = this.retries,
        systemic: SystemicFailureOptions = this.systemic,
        backpressure: BackpressureOptions = this.backpressure,
    ): SubscriptionOptions = SubscriptionOptions(
        maxInFlight,
        retry,
        checkpointInterval,
        stopTimeout,
        readRetry,
        starts,
        parked,
        retries,
        systemic,
        backpressure,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is SubscriptionOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SubscriptionOptions(maxInFlight=$maxInFlight, retry=$retry, checkpointInterval=$checkpointInterval, " +
            "stopTimeout=$stopTimeout, readRetry=$readRetry, starts=$starts, parked=$parked, retries=$retries, " +
            "systemic=$systemic, backpressure=$backpressure)"

    private fun valueFields(): List<Any?> = listOf(
        maxInFlight,
        retry,
        checkpointInterval,
        stopTimeout,
        readRetry,
        starts,
        parked,
        retries,
        systemic,
        backpressure,
    )
}

/**
 * How a subscription answers backpressure: a failure classified
 * [me.ahoo.wow.spi.subscription.FailureClass.BACKPRESSURE], a downstream that is up but asked to be called later. The
 * entry is retried at most [maxAttempts] times, then its aggregate parks as after any spent budget; it never pauses the
 * partition. The wait before the next attempt starts at [initialBackoff], doubles to at most [maxBackoff], is shortened
 * by up to [jitter] (a share, 0 to 1) at random so the lanes do not retry in step, and is at least the downstream's
 * `Retry-After`. Meanwhile the partition is throttled: each backpressure halves the handlings it runs at once, down to
 * [minConcurrency], and each success raises it by half again, back to the in-flight bound (at once when no handling
 * waits under the limit).
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class BackpressureOptions @InternalWowApi constructor(
    val maxAttempts: Int = 20,
    val initialBackoff: Duration = 100.milliseconds,
    val maxBackoff: Duration = 10.seconds,
    val jitter: Double = 0.5,
    val minConcurrency: Int = 1,
) {
    init {
        require(maxAttempts >= 1) { "A backpressured entry is attempted at least once." }
        require(initialBackoff.isPositive() && initialBackoff <= maxBackoff) {
            "The backpressure backoff is positive and at most the maximum backoff."
        }
        require(jitter in 0.0..1.0) { "The jitter is a share of the backoff, from 0 to 1." }
        require(minConcurrency >= 1) { "A throttled partition still runs at least one handling at once." }
    }

    private val retries = RetryPolicy(initialBackoff = initialBackoff, maxBackoff = maxBackoff)

    /**
     * The wait after the [failures]th backpressure in a row (1 for the first): the backoff, shortened by [jitter] times
     * [random] (from 0 to 1), and at least [retryAfter].
     */
    internal fun backoff(failures: Int, retryAfter: Duration?, random: Double): Duration {
        val backoff = retries.backoff(failures)
        return (backoff * (1 - jitter * random)).coerceAtLeast(retryAfter ?: Duration.ZERO)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is BackpressureOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "BackpressureOptions(maxAttempts=$maxAttempts, initialBackoff=$initialBackoff, maxBackoff=$maxBackoff, " +
            "jitter=$jitter, minConcurrency=$minConcurrency)"

    private fun valueFields(): List<Any?> = listOf(maxAttempts, initialBackoff, maxBackoff, jitter, minConcurrency)
}

/**
 * When a subscription pauses a partition on a systemic failure: at once on a failure classified
 * [me.ahoo.wow.spi.subscription.FailureClass.SYSTEMIC]; and when [failingAggregates] different aggregates failed with
 * a recoverable one within [window], with no handling succeeding since (0 turns that off). While paused, one attempt
 * at a time probes whether what failed is back, the first [initialBackoff] after the pause, each next one twice as long
 * after the previous one failed, at most [maxBackoff].
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class SystemicFailureOptions @InternalWowApi constructor(
    val failingAggregates: Int = 3,
    val window: Duration = 10.seconds,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 30.seconds,
) {
    init {
        require(failingAggregates == 0 || failingAggregates >= 2) {
            "A pause takes at least two failing aggregates, or 0 for none; not $failingAggregates."
        }
        require(window.isPositive()) { "The window of the failing aggregates is positive." }
        require(initialBackoff.isPositive() && initialBackoff <= maxBackoff) {
            "The probe backoff is positive and at most the maximum backoff."
        }
    }

    private val probes = RetryPolicy(initialBackoff = initialBackoff, maxBackoff = maxBackoff)

    /** The wait before the probe after [failures] failed probes and the one that paused (1 for the first probe). */
    internal fun backoff(failures: Int): Duration = probes.backoff(failures)

    override fun equals(other: Any?): Boolean =
        this === other || other is SystemicFailureOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "SystemicFailureOptions(failingAggregates=$failingAggregates, window=$window, initialBackoff=$initialBackoff, " +
            "maxBackoff=$maxBackoff)"

    private fun valueFields(): List<Any?> = listOf(failingAggregates, window, initialBackoff, maxBackoff)
}

/**
 * How a [SubscriptionRuntime] retries its parked entries over the long horizon (9.x
 * `CompensationScheduler`): every [scanInterval] it reads its subscriptions' ledger entries and, in each partition it
 * runs, resumes those whose next retry is due ([me.ahoo.wow.spi.ledger.ParkedAggregate.nextRetryAt]) and runs the
 * requests an operator left on them.
 *
 * An entry's retry spec is the one an operator applied to it, else its subscription's in [specs] (by
 * [SubscriptionId], as `wow.subscriptions.[<context>.<name>].parked.*` configures it), else [spec]
 * (`wow.subscription.parked.*`).
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class ParkedRetryOptions @InternalWowApi constructor(
    val spec: ParkedRetrySpec = ParkedRetrySpec(),
    val specs: Map<SubscriptionId, ParkedRetrySpec> = emptyMap(),
    val scanInterval: Duration = 10.seconds,
) {
    init {
        require(scanInterval.isPositive()) { "The scan interval of the parked entries is positive." }
    }

    /** The retry spec of [subscription]'s entries that no operator applied one to. */
    fun specOf(subscription: SubscriptionId): ParkedRetrySpec = specs[subscription] ?: spec

    /** The retry spec [entry], parked by a subscription of [context], is retried by: its own, else its subscription's. */
    fun specOf(entry: ParkedAggregate, context: String): ParkedRetrySpec =
        entry.retrySpec ?: specOf(SubscriptionId(context, entry.subscription))

    override fun equals(other: Any?): Boolean =
        this === other || other is ParkedRetryOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ParkedRetryOptions(spec=$spec, specs=$specs, scanInterval=$scanInterval)"

    private fun valueFields(): List<Any?> = listOf(spec, specs, scanInterval)
}

/**
 * Calls [block] until it succeeds, waiting [policy]'s backoff between failures and logging each one as a failure
 * to [action]; cancellation stops it.
 *
 * [failed] hears each failure, before the backoff.
 *
 * [RetryPolicy.maxAttempts] is the budget of attempts before the failure is taken as lasting
 * (`wow.subscription.read-retry.error-after`): until it is spent, each failure is a warning and the backoff grows; once
 * it is, each further failure is an error, for an operator to see, and the call is retried at the maximum backoff. It
 * is never given up: nothing would read the partition again, and the node keeps its lease.
 */
@Suppress("TooGenericExceptionCaught") // Whatever failed is retried; only cancellation stops.
internal suspend fun <T> retryForever(
    policy: RetryPolicy,
    action: () -> String,
    failed: (Exception) -> Unit = {},
    block: suspend () -> T,
): T {
    var failures = 0
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures++
            failed(failure)
            val backoff = readBackoff(policy, failures)
            if (failures < policy.maxAttempts) {
                logger.warn(failure) { "Failed to ${action()} (attempt $failures); retrying in $backoff." }
            } else {
                logger.error(failure) {
                    "Failed to ${action()} $failures times, spending its ${policy.maxAttempts} attempts; still " +
                        "retrying, every $backoff."
                }
            }
            delay(backoff)
        }
    }
}

/**
 * The wait after [failures] failed calls under [policy] in [retryForever]: its backoff while attempts are left, its
 * maximum backoff once [RetryPolicy.maxAttempts] are spent.
 */
internal fun readBackoff(policy: RetryPolicy, failures: Int): Duration =
    if (failures < policy.maxAttempts) policy.backoff(failures) else policy.maxBackoff

private val logger = KotlinLogging.logger {}
