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

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/** A command's [toCommandRequest], and the [CommandClient] entries that take a command and send it (#6573). */
class CommandRequestsTest {
    data class AddItem(val item: String)

    private val cart = AggregateType("shop", "cart")

    @Test
    fun `a command's request should be the request of it and its id, the block setting the other fields`() {
        AddItem("a").toCommandRequest().assert().isEqualTo(CommandRequest.of(AddItem("a")))
        AddItem("a").toCommandRequest("c-1").assert().isEqualTo(CommandRequest.of(AddItem("a"), "c-1"))
        AddItem("a").toCommandRequest("c-1") {
            type(cart)
            targetScope(TargetScope.of("t", Scope("o")))
            wait(WaitPlan.COMMITTED)
            timeout(2.seconds)
            requestId("r-1")
            expectedVersion(3)
        }.assert().isEqualTo(
            CommandRequest.of(
                AddItem("a"),
                "c-1",
                cart,
                TargetScope.of("t", Scope("o")),
                WaitPlan.COMMITTED,
                2.seconds,
                "r-1",
                3
            ),
        )
    }

    @Test
    fun `an id the block sets should replace the one given before it`() {
        AddItem("a").toCommandRequest("c-1") { aggregateId("c-2") }.aggregateId.assert().isEqualTo("c-2")
        AddItem("a").toCommandRequest("c-1") { aggregateId(null) }.aggregateId.assert().isNull()
    }

    @Test
    fun `a request to a ref should be the request CommandRequest-to builds, with the fields its block sets`() {
        AddItem("a").toCommandRequest(to = cart.ref("t", "c-1")).assert()
            .isEqualTo(CommandRequest.to(cart.ref("t", "c-1"), AddItem("a")))
        AddItem("a").toCommandRequest(to = cart.unassigned("t")) { requestId("r-1") }.assert()
            .isEqualTo(
                CommandRequest.of(AddItem("a"), type = cart, targetScope = TargetScope.of("t"), requestId = "r-1")
            )
    }

    @Test
    fun `a target tenant set in one call should keep the scope named before it, and none should clear it`() {
        CommandRequest.builder(AddItem("a")).targetScope(TargetScope.of(scope = Scope("o"))).targetTenant("t").build()
            .targetScope.assert().isEqualTo(TargetScope.of("t", Scope("o")))
        CommandRequest.builder(AddItem("a")).targetTenant("t").targetTenant(null).build().targetScope.assert().isNull()
        CommandRequest.builder(AddItem("a")).targetScope(TargetScope.of("t", Scope("o"))).targetTenant(null).build()
            .targetScope.assert().isEqualTo(TargetScope.of(scope = Scope("o")))
    }

    @Test
    fun `a request to a ref in the block should be the request CommandRequest-to builds`() {
        AddItem("a").toCommandRequest { to(cart.ref("t", "c-1")) }.assert()
            .isEqualTo(CommandRequest.to(cart.ref("t", "c-1"), AddItem("a")))
        AddItem("a").toCommandRequest { to(cart.unassigned("t")) }.assert()
            .isEqualTo(CommandRequest.of(AddItem("a"), type = cart, targetScope = TargetScope.of("t")))
        CommandRequest.builder(AddItem("a")).to(cart.ref("t", "c-1")).requestId("r-1").build().assert()
            .isEqualTo(CommandRequest.of(AddItem("a"), "c-1", cart, TargetScope.of("t"), requestId = "r-1"))
    }

    @Test
    fun `a request made of a request should fail, saying to pass the request as it is`() {
        val request = CommandRequest.of(AddItem("a"), "c-1")
        assertThrows<IllegalArgumentException> { request.toCommandRequest { wait(WaitPlan.COMMITTED) } }
            .message.assert().isEqualTo(
                "A CommandRequest sends a command object, not another request: pass a request that is built as it " +
                    "is (context.call(request), client.wait(request)), and change its fields with " +
                    "request.toBuilder(), not in a block."
            )
    }

    @Test
    fun `each entry that takes a command should send the command's request`() {
        val client = mockk<CommandClient>()
        val sent = mutableListOf<CommandRequest<*>>()
        val result = mockk<CommandResult>()
        coEvery { client.send(capture(sent)) } returns result
        coEvery { client.wait(capture(sent)) } returns result
        every { client.stream(capture(sent)) } returns emptyFlow()
        runBlocking {
            client.send(AddItem("a")).assert().isSameAs(result)
            client.wait(AddItem("b"), "c-2") { wait(WaitPlan.COMMITTED) }.assert().isSameAs(result)
            client.stream(AddItem("c"), "c-3") { requestId("r-3") }.toList().assert().isEmpty()
            client.stream(AddItem("d")).toList().assert().isEmpty()
            client.send(AddItem("e"), to = cart.ref("t", "c-5"))
            client.wait(AddItem("f"), to = cart.ref("t", "c-6"))
            client.stream(AddItem("g"), to = cart.ref("t", "c-7")).toList()
        }
        sent.assert().containsExactly(
            AddItem("a").toCommandRequest(),
            AddItem("b").toCommandRequest("c-2") { wait(WaitPlan.COMMITTED) },
            CommandRequest.of(AddItem("c"), "c-3", requestId = "r-3"),
            CommandRequest.of(AddItem("d")),
            CommandRequest.to(cart.ref("t", "c-5"), AddItem("e")),
            CommandRequest.to(cart.ref("t", "c-6"), AddItem("f")),
            CommandRequest.to(cart.ref("t", "c-7"), AddItem("g")),
        )
    }

    @Test
    fun `a request given to an entry with a block should fail before the client is called`() {
        val client = mockk<CommandClient>()
        val request = slot<CommandRequest<*>>()
        coEvery { client.wait(capture(request)) } returns mockk()
        assertThrows<IllegalArgumentException> {
            runBlocking { client.wait(CommandRequest.of(AddItem("a"))) { wait(WaitPlan.COMMITTED) } }
        }.message.assert().contains("pass a request that is built as it is")
        request.isCaptured.assert().isFalse()
    }
}
