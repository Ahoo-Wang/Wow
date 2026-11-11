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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The checkpoints kept in the target (`"checkpoint": "target:<name>"`) on PostgreSQL and on MongoDB: the table's
 * compare-and-set and fenced key writes, the roles' leases in the target's own lease store, and a migration run on them.
 */
class TargetCheckpointStoreTest {
    @TempDir
    lateinit var directory: Path

    private class Backend(val name: String, val table: CheckpointTable, val leases: LeaseStore)

    private fun postgres(): Backend {
        val postgres = WowContainers.postgres
        val database = "wow_checkpoints_${databases.incrementAndGet()}_${System.nanoTime()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().use { statement -> statement.execute("CREATE DATABASE $database") }
        }
        val dataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$database"))
            user = postgres.username
            password = postgres.password
        }
        return Backend(
            "PostgreSQL",
            PostgresCheckpointTable(dataSource).also { it.createSchema() },
            PostgresLeaseStore(dataSource).also { it.createSchema() },
        )
    }

    private fun mongo(): Backend {
        val database = "wow_checkpoints_${databases.incrementAndGet()}"
        return Backend(
            "MongoDB",
            MongoCheckpointTable(NineXMongoStore.client, database).also { it.createSchema() },
            MongoLeaseStore(NineXMongoStore.client, database).also { it.createSchema() },
        )
    }

    @Test
    fun `the checkpoints should be written by compare-and-set, and every write fenced by the writers' epochs`() {
        listOf(postgres(), mongo()).forEach { backend ->
            val table = backend.table
            // Keys recorded before any checkpoint: no fence to hold them to yet.
            table.addKeys("fresh", listOf(KEY)) { it.assert().describedAs(backend.name).isEmpty() }
            table.keys("fresh", "order_no", null, 10).map { it.key }
                .assert().describedAs(backend.name).containsExactly(KEY)
            val first = ImportCheckpoints().with(CART, AggregateCheckpoint(startedAt = 1))
            table.load("m").assert().describedAs(backend.name).isNull()
            table.write("m", 0, first, mapOf("import" to 1L)).assert().describedAs(backend.name).isTrue()
            // A second first write, or a write over an older version, loses.
            table.write("m", 0, first, mapOf("import" to 1L)).assert().describedAs(backend.name).isFalse()
            table.load("m").assert().describedAs(backend.name)
                .isEqualTo(StoredCheckpoints(first, 1, mapOf("import" to 1L)))

            val paused = TargetCheckpointRepository(table, "m", held(CheckpointRole.IMPORT to 1))
            val pausedKeys = TargetImportedKeys(table, "m", held(CheckpointRole.IMPORT to 1), pageSize = 2)
            runBlocking { pausedKeys.add(listOf(KEY, KEY.copy(key = "no-2"), KEY.copy(key = "no-3"))) }
            TargetCheckpointRepository(table, "m", held(CheckpointRole.IMPORT to 2))
                .update { it.with(CART, AggregateCheckpoint(startedAt = 2)) }
            assertThrows<MigrationException> { paused.update { it } }.message.assert().describedAs(backend.name)
                .contains("(lease epoch 2, this run's 1)")
            assertThrows<MigrationException> { runBlocking { pausedKeys.add(listOf(KEY.copy(key = "no-4"))) } }
            assertThrows<MigrationException> { runBlocking { pausedKeys.retain("order_no") { false } } }

            val current = TargetImportedKeys(table, "m", held(CheckpointRole.IMPORT to 2), pageSize = 2)
            runBlocking {
                current.retain("order_no") { it.key != "no-2" }
                val kept = mutableListOf<String>()
                current.forEach("order_no") { kept += it.key }
                kept.assert().describedAs(backend.name).containsExactly("no-1", "no-3")
            }
            table.load("m")!!.also {
                it.checkpoints.of(CART)!!.startedAt.assert().describedAs(backend.name).isEqualTo(2)
                it.fences.assert().describedAs(backend.name).isEqualTo(mapOf("import" to 2L))
            }
        }
    }

    @Test
    fun `a role's lease in the target should be held by one run, naming it to the next, beside a run of another role`() {
        listOf(postgres(), mongo()).forEach { backend ->
            runBlocking {
                val tail = leases(backend, CheckpointHolder("tail", "migrate-tail-0", 1, "run-a"))
                val other = leases(backend, CheckpointHolder("import", "migrate-import-0", 1, "run-b"))
                val holding = CompletableDeferred<Unit>()
                val done = CompletableDeferred<Unit>()
                val tailing = async {
                    tail.holding(setOf(CheckpointRole.IMPORT)) { lock ->
                        holding.complete(Unit)
                        done.await()
                        lock.epochs
                    }
                }
                holding.await()
                assertThrows<MigrationException> { other.holding(setOf(CheckpointRole.IMPORT)) { } }
                    .message.assert().describedAs(backend.name).startsWith(
                        "The checkpoint target:m is in use: tail on migrate-tail-0 (pid 1, run run-a) holds its " +
                            "import lease, renewed while it runs",
                    )
                other.holding(setOf(CheckpointRole.COMPARE)) { it.epochs }
                    .assert().describedAs(backend.name).isEqualTo(mapOf(CheckpointRole.COMPARE to 1L))
                done.complete(Unit)
                tailing.await().assert().describedAs(backend.name).isEqualTo(mapOf(CheckpointRole.IMPORT to 1L))
                // Released: the next run takes it at once, at the next epoch.
                other.holding(setOf(CheckpointRole.IMPORT, CheckpointRole.EXPORT)) { it.epochs }
                    .assert().describedAs(backend.name)
                    .isEqualTo(mapOf(CheckpointRole.IMPORT to 2L, CheckpointRole.EXPORT to 1L))
            }
        }
    }

    @Test
    fun `an import on PostgreSQL should keep its checkpoints and its reserved keys in the target, and compare reads them`() {
        val postgres = WowContainers.postgres
        val database = "wow_target_checkpoints_${databases.incrementAndGet()}_${System.nanoTime()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().use { statement -> statement.execute("CREATE DATABASE $database") }
        }
        val url = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/$database")
        migrate(
            """{ "type": "postgres", "jdbcUrl": "$url", "username": "${postgres.username}", """ +
                """"password": "${postgres.password}" }""",
        )
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name, fences FROM ${PostgresCheckpointTable.CHECKPOINTS}").use { row ->
                    row.next().assert().isTrue()
                    row.getString("name").assert().isEqualTo("example")
                    WowJson.mapper.readValue(row.getString("fences"), Map::class.java)
                        .assert().isEqualTo(mapOf("import" to 2, "compare" to 1))
                    row.next().assert().isFalse()
                }
                statement.executeQuery("SELECT count(*) FROM ${PostgresCheckpointTable.KEYS_TABLE}").use { row ->
                    row.next()
                    row.getLong(1).assert().isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `an import on MongoDB should keep its checkpoints and its reserved keys in the target, and compare reads them`() {
        val target = "wow_v10_target_checkpoints"
        migrate("""{ "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$target" }""")
        val database = NineXMongoStore.client.getDatabase(target)
        val stored = database.getCollection(MongoCheckpointTable.CHECKPOINTS).find().toList().single()
        stored.getString("_id").assert().isEqualTo("example")
        stored.get("fences", Document::class.java).assert().isEqualTo(Document(mapOf("import" to 2L, "compare" to 1L)))
        database.getCollection(MongoCheckpointTable.KEYS).countDocuments().assert().isEqualTo(1)
    }

    /**
     * Imports the 9.x example, with one confirmed key, into [target] (its JSON), the checkpoints kept there, then again
     * from the first, then compares: each run writes them, and none leaves a file.
     */
    private fun migrate(target: String) {
        val nineX = NineXMongoStore.example()
        val first = NineXExample.stream("order-stream-v1.json")
        NineXMongoStore.prepare(
            nineX,
            "order_no",
            "NO-1",
            Document("orderId", first.aggregateId.id),
            NineXPreparedKey.TTL_FOREVER
        )
        val map = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.order\",",
            """
            "to": "example.order",
            "reservations": [ { "from": "order_no", "namespace": "order.no", "ownerId": "orderId" } ],
            """.trimIndent(),
        )
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        val config = directory.resolve("migrate-data.json")
        Files.writeString(
            config,
            """
            {
              "map": "migration-map.json",
              "manifests": ["manifest.json"],
              "checkpoint": "target:example",
              "source": { "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "${nineX.name}" },
              "target": $target,
              "partitions": { "${NineXExample.CONTEXT}": 4 },
              "pageSize": 2
            }
            """.trimIndent(),
        )
        val output = ByteArrayOutputStream()
        fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

        run("import", "--config", config.toString()).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        run("import", "--config", config.toString(), "--restart").assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        run("compare", "--config", config.toString()).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert().contains("PrepareKey order_no compared: 1 confirmed keys, 0 differ")
        Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() }
            .filter { it.contains("checkpoint") }.assert().isEmpty()
    }

    private fun leases(backend: Backend, holder: CheckpointHolder) = TargetCheckpointLeases(
        backend.leases,
        "m",
        holder,
        ttl = 2.seconds,
        renewal = 300.milliseconds,
        poll = 100.milliseconds,
    )

    private fun held(vararg epochs: Pair<CheckpointRole, Long>): CheckpointLock = object : CheckpointLock {
        override val epochs: Map<CheckpointRole, Long> = mapOf(*epochs)
    }

    private companion object {
        val databases = AtomicInteger()
        val CART = NineXAggregate("example-service", "cart")
        val KEY = ImportedKey.of(
            "order_no",
            "no-1",
            ReservationScope.of(AggregateType("example", "order"), "(0)", "order.no"),
            AggregateType("example", "order").ref("(0)", "order-1"),
        )
    }
}
