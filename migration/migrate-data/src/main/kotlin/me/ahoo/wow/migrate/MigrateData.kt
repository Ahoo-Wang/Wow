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
 * migrate-data check  --config <file>                       the mapping check: a dry run that writes nothing
 * migrate-data import --config <file> [--dry-run] [--restart]
 * migrate-data tail   --config <file> [--once]
 * ```
 *
 * - `check` scans the 9.x store and checks `migration-map.json` against it and the V10 Manifest.
 * - `import` checks first and does not start on a failed check; then imports every mapped 9.x aggregate, resuming from
 *   its checkpoint. `--dry-run` converts every stream and appends none; `--restart` walks every instance again, the
 *   final complete pass once 9.x has stopped writing.
 * - `tail` follows what 9.x commits after the full import until it is stopped (or after one pass with `--once`), the
 *   increment until cut-over. It reads the 9.x store by commit time, which needs an index 9.x did not create: without
 *   it the tail refuses to start and prints the command that creates it, unless `source.createCommitTimeIndex` lets
 *   it create the index itself, the tool's only write to a 9.x store.
 *
 * From the repository: `./gradlew :migrate-data:run --args="check --config /path/to/migrate-data.json"`.
 */
class MigrateData(
    private val out: PrintStream = System.out,
    private val connect: (MigrationConfig) -> MigrationConnections = ::ConfiguredConnections,
    private val running: () -> Boolean = { true },
) {
    /** Runs [args]; returns the process exit code: 0 done, 1 the check or the import failed, 2 a usage error. */
    fun run(args: List<String>): Int {
        val command = args.firstOrNull()?.takeIf { it in COMMANDS }
        val options = args.drop(1)
        val configPath = options.zipWithNext().firstOrNull { it.first == "--config" }?.second
        if (command == null || configPath == null) {
            out.println(USAGE)
            return USAGE_ERROR
        }
        val unknown = options.filter { it.startsWith("--") } - KNOWN_OPTIONS.getValue(command)
        if (unknown.isNotEmpty()) {
            out.println("Unknown options $unknown.\n$USAGE")
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
                        IMPORT -> import(plan, config, connections, "--dry-run" in options, "--restart" in options)
                        else -> tail(plan, config, connections, "--once" in options)
                    }
                }
            }
        } catch (failure: MigrationException) {
            out.println("FAILED ${failure.message}")
            FAILED
        }
    }

    private fun check(plan: MigrationPlan, connections: MigrationConnections): Int {
        val report = MappingCheck(plan).check(connections.source.inventory())
        out.println(report.render())
        return if (report.passed) OK else FAILED
    }

    private suspend fun import(
        plan: MigrationPlan,
        config: MigrationConfig,
        connections: MigrationConnections,
        dryRun: Boolean,
        restart: Boolean,
    ): Int {
        if (check(plan, connections) != OK) {
            return FAILED
        }
        val checkpoints = if (dryRun) {
            InMemoryCheckpointRepository()
        } else {
            FileCheckpointRepository(Path.of(config.checkpoint))
        }
        val importer = importer(plan, config.options(dryRun, restart), connections, checkpoints)
        plan.aggregates.keys.forEach { importer.importAll(it) }
        out.println(if (dryRun) "Dry run done: nothing was appended." else "Import done.")
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
        private const val CHECK = "check"
        private const val IMPORT = "import"
        private const val TAIL = "tail"
        private val COMMANDS = setOf(CHECK, IMPORT, TAIL)
        private val KNOWN_OPTIONS = mapOf(
            CHECK to setOf("--config"),
            IMPORT to setOf("--config", "--dry-run", "--restart"),
            TAIL to setOf("--config", "--once"),
        )
        private val USAGE = """
            Usage: migrate-data <command> --config <file>
              check   checks migration-map.json against the 9.x store and the V10 Manifest; writes nothing
              import  [--dry-run] [--restart]  imports the 9.x event streams into the V10 log
              tail    [--once]  follows what 9.x commits after the import, until stopped
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
