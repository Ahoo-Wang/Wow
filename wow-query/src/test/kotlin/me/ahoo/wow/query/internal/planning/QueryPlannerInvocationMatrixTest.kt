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
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryValidationMode
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.normalization.NormalizedPage
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInput
import me.ahoo.wow.query.internal.normalization.NormalizedQueryInvocation
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.function.Consumer
import java.util.stream.Stream

class QueryPlannerInvocationMatrixTest {

    private val planner = QueryPlanner()

    @ParameterizedTest(name = "{0}/{1}/{2}")
    @MethodSource("invocationMatrix")
    fun `planner should enforce the complete normalized invocation matrix`(
        operationName: String,
        resultShapeName: String,
        inputName: String,
        expectedValid: Boolean,
    ) {
        val operation = QueryOperation.valueOf(operationName)
        val resultShape = QueryResultShape.valueOf(resultShapeName)
        val input = input(inputName)
        val invocation = NormalizedQueryInvocation(
            PlanningFixtures.target,
            operation,
            resultShape,
            input,
        )
        if (expectedValid) {
            planner.plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            ).assert().isInstanceOf(PlanningDecision.Planned::class.java)
            return
        }
        assertThrownBy<QueryRejectedException> {
            planner.plan(
                invocation,
                PlanningFixtures.schema,
                PlanningConstraints(QueryValidationMode.STRICT),
            )
        }.satisfies(
            Consumer { error ->
                error.rejection.code.assert().isEqualTo(QueryRejectionCode.INVALID_INVOCATION)
                error.rejection.path.toString().assert().isEqualTo("$.input")
            },
        )
    }

    companion object {
        @JvmStatic
        fun invocationMatrix(): Stream<Arguments> {
            val inputNames = listOf("single", "stream", "page", "count", "analytics")
            return QueryOperation.entries.flatMap { operation ->
                QueryResultShape.entries.flatMap { resultShape ->
                    inputNames.map { inputName ->
                        val input = input(inputName)
                        Arguments.of(
                            operation.name,
                            resultShape.name,
                            inputName,
                            accepts(operation, resultShape, input)
                        )
                    }
                }
            }.stream()
        }

        private fun input(name: String): NormalizedQueryInput {
            val query = PlanningFixtures.recordQuery()
            return when (name) {
                "single" -> NormalizedQueryInput.Single(query)
                "stream" -> NormalizedQueryInput.Stream(query, limit = 0)
                "page" -> NormalizedQueryInput.Page(query, NormalizedPage(1, 20, 0))
                "count" -> NormalizedQueryInput.Count(NormalizedCondition.All)
                "analytics" -> NormalizedQueryInput.Analytics(
                    AnalyticsQuery(
                        NormalizedCondition.All,
                        AnalyticsGrouping.Global,
                        NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
                    ),
                )

                else -> error("Unknown input: $name")
            }
        }

        private fun accepts(
            operation: QueryOperation,
            resultShape: QueryResultShape,
            input: NormalizedQueryInput,
        ): Boolean =
            when (operation) {
                QueryOperation.SINGLE -> input is NormalizedQueryInput.Single && resultShape.isRecord()
                QueryOperation.STREAM -> input is NormalizedQueryInput.Stream && resultShape.isRecord()
                QueryOperation.PAGE -> input is NormalizedQueryInput.Page && resultShape.isRecord()
                QueryOperation.COUNT -> input is NormalizedQueryInput.Count && resultShape == QueryResultShape.COUNT
                QueryOperation.ANALYZE -> {
                    input is NormalizedQueryInput.Analytics && resultShape == QueryResultShape.ANALYTICS
                }
            }

        private fun QueryResultShape.isRecord(): Boolean =
            this == QueryResultShape.TYPED || this == QueryResultShape.DYNAMIC
    }
}
