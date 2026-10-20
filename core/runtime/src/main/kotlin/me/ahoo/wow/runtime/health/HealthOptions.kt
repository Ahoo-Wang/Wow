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

package me.ahoo.wow.runtime.health

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The thresholds of a node's health (review R3.5-3), conservative by default: a probe that fails too soon takes
 * nodes out of service, or restarts them, for what a retry would have absorbed.
 *
 * - [sampleInterval]: how often [NodeHealth] samples what a probe must not wait for (each log's head, the
 *   subscriptions' lag and parked counts), and probes the stores the node has not called successfully since.
 * - [storeDownAfter]: how long a store must keep failing, with no success since, before it is `DOWN`; failing for
 *   less, `DEGRADED`. A failure is a call or a probe that failed, or a probe that timed out (after this long, at least
 *   a second); a probe still under way is not one. With zero, a store is `DOWN` at its first failure, never because a
 *   sample is pending. [storeWindow]: a store with a failure this recent is `DEGRADED`.
 * - [handoverGrace]: a lease group whose owned partitions differ from the node's share for longer is `DEGRADED`, and
 *   `DOWN` when the node owns none of a share that is not empty (a hand-over within it is normal).
 * - [topicsRequired]: whether a topic the node is still joining (decision D134, review R2.1-8) makes it not ready.
 * - [executorSaturation]: the share of `wow.executor.max-in-flight` at which the executor is `DEGRADED`.
 * - [lagDegradedAt], [parkedDegradedAt]: the entries behind the log's head, and the parked entries (counted up to
 *   it), at which a subscription is `DEGRADED`.
 * - [stallTimeout]: the liveness fails when the node's dispatcher has not ticked for this long, or when commands are
 *   in flight, the stores answer, and none has finished for this long.
 */
@InternalWowApi
class HealthOptions(
    val sampleInterval: Duration = DEFAULT_SAMPLE_INTERVAL,
    val storeDownAfter: Duration = DEFAULT_STORE_DOWN_AFTER,
    val storeWindow: Duration = DEFAULT_STORE_WINDOW,
    val handoverGrace: Duration = DEFAULT_HANDOVER_GRACE,
    val topicsRequired: Boolean = true,
    val executorSaturation: Double = DEFAULT_EXECUTOR_SATURATION,
    val lagDegradedAt: Long = DEFAULT_LAG_DEGRADED_AT,
    val parkedDegradedAt: Int = DEFAULT_PARKED_DEGRADED_AT,
    val stallTimeout: Duration = DEFAULT_STALL_TIMEOUT,
) {
    init {
        require(sampleInterval.isPositive()) { "The health sample interval is positive." }
        require(!storeDownAfter.isNegative()) { "A store is down after a duration that is not negative." }
        require(storeWindow.isPositive()) { "The window of a store's failures is positive." }
        require(!handoverGrace.isNegative()) { "The hand-over grace is not negative." }
        require(executorSaturation > 0.0 && executorSaturation <= 1.0) {
            "The executor's saturation is a share of its bound, in (0, 1]."
        }
        require(lagDegradedAt >= 1) { "A subscription is degraded at a lag of at least one entry." }
        require(parkedDegradedAt >= 1) { "A subscription is degraded at one parked entry at least." }
        require(stallTimeout.isPositive()) { "The stall timeout is positive." }
    }

    override fun toString(): String =
        "HealthOptions(sampleInterval=$sampleInterval, storeDownAfter=$storeDownAfter, storeWindow=$storeWindow, " +
            "handoverGrace=$handoverGrace, topicsRequired=$topicsRequired, executorSaturation=$executorSaturation, " +
            "lagDegradedAt=$lagDegradedAt, parkedDegradedAt=$parkedDegradedAt, stallTimeout=$stallTimeout)"

    @InternalWowApi
    companion object {
        val DEFAULT_SAMPLE_INTERVAL: Duration = 10.seconds
        val DEFAULT_STORE_DOWN_AFTER: Duration = 15.seconds
        val DEFAULT_STORE_WINDOW: Duration = 1.minutes
        val DEFAULT_HANDOVER_GRACE: Duration = 1.minutes
        const val DEFAULT_EXECUTOR_SATURATION: Double = 0.9
        const val DEFAULT_LAG_DEGRADED_AT: Long = 10_000
        const val DEFAULT_PARKED_DEGRADED_AT: Int = 100
        val DEFAULT_STALL_TIMEOUT: Duration = 3.minutes
    }
}
