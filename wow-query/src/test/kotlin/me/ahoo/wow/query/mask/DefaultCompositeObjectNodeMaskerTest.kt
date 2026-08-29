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

package me.ahoo.wow.query.mask

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

class DefaultCompositeObjectNodeMaskerTest {
    @Test
    fun `composite should apply object node maskers in order`() {
        val first = masker("first", "1")
        val second = masker("second", "2")
        val composite = DefaultCompositeObjectNodeMasker(listOf(first, second))

        val result = composite.mask(JsonSerializer.createObjectNode())

        result.properties().map { it.key }.assert().isEqualTo(listOf("first", "second"))
    }

    private fun masker(key: String, value: String) = object : StateObjectNodeMasker {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun mask(node: ObjectNode): ObjectNode = node.put(key, value)
    }
}
