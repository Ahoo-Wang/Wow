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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.snapshot.StateViews
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test

/** The state event of a committed entry (design 04 §1): the view taken at commit, else the snapshot and the log. */
class StateEventReaderTest {
    private val codec = EventCodec()
    private val log = InMemoryEventLog()
    private val snapshots = InMemorySnapshotStore()
    private val kernel = Kernel(log, codec = codec, snapshots = snapshots)
    private val order = orderModel()
    private val cart = cartModel()
    private val models = listOf(order, cart)
    private val views = StateViews(codec, listOf(CART))
    private val executor = Executor(kernel, sharedViews = views)
    private val decoder = EntryDecoder.models(models, codec)
    private val ref: AggregateRef = CART.ref(TENANT, "alice")

    private suspend fun add(vararg items: String, causation: Causation? = null): CommandOutcome.Committed {
        val id = "c-${items.joinToString("-")}"
        val command = Command(
            id,
            "r-$id",
            ref,
            AddItems(items.toList()),
            scope = Scope(ownerId = "alice"),
            headers = mapOf("app" to "test"),
            causation = causation,
        )
        return executor.execute(cart, command) as CommandOutcome.Committed
    }

    private suspend fun entry(version: Int): SubscribedEntry {
        val stream = log.read(ref, version, version).toList().single()
        return decoder.decode(stream, null, null)
    }

    @Test
    fun `the state should come from the view the commit took, left for the other readers`(): Unit = runBlocking {
        val causation = Causation("root", "shop.order/t/o-1@1")
        add("apple", causation = causation)
        val reader = StateEventReader(models, kernel, codec, views, snapshots)
        val first = reader.read(entry(1))
        val second = reader.read(entry(1))
        (first.state as CartState).items.assert().containsExactly("apple")
        // Each reader gets its own copy.
        first.state.assert().isNotSameAs(second.state)
        views.peek(ref, 1).assert().isNotNull()
        first.events.assert().containsExactly(ItemsAdded(listOf("apple")))
        first.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        first.causation.assert().isEqualTo(causation)
        first.headers.assert().isEqualTo(mapOf("app" to "test"))
        first.requestId.assert().isEqualTo("r-c-apple")
        first.commandId.assert().isEqualTo("c-apple")
        first.id.assert().isEqualTo("shop.cart/t/alice@1")
    }

    @Test
    fun `without a view the state should be the snapshot evolved with the log's tail`(): Unit = runBlocking {
        add("apple")
        val committed = add("pear")
        snapshots.save(
            codec.snapshotOf(cart, ref, CartState().apply { items = setOf("snapshot") }, 1, Scope.NONE, 0, 0),
        )
        add("plum")
        val reader = StateEventReader(models, kernel, codec, views = null, snapshots = snapshots)
        (reader.read(entry(2)).state as CartState).items.assert().containsExactlyInAnyOrder("snapshot", "pear")
        committed.stream.version.assert().isEqualTo(2)
        // A view of another version is not used.
        (StateEventReader(models, kernel, codec, views, snapshots).read(entry(2)).state as CartState).items
            .assert().containsExactlyInAnyOrder("snapshot", "pear")
    }

    @Test
    fun `a snapshot past the entry or a failing store should cost a replay, not the entry`(): Unit = runBlocking {
        add("apple")
        add("pear")
        snapshots.save(codec.snapshotOf(cart, ref, CartState(), 2, Scope.NONE, 0, 0))
        (StateEventReader(models, kernel, codec, null, snapshots).read(entry(1)).state as CartState).items
            .assert().containsExactly("apple")
        val failing = object : SnapshotStore by snapshots {
            override suspend fun load(ref: AggregateRef): SnapshotRecord? = error("The store is down.")
        }
        (StateEventReader(models, kernel, codec, null, failing).read(entry(2)).state as CartState).items
            .assert().containsExactlyInAnyOrder("apple", "pear")
        (StateEventReader(models, kernel, codec).read(entry(2)).state as CartState).items
            .assert().containsExactlyInAnyOrder("apple", "pear")
    }

    @Test
    fun `an entry the reader cannot hand over should fail`(): Unit = runBlocking {
        add("apple")
        val entry = entry(1)
        runCatching { StateEventReader(listOf(order), kernel, codec).read(entry) }.exceptionOrNull()!!.message
            .assert().contains("No model of shop.cart")
        val ahead = entry.copy(stream = entry.stream.copy(version = 5))
        runCatching { StateEventReader(models, kernel, codec).read(ahead) }.exceptionOrNull()!!.message
            .assert().contains("The log does not hold shop.cart/t/alice@5")
    }
}
