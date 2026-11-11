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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Admission (design 02 §1; decisions D92–D97, D100, D106, D114, D115), including its security cases (roadmap 5b.1).
 */
class CommandGatewayTest : CommandGatewayFixtures() {
    @Test
    fun `identity comes from the caller and the ids from the server`(): Unit = runBlocking {
        val result = gateway().execute(request(command = "OpenCart", body = "{}", aggregateId = null))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        result.ref.tenantId.assert().isEqualTo(TENANT)
        UUID.fromString(result.ref.id).version().assert().isEqualTo(7)
        UUID.fromString(result.commandId).version().assert().isEqualTo(7)
        result.ref.id.assert().isNotEqualTo(result.commandId)
        result.requestId.assert().isEqualTo(result.commandId)
        result.correlationId.assert().isEqualTo(result.commandId)
        decided.assert().containsExactly(result.commandId)
    }

    @Test
    fun `a creation without an id keeps the id its request created, within the caller's scope (D191)`(): Unit =
        runBlocking {
            var drawn = 0
            val ids = IdGenerators(aggregates = AggregateIdGenerator { "cart-n${++drawn}" })
            val gateway = gateway(ids = ids, committed = CommittedRequests.of(log))
            fun open(caller: Caller = alice, requestId: String? = "open-1") =
                request(command = "OpenCart", body = "{}", aggregateId = null, caller = caller, requestId = requestId)

            val opened = gateway.execute(open())
            opened.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            opened.ref.id.assert().isEqualTo("cart-n1")
            // The retry takes the committed id, without drawing another, and is answered as a duplicate.
            val retried = gateway.execute(open())
            retried.ref.id.assert().isEqualTo("cart-n1")
            retried.duplicate.assert().isTrue()
            drawn.assert().isEqualTo(1)
            // Another owner reusing the request id learns nothing of the cart: a new id, answered as if nothing existed.
            val stranger = gateway.execute(open(caller = bob))
            stranger.error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
            stranger.ref.id.assert().isEqualTo("cart-n2")
            // Without a request id of its own, a request is new: it draws a new id.
            gateway.execute(open(requestId = null)).ref.id.assert().isEqualTo("cart-n3")
        }

    @Test
    fun `the command id should come from the global generator, a new aggregate's from its type's`(): Unit =
        runBlocking {
            val types = CopyOnWriteArrayList<AggregateType>()
            var global = 0
            val ids = IdGenerators(
                GlobalIdGenerator { "g-${++global}" },
                AggregateIdGenerator { type ->
                    types += type
                    "${type.name}-new"
                },
            )
            val gateway = gateway(ids = ids)
            gateway.ids.assert().isSameAs(ids)
            val opened = gateway.execute(request(command = "OpenCart", body = "{}", aggregateId = null))
            opened.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            opened.ref.id.assert().isEqualTo("cart-new")
            opened.commandId.assert().isEqualTo("g-1")
            opened.requestId.assert().isEqualTo("g-1")
            types.assert().containsExactly(model.type)
            val added = gateway.execute(request(aggregateId = "cart-new"))
            added.commandId.assert().isEqualTo("g-2")
            types.size.assert().isEqualTo(1)
        }

