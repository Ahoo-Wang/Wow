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

import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.apache.kafka.common.errors.BrokerNotAvailableException
import org.apache.kafka.common.errors.CoordinatorNotAvailableException
import org.apache.kafka.common.errors.DisconnectException
import org.apache.kafka.common.errors.LeaderNotAvailableException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.NotEnoughReplicasException
import org.apache.kafka.common.errors.TimeoutException

/**
 * Classifies Kafka's connection failures as systemic, for a handler that sends to Kafka itself: a
 * broker, leader or coordinator not available, a network failure or a disconnection, too few replicas in sync, a
 * request that timed out. Every aggregate of the partition would fail the same way, so the subscription pauses it.
 *
 * Found by [java.util.ServiceLoader] (`META-INF/services`) when this backend is on the class path.
 */
internal class KafkaFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is BrokerNotAvailableException,
        is CoordinatorNotAvailableException,
        is LeaderNotAvailableException,
        is NetworkException,
        is DisconnectException,
        is NotEnoughReplicasException,
        is TimeoutException,
        -> FailureClass.SYSTEMIC
        else -> null
    }
}
