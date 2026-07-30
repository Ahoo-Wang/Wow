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
import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.infra.sink.mpscUnicastManySink
import me.ahoo.wow.messaging.dispatcher.AggregateDispatcher
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscription
import reactor.core.Disposable
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalFirstMessageBusTest {

    @Test
    fun `receiver waits for local and distributed readiness`() {
        val localReadiness = Sinks.empty<Void>()
        val distributedReadiness = Sinks.empty<Void>()
        val bus = RecordingLocalFirstMessageBus(
            localBus = RecordingLocalBus(readiness = localReadiness.asMono()),
            distributedBus = RecordingDistributedBus(
                readiness = distributedReadiness.asMono(),
            ),
        )
        val receiver = bus.receiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )
        receiver.messages.subscribe()
        val ready = receiver.readiness.toFuture()

        localReadiness.tryEmitEmpty().orThrow()
        ready.isDone.assert().isFalse()
        distributedReadiness.tryEmitEmpty().orThrow()
        ready.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `receiver opens local and distributed processing exactly once`() {
        val localAdmissions = AtomicInteger()
        val distributedAdmissions = AtomicInteger()
        val bus = RecordingLocalFirstMessageBus(
            localBus = RecordingLocalBus(
                processingAdmission = localAdmissions::incrementAndGet,
            ),
            distributedBus = RecordingDistributedBus(
                processingAdmission = distributedAdmissions::incrementAndGet,
            ),
        )
        val receiver = bus.receiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )

        receiver.openProcessing()
        receiver.openProcessing()

        localAdmissions.get().assert().isOne()
        distributedAdmissions.get().assert().isOne()
    }

    @Test
    fun `receiver revokes local routing before physical cancellation`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val subscription = MessageSubscription(
            LocalFirstTestMessage(),
            receiverGroup = "receiver-group",
        )
        val receiver = bus.runtimeReceiver(subscription)
        val messages = receiver.messages.subscribe()

        try {
            localBus.physicalSubscriberCount.assert().isOne()
            localBus.subscriberCount(subscription.namedAggregates.single()).assert().isZero()

            receiver.openProcessing()
            localBus.subscriberCount(subscription.namedAggregates.single()).assert().isOne()

            receiver.closeProcessing()
            localBus.physicalSubscriberCount.assert().isOne()
            localBus.subscriberCount(subscription.namedAggregates.single()).assert().isZero()

            val message = LocalFirstTestMessage(id = "fallback")
            StepVerifier.create(bus.send(message))
                .verifyComplete()

            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            messages.dispose()
            bus.close()
        }
    }

    @Test
    fun `routing revocation during local emission falls back without local handled flag`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = bus.runtimeReceiver(
            MessageSubscription(
                LocalFirstTestMessage(),
                receiverGroup = "receiver-group",
            ),
        )
        val localDeliveryEntered = CountDownLatch(1)
        val releaseLocalDelivery = CountDownLatch(1)
        val messages = receiver.messages.subscribe {
            localDeliveryEntered.countDown()
            check(releaseLocalDelivery.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release local delivery."
            }
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            receiver.openProcessing()
            val send = executor.submit {
                bus.send(LocalFirstTestMessage(id = "racing")).block(Duration.ofSeconds(5))
            }
            localDeliveryEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            receiver.closeProcessing()
            releaseLocalDelivery.countDown()
            send.get(5, TimeUnit.SECONDS)

            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            releaseLocalDelivery.countDown()
            messages.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
            bus.close()
        }
    }

    @Test
    fun `send sends local message first and distributed copy when local subscriber exists`() {
        val localBus = RecordingLocalBus(subscribers = 1)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val message = LocalFirstTestMessage(id = "message-id")

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        localBus.sent.single().assert().isNotSameAs(message)
        distributedBus.sent.single().assert().isNotSameAs(message)
        message.isLocalFirst().assert().isFalse()
        distributedBus.sent.single().isLocalFirst().assert().isTrue()
    }

    @Test
    fun `each send subscription owns fresh local and distributed copies`() {
        val localBus = RecordingLocalBus(subscribers = 1)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val publisher = bus.send(LocalFirstTestMessage(id = "message-id"))

        StepVerifier.create(publisher).verifyComplete()
        StepVerifier.create(publisher).verifyComplete()

        localBus.sent.assert().hasSize(2)
        localBus.sent[0].assert().isNotSameAs(localBus.sent[1])
        distributedBus.sent.assert().hasSize(2)
        distributedBus.sent[0].assert().isNotSameAs(distributedBus.sent[1])
        distributedBus.sent.all { it.isLocalFirst() }.assert().isTrue()
    }

    @Test
    fun `send disables local first on distributed copy when local send fails`() {
        val localBus = RecordingLocalBus(subscribers = 1).apply {
            sendResult = { Mono.error(IllegalStateException("local failed")) }
        }
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)

        StepVerifier.create(bus.send(LocalFirstTestMessage(id = "message-id")))
            .verifyComplete()

        localBus.sent.assert().hasSize(1)
        distributedBus.sent.single().isLocalFirst().assert().isFalse()
    }

    @Test
    fun `send skips local bus when there are no local subscribers`() {
        val localBus = RecordingLocalBus(subscribers = 0)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val message = LocalFirstTestMessage(id = "message-id")

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        localBus.sent.assert().isEmpty()
        distributedBus.sent.single().assert().isNotSameAs(message)
        message.isLocalFirst().assert().isFalse()
    }

    @Test
    fun `send skips an in-memory bus while its active sink is closing`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val activeMessage = LocalFirstTestMessage(id = "active")
        val fallbackMessage = LocalFirstTestMessage(id = "fallback")
        val onNextEntered = CountDownLatch(1)
        val releaseOnNext = CountDownLatch(1)
        val localSubscription = localBus.receive(MessageSubscription(activeMessage)).subscribe {
            onNextEntered.countDown()
            check(releaseOnNext.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release the active local delivery."
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val activeSend = executor.submit {
                localBus.send(activeMessage).block(Duration.ofSeconds(5))
            }
            onNextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            localBus.close()
            localBus.subscriberCount(activeMessage).assert().isZero()

            StepVerifier.create(bus.send(fallbackMessage))
                .verifyComplete()
            distributedBus.sent.single().assert().isNotSameAs(fallbackMessage)
            fallbackMessage.isLocalFirst().assert().isFalse()

            releaseOnNext.countDown()
            activeSend.get(5, TimeUnit.SECONDS)
        } finally {
            releaseOnNext.countDown()
            localSubscription.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
            localBus.close()
        }
    }

    @Test
    fun `send skips local bus when local first is disabled case insensitively`() {
        val localBus = RecordingLocalBus(subscribers = 1)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val message = LocalFirstTestMessage(
            id = "message-id",
            header = DefaultHeader.empty().with(LOCAL_FIRST_HEADER, "FALSE"),
        )

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        localBus.sent.assert().isEmpty()
        distributedBus.sent.single().assert().isSameAs(message)
        message.isLocalFirst().assert().isFalse()
    }

    @Test
    fun `send skips local bus when aggregate is not local`() {
        val localBus = RecordingLocalBus(subscribers = 1)
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val message = LocalFirstTestMessage(id = "message-id", aggregateName = "non_local_aggregate")

        StepVerifier.create(bus.send(message))
            .verifyComplete()

        localBus.sent.assert().isEmpty()
        distributedBus.sent.single().assert().isSameAs(message)
        message.shouldLocalFirst().assert().isFalse()
    }

    @Test
    fun `receive filters distributed messages already handled locally and acknowledges them`() {
        val localExchange = LocalFirstTestExchange(LocalFirstTestMessage(id = "local"))
        val filteredDistributedExchange = LocalFirstTestExchange(
            LocalFirstTestMessage(id = "filtered").withLocalFirst()
        )
        val remoteDistributedExchange = LocalFirstTestExchange(LocalFirstTestMessage(id = "remote"))
        val localBus = RecordingLocalBus(receiveFlux = Flux.just(localExchange))
        val distributedBus = RecordingDistributedBus(
            receiveFlux = Flux.just(filteredDistributedExchange, remoteDistributedExchange)
        )
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val subscription = MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group")

        StepVerifier.create(bus.receive(subscription).collectList())
            .assertNext { exchanges ->
                exchanges.map { it.message.id }.toSet().assert().isEqualTo(setOf("local", "remote"))
                filteredDistributedExchange.acknowledged.assert().isTrue()
                remoteDistributedExchange.acknowledged.assert().isFalse()
            }
            .verifyComplete()

        localBus.received.single().receiverGroup.assert().isEqualTo(subscription.receiverGroup)
        distributedBus.received.single().assert().isEqualTo(subscription)
    }
}

