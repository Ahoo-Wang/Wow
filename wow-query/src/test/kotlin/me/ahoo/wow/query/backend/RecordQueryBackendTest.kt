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

package me.ahoo.wow.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalQueryBackendApi::class)
class RecordQueryBackendTest {
    @Test
    fun `page result should be an immutable exact envelope`() {
        val record = BackendRecord(
            "order-1",
            NormalizedValue.ObjectValue(mapOf("value" to NormalizedValue.Text("one"))),
            BackendRecordCompleteness.COMPLETE,
        )
        val source = mutableListOf(record)
        val page = BackendPage(source, 1, BackendTotalRelation.EXACT, BackendPageConsistency.SAME_INPUT)
        source.clear()

        page.records.assert().containsExactly(record)
        page.assert().isEqualTo(
            BackendPage(listOf(record), 1, BackendTotalRelation.EXACT, BackendPageConsistency.SAME_INPUT),
        )
        @Suppress("UNCHECKED_CAST")
        assertThrownBy<UnsupportedOperationException> {
            (page.records as MutableList<BackendRecord>).clear()
        }
    }

    @Test
    fun `page window should reject invalid offset and size`() {
        assertThrownBy<IllegalArgumentException> { BackendPageWindow(-1, 1) }
        assertThrownBy<IllegalArgumentException> { BackendPageWindow(0, 0) }
    }

    @Test
    fun `execution options should retain every explicit budget without weakening the legacy constructor`() {
        val deadline = Instant.parse("2024-01-01T00:00:00Z")
        QueryBackendExecutionOptions(
            deadline = deadline,
            maxReturnedRecords = 10,
            maxScannedRecords = 100,
            maxPageWindow = 1_000,
            maxCandidateBuckets = 20,
            maxReturnedBuckets = 5,
            maxCursorPages = 3,
            allowDiskUse = true,
        ).assert().isEqualTo(
            QueryBackendExecutionOptions(deadline, 10, 100, 1_000, 20, 5, 3, true),
        )
        QueryBackendExecutionOptions(deadline, 10).assert().isEqualTo(
            QueryBackendExecutionOptions(deadline = deadline, maxReturnedRecords = 10),
        )

        assertThrownBy<IllegalArgumentException> {
            QueryBackendExecutionOptions(deadline = null, maxReturnedRecords = 0)
        }
        assertThrownBy<IllegalArgumentException> {
            QueryBackendExecutionOptions(deadline = null, maxReturnedRecords = null, maxScannedRecords = 0)
        }
        assertThrownBy<IllegalArgumentException> {
            QueryBackendExecutionOptions(deadline = null, maxReturnedRecords = null, maxPageWindow = 0)
        }
    }
}
