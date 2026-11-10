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
package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.backend.compareScalars
import me.ahoo.wow.query.backend.orderOf
import me.ahoo.wow.query.backend.valueKey
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigInteger

class ValueOrderTest {
    private val nodes = JsonNodeFactory.instance

    private fun node(text: String): JsonNode = WowJson.mapper.readTree(text)

    @Test
    fun `values of different kinds order by kind, then each kind by value`() {
        orderOf(null, node("1")).assert().isNegative()
        orderOf(node("null"), node("1")).assert().isNegative()
        orderOf(node("1"), node("\"a\"")).assert().isNegative()
        orderOf(node("1.5"), node("2")).assert().isNegative()
        orderOf(node("\"b\""), node("\"a\"")).assert().isPositive()
        orderOf(node("true"), node("false")).assert().isPositive()
        orderOf(node("[1]"), node("[2]")).assert().isNegative()
        orderOf(node("[1]"), node("true")).assert().isPositive()
        orderOf(null, node("null")).assert().isZero()
        compareScalars(node("false"), node("true")).assert().isEqualTo(-1)
        compareScalars(node("\"a\""), node("1")).assert().isNull()
    }

    @Test
    fun `numbers order by their exact value, past a long and past a double`() {
        val maxLong = nodes.numberNode(Long.MAX_VALUE)
        val pastLong = nodes.numberNode(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))
        orderOf(maxLong, pastLong).assert().isNegative()
        orderOf(pastLong, maxLong).assert().isPositive()
        orderOf(node("1.00000000000000000001"), node("1")).assert().isPositive()
        orderOf(nodes.numberNode(1), nodes.numberNode(1L)).assert().isZero()
    }

    @Test
    fun `values equal by value share a key, whatever their JSON width`() {
        listOf(nodes.numberNode(1), nodes.numberNode(1L), node("1.0"), nodes.numberNode(BigInteger.ONE))
            .map { it.valueKey() }.distinct().assert().hasSize(1)
        nodes.numberNode(10).valueKey().assert().isEqualTo(node("10.00").valueKey())
        nodes.numberNode(1).valueKey().assert().isNotEqualTo(nodes.numberNode(2).valueKey())
        nodes.stringNode("1").valueKey().assert().isNotEqualTo(nodes.numberNode(1).valueKey())
        (null as JsonNode?).valueKey().assert().isNull()
    }
}
