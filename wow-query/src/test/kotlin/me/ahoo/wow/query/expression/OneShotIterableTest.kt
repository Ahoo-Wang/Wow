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

package me.ahoo.wow.query.expression

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.util.IdentityHashMap

class OneShotIterableTest {
    private val target = QueryTarget("sales.order".toNamedAggregate(), QueryDocumentKind.EVENT_STREAM)

    @Test
    fun `ALL_IN materializes a one-shot iterable exactly once`() {
        val values = OneShotIterable(listOf("a", "b"))
        val condition = Condition("state.tags", Operator.ALL_IN, values)

        LegacyConditionLowerer.lower(condition, target, Instant.EPOCH, ZoneId.of("UTC")).assert().isEqualTo(
            PredicateExpression(
                LogicalField("state.tags"),
                PortableOperator.ALL_IN,
                listOf(QueryValue.StringValue("a"), QueryValue.StringValue("b"))
            )
        )
        values.iteratorCalls.assert().isOne()
    }

    @Test
    fun `normalizes nested iterable array map binary and closed scalar values`() {
        val nested = OneShotIterable(
            listOf(1, BigInteger.TWO, BigDecimal("1.20"), 1.5, Instant.EPOCH, SampleEnum.ACTIVE, null)
        )
        val binary = byteArrayOf(1, 2)
        val input = linkedMapOf<String, Any?>(
            "nested" to nested,
            "array" to arrayOf<Any>(true, "value"),
            "binary" to binary
        )

        val actual = QueryValueNormalizer.normalize(input)
        binary[0] = 9

        actual.assert().isEqualTo(
            QueryValue.ObjectValue(
                mapOf(
                    "nested" to QueryValue.ListValue(
                        listOf(
                            QueryValue.IntegerValue(1),
                            QueryValue.IntegerValue(2),
                            QueryValue.DecimalValue(BigDecimal("1.20")),
                            QueryValue.FloatingValue(1.5),
                            QueryValue.InstantValue(Instant.EPOCH),
                            QueryValue.EnumValue("ACTIVE"),
                            QueryValue.NullValue
                        )
                    ),
                    "array" to QueryValue.ListValue(
                        listOf(QueryValue.BooleanValue(true), QueryValue.StringValue("value"))
                    ),
                    "binary" to QueryValue.BinaryValue(byteArrayOf(1, 2))
                )
            )
        )
        nested.iteratorCalls.assert().isOne()
    }

    @Test
    fun `rejects identity-map cardinality loss and arbitrary driver values`() {
        val first = String(charArrayOf('k'))
        val second = String(charArrayOf('k'))
        val identityMap = IdentityHashMap<String, Any>()
        identityMap[first] = 1
        identityMap[second] = 2

        assertThrows<QueryException> { QueryValueNormalizer.normalize(identityMap) }
        assertThrows<QueryException> { QueryValueNormalizer.normalize(Any()) }
        assertThrows<QueryException> { QueryValueNormalizer.normalize(MutableBigDecimal("1.0")) }
    }

    private class OneShotIterable<T>(private val values: List<T>) : Iterable<T> {
        var iteratorCalls: Int = 0
            private set

        override fun iterator(): Iterator<T> {
            iteratorCalls++
            check(iteratorCalls == 1) { "iterator requested more than once" }
            return values.iterator()
        }
    }

    private enum class SampleEnum {
        ACTIVE
    }

    private class MutableBigDecimal(value: String) : BigDecimal(value) {
        override fun toByte(): Byte = toInt().toByte()

        override fun toShort(): Short = toInt().toShort()
    }
}
