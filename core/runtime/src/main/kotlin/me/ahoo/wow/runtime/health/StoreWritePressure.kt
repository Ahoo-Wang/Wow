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
import me.ahoo.wow.spi.store.WritePressure
import me.ahoo.wow.spi.store.WritePressureSample
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The append latency of one store, as the node's logs on it measure each call of `append` and
 * `appendAll` (the backend's own time, the wait for a connection included; not the partition append queue's): a
 * [sample] is the 90th percentile of the period's latencies against a baseline of the earlier periods.
 *
 * The baseline is the median of the latest [BASELINE_PERIODS] periods' percentiles: the store's normal latency, which
 * neither its quietest seconds pull down nor a burst of slow ones pulls up. A period of fewer than [MIN_APPENDS]
 * appends says nothing of the latency and leaves the baseline as it is. A period keeps at most the latest
 * [MAX_SAMPLES] latencies.
 *
 * Like any baseline that follows its store, a pressure that lasts longer than half the window becomes the store's
 * normal latency; the callers waiting for a connection, which the pool says, press all the same.
 */
@InternalWowApi
class StoreWritePressure internal constructor(
    /** The store, `wow.stores.<name>`. */
    val store: String,
) : WritePressure {
    private val latencies = LongArray(MAX_SAMPLES)
    private var count = 0
    private var next = 0

    /** The percentiles of the latest periods that measured enough appends, in nanoseconds, oldest overwritten. */
    private val percentiles = LongArray(BASELINE_PERIODS)
    private var periods = 0
    private var nextPeriod = 0

    /** Records one append call that took [nanos]. */
    @Synchronized
    internal fun record(nanos: Long) {
        latencies[next] = nanos
        next = (next + 1) % MAX_SAMPLES
        if (count < MAX_SAMPLES) {
            count++
        }
    }

    /** The period's percentile against the baseline of the periods before it, which this period then joins. */
    @Synchronized
    override fun sample(): WritePressureSample {
        val appends = count
        val before = baseline()?.nanoseconds
        if (appends < MIN_APPENDS) {
            reset()
            return WritePressureSample(appends, null, before, 0)
        }
        val sorted = latencies.copyOf(appends).also { it.sort() }
        val p90 = sorted[ceil(appends * PERCENTILE).toInt() - 1]
        reset()
        percentiles[nextPeriod] = p90
        nextPeriod = (nextPeriod + 1) % BASELINE_PERIODS
        if (periods < BASELINE_PERIODS) {
            periods++
        }
        return WritePressureSample(appends, p90.nanoseconds, before, 0)
    }

    /** The median of the kept percentiles (the lower middle one of an even count), or null before any. */
    private fun baseline(): Long? {
        if (periods == 0) return null
        val sorted = percentiles.copyOf(periods).also { it.sort() }
        return sorted[(periods - 1) / 2]
    }

    private fun reset() {
        count = 0
        next = 0
    }

    override fun toString(): String = "StoreWritePressure($store)"

    @InternalWowApi
    companion object {
        /** The fewest appends of a period whose latency says anything. */
        const val MIN_APPENDS: Int = 5

        /** The latencies a period keeps, its latest. */
        const val MAX_SAMPLES: Int = 1024

        /** The percentile a period reports. */
        const val PERCENTILE: Double = 0.9

        /**
         * The periods the baseline is the median of: a minute of one-second periods, long enough that a burst of
         * slow seconds (a prime, a compaction) stays in the minority, short enough that the latency of a store whose
         * load changed for good is its baseline within half a minute.
         */
        const val BASELINE_PERIODS: Int = 60
    }
}

/** The [StoreWritePressure] of each store the node's logs are on, by store name. */
internal class WritePressures {
    private val stores = ConcurrentHashMap<String, StoreWritePressure>()

    /** [store]'s, created on first use. */
    fun of(store: String): StoreWritePressure = stores.computeIfAbsent(store, ::StoreWritePressure)

    /** [store]'s, or null when no log of the node is on it. */
    fun find(store: String): StoreWritePressure? = stores[store]
}
