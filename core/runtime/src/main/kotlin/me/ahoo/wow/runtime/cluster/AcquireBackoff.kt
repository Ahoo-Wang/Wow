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

package me.ahoo.wow.runtime.cluster

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * PROTOTYPE (#6675, not for merge): one lease group's acquisitions held back while its lease store fails. A store
 * failure (an acquisition or a renewal that threw) opens a window of [base] doubled per consecutive failure up to [cap],
 * with full jitter; within it no acquisition is asked; after it, one at a time (a probe) until a call succeeds, which
 * closes the window. An acquisition the store answered, held by another node or not, is a success.
 */
internal class AcquireBackoff(
    private val base: Duration,
    private val cap: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    private var failures = 0
    private var until: TimeMark? = null
    private var probing = false

    /** Whether an acquisition may be asked now; one that may must report [succeeded] or [failed]. */
    @Synchronized
    fun tryEnter(): Boolean {
        if (failures == 0) {
            return true
        }
        val window = until
        if (window != null && window.hasNotPassedNow()) {
            return false
        }
        if (probing) {
            return false
        }
        probing = true
        return true
    }

    @Synchronized
    fun succeeded() {
        failures = 0
        until = null
        probing = false
    }

    @Synchronized
    fun failed() {
        failures++
        probing = false
        val exponent = (failures - 1).coerceAtMost(MAX_EXPONENT)
        val ceiling = (base * (1 shl exponent)).coerceAtMost(cap)
        until = timeSource.markNow() + ceiling * random.nextDouble()
    }

    private companion object {
        const val MAX_EXPONENT = 16
    }
}
