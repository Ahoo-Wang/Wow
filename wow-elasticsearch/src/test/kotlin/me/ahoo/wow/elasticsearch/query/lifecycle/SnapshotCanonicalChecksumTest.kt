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

package me.ahoo.wow.elasticsearch.query.lifecycle

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.SnapshotRecords
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SnapshotCanonicalChecksumTest {
    @Test
    fun `should ignore snapshot time and canonicalize object order and numeric representation`() {
        val first = checksum(
            linkedMapOf(
                MessageRecords.AGGREGATE_ID to "order-1",
                MessageRecords.VERSION to 1,
                SnapshotRecords.SNAPSHOT_TIME to 1L,
                "state" to linkedMapOf("quantity" to 1, "name" to "cart"),
            ),
        )
        val equivalent = checksum(
            linkedMapOf(
                "state" to linkedMapOf("name" to "cart", "quantity" to BigDecimal("1.00")),
                SnapshotRecords.SNAPSHOT_TIME to 9_999L,
                MessageRecords.VERSION to 1L,
                MessageRecords.AGGREGATE_ID to "order-1",
            ),
        )

        equivalent.assert().isEqualTo(first)
    }

    @Test
    fun `should change only content checksum when document content changes`() {
        val original = checksum(document("order-1", "created"))
        val changed = checksum(document("order-1", "paid"))

        changed.identityChecksum.assert().isEqualTo(original.identityChecksum)
        changed.contentChecksum.assert().isNotEqualTo(original.contentChecksum)
    }

    @Test
    fun `should reject unordered duplicate or mismatched identities`() {
        val accumulator = SnapshotCanonicalChecksumAccumulator()
        accumulator.accept("order-2", document("order-2", "created"))

        assertThrownBy<IllegalArgumentException> {
            accumulator.accept("order-1", document("order-1", "created"))
        }.hasMessageContaining("strictly ascending")
        assertThrownBy<IllegalArgumentException> {
            SnapshotCanonicalChecksumAccumulator().accept("order-1", document("order-2", "created"))
        }.hasMessageContaining("serialized aggregate id")
    }

    @Test
    fun `should reject cyclic or over-budget documents before producing evidence`() {
        val cyclic = linkedMapOf<String, Any?>()
        cyclic[MessageRecords.AGGREGATE_ID] = "order-1"
        cyclic[MessageRecords.VERSION] = 1
        cyclic["state"] = cyclic

        assertThrownBy<IllegalArgumentException> {
            SnapshotCanonicalChecksumAccumulator().accept("order-1", cyclic)
        }.hasMessageContaining("cycle")
        assertThrownBy<IllegalArgumentException> {
            SnapshotCanonicalChecksumAccumulator(
                SnapshotCanonicalChecksumLimits(maxPayloadBytesPerDocument = 4),
            ).accept("order-1", document("order-1", "created"))
        }.hasMessageContaining("payload limit")
    }

    private fun checksum(document: Map<String, Any?>): SnapshotCanonicalChecksumEvidence =
        SnapshotCanonicalChecksumAccumulator().apply { accept("order-1", document) }.finish()

    private fun document(identity: String, status: String): Map<String, Any?> = linkedMapOf(
        MessageRecords.AGGREGATE_ID to identity,
        MessageRecords.VERSION to 1,
        SnapshotRecords.SNAPSHOT_TIME to 1L,
        "state" to linkedMapOf("status" to status),
    )
}
