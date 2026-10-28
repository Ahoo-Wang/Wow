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

package me.ahoo.wow.spi.transport

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TransportValuesTest {
    @Test
    fun `topic and source messages are values, their bytes compared by content`() {
        val message = TopicMessage.of("k", byteArrayOf(1, 2), mapOf("h" to "v"), 3)
        message.assert().isEqualTo(TopicMessage.of("k", byteArrayOf(1, 2), mapOf("h" to "v"), 3))
            .isNotEqualTo(TopicMessage.of("k", byteArrayOf(1), mapOf("h" to "v"), 3))
        message.hashCode().assert().isEqualTo(TopicMessage.of("k", byteArrayOf(1, 2), mapOf("h" to "v"), 3).hashCode())
        message.toString().assert().isEqualTo("TopicMessage(key=k, partition=3, headers={h=v}, 2 bytes)")
        assertThrows<IllegalArgumentException> { TopicMessage.of("k", byteArrayOf(), partition = -1) }
        val read = SourceMessage.of(7, "k", emptyMap(), byteArrayOf(1))
        read.assert().isEqualTo(SourceMessage.of(7, "k", emptyMap(), byteArrayOf(1)))
            .isNotEqualTo(SourceMessage.of(8, "k", emptyMap(), byteArrayOf(1)))
        read.hashCode().assert().isEqualTo(SourceMessage.of(7, "k", emptyMap(), byteArrayOf(1)).hashCode())
        val offsets = PartitionOffsets.of(1, 4)
        offsets.assert().isEqualTo(PartitionOffsets.of(1, 4)).isNotEqualTo(PartitionOffsets.of(0, 4))
        offsets.hashCode().assert().isEqualTo(PartitionOffsets.of(1, 4).hashCode())
        offsets.toString().assert().isEqualTo("PartitionOffsets(start=1, end=4)")
        assertThrows<IllegalArgumentException> { PartitionOffsets.of(5, 4) }
    }
}
