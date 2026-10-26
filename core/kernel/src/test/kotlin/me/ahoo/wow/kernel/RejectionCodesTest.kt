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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

class RejectionCodesTest {
    data class Open(val code: String)
    data class Opened(val code: String)

    class Account(val id: String)

    private val type = AggregateType("bank", "account")

    /** A decide function that rejects with whatever code it is given, as a dynamic code or a Java model may. */
    private val model = aggregate<Account>("bank", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body ->
            Decision.Rejected(Rejection(body.code, "Refused.", mapOf("limit" to 10)))
        }
        evolve<Opened> { }
    }

    @Test
    fun `a domain should not reject with a framework code`() {
        assertThrownBy<IllegalArgumentException> { Rejection.of("wow.command.busy", "Busy.") }
        assertThrownBy<IllegalArgumentException> { Decision.reject("wow.aggregate.not-found", "Gone.") }
    }

    @Test
    fun `a framework code a domain command decides should fail the command`() = aggregateSpec(model) {
        whenCommand(Open("wow.command.busy")) {
            val rejection = (outcome as CommandOutcome.Rejected).rejection
            rejection.code.assert().isEqualTo(KernelRejections.FAILED)
            rejection.message.assert().isEqualTo("Refused.")
            rejection.extensions.assert().isEqualTo(
                mapOf("limit" to 10, RejectionCodes.REJECTED_CODE to "wow.command.busy"),
            )
        }
    }

    @Test
    fun `a full code of a domain should stay as it is`() = aggregateSpec(model) {
        whenCommand(Open("tree.node-not-found")) { expectRejected("tree.node-not-found") }
        whenCommand(Open("bank.account.frozen")) { expectRejected("bank.account.frozen") }
        whenCommand(Open("frozen")) { expectRejected("bank.account.frozen") }
    }

    @Test
    fun `a framework command should keep its framework code`() {
        val rejection = Rejection(KernelRejections.UNHANDLED, "No such command.")
        RejectionCodes.qualified(rejection, type, Lifecycle.DELETE_AGGREGATE).assert().isSameAs(rejection)
        RejectionCodes.qualified(rejection, type, "bank.Open").code.assert().isEqualTo(KernelRejections.FAILED)
    }
}
