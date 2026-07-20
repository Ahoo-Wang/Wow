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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions

class KafkaBusCompatibilityTest {

    @Test
    fun `should preserve the four argument JVM constructors`() {
        KafkaCommandBus::class.java.getConstructor(
            CommandTopicConverter::class.java,
            SenderOptions::class.java,
            ReceiverOptions::class.java,
            ReceiverOptionsCustomizer::class.java,
        ).assert().isNotNull()
        KafkaDomainEventBus::class.java.getConstructor(
            EventStreamTopicConverter::class.java,
            SenderOptions::class.java,
            ReceiverOptions::class.java,
            ReceiverOptionsCustomizer::class.java,
        ).assert().isNotNull()
        KafkaStateEventBus::class.java.getConstructor(
            StateEventTopicConverter::class.java,
            SenderOptions::class.java,
            ReceiverOptions::class.java,
            ReceiverOptionsCustomizer::class.java,
        ).assert().isNotNull()
        AbstractKafkaBus::class.java.getConstructor(
            AggregateTopicConverter::class.java,
            SenderOptions::class.java,
            ReceiverOptions::class.java,
            ReceiverOptionsCustomizer::class.java,
        ).assert().isNotNull()
    }

    @Test
    fun `compatibility constructors should use safe receiver defaults`() {
        val buses = listOf(
            KafkaCommandBus(
                DefaultCommandTopicConverter(),
                senderOptions(),
                receiverOptions(),
                NoOpReceiverOptionsCustomizer,
            ),
            KafkaDomainEventBus(
                DefaultEventStreamTopicConverter(),
                senderOptions(),
                receiverOptions(),
                NoOpReceiverOptionsCustomizer,
            ),
            KafkaStateEventBus(
                DefaultStateEventTopicConverter(),
                senderOptions(),
                receiverOptions(),
                NoOpReceiverOptionsCustomizer,
            ),
            KafkaCommandBus(
                senderOptions = senderOptions(),
                receiverOptions = receiverOptions(),
            ),
            KafkaDomainEventBus(
                senderOptions = senderOptions(),
                receiverOptions = receiverOptions(),
            ),
            KafkaStateEventBus(
                senderOptions = senderOptions(),
                receiverOptions = receiverOptions(),
            ),
        )
        val abstractBus = CompatibilityAbstractKafkaBus(
            senderOptions = senderOptions(),
            receiverOptions = receiverOptions(),
        )

        buses.forEach(AutoCloseable::close)
        abstractBus.createDefaultReceiver(receiverOptions()).assert().isNotNull()
        abstractBus.close()
    }

    private fun senderOptions(): SenderOptions<String, String> {
        return SenderOptions.create(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ),
        )
    }

    private fun receiverOptions(): ReceiverOptions<String, String> {
        return ReceiverOptions.create(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        )
    }

    private class CompatibilityAbstractKafkaBus(
        senderOptions: SenderOptions<String, String>,
        receiverOptions: ReceiverOptions<String, String>,
    ) : AbstractKafkaBus<DomainEventStream, EventStreamExchange>(
        DefaultEventStreamTopicConverter(),
        senderOptions,
        receiverOptions,
    ) {
        override val messageType: Class<DomainEventStream>
            get() = DomainEventStream::class.java

        override fun DomainEventStream.toExchange(receiverOffset: ReceiverOffset): EventStreamExchange {
            return KafkaEventStreamExchange(this, receiverOffset)
        }

        fun createDefaultReceiver(
            receiverOptions: ReceiverOptions<String, String>,
        ): KafkaReceiver<String, String> {
            return createReceiver(receiverOptions)
        }
    }
}
