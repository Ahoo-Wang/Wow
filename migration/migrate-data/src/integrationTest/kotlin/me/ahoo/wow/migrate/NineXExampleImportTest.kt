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
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Roadmap 8b.1's exit: the 9.x example's data, in a 9.x MongoDB event store, imported by the command line into the V10
 * log on PostgreSQL and on MongoDB, then read back through V10, where every aggregate's state equals the 9.x state;
 * then a stream 9.x commits after the import is followed by the tail.
 */
class NineXExampleImportTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX: MongoDatabase = NineXMongoStore.example()

    private fun config(target: String): Path {
        Files.writeString(directory.resolve("migration-map.json"), NineXExample.MAP_TEXT)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  "manifests": ["manifest.json"],
                  "source": { "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "${nineX.name}" },
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

    private fun migrate(config: Path, log: EventLog) = runBlocking {
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

        // The tail follows what 9.x commits after the import.
        val received = NineXMongoStore.received(NineXExample.resource("order-stream-v4.json"), System.currentTimeMillis())
        NineXMongoStore.insert(nineX, received)
        run("tail", "--config", config.toString(), "--once").assert().describedAs(output.toString())
            .isEqualTo(MigrateData.OK)
        val receivedState = kernel.stateAt(NineXExample.orderModel, orderRef, 5)!!.state as OrderState
        receivedState.status.assert().isEqualTo(OrderStatus.RECEIVED)
    }

    @Test
    fun `the 9x example's data imports into the V10 log on PostgreSQL`() {
        val postgres = WowContainers.postgres
        val config = config(
            """{ "type": "postgres", "jdbcUrl": "${postgres.jdbcUrl}", "username": "${postgres.username}", """ +
                """"password": "${postgres.password}" }""",
        )
        val dataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        migrate(config, PostgresEventLog(dataSource, NineXExample.CONTEXT, PARTITIONS))
    }

    @Test
    fun `the 9x example's data imports into the V10 log on MongoDB`() {
        val config = config(
            """{ "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$TARGET_DATABASE" }""",
        )
        migrate(config, MongoEventLog(NineXMongoStore.client, TARGET_DATABASE, NineXExample.CONTEXT, PARTITIONS))
    }

    private companion object {
        const val PARTITIONS = 4
        const val TARGET_DATABASE = "wow_v10_migrated"
    }
}
