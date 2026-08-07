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
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class QueryPlannerResultConstraintTest {
    private val planner = QueryPlanner()

    @Test
    fun `static result constraints should reject unbounded stream and oversized page without fallback`() {
        val stream = NormalizedQueryInvocation(
            PlanningFixtures.target,
            QueryOperation.STREAM,
            QueryResultShape.DYNAMIC,
            NormalizedQueryInput.Stream(PlanningFixtures.recordQuery(), limit = 0),
        )
        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.UNBOUNDED_STREAM_DISALLOWED,
            "$.input.limit",
        ) {
            planner.plan(
                stream,
                PlanningFixtures.schema,
                PlanningConstraints(
                    QueryValidationMode.COMPATIBLE,
                    streamConstraint = StreamPlanningConstraint.BoundedOnly,
                ),
            )
        }

        planner.plan(
            PlanningFixtures.page(index = 5, size = 20, offset = 80),
            PlanningFixtures.schema,
            PlanningConstraints(
                QueryValidationMode.STRICT,
                pageConstraint = PagePlanningConstraint.MaximumWindow(100),
            ),
        ).assert().isInstanceOf(PlanningDecision.Planned::class.java)
        assertRejected(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionCode.PAGE_WINDOW_EXCEEDED,
            "$.input.page",
        ) {
            planner.plan(
                PlanningFixtures.page(index = 6, size = 20, offset = 100),
                PlanningFixtures.schema,
                PlanningConstraints(
                    QueryValidationMode.COMPATIBLE,
                    pageConstraint = PagePlanningConstraint.MaximumWindow(100),
                ),
            )
        }
        assertRejected(
            QueryRejectionCategory.INVALID_QUERY,
            QueryRejectionCode.INVALID_PAGE,
            "$.input.page",
        ) {
            planner.plan(
                PlanningFixtures.page(index = 5, size = 20, offset = 81),
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            )
        }
    }

    private fun assertRejected(
        category: QueryRejectionCategory,
        code: QueryRejectionCode,
        path: String,
        action: () -> Unit,
    ) {
        assertThrownBy<QueryRejectedException>(action).satisfies(
            Consumer { error ->
                error.rejection.category.assert().isEqualTo(category)
                error.rejection.code.assert().isEqualTo(code)
                error.rejection.path.toString().assert().isEqualTo(path)
            },
        )
    }
}
