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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.Manifest
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The command line of `wow-migrate-data`:
 *
 * ```text
 * migrate-data draft-map --config <file> [--out <file>] [--force] [--dry-run]
 * migrate-data check    --config <file>                       the mapping check: a dry run that writes nothing
 * migrate-data import   --config <file> [--dry-run] [--restart | --final] [--snapshots]
 * migrate-data tail     --config <file> [--once]
 * migrate-data compare  --config <file> [--sample <percent>] [--restart]
 * migrate-data snapshot --config <file> [--restart]
 * migrate-data backfill-tags --config <file> [--restart] [--force]
 * migrate-data export   --config <file> [--dry-run] [--restart]
 * ```
 *
 * - `draft-map` drafts `migration-map.json` from the event and command names the 9.x store holds and the V10 Manifest
 *   ([MapDrafter]): every name it matches mapped, every other listed in the draft's `exceptions` and printed, for a
 *   person to resolve. It writes the map the configuration names, or `--out`, and never over an existing file
 *   without `--force`; `--dry-run` prints the draft and writes nothing. `check` then reads the draft as any map.
 * - `check` scans the 9.x store and checks `migration-map.json` against it and the V10 Manifest.
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
 *   it create the index itself, the tool's only write to a 9.x store.
 * - `compare` compares each 9.x snapshot with the V10 state rebuilt from the imported log at its version
 *   ([StateComparison]), resuming from its checkpoint; `--sample` compares that percentage of the instances, the same
 *   ones on every run, and `--restart` compares from the first again. It then checks that every confirmed 9.x
 *   `PrepareKey` key is reserved in V10 for the same owner and every key it imported is still confirmed to that
 *   owner in 9.x ([ReservationComparison], every key on every run), and that every unresolved 9.x execution is
 *   parked in V10 and every entry it imported still stands for an unresolved execution ([CompensationComparison]). It prints each instance, key
 *   and execution that differs, and exits with [DIFFERS] when one does.
 * - `snapshot` writes the V10 snapshot of every imported instance at its last version ([SnapshotWriter]), resuming
 *   from its checkpoint; `--restart` walks every instance again (the snapshots already written are skipped).
 *
 * - `backfill-tags` records the tags of every stream the V10 log appended before format 1 learned them,
 *   each aggregate of the configured contexts' Manifests replayed with its V10 model ([TagsBackfiller]),
 *   resuming from its checkpoint; `--restart` walks every aggregate again. Then, when the configuration names an
 *   Elasticsearch cluster, it overwrites the events index documents indexed before the tags from the log
 *   ([TagsBackfiller.resink]), and only once the logs hold no stream without tags: the logs first, never the other
 *   way round; `--force` re-sinks anyway, leaving the documents of the streams still without tags.
 *
 * - `export` writes back to the 9.x store what V10 wrote after the cut-over, for a rollback after V10 has written
 *   ([EventStreamExporter]): it scans each V10 context's log first and refuses, writing nothing, when a
 *   stream 9.x lacks holds a V10-only type (each named) or 9.x holds another stream at its version; then writes the
 *   streams 9.x lacks, resuming from its checkpoint, and checks that each 9.x aggregate holds as many streams as V10
 *   holds of its type. `--dry-run` only scans; `--restart` walks every partition from its first entry again.
 *
 * `compare`, `snapshot` and `backfill-tags` rebuild states with the services' V10 models, found on the configuration's `classpath`
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
        val manifest = readManifests(config.manifests)
        if (command == DRAFT_MAP) {
            return reported { connect(config).use { draftMap(config, manifest, it, options) } }
        }
        val plan = MigrationPlan(MigrationMap.read(Files.readString(Path.of(config.map))), manifest)
        return reported {
            connect(config).use { connections ->
                runBlocking { run(command, plan, config, connections, options, sample) }
            }
        }
    }

    /** Runs [command], one that reads the map, with its [options]. */
    @Suppress("LongParameterList") // A command's inputs.
    private suspend fun run(
        command: String,
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        options: List<String>,
        sample: Int?,
    ): Int = when (command) {
        CHECK -> check(plan, connections)
        IMPORT -> import(plan, config, connections, options)
        TAIL -> tail(plan, config, connections, ONCE in options)
        COMPARE -> compare(plan, config, connections, sample ?: CompareOptions.ALL, RESTART in options)
        BACKFILL_TAGS -> backfillTags(plan, config, connections, RESTART in options, FORCE in options)
        EXPORT -> export(plan, config, connections, DRY_RUN in options, RESTART in options)
        else -> snapshot(plan, config, connections, RESTART in options)
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
        val draft = MapDrafter(manifest).draft(connections.source.inventory(), connections.reservations.prepareKeys())
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
        if (FINAL in options) {
            return finalPass(plan, config, connections, SNAPSHOTS in options)
        }
        val dryRun = DRY_RUN in options
        val checkpoints = if (dryRun) {
            InMemoryCheckpointRepository()
        } else {
            FileCheckpointRepository(Path.of(config.checkpoint))
        }
        val importer = importer(plan, config.options(dryRun, RESTART in options), connections, checkpoints)
        plan.aggregates.keys.forEach { importer.importAll(it) }
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
            imported = if (dryRun) InMemoryImportedKeys() else importedKeys(config),
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

    /**
     * The final pass once 9.x has stopped writing (`import --final`, step 3): one pass of the tail from
     * its checkpoint with the final look-back window, then the reservations and the compensation executions walked
     * from the first. It refuses, writing nothing, when an aggregate's tail did not follow 9.x continuously: the final
     * pass is then `import --restart`.
     */
    private suspend fun finalPass(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        snapshots: Boolean,
    ): Int {
        val checkpoints = FileCheckpointRepository(Path.of(config.checkpoint))
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
            imported = importedKeys(config),
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
        return if (snapshots) snapshot(plan, config, connections, restart = true) else OK
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
            imported = importedKeys(config),
        )
        val differingKeys = plan.reservations.keys.map { reservations.compare(it) }.filterNot { it.passed }
        val executions = compareCompensation(plan, config, connections)
        if (differing.isEmpty() && differingKeys.isEmpty() && executions?.passed != false) {
            out.println(
                "Comparison done: every state compared equals its 9.x snapshot, every confirmed 9.x key is " +
                    "reserved for its owner, and every unresolved 9.x execution imported is parked; nothing " +
                    "imported was released or resolved in 9.x since.",
            )
            return OK
        }
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

    /** Backfills the tags of every aggregate of the plan's contexts in their Manifests, then prints what comes next. */
    private suspend fun backfillTags(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        restart: Boolean,
        force: Boolean,
    ): Int {
        val backfiller = TagsBackfiller(
            models = models(config),
            logOf = connections::logOf,
            checkpoints = FileCheckpointRepository(Path.of(config.checkpoint)),
            options = config.options(restart = restart),
            progress = { out.println(it.render()) },
        )
        val types = plan.manifest.contexts.filter { it.name in plan.contexts }
            .flatMap { context -> context.aggregates.map { AggregateType(context.name, it.name) } }
        out.println(TAGS_ORDER)
        types.forEach { backfiller.backfill(it) }
        val left = types.filter { backfiller.untagged(it) }
        if (left.isNotEmpty()) {
            out.println("Streams without tags remain in $left (appended meanwhile by an old node?): run it again.")
            if (!force) return FAILED
        }
        val sinks = connections.eventSinks
        if (sinks == null) {
            out.println("Tags backfilled. No Elasticsearch cluster is configured: nothing to re-sink.")
            return if (left.isEmpty()) OK else FAILED
        }
        types.forEach { type ->
            val overwritten = backfiller.resink(
                type,
                UntaggedDocuments.of(sinks.sinkOf(type.context, type.name)),
                force
            )
            out.println("$type events index: $overwritten documents re-sunk with their tags")
        }
        out.println("Tags backfilled and re-sunk.")
        return if (left.isEmpty()) OK else FAILED
    }

    /** Exports to 9.x what V10 wrote after the cut-over: scan, then write, then count. */
    private suspend fun export(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        dryRun: Boolean,
        restart: Boolean,
    ): Int {
        val exporter = EventStreamExporter(
            plan = plan,
            logOf = connections::logOf,
            target = connections.nineXTarget,
            checkpoints = FileCheckpointRepository(Path.of(config.checkpoint)),
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

    /** Records where the imported history ends in each context's log, after a pass that appended to it. */
    private suspend fun recordCutover(plan: MigrationPlan, connections: MigrationConnections) {
        val recorder = CutoverRecorder.of(plan, connections)
        out.println(recorder.render(recorder.record()))
    }

    /** The keys the reservation import confirmed, beside the checkpoint file. */
    private fun importedKeys(config: MigrationConfig): ImportedKeys =
        FileImportedKeys(Path.of("${config.checkpoint}$IMPORTED_KEYS"))

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
        private const val BACKFILL_TAGS = "backfill-tags"
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

        /** The order of a tags backfill, printed before it starts. */
        const val TAGS_ORDER = "Tags backfill, in this order: 1. backfill the tags of every V10 log; 2. only then " +
            "re-sink the Elasticsearch events documents indexed without them, from the log (refused while a log " +
            "still holds a stream without tags, unless --force)."

        /** The suffix of the file of the keys the import confirmed, after the checkpoint file's name. */
        const val IMPORTED_KEYS = ".reservations.jsonl"
        private val COMMANDS = setOf(DRAFT_MAP, CHECK, IMPORT, TAIL, COMPARE, SNAPSHOT, BACKFILL_TAGS, EXPORT)
        private val KNOWN_OPTIONS = mapOf(
            DRAFT_MAP to setOf(CONFIG, OUT, FORCE, DRY_RUN),
            CHECK to setOf(CONFIG),
            IMPORT to setOf(CONFIG, DRY_RUN, RESTART, FINAL, SNAPSHOTS),
            TAIL to setOf(CONFIG, ONCE),
            COMPARE to setOf(CONFIG, SAMPLE, RESTART),
            SNAPSHOT to setOf(CONFIG, RESTART),
            BACKFILL_TAGS to setOf(CONFIG, RESTART, FORCE),
            EXPORT to setOf(CONFIG, DRY_RUN, RESTART),
        )
        private val USAGE = """
            Usage: migrate-data <command> --config <file>
              draft-map [--out <file>] [--force] [--dry-run]  drafts migration-map.json from the names the 9.x store
                        holds and the V10 Manifest, listing what it cannot match as exceptions; never replaces a
                        file without --force; --dry-run prints the draft and writes nothing
              check     checks migration-map.json against the 9.x store and the V10 Manifest; writes nothing
              import    [--dry-run] [--restart | --final] [--snapshots]  imports the 9.x event streams into the V10
                        log, then writes the V10 snapshots with --snapshots; --final is the final pass after 9.x
                        stops: the tail from its checkpoint, then the reservations and compensation walked again
              tail      [--once]  follows what 9.x commits after the import, until stopped
              compare   [--sample <percent>] [--restart]  compares each 9.x snapshot with the V10 state rebuilt
                        from the log at its version; exits with 3 when one differs
              snapshot  [--restart]  writes the V10 snapshot of every imported instance
              backfill-tags  [--restart] [--force]  records the tags of the V10 streams appended without them,
                        then re-sinks the Elasticsearch events documents indexed without them
              export    [--dry-run] [--restart]  writes back to the 9.x store what V10 wrote after the cut-over,
                        for a rollback; refuses V10-only types; exits with 3 when the stream counts differ
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
