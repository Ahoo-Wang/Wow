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
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QueryScope
import me.ahoo.wow.api.query.QuerySort
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

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
            budget {
                timeout(Duration.ofSeconds(3))
                maxRecords(50)
            }
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
        query.budget.timeout.assert().isEqualTo(Duration.ofSeconds(3))
        query.budget.maxRecords.assert().isEqualTo(50L)
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
    fun `should expose every predicate and logical expression operator`() {
        val dsl = ExpressionDsl()
        val field = dsl.field("state.value")
        val predicates = listOf(
            field eq "value",
            field ne "value",
            field gt 1,
            field lt 2,
            field gte 1,
            field lte 2,
            field contains "alu",
            field.inside(listOf("a", "b")),
            field.notInside(listOf("c")),
            field.between(1 to 2),
            field.containsAll(listOf("a", "b")),
            field.startsWith("pre"),
            field.endsWith("post"),
            field.isNull(),
            field.isNotNull(),
            field.isTrue(),
            field.isFalse(),
            field.exists(),
            field.isEmpty()
        ).map { it as PredicateExpression }

        predicates.mapTo(linkedSetOf(), PredicateExpression::operator)
            .assert().containsExactlyInAnyOrder(*PredicateOperator.entries.toTypedArray())
        (dsl.or(predicates.first(), predicates.last()) as LogicalExpression)
            .operator.assert().isEqualTo(LogicalOperator.OR)
        (dsl.nor(predicates.first()) as LogicalExpression).operator.assert().isEqualTo(LogicalOperator.NOR)
        (dsl.elementMatch("state.items") { field("sku") eq "sku-1" } is ElementMatchExpression)
            .assert().isTrue()
        (dsl.search("text", "state.name", "state.description") is SearchExpression).assert().isTrue()
    }

    @Test
    fun `should build direct scope sort projection and budget values`() {
        val query = SnapshotRecordQueryDsl().apply {
            filter(LogicalExpression(LogicalOperator.OR, listOf(ExpressionDsl().field("deleted").isFalse())))
            projection(QueryProjection.Exclude(setOf(LogicalField("state.secret"))))
            sort(listOf(QuerySort(LogicalField("aggregateId"), QuerySortDirection.ASC)))
            scope(QueryScope(ownerId = "owner-1", spaceId = "space-1"))
            budget(QueryBudget(Duration.ofSeconds(2), 20))
        }.build()

        query.scope.ownerId.assert().isEqualTo("owner-1")
        query.scope.spaceId.assert().isEqualTo("space-1")
        query.projection.assert().isInstanceOf(QueryProjection.Exclude::class.java)
        query.sort.single().direction.assert().isEqualTo(QuerySortDirection.ASC)
        query.budget.maxRecords.assert().isEqualTo(20)
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
        gateway.count {
            filter { field("aggregateId") eq "id" }
            budget { timeout(Duration.ofSeconds(1)) }
        }
    }
}
