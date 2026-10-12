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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Admission (design 02 §1; decisions D92–D97, D100, D106, D114, D115), including its security cases (roadmap 5b.1). */
class CommandGatewayTest {
    data class OpenCart(val note: String = "")
    data class AddItem(val sku: String, val quantity: Int = 1)
    data class Checkout(val note: String = "")
    data class ItemAdded(val sku: String, val quantity: Int)
    data class CartOpened(val note: String)

    class Cart {
        var items = 0
    }

    /** The commands the decide functions ran, by command id. */
    private val decided = CopyOnWriteArrayList<String>()

    /** While not null, deciding [AddItem] waits for it. */
    @Volatile
    private var hold: CompletableDeferred<Unit>? = null

    private val model = aggregate<Cart>(CONTEXT, "cart") {
        initial { Cart() }
        decide<OpenCart>(Creation.REQUIRED) { _, body ->
            decided += command.id
            Decision.events(CartOpened(body.note))
        }
        decideSuspending<AddItem>(Creation.ALLOWED) { _, body ->
            decided += command.id
            hold?.await()
            Decision.events(ItemAdded(body.sku, body.quantity))
        }
        decide<Checkout> { _, _ ->
            decided += command.id
            Decision.NoOp
        }
        evolve<CartOpened> { }
        evolve<ItemAdded> { items += it.quantity }
    }

    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val executor = Executor(Kernel(InMemoryEventLog(partitions = 2)), laneCapacity = 1)
    private val coordinator = WaitCoordinator(
        executor,
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, registry),
        WaitOptions(origin, maxTimeout = 30.seconds),
    )

    private fun gateway(
        authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
        rewriters: List<CommandRewriter> = emptyList(),
        validators: List<CommandValidator> = emptyList(),
    ) = CommandGateway(
        ModelRegistry(listOf(model)),
        mapOf(CONTEXT to coordinator),
        authorizer = authorizer,
        rewriters = rewriters,
        validators = validators,
    )

    private val alice = Caller(TENANT, Scope("alice", "home"), subject = "alice")
    private val bob = Caller(TENANT, Scope("bob", "home"), subject = "bob")
    private val admin = Caller("ops", Scope("admin"), subject = "admin", crossScope = true)

    private fun request(
        command: String = "AddItem",
        body: String = """{"sku":"apple"}""",
        caller: Caller = alice,
        aggregateId: String? = "cart-1",
        target: TargetScope? = null,
        requestId: String? = null,
        headers: Map<String, String> = emptyMap(),
        wait: WaitPlan? = null,
    ) = CommandRequest(
        context = CONTEXT,
        aggregate = "cart",
        command = command,
        body = CommandBody.of(body),
        caller = caller,
        aggregateId = aggregateId,
        target = target,
        requestId = requestId,
        headers = headers,
        wait = wait,
    )

    private fun CommandResult.assertRefused(code: String) {
        status.assert().isEqualTo(CommandResult.Status.REJECTED)
        error!!.code.assert().isEqualTo(code)
        hop.assert().isEqualTo(Hop.accepted(CONTEXT))
    }

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
    fun `conflicting or missing identity sources are refused as malformed`(): Unit = runBlocking {
        val gateway = gateway()
        gateway.execute(request(headers = mapOf("Wow-Tenant-Id" to "other")))
            .assertRefused(FrameworkCodes.MALFORMED)
        gateway.execute(request(headers = mapOf("wow.owner" to "bob"))).assertRefused(FrameworkCodes.MALFORMED)
        gateway.execute(request(requestId = " ")).assertRefused(FrameworkCodes.MALFORMED)
        gateway.execute(request(aggregateId = " ")).assertRefused(FrameworkCodes.MALFORMED)
        gateway.execute(request().copy(expectedVersion = -1)).assertRefused(FrameworkCodes.MALFORMED)
        val missing = gateway.execute(request(command = "Checkout", body = "{}", aggregateId = null))
        missing.assertRefused(FrameworkCodes.MALFORMED)
        missing.ref.id.assert().isEqualTo(CommandGateway.UNADDRESSED)
        decided.assert().isEmpty()
        // A request fact outside the framework's namespace travels with the command.
        gateway.execute(request(headers = mapOf("CoSec-App-Id" to "app"))).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `the authorizer sees the action before the body is decoded`(): Unit = runBlocking {
        val actions = CopyOnWriteArrayList<CommandAction>()
        val denying = CommandAuthorizer { caller, action ->
            actions += action
            if (caller == bob) Authorization.Denied("Bob may not add items.") else Authorization.Allowed
        }
        val gateway = gateway(authorizer = denying)
        gateway.execute(request(caller = bob, body = "{not json")).assertRefused(FrameworkCodes.AUTH_FORBIDDEN)
        actions.single().also {
            it.type.name.assert().isEqualTo("shop.cart.AddItem")
            it.ref.assert().isEqualTo(model.type.ref(TENANT, "cart-1"))
            it.scope.assert().isEqualTo(bob.scope)
            it.creation.assert().isEqualTo(Creation.ALLOWED)
        }
        val coded = gateway(authorizer = { _, _ -> Authorization.Denied("No.", "shop.cart.closed") })
        coded.execute(request()).assertRefused("shop.cart.closed")
        decided.assert().isEmpty()
    }

    @Test
    fun `a body that does not decode as the command is malformed`(): Unit = runBlocking {
        val gateway = gateway()
        gateway.execute(request(body = "{not json")).assertRefused(FrameworkCodes.MALFORMED)
        gateway.execute(request(body = """{"quantity":2}""")).assertRefused(FrameworkCodes.MALFORMED)
        gateway.execute(request(body = """{"sku":"apple","quantity":"many"}""")).assertRefused(FrameworkCodes.MALFORMED)
        gateway.execute(request(body = "null")).assertRefused(FrameworkCodes.MALFORMED)
        decided.assert().isEmpty()
        val tree = JsonMapper.builder().build().readTree("""{"sku":"pear","quantity":3}""")
        gateway.execute(request().copy(body = CommandBody.Tree(tree))).status
            .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `an unknown aggregate or command is unhandled`(): Unit = runBlocking {
        val gateway = gateway()
        gateway.execute(request(command = "RemoveItem")).assertRefused(FrameworkCodes.UNHANDLED)
        val unknown = gateway.execute(request().copy(aggregate = "wishlist", aggregateId = null))
        unknown.assertRefused(FrameworkCodes.UNHANDLED)
        unknown.ref.id.assert().isEqualTo(CommandGateway.UNADDRESSED)
        unknown.function.name.assert().isEqualTo("AddItem")
        gateway.execute(request(command = "Remove.")).function.name.assert().isEqualTo("Remove.")
        // The full model name resolves as well as the short one.
        gateway.execute(
            request(command = "shop.cart.AddItem")
        ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `validators' violations refuse the command together`(): Unit = runBlocking {
        val positive = CommandValidator { _, body ->
            if ((body as AddItem).quantity > 0) emptyList() else listOf(Violation("quantity", "Min", "At least 1."))
        }
        val known = CommandValidator { _, body ->
            if ((body as AddItem).sku == "apple") emptyList() else listOf(Violation("sku", "Known", "Unknown sku."))
        }
        val result = gateway(validators = listOf(positive, known))
            .execute(request(body = """{"sku":"kiwi","quantity":0}"""))
        result.assertRefused(FrameworkCodes.INVALID)
        result.error!!.violations.map { it.path }.assert().containsExactly("quantity", "sku")
        decided.assert().isEmpty()
    }

    @Test
    fun `rewriters change the body and headers, and are validated after`(): Unit = runBlocking {
        val seen = CopyOnWriteArrayList<Command<*>>()
        val defaults = CommandRewriter { command ->
            val body = command.body as AddItem
            command.edit { copy(body = body.copy(quantity = 5), headers = headers + ("App" to "web")) }
        }
        val recording = CommandRewriter { command -> command.also { seen += it } }
        val validator = CommandValidator { _, body ->
            if ((body as AddItem).quantity == 5) emptyList() else listOf(Violation("quantity", "Five", "Not 5."))
        }
        val result = gateway(rewriters = listOf(defaults, recording), validators = listOf(validator))
            .execute(request(body = """{"sku":"apple","quantity":0}""", wait = WaitPlan.COMMITTED.within(10.minutes)))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        seen.single().headers.assert().isEqualTo(mapOf("App" to "web"))
        // The wait is capped by the coordinator's maximum when the command is built.
        seen.single().wait.timeout.assert().isEqualTo(30.seconds)
    }

    @Test
    fun `a rewriter may not change the identity, the ids or the wait`(): Unit = runBlocking {
        val rewrites = listOf<Command<Any>.() -> Command<Any>>(
            { copy(ref = ref.copy(tenantId = "other")) },
            { copy(scope = Scope("bob")) },
            { copy(id = "forged") },
            { copy(requestId = "forged") },
            { copy(wait = WaitPlan.ACCEPTED) },
            { copy(expectedVersion = 3) },
            { copy(causation = Causation("root", "upstream")) },
            { copy(body = Checkout()) },
            { copy(headers = mapOf("Wow-Scope" to "owner=\"bob\"")) },
        )
        rewrites.forEach { rewrite ->
            val gateway = gateway(rewriters = listOf(CommandRewriter { it.edit(rewrite) }))
            runCatching { gateway.execute(request()) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java)
        }
        decided.assert().isEmpty()
    }

    @Test
    fun `a full lane answers busy, as a result`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>().also { hold = it }
        val gateway = gateway()
        val held = async(Dispatchers.Default) { gateway.execute(request()) }
        eventually { decided.size == 1 }
        val busy = gateway.execute(request())
        busy.status.assert().isEqualTo(CommandResult.Status.FAILED)
        busy.error!!.code.assert().isEqualTo(FrameworkCodes.BUSY)
        busy.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        gate.complete(Unit)
        held.await().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a closed executor answers node closing, as a result`(): Unit = runBlocking {
        executor.close()
        val result = gateway().execute(request())
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
    }

    @Test
    fun `an accepted-only plan is answered at admission and runs detached`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>().also { hold = it }
        val gateway = gateway()
        val results = withTimeout(5.seconds) { gateway.send(request(wait = WaitPlan.ACCEPTED)).toList() }
        val accepted = results.single()
        accepted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        accepted.hop.assert().isEqualTo(Hop.accepted(CONTEXT))
        eventually { decided.contains(accepted.commandId) }
        registry.size.assert().isEqualTo(0)
        // Closing waits for the detached command, then refuses new requests.
        val closing = async(Dispatchers.Default) { gateway.close() }
        delay(100)
        closing.isCompleted.assert().isFalse()
        gate.complete(Unit)
        withTimeout(5.seconds) { closing.await() }
        executor.activeLanes.assert().isEqualTo(0)
        val refused = gateway.execute(request())
        refused.status.assert().isEqualTo(CommandResult.Status.FAILED)
        refused.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        refused.hop.assert().isEqualTo(Hop.accepted(CONTEXT))
        // The detached command committed: the next one finds the aggregate at version 1.
        hold = null
        gateway().execute(request()).version.assert().isEqualTo(2)
    }

    @Test
    fun `a plan the runtime cannot follow is still rejected`(): Unit = runBlocking {
        val gateway = gateway()
        val result = gateway.execute(request(wait = WaitPlan.of(Hop.accepted(), Hop.accepted())))
        result.assertRefused(FrameworkCodes.WAIT_INVALID)
        decided.assert().isEmpty()
    }

    @Test
    fun `a detached command's outcome is only logged`(): Unit = runBlocking {
        val gateway = gateway()
        // Rejected by the kernel: the cart does not exist.
        val missing = gateway.execute(request(command = "Checkout", body = "{}", wait = WaitPlan.ACCEPTED))
        missing.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        // A route that fails: nobody waits, so the failure is logged and the scope goes on.
        val failing = WaitCoordinator(
            executor,
            registry,
            SubscriptionDirectory.NONE,
            LocalSignalSender(origin.node, registry),
            WaitOptions(origin),
            router = { _, _, _, _ -> error("unreachable") },
        )
        val throwing = CommandGateway(ModelRegistry(listOf(model)), mapOf(CONTEXT to failing))
        throwing.execute(request(wait = WaitPlan.ACCEPTED)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        throwing.close()
        gateway.close()
        decided.assert().isEmpty()
        runCatching { failing.accept(model, Command("c", "r", model.type.ref(TENANT, "x"), Checkout()), this) }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `admission values check their facts`() {
        listOf(
            { CommandRequest(" ", "cart", "AddItem", CommandBody.of("{}")) },
            { CommandRequest(CONTEXT, " ", "AddItem", CommandBody.of("{}")) },
            { CommandRequest(CONTEXT, "cart", " ", CommandBody.of("{}")) },
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
    fun `the framework codes are listed once each, in the wow namespace`() {
        FrameworkCodes.ALL.size.assert().isEqualTo(18)
        FrameworkCodes.ALL.forEach { it.assert().startsWith("wow.") }
        FrameworkCodes.ALL.assert().contains(
            FrameworkCodes.MALFORMED,
            FrameworkCodes.INVALID,
            FrameworkCodes.UNAUTHENTICATED,
            FrameworkCodes.AUTH_FORBIDDEN,
            FrameworkCodes.AGGREGATE_FORBIDDEN,
            FrameworkCodes.BUSY,
            FrameworkCodes.NODE_CLOSING,
        )
    }

    @Test
    fun `every context of the registry needs a coordinator`() {
        runCatching { CommandGateway(ModelRegistry(listOf(model)), emptyMap()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { ModelRegistry(listOf(model, model)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun Command<*>.edit(change: Command<Any>.() -> Command<Any>): Command<*> = (this as Command<Any>).change()

        const val CONTEXT = "shop"
        const val TENANT = "acme"
    }
}
