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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.kafka.receiver.ReceiverRecord
import reactor.kotlin.test.test

class KafkaRecordDecodeFailureHandlerTest {

    @Test
    fun `fail handler should expose metadata without payload`() {
        val failure = failure()

        FailKafkaRecordDecodeFailureHandler.handle(failure)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(KafkaRecordDecodeException::class.java)
                it.message.assert()
                    .contains("topic=topic")
                    .contains("partition=1")
                    .contains("offset=2")
                    .doesNotContain("secret")
            }
            .verify()
    }

    @Test
    fun `acknowledge handler should complete`() {
        AcknowledgeKafkaRecordDecodeFailureHandler.handle(failure())
            .test()
            .verifyComplete()
    }

    private fun failure(): KafkaRecordDecodeFailure {
        val record = mockk<ReceiverRecord<String, String>>()
        every { record.topic() } returns "topic"
        every { record.partition() } returns 1
        every { record.offset() } returns 2
        every { record.value() } returns "secret"
        return KafkaRecordDecodeFailure(record, IllegalArgumentException("secret"))
    }
}
