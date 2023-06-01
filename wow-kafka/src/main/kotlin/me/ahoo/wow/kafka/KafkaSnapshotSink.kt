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

import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotSink
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asPrettyJson
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderOptions
import reactor.kafka.sender.SenderRecord

class KafkaSnapshotSink(
    private val topicConverter: SnapshotTopicConverter = DefaultSnapshotTopicConverter(),
    senderOptions: SenderOptions<String, String>,
) : SnapshotSink {
    companion object {
        private val log = LoggerFactory.getLogger(KafkaSnapshotSink::class.java)
    }

    private val sender: KafkaSender<String, String> = KafkaSender.create(senderOptions)
    override fun sink(snapshot: Snapshot<*>): Mono<Void> {
        if (log.isDebugEnabled) {
            log.debug("Sink {}.", snapshot.asPrettyJson())
        }

        val senderRecord = encode(snapshot)
        return sender.send(Mono.just(senderRecord))
            .doOnNext {
                @Suppress("ThrowingExceptionsWithoutMessageOrCause")
                val error = it.exception()
                if (error != null) {
                    throw error
                }
            }
            .next()
            .then()
    }

    private fun encode(snapshot: Snapshot<*>): SenderRecord<String, String, String> {
        val producerRecord = ProducerRecord(
            /* topic = */
            topicConverter.convert(snapshot.aggregateId),
            /* partition = */
            null,
            /* timestamp = */
            snapshot.snapshotTime,
            /* key = */
            snapshot.aggregateId.id,
            /* value = */
            snapshot.asJsonString(),
        )
        return SenderRecord.create(producerRecord, snapshot.aggregateId.id)
    }

    override fun close() {
        if (log.isInfoEnabled) {
            log.info("[${this.javaClass.simpleName}] Close KafkaSender.")
        }
        sender.close()
    }
}
