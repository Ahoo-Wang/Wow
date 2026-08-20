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

package me.ahoo.wow.query.gateway.dsl

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.DeletionScope
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryDslTest {
    @Test
    fun `should build explicit snapshot query`() {
        val query = SnapshotRecordQueryDsl().apply {
            filter {
                and(
                    field("state.status") eq "ACTIVE",
                    field("state.name") search "iphone"
                )
            }
            projection { include("aggregateId", "state.status") }
            scope {
                tenantId("tenant-1")
                deletion(DeletionScope.ACTIVE)
            }
            sort { desc("version") }
        }.build()

        val filter = query.filter as LogicalExpression
        filter.operator.assert().isEqualTo(LogicalOperator.AND)
        filter.operands.assert().hasSize(2)
        (filter.operands.first() is PredicateExpression).assert().isTrue()
        query.projection.assert().isEqualTo(
            QueryProjection.Include(
                setOf(
                    me.ahoo.wow.api.query.LogicalField("aggregateId"),
                    me.ahoo.wow.api.query.LogicalField("state.status")
                )
            )
        )
        query.scope.tenantId.assert().isEqualTo("tenant-1")
        query.sort.single().direction.assert().isEqualTo(QuerySortDirection.DESC)
    }

    @Test
    fun `should reject implicit accumulation and repeated settings`() {
        val dsl = SnapshotQueryDsl()
        dsl.filter { field("state.status") eq "ACTIVE" }

        assertThrows<IllegalArgumentException> {
            dsl.filter { field("state.status") eq "PAID" }
        }
        assertThrows<IllegalArgumentException> {
            queryScope {
                tenantId("tenant-1")
                tenantId("tenant-2")
            }
        }
    }

    @Test
    fun `should expose typed record count and pagination gateway DSL`() {
        val gateway = mockk<SnapshotQueryGateway<MockStateAggregate>>(relaxed = true)

        gateway.first { filter { field("aggregateId") eq "id" } }
        gateway.firstRecord { projection { include("aggregateId") } }
        gateway.stream { sort { asc("aggregateId") } }
        gateway.stream(10) { sort { asc("aggregateId") } }
        gateway.streamRecords { projection { include("aggregateId") } }
        gateway.streamRecords(10) { projection { include("aggregateId") } }
        gateway.page(1, 10) { sort { asc("aggregateId") } }
        gateway.pageRecords(1, 10) { projection { include("aggregateId") } }
        gateway.count { filter { field("aggregateId") eq "id" } }
    }
}
