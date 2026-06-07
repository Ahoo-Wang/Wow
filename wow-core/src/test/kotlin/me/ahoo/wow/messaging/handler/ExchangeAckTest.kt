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

package me.ahoo.wow.messaging.handler

import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.handler.ExchangeAck.filterThenAck
import me.ahoo.wow.messaging.handler.ExchangeAck.finallyAck
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ExchangeAckTest {

    @Test
    fun `mono finallyAck acknowledges successful completion`() {
        val exchange = AckExchange()

        StepVerifier.create(Mono.just("value").finallyAck(exchange))
            .verifyComplete()

        exchange.ackCount.get().assert().isOne()
    }

    @Test
    fun `mono finallyAck acknowledges before rethrowing errors`() {
        val exchange = AckExchange()
        val error = IllegalStateException("failed")

        StepVerifier.create(Mono.error<String>(error).finallyAck(exchange))
            .expectErrorSatisfies {
                it.assert().isSameAs(error)
                exchange.ackCount.get().assert().isOne()
            }
            .verify()
    }

    @Test
    fun `flux finallyAck acknowledges after completion`() {
        val exchange = AckExchange()

        StepVerifier.create(Flux.just(1, 2).finallyAck(exchange))
            .verifyComplete()

        exchange.ackCount.get().assert().isOne()
    }

    @Test
    fun `filterThenAck passes matching exchanges and acknowledges discarded exchanges`() {
        val kept = AckExchange(message = TestNamedMessage(id = "keep"))
        val discarded = AckExchange(message = TestNamedMessage(id = "discard"))

        StepVerifier.create(Flux.just(kept, discarded).filterThenAck { it.message.id == "keep" })
            .expectNext(kept)
            .verifyComplete()

        kept.ackCount.get().assert().isEqualTo(0)
        discarded.ackCount.get().assert().isOne()
    }
}

private class AckExchange(
    override val message: TestNamedMessage = TestNamedMessage()
) : MessageExchange<AckExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
    val ackCount = AtomicInteger()

    override fun acknowledge(): Mono<Void> =
        Mono.fromRunnable {
            ackCount.incrementAndGet()
        }
}
