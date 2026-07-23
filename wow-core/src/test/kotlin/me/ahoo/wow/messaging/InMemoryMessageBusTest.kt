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
import me.ahoo.wow.infra.sink.mpscUnicastManySink
import me.ahoo.wow.messaging.handler.MessageExchange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import reactor.util.context.Context
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `unaccepted terminal should keep the ordinary sink available for close retry`() {
        val bus = RetryCloseInMemoryMessageBus()
        val message = TestNamedMessage()
        val completed = CountDownLatch(1)
        val subscription = bus.receive(MessageSubscription(message)).subscribe({}, {}, completed::countDown)
        try {
            assertThrows<Sinks.EmissionException> {
                bus.close()
            }
            bus.subscriberCount(message).assert().isEqualTo(1)

            bus.close()
            completed.await(5, TimeUnit.SECONDS).assert().isTrue()
            bus.subscriberCount(message).assert().isZero()
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `zero subscriber completion should detach an unbuffered unicast sink`() {
        val sinkCreations = AtomicInteger()
        val bus = ZeroSubscriberCloseInMemoryMessageBus(sinkCreations)
        val message = TestNamedMessage()

        bus.send(message).block(Duration.ofSeconds(1))
        sinkCreations.get().assert().isEqualTo(1)
        bus.subscriberCount(message).assert().isZero()

        bus.close()

        val reopened = bus.receive(MessageSubscription(message)).subscribe()
        try {
            sinkCreations.get().assert().isEqualTo(2)
            bus.subscriberCount(message).assert().isEqualTo(1)
        } finally {
            reopened.dispose()
            bus.close()
        }
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

    @Test
    fun `throwing sink should not bypass a pending mpsc close settlement`() {
        val bus = MixedCloseInMemoryMessageBus()
        val activeMessage = TestNamedMessage(aggregateName = "active")
        val throwingMessage = TestNamedMessage(aggregateName = "throwing")
        val concurrentMessage = TestNamedMessage(aggregateName = "concurrent")
        val onNextEntered = CountDownLatch(1)
        val releaseOnNext = CountDownLatch(1)
        val activeCompleted = CountDownLatch(1)
        val activeSubscription = bus.receive(MessageSubscription(activeMessage)).subscribe(
            {
                onNextEntered.countDown()
                check(releaseOnNext.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release active MPSC delivery."
                }
            },
            { throw AssertionError("Expected active MPSC completion.", it) },
            { activeCompleted.countDown() },
        )
        val throwingSubscription = bus.receive(MessageSubscription(throwingMessage)).subscribe()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val activeSend = executor.submit { bus.send(activeMessage).block(Duration.ofSeconds(5)) }
            onNextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            assertThrows<IllegalStateException> {
                bus.close()
            }
            StepVerifier.create(bus.receive(MessageSubscription(concurrentMessage)))
                .expectComplete()
                .verify(Duration.ofSeconds(1))

            releaseOnNext.countDown()
            activeSend.get(5, TimeUnit.SECONDS)
            activeCompleted.await(5, TimeUnit.SECONDS).assert().isTrue()

            val reopened = bus.receive(MessageSubscription(concurrentMessage)).subscribe()
            try {
                bus.subscriberCount(concurrentMessage).assert().isEqualTo(1)
            } finally {
                reopened.dispose()
            }
        } finally {
            releaseOnNext.countDown()
            activeSubscription.dispose()
            throwingSubscription.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
            bus.close()
        }
    }

    @Test
    fun `exceptionally settled mpsc sink should be removed before reopening the bus`() {
        val bus = MpscInMemoryMessageBus()
        val message = TestNamedMessage()
        val completionFailure = IllegalStateException("completion failed")
        val subscription = AtomicReference<Subscription>()
        bus.receive(MessageSubscription(message)).subscribe(
            object : CoreSubscriber<TestMessageExchange> {
                override fun currentContext(): Context = Context.empty()

                override fun onSubscribe(delegate: Subscription) {
                    subscription.set(delegate)
                    delegate.request(Long.MAX_VALUE)
                }

                override fun onNext(exchange: TestMessageExchange) = Unit

                override fun onError(throwable: Throwable) = Unit

                override fun onComplete() {
                    throw completionFailure
                }
            },
        )

        assertThrows<IllegalStateException> {
            bus.close()
        }.assert().isSameAs(completionFailure)

        val reopenedError = AtomicReference<Throwable?>()
        val reopened = bus.receive(MessageSubscription(message)).subscribe({}, reopenedError::set)
        try {
            reopenedError.get().assert().isNull()
            bus.subscriberCount(message).assert().isEqualTo(1)
        } finally {
            reopened.dispose()
            subscription.get().cancel()
            bus.close()
        }
    }

    @Test
    fun `terminal callback failure should detach an ordinary terminated sink`() {
        val bus = TestInMemoryMessageBus()
        val message = TestNamedMessage()
        val completionFailure = IllegalStateException("completion failed")
        bus.receive(MessageSubscription(message)).subscribe(
            object : CoreSubscriber<TestMessageExchange> {
                override fun currentContext(): Context = Context.empty()

                override fun onSubscribe(delegate: Subscription) {
                    delegate.request(Long.MAX_VALUE)
                }

                override fun onNext(exchange: TestMessageExchange) = Unit

                override fun onError(throwable: Throwable) = Unit

                override fun onComplete() {
                    throw completionFailure
                }
            },
        )

        assertThrows<IllegalStateException> {
            bus.close()
        }.assert().isSameAs(completionFailure)

        val reopenedError = AtomicReference<Throwable?>()
        val reopened = bus.receive(MessageSubscription(message)).subscribe({}, reopenedError::set)
        try {
            reopenedError.get().assert().isNull()
            bus.subscriberCount(message).assert().isEqualTo(1)
        } finally {
            reopened.dispose()
            bus.close()
        }
    }

    @Test
    fun `shared close failure should not stop remaining sinks or leave the bus closing`() {
        val failure = IllegalStateException("shared close failure")
        val closeCalls = AtomicInteger()
        val bus = SharedFailureInMemoryMessageBus(failure, closeCalls)
        val firstMessage = TestNamedMessage(aggregateName = "first")
        val secondMessage = TestNamedMessage(aggregateName = "second")
        val reopenedMessage = TestNamedMessage(aggregateName = "reopened")
        val first = bus.receive(MessageSubscription(firstMessage)).subscribe()
        val second = bus.receive(MessageSubscription(secondMessage)).subscribe()
        try {
            assertThrows<IllegalStateException> {
                bus.close()
            }.assert().isSameAs(failure)
            closeCalls.get().assert().isEqualTo(2)

            val reopened = bus.receive(MessageSubscription(reopenedMessage)).subscribe()
            try {
                bus.subscriberCount(reopenedMessage).assert().isEqualTo(1)
            } finally {
                reopened.dispose()
            }
        } finally {
            first.dispose()
            second.dispose()
        }
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

private class RetryCloseInMemoryMessageBus : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        RetryCompleteManySink()
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class ZeroSubscriberCloseInMemoryMessageBus(
    private val sinkCreations: AtomicInteger,
) : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        sinkCreations.incrementAndGet()
        Sinks.unsafe().many().unicast().onBackpressureError()
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class MixedCloseInMemoryMessageBus : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        when (it.aggregateName) {
            "active" -> mpscUnicastManySink()
            "throwing" -> ThrowingCompleteManySink()
            else -> Sinks.unsafe().many().multicast().onBackpressureBuffer()
        }
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class MpscInMemoryMessageBus : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        mpscUnicastManySink()
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class SharedFailureInMemoryMessageBus(
    private val failure: Throwable,
    private val closeCalls: AtomicInteger,
) : InMemoryMessageBus<TestNamedMessage, TestMessageExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<TestNamedMessage> = {
        SharedFailureCompleteManySink(failure, closeCalls)
    }

    override fun TestNamedMessage.createExchange(): TestMessageExchange = TestMessageExchange(this)
}

private class ThrowingCompleteManySink<T : Any>(
    private val attempts: AtomicInteger = AtomicInteger(),
    private val backing: Sinks.Many<T> = Sinks.unsafe().many().multicast().onBackpressureBuffer(),
) : Sinks.Many<T> by backing {
    override fun tryEmitComplete(): Sinks.EmitResult =
        if (attempts.getAndIncrement() == 0) {
            throw IllegalStateException("close failed")
        } else {
            backing.tryEmitComplete()
        }
}

private class SharedFailureCompleteManySink<T : Any>(
    private val failure: Throwable,
    private val closeCalls: AtomicInteger,
    private val backing: Sinks.Many<T> = Sinks.unsafe().many().multicast().onBackpressureBuffer(),
) : Sinks.Many<T> by backing {
    override fun tryEmitComplete(): Sinks.EmitResult {
        closeCalls.incrementAndGet()
        throw failure
    }
}

private class RetryCompleteManySink<T : Any>(
    private val attempts: AtomicInteger = AtomicInteger(),
    private val backing: Sinks.Many<T> = Sinks.unsafe().many().multicast().onBackpressureBuffer(),
) : Sinks.Many<T> by backing {
    override fun tryEmitComplete(): Sinks.EmitResult =
        if (attempts.getAndIncrement() == 0) {
            Sinks.EmitResult.FAIL_NON_SERIALIZED
        } else {
            backing.tryEmitComplete()
        }
}

private class TestMessageExchange(
    override val message: TestNamedMessage
) : MessageExchange<TestMessageExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
}
