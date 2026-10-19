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

import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicReferenceArray

/** The results of a run: the JSON the report is built from, and a short Markdown summary of it. */
object Report {
    private const val MICROS_PER_MILLI = 1_000.0
    private val PERCENTILES = listOf("p50" to 50.0, "p95" to 95.0, "p99" to 99.0, "p999" to 99.9)

    /** A histogram's percentiles and max in milliseconds. */
    fun latencies(histogram: Histogram): Map<String, Double> = buildMap {
        PERCENTILES.forEach { (name, percentile) -> put(name, histogram.percentile(percentile) / MICROS_PER_MILLI) }
        put("max", histogram.max / MICROS_PER_MILLI)
        put("mean", histogram.mean / MICROS_PER_MILLI)
    }

    @Suppress("LongParameterList")
    fun result(
        label: String,
        adapter: VersionAdapter,
        scenario: Scenario,
        rate: Double,
        warmUp: Int,
        measure: Int,
        keys: KeyChooser,
        seed: Long,
        targets: List<URI>,
        startedAt: Instant,
        measured: OpenLoop.Measured,
        resources: Map<String, Any?>?,
        environment: Map<String, Any?>,
        meta: Map<String, String>,
    ): Map<String, Any?> {
        val ok = measured.latency.count
        val errors = measured.errors.mapValues { it.value.sum() }.toSortedMap()
        val failed = errors.values.sum()
        val seconds = perSecond(measured.perSecond, withMax = true)
        val warmUpSeconds = perSecond(measured.warmUpPerSecond, withMax = false)
        val worstSecond = seconds.maxByOrNull { it["p99Ms"] as Double }
        return mapOf(
            "schema" to "wow-e2e-bench/run/1",
            "label" to label,
            "version" to adapter.name,
            "scenario" to scenario.label,
            "scenarioDescription" to scenario.description,
            "startedAt" to startedAt.toString(),
            "load" to mapOf(
                "model" to "open loop, fixed arrival rate; latency from each operation's scheduled time",
                "rate" to rate,
                "warmUpSeconds" to warmUp,
                "measureSeconds" to measure,
                "seed" to seed,
                "keys" to keys.describe(),
                "targets" to targets.map { it.toString() },
            ),
            "results" to mapOf(
                "scheduled" to measured.scheduled.get(),
                "ok" to ok,
                "failed" to failed,
                "errors" to errors,
                "errorPercent" to percent(failed, ok + failed),
                "throughput" to ok.toDouble() / measure,
                "latencyMs" to latencies(measured.latency),
                "worstSecondP99Ms" to worstSecond?.get("p99Ms"),
                "worstSecond" to worstSecond?.get("second"),
                "maxSendLagMs" to measured.maxSendLagNanos.get() / OpenLoop.NANOS_PER_MICRO / MICROS_PER_MILLI,
                "failedLatencyMs" to latencies(measured.errorLatency),
                "perSecond" to seconds,
                "warmUpPerSecond" to warmUpSeconds,
                "histogram" to mapOf(
                    "unit" to "microseconds",
                    "subBucketBits" to Histogram.SUB_BITS,
                    "buckets" to measured.latency.buckets(),
                ),
            ),
            "resources" to resources,
            "environment" to environment,
            "meta" to meta,
        )
    }

    /** One entry per second that recorded a latency: its count, p50, p99 and, [withMax], its max. */
    private fun perSecond(seconds: AtomicReferenceArray<Histogram>, withMax: Boolean): List<Map<String, Any>> =
        (0 until seconds.length()).mapNotNull { index ->
            seconds.get(index)?.let { histogram ->
                buildMap {
                    put("second", index)
                    put("ok", histogram.count)
                    put("p50Ms", histogram.percentile(50.0) / MICROS_PER_MILLI)
                    put("p99Ms", histogram.percentile(99.0) / MICROS_PER_MILLI)
                    if (withMax) put("maxMs", histogram.max / MICROS_PER_MILLI)
                }
            }
        }

