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

import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Mono
import reactor.kafka.receiver.ReceiverRecord

data class KafkaRecordDecodeFailure(
    val record: ReceiverRecord<String, String>,
    val cause: Exception,
)

fun interface KafkaRecordDecodeFailureHandler {
    /**
     * Handles a record that cannot be decoded or violates transport invariants.
     *
     * Completing the returned [Mono] authorizes the bus to acknowledge and skip the record.
     * Emitting an error leaves the record unacknowledged and terminates the receive stream.
     */
    fun handle(failure: KafkaRecordDecodeFailure): Mono<Void>
}

class KafkaRecordDecodeException(
    failure: KafkaRecordDecodeFailure,
) : RuntimeException(
    "Failed to decode Kafka record [topic=${failure.record.topic()}, " +
        "partition=${failure.record.partition()}, offset=${failure.record.offset()}, " +
        "cause=${failure.cause.javaClass.name}].",
)

object FailKafkaRecordDecodeFailureHandler : KafkaRecordDecodeFailureHandler {
    override fun handle(failure: KafkaRecordDecodeFailure): Mono<Void> {
        return Mono.error(KafkaRecordDecodeException(failure))
    }
}

object AcknowledgeKafkaRecordDecodeFailureHandler : KafkaRecordDecodeFailureHandler {
    private val log = KotlinLogging.logger {}

    override fun handle(failure: KafkaRecordDecodeFailure): Mono<Void> {
        return Mono.fromRunnable {
            log.error {
                "Skip undecodable Kafka record [topic=${failure.record.topic()}, " +
                    "partition=${failure.record.partition()}, offset=${failure.record.offset()}, " +
                    "cause=${failure.cause.javaClass.name}]."
            }
        }
    }
}
