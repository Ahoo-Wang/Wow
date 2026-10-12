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

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryCheckpointStore
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The built-in snapshot subscription (design 03 §3, roadmap 4.6) and the default wait it completes (D69). */
class SnapshotSubscriptionTest {
    data class Add(val amount: Int = 1)
    data class Added(val amount: Int)
    data class Write(val text: String = "")
    data class Written(val text: String)

    class Counter {
        var total = 0
    }

    class Note {
        var text = ""
    }

    /** A state that cannot be serialized once its total reaches 2. */
    class Fragile {
        var total = 0

        @Suppress("unused") // Read by Jackson.
        val checked: Int
            get() = total.also { check(it < 2) { "A fragile total of $it does not serialize." } }
    }

    private val counter = aggregate<Counter>("shop", "counter") {
        initial { Counter() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    /** Snapshots disabled: not given to the snapshot subscription. */
    private val note = aggregate<Note>("shop", "note") {
        initial { Note() }
        decide<Write>(Creation.ALLOWED) { _, body -> Decision.events(Written(body.text)) }
        evolve<Written> { text = it.text }
    }

    private val fragile = aggregate<Fragile>("shop", "fragile") {
        initial { Fragile() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    /** Records the first version of each read of an aggregate's streams (the tail is not recorded). */
    private class RecordingLog(private val delegate: EventLog) : EventLog by delegate {
        val reads = CopyOnWriteArrayList<Int>()

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            reads += fromVersion
            return delegate.read(ref, fromVersion, toVersion)
        }
    }

    /** Records each save asked of it; while [gate] is set, a save waits for it first. */
    private class RecordingStore(private val delegate: SnapshotStore = InMemorySnapshotStore()) :
        SnapshotStore by delegate {
        val saves = CopyOnWriteArrayList<Int>()
        val loads = AtomicInteger()

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            saves += snapshot.version
            gate?.await()
            return delegate.save(snapshot)
        }

        override suspend fun load(ref: AggregateRef): SnapshotRecord? {
            loads.incrementAndGet()
            return delegate.load(ref)
        }
    }

    private val codec = EventCodec()
    private val log = RecordingLog(InMemoryEventLog(partitions = 1))
    private val store = RecordingStore()
    private val kernel = Kernel(log, codec, snapshots = store)
    private val snapshots = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val registry = WaitRegistry()
    private val signals = LocalSignalSender(origin.node, registry)
    private val sequence = AtomicInteger()

    /** What a second subscription of the same runtime has taken: the shared tail gave it to the snapshot one too. */
    private val probed = CopyOnWriteArrayList<Pair<String, Int>>()
    private val probe = Subscription("probe", SubscriptionFilter("shop")) { probed += it.ref.id to it.version }

    private val runtimes = CopyOnWriteArrayList<SubscriptionRuntime>()

    private fun runtime(
        checkpoints: CheckpointStore = InMemoryCheckpointStore(),
        subscription: SnapshotSubscription = snapshots,
    ) = SubscriptionRuntime(
        "shop",
        log,
        checkpoints,
        InMemoryFailureLedger(),
        listOf(subscription.subscription, probe),
        testOptions(),
        signals = signals,
    ).also { runtimes += it }

    private val runtime = runtime()
    private val executor = Executor(kernel, views = snapshots.views)
    private val coordinator = WaitCoordinator(executor, registry, runtime, signals, WaitOptions(origin))

    private fun add(id: String, wait: WaitPlan = WaitPlan.SNAPSHOT, requestId: String? = null): Command<Add> {
        val n = sequence.incrementAndGet()
        return Command("c-$n", requestId ?: "r-$n", counter.type.ref("t", id), Add(), wait = wait)
    }

    private fun ref(id: String) = counter.type.ref("t", id)

    @AfterEach
    fun stop(): Unit = runBlocking {
        store.gate?.complete(Unit)
        runtimes.forEach { it.stop() }
    }

    @Test
    fun `the default wait answers once the snapshot holds the commit`(): Unit = runBlocking {
        runtime.start(setOf(0))
        val owned = Scope("alice", "space")
        val first = coordinator.execute(counter, add("a").copy(scope = owned))
        first.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        first.hop.assert().isEqualTo(Hop.snapshot("shop"))
        val created = store.load(ref("a"))!!
        created.version.assert().isEqualTo(1)
        created.state.assert().isEqualTo("""{"total":1}""")
        created.scope.assert().isEqualTo(owned)
        created.schemaFingerprint.assert().isEqualTo(codec.stateSchemaFingerprint(counter))

        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        val updated = store.load(ref("a"))!!
        updated.version.assert().isEqualTo(2)
        updated.state.assert().isEqualTo("""{"total":2}""")
        // The scope is the stream's, carried forward from the creating command (D56).
        updated.scope.assert().isEqualTo(owned)
        val streams = log.read(ref("a")).toList()
        updated.firstEventTime.assert().isEqualTo(streams[0].commitTime)
        updated.lastEventTime.assert().isEqualTo(streams[1].commitTime)
    }

    @Test
    fun `an aggregate with snapshots disabled waits for COMMITTED by default`(): Unit = runBlocking {
        runtime.start(setOf(0))
        val ref = note.type.ref("t", "n")
        val result = coordinator.execute(note, Command("c-note", "r-note", ref, Write("hi")))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        eventually { probed.contains("n" to 1) }
        store.load(ref).assert().isNull()
        store.saves.assert().isEmpty()
        snapshots.views.size.assert().isEqualTo(0)
    }

    @Test
    fun `one aggregate's entries waiting together are written once, at the latest version`(): Unit = runBlocking {
        runtime.start(setOf(0))
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val first = async { coordinator.execute(counter, add("a")) }
        eventually { store.saves.size == 1 }
        // Versions 2 to 5 queue behind version 1, whose write is held.
        val later = (2..5).map { async { coordinator.execute(counter, add("a")) } }
        eventually { probed.count { it.first == "a" } == 5 }
        delay(50)
        later.forEach { it.isCompleted.assert().isFalse() }
        gate.complete(Unit)

        first.await().hop.assert().isEqualTo(Hop.snapshot("shop"))
        later.map { it.await() }.forEach {
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            it.hop.assert().isEqualTo(Hop.snapshot("shop"))
        }
        store.saves.assert().containsExactly(1, 5)
        store.load(ref("a"))!!.state.assert().isEqualTo("""{"total":5}""")
    }

    @Test
    fun `a replay never moves a snapshot back, nor rebuilds what the store holds`(): Unit = runBlocking {
        runtime.start(setOf(0))
        repeat(3) { coordinator.execute(counter, add("a")) }
        store.load(ref("a"))!!.version.assert().isEqualTo(3)
        runtime.stop()
        val saved = store.saves.size
        val third = log.read(ref("a"), 3, 3).toList().single()
        log.reads.clear()

        // A new checkpoint replays the partition from the beginning.
        val replay = runtime(InMemoryCheckpointStore())
        replay.start(setOf(0))
        eventually { replay.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, third) }
        store.saves.size.assert().isEqualTo(saved)
        store.load(ref("a"))!!.version.assert().isEqualTo(3)
        // No state was rebuilt from the log.
        log.reads.assert().isEmpty()
    }

    @Test
    fun `without a state view the snapshot and the log's tail give the state`(): Unit = runBlocking {
        // Commands run outside the executor that records views: as on another node, or before an ownership change.
        val elsewhere = Executor(kernel)
        repeat(2) { elsewhere.execute(counter, add("a", WaitPlan.COMMITTED)) }
        runtime.start(setOf(0))
        eventually { store.load(ref("a"))?.version == 2 }
        runtime.stop()
        repeat(3) { elsewhere.execute(counter, add("a", WaitPlan.COMMITTED)) }
        val fifth = log.read(ref("a"), 5, 5).toList().single()
        log.reads.clear()

        val other = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "snapshot", 0, 2) }, other)
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, fifth) }
        // Evolved from the stored snapshot, never replayed from the first stream.
        log.reads.assert().isNotEmpty()
        log.reads.none { it == 1 }.assert().isTrue()
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(5)
        snapshot.state.assert().isEqualTo("""{"total":5}""")
        snapshot.firstEventTime.assert().isEqualTo(log.read(ref("a"), 1, 1).toList().single().commitTime)
    }

    @Test
    fun `a state view is written without reading the log or the store`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        runtime.stop()
        // The view is recorded as the command commits, before the stopped runtime reads the entry.
        val committed = executor.execute(counter, add("a", WaitPlan.COMMITTED)) as CommandOutcome.Committed
        snapshots.views.size.assert().isEqualTo(1)
        log.reads.clear()
        val loads = store.loads.get()

        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "snapshot", 0, 1) })
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, committed.stream) }
        store.loads.get().assert().isEqualTo(loads)
        log.reads.assert().isEmpty()
        snapshots.views.size.assert().isEqualTo(0)
        store.load(ref("a"))!!.lastEventTime.assert().isEqualTo(committed.stream.commitTime)
    }

    @Test
    fun `a duplicate request is answered from the snapshot subscription's checkpoint`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a", requestId = "same")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        val again = coordinator.execute(counter, add("a", requestId = "same"))
        again.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        again.duplicate.assert().isTrue()
        again.hop.assert().isEqualTo(Hop.snapshot("shop"))
    }

    @Test
    fun `a state view that fails to serialize never fails the committed command`(): Unit = runBlocking {
        val subscription = SnapshotSubscription("shop", listOf(fragile), store, kernel, log, codec)
        val executor = Executor(kernel, views = subscription.views)
        val ref = fragile.type.ref("t", "f")
        fun add() = sequence.incrementAndGet().let { Command("c-$it", "r-$it", ref, Add(), wait = WaitPlan.COMMITTED) }

        executor.execute(fragile, add()).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        subscription.views.size.assert().isEqualTo(1)
        // Version 2 cannot be serialized: still committed, and the stale view of version 1 is dropped.
        val second = executor.execute(fragile, add()) as CommandOutcome.Committed
        second.stream.version.assert().isEqualTo(2)
        subscription.views.size.assert().isEqualTo(0)

        val ledger = InMemoryFailureLedger()
        val running = SubscriptionRuntime(
            "shop",
            log,
            InMemoryCheckpointStore(),
            ledger,
            listOf(subscription.subscription),
            testOptions(),
        ).also { runtimes += it }
        running.start(setOf(0))
        // The subscription rebuilds the state instead; version 2 fails to serialize there too, so the aggregate
        // parks: from version 2, or from 1 when both entries ran as one batch. Nothing past version 1 is written.
        eventually { ledger.isParked(Hop.SNAPSHOT_SUBSCRIPTION, ref) }
        (ledger.parked(Hop.SNAPSHOT_SUBSCRIPTION, ref)!!.fromVersion in 1..2).assert().isTrue()
        (store.load(ref)?.version ?: 1).assert().isEqualTo(1)
    }

    @Test
    fun `a snapshot subscription names the aggregate types it snapshots, all of its context`() {
        runtime.covers(Hop.SNAPSHOT_SUBSCRIPTION, counter.type).assert().isTrue()
        runtime.covers(Hop.SNAPSHOT_SUBSCRIPTION, note.type).assert().isFalse()
        snapshots.subscription.name.assert().isEqualTo(Hop.SNAPSHOT_SUBSCRIPTION)
        assertThrows<IllegalArgumentException> {
            SnapshotSubscription("other", listOf(counter), store, kernel, log)
        }
        assertThrows<IllegalArgumentException> {
            SnapshotSubscription("shop", emptyList(), store, kernel, log)
        }
    }
}
