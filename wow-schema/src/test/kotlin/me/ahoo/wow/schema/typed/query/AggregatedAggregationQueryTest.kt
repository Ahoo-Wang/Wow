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

package me.ahoo.wow.schema.typed.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.typed.SnapshotAggregationElements
import me.ahoo.wow.schema.typed.SnapshotAggregationFields
import org.junit.jupiter.api.Test

class AggregatedAggregationQueryTest {
    private val generator = SchemaGeneratorBuilder().build()
    private val field = object : SnapshotAggregationFields<Order> {}
    private val element = object : SnapshotAggregationElements<Order> {}

    @Test
    fun `should construct every typed aggregation subtype`() {
        val query = AggregatedAggregationQuery(
            elements = listOf(AggregatedAggregationElement(element)),
            groupBy = listOf(
                AggregatedAggregationGroup.Terms(field, "terms"),
                AggregatedAggregationGroup.Histogram(field, "histogram", 10.0),
                AggregatedAggregationGroup.DateHistogram(field, "date", AggregationDateUnit.DAY),
            ),
            metrics = listOf(
                AggregatedAggregationMetric.Count("count"),
                AggregatedAggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregatedAggregationExpression.Field(field),
                    "sum",
                ),
            ),
        )

        query.elements.single().path.assert().isSameAs(element)
        query.groupBy.map { it.alias }.assert().containsExactly("terms", "histogram", "date")
        query.metrics.map { it.alias }.assert().containsExactly("count", "sum")
    }

    @Test
    fun `should generate typed aggregation query`() {
        val schema = generator.generateSchema(
            AggregatedAggregationQuery::class.java,
            Order::class.java,
        ).asJsonSchema()

        schema.actual.toString().assert().contains(
            "condition",
            "elements",
            "groupBy",
            "metrics",
            "sort",
            "limit",
            "DATE_HISTOGRAM",
            "NUMERIC",
            "FIELD",
        )
    }

    @Test
    fun `should expose separate element and scalar field enums`() {
        val elements = generator.generateSchema(
            SnapshotAggregationElements::class.java,
            Order::class.java,
        ).asJsonSchema().actual.toString()
        val fields = generator.generateSchema(
            SnapshotAggregationFields::class.java,
            Order::class.java,
        ).asJsonSchema().actual.toString()

        elements.assert().contains("state.items")
        check("state.items.quantity" !in elements)
        fields.assert().contains("state.items.quantity")
    }
}
