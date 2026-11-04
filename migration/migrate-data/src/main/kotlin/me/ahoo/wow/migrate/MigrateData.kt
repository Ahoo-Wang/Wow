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
import me.ahoo.wow.manifest.Manifest
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The command line of `wow-migrate-data`:
 *
 * ```text
 * migrate-data draft-map --config <file> [--out <file>] [--force] [--dry-run]
 * migrate-data check    --config <file> [--decode]            the mapping check: a dry run that writes nothing
 * migrate-data import   --config <file> [--dry-run] [--restart | --final] [--snapshots]
 * migrate-data tail     --config <file> [--once]
 * migrate-data compare  --config <file> [--sample <percent>] [--restart]
 * migrate-data snapshot --config <file> [--restart]
 * migrate-data export   --config <file> [--dry-run] [--restart]
 * migrate-data --help
 * ```
 *
 * - `draft-map` drafts `migration-map.json` from the event and command names the 9.x store holds and the V10 Manifest
 *   ([MapDrafter]): every name it matches mapped, every other listed in the draft's `exceptions` and printed, for a
 *   person to resolve. It writes the map the configuration names, or `--out`, and never over an existing file
 *   without `--force`; `--dry-run` prints the draft and writes nothing. `check` then reads the draft as any map.
 * - `check` scans the 9.x store and checks `migration-map.json` against it and the V10 Manifest; `--decode` then, once
 *   that passed, reads every 9.x event of the mapped aggregates with the V10 models ([DecodeCheck]), printing each that
 *   does not read, and fails when one does not.
 * - `import` checks first and does not start on a failed check; then imports every mapped 9.x aggregate, resuming from
 *   its checkpoint. `--dry-run` converts every stream and appends none; `--restart` walks every instance again, the
 *   final complete pass once 9.x has stopped writing; `--final` is the shorter final pass when the tail followed 9.x
 *   until it stopped: one tail pass from its checkpoint with the final look-back window
 *   ([MigrationConfig.final]), then the reservations and the compensation walked again, refused (nothing written)
 *   when an aggregate's tail did not run continuously; `--snapshots` then writes the V10 snapshots, as `snapshot` does.
 *   After the streams it imports the confirmed keys of every mapped 9.x `PrepareKey` as V10 reservations
 *   ([ReservationImporter]), and, when the configuration names the 9.x compensation database, the unresolved 9.x
 *   executions as parked entries of the V10 service's failure ledger ([CompensationImporter]), resuming from their
 *   checkpoints too. A walk of either from the first (the first run, or `--restart`) first undoes what an earlier run
 *   imported and 9.x has since released or resolved; the keys it confirmed are recorded
 *   beside the checkpoint file ([IMPORTED_KEYS]).
 * - `tail` follows what 9.x commits after the full import until it is stopped (or after one pass with `--once`), the
 *   increment until cut-over. It reads the 9.x store by commit time, which needs an index 9.x did not create: without
 *   it the tail refuses to start and prints the command that creates it, unless `source.createCommitTimeIndex` lets
 *   it create the index itself. That index and the rollback `export`, which writes streams back, are the tool's only
 *   writes to a 9.x store.
 * - `compare` compares each 9.x snapshot with the V10 state rebuilt from the imported log at its version
 *   ([StateComparison]), resuming from its checkpoint; once that has completed, it compares again only the instances
 *   changed on either side since and those that differed, unless what the result depends on changed, which it says;
 *   `--sample` compares that percentage of the instances, the same ones on every run, and `--restart` compares from
 *   the first again. It first prints the time zone it runs in, the
 *   one a state that turns a date into an instant with the system's zone rebuilds in. It checks that the log holds
 *   every stream 9.x committed since the full import started, up to where the tail has read ([ImportCoverage]),
 *   which the snapshots cannot show after an instance's latest one. It then checks that every confirmed 9.x
 *   `PrepareKey` key is reserved in V10 for the same owner and every key it imported is still confirmed to that
 *   owner in 9.x ([ReservationComparison], every key on every run), and that every unresolved 9.x execution is
 *   parked in V10 and every entry it imported still stands for an unresolved execution ([CompensationComparison]). It prints each instance, key
 *   and execution that differs, and exits with [DIFFERS] when one does.
 * - `snapshot` writes the V10 snapshot of every imported instance at its last version ([SnapshotWriter]), resuming
 *   from its checkpoint; once a walk has completed, it catches up instead: it writes the snapshots of the instances
 *   the V10 log changed since, so a walk during the dual-run leaves the cut-over only those. `--restart` walks every
 *   instance again (the snapshots already written are skipped). Each instance whose snapshot it cannot write is
 *   printed, and it fails once it has walked every aggregate.
 *
 * - `export` writes back to the 9.x store what V10 wrote after the cut-over, for a rollback after V10 has written
 *   ([EventStreamExporter]): it scans each V10 context's log first and refuses, writing nothing, when a
 *   stream 9.x lacks holds a V10-only type (each named) or 9.x holds another stream at its version; then writes the
 *   streams 9.x lacks, resuming from its checkpoint, and checks that each 9.x aggregate holds as many streams as V10
 *   holds of its type. `--dry-run` only scans; `--restart` walks every partition from its first entry again.
 *
 * `compare` and `snapshot` rebuild states with the services' V10 models, found on the configuration's `classpath`
 * ([MigrationModels]).
 *
 * From the repository: `./gradlew :migrate-data:run --args="check --config /path/to/migrate-data.json"`.
 */
