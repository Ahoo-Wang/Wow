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

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.tck.containers.WowContainers
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finding A4-8 (decision D227 ③): the 9.x `PrepareKey`s of a 9.x MongoDB store, as 9.x's `MongoPrepareKey` writes
 * them, imported by the command line into the V10 reservation store on MongoDB: each confirmed key reserved for the
 * instance its value names, in that instance's tenant (or across tenants for a `SYSTEM` mapping), pending and expired
 * keys skipped; a `PrepareKey` without a mapping fails the check; a run again changes nothing; `compare` finds every
 * key reserved.
 */
class NineXReservationImportTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX: MongoDatabase = NineXMongoStore.example()
    private val first = NineXExample.stream("order-stream-v1.json")
    private val owner = NineXExample.orderModel.type.ref(first.aggregateId.tenantId, first.aggregateId.id)
    private val ownerB = NineXExample.orderModel.type.ref(TENANT_B, "order-b")

    private fun config(map: String): Path {
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "${nineX.name}" },
                  "target": { "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$TARGET" },
                  "partitions": { "${NineXExample.CONTEXT}": 4 },
                  "pageSize": 2
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    private fun scope(tenantId: String, namespace: String = "order.no") =
        ReservationScope.of(NineXExample.CONTEXT, tenantId, namespace)

    @Test
    fun `the confirmed 9x PrepareKey keys import as V10 reservations of their owners`(): Unit = runBlocking {
        // A second order, in another tenant: its key's reservation takes that tenant.
        NineXMongoStore.insert(
            nineX,
            NineXExample.resource("order-stream-v1.json")
                .replace(first.id, "order-b-stream")
                .replace(first.aggregateId.id, "order-b")
                .replace("\"tenantId\":\"${first.aggregateId.tenantId}\"", "\"tenantId\":\"$TENANT_B\""),
        )
        val now = System.currentTimeMillis()
        val forever = NineXPreparedKey.TTL_FOREVER
        NineXMongoStore.prepare(nineX, "order_no", "NO-1", Document("orderId", first.aggregateId.id), forever)
        NineXMongoStore.prepare(nineX, "order_no", "NO-2", Document("orderId", "order-b"), forever)
        NineXMongoStore.prepare(nineX, "order_no", "NO-3", Document("orderId", "order-b"), now - HOUR)
        NineXMongoStore.prepare(nineX, "order_no", "NO-4", Document("orderId", "order-b"), now + HOUR)
        NineXMongoStore.prepare(nineX, "order_code", "C-1", first.aggregateId.id, forever)

        // A PrepareKey without a mapping: its keys would be free in V10, so the import does not start.
        run("import", "--config", config(NineXExample.MAP_TEXT).toString()).assert().isEqualTo(MigrateData.FAILED)
        output.toString().assert()
            .contains("  PrepareKey order_no: 4 keys")
            .contains("the 9.x PrepareKey order_code (1 keys) has no reservation mapping")
            .contains("the 9.x PrepareKey order_no (4 keys) has no reservation mapping")
            .doesNotContain("streams imported")

        val map = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.order\",",
            """
            "to": "example.order",
            "reservations": [
              { "from": "order_no", "namespace": "order.no", "ownerId": "orderId" },
              { "from": "order_code", "namespace": "order.code", "tenancy": "SYSTEM" }
            ],
            """.trimIndent(),
        )
        val config = config(map)
        run("import", "--config", config.toString()).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert()
            .contains(
                "PrepareKey order_no reservations done: 4 keys, 2 imported, 0 already there, 1 pending and 1 " +
                    "expired skipped, 0 without an owner",
            )
            .contains("PrepareKey order_code reservations done: 1 keys, 1 imported")
        val store = MongoReservationStore(NineXMongoStore.client, TARGET)
        store.find(scope(first.aggregateId.tenantId), "NO-1")!!.also {
            it.owner.assert().isEqualTo(owner)
            it.confirmed.assert().isTrue()
        }
        store.find(scope(TENANT_B), "NO-2")!!.owner.assert().isEqualTo(ownerB)
        store.find(scope(TENANT_B), "NO-3").assert().isNull()
        store.find(scope(TENANT_B), "NO-4").assert().isNull()
        store.find(scope(ReservationScope.SYSTEM_TENANT, "order.code"), "C-1")!!.owner.assert().isEqualTo(owner)

        // A run again, from the first key, finds every key reserved and writes nothing.
        run("import", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        output.toString().assert().contains(
            "PrepareKey order_no reservations done: 4 keys, 0 imported, 2 already there, 1 pending and 1 expired " +
                "skipped, 0 without an owner",
        )

        run("compare", "--config", config.toString()).assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        output.toString().assert()
            .contains("PrepareKey order_no compared: 2 confirmed keys, 0 differ, 0 without an owner")
            .contains("PrepareKey order_code compared: 1 confirmed keys, 0 differ, 0 without an owner")
        store.release(scope(TENANT_B), "NO-2", ownerB, ReservationStore.ANY_VERSION).assert().isTrue()
        run("compare", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.DIFFERS)
        output.toString().assert().contains("DIFFERS PrepareKey order_no/NO-2: 9.x $ownerB, V10 (none)")

        // During the window 9.x rolls NO-1 back and reprepares C-1 for order-b (finding R2.4-2): compare finds V10
        // still holding them for the owners the import gave them, and the final restart releases NO-1 and moves C-1
        // instead of stopping on it.
        nineX.getCollection("prepare_order_no").deleteOne(Filters.eq("_id", "NO-1"))
        nineX.getCollection("prepare_order_code").updateOne(Filters.eq("_id", "C-1"), Updates.set("value", "order-b"))
        run("compare", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.DIFFERS)
        output.toString().assert()
            .contains("DIFFERS PrepareKey order_no/NO-1: V10 $owner, which 9.x no longer confirms it to")
            .contains("DIFFERS PrepareKey order_code/C-1: V10 $owner, which 9.x no longer confirms it to")
        run("import", "--config", config.toString(), "--restart").assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        output.toString().assert()
            .contains("RELEASED PrepareKey order_no/NO-1 of $owner")
            .contains("RELEASED PrepareKey order_code/C-1 of $owner")
        store.find(scope(first.aggregateId.tenantId), "NO-1").assert().isNull()
        store.find(scope(TENANT_B), "NO-2")!!.owner.assert().isEqualTo(ownerB)
        store.find(scope(ReservationScope.SYSTEM_TENANT, "order.code"), "C-1")!!.owner.assert().isEqualTo(ownerB)
        run("compare", "--config", config.toString(), "--restart").assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
    }

    private companion object {
        const val TARGET = "wow_v10_reserved"
        const val TENANT_B = "tenant-b"
        const val HOUR = 3_600_000L
    }
}
