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

package me.ahoo.wow.query.analytics

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.analytics.AnalyticsBucket
import me.ahoo.wow.api.query.analytics.AnalyticsBucketWindow
import me.ahoo.wow.api.query.analytics.AnalyticsCompleteness
import me.ahoo.wow.api.query.analytics.AnalyticsConsistency
import me.ahoo.wow.api.query.analytics.AnalyticsCursor
import me.ahoo.wow.api.query.analytics.AnalyticsDimension
import me.ahoo.wow.api.query.analytics.AnalyticsGrouping
import me.ahoo.wow.api.query.analytics.AnalyticsMetric
import me.ahoo.wow.api.query.analytics.AnalyticsMetricKind
import me.ahoo.wow.api.query.analytics.AnalyticsPage
import me.ahoo.wow.api.query.analytics.AnalyticsQuery
import me.ahoo.wow.api.query.analytics.AnalyticsValue
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test

class AnalyticsPublicJsonContractTest {
    @Test
    fun `request and response should round trip with an opaque scalar cursor`() {
        val request = AnalyticsQuery(
            condition = Condition.eq("state.status", "PAID"),
            grouping = AnalyticsGrouping.by(listOf(AnalyticsDimension("status", "state.status"))),
            metrics = listOf(AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT)),
            window = AnalyticsBucketWindow(100, AnalyticsCursor("payload.signature")),
        )
        val requestJson = request.toJsonString()

        requestJson.contains("\"cursor\":\"payload.signature\"").assert().isTrue()
        requestJson.toObject<AnalyticsQuery>().assert().isEqualTo(request)

        val response = AnalyticsPage(
            listOf(
                AnalyticsBucket(
                    mapOf("status" to AnalyticsValue.of("PAID")),
                    mapOf("count" to AnalyticsValue.of(Long.MAX_VALUE)),
                ),
            ),
            AnalyticsCursor("next.signature"),
            AnalyticsConsistency.EVENTUAL,
            AnalyticsCompleteness.EXACT,
        )
        val responseJson = response.toJsonString()

        responseJson.contains("\"value\":\"${Long.MAX_VALUE}\"").assert().isTrue()
        responseJson.toObject<AnalyticsPage>().assert().isEqualTo(response)

        val finalPageJson = AnalyticsPage(
            emptyList(),
            null,
            AnalyticsConsistency.EVENTUAL,
            AnalyticsCompleteness.EXACT,
        ).toJsonString()
        finalPageJson.contains("\"nextCursor\":null").assert().isTrue()
    }
}
