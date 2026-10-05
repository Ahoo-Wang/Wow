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

package me.ahoo.wow.redis.bus

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.messaging.transport.TransportDecodeFailure
import me.ahoo.wow.messaging.transport.TransportDecodeFailureAction
import me.ahoo.wow.messaging.transport.TransportRecord
import me.ahoo.wow.messaging.transport.TransportRecordMismatchException
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class RedisRecordDecodeFailureHandlerTest {

    @Test
    fun `reports the reason and leaves the record pending`() {
        reasonOf(payload = null, cause = IllegalArgumentException())
            .assert().isEqualTo(RedisRecordDecodeFailureReason.MISSING_MESSAGE_FIELD)
        reasonOf(payload = "{}", cause = TransportRecordMismatchException("topic"))
            .assert().isEqualTo(RedisRecordDecodeFailureReason.TOPIC_MISMATCH)
        reasonOf(payload = "not-json", cause = IllegalStateException())
            .assert().isEqualTo(RedisRecordDecodeFailureReason.DESERIALIZATION_FAILED)
    }

    private fun reasonOf(payload: String?, cause: Exception): RedisRecordDecodeFailureReason {
        val observations = mutableListOf<RedisMessageBusObservation>()
        val record = object : TransportRecord {
            override val topic: String = "topic"
            override val key: String? = null
            override val keyed: Boolean = false
            override val payload: String? = payload
            override val id: String = "1-0"

            override fun ack(): Mono<Void> = Mono.empty()
        }
        RedisRecordDecodeFailureHandler { observations += it }
            .handle(TransportDecodeFailure(record, "group", CommandMessage::class.java, cause))
            .test()
            .expectNext(TransportDecodeFailureAction.LEAVE_PENDING)
            .verifyComplete()
        val observation = observations.single() as RedisMessageBusObservation.RecordDecodeFailed
        observation.consumerGroup.assert().isEqualTo("group")
        return observation.reason
    }
}
