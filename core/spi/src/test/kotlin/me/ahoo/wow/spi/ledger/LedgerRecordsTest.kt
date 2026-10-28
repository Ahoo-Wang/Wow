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

package me.ahoo.wow.spi.ledger

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import me.ahoo.wow.spi.snapshot.SnapshotRebuildRequested
import org.junit.jupiter.api.Test

/**
 * The records a service publishes of its failure ledger, and the snapshot rebuild's system command and event: Wow's
 * own wire records (decision D304), plain classes with a data class's equality, hash and string.
 */
class LedgerRecordsTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")
    private val aggregate = LedgerAggregate.of(ref)

    private fun entry(retries: Int = 0) = LedgerEntry(
        service = "sales-service",
        context = "sales",
        subscription = "OrderSaga.onPlaced",
        aggregate = aggregate,
        fromVersion = 1,
        reason = "down",
        failedAt = 1,
        retries = retries,
        retrySpec = LedgerRetrySpec(3, "PT1M", "PT2M"),
        status = "SCHEDULED",
        recordedAt = 2,
    )

    private fun resolution(outcome: String = LedgerResolution.RESUMED) =
        LedgerResolution.of("sales-service", "sales", "OrderSaga.onPlaced", aggregate, 1, outcome, 3)

    @Test
    fun `an entry is a value, copied by Wow's own copy`() {
        valueSemantics(entry(), entry(), entry(1))
        entry().copyWith(retries = 1).assert().isEqualTo(entry(1))
        entry().recoverable.assert().isEqualTo(LedgerEntry.UNKNOWN)
        entry().retrySpec.applied.assert().isFalse()
        entry().request.assert().isNull()
    }

    @Test
    fun `a ledger aggregate is the ref it names`() {
        aggregate.assert().isEqualTo(LedgerAggregate("sales", "order", "tenant", "o-1"))
        aggregate.toRef().assert().isEqualTo(ref)
        valueSemantics(aggregate, LedgerAggregate.of("sales", "order", "tenant", "o-1"), aggregate.copyWith(id = "o-2"))
        aggregate.copyWith(id = "o-2").toRef().id.assert().isEqualTo("o-2")
    }

    @Test
    fun `a retry spec and a resolution are values built from their factories`() {
        valueSemantics(
            LedgerRetrySpec.of(3, "PT1M", "PT2M"),
            LedgerRetrySpec(3, "PT1M", "PT2M", false),
            LedgerRetrySpec.of(3, "PT1M", "PT2M", true),
        )
        LedgerRetrySpec.of(3, "PT1M", "PT2M").toString().assert()
            .isEqualTo("LedgerRetrySpec(maxRetries=3, initialBackoff=PT1M, timeout=PT2M, applied=false)")
        valueSemantics(resolution(), resolution(), resolution(LedgerResolution.SKIPPED))
        resolution().toString().assert().isEqualTo(
            "LedgerResolution(service=sales-service, context=sales, subscription=OrderSaga.onPlaced, " +
                "aggregate=$aggregate, failedAt=1, outcome=RESUMED, resolvedAt=3)",
        )
        LedgerResolution.of("s", "c", "p", aggregate, null, LedgerResolution.NOT_PARKED, 2).failedAt.assert().isNull()
        LedgerResolution("s", "c", "p", aggregate, outcome = "SKIPPED", resolvedAt = 2)
            .assert().isEqualTo(LedgerResolution.of("s", "c", "p", aggregate, null, "SKIPPED", 2))
        listOf(LedgerResolution.SKIPPED, LedgerResolution.RESOLVED, LedgerResolution.NOT_PARKED).assert()
            .containsExactly("SKIPPED", "RESOLVED", "NOT_PARKED")
    }

    @Test
    fun `the snapshot rebuild's command and event are values`() {
        valueSemantics(
            RebuildSnapshot.of(listOf("o-1")),
            RebuildSnapshot.of(listOf("o-1")),
            RebuildSnapshot.of(listOf("o-2"))
        )
        valueSemantics(
            SnapshotRebuildRequested.of(listOf("o-1")),
            SnapshotRebuildRequested.of(listOf("o-1")),
            SnapshotRebuildRequested.of(emptyList()),
        )
    }

    private fun valueSemantics(value: Any, same: Any, other: Any) {
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
