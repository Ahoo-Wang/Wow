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

package me.ahoo.wow.messaging.transport

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A [Transport] inside one JVM with broker semantics: every consumer group on a topic receives each record, and the
 * members of a group share them round-robin. Records go through their encoded form, so a bus over it exercises the
 * same encode, decode and validation as one over Kafka or Redis.
 *
 * Nothing is retained: a record published while a topic has no open receiver is dropped, and acknowledgement is a
 * no-op. It suits tests and single-process setups that want the distributed code path. The local, object-passing
 * buses (`InMemoryCommandBus` and the event buses) remain what local-first delivery uses.
 */
class InMemoryTransport : Transport {
    private val topics = ConcurrentHashMap<String, ConcurrentHashMap<String, Group>>()
    private val sequence = AtomicLong()

    override fun send(message: TransportMessage): Mono<Void> =
        Mono.fromRunnable {
            val groups = topics[message.topic] ?: return@fromRunnable
            groups.values.forEach { group ->
                group.next()?.emit(
                    InMemoryTransportRecord(
                        topic = message.topic,
                        key = message.key,
                        payload = message.payload,
                        id = sequence.incrementAndGet().toString(),
                    ),
                )
            }
        }

    override fun open(group: String, topics: Set<String>): TransportReceiver =
        object : TransportReceiver {
            private val ready = Sinks.empty<Void>()

            override val records: Flux<TransportRecord> =
                Flux.defer {
                    val member = Member()
                    val groups = topics.map { topic ->
                        this@InMemoryTransport.topics
                            .computeIfAbsent(topic) { ConcurrentHashMap() }
                            .computeIfAbsent(group) { Group() }
                    }
                    groups.forEach { it.members.add(member) }
                    ready.tryEmitEmpty()
                    member.sink.asFlux()
                        .doFinally {
                            groups.forEach { it.members.remove(member) }
                        }
                }

            override val readiness: Mono<Void>
                get() = ready.asMono()
        }

    private class Group {
        val members = CopyOnWriteArrayList<Member>()
        private val cursor = AtomicInteger()

        fun next(): Member? {
            val snapshot = members.toArray()
            if (snapshot.isEmpty()) {
                return null
            }
            return snapshot[Math.floorMod(cursor.getAndIncrement(), snapshot.size)] as Member
        }
    }

    private class Member {
        val sink: Sinks.Many<TransportRecord> = Sinks.many().unicast().onBackpressureBuffer()

        fun emit(record: TransportRecord) {
            synchronized(this) {
                sink.tryEmitNext(record)
            }
        }
    }

    private class InMemoryTransportRecord(
        override val topic: String,
        override val key: String,
        override val payload: String,
        override val id: String,
    ) : TransportRecord {
        override fun ack(): Mono<Void> = Mono.empty()
    }
}
