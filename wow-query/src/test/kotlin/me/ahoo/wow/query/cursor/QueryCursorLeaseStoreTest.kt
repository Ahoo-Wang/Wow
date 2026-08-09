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

@file:OptIn(ExperimentalQueryCursorApi::class)

package me.ahoo.wow.query.cursor

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class QueryCursorLeaseStoreTest {
    @Test
    fun `entry should defensively copy opaque payload and preserve value semantics`() {
        val payload = byteArrayOf(1, 2, 3)
        val entry = QueryCursorLeaseEntry(
            QueryCursorLeaseId("lease_1"),
            Instant.parse("2026-08-09T00:05:00Z"),
            QueryCursorPayloadFormat.WOW_QUERY_CURSOR_V1,
            payload,
        )
        payload[0] = 9
        val returned = entry.payload()
        returned[1] = 9

        entry.payload().toList().assert().containsExactly(1.toByte(), 2.toByte(), 3.toByte())
        entry.assert().isEqualTo(
            QueryCursorLeaseEntry(
                QueryCursorLeaseId("lease_1"),
                Instant.parse("2026-08-09T00:05:00Z"),
                QueryCursorPayloadFormat.WOW_QUERY_CURSOR_V1,
                byteArrayOf(1, 2, 3),
            ),
        )
        entry.hashCode().assert().isEqualTo(
            QueryCursorLeaseEntry(
                QueryCursorLeaseId("lease_1"),
                Instant.parse("2026-08-09T00:05:00Z"),
                QueryCursorPayloadFormat.WOW_QUERY_CURSOR_V1,
                byteArrayOf(1, 2, 3),
            ).hashCode(),
        )
    }

    @Test
    fun `lease identifiers and payload should be bounded`() {
        assertThrownBy<IllegalArgumentException> { QueryCursorLeaseId("not+padded=") }
        assertThrownBy<IllegalArgumentException> { QueryCursorStoreRevision("\u0000") }
        assertThrownBy<IllegalArgumentException> {
            QueryCursorLeaseEntry(
                QueryCursorLeaseId("lease"),
                Instant.parse("2026-08-09T00:05:00Z"),
                QueryCursorPayloadFormat.WOW_QUERY_CURSOR_V1,
                byteArrayOf(),
            )
        }
    }
}
