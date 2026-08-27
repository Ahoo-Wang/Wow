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

package me.ahoo.wow.tck.container

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class KafkaTestFixtureTest {

    @Test
    fun `should create default client id`() {
        val clientId = KafkaTestFixture().clientId("sender")

        clientId.assert().startsWith("wow_test_client_sender_")
    }

    @Test
    fun `should reject unsafe client id suffix`() {
        assertThrownBy<IllegalArgumentException> {
            KafkaTestFixture().clientId("a".repeat(16))
        }.hasMessage(
            "Kafka client id prefix 'wow-test-client_aaaaaaaaaaaaaaaa' must normalize to 1-30 lowercase letters, digits, or underscores and start with a letter.",
        )
    }
}
