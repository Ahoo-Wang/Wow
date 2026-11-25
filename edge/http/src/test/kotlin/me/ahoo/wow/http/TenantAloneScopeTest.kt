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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.ForwardedHeaders
import me.ahoo.wow.client.NamedCommand
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.internal.InternalServer
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.runtime.client.InProcessCommandClient
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * A request that names only a target tenant acts in that tenant with its sender's own scope, on every path that
 * sends it: the node's own client, an HTTP client and a saga (decision D483 ⑤, `TargetScope.scope` null). A path that
 * acts in another scope, none or the target's, fails here.
 */
class TenantAloneScopeTest {
    private val admin = Caller(TENANT, Scope("admin", "ops"), subject = "admin", crossScope = true)
    private val node = HttpNode(callers = CallerResolver { admin })
    private val server = InternalServer(node.routes)

    @AfterEach
    fun close() {
        server.close()
    }

    private val overHttp = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf(CONTEXT to URI.create("http://${server.address}"))))
        .user(UserCredential.FORWARDED)
        .manifestPeers(setOf(CONTEXT))
        .build()
        .commands
        .of(admin)

    /** An item for the cart [id] of [OTHER], naming no scope: [command] the object, or by its name over HTTP. */
    private fun request(id: String, command: Any = AddItem("apple")) = CommandRequest.builder(command).aggregateId(id)
        .type(cartModel.type).targetTenant(OTHER).wait(WaitPlan.COMMITTED).build()

    /** The item by its full type name, as a peer described by its Manifest is sent one. */
    private val named = NamedCommand("shop.cart.AddItem", WowJson.mapper.readTree("""{"sku":"apple"}"""))

    @Test
    fun `a target naming only a tenant should keep the sender's own scope in process, over HTTP and in a saga`() {
        runBlocking {
            InProcessCommandClient(node.gateway).of(admin).wait(request("c-1")).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        val inProcess = node.commands.last()
        runBlocking {
            withContext(
                ForwardedHeaders(mapOf("X-User" to "admin"))
            ) { overHttp.wait(request("c-2", named)) }.status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        val http = node.commands.last()
        listOf(inProcess, http).forEach { command ->
            command.ref.tenantId.assert().describedAs(command.ref.id).isEqualTo(OTHER)
            command.scope.assert().describedAs(command.ref.id).isEqualTo(admin.scope)
        }
        // A saga's command to a ref of another tenant: the commit's scope, as its sender's own.
        val commit = cartModel.type.ref(TENANT, "c-0")
        val event = StateEvent(
            commit,
            1,
            "c-0",
            "r-0",
            "shop.cart.AddItem",
            1L,
            listOf("added"),
            "state",
            scope = Scope("alice", "kitchen"),
            causation = Causation("root", "root"),
            operator = Operator("alice", TENANT),
        )
        val saga = ExecutionSagaContext(
            ExecutionId(CONTEXT, "CartSaga.onAdded", commit, 1),
            event,
            IdGenerators.DEFAULT,
            CommandTypes.SIMPLE_NAMES,
        )
        val derived = saga.commandsOf(listOf(CommandRequest.to(cartModel.type.ref(OTHER, "c-3"), AddItem("apple"))))
            .single()
        derived.ref.tenantId.assert().isEqualTo(OTHER)
        derived.scope.assert().isEqualTo(event.scope)
    }

    private companion object {
        const val OTHER = "globex"
    }
}
