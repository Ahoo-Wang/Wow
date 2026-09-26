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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.query.QueryAdmission
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Metric filters test each record as a whole value (MongoDB `$cond` guards, Elasticsearch filter aggregations), so
 * admission rejects array-valued fields, including unions with an array alternative, and the element-matching and
 * full-text filters that have no whole-value translation.
 */
class MetricFilterAdmissionTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "status" to scalarFixture(),
            "tags" to arrayFixture(scalarFixture()),
            "notes" to QueryValueSchema(
                QueryValueKind.UNION,
                alternatives = listOf(scalarFixture(), arrayFixture(scalarFixture())),
            ),
            "flags" to QueryValueSchema(
                QueryValueKind.UNION,
                alternatives = listOf(scalarFixture(), scalarFixture()),
            ),
            "orders" to arrayFixture(
                objectFixture(
                    "note" to scalarFixture(),
                    "lines" to arrayFixture(objectFixture()),
                ),
            ),
        )
    )

    private fun admit(filter: FilterExpression, element: String? = null) = QueryAdmission.Trusted.aggregate(
        AggregationQuery(
            elements = listOfNotNull(element?.let { AggregationElement(QueryField(it)) }),
            metrics = listOf(AggregationMetric.Count("count", filter = filter)),
        ),
        schema,
    )

    private fun rejected(filter: FilterExpression, element: String? = null): String? =
        assertThrows<QuerySchemaValidationException> { admit(filter, element) }.message

    @Test
    fun `scalar and fieldless filters pass`() {
        admit(MatchNoneFilter)
        admit(ExistsFilter(QueryField("status")))
        admit(IsNotNullFilter(QueryField("status")))
        admit(ContainsFilter(QueryField("status"), "PAID"))
        admit(NotExistsFilter(QueryField("status")))
    }

    @Test
    fun `unions without an array alternative pass`() {
        admit(ExistsFilter(QueryField("flags")))
    }

    @Test
    fun `array valued fields are rejected`() {
        rejected(ExistsFilter(QueryField("tags"))).assert().isEqualTo(
            "Aggregation metric filter field [tags] must be scalar; array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `union fields with an array alternative are rejected`() {
        rejected(ExistsFilter(QueryField("notes"))).assert().contains("[notes] must be scalar")
    }

    @Test
    fun `element scoped metric filters resolve fields relative to their element`() {
        admit(ExistsFilter(QueryField("note")), element = "orders")
        rejected(ExistsFilter(QueryField("lines")), element = "orders").assert().isEqualTo(
            "Aggregation metric filter field [orders.lines] must be scalar; array fields are not supported in " +
                "metric filters.",
        )
    }

    @Test
    fun `boolean operands are checked`() {
        rejected(OrFilter(listOf(ExistsFilter(QueryField("status")), ExistsFilter(QueryField("tags")))))
            .assert().contains("[tags] must be scalar")
    }

    @Test
    fun `element match filters are rejected`() {
        rejected(ElementMatchFilter(QueryField("orders"), ExistsFilter(QueryField("note")))).assert()
            .isEqualTo("Aggregation metric filters do not support [ELEMENT_MATCH].")
    }

    @Test
    fun `search filters are rejected`() {
        rejected(SearchFilter("premium", fields = setOf(QueryField("status")))).assert()
            .isEqualTo("Aggregation metric filters do not support search filters.")
    }
}
