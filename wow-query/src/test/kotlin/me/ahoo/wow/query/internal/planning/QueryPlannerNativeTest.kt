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
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.JunctionOperator
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.Utf8Json
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class QueryPlannerNativeTest {
    private val planner = QueryPlanner()

    @Test
    fun `native conditions should require one coherent backend in every validation mode`() {
        val sameBackend = nativeJunction("mongo", "mongo")
        val nativePlan = planner.plan(
            PlanningFixtures.single(PlanningFixtures.recordQuery(sameBackend)),
            PlanningFixtures.schema,
            PlanningConstraints(QueryValidationMode.STRICT),
        ) as PlanningDecision.Planned
        nativePlan.plan.semanticTier.assert().isEqualTo(SemanticTier.NATIVE)
        nativePlan.plan.requiredCapabilities.nativeBackend.assert().isEqualTo(BackendId("mongo"))

        QueryValidationMode.entries.forEach { mode ->
            assertThrownBy<QueryRejectedException> {
                planner.plan(
                    PlanningFixtures.single(PlanningFixtures.recordQuery(nativeJunction("mongo", "elasticsearch"))),
                    PlanningFixtures.schema,
                    PlanningConstraints(mode),
                )
            }.satisfies(
                Consumer { error ->
                    error.rejection.category.assert().isEqualTo(QueryRejectionCategory.INVALID_QUERY)
                    error.rejection.code.assert().isEqualTo(QueryRejectionCode.NATIVE_BACKEND_CONFLICT)
                    error.rejection.path.toString().assert()
                        .isEqualTo("$.input.query.condition.children[1].backendId")
                },
            )

            assertThrownBy<QueryRejectedException> {
                planner.plan(
                    PlanningFixtures.single(),
                    PlanningFixtures.schema,
                    PlanningConstraints(
                        mode,
                        mandatoryCondition = NormalizedCondition.Native(BackendId("mongo"), Utf8Json("{}")),
                    ),
                )
            }.satisfies(
                Consumer { error ->
                    error.rejection.category.assert().isEqualTo(QueryRejectionCategory.UNSUPPORTED_FEATURE)
                    error.rejection.code.assert().isEqualTo(QueryRejectionCode.MANDATORY_NATIVE_NOT_ALLOWED)
                    error.rejection.path.toString().assert().isEqualTo("$.constraints.mandatoryCondition")
                },
            )
        }
    }

    private fun nativeJunction(first: String, second: String): NormalizedCondition = NormalizedCondition.Junction(
        JunctionOperator.AND,
        listOf<NormalizedCondition>(
            NormalizedCondition.Native(BackendId(first), Utf8Json("{}")),
            NormalizedCondition.Native(BackendId(second), Utf8Json("{}")),
        ),
    )
}
