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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KafkaReceiverPolicyTest {

    @Test
    fun `should use safe receiver defaults`() {
        val policy = KafkaReceiverPolicy()

        policy.prefetchBatches.assert().isEqualTo(1)
        policy.maxDeferredCommits.assert().isEqualTo(500)
    }

    @Test
    fun `should reject unsafe receiver settings`() {
        assertThrows<IllegalArgumentException> {
            KafkaReceiverPolicy(prefetchBatches = 0)
        }.message.assert().isEqualTo("prefetchBatches must be greater than 0.")

        assertThrows<IllegalArgumentException> {
            KafkaReceiverPolicy(maxDeferredCommits = 0)
        }.message.assert().contains("must be greater than 0")
    }
}
