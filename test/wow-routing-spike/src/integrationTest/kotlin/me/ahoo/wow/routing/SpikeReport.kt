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

package me.ahoo.wow.routing

/** The spike's tables: saturation per variant, then the median of the repetitions of each open-loop cell. */
object SpikeReport {
    fun markdown(saturations: List<Measurement>, measurements: List<Measurement>): String = buildString {
        appendLine("## Saturation (closed loop, commits/s)")
        appendLine()
        appendLine("| Workload | Local | Partitioned | Ingress-hash |")
        appendLine("|---|---:|---:|---:|")
        saturations.groupBy { it.workload }.forEach { (workload, runs) ->
            val byRouting = runs.associateBy { it.routing }
            appendLine(
                "| $workload | " +
                    Routing.entries.joinToString(" | ") { "%,.0f".format(byRouting[it.label]?.throughput ?: Double.NaN) } +
                    " |",
            )
        }
        appendLine()
        appendLine("## Open loop (median of repetitions)")
        appendLine()
        appendLine(
            "| Workload | Load | Rate/s | Routing | Runs | Commits/s | p50 ms | p99 ms | p999 ms | steady p99 ms | " +
                "worst-second p99 ms | Hit | Reads/cmd | State reads/cmd | Conflicts/cmd | Contended | Forwarded | " +
                "Hop µs | CPU µs/cmd | GC ms |",
        )
        appendLine("|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        measurements.groupBy { Triple(it.workload, it.load, it.routing) }.forEach { (key, runs) ->
            fun median(metric: (Measurement) -> Double) = runs.map(metric).sorted()[runs.size / 2]
            appendLine(
                "| ${key.first} | ${key.second} | ${"%,.0f".format(runs.first().offeredRate)} | ${key.third} | " +
                    "${runs.size} | ${"%,.0f".format(median { it.throughput })} | " +
                    "${"%.2f".format(median { it.p50Millis })} | ${"%.2f".format(median { it.p99Millis })} | " +
                    "${"%.2f".format(median { it.p999Millis })} | ${"%.2f".format(median { it.steadyP99Millis })} | " +
                    "${"%.2f".format(median { it.worstSecondP99Millis })} | " +
                    "${"%.2f".format(median { it.cacheHitRate })} | ${"%.2f".format(median { it.readsPerCommand })} | " +
                    "${"%.2f".format(median { it.stateReadsPerCommand })} | " +
                    "${"%.3f".format(median { it.conflictsPerCommand })} | " +
                    "${"%.4f".format(median { it.contendedRatio })} | ${"%.2f".format(median { it.forwardRatio })} | " +
                    "${"%.0f".format(median { it.meanForwardMicros })} | " +
                    "${"%.0f".format(median { it.cpuMicrosPerCommand })} | ${"%.0f".format(median { it.gcMillis.toDouble() })} |",
            )
        }
    }
}
