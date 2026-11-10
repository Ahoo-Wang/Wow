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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

internal class MigrateDataConfigurationTest : MigrateDataFixtures() {
    @Test
    fun `a migration failure ends the run with its message`() {
        run("tail", "--config", config().toString(), "--once").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("FAILED The full import of")
    }

    @Test
    fun `usage errors print the usage`() {
        run("--help").assert().isEqualTo(MigrateData.OK)
        printed.assert().startsWith("Usage: migrate-data <command> --config <file>").contains("--help    prints this")
        run().assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("migrate", "--config", "x").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("check").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("check", "--config", "x", "--once").assert().isEqualTo(MigrateData.USAGE_ERROR)
        printed.assert().contains("Unknown options [--once]").contains("Usage: migrate-data")
        run("compare", "--config", "x", "--sample", "0").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("compare", "--config", "x", "--sample", "all").assert().isEqualTo(MigrateData.USAGE_ERROR)
        printed.assert().contains("--sample takes a percentage from 1 to 100.")
        run("import", "--config", "x", "--dry-run", "--snapshots").assert().isEqualTo(MigrateData.USAGE_ERROR)
        printed.assert().contains("A dry run writes no snapshots")
    }

    @Test
    fun `every connection string written env NAME should be read from the environment, the JDBC URL and user too`() {
        val environment = mapOf(
            "SOURCE_URI" to "mongodb://source:27017",
            "SOURCE_DB" to "example_db",
            "PG_URL" to "jdbc:postgresql://db:5432/wow",
            "PG_USER" to "wow",
            "PG_PASSWORD" to "secret",
            "LEDGER_DB" to "compensation_db",
            "LEDGER_URI" to "mongodb://ledger:27017",
        )
        val written = MigrationConfig(
            map = "map.json",
            manifests = emptyList(),
            source = SourceConfig("env:SOURCE_URI", "env:SOURCE_DB"),
            target = TargetConfig(
                TargetConfig.POSTGRES,
                jdbcUrl = "env:PG_URL",
                username = "env:PG_USER",
                password = "env:PG_PASSWORD",
            ),
            compensation = CompensationConfig("env:LEDGER_DB", "order-service", "env:LEDGER_URI"),
        )
        val resolved = written.resolved { environment[it] }
        resolved.source.assert().isEqualTo(SourceConfig("mongodb://source:27017", "example_db"))
        resolved.target.jdbcUrl.assert().isEqualTo("jdbc:postgresql://db:5432/wow")
        resolved.target.username.assert().isEqualTo("wow")
        resolved.target.password.assert().isEqualTo("secret")
        resolved.compensation.assert().isEqualTo(
            CompensationConfig("compensation_db", "order-service", "mongodb://ledger:27017")
        )
        // A string not written so stays as written, and an unset variable is named.
        written.copy(target = TargetConfig(TargetConfig.POSTGRES, jdbcUrl = "jdbc:postgresql://x/wow")).resolved {
            environment[it]
        }
            .target.jdbcUrl.assert().isEqualTo("jdbc:postgresql://x/wow")
        assertThrows<MigrationException> { written.resolved { null } }.message.assert().contains("SOURCE_URI")
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
            it.readParallelism.assert().isEqualTo(ImportOptions.DEFAULT_READ_PARALLELISM)
        }
        config.copy(readParallelism = 4).options().readParallelism.assert().isEqualTo(4)
        MigrationConfig.resolve(null).assert().isNull()
        MigrationConfig.resolve("plain").assert().isEqualTo("plain")
        MigrationConfig.resolve(
            "env:PASSWORD"
        ) { if (it == "PASSWORD") "secret" else null }.assert().isEqualTo("secret")
        assertThrows<MigrationException> { MigrationConfig.resolve("env:MISSING") { null } }
        config.final.assert().isEqualTo(FinalPassConfig(lookbackMillis = 60_000, maxTailAgeMillis = 600_000))
        assertThrows<IllegalArgumentException> { FinalPassConfig(lookbackMillis = -1) }
        assertThrows<IllegalArgumentException> { FinalPassConfig(maxTailAgeMillis = 0) }
    }

    @Test
    fun `the class path of the models resolves against the configuration`() {
        val path = config()
        Files.writeString(path, Files.readString(path).replace("\"pollIntervalMillis\": 1", "\"classpath\": [\"lib\"]"))
        MigrationConfig.read(path).classpath.assert().containsExactly(directory.resolve("lib").toString())
    }

    @Test
    fun `backfill-tags is gone, the service stamps the access attributes itself`() {
        run("backfill-tags", "--config", config().toString()).assert().isEqualTo(MigrateData.USAGE_ERROR)
    }

    @Test
    fun `a target is PostgreSQL or MongoDB, with what it needs`() {
        TargetConfig("mongo", uri = "mongodb://localhost", database = "wow").type.assert().isEqualTo("mongo")
        assertThrows<IllegalArgumentException> { TargetConfig("redis") }
        assertThrows<IllegalArgumentException> { TargetConfig("postgres") }
        assertThrows<IllegalArgumentException> { TargetConfig("mongo", uri = "mongodb://localhost") }
        assertThrows<IllegalArgumentException> { TargetConfig("mongo", database = "wow") }
        TargetConfig("postgres", jdbcUrl = "jdbc:postgresql://localhost/wow").poolSize.assert()
            .isEqualTo(TargetConfig.DEFAULT_POOL_SIZE)
        assertThrows<IllegalArgumentException> { TargetConfig("postgres", jdbcUrl = "jdbc:x", poolSize = 0) }
    }

    @Test
    fun `without a compensation database there is no failure ledger to write`() {
        ConfiguredConnections(MigrationConfig.read(config())).use { configured ->
            configured.compensation.assert().isNull()
            assertThrows<MigrationException> { configured.failureLedger() }
        }
        assertThrows<IllegalArgumentException> { CompensationConfig(" ", "s") }
        assertThrows<IllegalArgumentException> { CompensationConfig("compensation_db", " ") }
        val bare = object : MigrationConnections {
            override val source: NineXEventSource get() = nineX

            override val snapshots: NineXSnapshotSource get() = nineXSnapshots

            override fun logOf(context: String): EventLog = log

            override fun snapshotStoreOf(context: String): SnapshotStore = store

            override val reservations: NineXReservationSource get() = prepared

            override fun reservationStore(): ReservationStore = reservationStore

            override fun checkpointStore(): CheckpointStore = checkpointStore

            override fun close() = Unit
        }
        bare.compensation.assert().isNull()
        assertThrows<MigrationException> { bare.failureLedger() }
        assertThrows<MigrationException> { bare.nineXTarget }
    }

    @Test
    fun `several Manifests read as one`() {
        val path = directory.resolve("manifest.json")
        Files.writeString(path, NineXExample.MANIFEST_TEXT)
        readManifests(listOf(path.toString(), path.toString())).contexts.size.assert().isEqualTo(2)
    }

    @Test
    fun `without manifests, the Manifests should be the class path's fragments, which must hold every mapped context`() {
        val lib = Files.createDirectories(directory.resolve("lib/META-INF/wow/manifest")).also {
            Files.writeString(it.resolve("ninex-example.json"), NineXExample.MANIFEST_TEXT)
        }.parent.parent.parent
        val config = config(manifests = "\"classpath\": [\"lib\"],").toString()
        MigrationConfig.read(Path.of(config)).manifests.assert().isEmpty()
        run("check", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("Mapping check passed.")
        classpathManifests(listOf(lib.toString()), javaClass.classLoader).contexts.map { it.name }
            .assert().containsExactly(NineXExample.CONTEXT)

        // draft-map reads them the same way, with no map to hold them to.
        run("draft-map", "--config", config, "--dry-run").assert().describedAs(printed).isEqualTo(MigrateData.OK)

        Files.createDirectories(directory.resolve("empty"))
        val bare = config(manifests = "\"classpath\": [\"empty\"],").toString()
        run("check", "--config", bare).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains(
            "FAILED The configuration names no manifests, and the Manifest fragments of the modules on the classpath " +
                "[${directory.resolve("empty")}] hold no context [example], which the map imports into: add the lib of " +
                "the service that hosts each to \"classpath\", or name its Manifest in \"manifests\".",
        )
    }

    @Test
    fun `without manifests or a classpath, the Manifests should be the tool's own modules', each fragment read`() {
        // The tool's own class path holds the 9.x example's module, whose fragment no resource holds here.
        val config = config(manifests = "").toString()
        run("check", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains(
            "FAILED The Manifest fragments of the modules in the tool's class path do not assemble: The Manifest " +
                "fragments could not be read:\n- fragment META-INF/wow/manifest/ninex-example.json is not on the " +
                "class path",
        )
    }
}
