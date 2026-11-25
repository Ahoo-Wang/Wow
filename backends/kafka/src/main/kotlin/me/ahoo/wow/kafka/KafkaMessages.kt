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

import me.ahoo.wow.spi.transport.SourceMessage
import org.apache.kafka.clients.consumer.ConsumerRecord

/** What a record of no value is read as: no bytes, never shared with a caller that might write it. */
private val NO_VALUE = ByteArray(0)

/**
 * [record] as the message a source reads. A record without a value (a tombstone another producer on the topic, or a
 * tool that deletes keys, wrote) is read as one of no bytes: no Wow envelope, so the read of its own partition fails
 * naming its offset, as a record of another producer does (`operate/kafka.md`, a record Wow cannot read), while the
 * topic's other partitions read on. Read as Kafka's null, it would fail the consumer that every partition of the topic
 * shares.
 */
internal fun sourceMessageOf(record: ConsumerRecord<String, ByteArray>): SourceMessage =
    SourceMessage.of(record.offset(), record.key(), KafkaHeaders.of(record.headers()), record.value() ?: NO_VALUE)
