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

package me.ahoo.wow.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How often a client sends a request again that a peer answered with a problem a retry may get past (decision D254 ③),
 * each time after the `Retry-After` the peer answered with, else after [backoff] doubled at each attempt:
 *
 * - a 429 (`wow.command.busy`, `wow.query.busy`), when its `Retry-After` is at most [maxRetryAfter]; a peer that asks
 *   for a longer wait is not retried, its problem is the caller's;
 * - a command's problem that keeps the request id (`wow.command.unknown`, `wow.storage.unavailable`,
 *   `wow.node.closing`, `wow.command.contended`, ...): sent again with the same `Wow-Request-Id`, so the peer
 *   answers a command that did commit as the duplicate it is;
 * - a connection that could not be made: nothing was sent.
 *
 * Never a 504 (`wow.wait.timeout`, whose command may have committed and goes on; `wow.query.timeout`), nor any other
 * problem. At most [maxAttempts] attempts in all; 1 never retries ([NONE]).
 */
class RetryPolicy private constructor(
    val maxAttempts: Int,
    val maxRetryAfter: Duration,
    val backoff: Duration,
) {
    init {
        require(maxAttempts >= 1) { "A request is attempted at least once." }
        require(!maxRetryAfter.isNegative()) { "The longest Retry-After honored is not negative." }
        require(backoff.isPositive()) { "The backoff of a retry is positive." }
    }

    /**
     * How long to wait before attempt [attempt] + 1 (the first retry after attempt 1), [retryAfter] the peer asked for;
     * null when no attempt is left or the peer asked for longer than [maxRetryAfter].
     */
    internal fun delayBefore(attempt: Int, retryAfter: Duration?): Duration? = when {
        attempt >= maxAttempts -> null
        retryAfter != null -> retryAfter.takeIf { it <= maxRetryAfter }
        else -> (backoff * (1 shl (attempt - 1).coerceAtMost(MAX_SHIFT))).coerceAtMost(maxRetryAfter)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is RetryPolicy && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "RetryPolicy(maxAttempts=$maxAttempts, maxRetryAfter=$maxRetryAfter, backoff=$backoff)"

    private fun valueFields(): List<Any?> = listOf(maxAttempts, maxRetryAfter, backoff)

    companion object {
        private const val MAX_SHIFT = 10

        /** Three attempts, a `Retry-After` of up to 5 seconds honored, 200 milliseconds doubled without one. */
        @JvmField
        val DEFAULT: RetryPolicy = RetryPolicy(3, 5.seconds, 200.milliseconds)

        /** One attempt: every problem is the caller's. */
        @JvmField
        val NONE: RetryPolicy = RetryPolicy(1, Duration.ZERO, 200.milliseconds)

        /** A policy of the given bounds; each left out is as in [DEFAULT]. */
        @JvmStatic
        @JvmOverloads
        fun of(
            maxAttempts: Int = DEFAULT.maxAttempts,
            maxRetryAfter: Duration = DEFAULT.maxRetryAfter,
            backoff: Duration = DEFAULT.backoff,
        ): RetryPolicy = RetryPolicy(maxAttempts, maxRetryAfter, backoff)
    }
}
