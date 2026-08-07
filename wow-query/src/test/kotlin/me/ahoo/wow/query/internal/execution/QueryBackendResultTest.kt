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

package me.ahoo.wow.query.internal.execution

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.query.internal.analytics.AnalyticsAlias
import me.ahoo.wow.query.internal.analytics.AnalyticsCompleteness
import me.ahoo.wow.query.internal.analytics.AnalyticsConsistency
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import org.junit.jupiter.api.Test

class QueryBackendResultTest {
    @Test
    fun `record and page must remain immutable with stable value semantics`() {
        val documentSource = linkedMapOf<String, NormalizedValue>(
            "payload" to NormalizedValue.Bytes(byteArrayOf(1, 2, 3)),
        )
        val record = BackendRecord(
            "order-1",
            NormalizedValue.ObjectValue(documentSource),
            BackendRecordCompleteness.COMPLETE,
        )
        val recordSource = mutableListOf(record)
        val page = BackendPage(
            recordSource,
            1,
            BackendTotalRelation.EXACT,
            BackendPageConsistency.SAME_INPUT,
        )
        val originalHash = page.hashCode()

        documentSource.clear()
        recordSource.clear()

        page.records.assert().containsExactly(record)
        page.hashCode().assert().isEqualTo(originalHash)
        page.assert().isEqualTo(
            BackendPage(
                listOf(record),
                1,
                BackendTotalRelation.EXACT,
                BackendPageConsistency.SAME_INPUT,
            ),
        )
        assertThrownBy<UnsupportedOperationException> {
            (page.records as MutableList).add(record)
        }
    }

    @Test
    fun `analytics bucket and page must defensively copy every collection boundary`() {
        val keyAlias = AnalyticsAlias("group")
        val metricAlias = AnalyticsAlias("count")
        val keySource = linkedMapOf(keyAlias to NormalizedValue.Text("A"))
        val metricSource = linkedMapOf(metricAlias to NormalizedValue.Int64(1))
        val bucket = BackendAnalyticsBucket(keySource, metricSource)
        val bucketSource = mutableListOf(bucket)
        val afterKeySource = mutableListOf<NormalizedValue>(NormalizedValue.Text("A"))
        val page = BackendAnalyticsPage(
            bucketSource,
            afterKeySource,
            AnalyticsConsistency.SNAPSHOT,
            AnalyticsCompleteness.EXACT,
        )
        val originalHash = page.hashCode()

        keySource.clear()
        metricSource.clear()
        bucketSource.clear()
        afterKeySource.clear()

        bucket.keys.assert().containsEntry(keyAlias, NormalizedValue.Text("A"))
        bucket.metrics.assert().containsEntry(metricAlias, NormalizedValue.Int64(1))
        page.buckets.assert().containsExactly(bucket)
        page.afterKey.assert().containsExactly(NormalizedValue.Text("A"))
        page.hashCode().assert().isEqualTo(originalHash)
        assertThrownBy<UnsupportedOperationException> {
            (bucket.keys as MutableMap)[keyAlias] = NormalizedValue.Text("B")
        }
        assertThrownBy<UnsupportedOperationException> {
            (page.buckets as MutableList).clear()
        }
        assertThrownBy<UnsupportedOperationException> {
            (page.afterKey as MutableList).clear()
        }
    }
}
