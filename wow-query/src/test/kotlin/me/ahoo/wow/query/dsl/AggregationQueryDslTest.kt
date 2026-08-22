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
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test

class AggregationQueryDslTest {
    @Test
    fun `should build aggregation query`() {
        val query = aggregationQuery {
            condition {
                "state.status" eq "PAID"
            }
            groupBy("state.country", "country")
            histogram("state.totalAmount", "amountBand", interval = 100.0, offset = 10.0)
            dateHistogram("eventTime", "month", AggregationDateUnit.MONTH, "Asia/Shanghai")
            count("orderCount")
            sum("state.totalAmount", "totalAmount")
            avg("state.totalAmount", "averageAmount")
            min("state.totalAmount", "minimumAmount")
            max("state.totalAmount", "maximumAmount")
            sort {
                "month".asc()
                "totalAmount".desc()
            }
            limit(20)
        }

        query.condition.operator.assert().isEqualTo(Operator.EQ)
        query.groupBy.assert().hasSize(3)
        query.groupBy[0].assert().isInstanceOf(AggregationGroup.Terms::class.java)
        query.metrics.assert().hasSize(5)
        query.metrics[0].assert().isInstanceOf(AggregationMetric.Count::class.java)
        query.sort.assert().containsExactly(
            Sort("month", Sort.Direction.ASC),
            Sort("totalAmount", Sort.Direction.DESC),
        )
        query.limit.assert().isEqualTo(20)
        query.toJsonString().toObject<AggregationQuery>().assert().isEqualTo(query)

        val defaultedGroups = aggregationQuery {
            histogram("version", "versionBand", 1.0)
            dateHistogram("snapshotTime", "day", AggregationDateUnit.DAY)
            count("count")
        }.groupBy
        (defaultedGroups[0] as AggregationGroup.Histogram).offset.assert().isEqualTo(0.0)
        (defaultedGroups[1] as AggregationGroup.DateHistogram).timeZone.assert().isEqualTo("UTC")
    }
}
