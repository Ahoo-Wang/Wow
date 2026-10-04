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

package me.ahoo.wow.benchmark.infrastructure.transport

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.aggregateId
import reactor.core.Disposable
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives one distributed event bus through send → receive → acknowledge. A single receiver (one consumer, as one
 * dispatcher per aggregate has today) acknowledges every exchange and then completes the producer's pending
 * operation, so one operation is the round trip of one event stream through the broker.
 */
class TransportReceiveAckHarness(
    private val bus: DistributedDomainEventBus,
    receiverGroup: String,
) : AutoCloseable {
    private val pending = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    private val producerSequence = AtomicInteger()
    private val subscription: Disposable

    init {
        val receiver = bus.receiver(MessageSubscription(BenchmarkAggregates.namedAggregate, receiverGroup))
        subscription = receiver.messages
            .concatMap { exchange ->
                exchange.acknowledge().doFinally {
                    pending[exchange.message.aggregateId.id]?.complete(Unit)
                }
            }
            .subscribe()
        receiver.readiness.block(READY_TIMEOUT)
        receiver.openProcessing()
    }

    fun newProducer(): Producer = Producer(producerSequence.getAndIncrement())

    /**
     * Sends [stream] and waits until the receiver acknowledged it; `false` on timeout.
     */
    fun roundTrip(stream: DomainEventStream): Boolean {
        val key = stream.aggregateId.id
        val done = CompletableFuture<Unit>()
        pending[key] = done
        return try {
            bus.send(stream).block(READY_TIMEOUT)
            done.get(ROUND_TRIP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (timeout: TimeoutException) {
            false
        } finally {
            pending.remove(key)
        }
    }

    override fun close() {
        subscription.dispose()
        bus.close()
    }

    /**
     * One producer thread's ring of event streams with distinct aggregate ids.
     */
    class Producer(producer: Int) {
        private val streams = Array(RING_SIZE) { index ->
            BenchmarkEvents.singleEventStream(
                aggregateId = BenchmarkAggregates.cartMetadata.aggregateId("transport-$producer-$index"),
            )
        }
        private var cursor = 0

        fun next(): DomainEventStream {
            val stream = streams[cursor]
            cursor = (cursor + 1) % RING_SIZE
            return stream
        }
    }

    private companion object {
        const val RING_SIZE = 1024
        const val ROUND_TRIP_TIMEOUT_SECONDS = 10L
        val READY_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
