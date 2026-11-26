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

package me.ahoo.wow.benchmark.load

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ResourcesTest {
    @Test
    fun `the topic's end offsets should be kept per partition and summed per topic`() {
        val before = Resources.kafkaOffsetsOf("wow.transfer.account.state:0:10\nwow.transfer.account.state:1:4\n")
        val after = Resources.kafkaOffsetsOf(
            "WARN some noise: from the tool\nwow.transfer.account.state:0:25\nwow.transfer.account.state:1:4\n" +
                "wow.transfer.account.state:2:3\nother:0:7\n",
        )

        after.assert().isEqualTo(
            mapOf("other" to mapOf(0 to 7L), "wow.transfer.account.state" to mapOf(0 to 25L, 1 to 4L, 2 to 3L)),
        )
        val delta = Resources.kafkaDelta(before, after)
        delta.assert().isEqualTo(
            mapOf("other" to mapOf(0 to 7L), "wow.transfer.account.state" to mapOf(0 to 15L, 1 to 0L, 2 to 3L)),
        )
        Resources.perTopic(delta).assert().isEqualTo(mapOf("other" to 7L, "wow.transfer.account.state" to 18L))
    }
}
