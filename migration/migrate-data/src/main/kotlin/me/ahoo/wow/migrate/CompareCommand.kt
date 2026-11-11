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

package me.ahoo.wow.migrate

import me.ahoo.wow.migrate.MigrateData.Companion.DIFFERS
import me.ahoo.wow.migrate.MigrateData.Companion.OK
import me.ahoo.wow.migrate.MigrateData.Companion.RESTART
import java.io.PrintStream
import java.time.ZoneId

/**
 * `compare` ([MigrateData]): each 9.x snapshot against the V10 state rebuilt at its version ([StateComparison]), the
 * log against what 9.x committed since the import ([ImportCoverage]), the reserved keys ([ReservationComparison]) and
 * the parked entries ([CompensationComparison]), then the verdict it prints and its exit code. It writes to [out], and
 * rebuilds states with the [models] of a configuration.
 */
internal class CompareCommand(
    private val out: PrintStream,
    private val models: (MigrationConfig) -> MigrationModels,
) {
    suspend fun compare(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
        keys: ImportedKeys,
        sample: Int,
        restart: Boolean,
    ): Int {
        // A state that turns a date into an instant with the system's zone rebuilds in this one.
        out.println(
            "Comparing in the time zone ${ZoneId.systemDefault()}: run compare in the one 9.x ran in (TZ, or " +
                "-Duser.timezone), as a state that turns a date into an instant with the system's zone rebuilds in it.",
        )
        val comparison = StateComparison(
            snapshots = connections.snapshots,
            events = connections.source,
            plan = plan,
            models = models(config),
            logOf = connections::logOf,
            checkpoints = checkpoints,
            options = compareOptions(config, sample, restart),
            report = out::println,
        )
        val differing = plan.aggregates.keys.map { comparison.compare(it) }.filterNot { it.passed }
        val missing = checkImports(plan, config, connections, checkpoints, restart)
        val differingKeys = compareReservations(plan, config, connections, keys)
        val executions = compareCompensation(plan, config, connections)
        val statesAndStreams = differing.isEmpty() && missing.isEmpty()
        if (statesAndStreams && differingKeys.isEmpty() && executions?.passed != false) {
            out.println(
                "Comparison done: every state compared equals its 9.x snapshot, the log holds every stream 9.x " +
                    "committed since the import up to where the tail has read, every confirmed 9.x key is " +
                    "reserved for its owner, and every unresolved 9.x execution imported is parked; nothing " +
                    "imported was released or resolved in 9.x since.",
            )
            return OK
        }
        reportMissing(missing)
        if (differing.isNotEmpty()) {
            out.println(
                "Comparison done: ${differing.sumOf { it.differing }} instances of ${differing.map { it.aggregate }} " +
                    "differ. Explain each kind of difference (a known 9.x defect or an intended change) before the " +
                    "cut-over.",
            )
        }
        if (differingKeys.isNotEmpty()) {
            out.println(
                "Comparison done: ${differingKeys.sumOf { it.differ }} confirmed keys of the PrepareKeys " +
                    "${differingKeys.map { it.name }} are not reserved for their owners in V10, and " +
                    "${differingKeys.sumOf { it.stale }} imported keys are no longer confirmed to their owners in " +
                    "9.x: run the import again with $RESTART before the cut-over.",
            )
        }
        if (executions?.passed == false) {
            out.println(
                "Comparison done: ${executions.differ} unresolved 9.x executions are not parked in V10, and " +
                    "${executions.stale} imported entries are no longer unresolved in 9.x: run the import again with " +
                    "$RESTART before the cut-over.",
            )
        }
        return DIFFERS
    }

    /**
     * The options of a comparison of [sample] percent: compared again since the last comparison, it reads 9.x
     * from before then by the final pass's look-back window, which sizes the 9.x nodes' clock skew.
     */
    private fun compareOptions(config: MigrationConfig, sample: Int, restart: Boolean) = CompareOptions(
        pageSize = config.pageSize,
        concurrency = config.concurrency,
        sample = sample,
        restart = restart,
        fingerprint = ComparisonFingerprint.of(config, sample),
        lookbackMillis = config.final.lookbackMillis,
    )

    /** Compares the confirmed 9.x keys with the V10 reservations ([ReservationComparison]); the PrepareKeys that differ. */
    private suspend fun compareReservations(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        keys: ImportedKeys,
    ): List<ReservationComparisonResult> {
        val reservations = ReservationComparison(
            source = connections.source,
            reservations = connections.reservations,
            plan = plan,
            store = connections::reservationStore,
            pageSize = config.pageSize,
            report = out::println,
            imported = keys,
        )
        return plan.reservations.keys.map { reservations.compare(it) }.filterNot { it.passed }
    }

    /** Checks that the log holds the streams 9.x committed since each full import ([ImportCoverage]); those it misses. */
    private suspend fun checkImports(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
        restart: Boolean,
    ): List<CoverageReport> {
        val coverage = ImportCoverage(
            source = connections.source,
            plan = plan,
            logOf = connections::logOf,
            checkpoints = checkpoints,
            options = CoverageOptions(
                pageSize = config.pageSize,
                concurrency = config.concurrency,
                lookbackMillis = config.lookbackMillis,
                marginMillis = config.final.lookbackMillis,
                restart = restart,
            ),
            report = out::println,
        )
        return plan.aggregates.keys.map { coverage.check(it) }.filterNot { it.passed }
    }

    private fun reportMissing(missing: List<CoverageReport>) {
        if (missing.isNotEmpty()) {
            out.println(
                "Comparison done: ${missing.sumOf { it.missing }} instances of ${missing.map { it.aggregate }} miss " +
                    "the latest stream 9.x committed to them since the import: before the cut-over, import them with " +
                    "one tail --once whose lookbackMillis reaches back to the earliest MISSING, or import $RESTART.",
            )
        }
    }

    /** Compares the unresolved 9.x executions with the V10 parked entries; null without a compensation database. */
    private suspend fun compareCompensation(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
    ): CompensationComparisonResult? = connections.compensation?.let { compensation ->
        CompensationComparison(
            source = compensation,
            plan = plan,
            logOf = connections::logOf,
            ledger = connections::failureLedger,
            pageSize = config.pageSize,
            report = out::println,
        ).compare()
    }
}
