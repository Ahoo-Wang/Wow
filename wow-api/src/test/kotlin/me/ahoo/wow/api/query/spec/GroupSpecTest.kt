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

package me.ahoo.wow.api.query.spec

import com.fasterxml.jackson.annotation.JsonSubTypes
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GroupSpecTest {
    private val field = QueryField("createdAt")

    @Test
    fun `every wire group type has exactly one spec of the same name`() {
        val wire = AggregationGroup::class.java.getAnnotation(JsonSubTypes::class.java).value
            .associate { it.name to it.value.java }
        wire.keys.assert().containsExactlyInAnyOrderElementsOf(GroupSpec.entries.map { it.name })
        listOf(
            AggregationGroup.Terms(field, "t"),
            AggregationGroup.Histogram(field, "h", 1.0),
            AggregationGroup.DateHistogram(field, "d", AggregationDateUnit.DAY),
            AggregationGroup.DatePart(field, "p", AggregationDatePart.DAY_OF_WEEK),
        ).forEach { group -> wire.getValue(group.spec.name).assert().isEqualTo(group.javaClass) }
    }

    @Test
    fun `date parts need the temporal capability and have fixed domains`() {
        GroupSpec.DATE_PART.capability.assert().isEqualTo(QueryCapability.AGGREGATE_TEMPORAL)
        AggregationDatePart.DAY_OF_WEEK.domain.assert().isEqualTo(1..7)
        AggregationDatePart.DAY_OF_MONTH.domain.assert().isEqualTo(1..31)
        AggregationDatePart.HOUR_OF_DAY.domain.assert().isEqualTo(0..23)
        AggregationDatePart.MONTH_OF_YEAR.domain.assert().isEqualTo(1..12)
    }

    @Test
    fun `a dense date part must be the only group`() {
        val dense = AggregationGroup.DatePart(field, "weekday", AggregationDatePart.DAY_OF_WEEK, dense = true)
        AggregationQuery(groupBy = listOf(dense), metrics = listOf(AggregationMetric.Count("count")))
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(dense, AggregationGroup.Terms(QueryField("status"), "status")),
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationGroup.DatePart(field, "weekday", AggregationDatePart.HOUR_OF_DAY, timeZone = "Nowhere/Zone")
        }
    }
}
