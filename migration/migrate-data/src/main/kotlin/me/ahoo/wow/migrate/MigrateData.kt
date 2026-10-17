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
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The command line of `wow-migrate-data` (design 07 §3):
 *
 * ```text
 * migrate-data check    --config <file>                       the mapping check: a dry run that writes nothing
 * migrate-data import   --config <file> [--dry-run] [--restart] [--snapshots]
 * migrate-data tail     --config <file> [--once]
 * migrate-data compare  --config <file> [--sample <percent>] [--restart]
 * migrate-data snapshot --config <file> [--restart]
 * ```
 *
 * - `check` scans the 9.x store and checks `migration-map.json` against it and the V10 Manifest.
 * - `import` checks first and does not start on a failed check; then imports every mapped 9.x aggregate, resuming from
 *   its checkpoint. `--dry-run` converts every stream and appends none; `--restart` walks every instance again, the
 *   final complete pass once 9.x has stopped writing; `--snapshots` then writes the V10 snapshots, as `snapshot` does.
 *   After the streams it imports the confirmed keys of every mapped 9.x `PrepareKey` as V10 reservations
 *   ([ReservationImporter]), and, when the configuration names the 9.x compensation database, the unresolved 9.x
 *   executions as parked entries of the V10 service's failure ledger ([CompensationImporter]), resuming from their
 *   checkpoints too.
 * - `tail` follows what 9.x commits after the full import until it is stopped (or after one pass with `--once`), the
 *   increment until cut-over. It reads the 9.x store by commit time, which needs an index 9.x did not create: without
 *   it the tail refuses to start and prints the command that creates it, unless `source.createCommitTimeIndex` lets
 *   it create the index itself, the tool's only write to a 9.x store.
 * - `compare` compares each 9.x snapshot with the V10 state rebuilt from the imported log at its version
 *   ([StateComparison]), resuming from its checkpoint; `--sample` compares that percentage of the instances, the same
 *   ones on every run, and `--restart` compares from the first again. It then checks that every confirmed 9.x
 *   `PrepareKey` key is reserved in V10 for the same owner ([ReservationComparison], every key on every run), and
 *   that every unresolved 9.x execution is parked in V10 ([CompensationComparison]). It prints each instance, key
 *   and execution that differs, and exits with [DIFFERS] when one does.
 * - `snapshot` writes the V10 snapshot of every imported instance at its last version ([SnapshotWriter]), resuming
 *   from its checkpoint; `--restart` walks every instance again (the snapshots already written are skipped).
 *
 * `compare` and `snapshot` rebuild states with the services' V10 models, found on the configuration's `classpath`
 * ([MigrationModels]).
 *
 * From the repository: `./gradlew :migrate-data:run --args="check --config /path/to/migrate-data.json"`.
 */