class LocalFirstMessageBusShutdownTest {

    @Test
    fun `distributed suppression requires local runtime admission`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = localBus.runtimeReceiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )
        val dispatcher = LocalReceiptDispatcher(receiver)

        try {
            dispatcher.prepare(DefaultRuntimeContext()).block()
            dispatcher.start()

            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "admitted")))
                .verifyComplete()

            dispatcher.handled.get().assert().isOne()
            distributedBus.sent.single().isLocalFirst().assert().isTrue()
        } finally {
            dispatcher.quiesce()
            StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
            bus.close()
        }
    }

    @Test
    fun `ordinary receiver does not require the runtime receipt protocol`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = localBus.receiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "custom"),
        )
        val received = AtomicInteger()
        val subscription = receiver.messages.subscribe {
            received.incrementAndGet()
        }

        try {
            receiver.openProcessing()

            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "custom-fallback")))
                .verifyComplete()

            received.get().assert().isZero()
            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            receiver.closeProcessing()
            subscription.dispose()
            bus.close()
        }
    }

    @Test
    fun `custom runtime receiver can confirm local admission`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = localBus.runtimeReceiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "custom"),
        )
        val subscription = receiver.messages.subscribe { exchange ->
            exchange.confirmLocalDelivery()
        }

        try {
            receiver.openProcessing()

            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "custom-admitted")))
                .verifyComplete()

            distributedBus.sent.single().isLocalFirst().assert().isTrue()
        } finally {
            receiver.closeProcessing()
            subscription.dispose()
            bus.close()
        }
    }

    @Test
    fun `multicast suppression waits for every targeted runtime admission`() {
        val localBus = MulticastLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val subscription = MessageSubscription(
            LocalFirstTestMessage(),
            receiverGroup = "first",
        )
        val secondAdmissionEntered = CountDownLatch(1)
        val releaseSecondAdmission = CountDownLatch(1)
        val firstDispatcher = LocalReceiptDispatcher(localBus.runtimeReceiver(subscription))
        val secondDispatcher = LocalReceiptDispatcher(
            receiver = localBus.runtimeReceiver(subscription.copy(receiverGroup = "second")),
            beforeGroupKey = {
                secondAdmissionEntered.countDown()
                check(releaseSecondAdmission.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the second local admission."
                }
            },
        )
        val runtimeContext = DefaultRuntimeContext()
        val executor = Executors.newSingleThreadExecutor()

        try {
            firstDispatcher.prepare(runtimeContext).block()
            secondDispatcher.prepare(runtimeContext).block()
            firstDispatcher.start()
            secondDispatcher.start()

            val send = executor.submit {
                bus.send(LocalFirstTestMessage(id = "multicast-admitted"))
                    .block(Duration.ofSeconds(5))
            }
            secondAdmissionEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            distributedBus.sent.assert().isEmpty()
            send.isDone.assert().isFalse()

            releaseSecondAdmission.countDown()
            send.get(5, TimeUnit.SECONDS)
            distributedBus.sent.single().isLocalFirst().assert().isTrue()
        } finally {
            releaseSecondAdmission.countDown()
            firstDispatcher.quiesce()
            secondDispatcher.quiesce()
            StepVerifier.create(firstDispatcher.stopGracefully()).verifyComplete()
            StepVerifier.create(secondDispatcher.stopGracefully()).verifyComplete()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
            bus.close()
        }
    }

    @Test
    fun `one rejected multicast admission disables distributed suppression`() {
        val localBus = MulticastLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val subscription = MessageSubscription(
            LocalFirstTestMessage(),
            receiverGroup = "first",
        )
        val admittedDispatcher = LocalReceiptDispatcher(localBus.runtimeReceiver(subscription))
        val rejectedDispatcher = LocalReceiptDispatcher(
            localBus.runtimeReceiver(subscription.copy(receiverGroup = "second")),
        )
        val admittedContext = DefaultRuntimeContext()
        val rejectedContext = DefaultRuntimeContext()

        try {
            admittedDispatcher.prepare(admittedContext).block()
            rejectedDispatcher.prepare(rejectedContext).block()
            admittedDispatcher.start()
            rejectedDispatcher.start()
            rejectedContext.closeAdmissionAndDrain()

            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "partially-admitted")))
                .verifyComplete()

            admittedDispatcher.handled.get().assert().isOne()
            rejectedDispatcher.handled.get().assert().isZero()
            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            admittedDispatcher.quiesce()
            rejectedDispatcher.quiesce()
            StepVerifier.create(admittedDispatcher.stopGracefully()).verifyComplete()
            StepVerifier.create(rejectedDispatcher.stopGracefully()).verifyComplete()
            bus.close()
        }
    }

    @Test
    fun `same group chained local send does not wait for handler demand`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = localBus.runtimeReceiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )
        val handled = CountDownLatch(2)
        val dispatcher = ChainedLocalReceiptDispatcher(receiver, bus, handled)

        try {
            dispatcher.prepare(DefaultRuntimeContext()).block()
            dispatcher.start()

            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "first")))
                .verifyComplete()

            handled.await(1, TimeUnit.SECONDS).assert().isTrue()
            distributedBus.sent.map { it.id }.toSet()
                .assert().isEqualTo(setOf("first", "nested"))
            distributedBus.sent.all { it.isLocalFirst() }.assert().isTrue()
        } finally {
            dispatcher.quiesce()
            StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
            bus.close()
        }
    }

    @Test
    fun `runtime admission rejection falls back without local handled flag`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = localBus.runtimeReceiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )
        val dispatcher = LocalReceiptDispatcher(receiver)
        val runtimeContext = DefaultRuntimeContext()

        try {
            dispatcher.prepare(runtimeContext).block()
            dispatcher.start()
            runtimeContext.closeAdmissionAndDrain()

            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "rejected")))
                .verifyComplete()

            dispatcher.handled.get().assert().isZero()
            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            dispatcher.quiesce()
            StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
            bus.close()
        }
    }

    @Test
    fun `an unconnected route cannot borrow a bare physical subscriber`() {
        val managedSubscriptionEntered = CountDownLatch(1)
        val releaseManagedSubscription = CountDownLatch(1)
        val localBus = BlockingSecondSubscriptionLocalBus(
            managedSubscriptionEntered,
            releaseManagedSubscription,
        )
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val subscription = MessageSubscription(
            LocalFirstTestMessage(),
            receiverGroup = "receiver-group",
        )
        val bareSubscription = localBus.receive(subscription).subscribe()
        val receiver = localBus.runtimeReceiver(subscription)
        receiver.openProcessing()
        val executor = Executors.newSingleThreadExecutor()
        val managedSubscription = executor.submit<Disposable> {
            receiver.messages.subscribe()
        }

        try {
            managedSubscriptionEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "not-connected")))
                .verifyComplete()

            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            receiver.closeProcessing()
            releaseManagedSubscription.countDown()
            managedSubscription.get(5, TimeUnit.SECONDS).dispose()
            bareSubscription.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
            bus.close()
        }
    }

    @Test
    fun `buffered local delivery falls back when its route closes`() {
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = localBus.runtimeReceiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )
        val subscriber = ZeroDemandSubscriber<LocalFirstTestExchange>()
        receiver.messages.subscribe(subscriber)

        try {
            receiver.openProcessing()
            val send = bus.send(LocalFirstTestMessage(id = "buffered")).toFuture()

            send.isDone.assert().isFalse()
            receiver.closeProcessing()
            send.get(1, TimeUnit.SECONDS)

            subscriber.received.get().assert().isZero()
            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            subscriber.dispose()
            bus.close()
        }
    }

    @Test
    fun `route closure does not resume the sender on the lifecycle thread`() {
        val lifecycleThread = Thread.currentThread()
        val resumedOnLifecycleThread = AtomicBoolean()
        val localBus = MpscLocalBus()
        val distributedBus = RecordingDistributedBus(
            onSend = {
                resumedOnLifecycleThread.set(Thread.currentThread() === lifecycleThread)
            },
        )
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val receiver = localBus.runtimeReceiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )
        val subscriber = ZeroDemandSubscriber<LocalFirstTestExchange>()
        receiver.messages.subscribe(subscriber)

        try {
            receiver.openProcessing()
            val send = bus.send(LocalFirstTestMessage(id = "buffered")).toFuture()

            receiver.closeProcessing()
            send.get(1, TimeUnit.SECONDS)

            resumedOnLifecycleThread.get().assert().isFalse()
        } finally {
            subscriber.dispose()
            bus.close()
        }
    }

    @Test
    fun `one closed multicast route disables local handled suppression`() {
        val localBus = MulticastLocalBus()
        val distributedBus = RecordingDistributedBus()
        val bus = RecordingLocalFirstMessageBus(localBus, distributedBus)
        val subscription = MessageSubscription(
            LocalFirstTestMessage(),
            receiverGroup = "receiver-group",
        )
        val firstReceiver = localBus.runtimeReceiver(subscription)
        val secondReceiver = localBus.runtimeReceiver(subscription.copy(receiverGroup = "second"))
        val firstReceived = AtomicInteger()
        val secondReceived = AtomicInteger()
        val firstSubscription = firstReceiver.messages.subscribe { firstReceived.incrementAndGet() }
        val secondSubscription = secondReceiver.messages.subscribe { secondReceived.incrementAndGet() }

        try {
            firstReceiver.openProcessing()
            secondReceiver.openProcessing()
            firstReceiver.closeProcessing()

            StepVerifier.create(bus.send(LocalFirstTestMessage(id = "multicast")))
                .verifyComplete()

            firstReceived.get().assert().isZero()
            secondReceived.get().assert().isZero()
            distributedBus.sent.single().isLocalFirst().assert().isFalse()
        } finally {
            firstSubscription.dispose()
            secondSubscription.dispose()
            bus.close()
        }
    }

    @Test
    fun `receiver closes both routes when the first close fails`() {
        val failure = IllegalStateException("local-close")
        val localCloses = AtomicInteger()
        val distributedCloses = AtomicInteger()
        val bus = RecordingLocalFirstMessageBus(
            localBus = RecordingLocalBus(
                processingQuiescence = {
                    localCloses.incrementAndGet()
                    throw failure
                },
            ),
            distributedBus = RecordingDistributedBus(
                processingQuiescence = distributedCloses::incrementAndGet,
            ),
        )
        val receiver = bus.receiver(
            MessageSubscription(LocalFirstTestMessage(), receiverGroup = "receiver-group"),
        )

        val thrown = assertThrows<IllegalStateException> {
            receiver.closeProcessing()
        }

        thrown.assert().isSameAs(failure)
        localCloses.get().assert().isOne()
        distributedCloses.get().assert().isOne()
    }
}

