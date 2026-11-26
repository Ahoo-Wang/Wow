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

package me.ahoo.wow.mongo.query

import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MatchAllFilter
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Round two of the deep review, security (suspicion 6 of #7021): MongoDB's `$group` keeps its key in `_id`, and the
 * compiler writes a metric under its alias beside it, so a metric aliased `_id`, which the query protocol admits,
 * names the group's key a second time. What the reference answers is what MongoDB should answer, or the alias is
 * refused as a query the caller can mend; anything else, the server's refusal answered as a storage failure among it,
 * is the defect.
 */
internal class R2IdAliasTest : MongoDifferentialFixtures() {
    @TestFactory
    fun `a metric or a group aliased _id answers what the reference answers`(): List<DynamicTest> = listOf(
        sameOrRefused("a count aliased _id") { aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("_id", MatchAllFilter)))) },
        sameOrRefused("a count aliased _id by status") {
            aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(STATUS, "status")),
                    metrics = listOf(AggregationMetric.Count("_id", MatchAllFilter)),
                ),
            )
        },
        sameOrRefused("a group aliased _id") {
            aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(STATUS, "_id")),
                    metrics = listOf(AggregationMetric.Count("count", MatchAllFilter)),
                ),
            )
        },
    )
}
