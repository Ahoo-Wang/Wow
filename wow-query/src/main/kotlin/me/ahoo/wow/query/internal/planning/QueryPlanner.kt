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

import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema

internal class QueryPlanner {
    fun plan(
        invocation: NormalizedQueryInvocation,
        schema: QueryDocumentSchema,
        constraints: PlanningConstraints,
    ): PlanningDecision {
        validateInvocation(invocation, schema)
        val conditionPlanner = QueryConditionPlanner(schema)
        val mandatory = conditionPlanner.plan(
            constraints.mandatoryCondition,
            QueryRejectionPath.ROOT.property("constraints").property("mandatoryCondition"),
            mandatory = true,
        ).let { result ->
            ValidatedMandatory(
                invocation.target,
                schema.contractId,
                result.condition,
                result.requiredCapabilities,
                result.semanticTier,
            )
        }
        return when (val input = invocation.input) {
            is NormalizedQueryInput.Analytics -> PlanningDecision.Planned(
                AnalyticsQueryPlanner(invocation, schema, conditionPlanner, constraints, mandatory).plan(input.query),
            )

            else -> RecordQueryPlanner(
                invocation,
                schema,
                conditionPlanner,
                constraints,
                mandatory,
            ).plan(input)
        }
    }

    private fun validateInvocation(
        invocation: NormalizedQueryInvocation,
        schema: QueryDocumentSchema,
    ) {
        if (invocation.target != schema.target) {
            rejectQuery(
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionPath.ROOT.property("target"),
                QueryRejectionCode.TARGET_SCHEMA_MISMATCH,
            )
        }
        if (!invocation.hasValidMatrix()) {
            rejectQuery(
                QueryRejectionCategory.INVALID_QUERY,
                QueryRejectionPath.ROOT.property("input"),
                QueryRejectionCode.INVALID_INVOCATION,
            )
        }
    }

    private fun NormalizedQueryInvocation.hasValidMatrix(): Boolean =
        when (operation) {
            QueryOperation.SINGLE -> input is NormalizedQueryInput.Single && hasRecordShape()
            QueryOperation.STREAM -> input is NormalizedQueryInput.Stream && hasRecordShape()
            QueryOperation.PAGE -> input is NormalizedQueryInput.Page && hasRecordShape()
            QueryOperation.COUNT -> input is NormalizedQueryInput.Count && resultShape == QueryResultShape.COUNT
            QueryOperation.ANALYZE -> input is NormalizedQueryInput.Analytics && resultShape == QueryResultShape.ANALYTICS
        }

    private fun NormalizedQueryInvocation.hasRecordShape(): Boolean =
        resultShape == QueryResultShape.TYPED || resultShape == QueryResultShape.DYNAMIC
}
