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

package me.ahoo.wow.runtime.timer

import me.ahoo.wow.runtime.subscription.RetryPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The tuning of a context's [TimerRunner] (decision D166; `wow.timers.*`).
 *
 * - [pollInterval]: how often each owned partition reads the store for the timers due within [lookahead]; between
 *   reads it sends what it loaded when each is due. A timer scheduled less than [lookahead] ahead is sent up to
 *   [pollInterval] late at most, plus the `timers` subscription's lag; a longer [lookahead] reads more timers at once.
 * - [retry]: how a technical failure to send a timer's command is retried, each attempt at its own due time
 *   ([RetryPolicy.backoff] after the failure), before the timer is parked under [PARKED_SUBSCRIPTION] after
 *   [RetryPolicy.maxAttempts] attempts in all (`wow.timers.max-attempts`).
 * - [expiresAfter]: a timer found due longer ago than this, after an outage, is not sent but cancelled with the
 *   outcome `EXPIRED`; null, the default, sends every timer however late.
 * - [batch]: the most timers a partition loads at once; [parallelism]: the most commands a partition sends at once.
 */
data class TimerOptions(
    val pollInterval: Duration = 1.seconds,
    val lookahead: Duration = 30.seconds,
    val retry: RetryPolicy = RetryPolicy(maxAttempts = 10, initialBackoff = 1.seconds, maxBackoff = 5.minutes),
    val expiresAfter: Duration? = null,
    val batch: Int = 1_000,
    val parallelism: Int = 64,
) {
    init {
        require(pollInterval.isPositive()) { "The poll interval is positive." }
        require(!lookahead.isNegative()) { "The lookahead is never negative." }
        require(expiresAfter == null || expiresAfter.isPositive()) { "An expiry is positive." }
        require(batch >= 1) { "A partition loads at least one timer at once." }
        require(parallelism >= 1) { "A partition sends at least one command at once." }
    }

    companion object {
        /** The subscription name a parked timer's target is recorded under in the failure ledger. */
        const val PARKED_SUBSCRIPTION: String = "wow.timers"
    }
}
