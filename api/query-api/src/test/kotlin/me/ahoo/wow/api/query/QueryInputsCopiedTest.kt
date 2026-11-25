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

/**
 * A query input whose constructor checks its lists keeps none its caller may change after building it: the sort of
 * each query, a projection's fields, a cross-tenant scope's tenants and an aggregation's lists.
 */
class QueryInputsCopiedTest {
    private val byStatus = Sort(QueryField("status"), Sort.Direction.ASC)
    private val byCount = Sort(QueryField("count"), Sort.Direction.DESC)
    private val status = AggregationGroup.Terms(QueryField("state.status"), "status")
    private val count = AggregationMetric.Count("count")

    /** A case: what builds the value from a mutable collection, and what changes that collection afterwards. */
    private class Case(val name: String, val build: () -> Pair<Any, () -> Unit>)

    private fun <E> listCase(name: String, first: E, second: E, build: (MutableList<E>) -> Any) = Case(name) {
        val list = mutableListOf(first)
        build(list) to { list += second }
    }

    private val cases = listOf(
        listCase("SingleQuery sort", byStatus, byCount) { SingleQuery(sort = it) },
        listCase("ListQuery sort", byStatus, byCount) { ListQuery(sort = it) },
        listCase("PagedQuery sort", byStatus, byCount) { PagedQuery(sort = it) },
        listCase("CursorQuery sort", byStatus, byCount) { CursorQuery(sort = it) },
        listCase<QueryField<*>>("Projection include", QueryField("name"), QueryField("price")) {
            Projection(include = it)
        },
        listCase<QueryField<*>>("Projection exclude", QueryField("secret"), QueryField("token")) {
            Projection(exclude = it)
        },
        Case("CrossTenant tenants") {
            val tenants = linkedSetOf("t1")
            CrossTenant(tenants) to { tenants += "t2" }
        },
        listCase(
            "AggregationQuery elements",
            AggregationElement(QueryField("state.lines")),
            AggregationElement(QueryField("tags"))
        ) {
            AggregationQuery(elements = it, metrics = listOf(count))
        },
        listCase<AggregationGroup>(
            "AggregationQuery groupBy",
            status,
            AggregationGroup.Terms(QueryField("state.city"), "city")
        ) {
            AggregationQuery(groupBy = it, metrics = listOf(count))
        },
        listCase<AggregationMetric>("AggregationQuery metrics", count, AggregationMetric.Count("other")) {
            AggregationQuery(metrics = it)
        },
        listCase("AggregationQuery sort", byStatus, byCount) {
            AggregationQuery(groupBy = listOf(status), metrics = listOf(count), sort = it)
        },
        listCase("Total within", "province", "city") { DerivedExpression.Total("count", it) },
        listCase("Rank within", "province", "city") { DerivedExpression.Rank("count", it) },
        listCase("Running within", "province", "city") { DerivedExpression.Running("count", byStatus, it) },
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
    fun `a copied list cannot be changed through the value`() {
        val sort = ListQuery(sort = mutableListOf(byStatus)).sort
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (sort as MutableList<Sort>).clear()
        }
    }

    @Test
    fun `a sort that names an alias is checked against the copy the query keeps`() {
        val sort = mutableListOf(byStatus)
        val query = AggregationQuery(groupBy = listOf(status), metrics = listOf(count), sort = sort)
        sort += Sort(QueryField("unknown"), Sort.Direction.ASC)
        query.effectiveSort().assert().containsExactly(byStatus)
    }
}
