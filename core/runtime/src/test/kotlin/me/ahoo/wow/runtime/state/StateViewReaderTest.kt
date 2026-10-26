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

package me.ahoo.wow.runtime.state

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.ItemsAdded
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.orderModel
import me.ahoo.wow.runtime.subscription.EntryDecoder
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Decision D133: the state after a committed entry, read by one state consumer through the shared views, else from
 * the nearest view or snapshot and the log's tail, else the log replayed; and the `stateEvents` decoder.
 */
class StateViewReaderTest {
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var streamsRead = 0

        /** Runs as each stream commits, before the append returns: where the log hands a commit over (D249). */
        var onCommitted: suspend (EventStreamRecord) -> Unit = {}

        /** The outcome of the next appends instead of appending, when given. */
        var refuse: AppendOutcome? = null
        var appended: EventStreamRecord? = null

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            delegate.read(ref, fromVersion, toVersion).onEach { streamsRead++ }

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
            appended = stream
            return refuse ?: delegate.append(stream, transport).also {
                if (it is AppendOutcome.Committed) {
                    onCommitted(stream)
                }
            }
        }
    }

    /** A snapshot store counting its loads. */
    private class CountingSnapshots(private val delegate: SnapshotStore = InMemorySnapshotStore()) :
        SnapshotStore by delegate {
        var loads = 0

        override suspend fun load(ref: AggregateRef): SnapshotRecord? = delegate.load(ref).also { loads++ }
    }

    private val codec = EventCodec()
    private val log = CountingLog()
    private val snapshots = CountingSnapshots()
    private val kernel = Kernel(log, codec = codec, snapshots = snapshots)
    private val cart = listCartModel()
    private val models = listOf(orderModel(), cart)
    private val views = StateViews(codec, mapOf(SNAPSHOT to setOf(CART), PROJECTION to setOf(CART)))
    private val executor = Executor(kernel, views = views)
    private val ref: AggregateRef = CART.ref(TENANT, "alice")

    private fun reader(
        name: String = PROJECTION,
        views: StateViews = this.views,
        snapshots: SnapshotStore? = this.snapshots,
    ) = StateViewReader(name, models, kernel, codec, views, snapshots)

    private suspend fun add(vararg items: String, executor: Executor = this.executor): CommandOutcome.Committed {
        val id = "c-${items.joinToString("-")}"
        val command = Command(id, "r-$id", ref, AddItems(items.toList()), scope = Scope(ownerId = "alice"))
        return executor.execute(cart, command) as CommandOutcome.Committed
    }

    private suspend fun stream(version: Int): EventStreamRecord = log.read(ref, version, version).toList().single()

    private fun SerializedState.items(): List<String> = codec.decodeState(cart, state).items

    /** A cart whose state reads back whole: its items keep their order. */
    class ListCart {
        var items: List<String> = emptyList()
    }

    private fun listCartModel(): AggregateModel<ListCart> = aggregate<ListCart>(SHOP, "cart") {
        initial { ListCart() }
        decide<AddItems>(Creation.ALLOWED) { _, body -> Decision.events(ItemsAdded(body.items)) }
        evolve<ItemsAdded> { items = items + it.items }
    }

    @Test
    fun `the state should come from the view the commit took, left for the other readers`(): Unit = runBlocking {
        add("apple")
        val view = reader().read(stream(1))
        view.items().assert().containsExactly("apple")
        view.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        view.deleted.assert().isFalse()
        // The other reader still finds it; once both passed a later version, it goes.
        reader(SNAPSHOT).read(stream(1)).assert().isSameAs(view)
        add("pear")
        reader().read(stream(2))
        views.get(ref, 1).assert().isSameAs(view)
        reader(SNAPSHOT).read(stream(2))
        views.get(ref, 1).assert().isNull()
        views.get(ref, 2).assert().isNotNull()
    }

    @Test
    fun `a reader handed the node's own commit before its view is recorded should read no store`(): Unit =
        runBlocking {
            // The reader runs where the log hands each commit over: inside the append, before the executor records.
            val read = mutableListOf<SerializedState>()
            log.onCommitted = { stream ->
                views.get(ref, stream.version).assert().isNull()
                val streams = log.streamsRead
                val loads = snapshots.loads
                read += reader().read(stream)
                (log.streamsRead - streams).assert().isEqualTo(0)
                (snapshots.loads - loads).assert().isEqualTo(0)
            }
            val committed = listOf(add("apple"), add("pear"), add("plum"))
            read.map { it.version }.assert().containsExactly(1, 2, 3)
            read.last().items().assert().containsExactly("apple", "pear", "plum")
            committed.zip(read).forEach { (commit, view) ->
                view.state.assert().isEqualTo(codec.stateText(commit.state))
                view.scope.assert().isEqualTo(commit.stream.scope)
            }
            // The executor's record afterwards keeps the reader's view: one view a version, read by the other reader.
            reader(SNAPSHOT).read(stream(3)).assert().isSameAs(read.last())
        }

    @Test
    fun `without a pending view, a reader of the node's own commits should evolve the view it read last`(): Unit =
        runBlocking {
            // An executor that records no views: the reader keeps what it read, and evolves it with the next commit.
            val elsewhere = Executor(kernel)
            log.onCommitted = { stream ->
                val streams = log.streamsRead
                val loads = snapshots.loads
                reader().read(stream)
                (log.streamsRead - streams).assert().isEqualTo(0)
                (snapshots.loads - loads).assert().isEqualTo(0)
            }
            add("apple", executor = elsewhere)
            add("pear", executor = elsewhere)
            log.onCommitted = {}
            views.get(ref, 2)!!.items().assert().containsExactly("apple", "pear")
        }

    @Test
    fun `an append that did not commit should leave no pending view`(): Unit = runBlocking {
        add("apple")
        log.refuse = AppendOutcome.Unknown(IllegalStateException("The store timed out."))
        val command = Command("c-pear", "r-pear", ref, AddItems(listOf("pear")), scope = Scope(ownerId = "alice"))
        executor.execute(cart, command).assert().isInstanceOf(CommandOutcome.Unknown::class.java)
        views.get(log.appended!!).assert().isNull()
        log.refuse = AppendOutcome.Rejected(IllegalStateException("The store refuses it."))
        runCatching { executor.execute(cart, command) }.exceptionOrNull()!!.message.assert().contains("refuses")
        views.get(log.appended!!).assert().isNull()
        views.get(ref, 2).assert().isNull()
    }

    @Test
    fun `a view recorded before the reader is handed the commit should be read as it is`(): Unit = runBlocking {
        add("apple")
        add("pear")
        val entries = listOf(stream(1), stream(2))
        val loads = snapshots.loads
        log.streamsRead = 0
        reader().read(entries[0]).assert().isSameAs(views.get(ref, 1))
        reader().read(entries[1]).assert().isSameAs(views.get(ref, 2))
        log.streamsRead.assert().isEqualTo(0)
        snapshots.loads.assert().isEqualTo(loads)
    }

    @Test
    fun `a view evicted between two commits should cost the store reads again, and only once`(): Unit = runBlocking {
        val time = TestTimeSource()
        val aging = StateViews(codec, mapOf(PROJECTION to setOf(CART)), StateViewOptions(maxAge = 1.seconds), time)
        val elsewhere = Executor(kernel)
        add("apple", executor = elsewhere)
        add("pear", executor = elsewhere)
        add("plum", executor = elsewhere)
        val entries = (1..3).map { stream(it) }
        val loads = snapshots.loads
        reader(views = aging).read(entries[0])
        // The view the reader kept for version 1 ages out before version 2 is handed over.
        time += 2.seconds
        aging.size.assert().isEqualTo(1)
        log.streamsRead = 0
        reader(views = aging).read(entries[1]).items().assert().containsExactly("apple", "pear")
        snapshots.loads.assert().isEqualTo(loads + 1)
        log.streamsRead.assert().isEqualTo(2)
        log.streamsRead = 0
        reader(views = aging).read(entries[2]).items().assert().containsExactly("apple", "pear", "plum")
        snapshots.loads.assert().isEqualTo(loads + 1)
        log.streamsRead.assert().isEqualTo(0)
    }

    @Test
    fun `without a view the state should be the snapshot evolved with the log's tail`(): Unit = runBlocking {
        val elsewhere = Executor(kernel)
        add("apple", executor = elsewhere)
        add("pear", executor = elsewhere)
        snapshots.save(
            codec.snapshotOf(cart, ref, ListCart().apply { items = listOf("snapshot") }, 1, Scope.NONE, 0, 0)
        )
        add("plum", executor = elsewhere)
        log.streamsRead = 0
        reader().read(stream(2)).items().assert().containsExactlyInAnyOrder("snapshot", "pear")
        // The stream read for the entry, then the snapshot's version and the tail up to the entry.
        log.streamsRead.assert().isEqualTo(3)
        // The rebuilt state is shared: the other reader finds it as a view.
        views.get(ref, 2)!!.items().assert().containsExactlyInAnyOrder("snapshot", "pear")
    }

    @Test
    fun `a reader behind the snapshot should replay once, then evolve what it read`(): Unit = runBlocking {
        val elsewhere = Executor(kernel)
        repeat(5) { add("item-$it", executor = elsewhere) }
        snapshots.save(codec.snapshotOf(cart, ref, ListCart(), 5, Scope.NONE, 0, 0))
        val entries = (1..5).map { stream(it) }
        log.streamsRead = 0
        // The first stream evolves the initial state: nothing read.
        reader().read(entries[0]).items().assert().containsExactly("item-0")
        log.streamsRead.assert().isEqualTo(0)
        reader().read(entries[2]).items().assert().containsExactly("item-0", "item-1", "item-2")
        // From the view it rebuilt at version 1: versions 2 and 3 only, not the log from version 1.
        log.streamsRead.assert().isEqualTo(2)
        log.streamsRead = 0
        // The view of the version right before the entry evolves with the entry in hand.
        reader().read(entries[3]).items().assert().hasSize(4)
        log.streamsRead.assert().isEqualTo(0)
    }

    @Test
    fun `a snapshot nearer than the latest view should be the base`(): Unit = runBlocking {
        val elsewhere = Executor(kernel)
        repeat(4) { add("item-$it", executor = elsewhere) }
        views.put(ref, SerializedState(1, codec.stateText(ListCart().apply { items = listOf("view") })))
        snapshots.save(
            codec.snapshotOf(cart, ref, ListCart().apply { items = listOf("snapshot") }, 3, Scope.NONE, 0, 0)
        )
        reader().read(stream(4)).items().assert().containsExactly("snapshot", "item-3")
        reader().read(stream(2), snapshot = null).items().assert().containsExactly("view", "item-1")
    }

    @Test
    fun `a view that does not decode whole should not be a base`(): Unit = runBlocking {
        val elsewhere = Executor(kernel)
        repeat(3) { add("item-$it", executor = elsewhere) }
        // One that does not parse.
        views.put(ref, SerializedState(2, """{"items":"""))
        reader().read(stream(3)).items().assert().containsExactly("item-0", "item-1", "item-2")
        // One that parses but does not write back the same.
        views.put(ref, SerializedState(4, """{"items":["view"],"unknown":1}"""))
        add("item-3", executor = elsewhere)
        add("item-4", executor = elsewhere)
        reader().read(stream(5)).items().assert().hasSize(5)
    }

    @Test
    fun `a failing store or no store should cost a replay, not the entry`(): Unit = runBlocking {
        val elsewhere = Executor(kernel)
        add("apple", executor = elsewhere)
        add("pear", executor = elsewhere)
        val failing = object : SnapshotStore by snapshots {
            override suspend fun load(ref: AggregateRef): SnapshotRecord? = error("The store is down.")
        }
        val alone = StateViews(codec, emptyMap())
        reader(
            views = alone,
            snapshots = failing
        ).read(stream(2)).items().assert().containsExactlyInAnyOrder("apple", "pear")
        reader(views = alone, snapshots = null).read(stream(1)).items().assert().containsExactly("apple")
        alone.size.assert().isEqualTo(0)
    }

    @Test
    fun `an entry the reader cannot read should fail`(): Unit = runBlocking {
        add("apple")
        val ahead = stream(1).copyWith(version = 5)
        runCatching {
            StateViewReader(
                PROJECTION,
                listOf(orderModel()),
                kernel,
                codec,
                views
            ).read(stream(1).copyWith(version = 2))
        }
            .exceptionOrNull()!!.message.assert().contains("No model of shop.cart")
        runCatching { reader().read(ahead) }.exceptionOrNull()!!.message
            .assert().contains("The log does not hold shop.cart/t/alice@5")
    }

    @Test
    fun `the stateEvents decoder should decode the events and read the state on demand`(): Unit = runBlocking {
        add("apple")
        val entry = EntryDecoder.stateEvents(reader()).decode(stream(1), null, null)
        entry.events.assert().containsExactly(ItemsAdded(listOf("apple")))
        codec.decodeState(cart, entry.state().state).items.assert().containsExactly("apple")
        val records = EntryDecoder.stateEvents(reader(), decodeEvents = false).decode(stream(1), null, null)
        records.events.assert().isEqualTo(stream(1).events)
        val plain = SubscribedEntry(stream(1), null, emptyList())
        runCatching { plain.state() }.exceptionOrNull()!!.message.assert()
            .contains("was not decoded with its state")
        runCatching {
            EntryDecoder.stateEvents(
                StateViewReader(PROJECTION, emptyList(), kernel, codec, views)
            ).decode(stream(1), null, null)
        }
            .exceptionOrNull()!!.message.assert().contains("No model of shop.cart")
    }

    @Test
    fun `each subscription decodes its own event instances, so no handler sees another's`(): Unit = runBlocking {
        add("apple")
        val first = EntryDecoder.stateEvents(reader()).decode(stream(1), null, null)
        val second = EntryDecoder.stateEvents(reader()).decode(stream(1), null, null)
        second.events.assert().isEqualTo(first.events)
        second.events.single().assert().isNotSameAs(first.events.single())
    }

    private companion object {
        const val SNAPSHOT = "snapshot"
        const val PROJECTION = "CartView.onEvent"
    }
}
