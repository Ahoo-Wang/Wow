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

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.InMemoryTopics
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.command.CommandMessages
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The topics of the tests, in memory: one partition each. A configuration only where [startShop] passes it: no
 * `@Configuration`, so the server's component scan does not give a test on Kafka these topics.
 */
class InMemoryShopTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics)
}

/**
 * The order saga across contexts (decision D144), its topics in memory in place of Kafka: a placed order sends a
 * `ReserveStock` for each product to the inventory's command topic, asking for a receipt, and the receipt of one the
 * inventory refused cancels the order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderSagaEndToEndTest {
    private val shop = startShop(sources = listOf(InMemoryShopTopics::class.java))
    private val topics = shop.context.getBean(InMemoryTopics::class.java)

    @AfterAll
    fun close() {
        shop.close()
    }

    @Test
    fun `a placed order should reserve its stock in the inventory and be cancelled when it cannot`(): Unit = runBlocking {
        val items = """[{"productId":"apple","price":2.50,"quantity":2},{"productId":"durian","price":30.00,"quantity":1}]"""
        val created = shop.post(
            "/example/order/create-order",
            """{"items":$items,"address":$ADDRESS}""",
            ALICE,
            mapOf("Wow-Wait" to "committed > handled(example/OrderSaga.onOrderCreated)"),
        )
        created.status.assert().describedAs(created.body.toString()).isEqualTo(200)
        created.json["hop"]["subscription"].asString().assert().isEqualTo("OrderSaga.onOrderCreated")
        val id = created.json["ref"]["id"].asString()

        // The saga's hop is done once the commands are on the inventory's topic.
        val sent = withTimeout(30.seconds) { topics.read(COMMANDS, 0, 0).take(2).toList() }
            .map { CommandMessages.commandOf(EnvelopeCodec.decode(it.value)) }
        sent.map { it.type }.assert().containsOnly("inventory.stock.ReserveStock")
        sent.map { it.ref.id to it.body["quantity"].asInt() }.assert().containsExactly("apple" to 2, "durian" to 1)
        sent.forEach { it.body["orderId"].asString().assert().isEqualTo(id) }
        val durian = sent.last()
        val replyTo = checkNotNull(durian.replyTo)
        replyTo.context.assert().isEqualTo("example")
        replyTo.subscription.assert().isEqualTo("OrderSaga.onOrderCreated")

        // The inventory refuses the durian: its receipt comes back, and the saga cancels the order.
        topics.send(RECEIPTS, listOf(CommandMessages.receipt(receiptOf(durian))))
        eventually { state(id)["status"].asString() == "CANCELLED" }
        state(id)["cancelReason"].asString().assert().contains("durian").contains("Only 0 of durian")
    }

    private fun receiptOf(command: CommandWire): CommandRejected<Any> {
        val replyTo = checkNotNull(command.replyTo)
        return CommandRejected(
            commandId = command.id,
            commandType = command.type,
            ref = command.ref,
            error = ErrorInfo("inventory.stock.insufficient", "Only 0 of durian are available, not 1."),
            causation = checkNotNull(command.causation),
            executionId = ExecutionId(replyTo.subscription, replyTo.ref, replyTo.version),
        )
    }

    private fun state(id: String) = shop.get("/example/order/$id/state", ALICE).json["state"]

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in $DEADLINE." }
            Thread.sleep(POLL.toMillis())
        }
    }

    private companion object {
        const val ALICE = "alice"
        const val COMMANDS = "wow.inventory.commands"
        const val RECEIPTS = "wow.example.receipts"
        const val ADDRESS = """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1"}"""
        val DEADLINE: Duration = Duration.ofSeconds(30)
        val POLL: Duration = Duration.ofMillis(50)
    }
}
