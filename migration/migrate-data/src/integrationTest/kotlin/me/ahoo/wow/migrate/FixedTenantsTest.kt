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
 * A V10 type whose tenant `migrate-data.json` fixes, as the host's `wow.aggregates.[<context>.<aggregate>].tenant`
 * does (roadmap §6, from #6556 and #6570), in the 9.x example's MongoDB store, whose carts are in the tenant `(0)`:
 * `check` fails, naming each instance in another tenant, which V10 would serve to no caller.
 */
class FixedTenantsTest {
    @TempDir
    lateinit var directory: Path

    private val database = NineXMongoStore.example()

    private fun check(tenants: String): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val code = MigrateData(PrintStream(output, true)).run(listOf("check", "--config", config(tenants).toString()))
        return code to output.toString()
    }

    @Test
    fun `check fails, naming each cart in another tenant than the fixed one, and passes it in its own`() {
        val id = NineXExample.stream(NineXExample.CART_STREAMS.first()).aggregateId.id
        val (code, printed) = check("""{ "example.cart": "acme" }""")
        code.assert().describedAs(printed).isEqualTo(MigrateData.FAILED)
        printed.lines().single { it.startsWith("PROBLEM example-service.cart: the tenant of the V10 aggregate") }
            .assert()
            .contains("is fixed to \"acme\"")
            .contains("in other tenants: \"(0)\" (1 instances, 4 streams).")
            .endsWith(": \"$id\"")
        val (fixed, passed) = check("""{ "example.cart": "(0)" }""")
        fixed.assert().describedAs(passed).isEqualTo(MigrateData.OK)
        passed.assert().doesNotContain("the tenant of the V10 aggregate")
    }

    @Test
    fun `a fixed tenant of no type of the Manifests fails the command with the host's wording`() {
        val (code, printed) = check("""{ "example.carts": "acme" }""")
        code.assert().isEqualTo(MigrateData.FAILED)
        printed.lines().single { it.startsWith("FAILED") }.assert().startsWith(
            "FAILED migrate-data.json: tenants.example.carts names no aggregate type the Manifests declare. Name " +
                "one of the aggregate types the Manifests declare: [",
        )
    }

    @Test
    fun `the store counts the instances in each tenant, and finds those outside a tenant`() {
        val source = MongoNineXEventSource(database)
        val cart = NineXAggregate("example-service", "cart")
        val id = NineXExample.stream(NineXExample.CART_STREAMS.first()).aggregateId.id
        // A cart in the tenant acme, with two streams, and one that records no tenant, which the counts leave out.
        val stream = WowJson.mapper.readTree(NineXExample.resource(NineXExample.CART_STREAMS.first())) as ObjectNode
        stream.put("id", "stream-acme-1").put("aggregateId", "cart-a").put("tenantId", "acme")
        NineXMongoStore.insert(database, WowJson.mapper.writeValueAsString(stream))
        stream.put("id", "stream-acme-2").put("version", 2)
        NineXMongoStore.insert(database, WowJson.mapper.writeValueAsString(stream))
        stream.put("id", "stream-tenantless").put("aggregateId", "cart-t").put("version", 1).remove("tenantId")
        NineXMongoStore.insert(database, WowJson.mapper.writeValueAsString(stream))
        source.inventory().single { it.aggregate == cart }.instancesByTenant.assert()
            .isEqualTo(mapOf("(0)" to 1L, "acme" to 1L))
        source.instancesOutsideTenant(cart, "(0)").assert().isEqualTo(mapOf(NineXAggregateId("cart-a", "acme") to 2L))
        source.instancesOutsideTenant(cart, "acme").assert().isEqualTo(mapOf(NineXAggregateId(id, "(0)") to 4L))
        source.instancesOutsideTenant(NineXAggregate("example-service", "payment"), "acme").assert().isEmpty()
    }

    private fun config(tenants: String): Path {
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
                  "partitions": { "${NineXExample.CONTEXT}": 4 },
                  "tenants": $tenants
                }
                """.trimIndent(),
            )
        }
    }
}