private class RecordingLocalFirstMessageBus(
    override val localBus: LocalMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>,
    override val distributedBus: DistributedMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>,
) : LocalFirstMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>

private class MpscLocalBus : InMemoryMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<LocalFirstTestMessage> = {
        mpscUnicastManySink<LocalFirstTestMessage>().also {
            sink = it
        }
    }
    private lateinit var sink: Sinks.Many<LocalFirstTestMessage>
    val physicalSubscriberCount: Int
        get() = sink.currentSubscriberCount()

    override fun LocalFirstTestMessage.createExchange(): LocalFirstTestExchange =
        LocalFirstTestExchange(this)
}

private class MulticastLocalBus : InMemoryMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>() {
    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<LocalFirstTestMessage> = {
        Sinks.unsafe().many().multicast().onBackpressureBuffer()
    }

    override fun LocalFirstTestMessage.createExchange(): LocalFirstTestExchange =
        LocalFirstTestExchange(this)
}

private class BlockingSecondSubscriptionLocalBus(
    managedSubscriptionEntered: CountDownLatch,
    releaseManagedSubscription: CountDownLatch,
) : InMemoryMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>() {
    private val sink = BlockingSecondSubscriptionSink<LocalFirstTestMessage>(
        managedSubscriptionEntered,
        releaseManagedSubscription,
    )

    override val sinkSupplier: (NamedAggregate) -> Sinks.Many<LocalFirstTestMessage> = {
        sink
    }

    override fun LocalFirstTestMessage.createExchange(): LocalFirstTestExchange =
        LocalFirstTestExchange(this)
}

