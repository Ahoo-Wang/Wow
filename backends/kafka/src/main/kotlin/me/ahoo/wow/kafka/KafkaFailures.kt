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

import me.ahoo.wow.spi.transport.TransportRefusedException
import me.ahoo.wow.spi.transport.TransportUnavailableException
import org.apache.kafka.common.InvalidRecordException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.BrokerNotAvailableException
import org.apache.kafka.common.errors.CoordinatorNotAvailableException
import org.apache.kafka.common.errors.DisconnectException
import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.LeaderNotAvailableException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.NotEnoughReplicasException
import org.apache.kafka.common.errors.RecordBatchTooLargeException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException

/**
 * Tells Kafka being unreachable, Kafka refusing a record for good and a defect apart:
 *
 * - unreachable, a [TransportUnavailableException], on which a subscription pauses its partition: a broker, leader
 *   or coordinator not available, a network failure or a disconnection, too few replicas in sync, a request that
 *   timed out; and the node's credentials or permissions refused ([AuthenticationException],
 *   [AuthorizationException]), a misconfiguration of the whole node that every entry would hit, named so;
 * - refused, a [TransportRefusedException], on which a subscription parks the aggregate at once: a record or batch
 *   beyond the broker's size limit, a topic name it does not take, a record it calls invalid;
 * - anything else stays the client's exception.
 */
internal object KafkaFailures {
    /** Whether [failure] says Kafka cannot be reached, or cannot take the request now. */
    fun isUnavailable(failure: Throwable): Boolean = when (failure) {
        is BrokerNotAvailableException,
        is CoordinatorNotAvailableException,
        is LeaderNotAvailableException,
        is NetworkException,
        is DisconnectException,
        is NotEnoughReplicasException,
        is TimeoutException,
        -> true
        else -> false
    }

    /** Whether [failure] says Kafka refused the node's credentials or its permission for the request. */
    fun isAuthFailure(failure: Throwable): Boolean =
        failure is AuthenticationException || failure is AuthorizationException

    /** Whether [failure] says the broker will never take the record as it is. */
    fun isRefused(failure: Throwable): Boolean = when (failure) {
        is RecordTooLargeException,
        is RecordBatchTooLargeException,
        is InvalidTopicException,
        is InvalidRecordException,
        -> true
        else -> false
    }

    /** [failure] as what it says ([KafkaFailures]): unavailable, refused, or as it is. */
    fun translate(failure: Throwable): Throwable = when {
        isUnavailable(failure) -> TransportUnavailableException("Kafka is unavailable; retry later.", failure)
        isAuthFailure(failure) -> TransportUnavailableException(
            "Kafka refused the node's credentials or permissions (${failure.javaClass.simpleName}: " +
                "${failure.message}); fix the node's Kafka security settings.",
            failure,
        )
        isRefused(
            failure
        ) -> TransportRefusedException("Kafka refuses the record for good: ${failure.message}", failure)
        else -> failure
    }
}
