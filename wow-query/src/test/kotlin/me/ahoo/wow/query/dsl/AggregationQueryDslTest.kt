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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import java.time.ZoneId

class AggregationQueryDslTest {

    @Test
    fun `aggregation DSL should preserve relative scopes and explicit aliases`() {
        val query = aggregation {
            filter { "state.status" eq "COMPLETED" }
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gt 0 }
            expand("discounts")
            terms("productId", alias = "product")
            count(alias = "count")
            sum("amount", alias = "total")
            sort { "total".desc() }
            limit(20)
        }

        query.elements.map { it.path.path }.assert().containsExactly("state.orders", "lines", "discounts")
        query.groupBy.assert().hasSize(1)
        query.metrics.assert().hasSize(2)
        query.limit.assert().isEqualTo(20)
    }

    @Test
    fun `aggregation DSL should map histogram positional arguments`() {
        val query = aggregation {
            histogram("amount", 50.0, "amountBucket")
            dateHistogram("createdAt", AggregationDateUnit.DAY, "day", ZoneId.of("Asia/Shanghai"))
            count("count")
        }

        query.groupBy.assert().containsExactly(
            AggregationGroup.Histogram(QueryField("amount"), "amountBucket", 50.0),
            AggregationGroup.DateHistogram(
                QueryField("createdAt"),
                "day",
                AggregationDateUnit.DAY,
                "Asia/Shanghai",
            ),
        )
    }

    @Test
    fun `aggregation DSL should add an any metric without another group`() {
        val query = aggregation {
            terms("productId", "productId")
            any("productName", "productName")
            count("count")
        }

        query.groupBy.assert().containsExactly(
            AggregationGroup.Terms(QueryField("productId"), "productId"),
        )
        query.metrics.assert().containsExactly(
            AggregationMetric.Any(QueryField("productName"), "productName"),
            AggregationMetric.Count("count"),
        )
    }

    @Test
    fun `aggregation DSL should build arithmetic metric expressions`() {
        val query = aggregation {
            sum(field("price") * field("quantity") - constant(10.0), "total")
            avg(field("amount") / constant(2.0) + field("fee"), "average")
        }

        query.metrics.assert().containsExactly(
            AggregationMetric.Numeric(
                AggregationFunction.SUM,
                AggregationExpression.Binary(
                    AggregationExpressionOperator.SUBTRACT,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.MULTIPLY,
                        AggregationExpression.Field(QueryField("price")),
                        AggregationExpression.Field(QueryField("quantity")),
                    ),
                    AggregationExpression.Constant(10.0),
                ),
                "total",
            ),
            AggregationMetric.Numeric(
                AggregationFunction.AVG,
                AggregationExpression.Binary(
                    AggregationExpressionOperator.ADD,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        AggregationExpression.Field(QueryField("amount")),
                        AggregationExpression.Constant(2.0),
                    ),
                    AggregationExpression.Field(QueryField("fee")),
                ),
                "average",
            ),
        )
    }
}
