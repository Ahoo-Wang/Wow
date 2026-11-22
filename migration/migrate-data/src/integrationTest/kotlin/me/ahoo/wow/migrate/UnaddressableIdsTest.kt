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

import com.mongodb.ExplainVerbosity
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The 9.x aggregate ids V10's admission refuses ([me.ahoo.wow.kernel.AggregateIds.refused]), in the 9.x
 * example's MongoDB store: `check` lists every instance of one, and fails, as no command could reach it after the
 * migration.
 */
class UnaddressableIdsTest {
    @TempDir
    lateinit var directory: Path

    private val order = NineXAggregate("example-service", "order")
    private val database = NineXMongoStore.example()
    private val source = MongoNineXEventSource(database)

    /** One id of each kind admission refuses. */
    private val refused = listOf(
        "a\u0000b", "a\u0085b", " o-1", "o-1\t", "o-1\u3000", ".", "..", "o".repeat(LONGEST + 1),
        // #6594: what a gateway's path check refuses, so no route by its id reaches it.
        "o/1", "o;1", "o\\1", "购".repeat(171), "😀".repeat(128) + "x",
    )

    /** Ids near them that admission takes: the store's pattern matches the last four too; the rule takes them. */
    private val taken = listOf("a.b", "a b", "购物", "a%b", "...", "o-1.", "o".repeat(LONGEST), "购".repeat(170))

    private fun insert(ids: List<String>) {
        ids.forEachIndexed { index, id ->
            val stream = WowJson.mapper.readTree(NineXExample.resource("order-stream-v1.json")) as ObjectNode
            stream.put("id", "stream-${ids.hashCode()}-$index")
            stream.put("aggregateId", id)
            NineXMongoStore.insert(database, WowJson.mapper.writeValueAsString(stream))
        }
    }

    @Test
    fun `the inventory lists every instance whose id admission refuses, and none it takes`() {
        insert(refused + taken)
        source.inventory().single { it.aggregate == order }.refusedIds.assert()
            .isEqualTo(refused.associate { NineXAggregateId(it, "(0)") to 1L })
    }

    @Test
    fun `check fails, naming each instance with its id shown safely, the reason and the count`() {
        insert(refused)
        val output = ByteArrayOutputStream()
        val code = MigrateData(PrintStream(output, true)).run(listOf("check", "--config", config().toString()))
        val printed = output.toString()
        code.assert().describedAs(printed).isEqualTo(MigrateData.FAILED)
        val problem = printed.lines().single { it.startsWith("PROBLEM example-service.order: V10 refuses") }
        problem.assert()
            .contains("the aggregate ids of these ${refused.size} 9.x instances, so no command could reach them")
            .contains("\"a\\u0000b\" (The aggregate id holds a control character")
            .contains("\"a\\u0085b\" (The aggregate id holds a control character")
            .contains("\" o-1\" (The aggregate id ' o-1' begins or ends with white space")
            .contains("\"o-1\\t\" (The aggregate id 'o-1\\u0009' begins or ends with white space")
            .contains("\"o-1\\u3000\" (The aggregate id 'o-1\\u3000' begins or ends with white space")
            .contains("\"..\" (The aggregate id '..' is a relative path segment")
            .contains("(The aggregate id is at most $LONGEST characters; this one has ${LONGEST + 1}: 1 streams")
            .contains("\"o/1\" (The aggregate id 'o/1' holds '/', which a gateway's path check refuses")
            .contains("\"o;1\" (The aggregate id 'o;1' holds ';'")
            .contains("\"o\\\\1\" (The aggregate id 'o\\1' holds '\\'")
            .contains("(The aggregate id has 171 characters, 513 bytes in UTF-8, more than the 512 bytes a route")
        problem.none { it.isISOControl() || it.isWhitespace() && it != ' ' }.assert().isTrue()
        printed.assert().contains("Mapping check failed: 1 problems.")
    }

    /**
     * What the check reads for it: 9.x's `(aggregateId, version)` index, whose keys the pattern is matched against,
     * and only the streams the pattern matches. Printed for the pull request's measurement.
     */
    @Test
    fun `the store reads the index for the ids, and fetches only the candidates`() {
        insert(refused + taken)
        val collection = database.getCollection("order${MongoNineXEventSource.SUFFIX}")
        val fetched = MongoNineXInventory.REFUSED_CANDIDATES.sumOf { candidates ->
            val explained = collection.find(candidates).explain(ExplainVerbosity.EXECUTION_STATS)
            val plan = explained.get("queryPlanner", Document::class.java).toJson()
            val stats = explained.get("executionStats", Document::class.java)
            println(
                "REFUSED_CANDIDATES on ${collection.countDocuments()} streams: keysExamined " +
                    "${stats["totalKeysExamined"]}, docsExamined ${stats["totalDocsExamined"]}, returned " +
                    "${stats["nReturned"]}, ${stats["executionTimeMillis"]} ms, plan $plan",
            )
            plan.assert().contains("\"IXSCAN\"").doesNotContain("\"COLLSCAN\"")
            (stats["totalDocsExamined"] as Number).toInt().also { examined ->
                examined.assert().isEqualTo((stats["nReturned"] as Number).toInt())
            }
        }
        // The refused ids and the four near ones the pattern matches: no other stream is fetched.
        fetched.assert().isEqualTo(refused.size + 4)
    }

    private fun config(): Path {
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.MAP_TEXT)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongoUrl}", "database": "${database.name}" },
                  "target": { "type": "postgres", "jdbcUrl": "jdbc:postgresql://unused/v10" },
                  "partitions": { "${NineXExample.CONTEXT}": 4 }
                }
                """.trimIndent(),
            )
        }
    }

    private companion object {
        /** The longest aggregate id admission takes (`AdmissionLimits.MAX_ID_LENGTH`, internal to the runtime). */
        const val LONGEST = 256
    }
}
