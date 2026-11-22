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
import me.ahoo.wow.spi.subscription.FailureDescriber
import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.junit.jupiter.api.Test

/** #6588: a Kafka client's failure parks with the protocol's error and the topics it names, never its message. */
class KafkaFailureDescriberTest {
    private val describer = KafkaFailureDescriber()

    /** What [failure] is described as; null when it is not. */
    private fun told(failure: Throwable): String? = describer.describe(failure)?.text

    @Test
    fun `the module should register its describer for ServiceLoader, as a node finds it`() {
        java.util.ServiceLoader.load(FailureDescriber::class.java).map { it.javaClass.name }.assert()
            .contains(KafkaFailureDescriber::class.java.name)
    }

    @Test
    fun `a failure of the protocol should be its error, and the topics it refused by name`() {
        told(TopicAuthorizationException(setOf("orders", "audit"))).assert()
            .isEqualTo("kafka error topic authorization failed topics 'audit' 'orders'")
        told(InvalidTopicException("bad", setOf("a b", "orders"))).assert()
            .isEqualTo("kafka error invalid topic exception topics 'orders'")
        told(RecordTooLargeException("The record for alice.smith@example.com is too large")).assert()
            .isEqualTo("kafka error message too large")
        told(TimeoutException("alice.smith@example.com")).assert()
            .isEqualTo("kafka error request timed out")
    }

    @Test
    fun `another failure should be its class name alone`() {
        told(IllegalStateException("alice.smith@example.com")).assert()
            .isNull()
    }
}
