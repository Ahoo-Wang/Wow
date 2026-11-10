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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

internal class MigrateDataCheckpointsTest : MigrateDataFixtures() {
    @Test
    fun `import and the tail keep their checkpoints in the target under the name target gives, and no file`(): Unit =
        runBlocking {
            val config = config(checkpoint = "target:example").toString()
            run("import", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
            run("tail", "--config", config, "--once").assert().describedAs(printed).isEqualTo(MigrateData.OK)
            run("compare", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
            val stored = checkpointTable.load("example")!!
            stored.checkpoints.of(NineXAggregate("example-service", "cart"))!!.tailedAt.assert().isNotNull()
            stored.checkpoints.comparisonOf(NineXAggregate("example-service", "order"))!!.completed.assert().isTrue()
            stored.fences.assert().isEqualTo(mapOf("import" to 2L, "compare" to 1L))
            Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() }
                .filter { it.contains("checkpoint") }.assert().isEmpty()
            // Every lease was released when its run ended.
            leaseStore.owners("${TargetCheckpointLeases.GROUP_PREFIX}example").assert().isEmpty()
            MigrationConfig.read(Path.of(config)).checkpoint.assert().isEqualTo("target:example")
        }

    @Test
    fun `a run that paused past its lease, which another run took and wrote, should stop at its next write`() {
        val config = config(checkpoint = "target:example").toString()
        var loads = 0
        checkpointTable.beforeLoad = {
            if (++loads == 3) {
                // The import pauses here; its lease expires by the store's clock, and another run takes it and writes.
                leaseClock.millis += TargetCheckpointLeases.TTL.inWholeMilliseconds + 1
                val other = runBlocking {
                    leaseStore.acquire("${TargetCheckpointLeases.GROUP_PREFIX}example", 0, "tail on other", TargetCheckpointLeases.TTL)
                }!!
                val stored = checkpointTable.load("example")
                checkpointTable.write(
                    "example",
                    stored?.version ?: 0,
                    stored?.checkpoints ?: ImportCheckpoints(),
                    stored?.fences.orEmpty() + ("import" to other.epoch),
                )
            }
        }
        run("import", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.lines().last { it.isNotBlank() }.assert().isEqualTo(
            "FAILED This run lost its import lease of the checkpoint target:example to another run, which has " +
                "written the checkpoint since (lease epoch 2, this run's 1). It stops without writing.",
        )
        printed.assert().doesNotContain("Import done.")
    }

    @Test
    fun `a second run of a role on a checkpoint file should be refused, naming the first, beside a run of another`(): Unit =
        runBlocking {
            val config = config().toString()
            run("import", "--config", config).assert().isEqualTo(MigrateData.OK)
            val file = directory.resolve("migrate-data.checkpoint.json")
            FileCheckpointLocks(
                file,
                CheckpointHolder("tail", "migrate-tail-0", 7, "run-a")
            ).holding(setOf(CheckpointRole.IMPORT)) {
                run("export", "--config", config).assert().isEqualTo(MigrateData.FAILED)
                printed.assert().contains(
                    "FAILED The checkpoint $file is in use: tail on migrate-tail-0 (pid 7, run run-a), since ",
                ).contains("holds its import lock")
                run("compare", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
            }
        }

    @Test
    fun `a checkpoint kept in the target has a name of its own`() {
        assertThrows<IllegalArgumentException> {
            MigrationConfig(
                map = "m",
                checkpoint = "target:a b",
                source = SourceConfig("mongodb://x", "db"),
                target = TargetConfig(TargetConfig.POSTGRES, jdbcUrl = "jdbc:postgresql://x/wow"),
            )
        }.message.assert().contains("not \"target:a b\"")
        MigrationConfig(
            map = "m",
            checkpoint = "target:order-service.v1_2",
            source = SourceConfig("mongodb://x", "db"),
            target = TargetConfig(TargetConfig.POSTGRES, jdbcUrl = "jdbc:postgresql://x/wow"),
        ).checkpointName.assert().isEqualTo("order-service.v1_2")
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
