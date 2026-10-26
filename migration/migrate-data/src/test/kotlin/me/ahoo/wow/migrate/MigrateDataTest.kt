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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
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

    private var nineX: NineXEventSource = source
    private var nineXSnapshots = InMemoryNineXSnapshotSource(NineXExample.snapshots(), source)
    private val store = InMemorySnapshotStore()
    private val prepared = InMemoryNineXReservationSource()
    private val reservationStore = InMemoryReservationStore()
    private var nineXCompensation: NineXCompensationSource? = null
    private val ledger = InMemoryFailureLedger()
    private val exportTarget = InMemoryNineXEventTarget(source)

    private val connections = object : MigrationConnections {
        override val source: NineXEventSource get() = nineX

        override val snapshots: NineXSnapshotSource get() = nineXSnapshots

        override fun logOf(context: String): EventLog = log

        override fun snapshotStoreOf(context: String): SnapshotStore = store

        override val reservations: NineXReservationSource get() = prepared

        override fun reservationStore(): ReservationStore = reservationStore

        override val compensation: NineXCompensationSource? get() = nineXCompensation

        override fun failureLedger(): FailureLedger = ledger

        override val nineXTarget: NineXEventTarget get() = exportTarget

        override fun close() {
            closed = true
        }
    }

    /** A 9.x store whose scan by commit time needs an index, as the MongoDB one does. */
    private class IndexedSource(delegate: NineXEventSource, val indexed: MutableSet<String> = mutableSetOf()) :
        NineXEventSource by delegate,
        CommitTimeIndexed {
        override fun missingCommitTimeIndexes(aggregates: Collection<NineXAggregate>): Map<String, String> =
            aggregates.map { "${it.name}_event_stream" }.filterNot { it in indexed }.associateWith { "create $it" }

        override fun ensureCommitTimeIndex(aggregate: NineXAggregate) {
            indexed += "${aggregate.name}_event_stream"
        }
    }

    private fun config(map: String = NineXExample.MAP_TEXT, createIndex: Boolean = false): Path {
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "mongodb://localhost:27017", "database": "example_db",
                              "createCommitTimeIndex": $createIndex },
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
    fun `import --final is the tail from its checkpoint, and needs a tail that followed 9x`(): Unit = runBlocking {
        val config = config().toString()
        run("import", "--config", config).assert().isEqualTo(MigrateData.OK)
        run("import", "--config", config, "--final").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("FAILED the tail of example-service.order never ran")
            .contains("run import --restart instead")
        run("tail", "--config", config, "--once").assert().isEqualTo(MigrateData.OK)
        // 9.x commits once more before it stops: the final pass imports it, without walking every instance again.
        val last = NineXExample.stream("order-stream-v4.json")
        source.add(
            last.copy(
                id = "late",
                version = 5,
                commandId = "late",
                requestId = "late",
                createTime = System.currentTimeMillis()
            )
        )
        run("import", "--config", config, "--final", "--snapshots").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Final pass done").contains("Snapshots done.")
        val ref = NineXExample.orderModel.type.ref(last.aggregateId.tenantId, last.aggregateId.id)
        log.read(ref).toList().map { it.version }.assert().containsExactly(1, 2, 3, 4, 5)
        run("import", "--config", config, "--final", "--restart").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("import", "--config", config, "--final", "--dry-run").assert().isEqualTo(MigrateData.USAGE_ERROR)
    }

    @Test
    fun `import --final walks the reservations and the compensation again`(): Unit = runBlocking {
        val id = NineXExample.stream("order-stream-v1.json").aggregateId
        prepared.add(NineXPreparedKey("order_no", "NO-1", WowJson.mapper.readTree("""{"orderId":"${id.id}"}"""), null))
        val map = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.order\",",
            "\"to\": \"example.order\", \"reservations\": [ { \"from\": \"order_no\", \"namespace\": \"order.no\", " +
                "\"ownerId\": \"orderId\" } ],",
        )
        val reserving = config(map).toString()
        run("import", "--config", reserving).assert().isEqualTo(MigrateData.OK)
        run("tail", "--config", reserving, "--once").assert().isEqualTo(MigrateData.OK)
        run("import", "--config", reserving, "--final").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("PrepareKey order_no reservations done").contains("Final pass done")
            .doesNotContain("Snapshots done.")

        prepared.remove("order_no", "NO-1")
        nineXCompensation = InMemoryNineXCompensationSource(NineXCompensationExample.executions())
        Files.writeString(directory.resolve("migration-map.json"), NineXCompensationExample.mapText())
        Files.writeString(directory.resolve("manifest.json"), NineXCompensationExample.MANIFEST_TEXT)
        val compensating = directory.resolve("compensating.json")
        Files.writeString(
            compensating,
            """
            {
              "map": "migration-map.json",
              "manifests": ["manifest.json"],
              "checkpoint": "compensating.checkpoint.json",
              "source": { "uri": "mongodb://localhost:27017", "database": "example_db" },
              "target": { "type": "postgres", "jdbcUrl": "jdbc:postgresql://localhost:5432/wow" },
              "partitions": { "example": 4 },
              "compensation": { "database": "compensation_db", "service": "example-service" }
            }
            """.trimIndent(),
        )
        run("import", "--config", compensating.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        run("tail", "--config", compensating.toString(), "--once").assert().isEqualTo(MigrateData.OK)
        run("import", "--config", compensating.toString(), "--final").assert().describedAs(printed)
            .isEqualTo(MigrateData.OK)
        printed.assert().contains("9.x compensation done")
    }

    @Test
    fun `the tail refuses a 9x store without its index, and creates it only when told to`() {
        val indexed = IndexedSource(source)
        nineX = indexed
        run("import", "--config", config().toString()).assert().isEqualTo(MigrateData.OK)
        run("tail", "--config", config().toString(), "--once").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("[order_event_stream, cart_event_stream]")
            .contains("  create order_event_stream")
            .contains("\"createCommitTimeIndex\": true")
            .doesNotContain("Tail stopped.")
        indexed.indexed.assert().isEmpty()

        run("tail", "--config", config(createIndex = true).toString(), "--once").assert().isEqualTo(MigrateData.OK)
        indexed.indexed.assert().containsExactlyInAnyOrder("order_event_stream", "cart_event_stream")

        // With the index in place, the tail runs without the flag, and writes nothing to the 9.x store.
        val existing = IndexedSource(source, mutableSetOf("order_event_stream", "cart_event_stream"))
        nineX = existing
        run("tail", "--config", config().toString(), "--once").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Tail stopped.")
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
    fun `import writes the snapshots when told, and compare finds every state equal`(): Unit = runBlocking {
        val config = config()
        run("import", "--config", config.toString(), "--snapshots").assert().describedAs(printed)
            .isEqualTo(MigrateData.OK)
        printed.assert().contains("example-service.order snapshots done: 1 instances, 1 V10 snapshots written")
            .contains("Snapshots done.")
        val id = NineXExample.snapshot("order-snapshot.json").aggregateId
        store.load(NineXExample.orderModel.type.ref(id.tenantId, id.id))!!.version.assert().isEqualTo(4)

        run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("example-service.order compared: 1 of 1 instances have a 9.x snapshot (100.0%)")
            .contains("Comparison done: every state compared equals its 9.x snapshot, every confirmed 9.x key")
        run("snapshot", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("0 V10 snapshots written, 1 already there")
    }

    @Test
    fun `import reserves the confirmed 9x keys, and compare checks them`(): Unit = runBlocking {
        val id = NineXExample.stream("order-stream-v1.json").aggregateId
        prepared.add(NineXPreparedKey("order_no", "NO-1", WowJson.mapper.readTree("""{"orderId":"${id.id}"}"""), null))
        run("import", "--config", config().toString()).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("  PrepareKey order_no: 1 keys")
            .contains("the 9.x PrepareKey order_no (1 keys) has no reservation mapping")

        val map = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.order\",",
            "\"to\": \"example.order\", \"reservations\": [ { \"from\": \"order_no\", \"namespace\": \"order.no\", " +
                "\"ownerId\": \"orderId\" } ],",
        )
        val config = config(map)
        run("import", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("PrepareKey order_no reservations done: 1 keys, 1 imported")
        val scope = ReservationScope.of(NineXExample.orderModel.type, id.tenantId, "order.no")
        val owner = NineXExample.orderModel.type.ref(id.tenantId, id.id)
        reservationStore.find(scope, "NO-1")!!.owner.assert().isEqualTo(owner)
        run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("PrepareKey order_no compared: 1 confirmed keys, 0 differ")

        reservationStore.release(scope, "NO-1", owner, ReservationStore.ANY_VERSION)
        run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains("DIFFERS PrepareKey order_no/NO-1: 9.x $owner, V10 (none)")
            .contains("1 confirmed keys of the PrepareKeys [order_no] are not reserved for their owners in V10")
        // Resumable: the import is complete, so it reserves again only from a restart.
        run("import", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        reservationStore.find(scope, "NO-1")!!.owner.assert().isEqualTo(owner)

        // 9.x rolls the key back: compare reports V10 still holding it, and the final restart releases it, from the
        // keys the import recorded beside the checkpoint file.
        prepared.remove("order_no", "NO-1")
        run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains("DIFFERS PrepareKey order_no/NO-1: V10 $owner, which 9.x no longer confirms it to")
            .contains("1 imported keys are no longer confirmed to their owners in 9.x")
        run("import", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        reservationStore.find(scope, "NO-1").assert().isNull()
        run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.OK)
    }

    @Test
    fun `compare reports a broken mapping and exits with its own code`() {
        val broken = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.cart\",",
            "\"to\": \"example.cart\", \"state\": { \"ignore\": [\"size\"], \"fields\": { \"items\": \"lines\" } },",
        )
        val config = config(broken)
        run("import", "--config", config.toString()).assert().isEqualTo(MigrateData.OK)
        run("compare", "--config", config.toString(), "--sample", "100").assert().describedAs(printed)
            .isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains("DIFFERS example-service.cart (0)/0VY3GwKq4OmV001 v4: items: 9.x (none), V10 [")
            .contains("lines: 9.x [")
            .contains("Comparison done: 1 instances of [example-service.cart] differ.")
        // A resumed comparison still reports what the first run found.
        run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.DIFFERS)
    }

    @Test
    fun `compare and snapshot need the import first`() {
        run("compare", "--config", config().toString()).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("FAILED The full import of example-service.order has not completed")
        run("snapshot", "--config", config().toString()).assert().isEqualTo(MigrateData.FAILED)
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
        run("compare", "--config", "x", "--sample", "0").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("compare", "--config", "x", "--sample", "all").assert().isEqualTo(MigrateData.USAGE_ERROR)
        printed.assert().contains("--sample takes a percentage from 1 to 100.")
        run("import", "--config", "x", "--dry-run", "--snapshots").assert().isEqualTo(MigrateData.USAGE_ERROR)
        printed.assert().contains("A dry run writes no snapshots")
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
    fun `backfill-tags records the tags of the imported streams, in its printed order`(): Unit = runBlocking {
        val config = config()
        run("import", "--config", config.toString()).assert().isEqualTo(MigrateData.OK)
        val order = NineXExample.orderModel.type
        log.untagged(order, null, 1).assert().isNotEmpty()
        run("backfill-tags", "--config", config.toString()).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains(MigrateData.TAGS_ORDER)
            .contains("example.order tags done")
            .contains("No Elasticsearch cluster is configured: nothing to re-sink.")
        log.untagged(order, null, 1).assert().isEmpty()
        FileCheckpointRepository(directory.resolve("migrate-data.checkpoint.json")).load().tagsOf(order)!!
            .completed.assert().isTrue()
        run("backfill-tags", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        run("backfill-tags", "--config", config.toString(), "--sample", "5").assert().isEqualTo(MigrateData.USAGE_ERROR)
    }

    @Test
    fun `backfill-tags fails while streams without tags remain, forced or not`(): Unit = runBlocking {
        val config = config()
        run("import", "--config", config.toString()).assert().isEqualTo(MigrateData.OK)
        // A log that records nothing: as if an old node appended each stream again meanwhile.
        val stubborn = object : EventLog by log, TagsBackfill by log {
            override suspend fun recordTags(ref: AggregateRef, tagsByVersion: Map<Int, Map<String, List<String>>>) = 0
        }
        val untagged = object : MigrationConnections by connections {
            override fun logOf(context: String): EventLog = stubborn
        }
        fun backfill(vararg options: String) =
            MigrateData(
                PrintStream(output, true),
                { untagged }
            ).run(listOf("backfill-tags", "--config", "$config") + options)
        backfill().assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("Streams without tags remain in [example.order")
        backfill("--restart", "--force").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("nothing to re-sink")
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
    fun `import parks the unresolved 9x executions, and compare checks them`(): Unit = runBlocking {
        nineXCompensation = InMemoryNineXCompensationSource(NineXCompensationExample.executions())
        Files.writeString(directory.resolve("migration-map.json"), NineXCompensationExample.mapText())
        Files.writeString(directory.resolve("manifest.json"), NineXCompensationExample.MANIFEST_TEXT)
        val config = directory.resolve("migrate-data.json")
        Files.writeString(
            config,
            """
            {
              "map": "migration-map.json",
              "manifests": ["manifest.json"],
              "source": { "uri": "mongodb://localhost:27017", "database": "example_db" },
              "target": { "type": "postgres", "jdbcUrl": "jdbc:postgresql://localhost:5432/wow" },
              "partitions": { "example": 4 },
              "compensation": { "database": "compensation_db", "service": "example-service" }
            }
            """.trimIndent(),
        )
        MigrationConfig.read(config).compensation.assert()
            .isEqualTo(CompensationConfig("compensation_db", "example-service"))
        run("import", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert()
            .contains("  9.x compensation: 5 unresolved executions of 4 functions, 2 resolved (history, not imported)")
            .contains(
                "9.x compensation done: 5 unresolved executions, 3 imported, 0 already parked, 2 not imported; 0 of " +
                    "other services left",
            )
        val ref = NineXExample.orderModel.type.ref(NineXCompensationExample.TENANT, NineXCompensationExample.ORDER_ID)
        ledger.parked(NineXCompensationExample.CART_SAGA, ref)!!.fromVersion.assert().isEqualTo(1)

        run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("9.x compensation compared: 3 unresolved executions, 0 differ, 2 not imported")
            .contains("and every unresolved 9.x execution imported is parked; nothing imported was released")
        ledger.resolve(NineXCompensationExample.CART_SAGA, ref)
        run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains("DIFFERS 9.x execution 0VYC0mp1nA0001")
            .contains("Comparison done: 1 unresolved 9.x executions are not parked in V10, and 0 imported entries")
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

            override fun close() = Unit
        }
        bare.compensation.assert().isNull()
        assertThrows<MigrationException> { bare.failureLedger() }
        assertThrows<MigrationException> { bare.nineXTarget }
    }

    @Test
    fun `export writes back what V10 wrote after the cut-over, and refuses a V10-only type`(): Unit = runBlocking {
        val config = config().toString()
        run("import", "--config", config).assert().isEqualTo(MigrateData.OK)
        val last = StreamConverter(NineXExample.plan).convert(NineXExample.stream("order-stream-v4.json"))
        log.append(
            last.copyWith(
                version = 5,
                commandId = "v10-receive",
                requestId = "v10-receive",
                events = listOf(EventRecord("example.order.OrderReceived", 1, "{}")),
            ),
        )
        run("export", "--config", config, "--dry-run").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Dry run done: 1 streams would be exported; nothing was written.")
        exportTarget.written.assert().isEmpty()

        run("export", "--config", config).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("example done: 1 streams exported to 9.x, 8 already there")
            .contains("EQUAL example-service.order <- example.order: V10 5 streams, 9.x 5 streams")
            .contains("Export done: 9.x holds every stream V10 holds; start 9.x again.")
        FileCheckpointRepository(directory.resolve("migrate-data.checkpoint.json")).load()
            .exportOf(NineXExample.CONTEXT)!!.exported.assert().isEqualTo(1)

        source.add(
            NineXExample.stream("cart-stream-v1.json").let {
                it.copy(id = "9x-only", aggregateId = it.aggregateId.copy(id = "9x-cart"))
            }
        )
        run("export", "--config", config).assert().isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains("DIFFERS example-service.cart").contains("hold different numbers of streams")

        log.append(
            last.copyWith(
                version = 6,
                commandId = "v10-v2",
                requestId = "v10-v2",
                events = listOf(EventRecord("example.order.OrderCreated", 2, "{}")),
            ),
        )
        run("export", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("REFUSED the V10 event example.order.OrderCreated@2").contains("Export refused")
        exportTarget.written.size.assert().isEqualTo(1)
        run("export", "--config", config, "--snapshots").assert().isEqualTo(MigrateData.USAGE_ERROR)
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
