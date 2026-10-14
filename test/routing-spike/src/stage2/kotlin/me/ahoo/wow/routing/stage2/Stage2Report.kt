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

package me.ahoo.wow.routing.stage2

/** The Markdown summary of a stage-2 run: what the job summary shows. */
object Stage2Report {
    @Suppress("LongMethod") // The report, top to bottom.
    fun markdown(settings: Stage2Settings, environment: Map<String, Any?>, runs: List<RunStats>): String = buildString {
        appendLine("## Spike H2, stage 2: three JVMs, real forwarding, durable PostgreSQL (D155)")
        appendLine()
        if (settings.smoke) {
            appendLine("> **Smoke run**: it only checks the harness; its numbers mean nothing.")
            appendLine()
        }
        appendLine(
            "${settings.aggregates} accounts, ${settings.partitions} partitions, ${fmt(settings.rate)} commands/s " +
                "offered open loop, ${settings.warmUp.seconds} s warm-up, ${settings.duration.seconds} s measured, " +
                "${settings.recovery.seconds} s after the kill. Environment: " +
                environment.entries.joinToString(", ") { "${it.key} ${it.value}" } + ".",
        )
        appendLine()
        appendLine("### Steady state")
        appendLine()
        appendLine(
            "| Distribution | Router | Throughput/s | p50 ms | p99 ms | p999 ms | max ms | steady p99 | worst-second p99 " +
                "| reads/cmd | tail reads/cmd | statements/cmd | node CPU µs/cmd | failures |",
        )
        appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
        runs.forEach { run ->
            val steady = run.steady
            appendLine(
                "| ${run.distribution} | ${run.router} | ${fmt(steady.throughput)} | ${fmt(steady.p50Millis)} " +
                    "| ${fmt(steady.p99Millis)} | ${fmt(steady.p999Millis)} | ${fmt(steady.maxMillis)} " +
                    "| ${fmt(steady.steadyP99Millis)} | ${fmt(steady.worstSecondP99Millis)} " +
                    "| ${fmt(run.readsPerCommand)} | ${fmt(run.tailReadsPerCommand)} | ${fmt(run.statementsPerCommand)} " +
                    "| ${fmt(run.nodeCpuMicrosPerCommand)} | ${codes(steady.failureCodes)} |",
            )
        }
        appendLine()
        appendLine(
            "Stage 1 for comparison (D6, in-process nodes, injected 0.3–1 ms hop, `synchronous_commit=off`): Zipf at 80% " +
                "load p99 Local 8.95 ms, Partitioned 3.20 ms, reads per command 2.44 against 1.38; uniform at 50% " +
                "Partitioned costs +0.6 ms of p99. Stage 2's reads per command count the statements on the stream and " +
                "snapshot tables (version, events, request id, snapshot load); the tail reads are the snapshot " +
                "subscription's.",
        )
        val kills = runs.filter { it.kill != null }
        if (kills.isNotEmpty()) {
            appendLine()
            appendLine("### Owner killed")
            appendLine()
            appendLine(
                "| Distribution | Victim | Partitions | Unavailable max / median ms | Recovered max / median ms " +
                    "| Lease taken over max ms | Failures after kill | Lost with the victim | In-flight failures " +
                    "| p99 after kill ms |",
            )
            appendLine("| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: |")
            kills.forEach { run ->
                val kill = checkNotNull(run.kill)
                appendLine(
                    "| ${run.distribution} | ${kill.victim} at ${fmt(kill.atSecond)} s | ${kill.partitions.size} " +
                        "| ${fmt(kill.maxUnavailableMillis)} / ${fmt(kill.medianUnavailableMillis)} " +
                        "| ${fmt(kill.maxRecoveredMillis)} / ${fmt(kill.medianRecoveredMillis)} " +
                        "| ${fmt(kill.maxLeaseTakeoverMillis)} | ${codes(kill.afterKill.failureCodes)} " +
                        "| ${kill.lostWithVictim} | ${kill.inFlightFailures} | ${fmt(kill.afterKill.p99Millis)} |",
                )
            }
            appendLine()
            appendLine(
                "Unavailable: until the last failed command to the partition was answered (0: none failed). Recovered: " +
                    "the first of ${PartitionWindow.RECOVERED_RUN} consecutive commands that all committed within the " +
                    "run's steady p999. Lost with the victim: commands the dead node had received, a load balancer's " +
                    "loss; the driver stops sending to it at the kill.",
            )
            kills.forEach { run -> appendPartitions(run) }
        }
        appendLine()
        appendLine("### Audit of the log")
        appendLine()
        appendLine(
            "| Distribution | Router | Committed (client) | In the log | Lost | Committed but failed | Duplicates |"
        )
        appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
        runs.forEach { run ->
            val audit = run.audit
            appendLine(
                "| ${run.distribution} | ${run.router} | ${audit.committedByClient} | ${audit.requestsInLog} " +
                    "| ${audit.lost} | ${audit.committedButFailed} | ${audit.duplicates} |",
            )
        }
    }

    private fun StringBuilder.appendPartitions(run: RunStats) {
        val kill = checkNotNull(run.kill)
        appendLine()
        appendLine("<details><summary>${run.distribution}: each partition of ${kill.victim}</summary>")
        appendLine()
        appendLine(
            "| Partition | Commands | Failures | First commit ms | Unavailable ms | Recovered ms | Max latency ms " +
                "| Lease taken over ms | New owner |",
        )
        appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
        kill.perPartition.forEach {
            appendLine(
                "| ${it.partition} | ${it.commands} | ${it.failures} | ${fmt(it.firstCommitMillis)} " +
                    "| ${fmt(it.unavailableMillis)} | ${fmt(it.recoveredMillis)} | ${fmt(it.maxLatencyMillis)} " +
                    "| ${fmt(it.leaseTakeoverMillis)} | ${it.newOwner ?: "-"} |",
            )
        }
        appendLine()
        appendLine("</details>")
    }

    private fun codes(codes: Map<String, Int>): String =
        if (codes.isEmpty()) "0" else codes.entries.joinToString(", ") { "${it.key} ${it.value}" }

    private fun fmt(value: Double?): String = when {
        value == null -> "-"
        value.isNaN() -> "-"
        value.isInfinite() -> "∞"
        value >= LARGE -> "%.0f".format(value)
        else -> "%.2f".format(value)
    }

    private const val LARGE = 100.0
}
