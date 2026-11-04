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
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessOperator
import me.ahoo.wow.api.access.AccessPredicate.AllOf
import me.ahoo.wow.api.access.AccessPredicate.Allowed
import me.ahoo.wow.api.access.AccessPredicate.AnyOf
import me.ahoo.wow.api.access.AccessPredicate.Comparison
import me.ahoo.wow.api.access.AccessPredicate.Denied
import me.ahoo.wow.api.access.AccessPredicate.Not
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.policy.AccessFilters
import org.junit.jupiter.api.Test

/**
 * [AccessFilters] beyond the backends' matrix (`AccessFilterSpec`): what it folds before a backend sees it, and an
 * attribute the model does not store.
 */
class AccessFiltersTest {
    class Document

    private val department = AccessAttribute.single<Document, String>("department", String::class.java)
    private val sharedWith = AccessAttribute.multiple<Document, String>("sharedWith", String::class.java)
    private val sales = Comparison(department, AccessOperator.EQUAL, listOf("sales"))

    private fun fieldOf(attribute: AccessAttribute<*, *>): QueryField<*> = QueryField("access.${attribute.name}")

    @Test
    fun `an attribute the model does not store matches nothing, either way`() {
        AccessFilters.compile(sales) { null }.assert().isSameAs(MatchNoneFilter)
        AccessFilters.compile(Not(sales)) { null }.assert().isSameAs(MatchNoneFilter)
    }

    @Test
    fun `a comparison built by the model's own filter matches nothing negated`() {
        val owner = MatchAllFilter
        AccessFilters.compileWith(sales) { owner }.assert().isSameAs(owner)
        AccessFilters.compileWith(Not(sales)) { owner }.assert().isSameAs(MatchNoneFilter)
    }

    @Test
    fun `constants fold, and a junction of one operand is that operand`() {
        val compiled = AccessFilters.compile(sales, ::fieldOf)
        AccessFilters.compile(Not(Allowed), ::fieldOf).assert().isSameAs(MatchNoneFilter)
        AccessFilters.compile(Not(Denied), ::fieldOf).assert().isSameAs(MatchAllFilter)
        AccessFilters.compile(AllOf(listOf(sales, Allowed)), ::fieldOf).assert().isEqualTo(compiled)
        AccessFilters.compile(AnyOf(listOf(sales, Denied)), ::fieldOf).assert().isEqualTo(compiled)
        AccessFilters.compile(AllOf(listOf(sales, Denied)), ::fieldOf).assert().isSameAs(MatchNoneFilter)
        AccessFilters.compile(AnyOf(listOf(sales, Allowed)), ::fieldOf).assert().isSameAs(MatchAllFilter)
        AccessFilters.compile(Comparison(sharedWith, AccessOperator.CONTAINS_ANY, listOf("bob")), ::fieldOf)
            .assert().isEqualTo(ContainsAllFilter(QueryField("access.sharedWith"), listOf("bob")))
    }
}
