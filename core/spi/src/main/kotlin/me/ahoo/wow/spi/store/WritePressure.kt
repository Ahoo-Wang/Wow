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

package me.ahoo.wow.spi.store

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.time.Duration

/**
 * How hard one store's writes are pressed, for what shares the store with them to give way: the
 * node measures its appends' latency where it records their calls, and the store's pool says how many callers wait
 * for a connection ([Store.waitingForConnection]). Each [sample] closes one period and opens the next.
 */
@InternalWowApi
fun interface WritePressure {
    /** What the period since the last sample saw. */
    fun sample(): WritePressureSample

    @InternalWowApi
    companion object {
        /** No signal: no appends measured, nobody waiting. */
        @JvmField
        val NONE: WritePressure = WritePressure { WritePressureSample.NONE }
    }
}

/**
 * One period of a store's [WritePressure]: the [appends] it measured, their 90th percentile latency [appendP90]
 * (null when too few were measured to say), the slow-moving [baseline] of that latency (null until a period sets it),
 * and how many callers were [waitingForConnection] when it ended.
 */
@InternalWowApi
class WritePressureSample(
    val appends: Int,
    val appendP90: Duration?,
    val baseline: Duration?,
    val waitingForConnection: Int,
) {
    /** This sample with [waiting] callers waiting for a connection. */
    fun waiting(waiting: Int): WritePressureSample = WritePressureSample(appends, appendP90, baseline, waiting)

    override fun toString(): String =
        "WritePressureSample(appends=$appends, appendP90=$appendP90, baseline=$baseline, " +
            "waitingForConnection=$waitingForConnection)"

    @InternalWowApi
    companion object {
        @JvmField
        val NONE: WritePressureSample = WritePressureSample(0, null, null, 0)
    }
}
