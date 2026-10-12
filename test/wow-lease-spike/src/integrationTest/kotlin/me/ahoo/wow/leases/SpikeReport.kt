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

package me.ahoo.wow.leases

/** The summary tables of a run, in Markdown; the report is written from them. */
object SpikeReport {
    private const val MILLIS = 1_000.0
    private const val PERCENT = 100.0
    private const val P95 = 0.95

    fun markdown(steps: List<StepResult>, leases: List<LeaseSeries>): String = buildString {
        val changes = steps.filter { it.change != Change.START }
        appendLine("## Convergence (seconds)")
        appendLine()
        appendLine(
            "| Backend | Config | P | Change | n | median | 95% CI of median | p95 | max | leases median | " +
                "members median | routes median | not converged |",
        )
        appendLine("|---|---|---:|---|---:|---:|---|---:|---:|---:|---:|---:|---:|")
        cells(steps).forEach { (key, cell) ->
            val converged = cell.mapNotNull { it.convergedMillis }.map { it / MILLIS }
            val (low, high) = Stats.medianInterval(converged)
            appendLine(
                "| ${key.backend} | ${key.config} | ${key.partitions} | ${key.label} | ${cell.size} | " +
                    "${f2(Stats.median(converged))} | ${f2(low)}–${f2(high)} | ${f2(Stats.quantile(converged, P95))} | " +
                    "${f2(converged.maxOrNull() ?: Double.NaN)} | ${f2(medianOf(cell) { it.leasesMillis })} | " +
                    "${f2(medianOf(cell) { it.membershipMillis })} | ${f2(medianOf(cell) { it.routesMillis })} | " +
                    "${cell.count { it.convergedMillis == null }} |",
            )
        }
        appendLine()
        appendLine("## Partitions moved (all backends and configurations)")
        appendLine()
        appendLine("| P | Change | n | moved mean | moved min–max | HRW share of the node mean | P/N ideal | extra moves (sum) |")
        appendLine("|---:|---|---:|---:|---|---:|---:|---:|")
        changes.groupBy { Triple(it.partitions, it.change, it.transition) }.toSortedMap(
            compareBy<Triple<Int, Change, String>> { it.first }.thenBy { it.second }.thenBy { it.third },
        ).forEach { (key, cell) ->
            val ideal = key.first.toDouble() / maxOf(cell.first().from, cell.first().to)
            appendLine(
                "| ${key.first} | ${key.second} ${key.third} | ${cell.size} | ${f1(cell.map { it.moved }.average())} | " +
                    "${cell.minOf { it.moved }}–${cell.maxOf { it.moved }} | ${f1(cell.map { it.predicted }.average())} | " +
                    "${f1(ideal)} | ${cell.sumOf { it.extraMoves }} |",
            )
        }
        appendLine()
        appendLine("## Duplicate handling and partitions without a lease")
        appendLine()
        appendLine(
            "| Backend | Config | P | Change | entries | repeats | repeated % | moved-partition entries | " +
                "repeated % of those | repeats per step median / max | unowned partition-s median | max unowned median |",
        )
        appendLine("|---|---|---:|---|---:|---:|---:|---:|---:|---|---:|---:|")
        cells(changes).forEach { (key, cell) ->
            val entries = cell.sumOf { it.entries }
            val repeats = cell.sumOf { it.repeats }
            val movedEntries = cell.sumOf { it.movedEntries }
            val movedRepeats = cell.sumOf { it.movedRepeats }
            appendLine(
                "| ${key.backend} | ${key.config} | ${key.partitions} | ${key.label} | $entries | $repeats | " +
                    "${pct(repeats, entries)} | $movedEntries | ${pct(movedRepeats, movedEntries)} | " +
                    "${f1(Stats.median(cell.map { it.repeats.toDouble() }))} / ${cell.maxOf { it.repeats }} | " +
                    "${f1(Stats.median(cell.map { it.unownedPartitionSeconds }))} | " +
                    "${f1(Stats.median(cell.map { it.maxUnowned.toDouble() }))} |",
            )
        }
        appendLine()
        appendLine("## Commands from the change until a second after convergence (sums over the repetitions)")
        appendLine()
        appendLine(
            "| Backend | Config | P | Change | sent | succeeded % | contended | unknown | other failed | threw | " +
                "conflicts | non-owner executions | forward failures | lost leases | p99 ms | baseline p99 ms | max ms |",
        )
        appendLine("|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        cells(changes).forEach { (key, cell) ->
            val windows = cell.mapNotNull { it.window }
            val baselines = cell.mapNotNull { it.baseline }
            val sent = windows.sumOf { it.sent }
            appendLine(
                "| ${key.backend} | ${key.config} | ${key.partitions} | ${key.label} | $sent | " +
                    "${pct(windows.sumOf { it.succeeded }, sent)} | ${windows.sumOf { it.contended }} | " +
                    "${windows.sumOf { it.unknown }} | ${windows.sumOf { it.otherFailed }} | ${windows.sumOf { it.errors }} | " +
                    "${windows.sumOf { it.conflicts }} | ${windows.sumOf { it.nonOwner }} | " +
                    "${windows.sumOf { it.forwardFailures }} | ${windows.sumOf { it.lostLeases }} | " +
                    "${f1(Stats.median(windows.map { it.p99Millis }))} | " +
                    "${f1(Stats.median(baselines.map { it.p99Millis }))} | ${f1(windows.maxOfOrNull { it.maxMillis } ?: Double.NaN)} |",
            )
        }
        appendLine()
        appendLine("## Second by second around a kill and a graceful leave (mean over the repetitions)")
        changes.filter { it.change == Change.KILL || it.change == Change.LEAVE }
            .groupBy { Key(it.backend, it.config, it.partitions, "${it.change} ${it.transition}") }
            .forEach { (key, cell) ->
                appendLine()
                appendLine("### ${key.backend} ${key.config} P=${key.partitions} ${key.label}")
                appendLine()
                appendLine("| s | sent | succeeded | failed | conflicts | non-owner | forward failures | lost leases | p99 ms |")
                appendLine("|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
                cell.flatMap { it.profile }.groupBy { it.offset ?: 0 }.toSortedMap().forEach { (offset, rows) ->
                    appendLine(
                        "| $offset | ${f1(rows.map { it.sent }.average())} | ${f1(rows.map { it.succeeded }.average())} | " +
                            "${f1(rows.map { it.failed }.average())} | ${f1(rows.map { it.conflicts }.average())} | " +
                            "${f1(rows.map { it.nonOwner }.average())} | ${f1(rows.map { it.forwardFailures }.average())} | " +
                            "${f1(rows.map { it.lostLeases }.average())} | " +
                            "${f1(Stats.median(rows.map { it.p99Millis }.filter { !it.isNaN() }))} |",
                    )
                }
            }
        appendLine()
        appendLine("## Lease-store calls (all nodes of a series)")
        appendLine()
        appendLine("| Backend | Config | P | operation | calls/s | p50 ms | p99 ms | max ms | failures (series) |")
        appendLine("|---|---|---:|---|---:|---:|---:|---:|---:|")
        leases.forEach { series ->
            series.operations.forEach { operation ->
                appendLine(
                    "| ${series.backend} | ${series.config} | ${series.partitions} | ${operation.operation} | " +
                        "${f1(operation.perSecond)} | ${f2(operation.p50Millis)} | ${f2(operation.p99Millis)} | " +
                        "${f1(operation.maxMillis)} | ${series.failures} |",
                )
            }
        }
    }

    private data class Key(val backend: String, val config: String, val partitions: Int, val label: String)

    private fun cells(steps: List<StepResult>): Map<Key, List<StepResult>> =
        steps.groupBy { Key(it.backend, it.config, it.partitions, "${it.change} ${it.transition}") }

    private fun medianOf(cell: List<StepResult>, value: (StepResult) -> Long?): Double =
        Stats.median(cell.mapNotNull(value).map { it / MILLIS })

    private fun pct(part: Long, whole: Long): String =
        if (whole == 0L) "-" else "%.3f".format(part * PERCENT / whole)

    private fun pct(part: Int, whole: Int): String = pct(part.toLong(), whole.toLong())

    private fun f1(value: Double): String = if (value.isNaN()) "-" else "%.1f".format(value)

    private fun f2(value: Double): String = if (value.isNaN()) "-" else "%.2f".format(value)
}
