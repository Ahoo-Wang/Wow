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

package me.ahoo.wow.kafka.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kafka.KafkaTransportProvider
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.transport.TransportConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Review r2 (configuration keys): `wow.kafka.poll-timeout` of zero is refused by `KafkaOptions`' bare
 * `IllegalArgumentException` ("The poll timeout is positive."), which names no key, where `operate/configuration.md`
 * promises a `WowConfigurationException` naming the key and its value.
 */
class R2KafkaOptionRefusalTest {
    @Test
    fun `a poll timeout that is not positive should be refused naming its key`() {
        val config = TransportConfig.of(
            "kafka",
            "kafka",
            mapOf("bootstrap-servers" to "localhost:9092", "poll-timeout" to "0s"),
        )
        val failure = assertThrows<WowConfigurationException> { KafkaTransportProvider.options(config) }
        failure.key.assert().endsWith("poll-timeout")
    }
}
