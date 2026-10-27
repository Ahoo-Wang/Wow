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

import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicProducer

/**
 * A [Sink] that publishes state events to [topic] through a [TopicProducer]: each
 * record in its envelope ([EnvelopeCodec.message]), keyed by its aggregate ref, so an aggregate's records keep their
 * order in one partition, with the `wow-format`, `wow-kind` and `wow-type` headers. [write] returns once the broker
 * acknowledged every record ([TopicProducer.send]).
 */
internal class TopicSink(private val producer: TopicProducer, val topic: String) : Sink {
    init {
        require(topic.isNotBlank()) { "A topic sink names its topic." }
    }

    override suspend fun write(records: List<StateEventRecord>) {
        if (records.isEmpty()) {
            return
        }
        producer.send(topic, records.map(EnvelopeCodec::message))
    }

    override fun toString(): String = "TopicSink($topic)"
}
