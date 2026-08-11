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
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Collections
import java.util.IdentityHashMap

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
    fun `decimal values should reject mutable big decimal subclasses at every composition boundary`() {
        val mutableDecimal = MutableBigDecimal()
        val initialHash = mutableDecimal.hashCode()
        mutableDecimal.mutation++
        (mutableDecimal.hashCode() == initialHash).assert().isFalse()

        assertAll(
            {
                assertSensitiveTypeRejected { QueryValue.DecimalValue(mutableDecimal) }
            },
            {
                assertSensitiveTypeRejected {
                    QueryValue.ListValue(listOf(QueryValue.DecimalValue(MutableBigDecimal())))
                }
            },
            {
                assertSensitiveTypeRejected {
                    QueryValue.ObjectValue(mapOf("nested" to QueryValue.DecimalValue(MutableBigDecimal())))
                }
            },
            {
                assertSensitiveTypeRejected {
                    PredicateExpression(
                        LogicalField("amount"),
                        PortableOperator.EQ,
                        listOf(QueryValue.DecimalValue(MutableBigDecimal()))
                    )
                }
            },
            {
                assertSensitiveTypeRejected {
                    QueryValue.DecimalValue(BigDecimal.ONE).copy(value = MutableBigDecimal())
                }
            }
        )

        val exact = QueryValue.DecimalValue(BigDecimal("1.25"))
        exact.copy().assert().isEqualTo(exact)
        exact.value.javaClass.assert().isEqualTo(BigDecimal::class.java)
    }

    @Test
    fun `query object maps should reject key cardinality loss in direct copy and nested construction`() {
        val ordinary = QueryValue.ObjectValue(linkedMapOf("first" to QueryValue.NullValue))
        val identityParameters = identityParameters()
        identityParameters.assert().hasSize(2)

        assertAll(
            {
                assertSensitiveCardinalityRejected { QueryValue.ObjectValue(identityParameters) }
            },
            {
                assertSensitiveCardinalityRejected { ordinary.copy(values = identityParameters) }
            },
            {
                assertSensitiveCardinalityRejected {
                    QueryValue.ListValue(listOf(QueryValue.ObjectValue(identityParameters)))
                }
            }
        )
        ordinary.values.assert().hasSize(1)
    }

    @Test
    fun `capability maps and sets should reject cardinality loss in direct and copy construction`() {
        val capability = QueryCapabilityId("cardinality-test")
        val field = LogicalField("ordinaryField")
        val native = NativeExpression(
            capability,
            "mongo",
            "template",
            mapOf("value" to QueryValue.NullValue),
            setOf(field)
        )
        val fullText = FullTextExpression(capability, "query", setOf(field))
        val identityParameters = identityParameters()
        val identityFields = identityFields()
        identityParameters.assert().hasSize(2)
        identityFields.assert().hasSize(2)

        assertAll(
            {
                assertSensitiveCardinalityRejected {
                    NativeExpression(capability, "mongo", "template", identityParameters, setOf(field))
                }
            },
            {
                assertSensitiveCardinalityRejected { native.copy(parameters = identityParameters) }
            },
            {
                assertSensitiveCardinalityRejected {
                    NativeExpression(capability, "mongo", "template", emptyMap(), identityFields)
                }
            },
            {
                assertSensitiveCardinalityRejected { native.copy(declaredFields = identityFields) }
            },
            {
                assertSensitiveCardinalityRejected { FullTextExpression(capability, "query", identityFields) }
            },
            {
                assertSensitiveCardinalityRejected { fullText.copy(fields = identityFields) }
            }
        )
        native.parameters.assert().hasSize(1)
        native.declaredFields.assert().containsExactly(field)
        fullText.fields.assert().containsExactly(field)
    }

    @Test
    fun `payload data surfaces should stay structural while toString remains redacted`() {
        val binary = QueryValue.BinaryValue(byteArrayOf(1, 2))
        val binaryCopy = binary.copy(value = binary.component1())
        val native = NativeExpression(
            capabilityId = QueryCapabilityId("native-template"),
            backendId = "mongo",
            templateId = "safe-template",
            parameters = mapOf("secret" to QueryValue.StringValue("do-not-log")),
            declaredFields = setOf(LogicalField("amount"))
        )
        val nativeCopy = native.copy(parameters = native.component4())

        binaryCopy.assert().isEqualTo(binary)
        binaryCopy.hashCode().assert().isEqualTo(binary.hashCode())
        nativeCopy.assert().isEqualTo(native)
        nativeCopy.hashCode().assert().isEqualTo(native.hashCode())
        binary.toString().assert().isEqualTo("BinaryValue(size=2)")
        native.toString().assert().doesNotContain("do-not-log")
        native.toString().assert().contains("parameterNames=[secret]")
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

    private fun identityParameters(): Map<String, QueryValue> = IdentityHashMap<String, QueryValue>().apply {
        this[String(charArrayOf('s', 'e', 'n', 's', 'i', 't', 'i', 'v', 'e', 'K', 'e', 'y'))] =
            QueryValue.NullValue
        this[String(charArrayOf('s', 'e', 'n', 's', 'i', 't', 'i', 'v', 'e', 'K', 'e', 'y'))] =
            QueryValue.NullValue
    }

    @Suppress("IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE")
    private fun identityFields(): Set<LogicalField> =
        Collections.newSetFromMap(IdentityHashMap<LogicalField, Boolean>()).apply {
            add(LogicalField("sensitiveField"))
            add(LogicalField("sensitiveField"))
        }

    private fun assertSensitiveCardinalityRejected(factory: () -> Any) {
        val error = assertThrows<IllegalArgumentException> { factory() }
        error.message.assert().doesNotContain("sensitive")
    }

    private fun assertSensitiveTypeRejected(factory: () -> Any) {
        val error = assertThrows<IllegalArgumentException> { factory() }
        error.message.assert().doesNotContain("987654321")
    }

    private class MutableBigDecimal : BigDecimal("987654321.125") {
        var mutation: Int = 1

        override fun toByte(): Byte = toInt().toByte()

        override fun toShort(): Short = toInt().toShort()

        override fun equals(other: Any?): Boolean = super.equals(other)

        override fun hashCode(): Int = super.hashCode() + mutation
    }
}
