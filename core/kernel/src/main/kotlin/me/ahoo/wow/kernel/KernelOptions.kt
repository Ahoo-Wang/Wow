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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The tuning of a [Kernel], set in code, not by configuration keys:
 * - [maxAttempts]: the times a command is decided again after a version conflict before it is answered as contended;
 * - [verifyDecidePurity]: whether every decide function is checked not to change the state it was given (the test
 *   DSL turns it on; it costs two serializations of the state per command);
 * - [refuseCreationRejectedWithEvents]: whether a creation rejected with events fails, naming its command, instead of
 *   being answered as a rejection whose events are dropped (the test DSL turns it on: such events can never be
 *   committed, since the aggregate does not exist);
 * - [reservationTtl]: how long a pending reservation lives unless the append confirms it first;
 * - [maxEarlyWait]: how long a scheduled command due later by the kernel's clock is held for its slot before it fails
 *   as not due, to be sent again;
 * - [recentRequests]: the requests each cached aggregate keeps of those it committed last, so that most commands skip
 *   looking their request id up (0 looks every one up);
 * - [maxReplay]: the most streams a read of a state at a version folds ([Kernel.stateAt]): a state further from its
 *   snapshot, or from version 1 without one, is refused with [ReplayLimitExceededException], which names the
 *   aggregate, instead of replaying a long history on one read.
 *
 * Fields may be added: construct it with named arguments; the constructor is internal to Wow.
 */
class KernelOptions @InternalWowApi constructor(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val verifyDecidePurity: Boolean = false,
    val reservationTtl: Duration = DEFAULT_RESERVATION_TTL,
    val maxEarlyWait: Duration = DEFAULT_MAX_EARLY_WAIT,
    val recentRequests: Int = Kernel.DEFAULT_RECENT_REQUESTS,
    val maxReplay: Int = DEFAULT_MAX_REPLAY,
    val refuseCreationRejectedWithEvents: Boolean = false,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt." }
        require(recentRequests >= 0) { "The recent requests kept per aggregate are never negative." }
        require(!maxEarlyWait.isNegative()) { "The maximum early wait is never negative." }
        require(maxReplay >= 1) { "A replay folds at least one stream." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is KernelOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "KernelOptions(maxAttempts=$maxAttempts, verifyDecidePurity=$verifyDecidePurity, " +
            "reservationTtl=$reservationTtl, maxEarlyWait=$maxEarlyWait, recentRequests=$recentRequests, " +
            "maxReplay=$maxReplay, refuseCreationRejectedWithEvents=$refuseCreationRejectedWithEvents)"

    private fun valueFields(): List<Any?> =
        listOf(
            maxAttempts,
            verifyDecidePurity,
            reservationTtl,
            maxEarlyWait,
            recentRequests,
            maxReplay,
            refuseCreationRejectedWithEvents,
        )

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        val DEFAULT_RESERVATION_TTL: Duration = 30.seconds
        val DEFAULT_MAX_EARLY_WAIT: Duration = 5.seconds

        /**
         * The most streams a read of a state at a version folds by default: a long history read whole on one request
         * costs memory and time the request is not worth; an aggregate's snapshots keep its reads within it.
         */
        const val DEFAULT_MAX_REPLAY: Int = 10_000
    }
}
