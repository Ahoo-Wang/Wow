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

package me.ahoo.wow.query.internal.plan

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.JunctionOperator
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedProjection
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.Utf8Json
import me.ahoo.wow.query.internal.planning.PlanningConstraints
import me.ahoo.wow.query.internal.planning.PlanningDecision
import me.ahoo.wow.query.internal.planning.PlanningFixtures
import me.ahoo.wow.query.internal.planning.QueryPlanner
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test

class QueryPlanFingerprintTest {

    private val planner = QueryPlanner()

    @Test
    fun `fingerprint should be stable across schema and projection registration order`() {
        val includeA = NormalizedProjection.Include(
            NonEmptyList.of(
                PlanningFixtures.path("state", "amount"),
                PlanningFixtures.path("state", "name"),
            ),
        )
        val includeB = NormalizedProjection.Include(
            NonEmptyList.of(
                PlanningFixtures.path("state", "name"),
                PlanningFixtures.path("state", "amount"),
                PlanningFixtures.path("state", "name"),
            ),
        )
        val reorderedSchema = QueryDocumentSchema(
            PlanningFixtures.schema.target,
            PlanningFixtures.schema.fields.values.reversed(),
            PlanningFixtures.schema.searchScopes.values.reversed(),
        )

        val first = plan(includeA, PlanningFixtures.schema)
        val second = plan(includeB, reorderedSchema)

        first.fingerprint.assert().isEqualTo(second.fingerprint)
        first.fingerprint.value.assert().isEqualTo(
            "49a4b6842c6441a3bd5c2a4a05a2e94c49bb6e88494454204d1a05b7fd669388",
        )
        (first.projection as PlannedProjection.Include).fields.values.assert().containsExactly(
            PlanningFixtures.amount,
            PlanningFixtures.name,
        )
    }

    @Test
    fun `fingerprint should preserve provenance and condition order`() {
        val name = predicate(PlanningFixtures.name, "Ada")
        val tenant = predicate(PlanningFixtures.tenant, "tenant-1")
        val first = planCondition(name, tenant)
        val swapped = planCondition(tenant, name)
        val reordered = planCondition(
            NormalizedCondition.Junction(JunctionOperator.OR, listOf(name, tenant)),
            NormalizedCondition.All,
        )
        val reverseOrder = planCondition(
            NormalizedCondition.Junction(JunctionOperator.OR, listOf(tenant, name)),
            NormalizedCondition.All,
        )

        first.fingerprint.assert().isNotEqualTo(swapped.fingerprint)
        reordered.fingerprint.assert().isNotEqualTo(reverseOrder.fingerprint)
        first.fingerprint.value.length.assert().isEqualTo(64)
        QueryPlanFingerprint.VERSION.assert().isEqualTo(1)
    }

    @Test
    fun `fingerprint should preserve Mongo object entry order`() {
        val first = NormalizedValue.ObjectValue(
            linkedMapOf(
                "a" to NormalizedValue.Int64(1),
                "b" to NormalizedValue.Int64(2),
            ),
        )
        val reversed = NormalizedValue.ObjectValue(
            linkedMapOf(
                "b" to NormalizedValue.Int64(2),
                "a" to NormalizedValue.Int64(1),
            ),
        )

        fingerprintFor(first).assert().isNotEqualTo(fingerprintFor(reversed))
    }

    @Test
    fun `planned collections should be defensively immutable`() {
        val plan = plan(NormalizedProjection.All, PlanningFixtures.schema)

        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (plan.sort as MutableList<PlannedSort>).clear()
        }
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (plan.requiredCapabilities.fieldRequirements as MutableMap<QueryFieldId, Set<*>>).clear()
        }
    }

    @Test
    fun `fingerprint should encode record window sort origin and native binding`() {
        val unbounded = planInvocation(
            NormalizedQueryInvocation(
                PlanningFixtures.target,
                QueryOperation.STREAM,
                QueryResultShape.DYNAMIC,
                NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), 0),
            ),
        )
        val bounded = planInvocation(
            NormalizedQueryInvocation(
                PlanningFixtures.target,
                QueryOperation.STREAM,
                QueryResultShape.DYNAMIC,
                NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), 1),
            ),
        )
        unbounded.fingerprint.assert().isNotEqualTo(bounded.fingerprint)

        val firstPage = planInvocation(PlanningFixtures.page(index = 1, size = 20, offset = 0))
        val secondPage = planInvocation(PlanningFixtures.page(index = 2, size = 20, offset = 20))
        firstPage.fingerprint.assert().isNotEqualTo(secondPage.fingerprint)
        val explicitIdentity = planInvocation(
            PlanningFixtures.page(
                PlanningFixtures.recordQuery(
                    sort = listOf(PlanningFixtures.sort(PlanningFixtures.path("aggregateId"))),
                ),
            ),
        )
        firstPage.fingerprint.assert().isNotEqualTo(explicitIdentity.fingerprint)

        val mongo = nativePlan("mongo", "{}")
        mongo.fingerprint.assert().isNotEqualTo(nativePlan("mongo", "{\"x\":1}").fingerprint)
        mongo.fingerprint.assert().isNotEqualTo(nativePlan("elasticsearch", "{}").fingerprint)
    }

    private fun plan(
        projection: NormalizedProjection,
        schema: QueryDocumentSchema,
    ): SingleQueryPlan =
        (
            planner.plan(
                PlanningFixtures.single(
                    PlanningFixtures.recordQuery(projection = projection),
                    QueryResultShape.DYNAMIC,
                ),
                schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan as SingleQueryPlan

    private fun planCondition(
        user: NormalizedCondition,
        mandatory: NormalizedCondition,
    ): SingleQueryPlan =
        (
            planner.plan(
                PlanningFixtures.single(PlanningFixtures.recordQuery(user)),
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT, mandatory),
            ) as PlanningDecision.Planned
            ).plan as SingleQueryPlan

    private fun planInvocation(invocation: NormalizedQueryInvocation): QueryPlan =
        (
            planner.plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ) as PlanningDecision.Planned
            ).plan

    private fun nativePlan(backend: String, payload: String): QueryPlan {
        val condition = NormalizedCondition.Native(BackendId(backend), Utf8Json(payload))
        return planInvocation(PlanningFixtures.single(PlanningFixtures.recordQuery(condition)))
    }

    private fun fingerprintFor(value: NormalizedValue): PlanFingerprint {
        val base = plan(NormalizedProjection.All, PlanningFixtures.schema)
        val condition = PlannedCondition.Predicate(PlanningFixtures.name, PredicateOperator.EQ, value)
        val plan = SingleQueryPlan.create(
            base.target,
            base.schemaContractId,
            EnforcedFilter(condition, PlannedCondition.All),
            base.resultShape,
            base.projection,
            base.sort,
            base.requiredCapabilities,
            base.semanticTier,
        )
        return QueryPlanFingerprint.compute(plan)
    }

    private fun predicate(
        field: QueryFieldId,
        value: String,
    ): NormalizedCondition.Predicate {
        val logical =
            when (field) {
                is QueryFieldId.System -> me.ahoo.wow.query.internal.normalization.LogicalField.System(field.kind)
                is QueryFieldId.Path -> PlanningFixtures.path(*field.segments.toTypedArray())
            }
        return NormalizedCondition.Predicate(logical, PredicateOperator.EQ, NormalizedValue.Text(value))
    }
}
