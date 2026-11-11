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

package me.ahoo.wow.test

import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.result.CommandResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A route handler's test gives it a call Wow builds, and answers the commands it sends. */
class RouteCallsTest {
    data class Reserve(val sku: String)

    private val stock = AggregateType("inventory", "stock")
    private val mary = Caller.of(tenantId = "t1", scope = Scope(ownerId = "mary"), subject = "mary")

    @Test
    fun `a route call acts in its caller's tenant and scope, and names what a handler that uses more lacks`() {
        val call = routeCall(caller = mary, requestId = "r-7")
        call.tenantId.assert().isEqualTo("t1")
        call.scope.assert().isEqualTo(Scope(ownerId = "mary"))
        call.requestId.assert().isEqualTo("r-7")
        assertThrows<IllegalStateException> { call.queries }.message.assert().contains("routeCall(queries = ...)")
        assertThrows<IllegalStateException> { call.commands }.message.assert().contains("answeringCommandClient")
        routeCall().caller.assert().isEqualTo(Caller.ANONYMOUS)
    }

    @Test
    fun `an answering client answers each request as its target would, and keeps what it took`(): Unit = runBlocking {
        val client = answeringCommandClient { call ->
            when ((call.request.command as Reserve).sku) {
                "gone" -> call.rejected("inventory.stock.gone")
                "down" -> call.failed("wow.storage.unavailable")
                else -> call.committed(version = 4)
            }
        }
        val asMary = client.of(mary)
        asMary.wait(CommandRequest.to(stock.ref("t1", "mug"), Reserve("mug"))).version.assert().isEqualTo(4)
        assertThrows<CommandRejectedException> {
            runBlocking { asMary.send(CommandRequest.to(stock.ref("t1", "gone"), Reserve("gone"))) }
        }.result.error?.code.assert().isEqualTo("inventory.stock.gone")
        assertThrows<WowProblemException> {
            runBlocking { client.stream(CommandRequest.to(stock.ref("t1", "down"), Reserve("down"))).toList() }
        }.code.assert().isEqualTo("wow.storage.unavailable")
        // What it took, through every client of it, in order and as whom.
        client.calls.map { it.caller to (it.request.command as Reserve).sku }.assert()
            .containsExactly(mary to "mug", mary to "gone", null to "down")
        client.calls.first().ref.assert().isEqualTo(stock.ref("t1", "mug"))
        asMary.calls.assert().hasSize(3)
        // A request that names no aggregate gets its result's ref from the test.
        val unnamed = answeringCommandClient { it.committed(ref = stock.ref("t1", "new")) }
        unnamed.wait(CommandRequest.of(Reserve("new"), "new")).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a route call hands over the queries and commands it was given`() {
        val queries: Queries = mockk()
        val commands = answeringCommandClient { it.committed() }
        val call = routeCall(queries = queries, commands = commands)
        call.queries.assert().isSameAs(queries)
        call.commands.assert().isSameAs(commands)
    }

    @Test
    fun `a call's result takes its ref from the request, else from the test, else says what to give`(): Unit =
        runBlocking {
            // No type, or no id: the request names no aggregate.
            val untyped = CommandCall(mary, CommandRequest.of(Reserve("mug"), "mug"))
            untyped.ref.assert().isNull()
            assertThrows<IllegalStateException> { untyped.committed() }
                .message.assert().contains("give the result its ref")
            CommandCall(null, CommandRequest.of(Reserve("new"), null, type = stock)).ref.assert().isNull()
            // A target tenant first, then the caller's, then the default tenant.
            CommandCall(null, CommandRequest.of(Reserve("mug"), "mug", type = stock)).ref.assert()
                .isEqualTo(stock.ref(Caller.DEFAULT_TENANT, "mug"))
            CommandCall(mary, CommandRequest.of(Reserve("mug"), "mug", type = stock, targetTenant = "t2")).ref.assert()
                .isEqualTo(stock.ref("t2", "mug"))
            // A request id the request gives is the result's; it reads in a failure message.
            val given = CommandCall(mary, CommandRequest.of(Reserve("mug"), "mug", type = stock, requestId = "r-9"))
            given.committed().requestId.assert().isEqualTo("r-9")
            given.toString().assert().contains("caller=").contains("request=")
        }
}
