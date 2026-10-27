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

class TopicDurabilityTest {
    @Test
    fun `a topic's durability is made by of, equal by its fields`() {
        val durability = TopicDurability.of(3, 2)
        durability.assert().isEqualTo(
            durability
        ).isEqualTo(TopicDurability.of(3, 2)).isNotEqualTo(TopicDurability.of(1, 1))
        durability.hashCode().assert().isEqualTo(TopicDurability.of(3, 2).hashCode())
        durability.toString().assert().isEqualTo("TopicDurability(replicationFactor=3, minInsyncReplicas=2)")
    }
}
