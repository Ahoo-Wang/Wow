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

import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Round two of the deep review, security: a projection is bounded by nothing. Neither the HTTP entry's budget nor
 * admission counts its entries or drops a repeated one, and `RecordProjection` deep-copies the included subtree once per
 * entry, for every record: a 256 KiB body holds some 30,000 copies of one include, each record's subtree is then copied
 * 30,000 times, up to 1,000 records a list (on PostgreSQL inside the JDBC read loop, its permit and connection held).
 */
class R2ProjectionSizeTest {
    private val items = QueryField("state.items")
    private val schema = gatewaySchema(
        fields = mapOf(items to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))),
    )

    @Test
    fun `a projection of ten thousand entries is refused at the entry`() {
        val query = ListQuery(MatchAllFilter, projection = Projection(include = List(10_000) { items }), limit = 100)
        assertThrows<QueryRequestException> {
            QueryBudgetFixtures.budget().check(query)
            QueryAdmission.Trusted.list(query, schema)
        }
    }
}
