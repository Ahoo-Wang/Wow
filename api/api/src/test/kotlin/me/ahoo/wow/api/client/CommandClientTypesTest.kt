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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.schedule.CancelSchedule
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

    data class AddItem(val item: String)

    private val cart = AggregateType("shop", "cart")

    @Test
    fun `a command request is a value that checks its facts`() {
        val request = CommandRequest.of(
            AddItem("apple"),
            id = "c-1",
            type = cart,
            targetTenant = "t",
            targetScope = Scope("o"),
            wait = WaitPlan.COMMITTED,
            timeout = 2.seconds,
            requestId = "r-1",
            expectedVersion = 3,
        )
        request.assert().isEqualTo(
            CommandRequest.of(AddItem("apple"), "c-1", cart, "t", Scope("o"), WaitPlan.COMMITTED, 2.seconds, "r-1", 3),
        )
        request.hashCode().assert().isEqualTo(request.toBuilder().build().hashCode())
        request.assert().isEqualTo(request)
        request.assert().isNotEqualTo(CommandRequest.of(AddItem("apple")))
        request.assert().isNotEqualTo("request")
        request.toString().assert().contains("requestId=r-1").contains("targetScope=")
        request.plan.assert().isEqualTo(WaitPlan.COMMITTED.within(2.seconds))
        CommandRequest.of(AddItem("apple")).plan.assert().isNull()
        CommandRequest.of(AddItem("apple"), timeout = 2.seconds).plan.assert().isEqualTo(WaitPlan.DEFAULT.within(2.seconds))
        assertThrows<IllegalArgumentException> { CommandRequest.of(AddItem("a"), timeout = 0.seconds) }
        assertThrows<IllegalArgumentException> { CommandRequest.of(AddItem("a"), requestId = " ") }
        assertThrows<IllegalArgumentException> { CommandRequest.of(AddItem("a"), expectedVersion = -1) }
        assertThrows<IllegalArgumentException> { CommandRequest.of(AddItem("a"), targetTenant = "") }
        assertThrows<IllegalArgumentException> { CommandRequest.of(AddItem("a"), id = " ") }
        assertThrows<IllegalArgumentException> { CommandRequest.of(Command("c", "r", cart.ref("t", "c-1"), AddItem("a"))) }
            .message.assert().contains("not a Command")
        assertThrows<IllegalArgumentException> { CommandRequest.of(CancelSchedule("k")) }
            .message.assert().contains("schedule command")
    }

    @Test
    fun `a builder sets each field, checks them as the constructor does, and a request keeps its own when rebuilt`() {
        val built = CommandRequest.builder(AddItem("apple")).id("c-1").type(cart).targetTenant("t")
            .targetScope(Scope("o")).wait(WaitPlan.ACCEPTED).timeout(1.seconds).requestId("r-1").expectedVersion(0)
            .build()
        built.assert().isEqualTo(
            CommandRequest.of(AddItem("apple"), "c-1", cart, "t", Scope("o"), WaitPlan.ACCEPTED, 1.seconds, "r-1", 0),
        )
        assertThrows<IllegalArgumentException> { CommandRequest.builder(AddItem("a")).requestId("").build() }
        // A request changed through its builder keeps its request id, so a retry stays the same request.
        built.toBuilder().wait(WaitPlan.COMMITTED).build().requestId.assert().isEqualTo("r-1")
    }

    @Test
    fun `a request to a ref takes its type, its id when assigned, and its tenant as the target`() {
        CommandRequest.to(cart.ref("t", "c-1"), AddItem("apple")).assert()
            .isEqualTo(CommandRequest.of(AddItem("apple"), id = "c-1", type = cart, targetTenant = "t"))
        CommandRequest.to(cart.unassigned("t"), AddItem("apple")).id.assert().isNull()
        CommandRequest.builder(cart.ref("t", "c-1"), AddItem("apple")).requestId("r-1").build().requestId
            .assert().isEqualTo("r-1")
    }

    @Test
    fun `a request's type is its own, else the one type that decides its command's class`() {
        val other = AggregateType("shop", "basket")
        typeOf(CommandRequest.of(AddItem("a"), type = other)) { listOf(cart) }.assert().isEqualTo(other)
        typeOf(CommandRequest.of(AddItem("a"))) { listOf(cart, cart) }.assert().isEqualTo(cart)
        assertThrows<IllegalArgumentException> { typeOf(CommandRequest.of(AddItem("a"))) { emptyList() } }
            .message.assert().contains("decided by no aggregate", "set CommandRequest.type")
        assertThrows<IllegalArgumentException> { typeOf(CommandRequest.of(AddItem("a"))) { listOf(cart, other) } }
            .message.assert().contains("several aggregates", "shop.basket", "set CommandRequest.type")
        assertThrows<IllegalArgumentException> { typeOf(CommandRequest.of(DeleteAggregate, "c-1")) { listOf(cart) } }
            .message.assert().isEqualTo("DeleteAggregate is a lifecycle command every aggregate takes: set CommandRequest.type.")
        typeOf(CommandRequest.of(DeleteAggregate, "c-1", type = cart)) { emptyList() }.assert().isEqualTo(cart)
    }

    @Test
    fun `a target tenant that is the caller's own changes nothing`() {
        targetTenantOf(CommandRequest.to(cart.ref("t", "c-1"), AddItem("a")), "t").assert().isNull()
        targetTenantOf(CommandRequest.to(cart.ref("t2", "c-1"), AddItem("a")), "t").assert().isEqualTo("t2")
        targetTenantOf(CommandRequest.of(AddItem("a")), "t").assert().isNull()
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
