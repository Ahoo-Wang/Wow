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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.wait.Origin
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One context's cart, decided on an executor of one lane, and the gateway, callers and requests over it: what the
 * admission tests share, each test with its own.
 */
abstract class CommandGatewayFixtures {
    data class OpenCart(val note: String = "")
    data class AddItem(val sku: String, val quantity: Int = 1)
    data class Checkout(val note: String = "")
    data class ItemAdded(val sku: String, val quantity: Int)
    data class CartOpened(val note: String)

    class Cart {
        var items = 0
    }

    /** A command as a 9.x domain wrote one: its SKUs in a getter-only list, filled as it is built. */
    class AddSkus {
        val skus = mutableListOf<String>()
    }

    /** The SKUs of the [AddSkus] commands decided, in order. */
    protected val skus = CopyOnWriteArrayList<String>()

    /** The commands the decide functions ran, by command id. */
    protected val decided = CopyOnWriteArrayList<String>()

    /** The operators of the [AddItem] commands decided, in order. */
    protected val operators = CopyOnWriteArrayList<Operator?>()

    /** The [AddItem] commands decided, in order. */
    protected val commands = CopyOnWriteArrayList<Command<*>>()

    /** While not null, deciding [AddItem] waits for it. */
    @Volatile
    protected var hold: CompletableDeferred<Unit>? = null

    protected val model = aggregate<Cart>(CONTEXT, "cart") {
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
        decide<AddSkus>(Creation.ALLOWED) { _, body ->
            skus += body.skus
            Decision.NoOp
        }
        evolve<CartOpened> { }
        evolve<ItemAdded> { items += it.quantity }
    }

    protected val registry = WaitRegistry()
    protected val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    protected val log = InMemoryEventLog(partitions = 2)
    protected val executor = Executor(Kernel(log), laneCapacity = 1)
    protected val coordinator = coordinator()

    protected fun coordinator(defaultTimeout: Duration = WaitPlan.DEFAULT_TIMEOUT) = WaitCoordinator(
        executor,
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, registry),
        origin,
        WaitOptions(maxTimeout = 30.seconds, defaultTimeout = defaultTimeout),
    )

    protected fun gateway(
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

    protected val alice = Caller(TENANT, Scope("alice", "home"), subject = "alice")
    protected val bob = Caller(TENANT, Scope("bob", "home"), subject = "bob")
    protected val admin = Caller("ops", Scope("admin"), subject = "admin", crossScope = true)

    protected fun request(
        command: String = "AddItem",
        body: String = """{"sku":"apple"}""",
        caller: Caller = alice,
        aggregateId: String? = "cart-1",
        target: TargetScope? = null,
        requestId: String? = null,
        headers: Map<String, String> = emptyMap(),
        wait: WaitPlan? = null,
    ) = ServerCommandRequest(
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

    protected fun CommandResult.assertRefused(code: String) {
        status.assert().isEqualTo(CommandResult.Status.REJECTED)
        error!!.code.assert().isEqualTo(code)
        hop.assert().isEqualTo(Hop.accepted(CONTEXT))
    }

    protected companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
    }
}
