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

package me.ahoo.wow.query.memory

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.memory.InMemoryReferenceFixtures.Companion.JAN_5
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/** The reference backend's values: how it reads, compares, orders and encodes the JSON values of a record. */
class InMemoryValueSemanticsTest {
    // Values

    @Test
    fun `values read through objects, arrays and their indexes`() {
        val record = WowJson.mapper.readTree("""{"a":[{"b":1},{"b":[2,3]},5],"c":{"d":null}}""")
        record.valuesAt(QueryField("a.b")).flattened().map { it.intValue() }.assert().containsExactly(1, 2, 3)
        record.valuesAt(QueryField("a.1.b")).single().size().assert().isEqualTo(2)
        record.valuesAt(QueryField("a.9")).assert().isEmpty()
        record.valuesAt(QueryField("c.d.e")).assert().isEmpty()
        record.valuesAt(QueryField("c.d")).single().isNull.assert().isTrue()
        null.operandNode().isNull.assert().isTrue()
        val node: JsonNode = nodes.stringNode("x")
        node.operandNode().assert().isSameAs(node)
        listOf(1, 2).operandNode().isArray.assert().isTrue()
    }

    @Test
    fun `values equal and order by value across widths and kinds`() {
        nodes.numberNode(1).sameValue(nodes.numberNode(1.0)).assert().isTrue()
        nodes.numberNode(BigInteger.TEN).sameValue(nodes.numberNode(10L)).assert().isTrue()
        array(1, 2).sameValue(array(1, 2)).assert().isTrue()
        array(1, 2).sameValue(array(1)).assert().isFalse()
        array(1, 2).sameValue(array(1, 3)).assert().isFalse()
        array(1, 2).matches(nodes.numberNode(2)).assert().isTrue()
        array(1, 2).matches(array(1)).assert().isFalse()
        val withNull = listOf(array(3, 1, 2), nodes.nullNode())
        withNull.sortKey(ascending = true, temporal = null).assert().isNull()
        withNull.sortKey(ascending = false, temporal = null)!!.intValue().assert().isEqualTo(3)
        listOf(array(3, 1, 2)).sortKey(ascending = false, temporal = null)!!.intValue().assert().isEqualTo(3)
        listOf<JsonNode>(nodes.nullNode()).sortKey(ascending = true, temporal = null).assert().isNull()
    }

    @Test
    fun `sort keys round trip as cursor positions`() {
        val keys: List<JsonNode?> = listOf(
            null,
            nodes.nullNode(),
            nodes.numberNode(7L),
            nodes.numberNode(BigInteger.TWO.pow(70)),
            nodes.numberNode(1.5),
            nodes.stringNode("s"),
            nodes.booleanNode(true),
            array(1),
        )
        keys.map { it.positionValue() }.assert().containsExactly(
            null,
            null,
            BigDecimal.valueOf(7),
            BigDecimal(BigInteger.TWO.pow(70)),
            BigDecimal("1.5"),
            "s",
            true,
            "[1]",
        )
        listOf<Any?>(null, BigDecimal("7"), BigDecimal("1.00000000000000000001"), "s", true, listOf(1))
            .map {
                it.positionNode()?.toString()
            }.assert().containsExactly(null, "7", "1.00000000000000000001", "\"s\"", "true", "[1]")
    }

    @Test
    fun `numbers and instants read from single values and singleton arrays`() {
        nodes.numberNode(2).numberOrNull().assert().isEqualTo(2.0)
        array(3).numberOrNull().assert().isEqualTo(3.0)
        array(3, 4).numberOrNull().assert().isNull()
        nodes.stringNode("3").numberOrNull().assert().isNull()
        fun millis(node: JsonNode, temporal: Temporal?) = node.instant(temporal)?.toEpochMilli()
        millis(nodes.numberNode(2L), Temporal.Epoch(TimeUnit.SECONDS)).assert().isEqualTo(2000L)
        millis(nodes.numberNode(2L), null).assert().isEqualTo(2L)
        millis(nodes.numberNode(2L), Temporal.Date).assert().isEqualTo(2L)
        millis(array(5), null).assert().isEqualTo(5L)
        nodes.booleanNode(true).instant(null).assert().isNull()
        millis(nodes.stringNode("2026-01-05"), Temporal.Date).assert().isEqualTo(JAN_5)
        parseInstant("2026-01-05T00:00:00Z")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-01-05T08:00:00+08:00")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-01-04T19:00:00-05:00")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-01-05T00:00:00")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-13-45").assert().isNull()
    }

    private fun array(vararg values: Int): JsonNode = nodes.arrayNode().also { array -> values.forEach(array::add) }
}
