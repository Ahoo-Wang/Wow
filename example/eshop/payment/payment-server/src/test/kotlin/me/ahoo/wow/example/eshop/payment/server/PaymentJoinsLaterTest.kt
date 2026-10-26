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

package me.ahoo.wow.example.eshop.payment.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * The ordering's order stream as it is before the payment service first starts: the stock of an order was already
 * confirmed, so the order waits for its payment. In place of [InMemoryPaymentTopics]; no `@Configuration`, so the
 * server's component scan does not pick it up.
 */
class ConfirmedBeforeTopics {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1).also { topics ->
        val order = PaymentJoinsLaterTest.ORDER
        val record = StateEventRecord.of(
            ref = order,
            version = 5,
            commandId = "command-${order.id}",
            requestId = "request-${order.id}",
            commandType = "eshop-ordering.order.ConfirmOrderStock",
            commitTime = System.currentTimeMillis(),
            events = listOf(
                StateEventPayload.of("eshop-ordering.order.OrderStockConfirmed", 1, json.createObjectNode())
            ),
            state = StateView.of(
                5,
                "fingerprint",
                json.readTree("""{"status":"STOCK_CONFIRMED","totalAmount":42.00}""")
            ),
        )
        runBlocking { topics.send("wow.eshop-ordering.order.state", listOf(EnvelopeCodec.message(record))) }
    }

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics)

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * Decision D194 (DX gap G10), with the payment service joining the eShop after the ordering published an order whose
 * stock was confirmed: the order-payment saga reads the ordering's order stream, another context's, from its
 * beginning without any configuration, so the order is not skipped and its payment is requested and charged. Starting
 * at the end, as a subscription of the service's own context does, it would never have seen it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentJoinsLaterTest {
    private val payment = PaymentNode(topics = ConfirmedBeforeTopics::class.java)

    @AfterAll
    fun close() {
        payment.close()
    }

    @Test
    fun `an order confirmed before the payment service first started should still have its payment requested`() {
        // The server's configuration sets no start: the saga's default for another context's stream is the beginning.
        Binder.get(
            payment.context.environment
        ).bind("wow.subscriptions", Bindable.mapOf(String::class.java, Any::class.java))
            .isBound.assert().isFalse()
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (!succeeded()) {
            check(System.nanoTime() < deadline) { "The order confirmed before the service started was not paid." }
            Thread.sleep(POLL.toMillis())
        }
        payment.state(ORDER.id).let { state ->
            state["orderId"].asString().assert().isEqualTo(ORDER.id)
            state["amount"].decimalValue().assert().isEqualByComparingTo("42.00")
            state["status"].asString().assert().isEqualTo("SUCCEEDED")
        }
    }

    /** Whether the payment of the order has succeeded: its stream holds the gateway's approval. */
    private fun succeeded(): Boolean =
        payment.streams(ORDER.id).any { stream -> stream.events.any { it.name.endsWith("PaymentSucceeded") } }

    companion object {
        val ORDER = EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "order-early")
        private val DEADLINE: Duration = Duration.ofSeconds(30)
        private val POLL: Duration = Duration.ofMillis(100)
    }
}
