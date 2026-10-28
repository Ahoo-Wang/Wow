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

import me.ahoo.wow.spi.transport.TransportUnavailableException
import org.apache.kafka.common.errors.BrokerNotAvailableException
import org.apache.kafka.common.errors.CoordinatorNotAvailableException
import org.apache.kafka.common.errors.DisconnectException
import org.apache.kafka.common.errors.LeaderNotAvailableException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.NotEnoughReplicasException
import org.apache.kafka.common.errors.TimeoutException

/**
 * Tells Kafka being unreachable from a defect: the first is a [TransportUnavailableException], on which a
 * subscription pauses its partition, the second stays the client's exception.
 *
 * Unavailable are a broker, leader or coordinator not available, a network failure or a disconnection, too few
 * replicas in sync, a request that timed out.
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

    /** [failure] as a [TransportUnavailableException] when it [isUnavailable], else as it is. */
    fun translate(failure: Throwable): Throwable =
        if (isUnavailable(failure)) {
            TransportUnavailableException("Kafka is unavailable; retry later.", failure)
        } else {
            failure
        }
}
