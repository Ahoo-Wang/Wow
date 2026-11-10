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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.ZoneId

internal class MigrateDataCompareTest : MigrateDataFixtures() {
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
}
