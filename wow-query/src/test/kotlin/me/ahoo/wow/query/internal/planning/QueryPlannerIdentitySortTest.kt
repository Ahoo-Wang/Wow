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

package me.ahoo.wow.query.internal.planning

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedPage
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.NormalizedSortDirection
import me.ahoo.wow.query.internal.plan.PageQueryPlan
import me.ahoo.wow.query.internal.plan.PlannedSortOrigin
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class QueryPlannerIdentitySortTest {
    private val planner = QueryPlanner()

    @Test
    fun `strict page should append canonical identity tie breaker`() {
        val query = PlanningFixtures.recordQuery(
            sort = listOf(PlanningFixtures.sort(PlanningFixtures.path("state", "amount"))),
        )
        val page = plan(PlanningFixtures.page(query), PlanningFixtures.schema)

        page.sort.map { it.field }.assert().containsExactly(PlanningFixtures.amount, PlanningFixtures.identity)
        page.sort.map { it.origin }.assert().containsExactly(
            PlannedSortOrigin.USER,
            PlannedSortOrigin.STABILITY_TIE_BREAKER,
        )
        page.page.offset.assert().isEqualTo(0L)
        page.page.size.assert().isEqualTo(20)
    }

    @Test
    fun `target schema aliases should canonicalize snapshot and event identity sort`() {
        val snapshotQuery = PlanningFixtures.recordQuery(
            sort = listOf(
                PlanningFixtures.sort(PlanningFixtures.path("aggregateId"), NormalizedSortDirection.DESC),
            ),
        )
        val snapshot = plan(PlanningFixtures.page(snapshotQuery), PlanningFixtures.schema)
        snapshot.sort.map { it.field }.assert().containsExactly(PlanningFixtures.identity)
        snapshot.sort.single().origin.assert().isEqualTo(PlannedSortOrigin.USER)

        val eventTarget = QueryTarget(PlanningFixtures.target.namedAggregate, QueryDocumentKind.EVENT_STREAM)
        val eventSchema = eventSchema(eventTarget)
        val eventQuery = PlanningFixtures.recordQuery(sort = listOf(PlanningFixtures.sort(PlanningFixtures.path("id"))))
        val eventInvocation = NormalizedQueryInvocation(
            eventTarget,
            QueryOperation.PAGE,
            QueryResultShape.TYPED,
            NormalizedQueryInput.Page(eventQuery, NormalizedPage(1, 20, 0)),
        )

        plan(eventInvocation, eventSchema).sort.map { it.field }.assert().containsExactly(PlanningFixtures.identity)
    }

    @Test
    fun `system field alias should not become a legacy search scope`() {
        val condition = NormalizedCondition.Search(
            PlanningFixtures.legacySearch(PlanningFixtures.path("aggregateId")),
            "id",
        )

        assertThrownBy<QueryRejectedException> {
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(condition)),
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            )
        }.satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(QueryRejectionCategory.UNSUPPORTED_FEATURE)
                error.rejection.code.assert().isEqualTo(QueryRejectionCode.SEARCH_SCOPE_NOT_FOUND)
                error.rejection.path.toString().assert().isEqualTo("$.input.query.condition.scope")
            },
        )
    }

    private fun eventSchema(target: QueryTarget): QueryDocumentSchema {
        val identity = PlanningFixtures.schema.fields.getValue(PlanningFixtures.identity).let { field ->
            QueryFieldSchema(
                field.id,
                field.type,
                field.presence,
                field.nullability,
                field.allowedOperators,
                field.capabilities,
                setOf(QueryFieldId.Path(listOf("id"))),
            )
        }
        return QueryDocumentSchema(
            target,
            PlanningFixtures.schema.fields.values.map { field ->
                if (field.id == PlanningFixtures.identity) identity else field
            },
            PlanningFixtures.schema.searchScopes.values,
        )
    }

    private fun plan(
        invocation: NormalizedQueryInvocation,
        schema: QueryDocumentSchema,
    ): PageQueryPlan =
        (
            planner.plan(
                invocation,
                schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan as PageQueryPlan
}
