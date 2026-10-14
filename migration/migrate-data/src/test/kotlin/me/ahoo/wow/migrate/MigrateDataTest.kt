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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class MigrateDataTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val source = InMemoryNineXEventSource(NineXExample.streams())
    private val log = InMemoryEventLog(4)
    private var closed = false
    private var passes = 0

    private val connections = object : MigrationConnections {
        override val source: NineXEventSource = this@MigrateDataTest.source

        override fun logOf(context: String): EventLog = log

        override fun close() {
            closed = true
        }
    }

    private fun config(map: String = NineXExample.MAP_TEXT): Path {
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "mongodb://localhost:27017", "database": "example_db" },
                  "target": { "type": "postgres", "jdbcUrl": "jdbc:postgresql://localhost:5432/wow" },
                  "partitions": { "example": 4 },
                  "pollIntervalMillis": 1
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String, running: () -> Boolean = { true }): Int =
        MigrateData(PrintStream(output, true), { connections }, running).run(args.toList())

    private val printed: String get() = output.toString()

    @Test
    fun `check reports and writes nothing`() {
        run("check", "--config", config().toString()).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Mapping check passed.")
        closed.assert().isTrue()
        Files.exists(directory.resolve("migrate-data.checkpoint.json")).assert().isFalse()
    }

    @Test
    fun `import checks, imports and checkpoints, and the tail follows`(): Unit = runBlocking {
        val config = config()
        run("import", "--config", config.toString()).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("example-service.order done").contains("Import done.")
        val checkpoints = FileCheckpointRepository(directory.resolve("migrate-data.checkpoint.json")).load()
        checkpoints.of(NineXAggregate("example-service", "cart"))!!.completed.assert().isTrue()
        run("tail", "--config", config.toString(), "--once").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Tail stopped.")
        run("tail", "--config", config.toString(), running = { ++passes < 2 }).assert().isEqualTo(MigrateData.OK)
        passes.assert().isEqualTo(2)
        run("import", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("4 skipped")
    }

    @Test
    fun `a dry run appends nothing`(): Unit = runBlocking {
        run("import", "--config", config().toString(), "--dry-run").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Dry run done: nothing was appended.")
        (0 until log.partitions).all { log.head(it) == null }.assert().isTrue()
        Files.exists(directory.resolve("migrate-data.checkpoint.json")).assert().isFalse()
    }

    @Test
    fun `a failed check stops the import`(): Unit = runBlocking {
        val map = NineXExample.MAP_TEXT.replace("\"cart_item_removed\"", "\"cart_item_gone\"")
        run("import", "--config", config(map).toString()).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("cart_item_removed@0.0.1 (1 events) has no mapping")
        log.read(NineXExample.orderModel.type.ref("(0)", "x")).toList().assert().isEmpty()
        run("check", "--config", config(map).toString()).assert().isEqualTo(MigrateData.FAILED)
    }

    @Test
    fun `a migration failure ends the run with its message`() {
        run("tail", "--config", config().toString(), "--once").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("FAILED The full import of")
    }

    @Test
    fun `usage errors print the usage`() {
        run().assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("migrate", "--config", "x").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("check").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("check", "--config", "x", "--once").assert().isEqualTo(MigrateData.USAGE_ERROR)
        printed.assert().contains("Unknown options [--once]").contains("Usage: migrate-data")
    }

    @Test
    fun `the configuration resolves its paths and environment values`() {
        val config = MigrationConfig.read(config())
        config.map.assert().isEqualTo(directory.resolve("migration-map.json").toString())
        config.checkpoint.assert().isEqualTo(directory.resolve("migrate-data.checkpoint.json").toString())
        config.partitionsOf("example").assert().isEqualTo(4)
        assertThrows<MigrationException> { config.partitionsOf("other") }
        config.options(dryRun = true, restart = true).also {
            it.dryRun.assert().isTrue()
            it.restart.assert().isTrue()
            it.pageSize.assert().isEqualTo(500)
        }
        MigrationConfig.resolve(null).assert().isNull()
        MigrationConfig.resolve("plain").assert().isEqualTo("plain")
        MigrationConfig.resolve(
            "env:PASSWORD"
        ) { if (it == "PASSWORD") "secret" else null }.assert().isEqualTo("secret")
        assertThrows<MigrationException> { MigrationConfig.resolve("env:MISSING") { null } }
    }

    @Test
    fun `a target is PostgreSQL or MongoDB, with what it needs`() {
        TargetConfig("mongo", uri = "mongodb://localhost", database = "wow").type.assert().isEqualTo("mongo")
        assertThrows<IllegalArgumentException> { TargetConfig("redis") }
        assertThrows<IllegalArgumentException> { TargetConfig("postgres") }
        assertThrows<IllegalArgumentException> { TargetConfig("mongo", uri = "mongodb://localhost") }
        assertThrows<IllegalArgumentException> { TargetConfig("mongo", database = "wow") }
    }

    @Test
    fun `several Manifests read as one`() {
        val path = directory.resolve("manifest.json")
        Files.writeString(path, NineXExample.MANIFEST_TEXT)
        readManifests(listOf(path.toString(), path.toString())).contexts.size.assert().isEqualTo(2)
    }

    @Test
    fun `checkpoints keep in a file, replaced whole`() {
        val path = directory.resolve("checkpoint.json")
        val repository = FileCheckpointRepository(path)
        repository.load().aggregates.assert().isEmpty()
        val aggregate = NineXAggregate("example-service", "order")
        val checkpoints = ImportCheckpoints().with(
            aggregate,
            AggregateCheckpoint(startedAt = 1, after = NineXAggregateId("o-1", "(0)"), completed = true, tailedTo = 9),
        )
        repository.save(checkpoints)
        repository.load().assert().isEqualTo(checkpoints)
        Files.exists(directory.resolve("checkpoint.json.tmp")).assert().isFalse()
        assertThrows<IllegalArgumentException> { ImportCheckpoints(format = 2) }
    }
}
