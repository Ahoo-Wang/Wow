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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a failing call is retried: at most [maxAttempts] calls in all, waiting [initialBackoff] after the first
 * failure and [multiplier] times longer after each next one, never longer than [maxBackoff].
 */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialBackoff: Duration = 100.milliseconds,
    val maxBackoff: Duration = 5.seconds,
    val multiplier: Double = 2.0,
) {
    init {
        require(maxAttempts >= 1) { "A call is attempted at least once." }
        require(!initialBackoff.isNegative() && initialBackoff <= maxBackoff) {
            "The initial backoff lies between zero and the maximum backoff."
        }
        require(multiplier >= 1.0) { "A backoff never shrinks." }
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
 * - [retry]: how a failing handler is retried before its aggregate is parked.
 * - [checkpointInterval]: how often each subscription saves its checkpoint; [SubscriptionRuntime.stop] saves too.
 * - [stopTimeout]: how long a stop waits for the handlers in flight before abandoning them; an abandoned entry is
 *   above the saved checkpoint, so it is handled again after the restart.
 * - [readRetry]: the backoff of a log read or a store write that failed; they are retried until the runtime stops
 *   (its [RetryPolicy.maxAttempts] is not used).
 */
data class SubscriptionOptions(
    val maxInFlight: Int = 1_024,
    val retry: RetryPolicy = RetryPolicy(),
    val checkpointInterval: Duration = 1.seconds,
    val stopTimeout: Duration = 10.seconds,
    val readRetry: RetryPolicy = RetryPolicy(
        maxAttempts = 1,
        initialBackoff = 100.milliseconds,
        maxBackoff = 5.seconds
    ),
) {
    init {
        require(maxInFlight >= 1) { "A subscription holds at least one entry in flight." }
        require(checkpointInterval.isPositive()) { "The checkpoint interval is positive." }
        require(!stopTimeout.isNegative()) { "The stop timeout is never negative." }
    }
}

/**
 * Calls [block] until it succeeds, waiting [policy]'s backoff between failures and logging each one as a failure
 * to [action]; cancellation stops it.
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
            val backoff = policy.backoff(failures)
            logger.warn(failure) { "Failed to ${action()} (attempt $failures); retrying in $backoff." }
            delay(backoff)
        }
    }
}

private val logger = KotlinLogging.logger {}
