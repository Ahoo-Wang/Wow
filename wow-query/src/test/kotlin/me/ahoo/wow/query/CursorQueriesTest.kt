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
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CursorQueriesTest {
    @Test
    fun `unique sort normalization must not interpret backend field names`() {
        val field = QueryField("_score")
        listOf(Sort(field, Sort.Direction.ASC))
            .withUniqueSort(QueryField("aggregateId")).first().field.assert().isEqualTo(field)
    }

    @Test
    fun `should append unique sort once`() {
        listOf(Sort(QueryField("version"), Sort.Direction.DESC))
            .withUniqueSort(QueryField("aggregateId")).assert().containsExactly(
                Sort(QueryField("version"), Sort.Direction.DESC),
                Sort(QueryField("aggregateId"), Sort.Direction.ASC),
            )
    }

    @Test
    fun `admission rejects a duplicate or overflowing effective cursor sort with the cursor codes`() {
        val schema = gatewaySchema()
        fun admit(vararg fields: String) = QueryAdmission.Trusted.cursor(
            CursorQuery(MatchAllFilter, sort = fields.map { Sort(QueryField(it), Sort.Direction.ASC) }),
            schema,
        )
        assertThrows<QueryRequestException> { admit("id", "id") }.code
            .assert().isEqualTo(QueryErrorCodes.CURSOR_SORT_DUPLICATE)
        // The identity tie-breaker counts: MAX_SORT_FIELDS other fields overflow once it is appended.
        assertThrows<QueryRequestException> {
            admit(*Array(AggregationQuery.MAX_SORT_FIELDS) { "field$it" })
        }.code.assert().isEqualTo(QueryErrorCodes.CURSOR_SORT_TOO_MANY)
    }
}
