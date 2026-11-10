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
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.ImportCutover
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

@Suppress("LargeClass") // One fixture of the tool's connections for every command it runs.
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
    private val checkpointStore = InMemoryCheckpointStore()
    private var nineXCompensation: NineXCompensationSource? = null
    private val ledger = InMemoryFailureLedger()
    private val exportTarget = InMemoryNineXEventTarget(source)
    private val checkpointTable = InMemoryCheckpointTable()
    private val leaseClock = MovableClock(System.currentTimeMillis())
    private val leaseStore = InMemoryLeaseStore(leaseClock)

    private val connections = object : MigrationConnections {
        override val source: NineXEventSource get() = nineX

        override val snapshots: NineXSnapshotSource get() = nineXSnapshots

        override fun logOf(context: String): EventLog = log

        override fun snapshotStoreOf(context: String): SnapshotStore = store

        override val reservations: NineXReservationSource get() = prepared

        override fun reservationStore(): ReservationStore = reservationStore

        override fun checkpointStore(): CheckpointStore = checkpointStore

        override val compensation: NineXCompensationSource? get() = nineXCompensation

        override fun failureLedger(): FailureLedger = ledger

        override val nineXTarget: NineXEventTarget get() = exportTarget

        override fun checkpointTable(): CheckpointTable = checkpointTable

        override fun leaseStore(): LeaseStore = leaseStore

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

    private fun config(
        map: String = NineXExample.MAP_TEXT,
        createIndex: Boolean = false,
        checkpoint: String = "migrate-data.checkpoint.json",
        manifests: String = "\"manifests\": [\"manifest.json\"],",
    ): Path {
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  $manifests
                  "checkpoint": "$checkpoint",
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
    fun `draft-map drafts a map check accepts, and never replaces one without --force`() {
        val config = config().toString()
        val map = directory.resolve("migration-map.json")
        run("draft-map", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("$map exists: draft-map writes a new map, so pass --force to replace it, or --out.")
        Files.delete(map)

        run("draft-map", "--config", config, "--dry-run").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("\"from\" : \"example-service.order\"").contains("Nothing was written.")
        Files.exists(map).assert().isFalse()

        run("draft-map", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("UNSOURCED example.order").contains("Draft written to $map: 2 aggregates")
        val draft = MigrationMap.read(Files.readString(map))
        draft.aggregates.map { it.to }.assert().containsExactly("example.cart", "example.order")
        draft.exceptions.assert().isNotEmpty()
        run("check", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("WARNING the draft's exception, for a person to resolve and delete: UNSOURCED")

        run("draft-map", "--config", config, "--force").assert().isEqualTo(MigrateData.OK)
        val elsewhere = directory.resolve("draft.json")
        run("draft-map", "--config", config, "--out", elsewhere.toString()).assert().isEqualTo(MigrateData.OK)
        MigrationMap.read(Files.readString(elsewhere)).assert().isEqualTo(draft)
        run("draft-map", "--config", config, "--out").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("draft-map", "--config", config, "--restart").assert().isEqualTo(MigrateData.USAGE_ERROR)
    }

    @Test
    fun `check reports and writes nothing`() {
        run("check", "--config", config().toString()).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Mapping check passed.").doesNotContain("(createTime, _id)")
        closed.assert().isTrue()
        Files.exists(directory.resolve("migrate-data.checkpoint.json")).assert().isFalse()
    }

    @Test
    fun `draft-map and check should fail on a store without an event stream, naming the store`() {
        // What a misnamed database reads as: a store holding nothing.
        nineX = InMemoryNineXEventSource()
        val path = config().toString()
        run("draft-map", "--config", path, "--dry-run").assert().isEqualTo(MigrateData.FAILED)
        run("check", "--config", path).assert().isEqualTo(MigrateData.FAILED)
        printed.lines().filter { it.startsWith("FAILED") }.assert().containsOnly(
            "FAILED the in-memory store holds no 9.x event stream: check source.database, and the variable an env: " +
                "value names.",
        ).hasSize(2)
    }

    @Test
    fun `import checks, imports and checkpoints, and the tail follows`(): Unit = runBlocking {
        val config = config()
        run("import", "--config", config.toString()).assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("example-service.order done").contains("Import done.")
            .doesNotContain("were already in the log")
        val checkpoints = FileCheckpointRepository(directory.resolve("migrate-data.checkpoint.json")).load()
        checkpoints.of(NineXAggregate("example-service", "cart"))!!.completed.assert().isTrue()
        run("tail", "--config", config.toString(), "--once").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("Tail stopped.")
        run("tail", "--config", config.toString(), running = { ++passes < 2 }).assert().isEqualTo(MigrateData.OK)
        passes.assert().isEqualTo(2)
        run("import", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("4 skipped").contains(
            "8 streams of [example-service.order, example-service.cart] were already in the log, and " +
                "were passed over as they were converted then: a change of the map since reaches them only in a " +
                "fresh target.",
        )
    }

    @Test
    fun `import should read nothing of an aggregate the map excludes`(): Unit = runBlocking {
        val cart = NineXAggregate("example-service", "cart")
        val map = NineXExample.map.let { map ->
            map.copy(
                aggregates = map.aggregates.filterNot { it.source() == cart },
                excluded = listOf(Exclusion(aggregate = cart.toString(), reason = "Another V10 store takes it")),
            )
        }
        run("import", "--config", config(WowJson.mapper.writeValueAsString(map)).toString())
            .assert().isEqualTo(MigrateData.OK)
        printed.assert().contains("WARNING the 9.x aggregate example-service.cart (")
            .contains("streams) is excluded: Another V10 store takes it").contains("Import done.")
        val streams = NineXExample.streams()
        val carts = streams.filter { it.aggregate == cart }.map { it.aggregateId }.distinct()
        carts.assert().isNotEmpty()
        carts.forEach { log.read(NineXExample.cartModel.type.ref(it.tenantId, it.id)).toList().assert().isEmpty() }
        streams.first { it.aggregate != cart }.aggregateId.let {
            log.read(NineXExample.orderModel.type.ref(it.tenantId, it.id)).toList().assert().isNotEmpty()
        }
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
        // The snapshots stamped the history, the stream the final pass imported too, and the walk is recorded once.
        log.read(ref).toList().map { it.accessVersion }.assert().containsOnly(5)
        printed.lines().count { it.startsWith("${NineXExample.orderModel.type}: its access walk is recorded") }
            .assert().isEqualTo(1)
        log.walk(NineXExample.orderModel.type)!!.completed.assert().isTrue()
        run("import", "--config", config, "--final", "--restart").assert().isEqualTo(MigrateData.USAGE_ERROR)
        run("import", "--config", config, "--final", "--dry-run").assert().isEqualTo(MigrateData.USAGE_ERROR)
    }

    @Test
    fun `a final pass that refuses writes no snapshot, even with --snapshots`(): Unit = runBlocking {
        val config = config().toString()
        run("import", "--config", config).assert().isEqualTo(MigrateData.OK)
        // The tail never ran: the final pass refuses before it writes, and --snapshots writes nothing after it.
        run("import", "--config", config, "--final", "--snapshots").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("FAILED the tail of example-service.order never ran")
            .doesNotContain("Snapshots done.")
        val id = NineXExample.stream("order-stream-v1.json").aggregateId
        store.load(NineXExample.orderModel.type.ref(id.tenantId, id.id)).assert().isNull()
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
        // The import's check warns of each collection without the index, and creates none.
        printed.lines().filter { it.startsWith("WARNING the 9.x collection") }.map { it.substringBefore(" has") }
            .assert().containsExactly(
                "WARNING the 9.x collection order_event_stream",
                "WARNING the 9.x collection cart_event_stream",
            )
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
        cutover().assert().isEmpty()
    }

    /** The cut-over the import recorded in the target's checkpoint store, by partition. */
    private suspend fun cutover(): Map<Int, Long> =
        checkpointStore.list(ImportCutover.groupOf("example")).associate { it.partition to it.position }

    /** The log's head of each partition that holds an entry. */
    private suspend fun heads(): Map<Int, Long> =
        (0 until log.partitions).mapNotNull { partition -> log.head(partition)?.let { partition to it } }.toMap()

    @Test
    fun `each pass that appends records where the imported history ends, moving it with the log's heads`(): Unit =
        runBlocking {
            val config = config().toString()
            run("import", "--config", config).assert().isEqualTo(MigrateData.OK)
            val imported = heads()
            imported.assert().isNotEmpty()
            cutover().assert().isEqualTo(imported)
            printed.assert().contains("CUTOVER example: the imported history ends at the log's head in")
            run("tail", "--config", config, "--once").assert().isEqualTo(MigrateData.OK)
            // 9.x commits once more before it stops: the final pass appends it, and the cut-over follows.
            val last = NineXExample.stream("order-stream-v4.json")
            source.add(
                last.copy(
                    id = "late",
                    version = 5,
                    commandId = "late",
                    requestId = "late",
                    createTime = System.currentTimeMillis(),
                ),
            )
            run("import", "--config", config, "--final").assert().describedAs(printed).isEqualTo(MigrateData.OK)
            heads().assert().isNotEqualTo(imported)
            cutover().assert().isEqualTo(heads())
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
        printed.assert().contains("Comparing in the time zone ${ZoneId.systemDefault()}: run compare in the one 9.x")
            .contains("example-service.order compared: 1 of 1 instances have a 9.x snapshot (100.0%)")
            .contains("example-service.order imported streams not checked: the tail never followed it")
            .contains(
                "Comparison done: every state compared equals its 9.x snapshot, the log holds every stream 9.x " +
                    "committed since the import up to where the tail has read, every confirmed 9.x key",
            )
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
    fun `check --decode reads every 9x event with the V10 models, and lists each that does not read`() {
        val config = config().toString()
        run("check", "--config", config, "--decode").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("example-service.order decoded: 4 streams, 4 events, every one read")
            .contains("Decode check passed: the V10 models read every 9.x event.")
        NineXExample.cartRemoving("cart-null", "[null]").forEach(source::add)
        run("check", "--config", config, "--decode").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains("PROBLEM decode: the 9.x event cart_item_removed@0.0.1 of 9.x stream ")
            .contains(
                "Decode check failed: 1 9.x events of 1 types do not read, and 0 streams do not convert. Give each " +
                    "type an @Upgrade that cleans its old shape.",
            )
        // Nothing is decoded on a map the check fails.
        val payment = NineXExample.stream("cart-stream-v1.json").let {
            it.copy(id = "payment-1", aggregate = NineXAggregate("example-service", "payment"))
        }
        source.add(payment)
        run("check", "--config", config, "--decode").assert().isEqualTo(MigrateData.FAILED)
        printed.assert().endsWith("Decode check not run: the mapping check failed.${System.lineSeparator()}")
    }

    @Test
    fun `snapshot should print every instance whose snapshot it cannot write, then fail`() {
        NineXExample.cartRemoving("cart-null", "[null]").forEach(source::add)
        val config = config().toString()
        run("import", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        run("snapshot", "--config", config).assert().isEqualTo(MigrateData.FAILED)
        printed.assert().contains(
            "FAILED example.cart/(0)/cart-null, the 9.x instance example-service.cart/(0)/cart-null: "
        )
            .contains(
                "FAILED 1 instances of [example-service.cart] have no V10 snapshot, each printed above: fix them, then " +
                    "run snapshot again, which walks every instance of those aggregates again, or reads again what " +
                    "the log changed when it was catching up, and passes over the snapshots written.",
            )
            .doesNotContain("Snapshots done.")
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
        ledger.parked(
            SubscriptionId(NineXExample.CONTEXT, NineXCompensationExample.CART_SAGA),
            ref
        )!!.fromVersion.assert().isEqualTo(1)

        run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("9.x compensation compared: 3 unresolved executions, 0 differ, 2 not imported")
            .contains("and every unresolved 9.x execution imported is parked; nothing imported was released")
        ledger.resolveAnywhere(SubscriptionId(NineXExample.CONTEXT, NineXCompensationExample.CART_SAGA), ref)
        run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains("DIFFERS 9.x execution 0VYC0mp1nA0001")
            .contains("Comparison done: 1 unresolved 9.x executions are not parked in V10, and 0 imported entries")
    }

    @Test
    fun `compare reports differing states without blaming the compensation when every execution is parked`(): Unit =
        runBlocking {
            nineXCompensation = InMemoryNineXCompensationSource(NineXCompensationExample.executions())
            val broken = NineXCompensationExample.mapText().replace(
                "\"to\": \"example.cart\",",
                "\"to\": \"example.cart\", \"state\": { \"ignore\": [\"size\"], \"fields\": { \"items\": \"lines\" } },",
            )
            Files.writeString(directory.resolve("migration-map.json"), broken)
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
            run("import", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
            run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.DIFFERS)
            printed.assert().contains("9.x compensation compared: 3 unresolved executions, 0 differ, 2 not imported")
                .contains("Comparison done: 1 instances of [example-service.cart] differ.")
                .doesNotContain("unresolved 9.x executions are not parked in V10")
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
    fun `a dry-run export reads the checkpoints kept in the target, and takes no lease`(): Unit = runBlocking {
        val config = config(checkpoint = "target:example").toString()
        run("import", "--config", config).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        val before = checkpointTable.load("example")!!
        run("export", "--config", config, "--dry-run").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("Dry run done")
        checkpointTable.load("example").assert().isEqualTo(before)
        // The export role was never taken: its first acquisition is at epoch 1.
        leaseStore.acquire(
            "${TargetCheckpointLeases.GROUP_PREFIX}example",
            CheckpointRole.EXPORT.partition,
            "probe",
            TargetCheckpointLeases.TTL
        )!!
            .epoch.assert().isEqualTo(1)
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
