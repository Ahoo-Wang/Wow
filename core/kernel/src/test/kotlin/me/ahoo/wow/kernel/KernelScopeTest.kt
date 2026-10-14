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
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The kernel's scope guard (decision D94): an existing aggregate takes commands from its own scope only. */
class KernelScopeTest {
    private val codec = EventCodec()
    private val log = InMemoryEventLog()
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private val owned = Scope("alice", "space-a")
    private var sequence = 0

    /** No state cache, so every command loads the aggregate's scope from the log (or the snapshot). */
    private val kernel = Kernel(log, codec)

    private suspend fun send(
        body: Any,
        scope: Scope,
        requestId: String? = null,
        expectedVersion: Int? = null,
        kernel: Kernel = this.kernel,
    ): CommandOutcome {
        sequence++
        val command = Command("c-$sequence", requestId ?: "r-$sequence", ref, body, expectedVersion, scope = scope)
        return kernel.execute(cartModel, command)
    }

    private suspend fun created(scope: Scope = owned): CommandOutcome.Committed =
        send(CreateCart("ahoo"), scope, requestId = "create").committed()

    private fun CommandOutcome.committed(): CommandOutcome.Committed = this as CommandOutcome.Committed

    private fun CommandOutcome.rejection(): String = (this as CommandOutcome.Rejected).rejection.code

    private suspend fun versions(): List<Int> = log.read(ref).toList().map { it.version }

    @Test
    fun `a command of another owner is forbidden`(): Unit = runTest {
        created()
        val refused = send(AddItem("apple", 1), Scope("bob", "space-a")) as CommandOutcome.Rejected
        refused.rejection.code.assert().isEqualTo(KernelRejections.FORBIDDEN)
        refused.rejection.message.assert().contains(ref.toString())
        send(AddItem("apple", 1), Scope("bob", null)).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        versions().assert().containsExactly(1)
    }

    @Test
    fun `a command of another space is forbidden`(): Unit = runTest {
        created()
        send(AddItem("apple", 1), Scope("alice", "space-b")).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(AddItem("apple", 1), Scope(null, "space-b")).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        versions().assert().containsExactly(1)
    }

    @Test
    fun `a part the aggregate lacks forbids a command that names it`(): Unit = runTest {
        created(Scope(ownerId = "alice"))
        send(AddItem("apple", 1), Scope("alice", "space-a")).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(AddItem("apple", 1), Scope(ownerId = "alice")).committed()
    }

    @Test
    fun `the parts absent from the command's scope are not checked`(): Unit = runTest {
        created()
        send(AddItem("apple", 1), Scope.NONE).committed().stream.scope.assert().isEqualTo(owned)
        send(AddItem("pear", 1), Scope(ownerId = "alice")).committed()
        send(AddItem("plum", 1), Scope(spaceId = "space-a")).rejection().assert().isEqualTo("cart.full")
        send(AddItem("apple", 1), owned).committed().stream.version.assert().isEqualTo(4)
    }

    @Test
    fun `creating is not guarded and gives the aggregate the creating command's scope`(): Unit = runTest {
        created().stream.scope.assert().isEqualTo(owned)
        // On an aggregate that exists, the guard comes first: another scope does not learn it is a duplicate creation.
        send(CreateCart("bob"), Scope("bob", "space-b")).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(CreateCart("alice"), owned).rejection().assert().isEqualTo(KernelRejections.EXISTS)
    }

    @Test
    fun `the current owner transfers the owner, and the new one then holds the aggregate`(): Unit = runTest {
        created()
        send(TransferOwner("carol"), Scope("bob", "space-a")).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(TransferOwner("bob"), owned).committed().stream.scope.assert().isEqualTo(Scope("bob", "space-a"))

        send(AddItem("apple", 1), owned).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(TransferOwner("alice"), owned).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(AddItem("apple", 1), Scope("bob", "space-a")).committed().stream.scope
            .assert().isEqualTo(Scope("bob", "space-a"))
    }

    @Test
    fun `a member of the current space transfers the space, and the new space then holds the aggregate`(): Unit =
        runTest {
            created()
            send(TransferSpace("space-c"), Scope("alice", "space-b"))
                .rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
            send(TransferSpace("space-b"), owned).committed().stream.scope
                .assert().isEqualTo(Scope("alice", "space-b"))
            send(AddItem("apple", 1), owned).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
            send(AddItem("apple", 1), Scope("alice", "space-b")).committed()
        }

    @Test
    fun `a transfer by a command without a scope is not guarded`(): Unit = runTest {
        created()
        send(TransferOwner("bob"), Scope.NONE).committed().stream.scope.assert().isEqualTo(Scope("bob", "space-a"))
    }

    @Test
    fun `the guard comes after existence and before the deleted mark and the version`(): Unit = runTest {
        val stranger = Scope("bob", "space-b")
        send(AddItem("apple", 1), stranger).rejection().assert().isEqualTo(KernelRejections.NOT_FOUND)
        created()
        send(AddItem("apple", 1), stranger, expectedVersion = 7)
            .rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(AddItem("apple", 1), owned, expectedVersion = 7)
            .rejection().assert().isEqualTo(KernelRejections.VERSION_MISMATCH)
        send(DeleteAggregate, owned).committed()
        send(AddItem("apple", 1), stranger).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(RestoreAggregate, stranger).rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(AddItem("apple", 1), owned).rejection().assert().isEqualTo(KernelRejections.DELETED)
        send(RestoreAggregate, owned).committed()
        versions().assert().containsExactly(1, 2, 3)
    }