    /** A few lines for a person: what ran, how fast, how long it took, what it cost. */
    @Suppress("UNCHECKED_CAST")
    fun summary(result: Map<String, Any?>): String {
        val load = result["load"] as Map<String, Any?>
        val results = result["results"] as Map<String, Any?>
        val latency = results["latencyMs"] as Map<String, Double>
        val meta = result["meta"] as Map<String, String>
        val resources = result["resources"] as Map<String, Any?>?
        val keys = load["keys"] as Map<String, Any?>
        return buildString {
            appendLine("# ${result["label"]}: ${result["version"]} ${result["scenario"]}")
            appendLine()
            appendLine(
                "${result["scenarioDescription"]}; ${meta.entries.joinToString(", ") { "${it.key} ${it.value}" }}."
            )
            appendLine()
            appendLine(
                "Open loop at ${load["rate"]}/s, ${keys["distribution"]} over ${keys["accounts"]} accounts, " +
                    "${load["warmUpSeconds"]} s warm-up, ${load["measureSeconds"]} s measured.",
            )
            appendLine()
            appendLine("| Throughput | OK | Failed | p50 | p95 | p99 | p999 | Max | Worst-second p99 |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            appendLine(
                "| ${"%.1f".format(results["throughput"] as Double)}/s | ${results["ok"]} | ${results["failed"]} | " +
                    "${ms(latency["p50"])} | ${ms(latency["p95"])} | ${ms(latency["p99"])} | ${ms(latency["p999"])} | " +
                    "${ms(latency["max"])} | ${ms(results["worstSecondP99Ms"] as Double?)} |",
            )
            (results["errors"] as Map<String, Long>).takeIf { it.isNotEmpty() }?.let { appendLine("\nErrors: $it") }
            resources?.let { appendResources(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun StringBuilder.appendResources(resources: Map<String, Any?>) {
        val processes = resources["processes"] as Map<String, Map<String, Any?>>?
        if (!processes.isNullOrEmpty()) {
            appendLine()
            appendLine("| Process | CPU (cores) | RSS max | Heap used max | GC count | GC time |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            processes.forEach { (name, process) -> appendLine(processRow(name, process)) }
        }
        val containers = resources["containers"] as Map<String, Map<String, Any?>>?
        if (!containers.isNullOrEmpty()) {
            appendLine()
            appendLine("| Container | CPU avg | CPU max | Memory |")
            appendLine("| --- | --- | --- | --- |")
            containers.forEach { (name, container) ->
                appendLine(
                    "| $name | ${"%.1f".format(container["cpuPercentAverage"] as Double)}% | " +
                        "${"%.1f".format(container["cpuPercentMax"] as Double)}% | ${container["lastMemory"]} |",
                )
            }
        }
        val stores = (resources["stores"] as Map<String, Any?>?)?.get("delta") as Map<String, Any?>?
        stores?.filterValues { it != null }?.takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine("Store operations in the measured phase: $it")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun processRow(name: String, process: Map<String, Any?>): String {
        val gc = process["gc"] as Map<String, Any?>
        val delta = gc["delta"] as Map<String, Double>?
        val count = delta?.let { (it["YGC"] ?: 0.0) + (it["FGC"] ?: 0.0) + (it["CGC"] ?: 0.0) }
        val heap = (gc["heapUsedMaxMb"] as Double?)?.let { "%.0f MB".format(it) } ?: "-"
        val time = delta?.get("GCT")?.let { "%.3f s".format(it) } ?: "-"
        return "| $name | ${"%.2f".format(process["averageCores"] as Double)} | " +
            "${"%.0f".format(process["rssMaxMb"] as Double)} MB | $heap | ${count?.let { "%.0f".format(it) } ?: "-"} | $time |"
    }

    private fun ms(value: Double?): String = value?.let { "%.1f ms".format(it) } ?: "-"
}
