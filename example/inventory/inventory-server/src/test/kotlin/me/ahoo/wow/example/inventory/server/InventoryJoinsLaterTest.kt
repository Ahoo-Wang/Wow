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

package me.ahoo.wow.example.inventory.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.InMemoryTopics
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spring.boot.WowProperties
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * The shop's order stream as it is before the inventory first starts: an order of a product the inventory does not
 * stock was already shipped. No `@Configuration`, so the server's component scan does not pick it up.
 */
class ShippedBeforeTransport {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(InventoryServerTest.PARTITIONS).also { topics ->
        val order = InventoryJoinsLaterTest.ORDER
        val view = json.readTree("""{"items":[{"productId":"fig","quantity":1}]}""")
        val record = StateEventRecord(
            ref = order,
            version = 3,
            commandId = "command-${order.id}",
            requestId = "request-${order.id}",
            commandType = "example.order.ShipOrder",
            commitTime = System.currentTimeMillis(),
            events = listOf(StateEventPayload("example.order.OrderShipped", 1, json.createObjectNode())),
            state = StateView(3, "fingerprint", view),
        )
        runBlocking { topics.send("wow.example.order.state", listOf(EnvelopeCodec.message(record))) }
    }

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics, intake = true)

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * Decision D194, DX G10: a new inventory joins the shop's order stream from its beginning without any configuration,
 * so an order shipped before it first started is not skipped. The order's product is not stocked here, so the
 * reservation it commits is rejected and, the saga having no `@OnRejected` handler, parks the order: proof the saga
 * handled a record published before the service existed. Starting at the end, it would never have seen it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryJoinsLaterTest {
    private val context: ConfigurableApplicationContext =
        SpringApplicationBuilder(InventoryServer::class.java, ShippedBeforeTransport::class.java).run(
            "--spring.main.banner-mode=off",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
            "--wow.log.partitions=${InventoryServerTest.PARTITIONS}",
            "--wow.subscription.stop-timeout=2s",
        )

    @AfterAll
    fun close() {
        context.close()
    }

    @Test
    fun `an order shipped before the inventory first started should reach its saga without a configured start`() {
        // The server's configuration sets no start: the saga's default for another context's stream is the beginning.
        context.getBean(WowProperties::class.java).subscriptions.assert().isEmpty()
        val ledger = context.getBean(ServiceNode::class.java).context("inventory").backends.ledger
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (runBlocking { ledger.parked(SHIPPED, ORDER) } == null) {
            check(System.nanoTime() < deadline) { "The order shipped before the inventory started was not handled." }
            Thread.sleep(50)
        }
        runBlocking { ledger.parked(SHIPPED, ORDER) }!!.reason.assert()
            .contains("inventory.stock/(0)/fig", "wow.aggregate.not-found")
    }

    companion object {
        private const val SHIPPED = "OrderStockSaga.onOrderShipped"
        val ORDER = AggregateType("example", "order").ref("(0)", "order-early")
    }
}
