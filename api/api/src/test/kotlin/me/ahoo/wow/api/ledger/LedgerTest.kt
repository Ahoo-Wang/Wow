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
    fun `an entry's ops id names its service, its subscription's context and name, and its aggregate`() {
        Ledger.parkedId("eshop-payment-service", "eshop-payment", "PaymentGatewaySaga.onPaymentRequested", ref)
            .assert().isEqualTo(
                "eshop-payment-service:eshop-payment:PaymentGatewaySaga.onPaymentRequested:" +
                    "eshop-payment.payment:(0):alice-1",
            )
        Ledger.targetOf("eshop-payment", "svc:c:s:eshop-payment.payment:(0):alice-1").toString().assert()
            .isEqualTo("eshop-payment.wow.ledger/(0)/svc:c:s:eshop-payment.payment:(0):alice-1")
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId(" ", "c", "s", ref) }
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId("svc", " ", "s", ref) }
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId("svc", "c", " ", ref) }
    }

    @Test
    fun `the commands name the entry they act on, in plain fields`() {
        val commands: List<LedgerCommand> = listOf(
            ResumeParked.of("s", ref),
            SkipParked.of("s", ref, 1),
            MarkParkedRecoverable.of("s", ref, "recoverable"),
            ApplyParkedRetrySpec.of("s", ref, maxRetries = 3),
        )
        commands.map { it.subscription to it.toRef() }.toSet().assert().containsExactly("s" to ref)
        commands.map { listOf(it.context, it.aggregate, it.tenantId, it.id) }.toSet().assert()
            .containsExactly(listOf("eshop-payment", "payment", "(0)", "alice-1"))
        ResumeParked.of("s", "eshop-payment", "payment", "(0)", "alice-1").assert().isEqualTo(ResumeParked.of("s", ref))
        ApplyParkedRetrySpec.of("s", ref).initialBackoff.assert().isNull()
    }
}
