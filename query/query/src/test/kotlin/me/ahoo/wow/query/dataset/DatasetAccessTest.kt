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

package me.ahoo.wow.query.dataset

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.query.policy.AccessFixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DatasetAccessTest {
    private val department = QueryField("department")

    @Test
    fun `a restriction is the one element match of a predicate, none for a grant of all and nothing for none`() {
        DatasetAccess.restriction(MatchAllFilter).assert().isSameAs(MatchAllFilter)
        DatasetAccess.restriction(MatchNoneFilter).assert().isSameAs(MatchNoneFilter)
        val predicate = OrFilter(listOf(EqualFilter(department, "sales"), IsEmptyFilter(QueryField("sharedWith"))))
        val restriction = DatasetAccess.restriction(predicate)
        restriction.assert().isEqualTo(ElementMatchFilter(DatasetAccess.FIELD, predicate))
        DatasetAccess.predicateOf(restriction).assert().isSameAs(predicate)
        DatasetAccess.predicateOf(predicate).assert().isNull()
        DatasetAccess.predicateOf(ElementMatchFilter(QueryField("lines"), predicate)).assert().isNull()
    }

    @Test
    fun `the attributes are compared by their names in the access record`() {
        DatasetAccess.fieldOf(AccessAttribute.OWNER).assert().isEqualTo(QueryField("owner"))
        DatasetAccess.fieldOf(AccessAttribute.SPACE).assert().isEqualTo(QueryField("space"))
        DatasetAccess.fieldOf(AccessFixtures.DEPARTMENT).assert().isEqualTo(department)
    }

    @Test
    fun `a restriction holds only the vocabulary access filters compile to`() {
        val accepted = AndFilter(
            listOf(
                InFilter(department, listOf("sales", 2L, true)),
                NorFilter(listOf(StartsWithFilter(QueryField("org"), "/acme"))),
                ContainsAllFilter(QueryField("sharedWith"), listOf("bob")),
                IsNullFilter(department),
                IsNotNullFilter(department),
                MatchAllFilter,
            ),
        )
        DatasetAccess.requireComparisons(accepted).assert().isSameAs(accepted)
        listOf(
            GreaterThanFilter(QueryField("pages"), 1L),
            EqualFilter(department, 1),
            EqualFilter(QueryField("org.unit"), "sales"),
            StartsWithFilter(QueryField("org"), "/acme", StringComparison.CASE_INSENSITIVE),
            OrFilter(listOf(EqualFilter(department, "sales"), ElementMatchFilter(QueryField("lines"), MatchAllFilter))),
        ).forEach { refused ->
            assertThrows<IllegalArgumentException> { DatasetAccess.restriction(refused) }
        }
    }
}
