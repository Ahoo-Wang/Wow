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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerResolution
import me.ahoo.wow.example.eshop.e2e.ops.OpsTestServer
import me.ahoo.wow.ops.api.parked.ParkedStatus
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The failure ledger across services end to end (work package 6.3f, decision D141): the ordering, inventory and
 * payment services each in a JVM of its own on PostgreSQL and Kafka, and a minimal ops service ([OpsTestServer]) in
 * this JVM on the memory backend, all over one Kafka.
 *
 * 1. The payment gateway is down (its outage file exists): the payment's gateway saga fails, retries and parks the
 *    payment; its automatic retries are off, so it waits for an operator.
 * 2. The payment service publishes its ledger (`wow.ops.publish.enabled`) to `wow.ops.commands`: the ops view, the
 *    `parked` aggregate whose id is the entry's, shows it parked.
 * 3. The gateway is back; an operator resumes the entry on the ops service. Its saga sends `wow.ledger.Resume` to
 *    `wow.eshop-payment.commands`, the payment service resumes the saga, the payment succeeds and pays the order, and
 *    the entry, published resolved, shows `RESOLVED` with the outcome `RESUMED` in the ops view.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EshopLedgerOpsTest {
    private val kafka = ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0").also { it.start() }
    private val postgres =
        PostgreSQLContainer("postgres:17-alpine").withDatabaseName(ORDERING_DATABASE).also { it.start() }
    private val outage = File.createTempFile("eshop-gateway-", ".down").apply { deleteOnExit() }

    init {
        listOf(INVENTORY_DATABASE, PAYMENT_DATABASE).forEach { database ->
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

    private val ops: ConfigurableApplicationContext = SpringApplicationBuilder(OpsTestServer::class.java).run(
        "--spring.main.banner-mode=off",
        "--spring.application.name=ops-service",
        "--server.address=127.0.0.1",
        "--server.port=0",
        "--wow.service=ops-service",
        "--wow.backend=memory",
        "--wow.internal.host=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.contexts.include=ops",
        "--wow.log.partitions=$OPS_PARTITIONS",
        "--wow.kafka.bootstrap-servers=${kafka.bootstrapServers}",
        "--wow.kafka.command-intake=true",
        "--wow.subscription.checkpoint-interval=100ms",
    )
    private val opsPort = ops.environment.getRequiredProperty("local.server.port", Int::class.java)
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

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
        listOf(payment, ordering, inventory).forEach { it.close() }
        ops.close()
        http.shutdownNow()
        postgres.stop()
        kafka.stop()
        outage.delete()
    }

    private fun arguments(database: String): List<String> = backendArguments(postgres, kafka, database, PARTITIONS)

    @Test
    fun `a payment the gateway failed is parked, shown in ops, and resumed from there`() {
        inventory.post("/eshop-inventory/stock/mug/receive-stock", """{"quantity":10}""").status.assert().isEqualTo(OK)
        val submitted = ordering.post(
            "/eshop-ordering/order/carol-1/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":2}]}""",
        )
        submitted.status.assert().describedAs(submitted.body).isEqualTo(OK)
        val tenant = json.readTree(submitted.body)["ref"]["tenantId"].asString()
        val parked = AggregateType("eshop-payment", "payment").ref(tenant, "carol-1")
        val id = Ledger.parkedId("eshop-payment-service", GATEWAY_SAGA, parked)

        // The ops view shows the entry the payment service parked and published.
        eventually("the parked payment in the ops view") { opsState(id) != null }
        val entry = opsState(id)!!
        entry["service"].asString().assert().isEqualTo("eshop-payment-service")
        entry["context"].asString().assert().isEqualTo("eshop-payment")
        entry["subscription"].asString().assert().isEqualTo(GATEWAY_SAGA)
        entry["aggregate"]["id"].asString().assert().isEqualTo("carol-1")
        entry["reason"].asString().assert().contains("unreachable")
        entry["status"].asString().assert().isEqualTo(ParkedStatus.EXHAUSTED)
        orderStatus("carol-1").assert().isEqualTo("STOCK_CONFIRMED")

        // The gateway is back: an operator resumes the entry from ops, and the owning service resumes it.
        outage.delete()
        val resumed = opsPost("/ops/parked/$id/resume", """{"reason":"The gateway is back."}""")
        resumed.status.assert().describedAs(resumed.body).isEqualTo(OK)
        eventually("the entry resolved in the ops view") { opsState(id)?.get("status")?.asString() == ParkedStatus.RESOLVED }
        opsState(id)!!["outcome"].asString().assert().isEqualTo(LedgerResolution.RESUMED)
        eventually("carol-1 paid") { orderStatus("carol-1") == "PAID" }
        val paid = payment.get("/eshop-payment/payment/carol-1/state")
        json.readTree(paid.body)["state"]["status"].asString().assert().isEqualTo("SUCCEEDED")
    }

    /** The ops view of the entry [id], or null while ops has none. */
    private fun opsState(id: String): JsonNode? {
        val answer = send(HttpRequest.newBuilder(opsUri("/ops/parked/$id/state")).GET())
        if (answer.status == NOT_FOUND) return null
        check(answer.status == OK) { "GET the ops view of $id answered ${answer.status}: ${answer.body}" }
        return json.readTree(answer.body)["state"]
    }

    private fun opsPost(path: String, body: String): Answer = send(
        HttpRequest.newBuilder(opsUri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)),
    )

    private fun opsUri(path: String): URI = URI.create("http://127.0.0.1:$opsPort$path")

    private fun send(request: HttpRequest.Builder): Answer {
        val response = http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
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
                        "\n--- ordering ---\n${ordering.output().takeLast(TAIL)}",
                )
            }
            Thread.sleep(POLL.toMillis())
        }
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val TAIL = 6_000
        const val ORDERING_MAIN = "me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt"
        const val INVENTORY_MAIN = "me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt"
        const val PAYMENT_MAIN = "me.ahoo.wow.example.eshop.payment.server.PaymentServerKt"
        const val GATEWAY_SAGA = "PaymentGatewaySaga.onPaymentRequested"
        const val PARTITIONS = 4
        const val OPS_PARTITIONS = 2
        const val ORDERING_DATABASE = "eshop_ordering"
        const val INVENTORY_DATABASE = "eshop_inventory"
        const val PAYMENT_DATABASE = "eshop_payment"
        val DEADLINE: Duration = Duration.ofSeconds(90)
        val POLL: Duration = Duration.ofMillis(200)
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
