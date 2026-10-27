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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.wow.example.eshop.e2e.json
import me.ahoo.wow.example.eshop.e2e.soak.Invariant
import java.io.File
import java.time.Duration
import java.time.Instant

/** How a check ended: it held, or it did not. */
enum class Status { PASSED, FAILED }

/** One check of a scenario, with what it saw. */
data class Check(
    val scenario: String,
    val name: String,
    val status: Status,
    val detail: String,
)

/**
 * One fault's unavailability window ([Outages]): from its first refused or failed probe to the first one served after
 * the last failure, or zero when no probe failed; [bound] is the scenario's upper bound.
 */
data class Window(
    val fault: String,
    val probe: String,
    val millis: Long,
    val bound: Duration,
    val failures: Int,
    val probes: Int
) {
    val ok: Boolean get() = millis <= bound.toMillis()
}

/** A fault the run injected, from [start] to [end], for the logs' and probes' allow-lists. */
data class Fault(val name: String, val start: Instant, var end: Instant? = null)

/**
 * The run's report (G.3): every check, every fault's window, the soak invariants and the findings, written as
 * `report.json` and as the job summary `summary.md` in [directory] (`ga.report`).
 */
class ClusterReport(private val directory: File, private val backend: String) {
    private val checks = mutableListOf<Check>()
    private val windows = mutableListOf<Window>()
    val faults = mutableListOf<Fault>()
    private val notes = mutableListOf<String>()
    private val started = Instant.now()

    @Synchronized
    fun check(scenario: String, name: String, ok: Boolean, detail: String) {
        checks += Check(scenario, name, if (ok) Status.PASSED else Status.FAILED, detail)
        println("[ga-e2e] ${if (ok) "PASSED" else "FAILED"} $scenario / $name: $detail")
    }

    @Synchronized
    fun window(window: Window) {
        windows += window
        check(
            "cluster",
            "window: ${window.fault}",
            window.ok,
            "${window.millis} ms unavailable (${window.failures} of ${window.probes} probes failed; bound ${window.bound.seconds} s)",
        )
    }

    @Synchronized
    fun note(text: String) {
        notes += text
        println("[ga-e2e] note: $text")
    }

    val failed: List<Check> @Synchronized get() = checks.filter { it.status == Status.FAILED }

    /** Writes the report with the [invariants]; the verdict passes when no check failed and no invariant was violated. */
    @Synchronized
    fun write(invariants: List<Invariant>): Boolean {
        val violations = invariants.sumOf { it.violated }
        val passed = failed.isEmpty() && violations == 0L
        directory.mkdirs()
        val report = linkedMapOf(
            "verdict" to if (passed) "PASSED" else "FAILED",
            "backend" to backend,
            "started" to started.toString(),
            "finished" to Instant.now().toString(),
            "violations" to violations,
            "checks" to checks,
            "windows" to windows.map {
                linkedMapOf(
                    "fault" to it.fault,
                    "probe" to it.probe,
                    "millis" to it.millis,
                    "boundMillis" to it.bound.toMillis(),
                    "ok" to it.ok,
                    "failures" to it.failures,
                    "probes" to it.probes,
                )
            },
            "faults" to faults.map { mapOf("name" to it.name, "start" to it.start.toString(), "end" to it.end?.toString()) },
            "invariants" to invariants.map { it.describe() },
            "notes" to notes,
        )
        File(directory, "report.json").writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        File(directory, "summary.md").writeText(summary(passed, violations, invariants))
        return passed
    }

    private fun summary(passed: Boolean, violations: Long, invariants: List<Invariant>): String = buildString {
        appendLine("### GA e2e on $backend: ${if (passed) "passed" else "failed"}, $violations invariant violations")
        appendLine()
        appendLine("| Scenario | Check | Result | Detail |")
        appendLine("| --- | --- | --- | --- |")
        checks.forEach {
            val result = when (it.status) {
                Status.PASSED -> "passed"
                Status.FAILED -> "**failed**"
            }
            appendLine("| ${it.scenario} | ${it.name} | $result | ${it.detail.cell()} |")
        }
        appendLine()
        appendLine("#### Unavailability windows")
        appendLine()
        appendLine("| Fault | Probe | Unavailable | Bound | Failed probes |")
        appendLine("| --- | --- | --- | --- | --- |")
        windows.forEach {
            appendLine(
                "| ${it.fault} | ${it.probe} | ${it.millis} ms | ${it.bound.seconds} s | ${it.failures} / ${it.probes} |"
            )
        }
        appendLine()
        appendLine("#### Invariants")
        appendLine()
        appendLine("| Invariant | OK | Checked | Violations |")
        appendLine("| --- | --- | --- | --- |")
        invariants.forEach {
            appendLine(
                "| `${it.name}` | ${it.violated == 0L} | ${it.checked.get()} | ${it.violated} |"
            )
        }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("#### Notes")
            appendLine()
            notes.forEach { appendLine("- ${it.cell()}") }
        }
    }

    private fun String.cell(): String = replace("|", "\\|").replace("\n", " ").take(CELL)

    private companion object {
        const val CELL = 400
    }
}
