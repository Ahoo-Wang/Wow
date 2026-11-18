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

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The migration with a sharded 9.x source and a sharded V10 target on one cluster, when `wow-test.mongo.url` names a
 * mongos (`test/mongo-cluster/cluster.sh up sharded`); skipped otherwise.
 *
 * The 9.x store is sharded as a 9.x deployment's own script usually shards it: each `<aggregate>_event_stream` by
 * `{aggregateId: "hashed"}`, each `<aggregate>_snapshot` by `{_id: "hashed"}`. That is the usual 9.x shape, not a fact
 * about any one project. The 9.x unique `(aggregateId, version)` index is built both ways a deployment meets it: by 9.x
 * at its start before the script shards the collection (the order), and after the script sharded it (the cart).
 *
 * The tool then runs as an operator runs it, through the mongos: `check`, `import`, an `import` again over a target
 * holding part of an instance's history, `compare`, `snapshot`, and the rollback `export` of what V10 wrote after. The
 * V10 collections the tool creates are sharded by the keys the MongoDB backend shards them by.
 */
class ShardedMigrationTest {
    @TempDir
    lateinit var directory: Path

    private val client = NineXMongoStore.client
    private val admin = client.getDatabase("admin")
    private val clusterConfig = client.getDatabase("config")
    private val output = ByteArrayOutputStream()

