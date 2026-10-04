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

package me.ahoo.wow.kafka

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.wire.WireGolden
import me.ahoo.wow.tck.wire.WireSamples
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.SenderOptions
import reactor.kafka.sender.SenderRecord
import tools.jackson.databind.node.ObjectNode

/**
 * Golden samples of the Kafka records the 9.2.x buses produce: topic, partition, timestamp, key, record headers and
 * value, for the command, domain event and state event buses with their default topic converters.
 *
 * The files under `src/test/resources/wire/v9/` were generated from 9.2.2 and are **frozen v9 wire contracts**: during a
 * rolling upgrade 9.2.x and 9.3.x nodes produce to and consume from the same topics with the same consumer groups, so
 * topic names, the partition key, the (absent) record headers and the value bytes must not change. Changing a golden
 * needs a design decision, not a re-generation.
 *
 * The record is taken from the production bus itself ([AbstractKafkaBus.encode], reached reflectively because it is
 * protected), and the golden record is decoded by the production [AbstractKafkaBus.decode], which also checks that the
 * key and topic agree with the decoded message.
 */
class KafkaWireFormatGoldenTest {

    @Test
    fun `command record keeps the v9 wire format`() {
        val bus = KafkaCommandBus(senderOptions = senderOptions(), receiverOptions = receiverOptions())
        bus.use {
            verify(it, WireSamples.commandMessage(), "kafka-command-record.json")
        }
    }

    @Test
    fun `domain event record keeps the v9 wire format`() {
        val bus = KafkaDomainEventBus(senderOptions = senderOptions(), receiverOptions = receiverOptions())
        bus.use {
            verify(it, WireSamples.domainEventStream(), "kafka-event-record.json")
        }
    }

    @Test
    fun `state event record keeps the v9 wire format`() {
        val bus = KafkaStateEventBus(senderOptions = senderOptions(), receiverOptions = receiverOptions())
        bus.use {
            verify(it, WireSamples.stateEvent(), "kafka-state-record.json")
        }
    }

    private fun verify(bus: AbstractKafkaBus<*, *>, message: Message<*, *>, golden: String) {
        val record: ProducerRecord<String, String> = bus.encodeRecord(message)
        WireGolden.assertMatches(golden, record.toGoldenJson())

        val goldenRecord = JsonSerializer.readTree(WireGolden.read(golden)) as ObjectNode
        val consumerRecord = ConsumerRecord(
            goldenRecord["topic"].asString(),
            0,
            0L,
            goldenRecord["key"].asString(),
            goldenRecord["value"].asString(),
        )
        val decoded = bus.decodeRecord(ReceiverRecord(consumerRecord, mockk(relaxed = true)))
        decoded.toJsonString().assert().isEqualTo(goldenRecord["value"].asString())
    }

    private fun ProducerRecord<String, String>.toGoldenJson(): String {
        val node = JsonSerializer.createObjectNode()
        node.put("topic", topic())
        if (partition() == null) node.putNull("partition") else node.put("partition", partition())
        node.put("timestamp", timestamp())
        node.put("key", key())
        val headers = node.putObject("headers")
        headers().forEach { headers.put(it.key(), String(it.value(), Charsets.UTF_8)) }
        node.put("value", value())
        return JsonSerializer.writerWithDefaultPrettyPrinter().writeValueAsString(node)
    }

    @Suppress("UNCHECKED_CAST")
    private fun AbstractKafkaBus<*, *>.encodeRecord(message: Message<*, *>): SenderRecord<String, String, *> {
        val encode = AbstractKafkaBus::class.java.getDeclaredMethod("encode", Message::class.java)
        encode.isAccessible = true
        return encode.invoke(this, message) as SenderRecord<String, String, *>
    }

    private fun AbstractKafkaBus<*, *>.decodeRecord(record: ReceiverRecord<String, String>): Message<*, *> {
        val decode = AbstractKafkaBus::class.java.getDeclaredMethod("decode", ReceiverRecord::class.java)
        decode.isAccessible = true
        return decode.invoke(this, record) as Message<*, *>
    }

    private fun senderOptions(): SenderOptions<String, String> =
        SenderOptions.create(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ),
        )

    private fun receiverOptions(): ReceiverOptions<String, String> =
        ReceiverOptions.create(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        )
}
