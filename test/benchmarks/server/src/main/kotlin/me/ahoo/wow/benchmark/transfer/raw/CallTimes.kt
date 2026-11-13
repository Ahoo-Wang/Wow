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

package me.ahoo.wow.benchmark.transfer.raw

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * The time a kind of call took, by name, cumulative: `<name> nanos` and `<name> calls` among a step's counters, so a
 * window's growth gives its mean ([means], `<name>Ms`). A diagnostic of where a store call's time goes: the store's
 * round trip, the whole call, the wait before it.
 */
class CallTimes {
    private val nanos = ConcurrentHashMap<String, LongAdder>()
    private val calls = ConcurrentHashMap<String, LongAdder>()

    /** Runs [block], its time counted as one [name] call. */
    fun <T> time(name: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            add(name, System.nanoTime() - started)
        }
    }

    /** One [name] call of [took] nanoseconds. */
    fun add(name: String, took: Long) {
        nanos.computeIfAbsent(name) { LongAdder() }.add(took)
        calls.computeIfAbsent(name) { LongAdder() }.increment()
    }

    /** Every name's nanoseconds and calls so far. */
    fun counters(): Map<String, Double> = buildMap {
        nanos.forEach { (name, sum) -> put("$name $NANOS", sum.sum().toDouble()) }
        calls.forEach { (name, sum) -> put("$name $CALLS", sum.sum().toDouble()) }
    }

    companion object {
        private const val NANOS = "nanos"
        private const val CALLS = "calls"
        private const val NANOS_PER_MILLI = 1_000_000.0

        /** Each name's mean call over [growth] (its counters' growth), `<name>Ms`; none for a name with no call. */
        fun means(growth: Map<String, Double>): Map<String, Double> = buildMap {
            growth.forEach { (key, count) ->
                if (key.endsWith(" $CALLS") && count > 0) {
                    val name = key.removeSuffix(" $CALLS")
                    growth["$name $NANOS"]?.let { put("${name}Ms", it / count / NANOS_PER_MILLI) }
                }
            }
        }
    }
}
