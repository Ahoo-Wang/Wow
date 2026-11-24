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

package me.ahoo.wow.runtime.sink

import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeHeaders
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer

/**
 * A [Sink] that publishes state events to [topic] through a [TopicProducer]: each record in its envelope
 * ([EnvelopeCodec.message]), keyed by its aggregate ref, with the `wow-format`, `wow-kind` and `wow-type` headers.
 * [write] returns once the broker acknowledged every record ([TopicProducer.send]).
 *
 * Each record goes to the partition the context's log gives its aggregate ([Partitioner.partitionOf] over the log's
 * [partitions]), as a command does in its command topic, never to the one the broker's partitioner picks from the key:
 * an aggregate's records stay in one partition, in order, whatever partitions the topic gains later, which receive
 * nothing. Each carries the log's count in the `wow-partitions` header ([EnvelopeHeaders.PARTITIONS]), so a reader
 * tells the records placed so from those its key's hash placed. The topic has at least the log's partitions, which
 * the node checks at its start.
 */
internal class TopicSink(private val producer: TopicProducer, val topic: String, private val partitions: Int) : Sink {
    init {
        require(topic.isNotBlank()) { "A topic sink names its topic." }
        Partitioner.requirePartitions(partitions)
    }

    override suspend fun write(records: List<StateEventRecord>) {
        if (records.isEmpty()) {
            return
        }
        val messages = records.map { record ->
            val message = EnvelopeCodec.message(record)
            TopicMessage.of(
                message.key,
                message.value,
                message.headers + (EnvelopeHeaders.PARTITIONS to partitions.toString()),
                Partitioner.partitionOf(record.ref, partitions),
            )
        }
        producer.send(topic, messages)
    }

    override fun toString(): String = "TopicSink($topic)"
}
