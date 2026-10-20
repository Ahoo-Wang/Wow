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

package me.ahoo.wow.api.client

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/** The values of the [CommandClient] (decisions D257, D259): plain values that check their facts. */
class CommandClientTypesTest {
    private fun result(status: CommandResult.Status) = CommandResult(
        commandId = "c",
        requestId = "r",
        correlationId = "c",
        ref = AggregateType("shop", "cart").ref("t", "c-1"),
        hop = Hop.committed("shop"),
        function = FunctionInfo(FunctionKind.COMMAND, "shop", "cart", "AddItem"),
        status = status,
        error = ErrorInfo("shop.cart.full", "Full."),
        signalTime = 0,
    )

    @Test
    fun `send options are values that check their facts`() {
        val options = SendOptions.of(
            wait = WaitPlan.COMMITTED,
            timeout = 2.seconds,
            requestId = "r-1",
            expectedVersion = 3,
            targetTenant = "t",
            targetScope = Scope("o"),
        )
        options.assert().isEqualTo(
            SendOptions.of(WaitPlan.COMMITTED, 2.seconds, "r-1", 3, "t", Scope("o")),
        )
        options.hashCode().assert().isEqualTo(
            SendOptions.of(WaitPlan.COMMITTED, 2.seconds, "r-1", 3, "t", Scope("o")).hashCode()
        )
        options.assert().isEqualTo(options)
        options.assert().isNotEqualTo(SendOptions.DEFAULT)
        options.assert().isNotEqualTo("options")
        options.toString().assert().contains("requestId=r-1").contains("targetScope=")
        options.plan.assert().isEqualTo(WaitPlan.COMMITTED.within(2.seconds))
        options.targeted.assert().isTrue()
        SendOptions.of(targetScope = Scope("o")).targeted.assert().isTrue()
        SendOptions.DEFAULT.targeted.assert().isFalse()
        SendOptions.DEFAULT.plan.assert().isNull()
        SendOptions.of(wait = WaitPlan.ACCEPTED).plan.assert().isEqualTo(WaitPlan.ACCEPTED)
        assertThrows<IllegalArgumentException> { SendOptions.of(timeout = 0.seconds) }
        assertThrows<IllegalArgumentException> { SendOptions.of(requestId = " ") }
        assertThrows<IllegalArgumentException> { SendOptions.of(expectedVersion = -1) }
        assertThrows<IllegalArgumentException> { SendOptions.of(targetTenant = "") }
    }

    @Test
    fun `a rejection and a problem say what happened`() {
        val rejected = CommandRejectedException(result(CommandResult.Status.REJECTED))
        rejected.code.assert().isEqualTo("shop.cart.full")
        rejected.message.assert().contains("shop.cart/t/c-1").contains("Full.")
        assertThrows<IllegalArgumentException> { CommandRejectedException(result(CommandResult.Status.FAILED)) }
        val problem = WowProblemException("wow.command.busy", 429, RetryHint(true, true, 1.seconds), "Busy.")
        problem.result.assert().isNull()
        problem.cause.assert().isNull()
        problem.message.assert().isEqualTo("Busy.")
        problem.retryHint.retryAfter.assert().isEqualTo(1.seconds)
    }

    @Test
    fun `a retry hint is a value`() {
        val hint = RetryHint(retryable = true, sameRequestId = true)
        hint.assert().isEqualTo(RetryHint(true, true, null))
        hint.hashCode().assert().isEqualTo(RetryHint(true, true).hashCode())
        hint.assert().isEqualTo(hint)
        hint.assert().isNotEqualTo(RetryHint.NONE)
        hint.assert().isNotEqualTo("hint")
        hint.toString().assert().isEqualTo("RetryHint(retryable=true, sameRequestId=true, retryAfter=null)")
        RetryHint.NONE.retryable.assert().isFalse()
        RetryHint.NONE.sameRequestId.assert().isFalse()
    }
}
