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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.migrate.example.OrderStatus
import me.ahoo.wow.migrate.example.OrderState
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Roadmap 8b.1's and 8b.2's exits: the 9.x example's data, in a 9.x MongoDB store, imported by the command line into
 * the V10 log on PostgreSQL and on MongoDB, then read back through V10, where every aggregate's state equals the 9.x
 * state; `compare` finds every 9.x snapshot equal (and reports a broken state mapping), `snapshot` writes the V10
 * snapshots once; then a stream 9.x commits after the import is followed by the tail.
 */
class NineXExampleImportTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX: MongoDatabase = NineXMongoStore.example()

    private fun config(target: String, createIndex: Boolean = false, map: String = NineXExample.MAP_TEXT): Path {
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "${nineX.name}",
                              "createCommitTimeIndex": $createIndex },
                  "target": $target,
                  "partitions": { "${NineXExample.CONTEXT}": $PARTITIONS },
                  "pageSize": 1,
                  "concurrency": 2
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    private fun migrate(target: String, log: EventLog, store: SnapshotStore) = runBlocking {
        val config = config(target)
        run("check", "--config", config.toString()).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        run("import", "--config", config.toString(), "--dry-run").assert().isEqualTo(MigrateData.OK)
        run("import", "--config", config.toString()).assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert().contains("example-service.order done: 1 instances, 4 streams imported")

        val kernel = Kernel(log)
        val order = NineXExample.stream("order-stream-v1.json").aggregateId
        val orderRef = NineXExample.orderModel.type.ref(order.tenantId, order.id)
        val orderState = kernel.stateAt(NineXExample.orderModel, orderRef, 4)!!
        NineXExample.sameJson(
            WowJson.mapper.valueToTree(orderState.state),
            WowJson.mapper.readTree(NineXExample.resource("order-state.json")),
        ).assert().describedAs(WowJson.mapper.writeValueAsString(orderState.state)).isTrue()
        val cart = NineXExample.stream("cart-stream-v1.json").aggregateId
        val cartRef = NineXExample.cartModel.type.ref(cart.tenantId, cart.id)
        NineXExample.sameJson(
            WowJson.mapper.valueToTree(kernel.stateAt(NineXExample.cartModel, cartRef, 4)!!.state),
            WowJson.mapper.readTree(NineXExample.resource("cart-state.json")),
        ).assert().isTrue()

        // Idempotent: a complete pass again writes nothing.
        run("import", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
        output.toString().assert().contains("example-service.order done: 1 instances, 0 streams imported, 4 skipped")

        // Roadmap 8b.2: every 9.x snapshot equals the V10 state rebuilt from the imported log, and the V10 snapshots
        // are written, once.
        run("compare", "--config", config.toString()).assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        output.toString().assert()
            .contains("example-service.order compared: 1 of 1 instances have a 9.x snapshot (100.0%), 1 compared, 0 differ")
            .contains("example-service.cart compared: 1 of 1 instances have a 9.x snapshot (100.0%), 1 compared, 0 differ")
            .doesNotContain("DIFFERS")
        run("snapshot", "--config", config.toString()).assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        output.toString().assert().contains("example-service.order snapshots done: 1 instances, 1 V10 snapshots written")
        val snapshot = store.load(orderRef)!!
        snapshot.version.assert().isEqualTo(4)
        NineXExample.sameJson(
            WowJson.mapper.readTree(snapshot.state),
            WowJson.mapper.readTree(NineXExample.resource("order-state.json")),
        ).assert().isTrue()
        kernel.stateAt(NineXExample.orderModel, orderRef, 4, snapshot)!!.version.assert().isEqualTo(4)
        run("import", "--config", config.toString(), "--restart", "--snapshots").assert().isEqualTo(MigrateData.OK)
        output.toString().assert().contains("example-service.order snapshots done: 1 instances, 0 V10 snapshots written, 1 already there")

        // A deliberately broken state mapping is reported, with the instance, its version and the fields.
        val broken = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.order\",",
            "\"to\": \"example.order\", \"state\": { \"fields\": { \"status\": \"orderStatus\" } },",
        )
        run("compare", "--config", config(target, map = broken).toString(), "--restart").assert()
            .isEqualTo(MigrateData.DIFFERS)
        output.toString().assert()
            .contains("DIFFERS example-service.order (0)/0VY3GwaK4OmV00E v4: orderStatus: 9.x \"SHIPPED\", V10 (none); status: 9.x (none), V10 \"SHIPPED\"")
            .contains("differing fields: orderStatus (1), status (1)")
        config(target)

        // The tail follows what 9.x commits after the import.
        val received = NineXMongoStore.received(NineXExample.resource("order-stream-v4.json"), System.currentTimeMillis())
        NineXMongoStore.insert(nineX, received)
        // The tool does not write to the 9.x store unless told to: without the tail's index, the tail refuses.
        run("tail", "--config", config.toString(), "--once").assert().isEqualTo(MigrateData.FAILED)
        output.toString().assert()
            .contains("db.getCollection(\"order_event_stream\").createIndex({ createTime: 1, _id: 1 })")
            .contains("\"createCommitTimeIndex\": true")
        MongoNineXEventSource(nineX).missingCommitTimeIndexes(NineXExample.plan.aggregates.keys).size
            .assert().isEqualTo(2)
        // Opted in, it creates the index and follows.
        run("tail", "--config", config(target, createIndex = true).toString(), "--once").assert()
            .describedAs(output.toString()).isEqualTo(MigrateData.OK)
        MongoNineXEventSource(nineX).missingCommitTimeIndexes(NineXExample.plan.aggregates.keys).assert().isEmpty()
        val receivedState = kernel.stateAt(NineXExample.orderModel, orderRef, 5)!!.state as OrderState
        receivedState.status.assert().isEqualTo(OrderStatus.RECEIVED)
        // With the index in place, the tail runs without the flag.
        run("tail", "--config", config(target).toString(), "--once").assert().isEqualTo(MigrateData.OK)
    }

    @Test
    fun `the 9x example's data imports into the V10 log on PostgreSQL`() {
        val postgres = WowContainers.postgres
        val target = """{ "type": "postgres", "jdbcUrl": "${postgres.jdbcUrl}", "username": "${postgres.username}", """ +
            """"password": "${postgres.password}" }"""
        val dataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        migrate(
            target,
            PostgresEventLog(dataSource, NineXExample.CONTEXT, PARTITIONS),
            PostgresSnapshotStore(dataSource, NineXExample.CONTEXT),
        )
    }

    @Test
    fun `the 9x example's data imports into the V10 log on MongoDB`() {
        val target =
            """{ "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$TARGET_DATABASE" }"""
        migrate(
            target,
            MongoEventLog(NineXMongoStore.client, TARGET_DATABASE, NineXExample.CONTEXT, PARTITIONS),
            MongoSnapshotStore(NineXMongoStore.client, TARGET_DATABASE, NineXExample.CONTEXT),
        )
    }

    private companion object {
        const val PARTITIONS = 4
        const val TARGET_DATABASE = "wow_v10_migrated"
    }
}
