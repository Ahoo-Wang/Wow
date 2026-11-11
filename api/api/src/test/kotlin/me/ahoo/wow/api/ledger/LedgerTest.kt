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
        Ledger.nameOf(RetryParked::class.java).assert().isEqualTo("wow.ledger.RetryParked")
        Ledger.nameOf(SkipParked::class.java).assert().isEqualTo("wow.ledger.SkipParked")
        Ledger.nameOf(MarkParkedRecoverable::class.java).assert().isEqualTo("wow.ledger.MarkParkedRecoverable")
        Ledger.nameOf(ApplyParkedRetrySpec::class.java).assert().isEqualTo("wow.ledger.ApplyParkedRetrySpec")
        Ledger.nameOf(String::class.java).assert().isNull()
        Ledger.COMMANDS.keys.assert().containsExactly(
            Ledger.RETRY,
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
        // One id per entry, however a ':' or '%' in a name joins the fields.
        val type = ref.type
        val shifted = listOf(
            Ledger.parkedId("svc", "c", "s", type.ref("a:b", "c")),
            Ledger.parkedId("svc", "c", "s", type.ref("a", "b:c")),
            Ledger.parkedId("svc", "c:s", "x", type.ref("a", "c")),
            Ledger.parkedId("svc:c", "s", "x", type.ref("a", "c")),
            Ledger.parkedId("svc", "c", "s", type.ref("a", "%3A")),
            Ledger.parkedId("svc", "c", "s", type.ref("a", ":")),
            Ledger.parkedId("svc", "c", "s", AggregateType("a.b", "c").ref("t", "i")),
            Ledger.parkedId("svc", "c", "s", AggregateType("a", "b.c").ref("t", "i")),
        )
        shifted.toSet().assert().hasSize(shifted.size)
        Ledger.parkedId("svc", "c", "s", type.ref("a:b", "50%")).assert()
            .isEqualTo("svc:c:s:eshop-payment.payment:a%3Ab:50%25")
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId(" ", "c", "s", ref) }
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId("svc", " ", "s", ref) }
        assertThrownBy<IllegalArgumentException> { Ledger.parkedId("svc", "c", " ", ref) }
    }

    @Test
    fun `the commands name the entry they act on, in plain fields`() {
        val commands: List<LedgerCommand> = listOf(
            RetryParked.of("s", ref),
            SkipParked.of("s", ref, 1),
            MarkParkedRecoverable.of("s", ref, Recoverable.RECOVERABLE),
            ApplyParkedRetrySpec.of("s", ref, maxRetries = 3),
        )
        commands.map { it.subscription to it.toRef() }.toSet().assert().containsExactly("s" to ref)
        commands.map { listOf(it.context, it.aggregate, it.tenantId, it.id) }.toSet().assert()
            .containsExactly(listOf("eshop-payment", "payment", "(0)", "alice-1"))
        RetryParked.of("s", "eshop-payment", "payment", "(0)", "alice-1").assert().isEqualTo(RetryParked.of("s", ref))
        ApplyParkedRetrySpec.of("s", ref).initialBackoff.assert().isNull()
    }
}
