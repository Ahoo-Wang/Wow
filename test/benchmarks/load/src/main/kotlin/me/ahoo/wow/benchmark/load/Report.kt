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
        mix: Mix,
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
        val seconds = (0 until measure).mapNotNull { index ->
            measured.perSecond.get(index)?.let { histogram ->
                mapOf(
                    "second" to index,
                    "ok" to histogram.count,
                    "p50Ms" to histogram.percentile(50.0) / MICROS_PER_MILLI,
                    "p99Ms" to histogram.percentile(99.0) / MICROS_PER_MILLI,
                    "maxMs" to histogram.max / MICROS_PER_MILLI,
                )
            }
        }
        val worstSecond = seconds.maxByOrNull { it["p99Ms"] as Double }
        return mapOf(
            "schema" to "wow-e2e-bench/run/1",
            "label" to label,
            "version" to adapter.name,
            "scenario" to mix.label,
            "scenarioDescription" to mix.description,
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
                "rerouted" to measured.rerouted,
                "failedLatencyMs" to latencies(measured.errorLatency),
                "byScenario" to byScenario(measured, measure),
                "perSecond" to seconds,
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

    /** A mix's scenarios, each apart: what completed, what failed, its latencies; null for one scenario. */
    private fun byScenario(measured: OpenLoop.Measured, measure: Int): Map<String, Any?>? =
        measured.byScenario.keys.plus(measured.failedByScenario.keys).toSet()
            .associate { scenario ->
                val histogram = measured.byScenario[scenario] ?: Histogram()
                scenario.label to mapOf(
                    "ok" to histogram.count,
                    "failed" to (measured.failedByScenario[scenario]?.sum() ?: 0L),
                    "throughput" to histogram.count.toDouble() / measure,
                    "latencyMs" to latencies(histogram),
                )
            }
            .takeIf { it.isNotEmpty() }

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
            (results["rerouted"] as Long?)?.takeIf { it > 0 }?.let {
                appendLine("\nRerouted: $it operations a node refused ran on the next node.")
            }
            (results["byScenario"] as Map<String, Map<String, Any?>>?)?.let { appendScenarios(it) }
            resources?.let { appendResources(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun StringBuilder.appendScenarios(byScenario: Map<String, Map<String, Any?>>) {
        appendLine()
        appendLine("| Scenario | Throughput | OK | Failed | p50 | p99 | p999 |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        byScenario.forEach { (label, scenario) ->
            val latency = scenario["latencyMs"] as Map<String, Double>
            appendLine(
                "| $label | ${"%.1f".format(scenario["throughput"] as Double)}/s | ${scenario["ok"]} | ${scenario["failed"]} | " +
                    "${ms(latency["p50"])} | ${ms(latency["p99"])} | ${ms(latency["p999"])} |",
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun StringBuilder.appendResources(resources: Map<String, Any?>) {
        (resources["loadGenerator"] as Map<String, Any?>?)?.let {
            appendLine()
            appendLine(
                "Load generator: ${"%.2f".format(it["averageCores"] as Double)} cores of " +
                    "${it["availableProcessors"]} available to it.",
            )
        }
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
        // Each store's counters between the samples nearest the measured phase's edges (Kafka's: the warm-up to the
        // drain), and the seconds they cover.
        val stores = (resources["stores"] as Map<String, Map<String, Any?>?>?)?.filterValues { it?.get("delta") != null }
        if (!stores.isNullOrEmpty()) {
            appendLine()
            stores.forEach { (store, counters) ->
                appendLine(
                    "Store operations, $store (seconds ${counters!!["fromSecond"]} to ${counters["toSecond"]}): ${counters["delta"]}",
                )
            }
        }
        // PostgreSQL's commits and WAL flushes per second over the measured phase (B.O-3).
        val series = resources["postgresSeries"] as List<Map<String, Any?>>?
        if (!series.isNullOrEmpty()) {
            fun column(key: String) = series.map { (it[key] as Number).toDouble() }
            val syncMs = column("wal_sync_time")
            appendLine()
            appendLine(
                "PostgreSQL per second: commits ${"%.0f".format(column("xact_commit").average())}, WAL flushes " +
                    "${"%.0f".format(column("wal_sync").average())}, WAL flush time ${"%.0f".format(syncMs.average())} ms " +
                    "(max ${"%.0f".format(syncMs.max())} ms), WAL write time ${"%.0f".format(column("wal_write_time").average())} ms",
            )
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
