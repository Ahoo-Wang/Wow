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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.lifecycle.AggregateRestored
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.SpaceTransferred
import me.ahoo.wow.api.lifecycle.TagsApplied
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** The lifecycle commands every model handles without user code (design 01 §6, roadmap 2.4). */
class KernelLifecycleTest {
    private val codec = EventCodec()
    private val log = InMemoryEventLog()
    private val snapshots = InMemorySnapshotStore()
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private val owned = Scope("alice", "space-a")
    private var sequence = 0

    /** No state cache, so every command loads from the log (or the snapshot). */
    private val kernel = Kernel(log, codec)

    private suspend fun send(
        body: Any,
        scope: Scope = Scope.NONE,
        requestId: String? = null,
        model: AggregateModel<CartState> = cartModel,
        kernel: Kernel = this.kernel,
    ): CommandOutcome {
        sequence++
        return kernel.execute(model, Command("c-$sequence", requestId ?: "r-$sequence", ref, body, scope = scope))
    }

    private suspend fun created(): CommandOutcome = send(CreateCart("ahoo"), owned)

    private fun CommandOutcome.committed(): CommandOutcome.Committed = this as CommandOutcome.Committed

    private fun CommandOutcome.rejection(): String = (this as CommandOutcome.Rejected).rejection.code

    private suspend fun head(): CachedState = kernel.stateAt(cartModel, ref, log.read(ref).toList().last().version)!!

    @Test
    fun `every model handles the lifecycle commands under their system names`() {
        cartModel.lifecycleCommands.assert().isEqualTo(Lifecycle.COMMANDS.values.toSet())
        Lifecycle.COMMANDS.forEach { (name, type) ->
            cartModel.findCommandType(name)?.type.assert().isEqualTo(type)
        }
        Lifecycle.EVENTS.forEach { (name, type) -> cartModel.eventType(type).name.assert().isEqualTo(name) }
        Lifecycle.nameOf(AddItem::class.java).assert().isNull()
    }

    @Test
    fun `deleting appends AggregateDeleted, and the aggregate then refuses every command but restore`(): Unit =
        runTest {
            created()
            val deleted = send(DeleteAggregate).committed()
            deleted.stream.events.map { it.name }.assert().containsExactly(Lifecycle.AGGREGATE_DELETED)
            deleted.stream.scope.assert().isEqualTo(owned)
            head().deleted.assert().isTrue()

            send(AddItem("apple", 1)).rejection().assert().isEqualTo(KernelRejections.DELETED)
            send(DeleteAggregate).rejection().assert().isEqualTo(KernelRejections.DELETED)
            send(TransferOwner("bob")).rejection().assert().isEqualTo(KernelRejections.DELETED)
            send(ApplyTags(mapOf("k" to listOf("v")))).rejection().assert().isEqualTo(KernelRejections.DELETED)
            send(CreateCart("again")).rejection().assert().isEqualTo(KernelRejections.DELETED)
            log.read(ref).toList().size.assert().isEqualTo(2)
        }

    @Test
    fun `restoring takes the deletion back, and restoring an aggregate not deleted changes nothing`(): Unit = runTest {
        created()
        send(RestoreAggregate).assert().isEqualTo(CommandOutcome.Unchanged(1))
        send(DeleteAggregate)
        val restored = send(RestoreAggregate).committed()
        restored.stream.events.map { it.name }.assert().containsExactly(Lifecycle.AGGREGATE_RESTORED)
        head().deleted.assert().isFalse()
        send(AddItem("apple", 1)).committed().stream.version.assert().isEqualTo(4)
    }

    @Test
    fun `a lifecycle command needs the aggregate to exist`(): Unit = runTest {
        send(DeleteAggregate).rejection().assert().isEqualTo(KernelRejections.NOT_FOUND)
        send(RestoreAggregate).rejection().assert().isEqualTo(KernelRejections.NOT_FOUND)
        send(TransferOwner("bob")).rejection().assert().isEqualTo(KernelRejections.NOT_FOUND)
    }

    @Test
    fun `transferring the owner changes the scope of this stream and of every later one`(): Unit = runTest {
        created()
        val transferred = send(TransferOwner("bob")).committed()
        transferred.stream.events.map { it.name }.assert().containsExactly(Lifecycle.OWNER_TRANSFERRED)
        transferred.stream.scope.assert().isEqualTo(Scope("bob", "space-a"))
        // A later command's caller scope does not move it.
        send(AddItem("apple", 1), Scope("bob", null)).committed().stream.scope
            .assert().isEqualTo(Scope("bob", "space-a"))
        send(TransferOwner("bob")).assert().isEqualTo(CommandOutcome.Unchanged(3))
        head().scope.assert().isEqualTo(Scope("bob", "space-a"))
    }

    @Test
    fun `transferring the space changes the space part of the scope only`(): Unit = runTest {
        created()
        val transferred = send(TransferSpace("space-b")).committed()
        transferred.stream.events.map { it.name }.assert().containsExactly(Lifecycle.SPACE_TRANSFERRED)
        transferred.stream.scope.assert().isEqualTo(Scope("alice", "space-b"))
        send(TransferSpace("space-b")).assert().isEqualTo(CommandOutcome.Unchanged(2))
        send(AddItem("apple", 1)).committed().stream.scope.assert().isEqualTo(Scope("alice", "space-b"))
    }