    @BeforeEach
    fun onSharded() {
        assumeTrue(NineXMongoStore.sharded, "Runs on a sharded cluster: -Dwow-test.mongo.url of a mongos.")
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    @Test
    @Suppress("LongMethod") // The tool's commands in the order an operator runs them, each one's output checked.
    fun `a sharded 9x store migrates into a sharded V10 target, and back`(): Unit = runBlocking {
        val nineX = client.getDatabase("ninex_sharded_${System.nanoTime()}")
        val target = "wow_v10_sharded_${System.nanoTime()}"
        // The order: 9.x creates its unique index at its start, then the script shards the collection.
        val orders = nineX.getCollection("order${MongoNineXEventSource.SUFFIX}")
        orders.createIndex(VERSIONS, IndexOptions().unique(true))
        shard(orders, Document("aggregateId", "hashed"))
        // The cart: the script shards the collection first, and 9.x creates its unique index after.
        val carts = nineX.getCollection("cart${MongoNineXEventSource.SUFFIX}")
        shard(carts, Document("aggregateId", "hashed"))
        carts.createIndex(VERSIONS, IndexOptions().unique(true))
        listOf("order", "cart").forEach {
            shard(nineX.getCollection("$it${MongoNineXSnapshotSource.SUFFIX}"), Document("_id", "hashed"))
        }
        listOf(orders, carts).forEach { collection ->
            collection.listIndexes().any {
                it.getBoolean("unique", false) && it.get("key", Document::class.java).keys.toList() == VERSIONS_KEY
            }.assert().describedAs("${collection.namespace}'s unique (aggregateId, version) index").isTrue()
        }
        // INSTANCES copies of each instance of the example; the first order lacks its last two streams for now.
        for (copy in 1..INSTANCES) {
            val streams = if (copy == 1) {
                NineXExample.ORDER_STREAMS.take(2) + NineXExample.CART_STREAMS
            } else {
                NineXExample.ORDER_STREAMS + NineXExample.CART_STREAMS
            }
            streams.forEach { NineXMongoStore.insert(nineX, copied(it, copy)) }
            NineXExample.SNAPSHOTS.forEach { NineXMongoStore.saveSnapshot(nineX, copied(it, copy)) }
        }
        listOf(orders, carts).forEach { collection ->
            shardsHolding(collection).assert().describedAs("${collection.namespace}").hasSizeGreaterThan(1)
        }

        val config = config(nineX, target).toString()
        run("check", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        run("import", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert()
            .contains("example-service.order done: $INSTANCES instances, ${INSTANCES * 4 - 2} streams imported")
            .contains("example-service.cart done: $INSTANCES instances, ${INSTANCES * 4} streams imported")

        // The 9.x history grows by the first order's last two streams; the import from the start again writes those.
        NineXExample.ORDER_STREAMS.drop(2).forEach { NineXMongoStore.insert(nineX, copied(it, 1)) }
        run("import", "--config", config, "--restart").assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        output.toString().assert().contains(
            "example-service.order done: $INSTANCES instances, 2 streams imported, ${INSTANCES * 4 - 2} skipped",
        )

        run("compare", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert().doesNotContain("DIFFERS").contains(
            "example-service.order compared: $INSTANCES of $INSTANCES instances have a 9.x snapshot (100.0%), " +
                "$INSTANCES compared, 0 differ",
        )
        run("snapshot", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert()
            .contains("example-service.order snapshots done: $INSTANCES instances, $INSTANCES V10 snapshots written")

        // The V10 collections the tool created are sharded as the MongoDB backend shards them.
        val logs = shardedIn(target, "wow_log__")
        logs.values.assert().isNotEmpty().containsOnly(Document("partition", 1))
        logs.keys.forEach { namespace ->
            chunksOf(namespace).map { it.getString("shard") }.distinct().assert().hasSizeGreaterThan(1)
        }
        val snapshots = shardedIn(target, "wow_snapshot__")
        snapshots.keys.map { it.substringAfterLast("__") }.assert().containsExactlyInAnyOrder("order", "cart")
        snapshots.values.assert().containsOnly(Document("tenant_id", 1).append("aggregate_id", "hashed"))

        // The rollback: what V10 wrote after the cut-over goes back to the sharded 9.x store.
        val converter = StreamConverter(NineXExample.plan) { NineXExample.IMPORTED_AT }
        val last = converter.convert(NineXStream.parse(copied("order-stream-v4.json", 1)))
        MongoEventLog(client, target, NineXExample.CONTEXT, PARTITIONS).append(
            last.copyWith(
                version = 5,
                commandId = "v10-pay",
                requestId = "v10-pay",
                commandType = "example.order.PayOrder",
                commitTime = last.commitTime + 1_000,
                events = listOf(EventRecord("example.order.OrderPaid", 1, """{"amount":12.50,"paid":true}""")),
            ),
        )
        run("export", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert()
            .contains("example done: 1 streams exported to 9.x, ${INSTANCES * 8} already there")
            .contains(
                "EQUAL example-service.order <- example.order: V10 ${INSTANCES * 4 + 1} streams, " +
                    "9.x ${INSTANCES * 4 + 1} streams",
            )
    }

    private fun config(nineX: MongoDatabase, target: String): Path {
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.MAP_TEXT)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongoUrl}", "database": "${nineX.name}" },
                  "target": { "type": "mongo", "uri": "${WowContainers.mongoUrl}", "database": "$target" },
                  "partitions": { "${NineXExample.CONTEXT}": $PARTITIONS },
                  "pageSize": 4,
                  "concurrency": 2
                }
                """.trimIndent(),
            )
        }
    }

    /** Shards [collection] by [key], as a 9.x deployment's script does. */
    private fun shard(collection: MongoCollection<Document>, key: Document) {
        admin.runCommand(Document("shardCollection", collection.namespace.fullName).append("key", key))
    }

    /** The shards that hold documents of [collection]. */
    private fun shardsHolding(collection: MongoCollection<Document>): List<String> =
        collection.aggregate(listOf(Document("\$collStats", Document("count", Document()))))
            .filter { (it["count"] as Number).toLong() > 0 }
            .map { it.getString("shard") }

    /** The sharded collections of [database] whose names start with [prefix], each with its shard key. */
    private fun shardedIn(database: String, prefix: String): Map<String, Document> =
        clusterConfig.getCollection("collections")
            .find(Document("_id", Document("\$regex", "^${Regex.escape("$database.$prefix")}")))
            .associate { it.getString("_id") to it.get("key", Document::class.java) }

    private fun chunksOf(namespace: String): List<Document> {
        val uuid = clusterConfig.getCollection("collections").find(Document("_id", namespace)).first()!!["uuid"]
        return clusterConfig.getCollection("chunks").find(Document("uuid", uuid)).toList()
    }

    /** The 9.x JSON of the example's resource [name] with each of its ids made the [copy]'s own. */
    private fun copied(name: String, copy: Int): String =
        ID.replace(NineXExample.resource(name)) { "\"${it.groupValues[1]}x$copy\"" }

    private companion object {
        const val PARTITIONS = 4
        const val INSTANCES = 20
        val VERSIONS_KEY = listOf("aggregateId", "version")
        val VERSIONS = Indexes.ascending(VERSIONS_KEY)
        val ID = Regex("\"(0VY3Gw[0-9A-Za-z]{9})\"")
    }
}
