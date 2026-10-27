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

package me.ahoo.wow.example.eshop.e2e.soak

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The soak's resource curves and node signals (D224 note ⑦): every [interval] each live node's resident memory and
 * cumulative CPU time from `ps`, as one curve per node (`resources-<time>.jsonl`, one sample a line) and a summary per
 * node in the report; and at the end the last `bench-signals {json}` line of every node's log (SoakSignals), summed
 * per service, so a soak shows the partitions paused, the tails' store reads and the refusals it went through.
 */
class SoakResources(private val stack: SoakStack, private val intervalMillis: Long = INTERVAL_MILLIS) {
    private val samples = CopyOnWriteArrayList<Map<String, Any?>>()
    private val began = System.currentTimeMillis()

    @Volatile
    private var running = false
    private var sampler: Thread? = null

    fun start() {
        running = true
        sampler = thread(name = "soak-resources", isDaemon = true) {
            while (running) {
                sample()
                Thread.sleep(intervalMillis)
            }
        }
    }

    fun stop() {
        running = false
        sampler?.join(intervalMillis * 2)
    }

    private fun sample() {
        val at = System.currentTimeMillis() - began
        stack.allNodes().forEach { node ->
            ps(node.service.pid)?.let { (rssMb, cpuSeconds) ->
                samples += linkedMapOf(
                    "t" to at,
                    "node" to node.id,
                    "context" to node.context,
                    "rssMb" to rssMb,
                    "cpuSeconds" to cpuSeconds,
                )
            }
        }
    }

    /** The samples, one JSON line each. */
    fun writeCurves(file: File, toJson: (Any?) -> String) {
        file.printWriter().use { out -> samples.forEach { out.println(toJson(it)) } }
    }

    /** Per node: its samples, its RSS at most and last, and its average cores over the samples. */
    fun summary(): Map<String, Any?> = samples.groupBy { it["node"] as String }.toSortedMap().mapValues { (_, list) ->
        val first = list.first()
        val last = list.last()
        val seconds = ((last["t"] as Long) - (first["t"] as Long)) / MILLIS_PER_SECOND
        val cpu = (last["cpuSeconds"] as Double) - (first["cpuSeconds"] as Double)
        linkedMapOf(
            "context" to first["context"],
            "samples" to list.size,
            "rssMaxMb" to list.maxOf { it["rssMb"] as Double },
            "rssLastMb" to last["rssMb"],
            "averageCores" to if (seconds > 0) cpu / seconds else null,
        )
    }

    companion object {
        const val INTERVAL_MILLIS = 10_000L
        private const val MILLIS_PER_SECOND = 1_000.0
        private const val KB_PER_MB = 1_024.0
        private const val SIXTY = 60.0
        private const val SECONDS_PER_DAY = 86_400.0
        private const val PS_SECONDS = 10L

        /** `ps` of [pid]: its resident memory in MB and cumulative CPU seconds, or null once it is gone. */
        fun ps(pid: Long): Pair<Double, Double>? = runCatching {
            val process = ProcessBuilder("ps", "-o", "rss=,time=", "-p", pid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(PS_SECONDS, TimeUnit.SECONDS) || output.isEmpty()) return null
            val (rss, time) = output.split(Regex("\\s+"))
            rss.toDouble() / KB_PER_MB to cpuSeconds(time)
        }.getOrNull()

        /** `[[dd-]hh:]mm:ss[.ss]` as seconds. */
        fun cpuSeconds(time: String): Double {
            val (days, clock) = if ('-' in time) time.substringBefore('-').toDouble() to time.substringAfter('-') else 0.0 to time
            return clock.split(':').fold(0.0) { total, part -> total * SIXTY + part.toDouble() } + days * SECONDS_PER_DAY
        }

        /**
         * The last `bench-signals {json}` line of every log in [logs] (each node's output), summed per service (the
         * part of the log's name after `eshop-` and before the next `-`) and over all: `{kind: {key: count}}`.
         */
        fun signals(logs: File, parse: (String) -> Map<String, Any?>): Map<String, Any?> {
            val byService = sortedMapOf<String, MutableMap<String, MutableMap<String, Long>>>()
            val total = sortedMapOf<String, MutableMap<String, Long>>()
            logs.listFiles { file -> file.name.startsWith("eshop-") && file.name.endsWith(".log") }.orEmpty()
                .forEach { file ->
                    val line = file.useLines { lines -> lines.lastOrNull { MARKER in it } } ?: return@forEach
                    val counts = parse(line.substring(line.indexOf(MARKER) + MARKER.length - 1))
                    val service = file.name.removePrefix("eshop-").substringBefore('-')
                    counts.filterKeys { it != "t" }.forEach { (kind, keys) ->
                        (keys as Map<*, *>).forEach { (key, count) ->
                            val value = (count as Number).toLong()
                            byService.getOrPut(service) { sortedMapOf() }.getOrPut(kind) { sortedMapOf() }
                                .merge(key.toString(), value, Long::plus)
                            total.getOrPut(kind) { sortedMapOf() }.merge(key.toString(), value, Long::plus)
                        }
                    }
                }
            return linkedMapOf("total" to total, "byService" to byService)
        }

        private const val MARKER = "bench-signals {"
    }
}
