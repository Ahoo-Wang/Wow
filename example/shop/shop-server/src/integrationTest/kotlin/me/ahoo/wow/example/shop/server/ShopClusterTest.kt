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

package me.ahoo.wow.example.shop.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.shop.api.ExampleContext
import me.ahoo.wow.example.shop.domain.order.Order
import me.ahoo.wow.example.shop.domain.order.PricingService
import me.ahoo.wow.http.internal.InternalHttp
import me.ahoo.wow.runtime.node.Routing
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The server's pricing, counting the orders this node decided: the node a command ran on consults it. */
class CountingPricingService(private val delegate: PricingService) : PricingService {
    val calls = AtomicInteger()

    override suspend fun getProductPrice(productId: String): BigDecimal {
        calls.incrementAndGet()
        return delegate.getProductPrice(productId)
    }
}

@Configuration(proxyBeanMethods = false)
class CountingPricing {
    @Bean
    @Primary
    fun countingPricingService(): CountingPricingService = CountingPricingService(CatalogPricingService(Catalog.DEFAULT))
}

/**
 * Two shop servers in one JVM, in the `postgres` profile on one database (roadmap M5-I2): each serves its public port
 * and its internal port. An order placed on the node that does not own its partition is forwarded over h2c to the one
 * that does, and the default wait, until the snapshot holds the commit, ends on the node it was sent to through the
 * owner's wait signal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopClusterTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
    private val nodes = listOf(FIRST, SECOND).map { node ->
        startShop(
            "wow.node.id" to node,
            "wow.log.partitions" to PARTITIONS,
            "wow.cluster.lease-ttl" to "15s",
            "wow.cluster.renew-interval" to "2s",
            "wow.cluster.member-ttl" to "15s",
            "wow.cluster.heartbeat-interval" to "2s",
            "wow.cluster.reconcile-interval" to "200ms",
            "wow.cluster.route-refresh-interval" to "200ms",
            "wow.subscription.checkpoint-interval" to "100ms",
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            profiles = listOf(PostgresDataSource.PROFILE),
            sources = listOf(CountingPricing::class.java),
        )
    }
    private val first = nodes[0]
    private val second = nodes[1]

    @AfterAll
    fun close() {
        nodes.forEach { it.close() }
        postgres.stop()
    }

    private val ShopNode.pricing: CountingPricingService
        get() = context.getBean(CountingPricingService::class.java)

    @Test
    fun `the servers should run on PostgreSQL with partitioned routing`() {
        nodes.forEach { shop ->
            shop.node.options.routing.assert().isEqualTo(Routing.PARTITIONED)
            shop.node.service.assert().isEqualTo("shop-service")
        }
    }

    @Test
    fun `an order placed on the node that does not own it should run on the owner and its wait end where it was sent`() {
        val (warmUp, id) = idsOwnedBySecond(2)
        // The forward timeout (one second, D80) is for an owner that is warm; this JVM, both nodes in it, has decided
        // no order yet. One order decided on its owner first, so the forward below is not its first decision. The
        // connection to the owner was opened when the first node's routes learned it (ClusterChannel.connect).
        second.post(
            "/example/order/$warmUp/create-order",
            """{"items":[{"productId":"apple","price":2.50,"quantity":1}],"address":$ADDRESS}""",
            ALICE,
        ).status.assert().isEqualTo(200)
        val before = first.pricing.calls.get()
        val ownerBefore = second.pricing.calls.get()

        val created = first.post(
            "/example/order/$id/create-order",
            """{"items":[{"productId":"apple","price":2.50,"quantity":2}],"address":$ADDRESS}""",
            ALICE,
        )
        created.status.assert().isEqualTo(200)
        created.json["status"].asString().assert().isEqualTo("SUCCEEDED")
        // The default wait reached the snapshot, which the owner's snapshot subscription signalled across.
        created.json["hop"]["subscription"].asString().assert().isEqualTo("snapshot")
        // The total the owner's decide function put (D49) came back with the forward's reply: the snapshot's hop,
        // signalled from the owner, answers it.
        created.json["results"][Order.TOTAL_AMOUNT_RESULT].decimalValue().assert().isEqualByComparingTo("5.00")
        val decided = "first ${first.pricing.calls.get() - before}, second ${second.pricing.calls.get() - ownerBefore}"
        val split = nodes.map { it.node.context(ExampleContext.NAME).ownership.owned }
        val partition = Partitioner.partitionOf(
            AggregateType(ExampleContext.NAME, ExampleContext.ORDER).ref(Caller.DEFAULT_TENANT, id),
            PARTITIONS,
        )
        val where = "Decided by $decided; partition $partition; owned $split; result ${created.body}"
        second.pricing.calls.get().assert().describedAs(where).isGreaterThan(ownerBefore)
        first.pricing.calls.get().assert().describedAs(where).isEqualTo(before)

        // The node the order was sent to reads its own write from the shared snapshots.
        val state = first.get("/example/order/$id/state", ALICE)
        state.status.assert().isEqualTo(200)
        state.headers.eTag.assert().isEqualTo("\"1\"")
        state.json["state"]["status"].asString().assert().isEqualTo("CREATED")

        // So does the next command, forwarded the same way.
        first.post("/example/order/$id/pay-order", """{"paymentId":"payment-1","amount":5.00}""", ALICE)
            .json["version"].asInt().assert().isEqualTo(2)
        first.get("/example/order/$id/state", ALICE).json["state"]["status"].asString().assert().isEqualTo("PAID")
    }

    @Test
    fun `the internal port the nodes forward over should speak h2c`() {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()
        val request = HttpRequest.newBuilder(InternalHttp.uri(second.node.address, InternalHttp.SIGNALS_PATH))
            .header("Content-Type", InternalHttp.JSON)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.version().assert().isEqualTo(HttpClient.Version.HTTP_2)
        response.statusCode().assert().isEqualTo(400)
    }

    /**
     * [count] order ids whose partitions the second node owns and the first forwards to it, once the nodes have split
     * the partitions between them and the split has held for [STABLE_POLLS] polls: a partition still moving between
     * them would run the command on either.
     */
    private fun idsOwnedBySecond(count: Int): List<String> {
        val type = AggregateType(ExampleContext.NAME, ExampleContext.ORDER)
        val deadline = System.currentTimeMillis() + OWNERSHIP_TIMEOUT
        var last: Set<Int>? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            val firstOwnership = first.node.context(ExampleContext.NAME).ownership
            val owned = second.node.context(ExampleContext.NAME).ownership.owned
            val split = firstOwnership.owned.isNotEmpty() && owned.isNotEmpty() &&
                (firstOwnership.owned + owned) == (0 until PARTITIONS).toSet() &&
                firstOwnership.owned.intersect(owned).isEmpty() &&
                owned.all { firstOwnership.forwardTarget(it)?.node == SECOND }
            stable = if (split && owned == last) stable + 1 else 0
            last = owned.takeIf { split }
            if (stable >= STABLE_POLLS) {
                return (0 until CANDIDATES).map { "order-${UUID.randomUUID()}" }.filter { candidate ->
                    Partitioner.partitionOf(type.ref(Caller.DEFAULT_TENANT, candidate), PARTITIONS) in owned
                }.take(count)
            }
            Thread.sleep(POLL)
        }
        error("The nodes did not split the partitions in time.")
    }

    private companion object {
        const val FIRST = "node-a"
        const val SECOND = "node-b"
        const val ALICE = "alice"
        const val PARTITIONS = 8
        const val CANDIDATES = 200
        const val OWNERSHIP_TIMEOUT = 30_000L
        const val POLL = 100L
        const val STABLE_POLLS = 10
        const val ADDRESS =
            """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1 Century Avenue"}"""
    }
}