    @Test
    fun `the guard reads the scope a snapshot restores`(): Unit = runTest {
        created()
        send(TransferOwner("bob"), owned)
        val snapshots = InMemorySnapshotStore()
        val head = kernel.stateAt(cartModel, ref, 2)!!
        snapshots.save(codec.snapshotOf(cartModel, ref, head.state as CartState, 2, head.scope, 0, 0))
        val fromSnapshot = Kernel(log, codec, snapshots = snapshots)
        val refused = send(AddItem("apple", 1), owned, kernel = fromSnapshot)
        refused.rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(AddItem("apple", 1), Scope("bob", "space-a"), kernel = fromSnapshot).committed()
    }

    @Test
    fun `a duplicate is answered within the scope that sent it, and forbidden outside it`(): Unit = runTest {
        val creation = created()
        send(CreateCart("ahoo"), owned, requestId = "create").assert()
            .isEqualTo(CommandOutcome.AlreadyCommitted(creation.stream))
        send(CreateCart("ahoo"), Scope.NONE, requestId = "create").assert()
            .isEqualTo(CommandOutcome.AlreadyCommitted(creation.stream))
        send(CreateCart("ahoo"), Scope("bob", "space-a"), requestId = "create")
            .rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        // Outside the scope, the reuse by another command type is not told apart either.
        send(AddItem("apple", 1), Scope("bob", "space-a"), requestId = "create")
            .rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(AddItem("apple", 1), owned, requestId = "create")
            .rejection().assert().isEqualTo(KernelRejections.REQUEST_ID_REUSED)
        versions().assert().containsExactly(1)
    }

    @Test
    fun `a retried transfer is answered though its caller no longer holds the aggregate`(): Unit = runTest {
        created()
        val owner = send(TransferOwner("bob"), owned, requestId = "to-bob").committed()
        send(TransferOwner("bob"), owned, requestId = "to-bob").assert()
            .isEqualTo(CommandOutcome.AlreadyCommitted(owner.stream))
        val space = send(TransferSpace("space-b"), Scope("bob", "space-a"), requestId = "to-b").committed()
        send(TransferSpace("space-b"), Scope("bob", "space-a"), requestId = "to-b").assert()
            .isEqualTo(CommandOutcome.AlreadyCommitted(space.stream))
        // The part the stream did not transfer is still checked.
        send(TransferOwner("bob"), Scope("alice", "space-x"), requestId = "to-bob")
            .rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        send(TransferSpace("space-b"), Scope("carol", "space-a"), requestId = "to-b")
            .rejection().assert().isEqualTo(KernelRejections.FORBIDDEN)
        versions().assert().containsExactly(1, 2, 3)
    }

    @Test
    fun `an aggregate of its tenant records no owner and checks none, but still its space`(): Unit = runTest {
        val model = aggregate<CartState>("example", "shelf") {
            ownership(Ownership.TENANT)
            initial { CartState(it.id) }
            decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
            decide<AddItem> { _, command -> Decision.events(ItemAdded(command.productId, command.quantity)) }
            evolve<CartCreated> { owner = it.owner }
            evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
        }
        model.ownership.assert().isEqualTo(Ownership.TENANT)
        model.lifecycleCommands.assert().doesNotContain(TransferOwner::class.java)
        val shelf = model.type.ref("tenant", "shelf-1")
        suspend fun send(body: Any, scope: Scope, requestId: String): CommandOutcome =
            kernel.execute(model, Command("c-$requestId", requestId, shelf, body, scope = scope))

        val created = send(CreateCart("ahoo"), owned, "create") as CommandOutcome.Committed
        created.stream.scope.assert().isEqualTo(Scope(spaceId = "space-a"))
        (send(AddItem("apple", 1), Scope("bob", "space-a"), "bob") as CommandOutcome.Committed)
            .stream.scope.assert().isEqualTo(Scope(spaceId = "space-a"))
        (send(AddItem("apple", 1), Scope("bob", "space-b"), "elsewhere") as CommandOutcome.Rejected)
            .rejection.code.assert().isEqualTo(KernelRejections.FORBIDDEN)
        // A retry of the creation from another owner is answered: the owner is no part of the aggregate's scope.
        send(CreateCart("ahoo"), Scope("carol", "space-a"), "create")
            .assert().isInstanceOf(CommandOutcome.AlreadyCommitted::class.java)
        (send(TransferOwner("carol"), Scope.NONE, "transfer") as CommandOutcome.Rejected)
            .rejection.code.assert().isEqualTo(KernelRejections.UNHANDLED)
    }

    @Test
    fun `an aggregate of its tenant decides no transfer of its owner`() {
        assertThrows<IllegalArgumentException> {
            aggregate<CartState>("example", "shelf") {
                ownership(Ownership.TENANT)
                initial { CartState(it.id) }
                decide<TransferOwner> { _, _ -> Decision.NoOp }
            }
        }.message.assert().contains("Ownership.TENANT")
    }
}
