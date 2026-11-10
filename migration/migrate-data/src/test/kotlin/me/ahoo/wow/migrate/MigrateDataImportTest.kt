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
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.subscription.ImportCutover
import org.junit.jupiter.api.Test
import java.nio.file.Files

internal class MigrateDataImportTest : MigrateDataFixtures() {
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
}