    @Test
    fun `applying tags replaces them, and applying the same ones changes nothing`(): Unit = runTest {
        created()
        val tags = mapOf("region" to listOf("eu", "us"))
        send(ApplyTags(tags)).committed().stream.events.map { it.name }
            .assert().containsExactly(Lifecycle.TAGS_APPLIED)
        head().tags.assert().isEqualTo(tags)
        send(ApplyTags(tags)).assert().isEqualTo(CommandOutcome.Unchanged(2))
        send(ApplyTags(mapOf("tier" to listOf("gold")))).committed()
        head().tags.assert().isEqualTo(mapOf("tier" to listOf("gold")))
        send(ApplyTags(emptyMap())).committed()
        head().tags.assert().isEmpty()
    }

    @Test
    fun `a committed outcome carries the deleted mark and the tags after its stream`(): Unit = runTest {
        val created = created().committed()
        created.deleted.assert().isFalse()
        created.tags.assert().isEmpty()
        val tags = mapOf("region" to listOf("eu"))
        send(ApplyTags(tags)).committed().tags.assert().isEqualTo(tags)
        val deleted = send(DeleteAggregate).committed()
        deleted.deleted.assert().isTrue()
        deleted.tags.assert().isEqualTo(tags)
        val restored = send(RestoreAggregate).committed()
        restored.deleted.assert().isFalse()
        restored.tags.assert().isEqualTo(tags)
    }

    @Test
    fun `the lifecycle events leave the user's state as it is`(): Unit = runTest {
        created()
        send(AddItem("apple", 1))
        val state = send(TransferOwner("bob")).committed().state as CartState
        state.owner.assert().isEqualTo("ahoo")
        state.items.assert().isEqualTo(mapOf("apple" to 1))
    }

    @Test
    fun `a snapshot and its tail restore the deleted mark, the scope and the tags`(): Unit = runTest {
        created()
        val tags = mapOf("region" to listOf("eu"))
        send(ApplyTags(tags))
        send(TransferOwner("bob"))
        val atSnapshot = head()
        atSnapshot.version.assert().isEqualTo(3)
        atSnapshot.tags.assert().isEqualTo(tags)
        // A snapshot whose tags differ from the log's shows that the kernel took them from it, then the tail.
        val marked = mapOf("from" to listOf("snapshot"))
        snapshots.save(
            codec.snapshotOf(
                cartModel,
                ref,
                atSnapshot.state as CartState,
                atSnapshot.version,
                atSnapshot.scope,
                firstEventTime = 0,
                lastEventTime = 0,
                deleted = atSnapshot.deleted,
                tags = marked,
            ),
        )
        send(DeleteAggregate)

        val fromSnapshot = Kernel(log, codec, snapshots = snapshots)
        send(AddItem("apple", 1), kernel = fromSnapshot).rejection().assert().isEqualTo(KernelRejections.DELETED)
        val restored = send(RestoreAggregate, kernel = fromSnapshot).committed()
        restored.stream.scope.assert().isEqualTo(Scope("bob", "space-a"))
        val cache = RecordingCache()
        Kernel(log, codec, states = cache, snapshots = snapshots).execute(
            cartModel,
            Command("c-last", "r-last", ref, AddItem("apple", 1)),
        ).committed().stream.version.assert().isEqualTo(6)
        cache.last!!.tags.assert().isEqualTo(marked)
        cache.last!!.deleted.assert().isFalse()
        cache.last!!.scope.assert().isEqualTo(Scope("bob", "space-a"))
    }

    @Test
    fun `a deleted snapshot at the log's head refuses commands`(): Unit = runTest {
        created()
        send(DeleteAggregate)
        val deleted = head()
        snapshots.save(
            codec.snapshotOf(
                cartModel,
                ref,
                deleted.state as CartState,
                deleted.version,
                deleted.scope,
                0,
                0,
                deleted = true,
            ),
        )
        snapshots.load(ref)!!.deleted.assert().isTrue()
        send(AddItem("apple", 1), kernel = Kernel(log, codec, snapshots = snapshots))
            .rejection().assert().isEqualTo(KernelRejections.DELETED)
    }

    @Test
    fun `a duplicate lifecycle command is answered with its committed stream, not decided again`(): Unit = runTest {
        created()
        val first = send(DeleteAggregate, requestId = "delete-1").committed()
        send(DeleteAggregate, requestId = "delete-1").assert().isEqualTo(CommandOutcome.AlreadyCommitted(first.stream))
        val reused = send(RestoreAggregate, requestId = "delete-1")
        reused.rejection().assert().isEqualTo(KernelRejections.REQUEST_ID_REUSED)
        // Deduplication comes before the deleted mark: a retry of the creation is still answered.
        val retried = send(CreateCart("ahoo"), owned, requestId = "r-1")
        retried.assert().isInstanceOf(CommandOutcome.AlreadyCommitted::class.java)
    }

