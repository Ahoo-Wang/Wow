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

package me.ahoo.wow.query.spec

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryCapability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GroupSpecTest {
    private val field = QueryField("state.createdAt")
    private val input = AggregationExpression.Field(QueryField("state.amount"))
    private val arithmetic = AggregationExpression.Binary(AggregationExpressionOperator.ADD, input, input)

    private val samples = listOf(
        AggregationGroup.Terms(field, "t"),
        AggregationGroup.Histogram(field, "h", 1.0),
        AggregationGroup.DateHistogram(field, "d", AggregationDateUnit.DAY),
        AggregationGroup.DatePart(field, "p", AggregationDatePart.DAY_OF_WEEK),
    )

    @Test
    fun `every group type has exactly one spec`() {
        samples.map { it.spec }.assert().containsExactlyElementsOf(GroupSpec.entries)
        samples.forEach { GroupSpec.of(it).assert().isEqualTo(it.spec) }
    }

    @Test
    fun `each group needs the capability of its kind`() {
        GroupSpec.entries.associateWith { it.capability }.assert().isEqualTo(
            mapOf(
                GroupSpec.TERMS to QueryCapability.AGGREGATE_TERMS,
                GroupSpec.HISTOGRAM to QueryCapability.AGGREGATE_NUMERIC,
                GroupSpec.DATE_HISTOGRAM to QueryCapability.AGGREGATE_TEMPORAL,
                GroupSpec.DATE_PART to QueryCapability.AGGREGATE_TEMPORAL,
            ),
        )
        GroupSpec.entries.filter { it.baseCost == OperatorCost.EXPENSIVE }.assert().containsExactly(GroupSpec.DATE_PART)
    }

    @Test
    fun `date parts, dense fill and expression inputs make a group expensive`() {
        listOf(
            AggregationGroup.Terms(field, "g") to OperatorCost.NORMAL,
            AggregationGroup.Terms(alias = "g", expression = arithmetic) to OperatorCost.EXPENSIVE,
            AggregationGroup.Histogram(field, "g", 1.0) to OperatorCost.NORMAL,
            AggregationGroup.Histogram(alias = "g", interval = 1.0, expression = arithmetic) to OperatorCost.EXPENSIVE,
            AggregationGroup.DateHistogram(field, "g", AggregationDateUnit.DAY) to OperatorCost.NORMAL,
            AggregationGroup.DateHistogram(field, "g", AggregationDateUnit.DAY, dense = true) to OperatorCost.EXPENSIVE,
            AggregationGroup.DatePart(field, "g", AggregationDatePart.HOUR_OF_DAY) to OperatorCost.EXPENSIVE,
            AggregationGroup.DatePart(field, "g", AggregationDatePart.HOUR_OF_DAY, dense = true) to
                OperatorCost.EXPENSIVE,
        ).forEach { (group, cost) -> group.spec.cost(group).assert().describedAs(group.toString()).isEqualTo(cost) }
    }

    @Test
    fun `a spec rejects a group of another type`() {
        assertThrows<IllegalArgumentException> { GroupSpec.TERMS.cost(AggregationGroup.Histogram(field, "g", 1.0)) }
    }
}
