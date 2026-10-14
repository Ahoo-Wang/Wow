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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
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

    /** The operators of the [AddItem] commands decided, in order. */
    private val operators = CopyOnWriteArrayList<Operator?>()

    /** The [AddItem] commands decided, in order. */
    private val commands = CopyOnWriteArrayList<Command<*>>()

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
            operators += command.operator
            commands += command
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
    private val log = InMemoryEventLog(partitions = 2)
    private val executor = Executor(Kernel(log), laneCapacity = 1)
    private val coordinator = coordinator()

    private fun coordinator(defaultTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT) = WaitCoordinator(
        executor,
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, registry),
        WaitOptions(origin, maxTimeout = 30.seconds, defaultTimeout = defaultTimeout),
    )

    private fun gateway(
        authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
        transformers: List<CommandTransformer> = emptyList(),
        validators: List<CommandValidator> = emptyList(),
        tenants: TenantPolicy = TenantPolicy.CALLERS,
        coordinator: WaitCoordinator = this.coordinator,
        ids: IdGenerators = IdGenerators.DEFAULT,
        committed: CommittedRequests = CommittedRequests.NONE,
    ) = CommandGateway(
        ModelRegistry(listOf(model)),
        mapOf(CONTEXT to coordinator),
        authorizer = authorizer,
        transformers = transformers,
        validators = validators,
        ids = ids,
        tenants = tenants,
        committed = committed,
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
            // Another owner reusing the request id learns nothing of the cart: a new id, refused as a duplicate.
            val stranger = gateway.execute(open(caller = bob))
            stranger.error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
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
                Operator("admin", "ops", crossScope = true),
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
        conflict.assertRefused(FrameworkCodes.MALFORMED)
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

        // A default longer than the maximum is capped by it.
        val capped = gateway(coordinator = coordinator(60.seconds))
        capped.defaultWaitTimeout(CONTEXT).assert().isEqualTo(30.seconds)
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
    fun `a transformer changes the body and headers, the result is validated, and the wait is capped`(): Unit =
        runBlocking {
            val defaults = object : CommandTransformer {
                override val input = model.commandType(AddItem::class.java)

                override suspend fun transform(input: CommandInput): CommandTarget = CommandTarget(
                    input.type,
                    input.aggregateId,
                    (input.body as AddItem).copy(quantity = 5),
                    input.headers + ("App" to "web"),
                )
            }
            val validator = CommandValidator { _, body ->
                if ((body as AddItem).quantity == 5) emptyList() else listOf(Violation("quantity", "Five", "Not 5."))
            }
            val result = gateway(transformers = listOf(defaults), validators = listOf(validator)).execute(
                request(body = """{"sku":"apple","quantity":0}""", wait = WaitPlan.COMMITTED.within(10.minutes)),
            )
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.transformedFrom.assert().isEqualTo(TransformedFrom("shop.cart.AddItem", "cart-1"))
            commands.single().headers.assert().isEqualTo(
                mapOf(
                    "App" to "web",
                    CommandTransformer.TRANSFORMED_FROM to "shop.cart.AddItem",
                    CommandTransformer.TRANSFORMED_FROM_ID to "cart-1",
                ),
            )
            // The wait is capped by the coordinator's maximum when the command is built.
            commands.single().wait.timeout.assert().isEqualTo(30.seconds)
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
        FrameworkCodes.ALL.size.assert().isEqualTo(21)
        FrameworkCodes.ALL.forEach { it.assert().startsWith("wow.") }
        FrameworkCodes.ALL.assert().contains(
            FrameworkCodes.MALFORMED,
            FrameworkCodes.INVALID,
            FrameworkCodes.UNAUTHENTICATED,
            FrameworkCodes.AUTH_FORBIDDEN,
            FrameworkCodes.AGGREGATE_FORBIDDEN,
            FrameworkCodes.BUSY,
            FrameworkCodes.NODE_CLOSING,
            FrameworkCodes.STORAGE_UNAVAILABLE,
            FrameworkCodes.SCHEDULE_NOT_DUE,
            FrameworkCodes.SCHEDULE_JOB_INVALID,
        )
    }

    @Test
    fun `a store unavailable while a command runs fails it as retryable, its retry commits (F2)`(): Unit = runBlocking {
        var down = true
        val failing = object : EventLog by log {
            override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String) =
                if (down) {
                    throw StorageUnavailableException("The log is unavailable.", IllegalStateException("reset"))
                } else {
                    log.findByRequestId(type, tenantId, requestId)
                }
        }
        val coordinator = WaitCoordinator(
            Executor(Kernel(failing)),
            registry,
            SubscriptionDirectory.NONE,
            LocalSignalSender(origin.node, registry),
            WaitOptions(origin),
        )
        val gateway = gateway(coordinator = coordinator)

        val failed = gateway.execute(request(requestId = "add-1"))
        failed.status.assert().isEqualTo(CommandResult.Status.FAILED)
        failed.error!!.code.assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        failed.error!!.message.assert().isEqualTo("The log is unavailable.")
        failed.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        failed.requestId.assert().isEqualTo("add-1")
        decided.assert().isEmpty()

        down = false
        val retried = gateway.execute(request(requestId = "add-1"))
        retried.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        retried.version.assert().isEqualTo(1)
        gateway.execute(request(requestId = "add-1")).duplicate.assert().isTrue()
        decided.assert().hasSize(1)
    }

    @Test
    fun `a store unavailable at admission refuses the request as retryable, before it runs (F2)`(): Unit = runBlocking {
        val committed = CommittedRequests { _, _, _ -> throw StorageUnavailableException("The log is unavailable.") }
        val result = gateway(committed = committed).execute(
            request(command = "OpenCart", body = "{}", aggregateId = null, requestId = "open-1"),
        )
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        result.hop.assert().isEqualTo(Hop.accepted(CONTEXT))
        result.requestId.assert().isEqualTo("open-1")
        decided.assert().isEmpty()
    }

    @Test
    fun `every context of the registry needs a coordinator`() {
        runCatching { CommandGateway(ModelRegistry(listOf(model)), emptyMap()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { ModelRegistry(listOf(model, model)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
    }
}