private class BlockingSecondSubscriptionSink<T : Any>(
    private val managedSubscriptionEntered: CountDownLatch,
    private val releaseManagedSubscription: CountDownLatch,
    private val delegate: Sinks.Many<T> =
        Sinks.unsafe().many().multicast().onBackpressureBuffer(),
) : Sinks.Many<T> by delegate {
    private val subscriptionCount = AtomicInteger()

    override fun asFlux(): Flux<T> {
        if (subscriptionCount.getAndIncrement() > 0) {
            managedSubscriptionEntered.countDown()
            check(releaseManagedSubscription.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release the managed subscription."
            }
        }
        return delegate.asFlux()
    }
}

private class RecordingLocalBus(
    private val subscribers: Int = 0,
    private val receiveFlux: Flux<LocalFirstTestExchange> = Flux.empty(),
    private val readiness: Mono<Void> = Mono.empty(),
    private val processingAdmission: () -> Unit = {},
    private val processingQuiescence: () -> Unit = {},
) : LocalMessageBus<LocalFirstTestMessage, LocalFirstTestExchange> {
    val sent: MutableList<LocalFirstTestMessage> = mutableListOf()
    val received: MutableList<MessageSubscription> = mutableListOf()
    var sendResult: (LocalFirstTestMessage) -> Mono<Void> = { Mono.empty() }

    override fun send(message: LocalFirstTestMessage): Mono<Void> =
        Mono.defer {
            sent += message
            sendResult(message)
        }

    override fun sendIfSubscribed(message: LocalFirstTestMessage): Mono<Boolean> =
        if (subscribers == 0) {
            Mono.just(false)
        } else {
            send(message).thenReturn(true)
        }

    override fun receive(subscription: MessageSubscription): Flux<LocalFirstTestExchange> {
        received += subscription
        return receiveFlux
    }

    override fun receiver(
        subscription: MessageSubscription,
    ): MessageReceiver<LocalFirstTestExchange> =
        MessageReceiver(
            messages = receive(subscription),
            readiness = readiness,
            processingAdmission = processingAdmission,
            processingQuiescence = processingQuiescence,
        )

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = subscribers
}

