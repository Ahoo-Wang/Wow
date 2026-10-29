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
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Admission of an aggregate whose id is its owner's: the id is the caller's owner, or named by a caller with none. */
class OwnerIdAdmissionTest {
    data class Open(val note: String = "")
    data class PutItem(val sku: String)
    data class Opened(val note: String)
    data class ItemPut(val sku: String)
    data class Open2(val note: String = "")
    data class Note(val sku: String)

    /** The commands the basket decided. */
    private val decided = CopyOnWriteArrayList<Command<*>>()

    private val basket: AggregateModel<Any> = aggregate(CONTEXT, "basket") {
        id(AggregateIdSource.OWNER)
        initial { Any() }
        decide<Open>(Creation.REQUIRED) { _, body ->
            decided += command
            Decision.events(Opened(body.note))
        }
        decide<PutItem> { _, body ->
            decided += command
            Decision.events(ItemPut(body.sku))
        }
        evolve<Opened> { }
        evolve<ItemPut> { }
    }

    /** An aggregate of assigned ids whose mounted command becomes a command of the caller's basket. */
    private val wishlist: AggregateModel<Any> = aggregate(CONTEXT, "wishlist") {
        initial { Any() }
        decide<Open2>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.note)) }
        mount<Note>()
        evolve<Opened> { }
    }

    private val putItem = basket.commandType(PutItem::class.java)
    private val note = wishlist.findMountedCommandType("$CONTEXT.wishlist.Note")!!
    private val toBasket = object : CommandTransformer {
        override val input = note

        override suspend fun transform(input: CommandInput): CommandTarget =
            CommandTarget(putItem, null, PutItem((input.body as Note).sku))
    }

    private val waits = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog(partitions = 2))),
        waits,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, waits),
        origin,
        WaitOptions(maxTimeout = 30.seconds),
    )
    private val gateway = CommandGateway(
        ModelRegistry(listOf(basket, wishlist)),
        mapOf(CONTEXT to coordinator),
        transformers = listOf(toBasket),
    )

    private fun request(
        command: String = "PutItem",
        body: String = """{"sku":"apple"}""",
        caller: Caller = alice,
        aggregateId: String? = null,
        target: TargetScope? = null,
        aggregate: String = "basket",
    ) = ServerCommandRequest(
        context = CONTEXT,
        aggregate = aggregate,
        command = command,
        body = CommandBody.of(body),
        caller = caller,
        aggregateId = aggregateId,
        target = target,
        wait = WaitPlan.COMMITTED,
    )

    private suspend fun opened(caller: Caller = alice, aggregateId: String? = null): CommandResult =
        gateway.execute(request("Open", "{}", caller, aggregateId))
            .also { it.status.assert().withFailMessage { "$it" }.isEqualTo(CommandResult.Status.SUCCEEDED) }

    private fun CommandResult.assertRefused(code: String, id: String) {
        status.assert().isEqualTo(CommandResult.Status.REJECTED)
        error!!.code.assert().isEqualTo(code)
        ref.id.assert().isEqualTo(id)
    }

    @Test
    fun `an owner-level caller's request without an id is to its own, and naming its own id passes`(): Unit =
        runBlocking {
            opened().ref.assert().isEqualTo(basket.type.ref(TENANT, "alice"))
            decided.single().scope.ownerId.assert().isEqualTo("alice")
            val put = gateway.execute(request())
            put.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            put.ref.id.assert().isEqualTo("alice")
            gateway.execute(request(aggregateId = "alice")).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            decided.map { it.ref.id }.assert().containsExactly("alice", "alice", "alice")
        }

    @Test
    fun `an owner-level caller naming another's id is forbidden, its creation too`(): Unit = runBlocking {
        opened(caller = bob)
        decided.clear()
        gateway.execute(request(aggregateId = "bob")).assertRefused(FrameworkCodes.AGGREGATE_FORBIDDEN, "bob")
        gateway.execute(
            request("Open", "{}", aggregateId = "bob")
        ).assertRefused(FrameworkCodes.AGGREGATE_FORBIDDEN, "bob")
        decided.assert().isEmpty()
    }

    @Test
    fun `a cross-scope caller acting in an owner's scope addresses that owner's`(): Unit = runBlocking {
        opened()
        val result = gateway.execute(request(caller = admin, target = TargetScope(TENANT, Scope("alice"))))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.ref.assert().isEqualTo(basket.type.ref(TENANT, "alice"))
        gateway.execute(request(caller = admin, target = TargetScope(TENANT, Scope("alice")), aggregateId = "bob"))
            .assertRefused(FrameworkCodes.AGGREGATE_FORBIDDEN, "bob")
    }

    @Test
    fun `an authenticated caller with no owner must name the id, and then passes`(): Unit = runBlocking {
        val refused = gateway.execute(request("Open", "{}", caller = clerk))
        refused.assertRefused(FrameworkCodes.COMMAND_MALFORMED, CommandGateway.UNADDRESSED)
        refused.error!!.message.assert().isEqualTo("This caller has no owner; address the aggregate by its id.")
        gateway.execute(
            request(caller = service)
        ).assertRefused(FrameworkCodes.COMMAND_MALFORMED, CommandGateway.UNADDRESSED)
        // Naming the id, it creates the owner's aggregate, recorded as the owner's.
        opened(caller = clerk, aggregateId = "carol").ref.id.assert().isEqualTo("carol")
        decided.single().scope.ownerId.assert().isEqualTo("carol")
        gateway.execute(request(caller = clerk, aggregateId = "carol")).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        gateway.execute(request(caller = service, aggregateId = "carol")).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        // The owner then sends its own.
        gateway.execute(request(caller = Caller(TENANT, Scope("carol"), subject = "carol"))).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `an unauthenticated caller without an id is unauthenticated`(): Unit = runBlocking {
        gateway.execute(request("Open", "{}", caller = Caller(TENANT)))
            .assertRefused(FrameworkCodes.AUTH_UNAUTHENTICATED, CommandGateway.UNADDRESSED)
        decided.assert().isEmpty()
    }

    @Test
    fun `a transformer's command to a basket takes the caller's owner, and finds none without one`(): Unit =
        runBlocking {
            opened()
            val put = gateway.execute(
                request("Note", """{"sku":"pear"}""", aggregate = "wishlist", aggregateId = "w-1")
            )
            put.status.assert().withFailMessage { "$put" }.isEqualTo(CommandResult.Status.SUCCEEDED)
            put.ref.assert().isEqualTo(basket.type.ref(TENANT, "alice"))
            decided.last().body.assert().isEqualTo(PutItem("pear"))
            gateway.execute(
                request("Note", """{"sku":"pear"}""", caller = clerk, aggregate = "wishlist", aggregateId = "w-1"),
            ).assertRefused(FrameworkCodes.AGGREGATE_NOT_FOUND, CommandGateway.UNADDRESSED)
        }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
        val alice = Caller(TENANT, Scope("alice", "home"), subject = "alice")
        val bob = Caller(TENANT, Scope("bob", "home"), subject = "bob")
        val admin = Caller("ops", Scope("admin"), subject = "admin", crossScope = true)

        /** A tenant-level caller: authenticated, with no owner. */
        val clerk = Caller(TENANT, subject = "clerk")
        val service = Caller(TENANT, subject = "inventory", service = true)
    }
}
