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

package me.ahoo.wow.api.ledger

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** The failure ledger's names, ids and records across services (decision D141). */
class LedgerTest {
    private val ref = AggregateType("eshop-payment", "payment").ref("(0)", "alice-1")
    private val aggregate = LedgerAggregate.of(ref)

    @Test
    fun `the system commands have system names`() {
        Ledger.nameOf(ResumeParked::class.java).assert().isEqualTo("wow.ledger.Resume")
        Ledger.nameOf(SkipParked::class.java).assert().isEqualTo("wow.ledger.Skip")
        Ledger.nameOf(MarkParkedRecoverable::class.java).assert().isEqualTo("wow.ledger.MarkRecoverable")
        Ledger.nameOf(ApplyParkedRetrySpec::class.java).assert().isEqualTo("wow.ledger.ApplyRetrySpec")
        Ledger.nameOf(String::class.java).assert().isNull()
        Ledger.COMMANDS.keys.assert().containsExactly(
            Ledger.RESUME,
            Ledger.SKIP,
            Ledger.MARK_RECOVERABLE,
            Ledger.APPLY_RETRY_SPEC,
        )
    }

    @Test
    fun `an entry's ops id names its service, subscription and aggregate`() {
        Ledger.parkedId("eshop-payment-service", "PaymentGatewaySaga.onPaymentRequested", ref).assert()
            .isEqualTo("eshop-payment-service:PaymentGatewaySaga.onPaymentRequested:eshop-payment.payment:(0):alice-1")
        Ledger.targetOf("eshop-payment", "svc:s:eshop-payment.payment:(0):alice-1").toString().assert()
            .isEqualTo("eshop-payment.wow.ledger/(0)/svc:s:eshop-payment.payment:(0):alice-1")
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId(" ", "s", ref) }
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId("svc", " ", ref) }
    }

    @Test
    fun `a ledger aggregate is the ref it names`() {
        aggregate.assert().isEqualTo(LedgerAggregate("eshop-payment", "payment", "(0)", "alice-1"))
        aggregate.toRef().assert().isEqualTo(ref)
    }

    @Test
    fun `the commands name the entry they act on`() {
        val commands: List<LedgerCommand> = listOf(
            ResumeParked("s", aggregate),
            SkipParked("s", aggregate),
            MarkParkedRecoverable("s", aggregate, "RECOVERABLE"),
            ApplyParkedRetrySpec("s", aggregate, maxRetries = 3),
        )
        commands.map { it.subscription to it.aggregate }.toSet().assert().containsExactly("s" to aggregate)
        ApplyParkedRetrySpec("s", aggregate).minBackoff.assert().isNull()
    }

    @Test
    fun `the records carry an entry and its outcome`() {
        val parked = LedgerEntry(
            service = "svc",
            context = "eshop-payment",
            subscription = "s",
            aggregate = aggregate,
            fromVersion = 1,
            reason = "boom",
            failedAt = 5,
            retrySpec = LedgerRetrySpec(10, "PT3M", "PT2M"),
            status = "SCHEDULED",
            recordedAt = 6,
        )
        parked.recoverable.assert().isEqualTo(LedgerEntry.UNKNOWN)
        parked.retrySpec.applied.assert().isFalse()
        parked.request.assert().isNull()
        val resolved = LedgerResolution("svc", "eshop-payment", "s", aggregate, 5, LedgerResolution.RESUMED, 7)
        resolved.outcome.assert().isEqualTo("RESUMED")
        listOf(LedgerResolution.SKIPPED, LedgerResolution.RESOLVED, LedgerResolution.NOT_PARKED).assert()
            .containsExactly("SKIPPED", "RESOLVED", "NOT_PARKED")
    }
}
