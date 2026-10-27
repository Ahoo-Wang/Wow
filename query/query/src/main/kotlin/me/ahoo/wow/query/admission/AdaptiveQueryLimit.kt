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

package me.ahoo.wow.query.admission

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.store.WritePressure
import me.ahoo.wow.spi.store.WritePressureSample
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How an [AdaptiveQueryLimit] reads its store's write pressure (decision D252): a period presses when the appends'
 * 90th percentile latency is above [latencyFactor] times its baseline (and at least [minLatency], so the noise of
 * sub-millisecond appends is no pressure), or when at least [connectionWaits] callers wait for a connection; [periods]
 * pressing periods in a row halve the limit, down to [floor]. A period is [interval] long.
 */
@InternalWowApi
class AdaptiveLimitOptions(
    val floor: Int = DEFAULT_FLOOR,
    val interval: Duration = DEFAULT_INTERVAL,
    val latencyFactor: Double = DEFAULT_LATENCY_FACTOR,
    val minLatency: Duration = DEFAULT_MIN_LATENCY,
    val connectionWaits: Int = DEFAULT_CONNECTION_WAITS,
    val periods: Int = DEFAULT_PERIODS,
) {
    init {
        require(floor >= 1) { "A store's query limit falls to 1 at the lowest, not $floor." }
        require(interval.isPositive()) { "The evaluation interval is positive, not $interval." }
        require(latencyFactor > 1.0) { "The latency factor is above 1, not $latencyFactor." }
        require(!minLatency.isNegative()) { "The least pressing latency is never negative, not $minLatency." }
        require(connectionWaits >= 1) { "At least one connection wait presses, not $connectionWaits." }
        require(periods >= 1) { "At least one pressing period lowers the limit, not $periods." }
    }

    override fun toString(): String =
        "AdaptiveLimitOptions(floor=$floor, interval=$interval, latencyFactor=$latencyFactor, " +
            "minLatency=$minLatency, connectionWaits=$connectionWaits, periods=$periods)"

    @InternalWowApi
    companion object {
        const val DEFAULT_FLOOR: Int = 1
        val DEFAULT_INTERVAL: Duration = 1.seconds
        const val DEFAULT_LATENCY_FACTOR: Double = 1.5
        val DEFAULT_MIN_LATENCY: Duration = 5.milliseconds
        const val DEFAULT_CONNECTION_WAITS: Int = 1
        const val DEFAULT_PERIODS: Int = 2

        @JvmField
        val DEFAULT: AdaptiveLimitOptions = AdaptiveLimitOptions()
    }
}

/**
 * The [bulkhead]'s limit, adapted to its store's write [pressure] (decision D252: the writes come first), by
 * additive increase and multiplicative decrease, one [evaluate] per period:
 *
 * - [AdaptiveLimitOptions.periods] pressing periods in a row: the limit halves, down to the floor, and the count of
 *   pressing periods starts over, so the next halving waits as long;
 * - a period that does not press, in which the limit held a query back (it waited or was refused): the limit rises by
 *   one, up to the bulkhead's ceiling;
 * - otherwise it stays.
 *
 * Lowering the limit cancels no running query (see [QueryBulkhead.limit]).
 */
@InternalWowApi
class AdaptiveQueryLimit(
    val bulkhead: QueryBulkhead,
    private val pressure: WritePressure,
    val options: AdaptiveLimitOptions = AdaptiveLimitOptions.DEFAULT,
) {
    /** The lowest limit: the floor, or the ceiling when that is lower. */
    val floor: Int = minOf(options.floor, bulkhead.ceiling)

    private var streak = 0

    /** Whether the last period pressed. */
    @Volatile
    var pressed: Boolean = false
        private set

    /** How many times the limit was lowered. */
    @Volatile
    var decreases: Long = 0
        private set

    /** The last period's sample of the write pressure. */
    @Volatile
    var lastSample: WritePressureSample = WritePressureSample.NONE
        private set

    /** Ends one period: reads the pressure and the bulkhead's contention, and moves the limit. */
    @Synchronized
    fun evaluate() {
        val sample = pressure.sample()
        val contended = bulkhead.takeContended()
        lastSample = sample
        pressed = presses(sample)
        val limit = bulkhead.limit
        if (!pressed) {
            streak = 0
            if (contended > 0 && limit < bulkhead.ceiling) {
                bulkhead.limit(limit + 1)
            }
            return
        }
        streak++
        if (streak < options.periods) return
        streak = 0
        val lowered = maxOf(floor, limit / 2)
        if (lowered < limit) {
            bulkhead.limit(lowered)
            decreases++
            logger.info { "The queries of store ${bulkhead.store} give way to its writes: $limit at once, now $lowered." }
        }
    }

    private fun presses(sample: WritePressureSample): Boolean {
        if (sample.waitingForConnection >= options.connectionWaits) return true
        val p90 = sample.appendP90 ?: return false
        val baseline = sample.baseline ?: return false
        return p90 >= options.minLatency && p90 > baseline * options.latencyFactor
    }

    override fun toString(): String =
        "AdaptiveQueryLimit(store=${bulkhead.store}, limit=${bulkhead.limit}, floor=$floor, " +
            "ceiling=${bulkhead.ceiling})"

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/**
 * Evaluates every one of [limits] each [interval] (decision D252), from [start] to [close]. An evaluation that fails is
 * logged; the next one runs all the same.
 */
@InternalWowApi
class AdaptiveQueryLimits(
    val limits: List<AdaptiveQueryLimit>,
    private val interval: Duration = AdaptiveLimitOptions.DEFAULT_INTERVAL,
    context: CoroutineContext = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + context)

    @Volatile
    private var evaluating: Job? = null

    init {
        require(interval.isPositive()) { "The evaluation interval is positive, not $interval." }
    }

    /** Starts evaluating, the first one [interval] from now; starting twice, or with no limits, does nothing. */
    @Synchronized
    fun start() {
        if (evaluating != null || limits.isEmpty()) return
        evaluating = scope.launch {
            while (true) {
                delay(interval)
                evaluate()
            }
        }
    }

    /** Evaluates every limit once. */
    @Suppress("TooGenericExceptionCaught") // A failed evaluation is logged; the next one runs all the same.
    fun evaluate() {
        limits.forEach { limit ->
            try {
                limit.evaluate()
            } catch (failure: Exception) {
                logger.warn(failure) { "Failed to evaluate $limit; evaluating again in $interval." }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
