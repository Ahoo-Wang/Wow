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

import me.ahoo.wow.spi.subscription.FailureDescriber
import me.ahoo.wow.spi.subscription.FailureDescription
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.name
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.phrase
import org.apache.kafka.common.errors.ApiException
import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.protocol.Errors

/** The words of Kafka's descriptions. */
internal enum class KafkaPhrase {
    KAFKA_ERROR,
    TOPICS,
}

/**
 * The Kafka client's failures an application's handler meets on its own producer or consumer, described for the reason
 * a parked aggregate records: the protocol's error ([Errors], the client's own enumeration of what a broker answers,
 * `kafka error topic authorization failed`), and the topics an authorization or a topic's name refused, by name.
 * Never the message.
 *
 * Found by `ServiceLoader`, from this module's `META-INF/services/me.ahoo.wow.spi.subscription.FailureDescriber`.
 */
internal class KafkaFailureDescriber : FailureDescriber {
    override fun describe(failure: Throwable): FailureDescription? {
        if (failure !is ApiException) return null
        val topics = when (failure) {
            is TopicAuthorizationException -> failure.unauthorizedTopics()
            is InvalidTopicException -> failure.invalidTopics()
            else -> emptySet()
        }.sorted().mapNotNull { runCatching { name(it) }.getOrNull() }
        val parts = listOf(phrase(KafkaPhrase.KAFKA_ERROR), phrase(Errors.forException(failure))) +
            if (topics.isEmpty()) emptyList() else listOf(phrase(KafkaPhrase.TOPICS)) + topics
        return FailureDescription.of(*parts.toTypedArray())
    }
}
