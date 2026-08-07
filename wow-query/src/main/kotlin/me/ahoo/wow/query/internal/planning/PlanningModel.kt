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

import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.plan.PlannedCondition
import me.ahoo.wow.query.internal.plan.QueryPlan
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.schema.SchemaContractId
import me.ahoo.wow.query.internal.value.NonEmptyList

internal data class PlanningConstraints(
    val validationMode: QueryValidationMode,
    val mandatoryCondition: NormalizedCondition = NormalizedCondition.All,
    val streamConstraint: StreamPlanningConstraint = StreamPlanningConstraint.Unrestricted,
    val pageConstraint: PagePlanningConstraint = PagePlanningConstraint.Unrestricted,
    val analyticsConstraint: AnalyticsPlanningConstraint = AnalyticsPlanningConstraint.Unrestricted,
)

internal sealed interface StreamPlanningConstraint {
    data object Unrestricted : StreamPlanningConstraint

    data object BoundedOnly : StreamPlanningConstraint
}

internal sealed interface PagePlanningConstraint {
    data object Unrestricted : PagePlanningConstraint

    data class MaximumWindow(val value: Long) : PagePlanningConstraint {
        init {
            require(value > 0) {
                "Maximum page window must be positive."
            }
        }
    }
}

internal sealed interface AnalyticsPlanningConstraint {
    data object Unrestricted : AnalyticsPlanningConstraint

    data class Limits(
        val maxDimensions: Int,
        val maxMetrics: Int,
        val maxBucketLimit: Int,
    ) : AnalyticsPlanningConstraint {
        init {
            require(maxDimensions > 0 && maxMetrics > 0 && maxBucketLimit > 0) {
                "Analytics planning limits must be positive."
            }
        }
    }
}

internal data class ValidatedMandatory(
    val target: QueryTarget,
    val schemaContractId: SchemaContractId,
    val condition: PlannedCondition,
    val requiredCapabilities: RequiredCapabilities,
    val semanticTier: SemanticTier,
)

internal sealed interface PlanningDecision {
    data class Planned(val plan: QueryPlan) : PlanningDecision

    data class LegacyFallback(
        val issues: NonEmptyList<QueryRejection>,
        val validatedMandatory: ValidatedMandatory,
    ) : PlanningDecision
}

internal data class PlannedConditionResult(
    val condition: PlannedCondition,
    val requiredCapabilities: RequiredCapabilities,
    val semanticTier: SemanticTier,
)
