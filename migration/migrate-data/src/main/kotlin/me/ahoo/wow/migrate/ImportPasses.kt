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

import kotlinx.coroutines.delay
import me.ahoo.wow.migrate.MigrateData.Companion.DRY_RUN
import me.ahoo.wow.migrate.MigrateData.Companion.FAILED
import me.ahoo.wow.migrate.MigrateData.Companion.OK
import me.ahoo.wow.migrate.MigrateData.Companion.RESTART
import java.io.PrintStream

/**
 * The passes that write the 9.x history into V10 ([MigrateData]'s `import` and `tail`), each in its order: the
 * streams of every aggregate, then where the imported history ends in each log ([recordCutover]), then the
 * reservations and the compensation. They write to [out]; the tail goes on while [running].
 */
internal class ImportPasses(
    private val out: PrintStream,
    private val running: () -> Boolean,
) {
    @Suppress("LongParameterList") // A command's inputs.
    suspend fun importAll(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        options: List<String>,
        checkpoints: CheckpointRepository,
        keys: ImportedKeys,
    ): Int {
        val dryRun = DRY_RUN in options
        val importer = importer(plan, config.options(dryRun, RESTART in options), connections, checkpoints)
        passedOver(plan.aggregates.keys.map { importer.importAll(it) })?.let(out::println)
        if (!dryRun) {
            recordCutover(plan, connections)
        }
        val reservations = ReservationImporter(
            source = connections.source,
            reservations = connections.reservations,
            plan = plan,
            store = connections::reservationStore,
            checkpoints = checkpoints,
            options = config.options(dryRun, RESTART in options),
            progress = { out.println(it.render()) },
            report = out::println,
            imported = keys,
        )
        plan.reservations.keys.forEach { reservations.importAll(it) }
        connections.compensation?.let { compensation ->
            CompensationImporter(
                source = compensation,
                plan = plan,
                logOf = connections::logOf,
                ledger = connections::failureLedger,
                checkpoints = checkpoints,
                options = config.options(dryRun, RESTART in options),
                progress = { out.println(it.render()) },
                report = out::println,
            ).importAll()
        }
        out.println(if (dryRun) "Dry run done: nothing was appended." else "Import done.")
        return OK
    }

    /**
     * The final pass once 9.x has stopped writing (`import --final`, step 3): one pass of the tail from
     * its checkpoint with the final look-back window, then the reservations and the compensation executions walked
     * from the first. It refuses, writing nothing, when an aggregate's tail did not follow 9.x continuously: the final
     * pass is then `import --restart`.
     */
    @Suppress("LongParameterList") // A command's inputs.
    suspend fun finalPass(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
        keys: ImportedKeys,
    ): Int {
        val importer = importer(
            plan,
            config.options().copy(lookbackMillis = config.final.lookbackMillis),
            connections,
            checkpoints,
        )
        val problems = plan.aggregates.keys.mapNotNull { importer.finalProblem(it, config.final.maxTailAgeMillis) }
        if (problems.isNotEmpty()) {
            problems.forEach { out.println("FAILED $it") }
            out.println("The final pass needs a tail that followed 9.x until it stopped: run import $RESTART instead.")
            return FAILED
        }
        plan.aggregates.keys.forEach { importer.tailOnce(it) }
        recordCutover(plan, connections)
        ReservationImporter(
            source = connections.source,
            reservations = connections.reservations,
            plan = plan,
            store = connections::reservationStore,
            checkpoints = checkpoints,
            options = config.options(restart = true),
            progress = { out.println(it.render()) },
            report = out::println,
            imported = keys,
        ).let { reservations -> plan.reservations.keys.forEach { reservations.importAll(it) } }
        connections.compensation?.let { compensation ->
            CompensationImporter(
                source = compensation,
                plan = plan,
                logOf = connections::logOf,
                ledger = connections::failureLedger,
                checkpoints = checkpoints,
                options = config.options(restart = true),
                progress = { out.println(it.render()) },
                report = out::println,
            ).importAll()
        }
        out.println(
            "Final pass done: the tail from its checkpoint, the reservations and the compensation walked again."
        )
        return OK
    }

    suspend fun tail(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
        once: Boolean,
    ): Int {
        val source = connections.source
        if (source is CommitTimeIndexed) {
            if (config.source.createCommitTimeIndex) {
                plan.aggregates.keys.forEach(source::ensureCommitTimeIndex)
            }
            val missing = source.missingCommitTimeIndexes(plan.aggregates.keys)
            if (missing.isNotEmpty()) {
                out.println(missingIndexes(missing))
                return FAILED
            }
        }
        val importer = importer(plan, config.options(), connections, checkpoints)
        do {
            plan.aggregates.keys.forEach { importer.tailOnce(it) }
            recordCutover(plan, connections)
            if (!once) {
                delay(config.pollIntervalMillis)
            }
        } while (!once && running())
        out.println("Tail stopped.")
        return OK
    }

    private fun missingIndexes(missing: Map<String, String>): String = buildString {
        appendLine(
            "The tail reads the 9.x collections ${missing.keys} by (createTime, _id), which they do not index, and " +
                "this tool does not write to the 9.x store unless told to. Create the index when convenient:",
        )
        missing.values.forEach { appendLine("  $it") }
        append("or set \"source\": { \"createCommitTimeIndex\": true } to let the tail create it.")
    }

    /**
     * What an import's [progress] passed over: the streams the log already held, from an earlier run, kept as they
     * were converted then, so a change of the map since does not reach them. Null when it passed over none.
     */
    private fun passedOver(progress: List<ImportProgress>): String? {
        val skipped = progress.filter { it.skipped > 0 }.ifEmpty { return null }
        return "${skipped.sumOf { it.skipped }} streams of ${skipped.map { it.aggregate }} were already in the log, " +
            "and were passed over as they were converted then: a change of the map since reaches them only in a " +
            "fresh target."
    }

    /** Records where the imported history ends in each context's log, after a pass that appended to it. */
    private suspend fun recordCutover(plan: MigrationPlan, connections: MigrationConnections) {
        val recorder = CutoverRecorder.of(plan, connections)
        out.println(recorder.render(recorder.record()))
    }

    private fun importer(
        plan: MigrationPlan,
        options: ImportOptions,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
    ): EventStreamImporter = EventStreamImporter(
        source = connections.source,
        plan = plan,
        logOf = connections::logOf,
        checkpoints = checkpoints,
        options = options,
        progress = { out.println(it.render()) },
    )
}
