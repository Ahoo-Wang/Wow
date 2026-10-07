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

import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.runtime.RuntimeResource
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * A message broker as Wow uses it: strings in, strings out.
 *
 * A transport knows nothing about Wow messages. [TransportMessageBus] names topics, encodes, decodes, validates and
 * builds exchanges; a transport only moves [TransportMessage]s to topics and hands [TransportRecord]s back to a
 * consumer group. Kafka, Redis Streams and [InMemoryTransport] implement it; a new broker implements it once and gets
 * the command, event-stream and state-event buses from [TransportCommandBus], [TransportDomainEventBus] and
 * [TransportStateEventBus].
 */
@WowSpi
interface Transport : AutoCloseable {
    /**
     * Publishes [message] to its topic. The returned [Mono] completes once the broker has accepted it.
     */
    fun send(message: TransportMessage): Mono<Void>

    /**
     * Joins consumer [group] on [topics]. Each call is one logical consumer: members of one group share the records,
     * different groups each receive all of them.
     */
    fun open(group: String, topics: Set<String>): TransportReceiver

    /**
     * What the [me.ahoo.wow.runtime.WowRuntime] closes after its dispatchers stop, within the same shutdown deadline
     * (for example a producer that must flush), or [RuntimeResource.NONE].
     */
    val runtimeResource: RuntimeResource
        get() = RuntimeResource.NONE

    override fun close() = Unit
}

/**
 * One outbound record. Records with one [key] stay in order; [timestamp] is the message's creation time in epoch
 * milliseconds. A backend writes what it supports: Kafka writes all of it (the record's partition is its key's),
 * Redis Streams only [topic] and [payload].
 */
@WowSpi
data class TransportMessage(
    val topic: String,
    val key: String,
    val payload: String,
    val timestamp: Long,
)

/**
 * One received record. [ack] confirms it to the broker (Kafka offset commit, Redis `XACK`); a record never acknowledged
 * is left to the broker's own redelivery (a Kafka offset that is never committed, a Redis entry that stays pending).
 */
@WowSpi
interface TransportRecord {
    val topic: String

    /** The record key; `null` on a backend without keys, or on a keyed record published without one. */
    val key: String?

    /**
     * Whether the backend has record keys. A keyed record's [key] must equal the decoded message's aggregate ID (a
     * missing key does not); Redis Streams records are not keyed.
     */
    val keyed: Boolean
        get() = true

    /** The encoded message, or `null` when the backend record carries none. */
    val payload: String?

    /** The backend position of this record, for logs (Kafka `partition-offset`, a Redis entry ID). */
    val id: String

    fun ack(): Mono<Void>
}

/**
 * The consumer [Transport.open] returns.
 *
 * [records] must be subscribed before [readiness]. [readiness] completes once records published from then on can no
 * longer be missed (Kafka: partitions assigned and their positions anchored; Redis: the consumer groups exist), or
 * fails when that setup fails. [openProcessing] starts consumption on a transport that holds it back until the runtime
 * is ready; [close] revokes that admission before the subscription is cancelled. Both are prompt and idempotent.
 */
@WowSpi
interface TransportReceiver {
    val records: Flux<TransportRecord>

    val readiness: Mono<Void>
        get() = Mono.empty()

    /**
     * Whether a record this receiver has not handed over stays with the broker for the group: the consumer pulls
     * [records] only on demand, and what it never delivers, or delivers but never acknowledges, another member
     * receives (Kafka, Redis Streams). On a graceful stop the runtime stops requesting from a durable receiver first,
     * so sustained traffic cannot keep it from becoming idle; a receiver that is not durable (the default) is drained
     * instead, since what it does not deliver is lost.
     */
    val durable: Boolean
        get() = false

    fun openProcessing() = Unit

    fun close() = Unit
}
