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

package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.infra.sink.ConcurrentManySink
import me.ahoo.wow.infra.sink.MpscUnicastManySink
import me.ahoo.wow.infra.sink.prepareConcurrentSink
import me.ahoo.wow.messaging.MessageSubscription
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class InMemoryCommandBusAtomicSinkTest {

    @Test
    fun `default supplier should create the exact atomic mpsc sink`() {
        val message = TestCommandMessage()
        val sink = InMemoryCommandBus().sinkSupplier(message)
        val many: Sinks.Many<*> = sink

        (many is MpscUnicastManySink<*>).assert().isTrue()
        (many is ConcurrentManySink<*>).assert().isFalse()
        sink.prepareConcurrentSink().assert().isSameAs(sink)
    }

    @Test
    fun `four producers should send every command once through the bus and preserve producer order`() {
        val producerCount = 4
        val valuesPerProducer = 100
        val total = producerCount * valuesPerProducer
        val messages = (0 until producerCount).associateWith { producer ->
            (0 until valuesPerProducer).map { sequence ->
                TestCommandMessage(id = "$producer-$sequence")
            }
        }
        val namedAggregate = messages.getValue(0).first()
        val bus = InMemoryCommandBus()
        val received = bus.receive(MessageSubscription(namedAggregate))
            .take(total.toLong())
            .collectList()
            .toFuture()
        bus.subscriberCount(namedAggregate).assert().isEqualTo(1)

        val executor = Executors.newFixedThreadPool(producerCount)
        val ready = CountDownLatch(producerCount)
        val start = CountDownLatch(1)
        try {
            val futures = messages.map { (_, producerMessages) ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    producerMessages.forEach { message ->
                        bus.send(message).block(Duration.ofSeconds(5))
                    }
                }
            }
            ready.await(5, TimeUnit.SECONDS).assert().isTrue()
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            val exchanges = checkNotNull(received.get(10, TimeUnit.SECONDS))
            val actualIds = exchanges.map { it.message.id }
            val expectedIds = messages.values.flatten().map { it.id }
            actualIds.size.assert().isEqualTo(total)
            actualIds.toSet().assert().isEqualTo(expectedIds.toSet())
            repeat(producerCount) { producer ->
                actualIds.filter { it.startsWith("$producer-") }
                    .map { it.substringAfter('-').toInt() }
                    .assert()
                    .containsExactly(*(0 until valuesPerProducer).toList().toTypedArray())
            }
        } finally {
            start.countDown()
            received.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
            bus.close()
        }
    }

    @Test
    fun `active send should use the cached atomic sink and complete after close`() {
        val message = TestCommandMessage()
        val bus = InMemoryCommandBus()
        val onNextEntered = CountDownLatch(1)
        val releaseOnNext = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val completed = AtomicBoolean()
        val observedError = AtomicReference<Throwable?>()
        val subscription = bus.receive(MessageSubscription(message)).subscribe(
            {
                onNextEntered.countDown()
                check(releaseOnNext.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the active command delivery."
                }
            },
            {
                observedError.set(it)
                terminal.countDown()
            },
            {
                completed.set(true)
                terminal.countDown()
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val send = executor.submit { bus.send(message).block(Duration.ofSeconds(5)) }
            onNextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            (cachedSinks(bus).single() is MpscUnicastManySink<*>).assert().isTrue()
            bus.close()
            cachedSinks(bus).assert().isEmpty()
            terminal.count.assert().isEqualTo(1)

            releaseOnNext.countDown()
            send.get(5, TimeUnit.SECONDS)
            terminal.await(5, TimeUnit.SECONDS).assert().isTrue()
            completed.get().assert().isTrue()
            observedError.get().assert().isNull()
        } finally {
            releaseOnNext.countDown()
            subscription.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
            bus.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cachedSinks(bus: InMemoryCommandBus): Collection<Sinks.Many<*>> {
        val field = bus.javaClass.superclass.getDeclaredField("sinks")
        field.isAccessible = true
        val sinks = field.get(bus) as Map<*, Sinks.Many<*>>
        return sinks.values
    }
}
