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

package me.ahoo.wow.messaging

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.infra.sink.concurrent
import me.ahoo.wow.messaging.handler.MessageExchange
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap

class InMemoryMessageBusTest {

    @Test
    fun `send marks message read only even when no subscriber exists`() {
        val bus = TestInMemoryMessageBus()
        val message = TestNamedMessage()

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        message.isReadOnly.assert().isTrue()
        bus.subscriberCount(message).assert().isEqualTo(0)
    }

    @Test
    fun `receive emits exchanges for subscribed aggregates`() {
        val bus = TestInMemoryMessageBus()
        val message = TestNamedMessage()

        StepVerifier.create(bus.receive(setOf(message)))
            .then {
                bus.subscriberCount(message).assert().isEqualTo(1)
                bus.send(message).subscribe()
            }
            .assertNext { exchange ->
                exchange.message.assert().isSameAs(message)
            }
            .thenCancel()
            .verify()
    }

    @Test
    fun `close completes sinks and clears subscribers`() {
        val bus = TestInMemoryMessageBus()
        val message = TestNamedMessage()

        StepVerifier.create(bus.receive(setOf(message)))
            .then {
                bus.subscriberCount(message).assert().isEqualTo(1)
                bus.close()
            }
            .verifyComplete()

        bus.subscriberCount(message).assert().isEqualTo(0)
    }
}

private class TestInMemoryMessageBus : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        Sinks.unsafe().many().multicast().onBackpressureBuffer<TestNamedMessage>().concurrent()
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class TestMessageExchange(
    override val message: TestNamedMessage
) : MessageExchange<TestMessageExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
}
