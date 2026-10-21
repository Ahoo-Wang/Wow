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

package me.ahoo.wow.benchmark.load

/**
 * The sampled lines of [Resources]: each sample is the time it arrived (epoch milliseconds) and its values, and the
 * measured phase a range of those times.
 */
object Samples {
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val UTIME = 11
    private const val STIME = 12
    private const val RSS_PAGES = 21
    private const val KB_PER_MB = 1_024.0

    /** [line] split by [separator] into numbers named [names], or null when it is not such a line. */
    fun columns(line: String, names: List<String>, separator: Char): Map<String, Double>? {
        val values = line.trim().split(separator).map { it.trim().toDoubleOrNull() }
        if (values.size != names.size || values.any { it == null }) return null
        return names.zip(values.map { it!! }).toMap()
    }

    /** A store sample's own time, its `epoch` column in milliseconds: its output may reach the reader late. */
    fun at(sample: Map<String, Double>): Long = ((sample["epoch"] ?: 0.0) * MILLIS_PER_SECOND).toLong()

    /** The lines of [samples] that arrived inside [window]. */
    fun within(samples: List<Pair<Long, String>>, window: LongRange): List<String> =
        samples.filter { it.first in window }.map { it.second }

    /** The last sample at or before [time], else the first one after it. */
    fun <T> atOrBefore(samples: List<Pair<Long, T>>, time: Long): Pair<Long, T>? =
        samples.lastOrNull { it.first <= time } ?: samples.firstOrNull()

    /** The first sample at or after [time], else the last one before it. */
    fun <T> atOrAfter(samples: List<Pair<Long, T>>, time: Long): Pair<Long, T>? =
        samples.firstOrNull { it.first >= time } ?: samples.lastOrNull()

    /**
     * The per-second rate of each of [keys] between consecutive samples, from the last one at or before the [window]'s
     * start to the first at or after its end; `t` is the later sample's second in the window.
     */
    fun perSecond(
        samples: List<Pair<Long, Map<String, Double>>>,
        window: LongRange,
        keys: List<String>
    ): List<Map<String, Any>> {
        val first = atOrBefore(samples, window.first) ?: return emptyList()
        val last = atOrAfter(samples, window.last) ?: return emptyList()
        val span = samples.filter { it.first in first.first..last.first }
        return span.zipWithNext().mapNotNull { (earlier, later) ->
            val seconds = (later.first - earlier.first) / MILLIS_PER_SECOND
            if (seconds <= 0) return@mapNotNull null
            mapOf("t" to (later.first - window.first) / MILLIS_PER_SECOND) +
                keys.associateWith { key -> ((later.second[key] ?: 0.0) - (earlier.second[key] ?: 0.0)) / seconds }
        }
    }

    /** [value] as a Long when it is whole, for counters. */
    fun whole(value: Double): Number = if (value == Math.rint(value) && !value.isInfinite()) value.toLong() else value

    /**
     * A `/proc/<pid>/stat` line as the process's CPU seconds (user and system, [clockTicks] a second) and resident
     * memory ([pageKb] a page), or null when it is not one. The command name may hold spaces: the fields follow its `)`.
     */
    fun procStat(stat: String, clockTicks: Double, pageKb: Double): Map<String, Any>? {
        val fields = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
        if (fields.size <= RSS_PAGES) return null
        val utime = fields[UTIME].toDoubleOrNull() ?: return null
        val stime = fields[STIME].toDoubleOrNull() ?: return null
        val rss = fields[RSS_PAGES].toDoubleOrNull() ?: return null
        return mapOf("cpuSeconds" to (utime + stime) / clockTicks, "rssMb" to rss * pageKb / KB_PER_MB)
    }
}
