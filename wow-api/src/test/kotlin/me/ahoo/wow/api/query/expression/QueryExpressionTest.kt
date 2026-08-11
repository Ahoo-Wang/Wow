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

package me.ahoo.wow.api.query.expression

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class QueryExpressionTest {
    @Test
    fun `expression collections should be immutable snapshots`() {
        val originalOperands = mutableListOf<QueryExpression>(MatchAll)
        val expression = LogicalExpression(LogicalOperator.AND, originalOperands)
        originalOperands += MatchNone

        expression.operands.assert().containsExactly(MatchAll)
        assertThrows<UnsupportedOperationException> {
            (expression.operands as MutableList).add(MatchNone)
        }

        val originalParameters = mutableMapOf("minimum" to QueryValue.DecimalValue(BigDecimal.ONE))
        val originalFields = mutableSetOf(LogicalField("amount"))
        val native = NativeExpression(
            capabilityId = QueryCapabilityId("native-template"),
            backendId = "mongo",
            templateId = "minimum-amount",
            parameters = originalParameters,
            declaredFields = originalFields
        )
        originalParameters.clear()
        originalFields.clear()

        native.parameters.keys.assert().containsExactly("minimum")
        native.declaredFields.assert().containsExactly(LogicalField("amount"))
        assertThrows<UnsupportedOperationException> {
            (native.parameters as MutableMap).clear()
        }
    }

    @Test
    fun `expression should expose data class source surface and copy defensively`() {
        val operands = mutableListOf<QueryExpression>(MatchAll)
        val expression = LogicalExpression(LogicalOperator.AND, operands)
        val (operator, destructuredOperands) = expression
        val replacement = mutableListOf<QueryExpression>(MatchNone)

        val copied = expression.copy(operator = LogicalOperator.OR, operands = replacement)
        replacement += MatchAll

        operator.assert().isEqualTo(LogicalOperator.AND)
        destructuredOperands.assert().containsExactly(MatchAll)
        copied.operands.assert().containsExactly(MatchNone)
        LogicalExpression::class.java.getDeclaredMethod("component1").returnType.assert()
            .isEqualTo(LogicalOperator::class.java)
        LogicalExpression::class.java.getDeclaredMethod("component2").returnType.assert()
            .isEqualTo(List::class.java)
        LogicalExpression::class.java.declaredMethods.any { it.name == "copy" }.assert().isTrue()
    }

    @Test
    fun `query values should deeply snapshot mutable input`() {
        val bytes = byteArrayOf(1, 2)
        val values = mutableListOf<QueryValue>(QueryValue.BinaryValue(bytes))
        val nested = mutableMapOf("values" to QueryValue.ListValue(values))
        val value = QueryValue.ObjectValue(nested)

        bytes[0] = 9
        values.clear()
        nested.clear()

        val listValue = value.values.getValue("values") as QueryValue.ListValue
        val binaryValue = listValue.values.single() as QueryValue.BinaryValue
        assertArrayEquals(byteArrayOf(1, 2), binaryValue.value)

        val exposed = binaryValue.value
        exposed[0] = 7
        assertArrayEquals(byteArrayOf(1, 2), binaryValue.value)
        assertThrows<UnsupportedOperationException> {
            (value.values as MutableMap).clear()
        }
    }

    @Test
    fun `logical nodes should reject empty operands`() {
        assertThrows<IllegalArgumentException> {
            LogicalExpression(LogicalOperator.OR, emptyList())
        }
        assertThrows<IllegalArgumentException> {
            PortableLogicalExpression(LogicalOperator.NOR, emptyList())
        }
    }

    @Test
    fun `native expression should require auditable declarations`() {
        val field = LogicalField("amount")
        val capability = QueryCapabilityId("native-template")

        assertThrows<IllegalArgumentException> {
            NativeExpression(capability, "", "template", emptyMap(), setOf(field))
        }
        assertThrows<IllegalArgumentException> {
            NativeExpression(capability, "mongo", "", emptyMap(), setOf(field))
        }
        assertThrows<IllegalArgumentException> {
            NativeExpression(capability, "mongo", "template", emptyMap(), emptySet())
        }
        assertThrows<IllegalArgumentException> {
            QueryCapabilityId(" ")
        }
        assertThrows<IllegalArgumentException> {
            LogicalField("invalid..path")
        }
        assertThrows<IllegalArgumentException> {
            FullTextExpression(capability, "query", emptySet())
        }
        assertThrows<IllegalArgumentException> {
            FullTextExpression(capability, " ", setOf(field))
        }
    }

    @Test
    fun `portable logical expression should only accept portable children`() {
        val portable: PortableExpression = PortableLogicalExpression(
            LogicalOperator.AND,
            listOf(MatchAll, PredicateExpression(LogicalField("amount"), PortableOperator.GT, emptyList()))
        )

        portable.assert().isInstanceOf(PortableLogicalExpression::class.java)
    }

    @Test
    fun `user logical expression should combine portable and capability children`() {
        val expression = LogicalExpression(
            LogicalOperator.OR,
            listOf(
                MatchAll,
                FullTextExpression(
                    QueryCapabilityId("full-text"),
                    "order",
                    setOf(LogicalField("description"))
                )
            )
        )

        expression.operands.assert().hasSize(2)
    }
}