    @Test
    fun `the operator is the caller's subject in its own tenant, marked when acting elsewhere (D163)`(): Unit =
        runBlocking {
            gateway().execute(request(caller = alice)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            gateway().execute(request(caller = admin, target = TargetScope(TENANT, Scope("alice"))))
                .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            gateway().execute(request(caller = admin, aggregateId = "cart-2"))
                .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            gateway().execute(request(caller = Caller.ANONYMOUS, aggregateId = "cart-3"))
                .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            operators.assert().containsExactly(
                Operator("alice", TENANT),
                Operator("admin", "ops", actedElsewhere = true),
                Operator("admin", "ops"),
                null,
            )
        }

    @Test
    fun `a given request id is kept, and the command id is still the server's`(): Unit = runBlocking {
        val result = gateway().execute(request(requestId = "req-1"))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.requestId.assert().isEqualTo("req-1")
        result.commandId.assert().isNotEqualTo("req-1")
        val retried = gateway().execute(request(requestId = "req-1"))
        retried.duplicate.assert().isTrue()
        retried.commandId.assert().isNotEqualTo(result.commandId)
    }

    @Test
    fun `a caller cannot act on another owner's aggregate - impersonation`(): Unit = runBlocking {
        gateway().execute(request(caller = alice)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val result = gateway().execute(request(caller = bob))
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        decided.size.assert().isEqualTo(1)
    }

    @Test
    fun `a target scope without the cross-scope permission is forbidden, never ignored`(): Unit = runBlocking {
        val result = gateway().execute(request(caller = bob, target = TargetScope(scope = Scope("alice", "home"))))
        result.assertRefused(FrameworkCodes.AUTH_FORBIDDEN)
        decided.assert().isEmpty()
        // Not even a target naming the caller's own scope.
        gateway().execute(request(caller = bob, target = TargetScope(TENANT, bob.scope)))
            .assertRefused(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `a caller with the cross-scope permission acts in the target scope`(): Unit = runBlocking {
        gateway().execute(request(caller = alice)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val asAlice = gateway().execute(request(caller = admin, target = TargetScope(TENANT, Scope("alice"))))
        asAlice.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        asAlice.ref.tenantId.assert().isEqualTo(TENANT)
        // The target replaces the scope as a whole: naming another owner is checked by the kernel.
        gateway().execute(request(caller = admin, target = TargetScope(TENANT, Scope("bob"))))
            .error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        // Without a target, the admin acts in its own tenant: another aggregate.
        val own = gateway().execute(request(caller = admin))
        own.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        own.ref.tenantId.assert().isEqualTo("ops")
    }

    @Test
    fun `a fixed tenant holds whoever the caller is, the cross-scope rules unchanged (D116)`(): Unit = runBlocking {
        val type = AggregateType(CONTEXT, "cart")
        val tenants = TenantPolicy.fixed(mapOf(type to "t0"))
        val actions = CopyOnWriteArrayList<CommandAction>()
        val gateway = gateway(
            tenants = tenants,
            authorizer = { _, action -> Authorization.Allowed.also { actions += action } }
        )
        gateway.tenants.assert().isSameAs(tenants)

        val own = gateway.execute(request(wait = WaitPlan.COMMITTED))
        own.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        own.ref.tenantId.assert().isEqualTo("t0")
        actions.single().ref.tenantId.assert().isEqualTo("t0")

        // A cross-scope caller still acts in its target's scope, in the fixed tenant, which it may name.
        val target = TargetScope("t0", Scope("alice", "home"))
        gateway.execute(request(caller = admin, target = target, wait = WaitPlan.COMMITTED)).ref.tenantId
            .assert().isEqualTo("t0")
        gateway.execute(
            request(caller = admin, target = TargetScope(null, Scope("alice", "home")), wait = WaitPlan.COMMITTED)
        )
            .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val conflict = gateway.execute(request(caller = admin, target = TargetScope("ops", Scope("admin"))))
        conflict.assertRefused(FrameworkCodes.COMMAND_MALFORMED)
        conflict.error!!.message.assert().contains("fixed to t0")
        conflict.ref.tenantId.assert().isEqualTo("t0")
        gateway.execute(request(caller = bob, target = target)).assertRefused(FrameworkCodes.AUTH_FORBIDDEN)

        // Another aggregate type stays in its caller's tenant.
        TenantPolicy.fixed(
            mapOf(AggregateType(CONTEXT, "order") to "t0")
        ).tenantOf(type, alice).assert().isEqualTo(TENANT)
    }

    @Test
    fun `a fixed tenant is not blank, and no fixed tenant is the callers' policy`() {
        TenantPolicy.fixed(emptyMap()).assert().isSameAs(TenantPolicy.CALLERS)
        assertThrows<IllegalArgumentException> { TenantPolicy.fixed(mapOf(AggregateType(CONTEXT, "cart") to " ")) }
        TenantPolicy.CALLERS.tenantOf(
            AggregateType(CONTEXT, "cart"),
            alice,
            TargetScope("ops")
        ).assert().isEqualTo("ops")
    }

    @Test
    fun `a request that names no plan waits the coordinator's default timeout`(): Unit = runBlocking {
        val gateway = gateway(coordinator = coordinator(4.seconds))
        gateway.defaultWaitTimeout(CONTEXT).assert().isEqualTo(4.seconds)
        gateway.defaultWaitTimeout("elsewhere").assert().isEqualTo(WaitPlan.DEFAULT_TIMEOUT)
        gateway.execute(request()).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        gateway.execute(request(wait = WaitPlan.COMMITTED.within(2.seconds)))
        commands.map { it.wait }.assert()
            .containsExactly(WaitPlan.DEFAULT.within(4.seconds), WaitPlan.COMMITTED.within(2.seconds))
    }

    @Test
    fun `admission values check their facts`() {
        listOf(
            { ServerCommandRequest(" ", "cart", "AddItem", CommandBody.of("{}")) },
            { ServerCommandRequest(CONTEXT, " ", "AddItem", CommandBody.of("{}")) },
            { ServerCommandRequest(CONTEXT, "cart", " ", CommandBody.of("{}")) },
            { Caller(" ") },
            { Caller(subject = " ") },
            { TargetScope(" ") },
            { Authorization.Denied("No.", " ") },
        ).forEach { make ->
            runCatching { make() }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        CommandBody.of("{}").toString().assert().isEqualTo("Bytes(2)")
        Caller.ANONYMOUS.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
    }

    @Test
    fun `every context of the registry needs a coordinator`() {
        runCatching { CommandGateway(ModelRegistry(listOf(model)), emptyMap()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { ModelRegistry(listOf(model, model)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