internal class MigrateData(
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
        if (args.firstOrNull() in HELP) {
            out.println(USAGE)
            return OK
        }
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
        return reported {
            if (command == DRAFT_MAP) {
                val manifest = manifestOf(config, null)
                connect(config).use { draftMap(config, manifest, it, options) }
            } else {
                val map = MigrationMap.read(Files.readString(Path.of(config.map)))
                val plan = MigrationPlan(map, manifestOf(config, map))
                val line = (listOf(command) + options.filter { it.startsWith("--") && it != CONFIG }).joinToString(" ")
                connect(config).use { connections ->
                    runBlocking { run(command, plan, config, Checkpoints(config, connections, line), options, sample) }
                }
            }
        }
    }

    /**
     * The Manifests the configuration names, or, when it names none, those assembled from the fragments of the
     * modules on its class path ([classpathManifests]), which must hold every context [map] imports into.
     */
    private fun manifestOf(config: MigrationConfig, map: MigrationMap?): Manifest {
        if (config.manifests.isNotEmpty()) {
            return readManifests(config.manifests)
        }
        return classpathManifests(config.classpath, MigrateData::class.java.classLoader).also { manifest ->
            map?.let { requireContexts(manifest, it, config.classpath) }
        }
    }

    /**
     * Where a run of the command line [line] keeps its progress: the checkpoint file or the target
     * ([MigrationConfig.checkpoint]), and the keys its reservation import confirmed beside them. [holding] runs a
     * command that writes them holding its roles ([CheckpointRole]), so no other run of its roles writes them at once.
     */
    private inner class Checkpoints(
        val config: MigrationConfig,
        val connections: MigrationConnections,
        private val line: String,
    ) {
        suspend fun <T> holding(
            roles: Set<CheckpointRole>,
            block: suspend (CheckpointRepository, ImportedKeys) -> T
        ): T {
            val holder = CheckpointHolder.of(line)
            val name = config.checkpointName
            if (name == null) {
                val file = Path.of(config.checkpoint)
                return FileCheckpointLocks(file, holder).holding(roles) {
                    block(
                        FileCheckpointRepository(file),
                        FileImportedKeys(Path.of("${config.checkpoint}$IMPORTED_KEYS"))
                    )
                }
            }
            val table = connections.checkpointTable()
            return TargetCheckpointLeases(connections.leaseStore(), name, holder, report = out::println)
                .holding(roles) { lock ->
                    block(TargetCheckpointRepository(table, name, lock), TargetImportedKeys(table, name, lock))
                }
        }
    }

    /** Runs [command], one that reads the map, with its [options]. */
    private suspend fun run(
        command: String,
        plan: MigrationPlan,
        config: MigrationConfig,
        checkpoints: Checkpoints,
        options: List<String>,
        sample: Int?,
    ): Int {
        val connections = checkpoints.connections
        val restart = RESTART in options
        return when (command) {
            CHECK -> if (DECODE in options) decodeCheck(plan, config, connections) else check(plan, connections)
            IMPORT -> import(plan, checkpoints, options)
            TAIL -> checkpoints.holding(setOf(CheckpointRole.IMPORT)) { repository, _ ->
                tail(plan, config, connections, repository, ONCE in options)
            }
            COMPARE -> checkpoints.holding(setOf(CheckpointRole.COMPARE)) { repository, keys ->
                compare(plan, config, connections, repository, keys, sample ?: CompareOptions.ALL, restart)
            }
            // The export writes into the 9.x store the import and the tail read, so it never runs beside them.
            EXPORT -> {
                val dryRun = DRY_RUN in options
                val roles = if (dryRun) emptySet() else setOf(CheckpointRole.EXPORT, CheckpointRole.IMPORT)
                checkpoints.holding(roles) { repository, _ ->
                    export(plan, config, connections, repository, dryRun, restart)
                }
            }
            else -> checkpoints.holding(setOf(CheckpointRole.SNAPSHOT)) { repository, _ ->
                snapshot(plan, config, connections, repository, restart)
            }
        }
    }

    /** Runs [step]; a [MigrationException] it throws is printed as `FAILED`, and fails the run. */
    private inline fun reported(step: () -> Int): Int = try {
        step()
    } catch (failure: MigrationException) {
        out.println("FAILED ${failure.message}")
        FAILED
    }

    /**
     * Drafts the map ([MapDrafter]) to the file the configuration names, or `--out`: never over an existing one
     * without `--force`, and nowhere on `--dry-run`, which prints it. Each exception is printed with its marker.
     */
    private fun draftMap(
        config: MigrationConfig,
        manifest: Manifest,
        connections: MigrationConnections,
        options: List<String>,
    ): Int {
        val path = Path.of(valueOf(options, OUT) ?: config.map)
        val dryRun = DRY_RUN in options
        if (!dryRun && Files.exists(path) && FORCE !in options) {
            out.println("FAILED $path exists: draft-map writes a new map, so pass $FORCE to replace it, or $OUT.")
            return FAILED
        }
        val draft = MapDrafter(manifest).draft(
            inventoryOf(connections.source),
            connections.reservations.prepareKeys(),
            TagReader.of(connections.source, connections.snapshots)::tags,
        )
        draft.exceptions.forEach(out::println)
        if (dryRun) {
            out.println(draft.render())
            out.println("Dry run: ${draft.summary()} Nothing was written.")
        } else {
            Files.writeString(path, draft.render())
            out.println("Draft written to $path: ${draft.summary()} Resolve them, then run check.")
        }
        return OK
    }

    /** What is wrong with [command]'s [options] ([sample] is the value of `--sample`); null when nothing is. */
    private fun usageProblem(command: String, options: List<String>, sample: Int?): String? {
        val unknown = options.filter { it.startsWith("--") } - KNOWN_OPTIONS.getValue(command)
        return when {
            unknown.isNotEmpty() -> "Unknown options $unknown."
            SAMPLE in options && (sample == null || sample !in 1..CompareOptions.ALL) ->
                "$SAMPLE takes a percentage from 1 to 100."

            DRY_RUN in options && SNAPSHOTS in options -> "A dry run writes no snapshots: $DRY_RUN or $SNAPSHOTS."
            FINAL in options && (RESTART in options || DRY_RUN in options) ->
                "The final pass is $FINAL alone: not with $RESTART or $DRY_RUN."
            OUT in options && valueOf(options, OUT)?.takeUnless { it.startsWith("--") } == null ->
                "$OUT takes the file to write the draft to."
            else -> null
        }
    }

    private fun valueOf(options: List<String>, name: String): String? =
        options.zipWithNext().firstOrNull { it.first == name }?.second

    /**
     * What [source] holds, for `draft-map` and `check`; a store without a single event stream fails, naming the database
     * it reads: one whose name is wrong reads as an empty store.
     */
    private fun inventoryOf(source: NineXEventSource): List<NineXInventory> = source.inventory().ifEmpty {
        throw MigrationException(
            "${source.store} holds no 9.x event stream: check source.database, and the variable an env: value names.",
        )
    }

    private fun check(plan: MigrationPlan, connections: MigrationConnections): Int {
        val report = MappingCheck(plan).check(
            inventoryOf(connections.source),
            connections.reservations.prepareKeys(),
            connections.compensation?.inventory(),
            connections.source::tenantsOf,
            (connections.source as? CommitTimeIndexed)?.missingCommitTimeIndexes(plan.aggregates.keys).orEmpty(),
            connections.snapshots::deleted,
            connections.source.missingWalkIndexes(plan.aggregates.keys),
            TagReader.of(connections.source, connections.snapshots),
        )
        out.println(report.render())
        return if (report.passed) OK else FAILED
    }

    /**
     * The mapping check, then, once it passed, every 9.x event of the mapped aggregates decoded by the V10 models
     * ([DecodeCheck]): each that does not read is printed, and the check fails.
     */
    private suspend fun decodeCheck(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections
    ): Int {
        if (check(plan, connections) != OK) {
            out.println("Decode check not run: the mapping check failed.")
            return FAILED
        }
        val decoding = DecodeCheck(
            source = connections.source,
            plan = plan,
            models = models(config),
            options = config.options(),
            report = { out.println(it.render()) },
        )
        val reports = plan.aggregates.keys.map { decoding.check(it).also { report -> out.println(report.render()) } }
        val failed = reports.filterNot { it.passed }
        if (failed.isEmpty()) {
            out.println("Decode check passed: the V10 models read every 9.x event.")
            return OK
        }
        out.println(
            "Decode check failed: ${failed.sumOf { it.failed.values.sum() }} 9.x events of " +
                "${failed.flatMap { it.failed.keys }.distinct().size} types do not read, and " +
                "${failed.sumOf { it.unconverted }} streams do not convert. Give each type an @Upgrade that cleans " +
                "its old shape.",
        )
        return FAILED
    }

    /**
     * The import ([importAll]) or its final pass ([finalPass]), once the check passed, holding the import's role, and
     * the snapshot writing's too with `--snapshots`; a dry run holds none and keeps its checkpoints in memory.
     */
    private suspend fun import(plan: MigrationPlan, checkpoints: Checkpoints, options: List<String>): Int {
        val connections = checkpoints.connections
        if (check(plan, connections) != OK) {
            return FAILED
        }
        val config = checkpoints.config
        if (DRY_RUN in options) {
            return importAll(plan, config, connections, options, InMemoryCheckpointRepository(), InMemoryImportedKeys())
        }
        val roles = setOfNotNull(CheckpointRole.IMPORT, CheckpointRole.SNAPSHOT.takeIf { SNAPSHOTS in options })
        return checkpoints.holding(roles) { repository, keys ->
            if (FINAL in options) {
                finalPass(plan, config, connections, repository, keys, SNAPSHOTS in options)
            } else {
                importAll(plan, config, connections, options, repository, keys)
            }
        }
    }

    @Suppress("LongParameterList") // A command's inputs.
    private suspend fun importAll(
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
        return if (SNAPSHOTS in options) snapshot(plan, config, connections, checkpoints, RESTART in options) else OK
    }

    /**
     * The final pass once 9.x has stopped writing (`import --final`, step 3): one pass of the tail from
     * its checkpoint with the final look-back window, then the reservations and the compensation executions walked
     * from the first. It refuses, writing nothing, when an aggregate's tail did not follow 9.x continuously: the final
     * pass is then `import --restart`.
     */
    @Suppress("LongParameterList") // A command's inputs.
    private suspend fun finalPass(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
        keys: ImportedKeys,
        snapshots: Boolean,
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
        return if (snapshots) snapshot(plan, config, connections, checkpoints, restart = false) else OK
    }

    @Suppress("LongParameterList") // A command's inputs.
    private suspend fun compare(
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

    private suspend fun snapshot(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
        restart: Boolean,
    ): Int {
        val writer = SnapshotWriter(
            source = connections.source,
            plan = plan,
            models = models(config),
            logOf = connections::logOf,
            storeOf = connections::snapshotStoreOf,
            checkpoints = checkpoints,
            options = config.options(restart = restart),
            progress = { out.println(it.render()) },
            report = out::println,
        )
        val failed = plan.aggregates.keys.map { writer.writeAll(it) }.filter { it.failed > 0 }
        if (failed.isNotEmpty()) {
            out.println(
                "FAILED ${failed.sumOf { it.failed }} instances of ${failed.map { it.aggregate }} have no V10 " +
                    "snapshot, each printed above: fix them, then run snapshot again, which walks every instance of " +
                    "those aggregates again, or reads again what the log changed when it was catching up, and " +
                    "passes over the snapshots written.",
            )
            return FAILED
        }
        out.println("Snapshots done.")
        return OK
    }

    /** Exports to 9.x what V10 wrote after the cut-over: scan, then write, then count. */
    @Suppress("LongParameterList") // A command's inputs.
    private suspend fun export(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        checkpoints: CheckpointRepository,
        dryRun: Boolean,
        restart: Boolean,
    ): Int {
        val exporter = EventStreamExporter(
            plan = plan,
            logOf = connections::logOf,
            target = connections.nineXTarget,
            checkpoints = checkpoints,
            options = ExportOptions(config.pageSize, config.concurrency, dryRun, restart),
            progress = { out.println(it.render()) },
        )
        val scans = plan.contexts.map { exporter.scan(it).also { scan -> out.println(scan.render()) } }
        if (scans.any { !it.passed }) {
            out.println(
                "Export refused: 9.x cannot read the V10-only types above, or holds other streams where V10 wrote; " +
                    "nothing was written. An export covers only the event types 9.x already had.",
            )
            return FAILED
        }
        if (dryRun) {
            out.println("Dry run done: ${scans.sumOf { it.missing }} streams would be exported; nothing was written.")
            return OK
        }
        scans.forEach { exporter.export(it) }
        val counts = scans.flatMap { exporter.verify(it) }
        counts.forEach { out.println(it.render()) }
        if (counts.all { it.passed }) {
            out.println("Export done: 9.x holds every stream V10 holds; start 9.x again.")
            return OK
        }
        out.println("Export done, but 9.x and V10 hold different numbers of streams: see DIFFERS above.")
        return DIFFERS
    }

    private suspend fun tail(
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

    companion object {
        const val OK = 0
        const val FAILED = 1
        const val USAGE_ERROR = 2
        const val DIFFERS = 3
        private const val DRAFT_MAP = "draft-map"
        private const val CHECK = "check"
        private const val IMPORT = "import"
        private const val TAIL = "tail"
        private const val COMPARE = "compare"
        private const val SNAPSHOT = "snapshot"
        private const val EXPORT = "export"
        private const val CONFIG = "--config"
        private const val DRY_RUN = "--dry-run"
        private const val RESTART = "--restart"
        private const val FINAL = "--final"
        private const val SNAPSHOTS = "--snapshots"
        private const val ONCE = "--once"
        private const val SAMPLE = "--sample"
        private const val FORCE = "--force"
        private const val OUT = "--out"
        private const val DECODE = "--decode"
        private val HELP = setOf("--help", "-h", "help")

        /** The suffix of the file of the keys the import confirmed, after the checkpoint file's name. */
        const val IMPORTED_KEYS = ".reservations.jsonl"
        private val COMMANDS = setOf(DRAFT_MAP, CHECK, IMPORT, TAIL, COMPARE, SNAPSHOT, EXPORT)
        private val KNOWN_OPTIONS = mapOf(
            DRAFT_MAP to setOf(CONFIG, OUT, FORCE, DRY_RUN),
            CHECK to setOf(CONFIG, DECODE),
            IMPORT to setOf(CONFIG, DRY_RUN, RESTART, FINAL, SNAPSHOTS),
            TAIL to setOf(CONFIG, ONCE),
            COMPARE to setOf(CONFIG, SAMPLE, RESTART),
            SNAPSHOT to setOf(CONFIG, RESTART),
            EXPORT to setOf(CONFIG, DRY_RUN, RESTART),
        )
        private val USAGE = """
            Usage: migrate-data <command> --config <file>
              draft-map [--out <file>] [--force] [--dry-run]  drafts migration-map.json from the names the 9.x store
                        holds and the V10 Manifest, listing what it cannot match as exceptions; never replaces a
                        file without --force; --dry-run prints the draft and writes nothing
              check     [--decode]  checks migration-map.json against the 9.x store and the V10 Manifest, then
                        with --decode reads every 9.x event with the V10 models, listing each that fails; writes
                        nothing
              import    [--dry-run] [--restart | --final] [--snapshots]  imports the 9.x event streams into the V10
                        log, then writes the V10 snapshots with --snapshots; --final is the final pass after 9.x
                        stops: the tail from its checkpoint, then the reservations and compensation walked again
              tail      [--once]  follows what 9.x commits after the import, until stopped
              compare   [--sample <percent>] [--restart]  compares each 9.x snapshot with the V10 state rebuilt
                        from the log at its version, once done those changed since, and checks the log holds every
                        stream 9.x committed since the import; exits with 3 when one differs or is missing
              snapshot  [--restart]  writes the V10 snapshot of every imported instance; once done, of those the
                        V10 log changed since
              export    [--dry-run] [--restart]  writes back to the 9.x store what V10 wrote after the cut-over,
                        for a rollback; refuses V10-only types; exits with 3 when the stream counts differ
              --help    prints this
            Exit codes: 0 done, 1 failed, 2 usage error, 3 the comparison or the export's count found differences.
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
