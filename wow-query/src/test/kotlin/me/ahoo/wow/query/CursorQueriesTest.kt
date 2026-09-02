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
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CursorQueriesTest {
    @Test
    fun `should append unique sort once`() {
        CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField("version"), Sort.Direction.DESC)))
            .withUniqueSort(QueryField("aggregateId")).sort.assert().containsExactly(
                Sort(QueryField("version"), Sort.Direction.DESC),
                Sort(QueryField("aggregateId"), Sort.Direction.ASC),
            )
    }

    @Test
    fun `should reject duplicate unstable and overflowing sort`() {
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort(QueryField("id"), Sort.Direction.ASC), Sort(QueryField("id"), Sort.Direction.DESC)),
            ).withUniqueSort(QueryField("aggregateId"))
        }
        listOf("_score", "_doc", "_shard_doc").forEach { field ->
            assertThrows<IllegalArgumentException> {
                CursorQuery(MatchAllFilter, sort = listOf(Sort(QueryField(field), Sort.Direction.ASC)))
                    .withUniqueSort(QueryField("aggregateId"))
            }
        }
        assertThrows<IllegalArgumentException> {
            CursorQuery(
                MatchAllFilter,
                sort = List(AggregationQuery.MAX_SORT_FIELDS) { Sort(QueryField("field$it"), Sort.Direction.ASC) },
            ).withUniqueSort(QueryField("aggregateId"))
        }
    }
}
