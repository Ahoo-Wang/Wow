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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The node's defaults of how it retries a timer's send and an entry its downstream pushes back, and the one backoff
 * between retries: the runtime's options are built from them, and the test DSL settles a spec's timers and handlings by
 * them, so a spec retries and parks as a node of the default options does. Read on a retry only, never on a hot path.
 */
@InternalWowApi
object RetryDefaults {
    /** The attempts a timer's send takes before the timer parks (`wow.timers.max-attempts`). */
    const val TIMER_MAX_ATTEMPTS: Int = 10

    /** The wait after a timer's first failed send. */
    val TIMER_INITIAL_BACKOFF: Duration = 1.seconds

    /** The longest wait between a timer's sends. */
    val TIMER_MAX_BACKOFF: Duration = 5.minutes

    /** How much longer each wait after a timer's failed send is than the one before. */
    const val TIMER_MULTIPLIER: Double = 2.0

    /** The sends a busy target refuses before a timer's attempt is spent. */
    const val TIMER_MAX_BUSY_RETRIES: Int = 20

    /**
     * The attempts of an entry its downstream pushes back before its aggregate parks
     * (`wow.subscription.backpressure.max-attempts`).
     */
    const val BACKPRESSURE_MAX_ATTEMPTS: Int = 20

    /**
     * The wait after [failures] failed attempts (1 for the first): [initial], [multiplier] times longer after each next
     * one, never longer than [max].
     */
    fun backoff(initial: Duration, max: Duration, multiplier: Double, failures: Int): Duration {
        var backoff = initial
        repeat(failures - 1) {
            backoff = (backoff * multiplier).coerceAtMost(max)
        }
        return backoff
    }
}
