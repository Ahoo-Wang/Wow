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
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXCompensationExample.CART_SAGA
import me.ahoo.wow.migrate.example.NineXCompensationExample.ORDER_CREATED
import me.ahoo.wow.migrate.example.NineXCompensationExample.ORDER_ID
import me.ahoo.wow.migrate.example.NineXCompensationExample.ORDER_STATE
import me.ahoo.wow.migrate.example.NineXCompensationExample.TENANT
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.mongo.ledger.MongoFailureLedger
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ParkedView
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap 8b.4 (decision D229 G-1): the 9.x compensation service's executions, as it stored them in its own MongoDB
 * database, imported by the command line into the V10 service's failure ledger, after the 9.x example's streams.
 *
 * Without subscription mappings the check fails; with them, the unresolved executions park the order for their
 * mapped subscriptions with their retry state, spec, recoverable mark and error, the succeeded and deleted ones are
 * left as history, and one on an order no stream holds or for a subscription of another context's topic is reported
 * and not imported. A run again changes nothing; `compare` finds every execution parked. The ops API then lists the
 * entries with their next retries, marks one recoverable and resumes one, which handles the order's streams from the
 * failed version; `compare` reports the resolved one.
 */
class NineXCompensationImportTest {
    @TempDir
    lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val nineX: MongoDatabase = NineXMongoStore.example()
    private val compensation: MongoDatabase = NineXMongoStore.compensation()
    private val service = "example-service-${services.incrementAndGet()}"
    private val ref = NineXExample.orderModel.type.ref(TENANT, ORDER_ID)

