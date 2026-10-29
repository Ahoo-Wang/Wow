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
    fun `another owner's command is forbidden, creating the aggregate or not`(): Unit = runTest {
        val refused = send(CreateCart("alice"), Scope(ownerId = "bob")) as CommandOutcome.Rejected
        refused.rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        refused.rejection.message.assert().contains(alice.toString())
        log.read(alice).toList().assert().isEmpty()
        // Before existence: another owner does not learn the aggregate does not exist.
        send(AddItem("apple", 1), Scope(ownerId = "bob")).rejection().assert()
            .isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        send(CreateCart("alice"), Scope(ownerId = "alice")).committed()
        send(AddItem("apple", 1), Scope("bob", null)).rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        send(CreateCart("alice"), Scope(ownerId = "bob")).rejection().assert()
            .isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
        log.read(alice).toList().map { it.version }.assert().containsExactly(1)
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
