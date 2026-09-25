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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.NullNode
import java.util.concurrent.TimeUnit

class QueryAdmissionTest {
    private val epoch = scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS))
    private val schema = boundSchemaFixture(
        objectFixture(
            "timeoutAt" to epoch,
            "note" to scalarFixture(),
            "items" to arrayFixture(objectFixture("at" to epoch)),
        ),
    )

    @Test
    fun `admission hands backends a normalized query`() {
        val timeoutAt = QueryField("timeoutAt")
        val admitted = QueryAdmission.count(
            AndFilter(
                listOf(
                    AndFilter(listOf(ExistsFilter(timeoutAt), EqualFilter(QueryField("note"), NullNode.instance))),
                    BeforeNowFilter(timeoutAt),
                ),
            ),
            schema,
        )
        val operands = (admitted.query as AndFilter).operands
        operands.take(2).assert().containsExactly(ExistsFilter(timeoutAt), IsNullFilter(QueryField("note")))
        (operands[2] as LessThanFilter).field.assert().isEqualTo(timeoutAt)
        admitted.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
    }

    @Test
    fun `every filter of one aggregation resolves against the same moment`() {
        val at = QueryField("at")
        val admitted = QueryAdmission.aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("items"), AfterNowFilter(at))),
                metrics = listOf(AggregationMetric.Count("due", BeforeNowFilter(at))),
            ),
            schema,
        ).query
        val element = admitted.elements.single().filter as GreaterThanFilter
        val metric = admitted.metrics.single().filter as LessThanFilter
        element.value.assert().isEqualTo(metric.value)
    }
}
