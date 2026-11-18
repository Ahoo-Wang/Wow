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
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A MongoDB target's `ddl` and `sharding`, the choices of the nodes' MongoDB configuration. The `ddl` cases run on any
 * MongoDB; the `sharding` ones when `wow-test.mongo.url` names a mongos (`test/mongo-cluster/cluster.sh up sharded`),
 * and are skipped otherwise.
 */
class TargetDdlShardingTest {
    @TempDir
    lateinit var directory: Path

    private val client = NineXMongoStore.client
    private val collections = client.getDatabase("config").getCollection("collections")
    private val nineX = NineXMongoStore.example()
    private val output = ByteArrayOutputStream()

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    /** The `FAILED` line the run printed. */
    private fun failed(): String = output.toString().lines().single { it.startsWith("FAILED") }

    @Test
    fun `sharding auto shards the collections the import creates, and none leaves them unsharded`() {
        assumeTrue(NineXMongoStore.sharded, "Runs on a sharded cluster: -Dwow-test.mongo.url of a mongos.")
        val sharded = "wow_v10_sharding_auto_${System.nanoTime()}"
        run("import", "--config", config(sharded, "{}")).assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        shardKeys(sharded).filterKeys { it.contains(".wow_log__") }.values.assert().isNotEmpty()
            .containsOnly(Document("partition", 1))

        val unsharded = "wow_v10_sharding_none_${System.nanoTime()}"
        val config = config(unsharded, """{ "sharding": "none" }""")
        run("import", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        run("snapshot", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        val created = client.getDatabase(unsharded).listCollectionNames().toList()
        created.filter { it.startsWith("wow_log__") || it.startsWith("wow_snapshot__") }.assert().isNotEmpty()
        shardKeys(unsharded).assert().isEmpty()
    }

    @Test
    fun `sharding none leaves it to the operator, who shards what the import created by the backend's keys`() {
        assumeTrue(NineXMongoStore.sharded, "Runs on a sharded cluster: -Dwow-test.mongo.url of a mongos.")
        val database = "wow_v10_by_hand_${System.nanoTime()}"
        val config = config(database, """{ "sharding": "none" }""")
        run("import", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        run("snapshot", "--config", config).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        // The operator shards the populated collections by the backend's keys, each on an index of its own: the log's
        // unique keys are partial, and no partial index serves a shard key.
        val logs = Document("partition", 1)
        val snapshots = Document("tenant_id", 1).append("aggregate_id", "hashed")
        val byHand = listOf("order", "cart").flatMap {
            listOf("$database.wow_log__example__$it" to logs, "$database.wow_snapshot__example__$it" to snapshots)
        }.toMap()
        val logIndex = Document("partition", 1).append("_id", 1)
        byHand.forEach { (namespace, key) -> shard(namespace, key, if (key == logs) logIndex else key) }
        output.reset()
        run("import", "--config", config, "--restart").assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert().contains("example-service.order done: 1 instances, 0 streams imported, 4 skipped")
        run("snapshot", "--config", config, "--restart").assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        shardKeys(database).assert().isEqualTo(byHand)
    }

    @Test
    fun `a collection made before Wow touched it, or sharded by another key after, is refused by name`() {
        assumeTrue(NineXMongoStore.sharded, "Runs on a sharded cluster: -Dwow-test.mongo.url of a mongos.")
        // Sharded by hand before the import: the collection exists, but Wow did not record its layout.
        val early = "wow_v10_early_${System.nanoTime()}"
        shard("$early.wow_log__example__order", Document("partition", 1))
        run("import", "--config", config(early, """{ "sharding": "none" }""")).assert().isEqualTo(MigrateData.FAILED)
        failed().assert().startsWith("FAILED wow_log__example__order exists without a format record")

        // Created by the tool, then sharded by another key: the run that shards it refuses it. A snapshot collection,
        // as MongoDB itself refuses a log collection any other key: the log's unique keys lead with the partition.
        val other = "wow_v10_other_key_${System.nanoTime()}"
        val unsharded = config(other, """{ "sharding": "none" }""")
        run("import", "--config", unsharded).assert().isEqualTo(MigrateData.OK)
        run("snapshot", "--config", unsharded).assert().isEqualTo(MigrateData.OK)
        val namespace = "$other.wow_snapshot__example__order"
        shard(namespace, Document("aggregate_id", "hashed"))
        output.reset()
        run("snapshot", "--config", config(other, "{}"), "--restart").assert().isEqualTo(MigrateData.FAILED)
        failed().assert().startsWith("FAILED $namespace cannot be sharded by ")
            .contains("the key this version of Wow shards it by")
        // The refused collection keeps the operator's key.
        shardKeys(other)[namespace].assert().isEqualTo(Document("aggregate_id", "hashed"))
    }

    @Test
    fun `ddl validate refuses a target it would have to create, and imports into one that exists`() {
        val empty = "wow_v10_ddl_validate_${System.nanoTime()}"
        run("import", "--config", config(empty, """{ "ddl": "validate" }""")).assert().isEqualTo(MigrateData.FAILED)
        failed().assert().startsWith("FAILED wow_log__example__partition does not exist")
            .contains("the store only validates its schema (ddl validate)")
        client.getDatabase(empty).listCollectionNames().toList().assert().isEmpty()

        // With the checkpoints kept in the empty target, the tool's own collections are refused first, by name.
        output.reset()
        run("import", "--config", config(empty, """{ "ddl": "validate" }""", inTarget = true))
            .assert().isEqualTo(MigrateData.FAILED)
        failed().assert().isEqualTo(
            "FAILED The target lacks the checkpoint collections, and ddl validate only checks them: create " +
                "wow_migrate_checkpoint, and wow_migrate_imported_key with the index " +
                "wow_migrate_imported_key_by_name on { checkpoint: 1, prepare_key: 1, _id: 1 }.",
        )
        client.getDatabase(empty).listCollectionNames().toList().assert().isEmpty()

        // The checkpoints kept in the target too: the first run creates their collections, the second only uses them.
        val existing = "wow_v10_ddl_validated_${System.nanoTime()}"
        run("import", "--config", config(existing, """{ "sharding": "none" }""", inTarget = true))
            .assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.reset()
        run("import", "--config", config(existing, """{ "ddl": "validate" }""", inTarget = true), "--restart")
            .assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert().contains("example-service.order done: 1 instances, 0 streams imported, 4 skipped")
        shardKeys(existing).assert().isEmpty()
    }

    @Test
    fun `ddl none creates nothing, so the import's writes alone make bare collections`() {
        val empty = "wow_v10_ddl_none_${System.nanoTime()}"
        // The checkpoints kept in the target too: under none their collections are not made ready either.
        run("import", "--config", config(empty, """{ "ddl": "none" }""", inTarget = true))
            .assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        // No format record, no index but the _id one, no shard key: the schema is the operator's.
        val database = client.getDatabase(empty)
        val names = database.listCollectionNames().toList()
        names.assert().isNotEmpty().doesNotContain("wow_format")
        names.forEach { name ->
            database.getCollection(name).listIndexes().map { it.getString("name") }.toList().assert()
                .describedAs(name).containsExactly("_id_")
        }
        shardKeys(empty).assert().isEmpty()
    }

    /**
     * The configuration of an import of the 9.x example into [database], its target given the fields of [target], its
     * checkpoints kept in the target when [inTarget], else in a file.
     */
    private fun config(database: String, target: String, inTarget: Boolean = false): String {
        val checkpoint = if (inTarget) "target:ddl" else "$database.checkpoint.json"
        val path = directory.resolve("$database.json")
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.MAP_TEXT)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        val fields = WowJsonFields.of(target)
        Files.writeString(
            path,
            """
            {
              "map": "migration-map.json",
              "manifests": ["manifest.json"],
              "checkpoint": "$checkpoint",
              "source": { "uri": "${WowContainers.mongoUrl}", "database": "${nineX.name}" },
              "target": { "type": "mongo", "uri": "${WowContainers.mongoUrl}", "database": "$database"$fields },
              "partitions": { "${NineXExample.CONTEXT}": 4 }
            }
            """.trimIndent(),
        )
        return path.toString()
    }

    /** Shards [namespace] by [key] on the index [index] it creates first, as an operator does by hand. */
    private fun shard(namespace: String, key: Document, index: Document = key) {
        val (database, collection) = namespace.split('.', limit = 2)
        client.getDatabase(database).getCollection(collection).createIndex(index)
        client.getDatabase("admin").runCommand(Document("shardCollection", namespace).append("key", key))
    }

    /** The sharded collections of [database], each with its shard key. */
    private fun shardKeys(database: String): Map<String, Document> =
        collections.find(Document("_id", Document("\$regex", "^${Regex.escape("$database.")}")))
            .associate { it.getString("_id") to it.get("key", Document::class.java) }

    /** The fields of the JSON object [text], to append to another object's: empty for `{}`. */
    private object WowJsonFields {
        fun of(text: String): String = text.trim().removePrefix("{").removeSuffix("}").trim()
            .let { if (it.isEmpty()) "" else ", $it" }
    }
}
