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

package me.ahoo.wow.query.internal.model

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsGrouping
import me.ahoo.wow.query.internal.analytics.AnalyticsMetric
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.normalization.NormalizedCondition
import me.ahoo.wow.query.internal.value.NonEmptyList
import org.junit.jupiter.api.Test

class QueryInvocationTest {

    private val namedAggregate = MaterializedNamedAggregate("sales", "order")
    private val target = QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
    private val analytics = AnalyticsQuery(
        userCondition = NormalizedCondition.All,
        grouping = AnalyticsGrouping.Global,
        metrics = NonEmptyList.of(AnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
    )

    @Test
    fun `target should materialize aggregate decorators`() {
        val decorator = object : NamedAggregateDecorator {
            override val namedAggregate: NamedAggregate = this@QueryInvocationTest.namedAggregate
        }

        val actual = QueryTarget(decorator, QueryDocumentKind.EVENT_STREAM)

        actual.namedAggregate.assert().isSameAs(namedAggregate)
        actual.documentKind.assert().isEqualTo(QueryDocumentKind.EVENT_STREAM)
    }

    @Test
    fun `should accept the complete operation result and input matrix`() {
        val invocations = listOf(
            QueryInvocation(
                target = target,
                operation = QueryOperation.SINGLE,
                resultShape = QueryResultShape.TYPED,
                input = QueryInput.Single(SingleQuery(Condition.ALL)),
            ),
            QueryInvocation(
                target = target,
                operation = QueryOperation.SINGLE,
                resultShape = QueryResultShape.DYNAMIC,
                input = QueryInput.Single(SingleQuery(Condition.ALL)),
            ),
            QueryInvocation(
                target = target,
                operation = QueryOperation.STREAM,
                resultShape = QueryResultShape.TYPED,
                input = QueryInput.Stream(ListQuery(Condition.ALL)),
            ),
            QueryInvocation(
                target = target,
                operation = QueryOperation.STREAM,
                resultShape = QueryResultShape.DYNAMIC,
                input = QueryInput.Stream(ListQuery(Condition.ALL)),
            ),
            QueryInvocation(
                target = target,
                operation = QueryOperation.PAGE,
                resultShape = QueryResultShape.TYPED,
                input = QueryInput.Page(PagedQuery(Condition.ALL)),
            ),
            QueryInvocation(
                target = target,
                operation = QueryOperation.PAGE,
                resultShape = QueryResultShape.DYNAMIC,
                input = QueryInput.Page(PagedQuery(Condition.ALL)),
            ),
            QueryInvocation(
                target = target,
                operation = QueryOperation.COUNT,
                resultShape = QueryResultShape.COUNT,
                input = QueryInput.Count(Condition.ALL),
            ),
            QueryInvocation(
                target = target,
                operation = QueryOperation.ANALYZE,
                resultShape = QueryResultShape.ANALYTICS,
                input = QueryInput.Analytics(analytics),
            ),
        )

        invocations.assert().hasSize(8)
    }

    @Test
    fun `analytics invocation should carry the semantic request`() {
        val invocation = QueryInvocation(
            target = target,
            operation = QueryOperation.ANALYZE,
            resultShape = QueryResultShape.ANALYTICS,
            input = QueryInput.Analytics(analytics),
        )

        (invocation.input as QueryInput.Analytics).query.assert().isSameAs(analytics)
    }

    @Test
    fun `should reject an input that does not match the operation`() {
        assertThrownBy<IllegalArgumentException> {
            QueryInvocation(
                target = target,
                operation = QueryOperation.SINGLE,
                resultShape = QueryResultShape.TYPED,
                input = QueryInput.Stream(ListQuery(Condition.ALL)),
            )
        }
    }

    @Test
    fun `should reject a result shape that does not match the operation`() {
        assertThrownBy<IllegalArgumentException> {
            QueryInvocation(
                target = target,
                operation = QueryOperation.COUNT,
                resultShape = QueryResultShape.DYNAMIC,
                input = QueryInput.Count(Condition.ALL),
            )
        }
    }

    @Test
    fun `execution and validation modes should remain independent`() {
        val combinations = QueryExecutionMode.entries.flatMap { executionMode ->
            QueryValidationMode.entries.map { validationMode -> executionMode to validationMode }
        }

        combinations.assert().hasSize(6)
        combinations.assert().contains(QueryExecutionMode.SHADOW to QueryValidationMode.STRICT)
    }
}