private class RecordingDistributedBus(
    private val receiveFlux: Flux<LocalFirstTestExchange> = Flux.empty(),
    private val readiness: Mono<Void> = Mono.empty(),
    private val processingAdmission: () -> Unit = {},
    private val processingQuiescence: () -> Unit = {},
    private val onSend: (LocalFirstTestMessage) -> Unit = {},
) : DistributedMessageBus<LocalFirstTestMessage, LocalFirstTestExchange> {
    val sent: MutableList<LocalFirstTestMessage> = mutableListOf()
    val received: MutableList<MessageSubscription> = mutableListOf()

    override fun send(message: LocalFirstTestMessage): Mono<Void> =
        Mono.fromRunnable {
            onSend(message)
            sent += message
        }

    override fun receive(subscription: MessageSubscription): Flux<LocalFirstTestExchange> {
        received += subscription
        return receiveFlux
    }

    override fun receiver(
        subscription: MessageSubscription,
    ): MessageReceiver<LocalFirstTestExchange> =
        MessageReceiver(
            messages = receive(subscription),
            readiness = readiness,
            processingAdmission = processingAdmission,
            processingQuiescence = processingQuiescence,
        )
}

private class ZeroDemandSubscriber<T : Any> : BaseSubscriber<T>() {
    val received = AtomicInteger()

