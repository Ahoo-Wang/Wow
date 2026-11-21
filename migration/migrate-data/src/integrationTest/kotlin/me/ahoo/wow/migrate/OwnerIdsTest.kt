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
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A 9.x aggregate mapped to one whose id is its owner's in V10 (issue #6560), in the 9.x example's MongoDB store, whose
 * cart streams record the owner `customer-1`, not their ids: `check` lists each such instance and fails, as its owner
 * could not reach it after the migration.
 */
class OwnerIdsTest {
    @TempDir
    lateinit var directory: Path

    private val database = NineXMongoStore.example()

    @Test
    fun `check fails, naming each cart whose streams record another owner than its id`() {
        val id = NineXExample.stream(NineXExample.CART_STREAMS.first()).aggregateId.id
        val output = ByteArrayOutputStream()
        val code = MigrateData(PrintStream(output, true)).run(listOf("check", "--config", config().toString()))
        val printed = output.toString()
        code.assert().describedAs(printed).isEqualTo(MigrateData.FAILED)
        printed.lines().single { it.startsWith("PROBLEM example-service.cart: the id of the V10 aggregate") }.assert()
            .contains("record another owner than their id, or none, so their owners could not reach them")
            .endsWith("\"$id\" (the owner \"customer-1\": 4 streams of the tenants [(0)])")
        printed.assert().contains("Mapping check failed: 1 problems.")
    }

    @Test
    fun `the store finds the streams whose owner is not their id, and none of another aggregate`() {
        val source = MongoNineXEventSource(database)
        val id = NineXExample.stream(NineXExample.CART_STREAMS.first()).aggregateId.id
        // A stream that records no tenant and no owner, and one whose owner is its id, which the store passes over.
        val stream = WowJson.mapper.readTree(NineXExample.resource(NineXExample.CART_STREAMS.first())) as ObjectNode
        stream.put("id", "stream-tenantless").put("aggregateId", "cart-t").remove(listOf("tenantId", "ownerId"))
        NineXMongoStore.insert(database, WowJson.mapper.writeValueAsString(stream))
        stream.put("id", "stream-owned").put("aggregateId", "cart-o").put("ownerId", "cart-o")
        NineXMongoStore.insert(database, WowJson.mapper.writeValueAsString(stream))
        source.ownersOtherThanIds(NineXAggregate("example-service", "cart")).assert().isEqualTo(
            mapOf(
                NineXInstanceOwner(NineXAggregateId(id, "(0)"), "customer-1") to 4L,
                NineXInstanceOwner(NineXAggregateId("cart-t", ""), "") to 1L,
            ),
        )
        source.ownersOtherThanIds(NineXAggregate("example-service", "payment")).assert().isEmpty()
    }

    private fun config(): Path {
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.MAP_TEXT)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.manifestText(NineXExample.ownedCartManifest))
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
}
