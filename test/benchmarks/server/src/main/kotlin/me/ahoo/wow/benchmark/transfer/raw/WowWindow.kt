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

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wow's own run's append queue and saves over its window, a diagnostic of the calibration's Wow side: every
 * SAMPLE_MILLIS, the node's append calls and the streams they carried, its lanes' groups waiting for a committer and
 * its pool (the `bench-pipeline` line's sections, [pipeline]), and its save statements ([saves], cumulative from the
 * window's start). [stop] gives each half of the window's commands an append, append calls a second and snapshots a
 * save, so a cold first half (slower calls, larger appends) shows, and over the whole window the mean groups waiting
 * and threads waiting for a connection.
 */
class WowWindow(private val pipeline: () -> String?, private val saves: () -> Long) {
    private class Sample(val millis: Long, val calls: Double, val streams: Double, val saves: Long, val line: String)

    private val samples = ArrayList<Sample>()
    private val stopping = AtomicBoolean()
    private val sampler = Thread.ofPlatform().name("raw-wow-window").start {
        while (!stopping.get()) {
            val line = pipeline().orEmpty()
            val calls = field(line, "appendCalls", "calls")
            val streams = field(line, "appendCalls", "streams")
            if (calls != null && streams != null) {
                synchronized(samples) { samples += Sample(System.currentTimeMillis(), calls, streams, saves(), line) }
            }
            Thread.sleep(SAMPLE_MILLIS)
        }
    }

    /** Stops sampling: each half's figures and the window's means, empty without two samples a half. */
    fun stop(): Map<String, Double> {
        stopping.set(true)
        sampler.join()
        val taken = synchronized(samples) { samples.toList() }
        if (taken.size < MIN_SAMPLES) return emptyMap()
        val middle = taken[taken.size / 2]
        val (first, last) = taken.first() to taken.last()
        val seconds = (last.millis - first.millis) / MILLIS
        return halfOf(FIRST, first, middle) + halfOf(SECOND, middle, last) + mapOf(
            APPEND_CALLS_PER_SECOND_ALL to (last.calls - first.calls) / seconds,
            HANDED_GROUPS to taken.mapNotNull { field(it.line, "appendDepth", "handedGroups") }.average(),
            POOL_WAITING to taken.mapNotNull { field(it.line, "pool", "waiting") }.average(),
            POOL_ACTIVE to taken.mapNotNull { field(it.line, "pool", "active") }.average(),
        ).filterValues { !it.isNaN() }
    }

    private fun halfOf(half: String, from: Sample, to: Sample): Map<String, Double> {
        val calls = to.calls - from.calls
        val streams = to.streams - from.streams
        val saves = (to.saves - from.saves).toDouble()
        val seconds = (to.millis - from.millis) / MILLIS
        if (calls <= 0 || seconds <= 0) return emptyMap()
        return buildMap {
            put("$half $COMMANDS_PER_APPEND", streams / calls)
            put("$half $APPEND_CALLS_PER_SECOND", calls / seconds)
            if (saves > 0) put("$half $SNAPSHOTS_PER_SAVE", streams / saves)
        }
    }

    companion object {
        const val FIRST = "wowFirstHalf"
        const val SECOND = "wowSecondHalf"
        const val COMMANDS_PER_APPEND = "commandsPerAppend"
        const val APPEND_CALLS_PER_SECOND = "appendCallsPerSecond"
        const val SNAPSHOTS_PER_SAVE = "snapshotsPerSave"
        const val APPEND_CALLS_PER_SECOND_ALL = "wowAppendCallsPerSecond"
        const val HANDED_GROUPS = "wowHandedGroupsMean"
        const val POOL_WAITING = "wowPoolWaitingMean"
        const val POOL_ACTIVE = "wowPoolActiveMean"
        private const val SAMPLE_MILLIS = 100L
        private const val MIN_SAMPLES = 5
        private const val MILLIS = 1_000.0

        /** A number field of a section of a `bench-pipeline` line; null when the line has none. */
        fun field(line: String, section: String, name: String): Double? {
            val body = Regex("\"${Regex.escape(section)}\":\\{([^}]*)}").find(line)?.groupValues?.get(1) ?: return null
            return Regex("\"${Regex.escape(name)}\":(-?[0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        }
    }
}
