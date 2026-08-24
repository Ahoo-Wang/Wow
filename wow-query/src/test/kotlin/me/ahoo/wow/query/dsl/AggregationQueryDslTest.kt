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
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.LogicalField
import org.junit.jupiter.api.Test
import java.time.ZoneId

class AggregationQueryDslTest {

    @Test
    fun `aggregation DSL should preserve relative scopes and explicit aliases`() {
        val query = aggregation {
            filter { "state.status" eq "COMPLETED" }
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gt 0 }
            terms("productId", alias = "product")
            count(alias = "count")
            sum("amount", alias = "total")
            sort { "total".desc() }
            limit(20)
        }

        query.elements.map { it.path.value }.assert().containsExactly("state.orders", "lines")
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
            AggregationGroup.Histogram(LogicalField("amount"), "amountBucket", 50.0),
            AggregationGroup.DateHistogram(
                LogicalField("createdAt"),
                "day",
                AggregationDateUnit.DAY,
                "Asia/Shanghai",
            ),
        )
    }
}
