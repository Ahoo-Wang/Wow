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
 * that runs longer than [timeout] is cancelled and counts as failed (roadmap P.3); infinite by default.
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
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
}

/**
 * The tuning of a [SubscriptionRuntime].
 *
 * - [maxInFlight]: the entries one subscription may hold in one partition between its checkpoint and the last entry
 *   it took (R3-2). It bounds both memory and what a crash replays; a subscription that reaches it stops taking
 *   entries from the shared tail and catches up on its own once it has room.
 * - [retry]: how a failing handler is retried before its aggregate is parked, unless its subscription has a budget of
 *   its own ([retryOf]).
 * - [checkpointInterval]: how often each subscription saves its checkpoint; [SubscriptionRuntime.stop] saves too.
 * - [stopTimeout]: how long a stop waits for the handlers in flight before abandoning them; an abandoned entry is
 *   above the saved checkpoint, so it is handled again after the restart.
 * - [readRetry]: the backoff of a log read or a store write that failed; they are retried until the runtime stops.
 *   Its [RetryPolicy.maxAttempts] is the attempts after which the failure is logged as an error and retried at the
 *   maximum backoff (decision D195, [retryForever]).
 * - [starts]: by subscription name, where a subscription without a checkpoint in a partition starts instead of where
 *   its handler says (decision D154: a projection from the beginning, a reactor or a saga from the end), as
 *   `wow.subscriptions."<name>".start` configures it. A subscription with a checkpoint goes on from it.
 * - [parked]: how the parked entries are retried over the long horizon (roadmap P.1).
 * - [retries]: by subscription name, the in-process retry budget the host gives it (roadmap P.3), as
 *   `wow.subscriptions.[<name>].retry.*` configures it: each setting given replaces the handler's own (`@Retry`,
 *   [Subscription.retry]), each one not given is the handler's, else [retry]'s.
 */
data class SubscriptionOptions(
    val maxInFlight: Int = 1_024,
    val retry: RetryPolicy = RetryPolicy(),
    val checkpointInterval: Duration = 1.seconds,
    val stopTimeout: Duration = 10.seconds,
    val readRetry: RetryPolicy = RetryPolicy(
        maxAttempts = 5,
        initialBackoff = 100.milliseconds,
        maxBackoff = 5.seconds
    ),
    val starts: Map<String, SubscriptionStart> = emptyMap(),
    val parked: ParkedRetryOptions = ParkedRetryOptions(),
    val retries: Map<String, HandlerRetry> = emptyMap(),
) {
    init {
        require(maxInFlight >= 1) { "A subscription holds at least one entry in flight." }
        require(checkpointInterval.isPositive()) { "The checkpoint interval is positive." }
        require(!stopTimeout.isNegative()) { "The stop timeout is never negative." }
    }

    /**
     * How [subscription]'s failing handler is retried before its aggregate is parked (roadmap P.3): the host's
     * [retries] for its name, over its handler's own [Subscription.retry], over [retry].
     */
    @InternalWowApi
    fun retryOf(subscription: Subscription): RetryPolicy {
        val configured = retries[subscription.name]
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
}

/**
 * How a [SubscriptionRuntime] retries its parked entries over the long horizon (roadmap P.1, 9.x
 * `CompensationScheduler`): every [scanInterval] it reads its subscriptions' ledger entries and, in each partition it
 * runs, resumes those whose next retry is due ([me.ahoo.wow.spi.ledger.ParkedAggregate.nextRetryAt]) and runs the
 * requests an operator left on them (decision D141).
 *
 * An entry's retry spec is the one an operator applied to it, else its subscription's in [specs] (by name, as
 * `wow.subscriptions.[<name>].parked.*` configures it), else [spec] (`wow.subscription.parked.*`).
 */
data class ParkedRetryOptions(
    val spec: ParkedRetrySpec = ParkedRetrySpec(),
    val specs: Map<String, ParkedRetrySpec> = emptyMap(),
    val scanInterval: Duration = 10.seconds,
) {
    init {
        require(scanInterval.isPositive()) { "The scan interval of the parked entries is positive." }
    }

    /** The retry spec of [subscription]'s entries that no operator applied one to. */
    fun specOf(subscription: String): ParkedRetrySpec = specs[subscription] ?: spec

    /** The retry spec [entry] is retried by: its own, else its subscription's. */
    fun specOf(entry: ParkedAggregate): ParkedRetrySpec = entry.retrySpec ?: specOf(entry.subscription)
}

/**
 * Calls [block] until it succeeds, waiting [policy]'s backoff between failures and logging each one as a failure
 * to [action]; cancellation stops it.
 *
 * [RetryPolicy.maxAttempts] is the budget of attempts before the failure is taken as lasting (decision D195,
 * `wow.subscription.read-retry.max-attempts`): until it is spent, each failure is a warning and the backoff grows; once
 * it is, each further failure is an error, for an operator to see, and the call is retried at the maximum backoff. It
 * is never given up: nothing would read the partition again, and the node keeps its lease (decision D71).
 */
@Suppress("TooGenericExceptionCaught") // Whatever failed is retried; only cancellation stops.
internal suspend fun <T> retryForever(policy: RetryPolicy, action: () -> String, block: suspend () -> T): T {
    var failures = 0
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures++
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