    private fun config(target: String, map: String = NineXCompensationExample.mapText(), uri: Boolean = false): Path {
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXCompensationExample.MANIFEST_TEXT)
        val compensationUri = if (uri) """, "uri": "${WowContainers.mongo.replicaSetUrl}"""" else ""
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
                  "pageSize": 2,
                  "compensation": { "database": "${compensation.name}", "service": "$service"$compensationUri }
                }
                """.trimIndent(),
            )
        }
    }

    private fun run(vararg args: String): Int = MigrateData(PrintStream(output, true)).run(args.toList())

    private val printed: String get() = output.toString()

    @Test
    fun `the unresolved 9x executions import into the failure ledger on MongoDB, and the ops API resumes them`(): Unit =
        runBlocking {
            val target = """{ "type": "mongo", "uri": "${WowContainers.mongo.replicaSetUrl}", "database": "$TARGET" }"""

            // Without subscription mappings, the failures of every 9.x processor function would be lost.
            run("import", "--config", config(target, NineXExample.MAP_TEXT).toString()).assert()
                .isEqualTo(MigrateData.FAILED)
            printed.assert()
                .contains("  9.x compensation: 5 unresolved executions of 4 functions, 2 resolved (history, not imported)")
                .contains(
                    "the 9.x processor function example-service.CartSaga.onOrderCreated (2 unresolved executions) " +
                        "has no subscription mapping: its failures would be lost",
                )
                .contains("the 9.x processor function example-service.OrderSaga.onEvent (1 unresolved executions)")
                .contains("the 9.x processor function example-service.OrderSaga.onStateEvent (1 unresolved executions)")
                .contains("the 9.x processor function example-service.OrderProjector.onOrderPaid (1 unresolved executions)")
                .doesNotContain("streams imported")

            val config = config(target)
            run("import", "--config", config.toString(), "--dry-run").assert().describedAs(printed)
                .isEqualTo(MigrateData.OK)
            printed.assert().contains(
                "9.x compensation done: 5 unresolved executions, 4 imported, 0 already parked, 1 not imported; 0 of " +
                    "other services left",
            )
            val ledger = MongoFailureLedger(NineXMongoStore.client, TARGET, service = service)
            ledger.list(CART_SAGA).assert().isEmpty()

            run("import", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
            printed.assert()
                .contains(
                    "9.x compensation done: 5 unresolved executions, 3 imported, 0 already parked, 2 not imported; 0 " +
                        "of other services left",
                )
                .contains(
                    "UNRESOLVED 9.x execution 0VYC0mp1nA0006 (example-service.OrderProjector.onOrderPaid on " +
                        "example-service.order/(0)/$ORDER_ID v3): the V10 subscription OrderProjector.onOrderPaid of " +
                        "reporting does not read the log of example; it is not imported",
                )
                .contains("UNRESOLVED 9.x execution 0VYC0mp1nA0007")
            ledger.parked(ORDER_CREATED, ref).assert().isEqualTo(
                ParkedAggregate.of(
                    ORDER_CREATED,
                    ref,
                    Partitioner.partitionOf(ref, PARTITIONS),
                    1,
                    "Conflict: stock reserved twice [9.x execution 0VYC0mp1nA0002]",
                    FAILED_AT + 181_000,
                    retries = 1,
                    retryAt = FAILED_AT + 180_000,
                    retrySpec = ParkedRetrySpec.of(5, 60.seconds, 30.seconds),
                    recoverable = Recoverable.RECOVERABLE,
                ),
            )
            ledger.parked(CART_SAGA, ref)!!.reason.assert().isEqualTo(
                "IllegalState: the order $ORDER_ID failed in CartSaga [9.x execution 0VYC0mp1nA0001]",
            )
            ledger.parked(ORDER_STATE, ref)!!.recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)
            // Only this service's ledger holds them.
            MongoFailureLedger(NineXMongoStore.client, TARGET, service = "another-service").list(CART_SAGA).assert().isEmpty()

            // A run again, from the first execution, finds every one parked and writes nothing.
            run("import", "--config", config.toString(), "--restart").assert().isEqualTo(MigrateData.OK)
            printed.assert().contains(
                "9.x compensation done: 5 unresolved executions, 0 imported, 3 already parked, 2 not imported",
            )
            run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
            printed.assert().contains("9.x compensation compared: 3 unresolved executions, 0 differ, 2 not imported")

            // The ops API lists the entries with their retry state, marks one recoverable and resumes one.
            val handled = CopyOnWriteArrayList<Pair<String, Int>>()
            val subscriptions = listOf(CART_SAGA, ORDER_CREATED, ORDER_STATE).map { name ->
                Subscription(name, SubscriptionFilter(NineXExample.CONTEXT, setOf("order")), StartFrom.END) {
                    handled += name to it.stream.version
                }
            }
            val runtime = SubscriptionRuntime(
                NineXExample.CONTEXT,
                MongoEventLog(NineXMongoStore.client, TARGET, NineXExample.CONTEXT, PARTITIONS),
                InMemoryCheckpointStore(),
                ledger,
                subscriptions,
                SubscriptionOptions(parked = ParkedRetryOptions(scanInterval = 1.hours)),
            )
            val ops = ParkedOps.of(listOf(runtime))
            val page = ops.list()
            page.entries.map { it.entry.subscription }.assert().containsExactly(CART_SAGA, ORDER_CREATED, ORDER_STATE)
            page.entries.map { it.status }.assert()
                .containsExactly(ParkedView.Status.SCHEDULED, ParkedView.Status.SCHEDULED, ParkedView.Status.UNRECOVERABLE)
            // 9.x's next retry for the first; the second's by its applied spec, after its retry started.
            page.entries.map { it.nextRetryAt }.assert()
                .containsExactly(FAILED_AT + 180_000, FAILED_AT + 180_000 + 120_000, null)
            ops.markRecoverable(SubscriptionId(NineXExample.CONTEXT, ORDER_STATE), ref, Recoverable.RECOVERABLE)!!.status.assert()
                .isEqualTo(ParkedView.Status.SCHEDULED)
            runtime.start((0 until PARTITIONS).toSet())
            try {
                ops.resume(SubscriptionId(NineXExample.CONTEXT, CART_SAGA), ref).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
            } finally {
                runtime.stop()
            }
            handled.assert().containsExactly(CART_SAGA to 1, CART_SAGA to 2, CART_SAGA to 3, CART_SAGA to 4)
            ops.list().entries.map { it.entry.subscription }.assert().containsExactly(ORDER_CREATED, ORDER_STATE)

            // The resumed execution is resolved in V10 now: compare reports it.
            run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.DIFFERS)
            printed.assert().contains(
                "DIFFERS 9.x execution 0VYC0mp1nA0001 (example-service.CartSaga.onOrderCreated on " +
                    "example-service.order/(0)/$ORDER_ID v1): 9.x $CART_SAGA v1, V10 (none)",
            )
        }

    @Test
    fun `the unresolved 9x executions import into the failure ledger on PostgreSQL`(): Unit = runBlocking {
        val postgres = WowContainers.postgres
        val target = """{ "type": "postgres", "jdbcUrl": "${postgres.jdbcUrl}", "username": "${postgres.username}", """ +
            """"password": "${postgres.password}" }"""
        val config = config(target, uri = true)
        run("import", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("9.x compensation done: 5 unresolved executions, 3 imported, 0 already parked")
        val dataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        val ledger = PostgresFailureLedger(dataSource, service = service)
        ledger.parked(ORDER_STATE, ref)!!.also {
            it.fromVersion.assert().isEqualTo(3)
            it.retries.assert().isEqualTo(1)
            it.recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)
            it.retrySpec.assert().isEqualTo(ParkedRetrySpec.of(10, 180.seconds, 120.seconds))
        }
        run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("9.x compensation compared: 3 unresolved executions, 0 differ, 2 not imported")

        // 9.x retries execution 2 successfully during the window (finding R2.4-1): compare reports the entry the
        // import parked for it, and the final restart resolves it.
        compensation.getCollection(MongoNineXCompensationSource.COLLECTION)
            .updateOne(Filters.eq("_id", "0VYC0mp1nA0002"), Updates.set("state.status", "SUCCEEDED"))
        run("compare", "--config", config.toString()).assert().isEqualTo(MigrateData.DIFFERS)
        printed.assert().contains(
            "DIFFERS V10 $ORDER_CREATED $ref parked from v1: the 9.x execution 0VYC0mp1nA0002 (example-service." +
                "OrderSaga.onEvent on example-service.order/(0)/$ORDER_ID v1) it was imported for is succeeded in 9.x",
        )
        run("import", "--config", config.toString(), "--restart").assert().describedAs(printed).isEqualTo(MigrateData.OK)
        printed.assert().contains("RESOLVED $ORDER_CREATED $ref from v1")
        ledger.parked(ORDER_CREATED, ref).assert().isNull()
        ledger.parked(CART_SAGA, ref).assert().isNotNull()
        run("compare", "--config", config.toString()).assert().describedAs(printed).isEqualTo(MigrateData.OK)
    }

    private companion object {
        const val TARGET = "wow_v10_compensation"
        const val PARTITIONS = 4

        /** When the example's executions failed. */
        const val FAILED_AT = 1_792_004_190_000L
        val services = AtomicInteger()
    }
}
