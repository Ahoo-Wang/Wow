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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class CountSnapshotQueryContextTest {

    @Test
    fun `should rewrite query with tenant id`() {
        val context = DefaultQueryContext<FilterExpression, Any>(
            queryType = QueryType.COUNT,
            MOCK_AGGREGATE_METADATA
        )
        context.setQuery(MatchAllFilter)
        context.appendFilter(TenantIdFilter("tenantId"))
        context.getQuery().assert().isEqualTo(TenantIdFilter("tenantId"))
    }

    @Test
    fun `should rewrite query with appended condition`() {
        val context = DefaultQueryContext<FilterExpression, Any>(
            queryType = QueryType.COUNT,
            MOCK_AGGREGATE_METADATA
        )
        val query = filter {
            "field1" eq "value1"
        }
        context.setQuery(query)
        context.appendFilter(OwnerIdFilter("ownerId"))
        val operands = (context.getQuery() as AndFilter).operands
        operands.assert().hasSize(2)
        operands.last().assert().isEqualTo(OwnerIdFilter("ownerId"))
    }
}
