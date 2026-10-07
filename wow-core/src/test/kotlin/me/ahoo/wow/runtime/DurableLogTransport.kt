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

package me.ahoo.wow.runtime

import me.ahoo.wow.messaging.transport.Transport
import me.ahoo.wow.messaging.transport.TransportMessage
import me.ahoo.wow.messaging.transport.TransportReceiver
import me.ahoo.wow.messaging.transport.TransportRecord
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A one-member, one-partition broker log that behaves like Kafka for the receiving side: records are pulled only on
 * demand, in log order, and the group's committed offset is the end of the acknowledged prefix (an out-of-order
 * acknowledgement is not committed past an earlier unacknowledged record).
 */
internal class DurableLogTransport : Transport {
    private val log = CopyOnWriteArrayList<TransportMessage>()
    private val nextDelivery = AtomicInteger()
    private val sink = AtomicReference<FluxSink<TransportRecord>?>()
    private val wip = AtomicInteger()

    /** Offsets of the acknowledged records. */
    val acknowledged: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    val size: Int
        get() = log.size

    /** How many records the receiver pulled. */
    val delivered: Int
        get() = nextDelivery.get()

    /** The group's committed offset: every record before it is acknowledged. */
    fun committedOffset(): Int {
        var offset = 0
        while (offset in acknowledged) {
            offset++
        }
        return offset
    }

    override fun send(message: TransportMessage): Mono<Void> =
        Mono.fromRunnable {
            log += message
            drain()
        }

    override fun open(group: String, topics: Set<String>): TransportReceiver =
        object : TransportReceiver {
            override val records: Flux<TransportRecord> =
                Flux.create { emitter ->
                    check(sink.compareAndSet(null, emitter)) { "DurableLogTransport supports one member." }
                    emitter.onRequest { drain() }
                    emitter.onDispose { sink.set(null) }
                }

            override val durable: Boolean
                get() = true
        }

    private fun drain() {
        if (wip.getAndIncrement() != 0) {
            return
        }
        do {
            val emitter = sink.get()
            while (emitter != null && emitter.requestedFromDownstream() > 0 && nextDelivery.get() < log.size) {
                val offset = nextDelivery.getAndIncrement()
                emitter.next(Record(offset, log[offset]))
            }
        } while (wip.decrementAndGet() != 0)
    }

    private inner class Record(
        private val offset: Int,
        private val message: TransportMessage,
    ) : TransportRecord {
        override val topic: String
            get() = message.topic
        override val key: String
            get() = message.key
        override val payload: String
            get() = message.payload
        override val id: String
            get() = offset.toString()

        override fun ack(): Mono<Void> = Mono.fromRunnable { acknowledged += offset }
    }
}