    @Test
    fun `a model can enable only some lifecycle commands, and still reads the events of the others`(): Unit = runTest {
        created()
        send(TransferOwner("bob"))
        send(DeleteAggregate)
        val restricted = cartModel(lifecycle = arrayOf(TransferOwner::class.java))
        restricted.lifecycleCommands.assert().containsExactly(TransferOwner::class.java)
        restricted.findCommandType(Lifecycle.DELETE_AGGREGATE).assert().isNull()
        send(RestoreAggregate, model = restricted).rejection().assert().isEqualTo(KernelRejections.UNHANDLED)
        kernel.stateAt(restricted, ref, 3)!!.deleted.assert().isTrue()
        cartModel(lifecycle = emptyArray()).lifecycleCommands.assert().isEmpty()
    }

    @Test
    fun `a model's own decide and evolve functions override the kernel's`(): Unit = runTest {
        val overriding = aggregate<CartState>("example", "cart") {
            initial { CartState(it.id) }
            decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
            decide<DeleteAggregate> { state, _ ->
                if (state.items.isEmpty()) {
                    Decision.events(AggregateDeleted)
                } else {
                    Decision.reject("cart.not-empty", "A cart with items is not deleted.")
                }
            }
            decide<AddItem> { _, command -> Decision.events(ItemAdded(command.productId, command.quantity)) }
            evolve<CartCreated> { owner = it.owner }
            evolve<ItemAdded> { items[it.productId] = it.quantity }
            evolve<AggregateDeleted> { items.clear() }
        }
        overriding.commandType(DeleteAggregate::class.java).name.assert().isEqualTo(Lifecycle.DELETE_AGGREGATE)
        overriding.eventType(AggregateDeleted::class.java).name.assert().isEqualTo(Lifecycle.AGGREGATE_DELETED)
        send(CreateCart("ahoo"), owned, model = overriding)
        send(AddItem("apple", 1), model = overriding)
        send(DeleteAggregate, model = overriding).rejection().assert().isEqualTo("cart.not-empty")
        // The kernel still keeps the deleted mark when the model's own decide function deletes.
        val other = cartModel.type.ref("tenant", "cart-2")
        kernel.execute(overriding, Command("c-o1", "r-o1", other, CreateCart("x")))
        kernel.execute(overriding, Command("c-o2", "r-o2", other, DeleteAggregate)).committed()
        kernel.execute(overriding, Command("c-o3", "r-o3", other, AddItem("apple", 1)))
            .rejection().assert().isEqualTo(KernelRejections.DELETED)
    }

    @Test
    fun `only the lifecycle types take names in the system namespace`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<CartState>("example", "cart") {
                initial { CartState(it.id) }
                decide<AddItem>(name = "${Lifecycle.NAMESPACE}.AddItem") { _, _ -> Decision.NoOp }
            }
        }
        assertThrownBy<IllegalArgumentException> {
            aggregate<CartState>("example", "cart") {
                initial { CartState(it.id) }
                evolve<ItemAdded>(name = Lifecycle.AGGREGATE_DELETED) { }
            }
        }
        assertThrownBy<IllegalArgumentException> {
            aggregate<CartState>("example", "cart") {
                initial { CartState(it.id) }
                decide<DeleteAggregate>(name = "example.cart.Delete") { _, _ -> Decision.NoOp }
            }
        }
        assertThrownBy<IllegalArgumentException> {
            aggregate<CartState>("example", "cart") {
                initial { CartState(it.id) }
                lifecycle(AddItem::class.java)
            }
        }
    }

    @Test
    fun `the lifecycle types round-trip through the codec`() {
        listOf(
            AggregateDeleted,
            AggregateRestored,
            OwnerTransferred("bob"),
            SpaceTransferred("space-b"),
            TagsApplied(mapOf("k" to listOf("v"))),
        ).forEach { event ->
            codec.decode(cartModel, codec.encode(cartModel, event)).assert().isEqualTo(event)
        }
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()
        listOf(DeleteAggregate, RestoreAggregate, TransferOwner("bob"), TransferSpace("s"), ApplyTags(emptyMap()))
            .forEach { command ->
                mapper.readValue(mapper.writeValueAsString(command), command.javaClass).assert().isEqualTo(command)
            }
    }

    /** Remembers the last state the kernel cached. */
    private class RecordingCache : StateCache {
        var last: CachedState? = null

        override fun take(ref: AggregateRef): CachedState? = null

        override fun put(ref: AggregateRef, state: CachedState) {
            last = state
        }
    }

    /** The cart model with only [lifecycle] enabled. */
    private fun cartModel(lifecycle: Array<Class<*>>): AggregateModel<CartState> =
        aggregate<CartState>("example", "cart") {
            initial { CartState(it.id) }
            decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
            decide<AddItem> { _, command -> Decision.events(ItemAdded(command.productId, command.quantity)) }
            evolve<CartCreated> { owner = it.owner }
            evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
            lifecycle(*lifecycle)
        }
}