class MigrateData(
    private val out: PrintStream = System.out,
    private val connect: (MigrationConfig) -> MigrationConnections = ::ConfiguredConnections,
    private val running: () -> Boolean = { true },
    private val models: (MigrationConfig) -> MigrationModels = { config ->
        MigrationModels.load(MigrationModels.classLoaderOf(config.classpath, MigrateData::class.java.classLoader))
    },
) {
    /**
     * Runs [args]; returns the process exit code: 0 done, 1 the check, the import or another step failed, 2 a usage
     * error, 3 the comparison found differences.
     */
    fun run(args: List<String>): Int {
        val command = args.firstOrNull()?.takeIf { it in COMMANDS }
        val options = args.drop(1)
        val configPath = valueOf(options, CONFIG)
        if (command == null || configPath == null) {
            out.println(USAGE)
            return USAGE_ERROR
        }
        val sample = valueOf(options, SAMPLE)?.toIntOrNull()
        usageProblem(command, options, sample)?.let { problem ->
            out.println("$problem\n$USAGE")
            return USAGE_ERROR
        }
        val config = MigrationConfig.read(Path.of(configPath))
        val plan = MigrationPlan(
            MigrationMap.read(Files.readString(Path.of(config.map))),
            readManifests(config.manifests),
        )
        return try {
            connect(config).use { connections ->
                runBlocking {
                    when (command) {
                        CHECK -> check(plan, connections)
                        IMPORT -> import(plan, config, connections, options)
                        TAIL -> tail(plan, config, connections, ONCE in options)
                        COMPARE -> compare(plan, config, connections, sample ?: CompareOptions.ALL, RESTART in options)
                        else -> snapshot(plan, config, connections, RESTART in options)
                    }
                }
            }
        } catch (failure: MigrationException) {
            out.println("FAILED ${failure.message}")
            FAILED
        }
    }

    /** What is wrong with [command]'s [options] ([sample] is the value of `--sample`); null when nothing is. */
    private fun usageProblem(command: String, options: List<String>, sample: Int?): String? {
        val unknown = options.filter { it.startsWith("--") } - KNOWN_OPTIONS.getValue(command)
        return when {
            unknown.isNotEmpty() -> "Unknown options $unknown."
            SAMPLE in options && (sample == null || sample !in 1..CompareOptions.ALL) ->
                "$SAMPLE takes a percentage from 1 to 100."

            DRY_RUN in options && SNAPSHOTS in options -> "A dry run writes no snapshots: $DRY_RUN or $SNAPSHOTS."
            else -> null
        }
    }

    private fun valueOf(options: List<String>, name: String): String? =
        options.zipWithNext().firstOrNull { it.first == name }?.second

    private fun check(plan: MigrationPlan, connections: MigrationConnections): Int {
        val report = MappingCheck(plan).check(
            connections.source.inventory(),
            connections.reservations.prepareKeys(),
            connections.compensation?.inventory(),
        )
        out.println(report.render())
        return if (report.passed) OK else FAILED
    }

    private suspend fun import(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        options: List<String>,
    ): Int {
        if (check(plan, connections) != OK) {
            return FAILED
        }
        val dryRun = DRY_RUN in options
        val checkpoints = if (dryRun) {
            InMemoryCheckpointRepository()
        } else {
            FileCheckpointRepository(Path.of(config.checkpoint))
        }
        val importer = importer(plan, config.options(dryRun, RESTART in options), connections, checkpoints)
        plan.aggregates.keys.forEach { importer.importAll(it) }
        val reservations = ReservationImporter(
            source = connections.source,
            reservations = connections.reservations,
            plan = plan,
            store = connections::reservationStore,
            checkpoints = checkpoints,
            options = config.options(dryRun, RESTART in options),
            progress = { out.println(it.render()) },
            report = out::println,
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
        return if (SNAPSHOTS in options) snapshot(plan, config, connections, RESTART in options) else OK
    }

    private suspend fun compare(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        sample: Int,
        restart: Boolean,
    ): Int {
        val comparison = StateComparison(
            snapshots = connections.snapshots,
            plan = plan,
            models = models(config),
            logOf = connections::logOf,
            checkpoints = FileCheckpointRepository(Path.of(config.checkpoint)),
            options = CompareOptions(config.pageSize, config.concurrency, sample, restart),
            report = out::println,
        )
        val differing = plan.aggregates.keys.map { comparison.compare(it) }.filterNot { it.passed }
        val reservations = ReservationComparison(
            source = connections.source,
            reservations = connections.reservations,
            plan = plan,
            store = connections::reservationStore,
            pageSize = config.pageSize,
            report = out::println,
        )
        val differingKeys = plan.reservations.keys.map { reservations.compare(it) }.filterNot { it.passed }
        val executions = compareCompensation(plan, config, connections)
        if (differing.isEmpty() && differingKeys.isEmpty() && executions?.passed != false) {
            out.println(
                "Comparison done: every state compared equals its 9.x snapshot, every confirmed 9.x key is " +
                    "reserved for its owner, and every unresolved 9.x execution imported is parked.",
            )
            return OK
        }
        if (differing.isNotEmpty()) {
            out.println(
                "Comparison done: ${differing.sumOf { it.differing }} instances of ${differing.map { it.aggregate }} " +
                    "differ. Explain each kind of difference (a known 9.x defect or an intended change) before the " +
                    "cut-over (design 07 §3 step 4).",
            )
        }
        if (differingKeys.isNotEmpty()) {
            out.println(
                "Comparison done: ${differingKeys.sumOf { it.differ }} confirmed keys of the PrepareKeys " +
                    "${differingKeys.map { it.name }} are not reserved for their owners in V10: run the import " +
                    "again before the cut-over.",
            )
        }
        if (executions?.passed == false) {
            out.println(
                "Comparison done: ${executions.differ} unresolved 9.x executions are not parked in V10: run the " +
                    "import again before the cut-over.",
            )
        }
        return DIFFERS
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

    private suspend fun snapshot(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        restart: Boolean,
    ): Int {
        val writer = SnapshotWriter(
            source = connections.source,
            plan = plan,
            models = models(config),
            logOf = connections::logOf,
            storeOf = connections::snapshotStoreOf,
            checkpoints = FileCheckpointRepository(Path.of(config.checkpoint)),
            options = config.options(restart = restart),
            progress = { out.println(it.render()) },
        )
        plan.aggregates.keys.forEach { writer.writeAll(it) }
        out.println("Snapshots done.")
        return OK
    }

    private suspend fun tail(plan: MigrationPlan, config: MigrationConfig, connections: MigrationConnections, once: Boolean): Int {
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
        val importer = importer(
            plan,
            config.options(),
            connections,
            FileCheckpointRepository(Path.of(config.checkpoint)),
        )
        do {
            plan.aggregates.keys.forEach { importer.tailOnce(it) }
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

    companion object {
        const val OK = 0
        const val FAILED = 1
        const val USAGE_ERROR = 2
        const val DIFFERS = 3
        private const val CHECK = "check"
        private const val IMPORT = "import"
        private const val TAIL = "tail"
        private const val COMPARE = "compare"
        private const val SNAPSHOT = "snapshot"
        private const val CONFIG = "--config"
        private const val DRY_RUN = "--dry-run"
        private const val RESTART = "--restart"
        private const val SNAPSHOTS = "--snapshots"
        private const val ONCE = "--once"
        private const val SAMPLE = "--sample"
        private val COMMANDS = setOf(CHECK, IMPORT, TAIL, COMPARE, SNAPSHOT)
        private val KNOWN_OPTIONS = mapOf(
            CHECK to setOf(CONFIG),
            IMPORT to setOf(CONFIG, DRY_RUN, RESTART, SNAPSHOTS),
            TAIL to setOf(CONFIG, ONCE),
            COMPARE to setOf(CONFIG, SAMPLE, RESTART),
            SNAPSHOT to setOf(CONFIG, RESTART),
        )
        private val USAGE = """
            Usage: migrate-data <command> --config <file>
              check     checks migration-map.json against the 9.x store and the V10 Manifest; writes nothing
              import    [--dry-run] [--restart] [--snapshots]  imports the 9.x event streams into the V10 log,
                        then writes the V10 snapshots with --snapshots
              tail      [--once]  follows what 9.x commits after the import, until stopped
              compare   [--sample <percent>] [--restart]  compares each 9.x snapshot with the V10 state rebuilt
                        from the log at its version; exits with 3 when one differs
              snapshot  [--restart]  writes the V10 snapshot of every imported instance
            Exit codes: 0 done, 1 failed, 2 usage error, 3 the comparison found differences.
        """.trimIndent()
    }
}

/** Runs the command line; a stop signal (Ctrl-C) ends a tail after its current pass. */
fun main(args: Array<String>) {
    val stopping = AtomicBoolean()
    val finished = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            stopping.set(true)
            finished.await()
        },
    )
    val code = try {
        MigrateData(running = { !stopping.get() }).run(args.toList())
    } finally {
        finished.countDown()
    }
    exitProcess(code)
}
