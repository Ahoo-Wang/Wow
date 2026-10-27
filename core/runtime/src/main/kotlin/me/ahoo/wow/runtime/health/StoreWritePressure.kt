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
 * [sample] is the 90th percentile of the period's latencies against a slow-moving baseline of the earlier periods.
 *
 * The baseline follows the periods' percentiles as a moving average: slowly up ([RISING_WEIGHT], so a burst of slow
 * appends stands out against it for tens of periods), faster down ([FALLING_WEIGHT]). A period of fewer than
 * [MIN_APPENDS] appends says nothing of the latency and leaves the baseline as it is. A period keeps at most the
 * latest [MAX_SAMPLES] latencies.
 */
@InternalWowApi
class StoreWritePressure internal constructor(
    /** The store, `wow.stores.<name>`. */
    val store: String,
) : WritePressure {
    private val latencies = LongArray(MAX_SAMPLES)
    private var count = 0
    private var next = 0

    /** The baseline in nanoseconds; negative until a period sets it. */
    private var baseline = -1.0

    /** Records one append call that took [nanos]. */
    @Synchronized
    internal fun record(nanos: Long) {
        latencies[next] = nanos
        next = (next + 1) % MAX_SAMPLES
        if (count < MAX_SAMPLES) {
            count++
        }
    }

    /** The period's percentile against the baseline of the periods before it, which this period then moves. */
    @Synchronized
    override fun sample(): WritePressureSample {
        val appends = count
        val before = baseline.takeIf { it >= 0 }?.toLong()?.nanoseconds
        if (appends < MIN_APPENDS) {
            reset()
            return WritePressureSample(appends, null, before, 0)
        }
        val sorted = latencies.copyOf(appends).also { it.sort() }
        val p90 = sorted[ceil(appends * PERCENTILE).toInt() - 1]
        reset()
        baseline = when {
            baseline < 0 -> p90.toDouble()
            p90 > baseline -> baseline + (p90 - baseline) * RISING_WEIGHT
            else -> baseline + (p90 - baseline) * FALLING_WEIGHT
        }
        return WritePressureSample(appends, p90.nanoseconds, before, 0)
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

        /** How far a slower period moves the baseline up. */
        const val RISING_WEIGHT: Double = 0.02

        /** How far a faster period moves the baseline down. */
        const val FALLING_WEIGHT: Double = 0.2
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
