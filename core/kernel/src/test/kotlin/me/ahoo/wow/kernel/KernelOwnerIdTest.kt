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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** An aggregate whose id is its owner's: the kernel records its id as its owner and refuses another owner's command. */
class KernelOwnerIdTest {
    private val log = InMemoryEventLog()
    private val kernel = Kernel(log, EventCodec())
    private val model = aggregate<CartState>("example", "basket") {
        id(AggregateIdSource.OWNER)
        initial { CartState(it.id) }
        decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
        decide<AddItem> { _, command -> Decision.events(ItemAdded(command.productId, command.quantity)) }
        evolve<CartCreated> { owner = it.owner }
        evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
    }
    private val alice = model.type.ref("tenant", "alice")
    private val derivation = Causation("bob-order", "example.order/tenant/o-1@1")
    private var sequence = 0

    private suspend fun send(body: Any, scope: Scope, causation: Causation? = null): CommandOutcome {
        sequence++
        return kernel.execute(
            model,
            Command("c-$sequence", "r-$sequence", alice, body, scope = scope, causation = causation)
        )
    }

    private fun CommandOutcome.committed(): CommandOutcome.Committed = this as CommandOutcome.Committed

    private fun CommandOutcome.rejection(): String = (this as CommandOutcome.Rejected).rejection.code

    @Test
    fun `an aggregate written with no owner or another one than its id is out of its owner's reach (#6560)`(): Unit =
        runTest {
            // As migrate-data would write a 9.x stream without the check: the scope it records is the 9.x one.
            val created = send(CreateCart("alice"), Scope(ownerId = "alice")).committed().stream
            listOf("carol" to Scope.NONE, "dave" to Scope(ownerId = "erin")).forEach { (id, scope) ->
                val imported = created.copyWith(
                    ref = model.type.ref("tenant", id),
                    commandId = "import-$id",
                    requestId = "import-$id",
                    scope = scope,
                )
                log.append(imported)
                val own = kernel.execute(
                    model,
                    Command("own-$id", "own-$id", imported.ref, AddItem("apple", 1), scope = Scope(ownerId = id)),
                )
                own.rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
            }
            // The rule the kernel records such an aggregate's scope by, which migrate-data checks a stream against.
            AggregateIdSource.OWNER.scopeOf("carol", Scope.NONE).assert().isEqualTo(Scope(ownerId = "carol"))
            AggregateIdSource.OWNER.scopeOf("alice", created.scope).assert().isSameAs(created.scope)
            AggregateIdSource.ASSIGNED.scopeOf("carol", Scope.NONE).assert().isEqualTo(Scope.NONE)
        }

    @Test
    fun `the model should say its id source and leave TransferOwner out`() {
        model.idSource.assert().isEqualTo(AggregateIdSource.OWNER)
        model.lifecycleCommands.assert().doesNotContain(TransferOwner::class.java)
        cartModel.idSource.assert().isEqualTo(AggregateIdSource.ASSIGNED)
        cartModel.lifecycleCommands.assert().contains(TransferOwner::class.java)
    }

    @Test
    fun `a caller with no owner creates the aggregate, which records its id as its owner`(): Unit = runTest {
        send(CreateCart("alice"), Scope.NONE).committed().stream.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        // The owner then sends its own commands.
        send(AddItem("apple", 1), Scope(ownerId = "alice")).committed().stream.version.assert().isEqualTo(2)
        // A caller with no owner still addresses it by its id.
        send(AddItem("pear", 1), Scope.NONE).committed().stream.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        log.read(alice).toList().map { it.version }.assert().containsExactly(1, 2, 3)
    }

    @Test
    fun `a derived command records the aggregate's id as its owner, whoever's saga sent it`(): Unit = runTest {
        send(CreateCart("alice"), Scope("bob", "space-b"), derivation).committed().stream.scope.assert()
            .isEqualTo(Scope("alice", "space-b"))
        send(AddItem("apple", 1), Scope("bob", "space-b"), derivation).committed().stream.scope.assert()
            .isEqualTo(Scope("alice", "space-b"))
    }

    @Test
    fun `the owner's own command creates the aggregate and changes it`(): Unit = runTest {
        send(CreateCart("alice"), Scope(ownerId = "alice")).committed().stream.scope.assert()
            .isEqualTo(Scope(ownerId = "alice"))
        send(AddItem("apple", 1), Scope(ownerId = "alice")).committed().stream.version.assert().isEqualTo(2)
    }

    @Test
    fun `another owner neither creates the aggregate nor learns that it exists`(): Unit = runTest {
        val refused = send(CreateCart("alice"), Scope(ownerId = "bob")) as CommandOutcome.Rejected
        refused.rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        refused.rejection.message.assert().contains(alice.toString())
        log.read(alice).toList().assert().isEmpty()
        // Another owner's other commands are answered alike whether the aggregate exists or not.
        send(AddItem("apple", 1), Scope(ownerId = "bob")).rejection().assert()
            .isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        send(CreateCart("alice"), Scope(ownerId = "alice")).committed()
        send(AddItem("apple", 1), Scope("bob", null)).rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        // A creation tells whether it exists anyway: it is forbidden.
        send(CreateCart("alice"), Scope(ownerId = "bob")).rejection().assert()
            .isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        log.read(alice).toList().map { it.version }.assert().containsExactly(1)
    }

    @Test
    fun `a refused creation leaves the initial state it was decided on as it was`(): Unit = runTest {
        val cache = object : StateCache {
            val states = HashMap<AggregateRef, CachedState>()

            override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

            override fun put(ref: AggregateRef, state: CachedState) {
                states[ref] = state
            }
        }
        val cached = Kernel(log, EventCodec(), states = cache)
        val refused = cached.execute(model, Command("c-x", "r-x", alice, CreateCart("mallory"), scope = Scope("bob")))
        refused.rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        // Refused before its state evolved: what the cache keeps is the initial state, as no event changed it.
        cache.states[alice]?.let { (it.state as CartState).owner.assert().isNull() }
        cached.execute(model, Command("c-y", "r-y", alice, CreateCart("alice"), scope = Scope("alice")))
            .committed().state.let { (it as CartState).owner.assert().isEqualTo("alice") }
    }

    @Test
    fun `TransferOwner is not handled`(): Unit = runTest {
        send(CreateCart("alice"), Scope(ownerId = "alice")).committed()
        send(TransferOwner("bob"), Scope(ownerId = "alice")).rejection().assert()
            .isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
        send(TransferOwner("bob"), Scope.NONE).rejection().assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
    }

    @Test
    fun `the model refuses a TransferOwner decider and an aggregate of its tenant`() {
        assertThrows<IllegalArgumentException> {
            aggregate<CartState>("example", "basket") {
                id(AggregateIdSource.OWNER)
                initial { CartState(it.id) }
                decide<TransferOwner> { _, _ -> Decision.NoOp }
            }
        }.message.assert().contains("AggregateIdSource.OWNER").contains("TransferOwner")
        assertThrows<IllegalArgumentException> {
            aggregate<CartState>("example", "basket") {
                id(AggregateIdSource.OWNER)
                ownership(Ownership.TENANT)
                initial { CartState(it.id) }
            }
        }.message.assert().contains("AggregateIdSource.OWNER").contains("not its tenant")
    }
}