    override fun hookOnSubscribe(subscription: Subscription) = Unit

    override fun hookOnNext(value: T) {
        received.incrementAndGet()
    }
}

private class LocalReceiptDispatcher(
    receiver: MessageReceiver<LocalFirstTestExchange>,
    private val beforeGroupKey: () -> Unit = {},
    override val scheduler: Scheduler = Schedulers.immediate(),
) : AggregateDispatcher<LocalFirstTestExchange>(
    messageReadiness = receiver.readiness,
    processingAdmission = receiver::openProcessing,
    processingQuiescence = receiver::closeProcessing,
) {
    override val name: String = "local-receipt-dispatcher"
    override val namedAggregate: NamedAggregate = LocalFirstTestMessage().materialize()
    override val parallelism: Int = 1
    override val messageFlux: Flux<LocalFirstTestExchange> = receiver.messages
    val handled = AtomicInteger()

    override fun LocalFirstTestExchange.toGroupKey(): Int {
        beforeGroupKey()
        return 0
    }

    override fun handleExchange(exchange: LocalFirstTestExchange): Mono<Void> =
        Mono.fromRunnable {
            handled.incrementAndGet()
        }
}

private class ChainedLocalReceiptDispatcher(
    receiver: MessageReceiver<LocalFirstTestExchange>,
    private val bus: LocalFirstMessageBus<LocalFirstTestMessage, LocalFirstTestExchange>,
    private val handled: CountDownLatch,
) : AggregateDispatcher<LocalFirstTestExchange>(
    messageReadiness = receiver.readiness,
    processingAdmission = receiver::openProcessing,
    processingQuiescence = receiver::closeProcessing,
) {
    override val name: String = "chained-local-receipt-dispatcher"
    override val namedAggregate: NamedAggregate = LocalFirstTestMessage().materialize()
    override val parallelism: Int = 1
    override val scheduler: Scheduler = Schedulers.immediate()
    override val messageFlux: Flux<LocalFirstTestExchange> = receiver.messages

    override fun LocalFirstTestExchange.toGroupKey(): Int = 0

    override fun handleExchange(exchange: LocalFirstTestExchange): Mono<Void> {
        handled.countDown()
        return if (exchange.message.id == "first") {
            bus.send(LocalFirstTestMessage(id = "nested"))
        } else {
            Mono.empty()
        }
    }
}

private class LocalFirstTestMessage(
    override val id: String = "message-id",
    override val header: Header = DefaultHeader.empty(),
    override val body: String = "body",
    override val createTime: Long = 1,
    override val aggregateName: String = "modeling_command_aggregate",
) : Message<LocalFirstTestMessage, String>,
    NamedAggregate,
    Copyable<LocalFirstTestMessage> {
    override val contextName: String = "wow-core-test"

    override fun copy(): LocalFirstTestMessage =
        LocalFirstTestMessage(
            id = id,
            header = header.copy(),
            body = body,
            createTime = createTime,
            aggregateName = aggregateName,
        )
}

private class LocalFirstTestExchange(
    override val message: LocalFirstTestMessage
) : MessageExchange<LocalFirstTestExchange, LocalFirstTestMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
    var acknowledged: Boolean = false
        private set

    override fun acknowledge(): Mono<Void> =
        Mono.fromRunnable {
            acknowledged = true
        }
}
