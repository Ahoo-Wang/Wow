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

package me.ahoo.wow.api.query.analytics

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.Condition
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class AnalyticsQueryTest {
    @Test
    fun `query should freeze collection boundaries and preserve value semantics`() {
        val dimensions = mutableListOf(AnalyticsDimension("status", "state.status"))
        val metrics = mutableListOf(AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT))
        val query = AnalyticsQuery(
            grouping = AnalyticsGrouping.by(dimensions),
            metrics = metrics,
            window = AnalyticsBucketWindow(100),
        )
        dimensions.clear()
        metrics.clear()

        query.grouping.dimensions.assert().hasSize(1)
        query.metrics.assert().hasSize(1)
        query.assert().isEqualTo(
            AnalyticsQuery(
                condition = Condition.ALL,
                grouping = AnalyticsGrouping.by(listOf(AnalyticsDimension("status", "state.status"))),
                metrics = listOf(AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT)),
                window = AnalyticsBucketWindow(100),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (query.metrics as MutableList<AnalyticsMetric>).clear()
        }
    }

    @Test
    fun `request invariants should reject ambiguous grouping metrics and cursors`() {
        assertThrownBy<IllegalArgumentException> {
            AnalyticsGrouping(AnalyticsGroupingKind.GLOBAL, listOf(AnalyticsDimension("a", "state.a")))
        }
        assertThrownBy<IllegalArgumentException> { AnalyticsGrouping.by(emptyList()) }
        assertThrownBy<IllegalArgumentException> {
            AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT, "state.count")
        }
        assertThrownBy<IllegalArgumentException> { AnalyticsMetric("sum", AnalyticsMetricKind.SUM) }
        assertThrownBy<IllegalArgumentException> {
            AnalyticsQuery(
                grouping = AnalyticsGrouping.global(),
                metrics = listOf(AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT)),
                window = AnalyticsBucketWindow(2),
            )
        }
        assertThrownBy<IllegalArgumentException> { AnalyticsCursor("a=") }
        assertThrownBy<IllegalArgumentException> { AnalyticsCursor("a".repeat(AnalyticsCursor.MAX_LENGTH + 1)) }
    }

    @Test
    fun `analytics values should be lossless canonical strings`() {
        AnalyticsValue.nullValue().assert().isEqualTo(AnalyticsValue(AnalyticsValueType.NULL, null))
        AnalyticsValue.of(true).assert().isEqualTo(AnalyticsValue(AnalyticsValueType.BOOLEAN, "true"))
        AnalyticsValue.of(Long.MAX_VALUE).value.assert().isEqualTo(Long.MAX_VALUE.toString())
        AnalyticsValue.of(BigDecimal("120.50")).value.assert().isEqualTo("120.50")
        AnalyticsValue.of(Instant.parse("2026-08-09T00:00:00Z")).value.assert()
            .isEqualTo("2026-08-09T00:00:00Z")

        listOf("01", "+1", "-0").forEach { invalid ->
            assertThrownBy<IllegalArgumentException> { AnalyticsValue(AnalyticsValueType.INT64, invalid) }
        }
        listOf("01.0", "1E+2", "-0.00").forEach { invalid ->
            assertThrownBy<IllegalArgumentException> { AnalyticsValue(AnalyticsValueType.DECIMAL, invalid) }
        }
        assertThrownBy<IllegalArgumentException> {
            AnalyticsValue(AnalyticsValueType.INSTANT, "2026-08-09T08:00:00+08:00")
        }
    }

    @Test
    fun `page should freeze and canonicalize alias maps`() {
        val keys = linkedMapOf(
            "z" to AnalyticsValue.of("last"),
            "a" to AnalyticsValue.of("first"),
        )
        val metrics = linkedMapOf("count" to AnalyticsValue.of(2L))
        val bucket = AnalyticsBucket(keys, metrics)
        val buckets = mutableListOf(bucket)
        val page = AnalyticsPage(
            buckets,
            AnalyticsCursor("next_page"),
            AnalyticsConsistency.EVENTUAL,
            AnalyticsCompleteness.EXACT,
        )
        keys.clear()
        metrics.clear()
        buckets.clear()

        bucket.keys.keys.assert().containsExactly("a", "z")
        bucket.metrics.assert().containsKey("count")
        page.buckets.assert().containsExactly(bucket)
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (bucket.keys as MutableMap<String, AnalyticsValue>).clear()
        }
    }
}
