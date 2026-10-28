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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Admission of a command object ([CommandBody.Value], decision D258): it is taken as it is, without decoding, its
 * command type derived from its class, and every other check holds as for a JSON body.
 */
class CommandValueAdmissionTest {
    data class OpenCart(val note: String = "")
    data class AddItem(val sku: String, val quantity: Int = 1, val price: BigDecimal = BigDecimal.ONE)
    data class Unknown(val x: Int = 0)

    /** A command whose JSON form cannot be written: its property throws. */
    class Unwritable {
        @Suppress("unused")
        val broken: String get() = error("No JSON here.")
    }

    class Cart

    /** The bodies the decide functions saw, in order. */
    private val bodies = CopyOnWriteArrayList<Any>()
    private val commands = CopyOnWriteArrayList<Command<*>>()

    private val model = aggregate<Cart>(CONTEXT, "cart") {
        initial { Cart() }
        decide<OpenCart>(Creation.REQUIRED) { _, body ->
            bodies += body
            commands += command
            Decision.events(body)
        }
        decide<AddItem>(Creation.ALLOWED) { _, body ->
            bodies += body
            commands += command
            Decision.events(body)
        }
        decide<Unwritable> { _, _ -> Decision.NoOp }
        evolve<OpenCart> { }
        evolve<AddItem> { }
    }

    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog(partitions = 2))),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, registry),
        origin,
        WaitOptions(maxTimeout = 30.seconds),
    )

    private fun gateway(
        authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
        validators: List<CommandValidator> = emptyList(),
        transformers: List<CommandTransformer> = emptyList(),
    ) = CommandGateway(
        ModelRegistry(listOf(model)),
        mapOf(CONTEXT to coordinator),
        authorizer = authorizer,
        transformers = transformers,
        validators = validators,
    )

    private val alice = Caller(TENANT, Scope("alice"), subject = "alice")
    private val cart = AggregateType(CONTEXT, "cart")

    private fun request(command: Any, ref: String? = "cart-1", tenant: String = TENANT, caller: Caller = alice) =
        ServerCommandRequest.builder(command, if (ref == null) cart.unassigned(tenant) else cart.ref(tenant, ref))
            .caller(caller)
            .wait(WaitPlan.COMMITTED)
            .build()

    @Test
    fun `a command object is taken as it is, its type derived from its class`(): Unit = runBlocking {
        val body = AddItem("apple", 2, BigDecimal("1.50"))
        val request = request(body)
        request.command.assert().isEqualTo(AddItem::class.java.name)
        request.aggregateId.assert().isEqualTo("cart-1")
        request.tenantId.assert().isEqualTo(TENANT)
        val result = gateway().execute(request)
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.function.name.assert().isEqualTo("AddItem")
        // Not encoded and decoded again: the very object, its decimal exact (D122).
        bodies.single().assert().isSameAs(body)
        (bodies.single() as AddItem).price.scale().assert().isEqualTo(2)
        commands.single().ref.assert().isEqualTo(cart.ref(TENANT, "cart-1"))
    }

    @Test
    fun `an unassigned ref is a creation that leaves its id to admission`(): Unit = runBlocking {
        val request = request(OpenCart("hi"), ref = null)
        request.aggregateId.assert().isNull()
        val result = gateway().execute(request)
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.ref.isAssigned.assert().isTrue()
        // A command that does not create its aggregate needs an id.
        val refused = gateway().execute(
            ServerCommandRequest.builder(Unwritable(), cart.unassigned(TENANT)).caller(alice).build()
        )
        refused.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
    }

    @Test
    fun `a request naming the command type by name takes an object of that type only`(): Unit = runBlocking {
        val named = ServerCommandRequest.builder(CONTEXT, "cart", "AddItem", CommandBody.Value(AddItem("pear")))
            .caller(alice)
            .aggregateId("cart-1")
            .build()
        gateway().execute(named).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val mismatched = ServerCommandRequest.builder(CONTEXT, "cart", "AddItem", CommandBody.Value(OpenCart()))
            .caller(alice)
            .aggregateId("cart-1")
            .build()
        val result = gateway().execute(mismatched)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        result.error!!.message.assert().contains("shop.cart.AddItem")
    }

    @Test
    fun `a class the aggregate does not decide is unhandled, as is an aggregate not served`(): Unit = runBlocking {
        val unknown = gateway().execute(request(Unknown()))
        unknown.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
        unknown.error!!.message.assert().contains(Unknown::class.java.name)
        val elsewhere = gateway().execute(
            ServerCommandRequest.builder(
                AddItem("x"),
                AggregateType("elsewhere", "cart").ref(TENANT, "c")
            ).caller(alice).build()
        )
        elsewhere.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
    }

    @Test
    fun `a ref in another tenant than the caller's is malformed`(): Unit = runBlocking {
        val result = gateway().execute(request(AddItem("apple"), tenant = "other"))
        result.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        result.error!!.message.assert().contains("tenant other").contains("tenant $TENANT")
        bodies.assert().isEmpty()
    }

    @Test
    fun `a command object that writes a NUL character, or that is not written as JSON, is malformed`(): Unit =
        runBlocking {
            gateway().execute(request(AddItem("ap\u0000ple"))).error!!.code.assert()
                .isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            val unwritable = gateway().execute(request(Unwritable()))
            unwritable.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            unwritable.error!!.message.assert().contains("not written as JSON")
            bodies.assert().isEmpty()
        }

    @Test
    fun `the authorizer, the validators and the transformers see the command object`(): Unit = runBlocking {
        val seen = CopyOnWriteArrayList<CommandAction>()
        val authorizer = CommandAuthorizer { _, action ->
            seen += action
            Authorization.Allowed
        }
        val positive = CommandValidator { _, body ->
            if ((body as AddItem).quantity > 0) emptyList() else listOf(Violation("quantity", "min", "At least 1."))
        }
        val invalid = gateway(authorizer, listOf(positive)).execute(request(AddItem("apple", 0)))
        invalid.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        seen.single().type.name.assert().isEqualTo("shop.cart.AddItem")
        val doubled = object : CommandTransformer {
            override val input = model.commandType(AddItem::class.java)

            override suspend fun transform(input: CommandInput): CommandTarget =
                CommandTarget(input.type, input.aggregateId, (input.body as AddItem).copy(quantity = 4), input.headers)
        }
        gateway(transformers = listOf(doubled)).execute(request(AddItem("apple")))
            .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        (bodies.single() as AddItem).quantity.assert().isEqualTo(4)
    }

    @Test
    fun `a command object retried with its request id is answered from the recent requests, not decided again`(): Unit =
        runBlocking {
            // Decision D261: a command object takes the kernel's dedupe path as a JSON body does.
            val log = LookupCountingLog()
            val warm = WaitCoordinator(
                Executor(Kernel(log, states = LruStateCache(16))),
                registry,
                SubscriptionDirectory.NONE,
                LocalSignalSender(origin.node, registry),
                origin,
                WaitOptions(maxTimeout = 30.seconds),
            )
            val gateway = CommandGateway(ModelRegistry(listOf(model)), mapOf(CONTEXT to warm))
            fun retried(body: Any, requestId: String) =
                ServerCommandRequest.builder(body, cart.ref(TENANT, "cart-9")).caller(alice).requestId(requestId)
                    .wait(WaitPlan.COMMITTED).build()
            gateway.execute(retried(OpenCart("hi"), "open")).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val added = gateway.execute(retried(AddItem("apple"), "add"))
            added.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            bodies.size.assert().isEqualTo(2)
            val retry = gateway.execute(retried(AddItem("apple"), "add"))
            retry.duplicate.assert().isTrue()
            retry.version.assert().isEqualTo(added.version)
            // Retried on a warm aggregate, a creation is a duplicate too, not an "exists" rejection.
            gateway.execute(retried(OpenCart("hi"), "open")).duplicate.assert().isTrue()
            bodies.size.assert().isEqualTo(2)
            log.lookups.get().assert().isZero()
        }

    /** Counts the request-id lookups the kernel asks of the log. */
    private class LookupCountingLog(private val delegate: EventLog = InMemoryEventLog(partitions = 2)) :
        EventLog by delegate {
        val lookups = AtomicInteger()

        override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? {
            lookups.incrementAndGet()
            return delegate.findByRequestId(type, tenantId, requestId)
        }
    }

    @Test
    fun `a request of a command object is a value with its facts`() {
        val body = AddItem("apple")
        val request = ServerCommandRequest.of(body, cart.ref(TENANT, "cart-1"))
        request.assert().isEqualTo(ServerCommandRequest.of(body, cart.ref(TENANT, "cart-1")))
        request.body.assert().isEqualTo(CommandBody.Value(AddItem("apple")))
        request.toString().assert().contains("tenantId=$TENANT")
        request.copyWith(tenantId = null).tenantId.assert().isNull()
        request.context.assert().isEqualTo(CONTEXT)
        request.aggregate.assert().isEqualTo("cart")
    }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
    }
}
