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

package me.ahoo.wow.query.converter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.query.dsl.condition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class ConditionConverterTest {

    private class SingleUseIterable<T>(private val values: List<T>) : Iterable<T> {
        private var consumed = false

        override fun iterator(): Iterator<T> {
            check(consumed.not()) { "Iterable can only be consumed once." }
            consumed = true
            return values.iterator()
        }
    }

    private class RecordingConditionConverter : AbstractConditionConverter<Pair<String, Condition>>() {
        val calls = mutableListOf<Pair<String, Condition>>()

        private fun record(name: String, condition: Condition): Pair<String, Condition> {
            val call = name to condition
            calls.add(call)
            return call
        }

        override fun and(condition: Condition) = record("and", condition)
        override fun or(condition: Condition) = record("or", condition)
        override fun nor(condition: Condition) = record("nor", condition)
        override fun id(condition: Condition) = record("id", condition)
        override fun ids(condition: Condition) = record("ids", condition)
        override fun aggregateId(condition: Condition) = record("aggregateId", condition)
        override fun aggregateIds(condition: Condition) = record("aggregateIds", condition)
        override fun tenantId(condition: Condition) = record("tenantId", condition)
        override fun ownerId(condition: Condition) = record("ownerId", condition)
        override fun spaceId(condition: Condition) = record("spaceId", condition)
        override fun all(condition: Condition) = record("all", condition)
        override fun eq(condition: Condition) = record("eq", condition)
        override fun ne(condition: Condition) = record("ne", condition)
        override fun gt(condition: Condition) = record("gt", condition)
        override fun lt(condition: Condition) = record("lt", condition)
        override fun gte(condition: Condition) = record("gte", condition)
        override fun lte(condition: Condition) = record("lte", condition)
        override fun contains(condition: Condition) = record("contains", condition)
        override fun match(condition: Condition) = record("match", condition)
        override fun isIn(condition: Condition) = record("isIn", condition)
        override fun notIn(condition: Condition) = record("notIn", condition)
        override fun between(condition: Condition) = record("between", condition)
        override fun allIn(condition: Condition) = record("allIn", condition)
        override fun startsWith(condition: Condition) = record("startsWith", condition)
        override fun endsWith(condition: Condition) = record("endsWith", condition)
        override fun elemMatch(condition: Condition) = record("elemMatch", condition)
        override fun isNull(condition: Condition) = record("isNull", condition)
        override fun notNull(condition: Condition) = record("notNull", condition)
        override fun isTrue(condition: Condition) = record("isTrue", condition)
        override fun isFalse(condition: Condition) = record("isFalse", condition)
        override fun exists(condition: Condition) = record("exists", condition)
        override fun deleted(condition: Condition) = record("deleted", condition)
        override fun raw(condition: Condition) = record("raw", condition)

        fun testInternalConvert(condition: Condition): Pair<String, Condition> {
            return internalConvert(condition)
        }

        fun testConvertWithoutGuard(condition: Condition): Pair<String, Condition> {
            return convertWithoutGuard(condition)
        }
    }

    private val converter = RecordingConditionConverter()

    private fun dispatchAndAssert(operator: String, condition: Condition) {
        converter.testInternalConvert(condition)
        converter.calls.last().first.assert().isEqualTo(operator)
    }

    @Test
    fun `should dispatch eq operator`() {
        dispatchAndAssert("eq", Condition.eq("field1", "value1"))
    }

    @Test
    fun `should dispatch ne operator`() {
        dispatchAndAssert("ne", Condition.ne("field1", "value1"))
    }

    @Test
    fun `should dispatch gt operator`() {
        dispatchAndAssert("gt", Condition.gt("field1", 1))
    }

    @Test
    fun `should dispatch lt operator`() {
        dispatchAndAssert("lt", Condition.lt("field1", 1))
    }

    @Test
    fun `should dispatch gte operator`() {
        dispatchAndAssert("gte", Condition.gte("field1", 1))
    }

    @Test
    fun `should dispatch lte operator`() {
        dispatchAndAssert("lte", Condition.lte("field1", 1))
    }

    @Test
    fun `should dispatch and operator`() {
        val condition = Condition.and(Condition.eq("f1", "v1"), Condition.eq("f2", "v2"))
        converter.testInternalConvert(condition)
        converter.calls.last().first.assert().isEqualTo("and")
        converter.calls.last().second.children.assert().hasSize(2)
    }

    @Test
    fun `should dispatch or operator`() {
        dispatchAndAssert("or", Condition.or(Condition.eq("f1", "v1")))
    }

    @Test
    fun `should dispatch nor operator`() {
        dispatchAndAssert("nor", Condition.nor(Condition.eq("f1", "v1")))
    }

    @Test
    fun `should dispatch id operator`() {
        dispatchAndAssert("id", Condition.id("id1"))
    }

    @Test
    fun `should dispatch ids operator`() {
        dispatchAndAssert("ids", Condition.ids("id1", "id2"))
    }

    @Test
    fun `should dispatch tenantId operator`() {
        dispatchAndAssert("tenantId", Condition.tenantId("t1"))
    }

    @Test
    fun `should dispatch deleted operator`() {
        dispatchAndAssert("deleted", Condition.deleted(DeletionState.DELETED))
    }

    @Test
    fun `should dispatch all operator`() {
        dispatchAndAssert("all", Condition.ALL)
    }

    @Test
    fun `should dispatch contains operator`() {
        dispatchAndAssert("contains", Condition.contains("field1", "value1"))
    }

    @Test
    fun `should dispatch isIn operator`() {
        dispatchAndAssert("isIn", Condition.isIn("field1", listOf("a", "b")))
    }

    @Test
    fun `should dispatch notIn operator`() {
        dispatchAndAssert("notIn", Condition.notIn("field1", listOf("a")))
    }

    @Test
    fun `should dispatch between operator`() {
        dispatchAndAssert("between", Condition.between("field1", 1, 10))
    }

    @Test
    fun `should dispatch allIn operator`() {
        dispatchAndAssert("allIn", Condition.all("field1", listOf("a")))
    }

    @Test
    fun `should dispatch startsWith operator`() {
        dispatchAndAssert("startsWith", Condition.startsWith("field1", "val"))
    }

    @Test
    fun `should dispatch endsWith operator`() {
        dispatchAndAssert("endsWith", Condition.endsWith("field1", "val"))
    }

    @Test
    fun `should dispatch elemMatch operator`() {
        dispatchAndAssert("elemMatch", Condition.elemMatch("field1", Condition.eq("f2", "v2")))
    }

    @Test
    fun `should dispatch isNull operator`() {
        dispatchAndAssert("isNull", Condition.isNull("field1"))
    }

    @Test
    fun `should dispatch notNull operator`() {
        dispatchAndAssert("notNull", Condition.notNull("field1"))
    }

    @Test
    fun `should dispatch isTrue operator`() {
        dispatchAndAssert("isTrue", Condition.isTrue("field1"))
    }

    @Test
    fun `should dispatch isFalse operator`() {
        dispatchAndAssert("isFalse", Condition.isFalse("field1"))
    }

    @Test
    fun `should dispatch exists operator`() {
        dispatchAndAssert("exists", Condition.exists("field1"))
    }

    @Test
    fun `should dispatch raw operator`() {
        dispatchAndAssert("raw", Condition.raw("1=1"))
    }

    @Test
    fun `should dispatch match operator`() {
        dispatchAndAssert("match", Condition.match("field1", "pattern"))
    }

    @Test
    fun `should guard condition before converting`() {
        val guardedConverter = RecordingConditionConverter()
        val condition = condition {
            "field1" eq "value1"
        }
        guardedConverter.convert(condition)
        guardedConverter.calls.first().first.assert().isEqualTo("and")
        val guarded = guardedConverter.calls.first().second
        guarded.children.first().operator.assert().isEqualTo(Condition.ACTIVE.operator)
    }

    @Test
    fun `should reject empty logical conditions recursively`() {
        listOf(Operator.AND, Operator.OR, Operator.NOR).forEach { operator ->
            val exception = assertThrows<IllegalArgumentException> {
                converter.convert(
                    Condition.and(
                        Condition.eq("field", "value"),
                        Condition(operator = operator),
                    )
                )
            }
            exception.message.assert().isEqualTo("$operator operator requires at least one child condition.")
        }
    }

    @Test
    fun `should recursively validate conversions without deletion guard`() {
        val exception = assertThrows<IllegalArgumentException> {
            converter.testConvertWithoutGuard(
                Condition.and(
                    Condition.eq("field", "value"),
                    Condition("nested", Operator.EXISTS, "true"),
                )
            )
        }
        exception.message.assert().isEqualTo("EXISTS operator requires value to be a Boolean.")
    }

    @Test
    fun `should validate fixed arity operators`() {
        listOf(
            Condition("field", Operator.BETWEEN, "1,2"),
            Condition("field", Operator.BETWEEN, listOf(1)),
            Condition("field", Operator.BETWEEN, listOf(1, 2, 3)),
        ).forEach { condition ->
            val exception = assertThrows<IllegalArgumentException> {
                converter.testInternalConvert(condition)
            }
            exception.message.assert().isEqualTo(
                "BETWEEN operator requires value to be an Iterable with exactly 2 elements."
            )
        }

        listOf(
            Condition("items", Operator.ELEM_MATCH),
            Condition(
                "items",
                Operator.ELEM_MATCH,
                children = listOf(Condition.ALL, Condition.ALL),
            ),
        ).forEach { condition ->
            val elemMatchException = assertThrows<IllegalArgumentException> {
                converter.testInternalConvert(condition)
            }
            elemMatchException.message.assert().isEqualTo(
                "ELEM_MATCH operator requires exactly one child condition."
            )
        }
    }

    @Test
    fun `should validate operator value types`() {
        val invalidConditions = listOf(
            Condition(operator = Operator.ID, value = 1) to
                "ID operator requires value to be a String.",
            Condition(operator = Operator.IDS, value = listOf("id", 1)) to
                "IDS operator requires value to be an Iterable of String values.",
            Condition("field", Operator.IN, "value") to
                "IN operator requires value to be an Iterable.",
            Condition("field", Operator.CONTAINS, 1) to
                "CONTAINS operator requires value to be a String.",
            Condition("field", Operator.EXISTS, "true") to
                "EXISTS operator requires value to be a Boolean.",
            Condition("field", Operator.RECENT_DAYS, 0) to
                "RECENT_DAYS operator requires value to be a positive whole number.",
            Condition("field", Operator.EARLIER_DAYS, 1.5) to
                "EARLIER_DAYS operator requires value to be a positive whole number.",
        )

        invalidConditions.forEach { (condition, expectedMessage) ->
            val exception = assertThrows<IllegalArgumentException> {
                converter.testInternalConvert(condition)
            }
            exception.message.assert().isEqualTo(expectedMessage)
        }
    }

    @Test
    fun `should reject fractional and non finite BEFORE_TODAY seconds`() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.5,
            86399.5,
            BigDecimal("1.5"),
        )
            .forEach { value ->
                val exception = assertThrows<IllegalArgumentException> {
                    converter.testInternalConvert(Condition("field", Operator.BEFORE_TODAY, value))
                }
                exception.message.assert().isEqualTo(
                    "BEFORE_TODAY operator requires value to be a valid second-of-day Number, " +
                        "ISO LocalTime String, or LocalTime."
                )
            }
    }

    @Test
    fun `should materialize collection values before dispatch`() {
        val conditions =
            listOf(
                Condition(operator = Operator.IDS, value = SingleUseIterable(listOf("id-1", "id-2"))) to
                    listOf("id-1", "id-2"),
                Condition(operator = Operator.AGGREGATE_IDS, value = SingleUseIterable(listOf("aggregate-1"))) to
                    listOf("aggregate-1"),
                Condition("field", Operator.IN, SingleUseIterable(listOf("included"))) to listOf("included"),
                Condition("field", Operator.BETWEEN, SingleUseIterable(listOf(1, 2))) to listOf(1, 2),
                Condition("field", Operator.NOT_IN, SingleUseIterable(listOf("excluded"))) to listOf("excluded"),
                Condition("field", Operator.ALL_IN, SingleUseIterable(listOf("required"))) to listOf("required"),
            )

        conditions.forEach { (condition, expectedValues) ->
            val dispatchedCondition = converter.testInternalConvert(condition).second
            dispatchedCondition.value.assert().isEqualTo(expectedValues)
        }
    }

    @Test
    fun `should recursively materialize collection values before dispatch`() {
        val condition =
            Condition.and(
                Condition("field", Operator.IN, SingleUseIterable(listOf("included"))),
            )

        val dispatchedCondition = converter.testConvertWithoutGuard(condition).second

        dispatchedCondition.children.single().value.assert().isEqualTo(listOf("included"))
    }

    @Test
    fun `should validate standard option types`() {
        val invalidConditions = listOf(
            Condition(
                "field",
                Operator.CONTAINS,
                "value",
                options = mapOf(Condition.IGNORE_CASE_OPTION_KEY to "true"),
            ) to "CONTAINS operator requires option 'ignoreCase' to be a Boolean.",
            Condition(
                "field",
                Operator.TODAY,
                options = mapOf(Condition.ZONE_ID_OPTION_KEY to 8),
            ) to "TODAY operator requires option 'zoneId' to be a String or ZoneId.",
            Condition(
                "field",
                Operator.TODAY,
                options = mapOf(Condition.DATE_PATTERN_OPTION_KEY to 8),
            ) to "TODAY operator requires option 'datePattern' to be a String or DateTimeFormatter.",
        )

        invalidConditions.forEach { (condition, expectedMessage) ->
            val exception = assertThrows<IllegalArgumentException> {
                converter.testInternalConvert(condition)
            }
            exception.message.assert().isEqualTo(expectedMessage)
        }
    }
}
