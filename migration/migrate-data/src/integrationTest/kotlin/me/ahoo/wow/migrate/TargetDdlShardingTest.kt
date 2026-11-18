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
import org.junit.jupiter.api.assertThrows
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
    fun `ddl validate refuses a target it would have to create, and imports into one that exists`() {
        val empty = "wow_v10_ddl_validate_${System.nanoTime()}"
        assertThrows<IllegalStateException> {
            run("import", "--config", config(empty, """{ "ddl": "validate" }"""))
        }.message.assert().contains("does not exist, and the store only validates its schema (ddl validate)")
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
        run("import", "--config", config(empty, """{ "ddl": "none" }""")).assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
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
