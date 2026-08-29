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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CursorQueriesTest {
    @Test
    fun `effective cursor sort should append one stable key`() {
        CursorQuery(MatchAllFilter, sort = listOf(Sort("state.createdAt", Sort.Direction.DESC)))
            .withUniqueSort("aggregateId").sort.assert().containsExactly(
                Sort("state.createdAt", Sort.Direction.DESC),
                Sort("aggregateId", Sort.Direction.ASC),
            )
    }

    @Test
    fun `effective cursor sort should allow an empty sort and existing unique key`() {
        CursorQuery(MatchAllFilter).withUniqueSort("aggregateId").sort.assert().containsExactly(
            Sort("aggregateId", Sort.Direction.ASC),
        )
        CursorQuery(MatchAllFilter, sort = listOf(Sort("aggregateId", Sort.Direction.DESC)))
            .withUniqueSort("aggregateId").sort.assert().containsExactly(
                Sort("aggregateId", Sort.Direction.DESC),
            )
    }

    @Test
    fun `effective cursor sort should reject duplicates and unstable metadata`() {
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort("state.id", Sort.Direction.ASC), Sort("state.id", Sort.Direction.DESC)),
            ).withUniqueSort("aggregateId")
        }
        assertThrows<IllegalArgumentException> {
            CursorQuery(MatchAllFilter, sort = listOf(Sort("_score", Sort.Direction.DESC)))
                .withUniqueSort("aggregateId")
        }
        assertThrows<IllegalArgumentException> {
            CursorQuery(MatchAllFilter).withUniqueSort("_score")
        }
    }

    @Test
    fun `effective cursor sort should reject unique key append overflow`() {
        val maximum = (0 until me.ahoo.wow.api.query.AggregationQuery.MAX_SORT_FIELDS).map { index ->
            Sort("field-$index", Sort.Direction.ASC)
        }

        CursorQuery(MatchAllFilter, sort = maximum)
            .withUniqueSort("field-31").sort.assert().hasSize(maximum.size)
        assertThrows<IllegalArgumentException> {
            CursorQuery(MatchAllFilter, sort = maximum).withUniqueSort("aggregateId")
        }
    }
}
