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
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.apache.kafka.common.errors.BrokerNotAvailableException
import org.apache.kafka.common.errors.CoordinatorNotAvailableException
import org.apache.kafka.common.errors.DisconnectException
import org.apache.kafka.common.errors.LeaderNotAvailableException
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.NotEnoughReplicasException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/** Kafka's connection failures are systemic (decision D243); the rest it leaves to the runtime. */
class KafkaFailureClassifierTest {
    @Test
    fun `connection failures are systemic, others unknown to it`() {
        val classifier = KafkaFailureClassifier()
        listOf(
            BrokerNotAvailableException("down"),
            CoordinatorNotAvailableException("down"),
            LeaderNotAvailableException("electing"),
            NetworkException("reset"),
            DisconnectException("gone"),
            NotEnoughReplicasException("1 of 3"),
            TimeoutException("Expiring 1 record"),
        ).forEach { classifier.classify(it).assert().isEqualTo(FailureClass.SYSTEMIC) }
        classifier.classify(RecordTooLargeException("too large")).assert().isNull()
    }

    @Test
    fun `the runtime finds it on the class path`() {
        ServiceLoader.load(FailureClassifier::class.java).map { it.javaClass.name }.assert()
            .contains(KafkaFailureClassifier::class.java.name)
    }
}
