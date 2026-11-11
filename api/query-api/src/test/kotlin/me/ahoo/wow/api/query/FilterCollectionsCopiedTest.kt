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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/** A filter or a having expression keeps no collection its caller may change after building it. */
class FilterCollectionsCopiedTest {
    private val field = QueryField("state.status")

    /** A case: what builds the value from a mutable collection, and what changes that collection afterwards. */
    private class Case(val name: String, val build: () -> Pair<Any, () -> Unit>)

    private fun <E> listCase(name: String, first: E, second: E, build: (MutableList<E>) -> Any) = Case(name) {
        val list = mutableListOf(first)
        build(list) to { list += second }
    }

    private val cases = listOf(
        listCase("AND", EqualFilter(field, "a"), EqualFilter(field, "b"), ::AndFilter),
        listCase("OR", EqualFilter(field, "a"), EqualFilter(field, "b"), ::OrFilter),
        listCase("NOR", EqualFilter(field, "a"), EqualFilter(field, "b"), ::NorFilter),
        listCase<Any?>("IN", "a", "b") { InFilter(field, it) },
        listCase<Any?>("NOT_IN", "a", "b") { NotInFilter(field, it) },
        listCase<Any?>("CONTAINS_ALL", "a", "b") { ContainsAllFilter(field, it) },
        listCase("IDS", "id-1", "id-2", ::IdsFilter),
        listCase("AGGREGATE_IDS", "id-1", "id-2", ::AggregateIdsFilter),
        listCase<Any?>("EQ of an array", "a", "b") { EqualFilter(field, it) },
        listCase<Any?>("NE of an array", "a", "b") { NotEqualFilter(field, it) },
        Case("SEARCH") {
            val fields = linkedSetOf<QueryField<*>>(QueryField("title"))
            SearchFilter("wow", fields) to { fields += QueryField("body") }
        },
        listCase("having IN", 1.0, 2.0) { HavingExpression.In("total", it) },
        listCase<HavingExpression>("having AND", HavingExpression.IsNull("a"), HavingExpression.IsNull("b")) {
            HavingExpression.And(it)
        },
        listCase<HavingExpression>("having OR", HavingExpression.IsNull("a"), HavingExpression.IsNull("b")) {
            HavingExpression.Or(it)
        },
    )

    @TestFactory
    fun `a collection changed after building changes neither the value nor its hash`(): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val (value, change) = case.build()
                val (same, _) = case.build()
                val hash = value.hashCode()
                change()
                value.assert().isEqualTo(same)
                value.hashCode().assert().isEqualTo(hash)
            }
        }

    @Test
    fun `a copied collection cannot be changed through the value`() {
        val operands = AndFilter(mutableListOf(EqualFilter(field, "a"))).operands
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (operands as MutableList<FilterExpression>).clear()
        }
    }

    @Test
    fun `a null among the values is still refused with the filter's own message`() {
        assertThrows<IllegalArgumentException> { InFilter(field, listOf("a", null)) }
            .message.assert().isEqualTo("IN values cannot contain null.")
    }

    @Test
    fun `the search fields keep their order, as the wire writes them`() {
        val fields = linkedSetOf<QueryField<*>>(QueryField("title"), QueryField("body"), QueryField("author"))
        SearchFilter("wow", fields).fields.toList().assert()
            .containsExactly(QueryField("title"), QueryField("body"), QueryField("author"))
    }

    @Test
    fun `a scalar equality keeps its value as given`() {
        val value = Any()
        EqualFilter(field, value).value.assert().isSameAs(value)
    }
}
