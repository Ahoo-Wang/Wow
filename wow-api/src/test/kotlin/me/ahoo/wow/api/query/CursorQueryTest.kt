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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CursorQueryTest {
    @Test
    fun `should use cursor defaults`() {
        val query = CursorQuery(MatchAllFilter)

        query.projection.assert().isEqualTo(Projection.ALL)
        query.sort.assert().isEmpty()
        query.size.assert().isEqualTo(10)
        query.cursor.assert().isNull()
    }

    @Test
    fun `should reject size without lookahead capacity and excessive sort`() {
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = 0) }
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, size = Int.MAX_VALUE) }
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = List(AggregationQuery.MAX_SORT_FIELDS + 1) { Sort(QueryField("field$it"), Sort.Direction.ASC) },
            )
        }
    }

    @Test
    fun `should preserve cursor while rewriting filter and projection`() {
        val query = CursorQuery(MatchAllFilter, size = 20, cursor = "next")
            .withFilter(IdFilter("id"))
            .withProjection(Projection(include = listOf(QueryField("state.name"))))

        query.filter.assert().isEqualTo(IdFilter("id"))
        query.projection.include.assert().containsExactly(QueryField("state.name"))
        query.size.assert().isEqualTo(20)
        query.cursor.assert().isEqualTo("next")
    }
}
