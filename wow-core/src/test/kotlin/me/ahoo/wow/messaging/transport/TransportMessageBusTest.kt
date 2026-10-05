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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TransportMessageBusTest {

    @Test
    fun `encode writes the topic, aggregate id key, creation time and JSON and makes the message read-only`() {
        val namings = AtomicInteger()
        val bus = TransportCommandBus(RecordingTransport(), countingNaming(namings))
        val message = command()
        val second = command()

        val encoded = bus.encode(message)
        bus.encode(second)

        encoded.assert().isEqualTo(
            TransportMessage(
                topic = "topic:${message.contextName}.${message.aggregateName}",
                key = message.aggregateId.id,
                payload = message.toJsonString(),
                timestamp = message.createTime,
            ),
        )
        message.isReadOnly.assert().isTrue()
        namings.get().assert().isEqualTo(1)
    }

    @Test
    fun `send hands the encoded message to the transport`() {
        val transport = RecordingTransport()
        val bus = TransportCommandBus(transport, NAMING)
        val message = command()

        bus.send(message).test().verifyComplete()

        transport.sent.assert().containsExactly(bus.encode(message))
    }

    @Test
    fun `decode returns a read-only message and accepts a backend without keys`() {
        val bus = TransportCommandBus(RecordingTransport(), NAMING)
        val message = command()

        val decoded = bus.decode(record(bus, message, key = null, keyed = false))

        decoded.id.assert().isEqualTo(message.id)
        decoded.isReadOnly.assert().isTrue()
    }

    @Test
    fun `decode rejects a missing payload, a wrong key and a wrong topic`() {
        val bus = TransportCommandBus(RecordingTransport(), NAMING)
        val message = command()

        assertThrows<IllegalArgumentException> {
            bus.decode(record(bus, message, payload = null))
        }.message.assert().isEqualTo("Transport record has no payload.")
        assertThrows<TransportRecordMismatchException> {
            bus.decode(record(bus, message, key = "wrong-key"))
        }.message.assert().isEqualTo("Transport record key does not match the decoded aggregate id.")
        assertThrows<TransportRecordMismatchException> {
            bus.decode(record(bus, message, key = null))
        }.message.assert().isEqualTo("Transport record key does not match the decoded aggregate id.")
        assertThrows<TransportRecordMismatchException> {
            bus.decode(record(bus, message, topic = "wrong.topic"))
        }.message.assert().isEqualTo("Transport record topic does not match the decoded aggregate.")
    }

    @Test
    fun `receiver opens the subscription's group and topics and acknowledges through the record`() {
        val message = command()
        val transport = RecordingTransport()
        val bus = TransportCommandBus(transport, NAMING)
        val record = record(bus, message)
        transport.records = Flux.just(record)

        bus.receiver(MessageSubscription(message, "group")).openedMessages()
            .flatMap { exchange ->
                exchange.message.id.assert().isEqualTo(message.id)
                exchange.acknowledge()
            }
            .test()
            .verifyComplete()

        transport.opened.assert().containsExactly("group" to setOf(bus.topicOf(message)))
        record.acks.get().assert().isEqualTo(1)
    }

    @Test
    fun `a failing decode fails the stream and the readiness without exposing the payload`() {
        val message = command()
        val transport = RecordingTransport(readiness = Mono.never())
        val bus = TransportCommandBus(transport, NAMING)
        val record = record(bus, message, payload = "secret")
        transport.records = Flux.just(record)

        val receiver = bus.receiver(MessageSubscription(message, "group"))
        receiver.openedMessages()
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(TransportDecodeException::class.java)
                it.message.assert().contains("topic=${record.topic}").contains("group=group").doesNotContain("secret")
                it.cause.assert().isNull()
            }
            .verify()
        receiver.readiness.test()
            .expectError(TransportDecodeException::class.java)
            .verify(Duration.ofSeconds(1))
        record.acks.get().assert().isEqualTo(0)
    }

    @Test
    fun `a decode failure before readiness fails the readiness with the decode error, not the cancellation`() {
        val message = command()
        val transport = RecordingTransport()
        val bus = TransportCommandBus(transport, NAMING)
        val transportReadiness = Sinks.empty<Void>()
        transport.readiness = transportReadiness.asMono()
        transport.records = Flux.just<TransportRecord>(record(bus, message, payload = "not-json"))
            .concatWith(Flux.never())
            .doOnCancel {
                transportReadiness.tryEmitError(CancellationException("receiver initialization was cancelled."))
            }

        val receiver = bus.receiver(MessageSubscription(message, "group"))
        receiver.openedMessages()
            .test()
            .expectError(TransportDecodeException::class.java)
            .verify(Duration.ofSeconds(1))
        receiver.readiness.test()
            .expectError(TransportDecodeException::class.java)
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `memoized naming stops caching beyond its bound`() {
        val namings = AtomicInteger()
        val naming = countingNaming(namings).memoized(maxAggregates = 1)
        val first = command()
        val other = "other.aggregate".toNamedAggregate()

        repeat(2) { naming.topicOf(first) }
        repeat(2) { naming.topicOf(other) }

        namings.get().assert().isEqualTo(3)
        naming.topicOf(other).assert().isEqualTo("topic:other.aggregate")
    }

    @Test
    fun `an acknowledged decode failure is acknowledged and skipped`() {
        assertDecodeFailureAction(TransportDecodeFailureAction.ACKNOWLEDGE, expectedAcks = 1)
        assertDecodeFailureAction(TransportDecodeFailureAction.LEAVE_PENDING, expectedAcks = 0)
    }

    @Test
    fun `the built-in acknowledge handler acknowledges`() {
        val bus = TransportCommandBus(RecordingTransport(), NAMING)
        val failure = TransportDecodeFailure(record(bus, command()), "group", CommandMessage::class.java, Exception())
        TransportDecodeFailureHandler.ACKNOWLEDGE.handle(failure).test()
            .expectNext(TransportDecodeFailureAction.ACKNOWLEDGE)
            .verifyComplete()
    }

    @Test
    fun `the bus closes its transport and exposes its runtime resource`() {
        val transport = RecordingTransport()
        val bus = TransportCommandBus(transport, NAMING)
        bus.runtimeResource.assert().isSameAs(transport.runtimeResource)
        bus.close()
        transport.closed.assert().isTrue()
    }

    @Test
    fun `in-memory transport delivers to every group and round-robin within a group`() {
        val transport = InMemoryTransport()
        val first = transport.open("a", setOf("t"))
        val second = transport.open("a", setOf("t"))
        val other = transport.open("b", setOf("t"))
        val firstRecords = first.records.take(2).map { it.payload!! }.collectList().toFuture()
        val secondRecords = second.records.take(2).map { it.payload!! }.collectList().toFuture()
        val otherRecords = other.records.take(4).map { it.payload!! }.collectList().toFuture()
        Flux.merge(first.readiness, second.readiness, other.readiness).then().block(Duration.ofSeconds(1))

        transport.send(TransportMessage("unknown", "k", "p", 0)).block(Duration.ofSeconds(1))
        Flux.range(0, 4)
            .concatMap { transport.send(TransportMessage("t", "k", "p$it", 0)) }
            .then()
            .block(Duration.ofSeconds(1))

        (firstRecords.get(1, TimeUnit.SECONDS).orEmpty() + secondRecords.get(1, TimeUnit.SECONDS).orEmpty())
            .assert().containsExactlyInAnyOrder("p0", "p1", "p2", "p3")
        otherRecords.get(1, TimeUnit.SECONDS).assert().containsExactly("p0", "p1", "p2", "p3")
    }

    private fun assertDecodeFailureAction(action: TransportDecodeFailureAction, expectedAcks: Int) {
        val message = command()
        val transport = RecordingTransport()
        val bus = TransportCommandBus(transport, NAMING) { Mono.just(action) }
        val undecodable = record(bus, message, payload = "not-json")
        val valid = record(bus, message)
        transport.records = Flux.just(undecodable, valid)

        bus.receiver(MessageSubscription(message, "group")).openedMessages()
            .test()
            .expectNextCount(1)
            .verifyComplete()

        undecodable.acks.get().assert().isEqualTo(expectedAcks)
    }

    private fun command(): CommandMessage<*> =
        MockCreateAggregate(id = generateGlobalId(), data = generateGlobalId()).toCommandMessage()

    private fun record(
        bus: TransportCommandBus,
        message: CommandMessage<*>,
        topic: String = bus.topicOf(message),
        key: String? = message.aggregateId.id,
        payload: String? = message.toJsonString(),
        keyed: Boolean = true,
    ) = TestRecord(topic, key, payload, keyed)

    private fun countingNaming(counter: AtomicInteger) = TopicNaming {
        counter.incrementAndGet()
        "topic:${it.contextName}.${it.aggregateName}"
    }

    class TestRecord(
        override val topic: String,
        override val key: String?,
        override val payload: String?,
        override val keyed: Boolean = true,
    ) : TransportRecord {
        val acks = AtomicInteger()
        override val id: String = "1"

        override fun ack(): Mono<Void> = Mono.fromRunnable { acks.incrementAndGet() }
    }

    private class RecordingTransport(
        var readiness: Mono<Void> = Mono.empty(),
    ) : Transport {
        val sent = mutableListOf<TransportMessage>()
        val opened = mutableListOf<Pair<String, Set<String>>>()
        var records: Flux<TransportRecord> = Flux.empty()
        var closed = false

        override fun send(message: TransportMessage): Mono<Void> = Mono.fromRunnable { sent += message }

        override fun open(group: String, topics: Set<String>): TransportReceiver {
            opened += group to topics
            val records = records
            val readiness = readiness
            return object : TransportReceiver {
                override val records: Flux<TransportRecord> = records
                override val readiness: Mono<Void> = readiness
            }
        }

        override fun close() {
            closed = true
        }
    }

    companion object {
        private val NAMING = TopicNaming { "${it.contextName}.${it.aggregateName}" }
    }
}
