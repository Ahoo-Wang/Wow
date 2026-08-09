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

package me.ahoo.wow.apiclient.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.analytics.AnalyticsBucketWindow
import me.ahoo.wow.api.query.analytics.AnalyticsCompleteness
import me.ahoo.wow.api.query.analytics.AnalyticsConsistency
import me.ahoo.wow.api.query.analytics.AnalyticsGrouping
import me.ahoo.wow.api.query.analytics.AnalyticsMetric
import me.ahoo.wow.api.query.analytics.AnalyticsMetricKind
import me.ahoo.wow.api.query.analytics.AnalyticsPage
import me.ahoo.wow.api.query.analytics.AnalyticsQuery
import org.junit.jupiter.api.Test
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class SnapshotAnalyticsQueryApiTest {
    @Test
    fun `should keep analytics client separate from legacy snapshot query interface`() {
        SnapshotAnalyticsQueryApi::class.java.getMethod("analyze", AnalyticsQuery::class.java)
            .getAnnotation(PostExchange::class.java)
            .value.assert().isEqualTo("snapshot/analyze")
        SnapshotQueryApi::class.java.methods.map { it.name }.assert().doesNotContain("analyze")
    }

    @Test
    fun `should delegate reactive analytics extension`() {
        val expected = AnalyticsPage(
            buckets = emptyList(),
            nextCursor = null,
            consistency = AnalyticsConsistency.EVENTUAL,
            completeness = AnalyticsCompleteness.EXACT,
        )
        val api = object : ReactiveSnapshotAnalyticsQueryApi {
            override fun analyze(query: AnalyticsQuery): Mono<AnalyticsPage> = Mono.just(expected)
        }

        query().analyze(api).test().expectNext(expected).verifyComplete()
    }

    private fun query(): AnalyticsQuery = AnalyticsQuery(
        grouping = AnalyticsGrouping.global(),
        metrics = listOf(AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT)),
        window = AnalyticsBucketWindow(1),
    )
}
