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

package me.ahoo.wow.example.eshop.e2e

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import me.ahoo.wow.ops.api.parked.ParkedStatus
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Duration

/**
 * The failure ledger across services end to end (work packages 6.3f and 7.1a, decision D141): the ordering,
 * inventory and payment services and the ops service (`:ops-server`) each in a JVM of its own, on PostgreSQL and
 * Kafka, as a deployment runs them.
 *
 * 1. The payment gateway is down (its outage file exists): the payment's gateway saga fails, retries and parks the
 *    payment; its automatic retries are off, so it waits for an operator.
 * 2. The payment service publishes its ledger (`wow.ops.publish.enabled`) to `wow.ops.commands`: the ops service
 *    records it on the `parked` aggregate whose id is the entry's, and an operator finds it through the snapshot query
 *    route, filtered by context, status and failure time, and reads its detail.
 * 3. The gateway is back; an operator resumes the entry on the ops service, which authorizes it as `wow.ledger.Resume`.
 *    Its saga sends `wow.ledger.Resume` to `wow.eshop-payment.commands`, the payment service resumes the saga, the
 *    payment succeeds and pays the order, and the entry, published resolved, shows `RESOLVED` with the outcome
 *    `RESUMED` in the ops view.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EshopLedgerOpsTest {
    private val kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0").also { it.start() }
    private val postgres =
        PostgreSQLContainer("postgres:17-alpine").withDatabaseName(ORDERING_DATABASE).also { it.start() }
    private val outage = File.createTempFile("eshop-gateway-", ".down").apply { deleteOnExit() }

    init {
        listOf(INVENTORY_DATABASE, PAYMENT_DATABASE, OPS_DATABASE).forEach { database ->
            postgres.execInContainer("psql", "-U", postgres.username, "-d", ORDERING_DATABASE, "-c", "CREATE DATABASE $database")
                .exitCode.assert().isEqualTo(0)
        }
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
            admin.createTopics(
                listOf(
                    NewTopic("wow.eshop-ordering.commands", PARTITIONS, 1),
                    NewTopic("wow.eshop-inventory.commands", PARTITIONS, 1),
                    NewTopic("wow.eshop-payment.commands", PARTITIONS, 1),
                    NewTopic("wow.eshop-ordering.receipts", 1, 1),
                    NewTopic("wow.eshop-payment.receipts", 1, 1),
                    NewTopic("wow.eshop-ordering.order.state", 2, 1),
                    NewTopic("wow.eshop-inventory.stock.state", 2, 1),
                    NewTopic("wow.eshop-payment.payment.state", 2, 1),
                    NewTopic("wow.ops.commands", OPS_PARTITIONS, 1),
                    NewTopic("wow.ops.parked.state", OPS_PARTITIONS, 1),
                ),
            ).all().get()
        }
    }

    // The ops service as it is deployed: its own application.yaml, on PostgreSQL, its intake on wow.ops.commands.
    private val ops = EshopService.start(
        "ops",
        OPS_MAIN,
        // The test's operators name themselves in headers, which the service trusts only when told to (review A3-4).
        backendArguments(postgres, kafka, OPS_DATABASE, OPS_PARTITIONS) +
            "--wow.platform.trust-identity-headers=true",
        readyHeaders = VIEWER,
    )
    private val inventory = EshopService.start("inventory", INVENTORY_MAIN, arguments(INVENTORY_DATABASE))
    private val ordering = EshopService.start("ordering", ORDERING_MAIN, arguments(ORDERING_DATABASE))
    private val payment = EshopService.start(
        "payment",
        PAYMENT_MAIN,
        arguments(PAYMENT_DATABASE) + listOf(
            "--eshop.payment.gateway.outage-file=${outage.path}",
            // The payment context takes the ledger's commands from its command topic, and publishes its ledger.
            "--wow.kafka.command-intake=true",
            "--wow.ops.publish.enabled=true",
            "--wow.ops.publish.interval=500ms",
            // A failing charge parks after two attempts, and waits for an operator.
            "--wow.subscription.retry.max-attempts=2",
            "--wow.subscriptions.[$GATEWAY_SAGA].parked.max-retries=0",
        ),
    )

    @AfterAll
    fun close() {
        listOf(payment, ordering, inventory, ops).forEach { it.close() }
        postgres.stop()
        kafka.stop()
        outage.delete()
    }

    private fun arguments(database: String): List<String> = backendArguments(postgres, kafka, database, PARTITIONS)

    @Test
    fun `a payment the gateway failed is parked, shown in ops, and resumed from there`() {
        val startedAt = System.currentTimeMillis()
        inventory.post("/eshop-inventory/stock/mug/receive-stock", """{"quantity":10}""").status.assert().isEqualTo(OK)
        val submitted = ordering.post(
            "/eshop-ordering/order/carol-1/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
        )
        submitted.status.assert().describedAs(submitted.body).isEqualTo(OK)
        val tenant = json.readTree(submitted.body)["ref"]["tenantId"].asString()
        val parked = EshopPaymentContract.Payment.ref(tenant, "carol-1")
        val id = Ledger.parkedId("eshop-payment-service", GATEWAY_SAGA, parked)

        // The ops service lists the entry the payment service parked and published, as an operator queries it.
        val exhausted = """{"op":"AND","operands":[
            {"op":"EQ","field":"state.context","value":"eshop-payment"},
            {"op":"EQ","field":"state.status","value":"${ParkedStatus.EXHAUSTED}"},
            {"op":"GTE","field":"state.failedAt","value":$startedAt}]}"""
        eventually("the parked payment listed by ops") { listed(exhausted) == listOf(id) }
        val entry = opsState(id)!!
        entry["service"].asString().assert().isEqualTo("eshop-payment-service")
        entry["subscription"].asString().assert().isEqualTo(GATEWAY_SAGA)
        entry["aggregate"]["id"].asString().assert().isEqualTo("carol-1")
        entry["reason"].asString().assert().contains("unreachable")
        orderStatus("carol-1").assert().isEqualTo("STOCK_CONFIRMED")

        // Only an operator acts on it.
        ops.post("/ops/parked/$id/resume", "{}", VIEWER).status.assert().isEqualTo(FORBIDDEN)

        // The gateway is back: an operator resumes the entry from ops, and the owning service resumes it.
        outage.delete()
        val resumed = ops.post("/ops/parked/$id/resume", """{"reason":"The gateway is back."}""", OPERATOR)
        resumed.status.assert().describedAs(resumed.body).isEqualTo(OK)
        eventually("the entry resolved in the ops view") { opsState(id)?.get("status")?.asString() == ParkedStatus.RESOLVED }
        opsState(id)!!["outcome"].asString().assert().isEqualTo("RESUMED")
        listed(exhausted).assert().isEmpty()
        eventually("carol-1 paid") { orderStatus("carol-1") == "PAID" }
        val paid = payment.get("/eshop-payment/payment/carol-1/state")
        json.readTree(paid.body)["state"]["status"].asString().assert().isEqualTo("SUCCEEDED")
    }

    /** The ids of the entries [filter] selects, as an operator lists them through the ops service's query route. */
    private fun listed(filter: String): List<String> {
        val answer = ops.post("/ops/parked/snapshot/query", """{"kind":"list","filter":$filter}""", OPERATOR)
        check(answer.status == OK) { "The ops query answered ${answer.status}: ${answer.body}" }
        return json.readTree(answer.body).values().map { it["aggregateId"].asString() }
    }

    /** The ops view of the entry [id], or null while ops has none. */
    private fun opsState(id: String): JsonNode? {
        val answer = ops.get("/ops/parked/$id/state", OPERATOR)
        if (answer.status == NOT_FOUND) return null
        check(answer.status == OK) { "GET the ops view of $id answered ${answer.status}: ${answer.body}" }
        return json.readTree(answer.body)["state"]
    }

    private fun orderStatus(id: String): String? {
        val answer = ordering.get("/eshop-ordering/order/$id/state")
        if (answer.status == NOT_FOUND) return null
        return json.readTree(answer.body)["state"]["status"].asString()
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError(
                    "Not reached in $DEADLINE: $what.\n--- payment ---\n${payment.output().takeLast(TAIL)}" +
                        "\n--- ops ---\n${ops.output().takeLast(TAIL)}",
                )
            }
            Thread.sleep(POLL.toMillis())
        }
    }

    private companion object {
        const val OK = 200
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val TAIL = 6_000
        const val ORDERING_MAIN = "me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt"
        const val INVENTORY_MAIN = "me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt"
        const val PAYMENT_MAIN = "me.ahoo.wow.example.eshop.payment.server.PaymentServerKt"
        const val OPS_MAIN = "me.ahoo.wow.ops.server.OpsServerKt"
        const val GATEWAY_SAGA = "PaymentGatewaySaga.onPaymentRequested"
        const val PARTITIONS = 4
        const val OPS_PARTITIONS = 2
        const val ORDERING_DATABASE = "eshop_ordering"
        const val INVENTORY_DATABASE = "eshop_inventory"
        const val PAYMENT_DATABASE = "eshop_payment"
        const val OPS_DATABASE = "wow_ops"

        /** The ops service's callers, as its header resolver reads them (a deployment's are CoSec's). */
        val OPERATOR = mapOf("X-User" to "olivia", "X-Role" to "ops.operator")
        val VIEWER = mapOf("X-User" to "victor")
        val DEADLINE: Duration = Duration.ofSeconds(90)
        val POLL: Duration = Duration.ofMillis(200)
    }
}
