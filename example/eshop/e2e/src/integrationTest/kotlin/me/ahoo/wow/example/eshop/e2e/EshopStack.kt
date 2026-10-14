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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.spi.log.Partitioner
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import java.sql.DriverManager
import java.time.Duration

/**
 * The whole eShop (work package E.6): the catalog, basket, ordering, inventory and payment services, each in a JVM of
 * its own as its `application.yaml` configures it, each on a PostgreSQL database of its own, the four that talk to
 * each other over Kafka, and the ordering's order stream written to ClickHouse by the Sink (its `clickhouse` profile)
 * for the sales dataset. The ordering runs on [orderingNodes] nodes sharing its database, each named `ordering-<n>`.
 *
 * The test suite and the load script ([EshopLoad]) both start it.
 */
class EshopStack(
    orderingNodes: Int = 1,
    private val partitions: Int = PARTITIONS,
    paymentLimit: String = "1000.00",
) : AutoCloseable {
    val kafka: ConfluentKafkaContainer = ConfluentKafkaContainer(KAFKA_IMAGE).also { it.start() }
    val postgres: PostgreSQLContainer =
        PostgreSQLContainer(POSTGRES_IMAGE).withDatabaseName(CATALOG_DATABASE).also { it.start() }
    val clickhouse: ClickHouseContainer = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).also { it.start() }

    init {
        listOf(BASKET_DATABASE, ORDERING_DATABASE, INVENTORY_DATABASE, PAYMENT_DATABASE).forEach { database ->
            val created = postgres.execInContainer(
                "psql",
                "-U",
                postgres.username,
                "-d",
                CATALOG_DATABASE,
                "-c",
                "CREATE DATABASE $database",
            )
            check(created.exitCode == 0) { "CREATE DATABASE $database failed: ${created.stderr}" }
        }
        // The nodes do not create topics; each command topic has as many partitions as its context's log (G11).
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
            admin.createTopics(
                listOf(
                    NewTopic("wow.eshop-ordering.commands", partitions, 1),
                    NewTopic("wow.eshop-inventory.commands", partitions, 1),
                    NewTopic("wow.eshop-basket.receipts", 1, 1),
                    NewTopic("wow.eshop-ordering.receipts", 1, 1),
                    NewTopic("wow.eshop-payment.receipts", 1, 1),
                    NewTopic("wow.eshop-basket.basket.state", 2, 1),
                    NewTopic("wow.eshop-ordering.order.state", 2, 1),
                    NewTopic("wow.eshop-inventory.stock.state", 2, 1),
                    NewTopic("wow.eshop-payment.payment.state", 2, 1),
                ),
            ).all().get()
        }
    }

    private val started = mutableListOf<EshopService>()

    val catalog: EshopService = start(
        "catalog",
        CATALOG_MAIN,
        listOf(
            "--spring.profiles.active=postgres",
            "--spring.datasource.url=${jdbcUrl(CATALOG_DATABASE)}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
        ),
    )
    val inventory: EshopService = start("inventory", INVENTORY_MAIN, arguments(INVENTORY_DATABASE))

    /** The ordering's nodes, by node id; a killed one is removed. */
    val ordering: MutableMap<String, EshopService> = (1..orderingNodes).associateTo(linkedMapOf()) { n ->
        "ordering-$n" to startOrdering("ordering-$n")
    }
    val basket: EshopService = start("basket", BASKET_MAIN, arguments(BASKET_DATABASE))
    val payment: EshopService = start(
        "payment",
        PAYMENT_MAIN,
        arguments(PAYMENT_DATABASE) + "--eshop.payment.gateway.limit=$paymentLimit",
    )

    /** An ordering node: the first that is still running. */
    val anyOrdering: EshopService get() = ordering.values.first()

    /** Starts the ordering node [nodeId] on the ordering's database, with the Sink and the sales dataset. */
    fun startOrdering(nodeId: String): EshopService = start(
        "ordering",
        ORDERING_MAIN,
        arguments(ORDERING_DATABASE, listOf("clickhouse")) + listOf(
            "--wow.node.id=$nodeId",
            "--wow.clickhouse.endpoint=${clickhouse.httpUrl}",
            "--wow.clickhouse.username=${clickhouse.username}",
            "--wow.clickhouse.password=${clickhouse.password}",
        ),
    )

    /** The ordering's partition of the order [orderId] in the default tenant. */
    fun orderPartition(orderId: String): Int =
        Partitioner.partitionOf(AggregateRef(ORDER, Caller.DEFAULT_TENANT, orderId), partitions)

    /**
     * The node holding the ordering's command lease of [partition] (`wow_lease`), or null while none holds a live one.
     * Its lease group is `<service>/<context>`.
     */
    fun orderingOwnerOf(partition: Int): String? =
        DriverManager.getConnection(jdbcUrl(ORDERING_DATABASE), postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "SELECT owner FROM wow_lease WHERE subscription_group = ? AND partition = ? AND expires_at > ?",
            ).use { statement ->
                statement.setString(1, "eshop-ordering-service/${ORDER.context}")
                statement.setInt(2, partition)
                statement.setLong(3, System.currentTimeMillis())
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    /** How many subscriptions named [prefix]`…` have saved a checkpoint in [partition] of [database] (`wow_checkpoint`). */
    fun checkpointed(database: String, prefix: String, partition: Int): Int =
        DriverManager.getConnection(jdbcUrl(database), postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM wow_checkpoint WHERE subscription LIKE ? AND partition = ?",
            ).use { statement ->
                statement.setString(1, "$prefix%")
                statement.setInt(2, partition)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }

    /** The state of the aggregate at [path] of [service], read with [headers], or null while it has none. */
    fun state(service: EshopService, path: String, headers: Map<String, String> = emptyMap()): JsonNode? =
        view(service, path, headers)?.get("state")

    /** The owner of the aggregate at [path] of [service] (decision D189), or null when it has none. */
    fun ownerOf(service: EshopService, path: String): String? =
        checkNotNull(view(service, path)) { "No state at $path." }["scope"]?.get("ownerId")?.asString()

    private fun view(service: EshopService, path: String, headers: Map<String, String> = emptyMap()): JsonNode? {
        val answer = service.get(path, headers)
        if (answer.status == NOT_FOUND) return null
        check(answer.status == OK) { "GET $path answered ${answer.status}: ${answer.body}" }
        return json.readTree(answer.body)
    }

    fun order(id: String): JsonNode? = state(anyOrdering, "/eshop-ordering/order/$id/state")

    fun orderStatus(id: String): String? = order(id)?.get("status")?.asString()

    fun payment(id: String): JsonNode? = state(payment, "/eshop-payment/payment/$id/state")

    fun stock(product: String): JsonNode =
        checkNotNull(state(inventory, "/eshop-inventory/stock/$product/state")) { "No stock of $product." }

    /** The names of the events of the aggregate at [path] of [service], in commit order (`…/state/trace`). */
    fun events(service: EshopService, path: String): List<String> {
        val answer = service.get("$path/state/trace")
        check(answer.status == OK) { "GET $path/state/trace answered ${answer.status}: ${answer.body}" }
        return json.readTree(answer.body)["commits"].values().flatMap { commit ->
            commit["events"].values().map { it["name"].asString() }
        }
    }

    /**
     * Waits until [condition] holds, for at most [deadline]; the failure says [what], with the [detail] at that time
     * and the services' output.
     */
    fun eventually(
        what: String,
        deadline: Duration = DEADLINE,
        detail: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val until = System.nanoTime() + deadline.toNanos()
        while (!condition()) {
            if (System.nanoTime() > until) {
                throw AssertionError("Not reached in $deadline: $what. ${detail()}\n${outputs()}")
            }
            Thread.sleep(POLL.toMillis())
        }
    }

    /** The tail of every service's output, without the Kafka clients' configuration dumps and lease acquisitions. */
    fun outputs(): String = started.joinToString("\n") { service ->
        val lines = service.output().lineSequence().filterNot { it.startsWith("\t") || "acquired partition" in it }
        "--- ${service.name} ---\n${lines.joinToString("\n").takeLast(TAIL)}"
    }

    private fun start(context: String, mainClass: String, arguments: List<String>): EshopService =
        EshopService.start(context, mainClass, arguments).also { started += it }

    private fun arguments(database: String, profiles: List<String> = emptyList()): List<String> =
        backendArguments(postgres, kafka, database, partitions, profiles)

    private fun jdbcUrl(database: String): String =
        "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database"

    override fun close() {
        started.reversed().forEach { it.close() }
        clickhouse.stop()
        postgres.stop()
        kafka.stop()
    }

    companion object {
        const val KAFKA_IMAGE = "confluentinc/cp-kafka:7.4.0"
        const val POSTGRES_IMAGE = "postgres:17-alpine"
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val CATALOG_MAIN = "me.ahoo.wow.example.eshop.catalog.server.CatalogServerKt"
        const val BASKET_MAIN = "me.ahoo.wow.example.eshop.basket.server.BasketServerKt"
        const val ORDERING_MAIN = "me.ahoo.wow.example.eshop.ordering.server.OrderingServerKt"
        const val INVENTORY_MAIN = "me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt"
        const val PAYMENT_MAIN = "me.ahoo.wow.example.eshop.payment.server.PaymentServerKt"
        const val CATALOG_DATABASE = "eshop_catalog"
        const val BASKET_DATABASE = "eshop_basket"
        const val ORDERING_DATABASE = "eshop_ordering"
        const val INVENTORY_DATABASE = "eshop_inventory"
        const val PAYMENT_DATABASE = "eshop_payment"
        const val PARTITIONS = 4

        /** A checkout's `Wow-Wait` until the order is committed in the ordering context. */
        const val CHECKOUT_TO_ORDER =
            "committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)"

        /**
         * A checkout's `Wow-Wait` until the payment's `PayOrder` is committed: 15 hops, through every saga on the way
         * (each adds its own hop and the next commit), the ordering's on the inventory's stock stream included. The
         * basket knows only the ordering's Manifest: it lets the hops of the inventory and the payment through, and
         * each is checked where the chain arrives (decision D190); the default of 16 hops holds it.
         */
        const val CHECKOUT_TO_PAID = CHECKOUT_TO_ORDER +
            " > handled(eshop-ordering/OrderProcessSaga.onOrderSubmitted) > committed(eshop-ordering)" +
            " > handled(eshop-ordering/OrderProcessSaga.onOrderAwaitingValidation) > committed(eshop-inventory)" +
            " > handled(eshop-ordering/StockConfirmationSaga.onStockReserved) > committed(eshop-ordering)" +
            " > handled(eshop-payment/OrderPaymentSaga.onOrderStockConfirmed) > committed(eshop-payment)" +
            " > handled(eshop-payment/PaymentGatewaySaga.onPaymentRequested) > committed(eshop-payment)" +
            " > handled(eshop-payment/OrderPaymentSaga.onPaymentSucceeded) > committed(eshop-ordering)"
        const val OK = 200
        const val NOT_FOUND = 404
        const val TAIL = 4_000
        val ORDER = AggregateType("eshop-ordering", "order")

        /** An employee: a tenant-level caller of the catalog, basket and ordering services (decision D189). */
        val EMPLOYEE: Map<String, String> = mapOf("X-User" to "erin", "X-Role" to "employee")

        /** The headers of the buyer [id]: an owner-level caller whose owner is [id]. */
        fun buyer(id: String): Map<String, String> = mapOf("X-User" to id)
        val DEADLINE: Duration = Duration.ofSeconds(60)
        val POLL: Duration = Duration.ofMillis(100)
    }
}
