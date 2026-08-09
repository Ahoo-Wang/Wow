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
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EventStreamCanonicalChecksumTest {
    @Test
    fun `map order and mathematical number representation should not change evidence`() {
        val first = linkedMapOf<String, Any?>(
            MessageRecords.AGGREGATE_ID to "001",
            MessageRecords.VERSION to 1,
            MessageRecords.BODY to listOf(linkedMapOf("value" to BigDecimal("1.00"), "name" to "created")),
        )
        val reordered = linkedMapOf<String, Any?>(
            MessageRecords.BODY to listOf(linkedMapOf("name" to "created", "value" to 1L)),
            MessageRecords.VERSION to BigDecimal("1.0"),
            MessageRecords.AGGREGATE_ID to "001",
        )

        evidence("001-1" to first).assert().isEqualTo(evidence("001-1" to reordered))
    }

    @Test
    fun `content changes should preserve identity evidence and change content evidence`() {
        val original = document("001", 1, listOf(mapOf("name" to "created")))
        val changed = document("001", 1, listOf(mapOf("name" to "changed")))

        val first = evidence("001-1" to original)
        val second = evidence("001-1" to changed)

        first.identityChecksum.assert().isEqualTo(second.identityChecksum)
        first.contentChecksum.assert().isNotEqualTo(second.contentChecksum)
    }

    @Test
    fun `aggregate and version order must be complete and continuous`() {
        evidence(
            "001-1" to document("001", 1, listOf(1, 2)),
            "001-3" to document("001", 3, listOf(3)),
            "002-1" to document("002", 1, listOf(1)),
        ).count.assert().isEqualTo(3)

        listOf(
            arrayOf("001-2" to document("001", 2, listOf(1))),
            arrayOf(
                "001-1" to document("001", 1, listOf(1)),
                "001-3" to document("001", 3, listOf(2)),
            ),
            arrayOf(
                "002-1" to document("002", 1, listOf(1)),
                "001-1" to document("001", 1, listOf(1)),
            ),
            arrayOf("other-1" to document("001", 1, listOf(1))),
            arrayOf("001-1" to document("001", 1, emptyList<Any>())),
        ).forEach { invalid ->
            assertThrownBy<IllegalArgumentException> { evidence(*invalid) }
        }
    }

    @Test
    fun `version must be an exact positive integer within the supported range`() {
        listOf(
            BigDecimal("1.5"),
            Long.MAX_VALUE,
            0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
        ).forEach { invalidVersion ->
            assertThrownBy<IllegalArgumentException> {
                evidence("001-$invalidVersion" to document("001", invalidVersion, listOf(1)))
            }
        }
    }

    private fun evidence(
        vararg documents: Pair<String, Map<String, Any?>>,
    ): EventStreamCanonicalChecksumEvidence = EventStreamCanonicalChecksumAccumulator().let { accumulator ->
        documents.forEach { (identity, document) -> accumulator.accept(identity, document) }
        accumulator.finish()
    }

    private fun document(
        aggregateId: String,
        version: Number,
        body: List<Any>,
    ): Map<String, Any?> = linkedMapOf(
        MessageRecords.AGGREGATE_ID to aggregateId,
        MessageRecords.VERSION to version,
        MessageRecords.BODY to body,
    )
}
