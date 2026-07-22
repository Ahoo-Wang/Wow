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
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

        StepVerifier.create(bus.receive(MessageSubscription(message)))
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

        StepVerifier.create(bus.receive(MessageSubscription(message)))
            .then {
                bus.subscriberCount(message).assert().isEqualTo(1)
                bus.close()
            }
            .verifyComplete()

        bus.subscriberCount(message).assert().isEqualTo(0)
    }

    @Test
    fun `close racing first sink creation should not orphan the receiving flux`() {
        val sinkSupplierEntered = CountDownLatch(1)
        val releaseSinkSupplier = CountDownLatch(1)
        val bus = BlockingSinkInMemoryMessageBus(sinkSupplierEntered, releaseSinkSupplier)
        val message = TestNamedMessage()
        val received = AtomicReference<Flux<TestMessageExchange>>()
        val receiveThread = Thread {
            received.set(bus.receive(MessageSubscription(message)))
        }.also(Thread::start)
        sinkSupplierEntered.await(5, TimeUnit.SECONDS).assert().isTrue()
        val closeThread = Thread(bus::close).also(Thread::start)

        try {
            awaitBlocked(closeThread)
        } finally {
            releaseSinkSupplier.countDown()
        }
        receiveThread.join(5_000)
        closeThread.join(5_000)

        receiveThread.isAlive.assert().isFalse()
        closeThread.isAlive.assert().isFalse()
        StepVerifier.create(checkNotNull(received.get()))
            .expectComplete()
            .verify(Duration.ofSeconds(1))
        bus.subscriberCount(message).assert().isZero()
    }

    @Test
    fun `close should not expose an empty registry before detached sinks complete`() {
        val completionEntered = CountDownLatch(1)
        val releaseCompletion = CountDownLatch(1)
        val bus = TestInMemoryMessageBus()
        val closingMessage = TestNamedMessage(aggregateName = "closing_aggregate")
        val concurrentMessage = TestNamedMessage(aggregateName = "concurrent_aggregate")
        val subscription = bus.receive(MessageSubscription(closingMessage))
            .doOnComplete {
                completionEntered.countDown()
                check(releaseCompletion.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release sink completion."
                }
            }
            .subscribe()
        val closeThread = Thread(bus::close).also(Thread::start)
        completionEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

        try {
            StepVerifier.create(bus.send(concurrentMessage))
                .verifyComplete()
            concurrentMessage.isReadOnly.assert().isTrue()
            StepVerifier.create(bus.receive(MessageSubscription(concurrentMessage)))
                .expectComplete()
                .verify(Duration.ofSeconds(1))
        } finally {
            releaseCompletion.countDown()
            closeThread.join(5_000)
            subscription.dispose()
        }

        closeThread.isAlive.assert().isFalse()
        bus.subscriberCount(concurrentMessage).assert().isZero()
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        thread.state.assert().isEqualTo(Thread.State.BLOCKED)
    }
}

private class TestInMemoryMessageBus : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        Sinks.unsafe().many().multicast().onBackpressureBuffer<TestNamedMessage>().concurrent()
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class BlockingSinkInMemoryMessageBus(
    private val sinkSupplierEntered: CountDownLatch,
    private val releaseSinkSupplier: CountDownLatch,
) : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        sinkSupplierEntered.countDown()
        check(releaseSinkSupplier.await(5, TimeUnit.SECONDS)) {
            "Timed out waiting to release sink creation."
        }
        Sinks.unsafe().many().multicast().onBackpressureBuffer()
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class TestMessageExchange(
    override val message: TestNamedMessage
) : MessageExchange<TestMessageExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
}
