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
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode

class MetricFilterValidationTest {
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

    @Test
    fun `scalar and fieldless filters pass`() {
        MatchAllFilter.requireScalarMetricFilterFields(null, schema)
        MatchNoneFilter.requireScalarMetricFilterFields(null, schema)
        DeletionFilter(DeletionState.ACTIVE).requireScalarMetricFilterFields(null, schema)
        ExistsFilter(QueryField("status")).requireScalarMetricFilterFields(null, schema)
        IsNotNullFilter(QueryField("status")).requireScalarMetricFilterFields(null, schema)
        ContainsFilter(QueryField("status"), "PAID").requireScalarMetricFilterFields(null, schema)
        ContainsAllFilter(QueryField("status"), listOf(json("PAID")))
            .requireScalarMetricFilterFields(null, schema)
        NotExistsFilter(QueryField("status")).requireScalarMetricFilterFields(null, schema)
    }

    @Test
    fun `unions without an array alternative pass`() {
        ExistsFilter(QueryField("flags")).requireScalarMetricFilterFields(null, schema)
    }

    @Test
    fun `unknown fields pass because the walker only guards known schema fields`() {
        ExistsFilter(QueryField("unknown")).requireScalarMetricFilterFields(null, schema)
    }

    @Test
    fun `array valued fields are rejected`() {
        assertThrows<QuerySchemaValidationException> {
            ExistsFilter(QueryField("tags")).requireScalarMetricFilterFields(null, schema)
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [tags] must be scalar; " +
                "array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `union fields with an array alternative are rejected`() {
        assertThrows<QuerySchemaValidationException> {
            ExistsFilter(QueryField("notes")).requireScalarMetricFilterFields(null, schema)
        }.message.assert().contains("[notes] must be scalar")
    }

    @Test
    fun `element scoped filters resolve fields relative to their parent`() {
        ExistsFilter(QueryField("note")).requireScalarMetricFilterFields(QueryField("orders"), schema)
        assertThrows<QuerySchemaValidationException> {
            ExistsFilter(QueryField("lines")).requireScalarMetricFilterFields(QueryField("orders"), schema)
        }.message.assert().isEqualTo(
            "Aggregation metric filter field [orders.lines] must be scalar; " +
                "array fields are not supported in metric filters.",
        )
    }

    @Test
    fun `boolean operands walk their children`() {
        assertThrows<QuerySchemaValidationException> {
            OrFilter(
                listOf(
                    ExistsFilter(QueryField("status")),
                    ExistsFilter(QueryField("tags")),
                ),
            ).requireScalarMetricFilterFields(null, schema)
        }.message.assert().contains("[tags] must be scalar")
    }

    @Test
    fun `element match filters are rejected before field inspection`() {
        assertThrows<QuerySchemaValidationException> {
            ElementMatchFilter(QueryField("tags"), ExistsFilter(QueryField("unknown")))
                .requireScalarMetricFilterFields(null, schema)
        }.message.assert().isEqualTo(
            "Aggregation metric filters do not support [ELEMENT_MATCH].",
        )
    }

    @Test
    fun `search filters are rejected`() {
        assertThrows<QuerySchemaValidationException> {
            SearchFilter("premium").requireScalarMetricFilterFields(null, schema)
        }.message.assert().isEqualTo(
            "Aggregation metric filters do not support search filters.",
        )
    }

    private fun json(value: Any?): JsonNode = JsonSerializer.valueToTree(value)
}
