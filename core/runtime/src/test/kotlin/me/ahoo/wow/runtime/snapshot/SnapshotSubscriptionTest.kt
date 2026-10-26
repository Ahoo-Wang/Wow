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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.ApplyTags
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelRejections
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
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
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
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

    /**
     * Records the first version of each read of an aggregate's streams (the tail is not recorded); while [afterAppend]
     * is set, an append returns only once it has run on the committed stream.
     */
    private class RecordingLog(private val delegate: EventLog) : EventLog by delegate {
        val reads = CopyOnWriteArrayList<Int>()

        @Volatile
        var afterAppend: (suspend (EventStreamRecord) -> Unit)? = null

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
            delegate.append(stream, transport).also { afterAppend?.invoke(stream) }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            reads += fromVersion
            return delegate.read(ref, fromVersion, toVersion)
        }

        override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
            reads += fromVersion
            return delegate.readTail(ref, fromVersion, earlier)
        }

        /** Read through [read] and [readTail], so that they count it. */
        override suspend fun readWithSnapshot(ref: AggregateRef, snapshots: SnapshotStore, earlier: Int): SnapshotTail =
            super.readWithSnapshot(ref, snapshots, earlier)
    }

    /** Records each save asked of it, and the size of each batch; while [gate] is set, a save waits for it first. */
    private class RecordingStore(private val delegate: SnapshotStore = InMemorySnapshotStore()) :
        SnapshotStore by delegate {
        val saves = CopyOnWriteArrayList<Int>()
        val batches = CopyOnWriteArrayList<Int>()
        val loads = AtomicInteger()

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            saves += snapshot.version
            gate?.await()
            return delegate.save(snapshot)
        }

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            batches += snapshots.size
            saves += snapshots.map { it.version }
            gate?.await()
            return delegate.saveAll(snapshots)
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

    private companion object {
        val ALICE = Operator("alice", "t")
        val BOB = Operator("bob", "home", crossScope = true)
    }

    /** A command of any [body], a lifecycle one included, to counter [id]. */
    private fun command(
        id: String,
        body: Any,
        wait: WaitPlan = WaitPlan.COMMITTED,
        scope: Scope = Scope.NONE,
    ): Command<Any> {
        val n = sequence.incrementAndGet()
        return Command("c-$n", "r-$n", ref(id), body, scope = scope, wait = wait)
    }

    private fun CommandOutcome.rejection(): String = (this as CommandOutcome.Rejected).rejection.code

    @AfterEach
    fun stop(): Unit = runBlocking {
        store.gate?.complete(Unit)
        runtimes.forEach { it.stop() }
    }

    @Test
    fun `the default wait answers once the snapshot holds the commit`(): Unit = runBlocking {
        runtime.start(setOf(0))
        val owned = Scope("alice", "space")
        val first = coordinator.execute(counter, add("a").copyWith(scope = owned, operator = ALICE))
        first.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        first.hop.assert().isEqualTo(Hop.snapshot("shop"))
        val created = store.load(ref("a"))!!
        created.version.assert().isEqualTo(1)
        created.state.assert().isEqualTo("""{"total":1}""")
        created.scope.assert().isEqualTo(owned)
        created.schemaFingerprint.assert().isEqualTo(codec.stateSchemaFingerprint(counter))
        created.operator.assert().isEqualTo(ALICE)
        created.firstOperator.assert().isEqualTo(ALICE)

        coordinator.execute(counter, add("a").copyWith(operator = BOB)).hop.assert().isEqualTo(Hop.snapshot("shop"))
        val updated = store.load(ref("a"))!!
        updated.version.assert().isEqualTo(2)
        updated.state.assert().isEqualTo("""{"total":2}""")
        // The scope is the stream's, carried forward from the creating command (D56).
        updated.scope.assert().isEqualTo(owned)
        val streams = log.read(ref("a")).toList()
        updated.firstEventTime.assert().isEqualTo(streams[0].commitTime)
        updated.lastEventTime.assert().isEqualTo(streams[1].commitTime)
        // The last stream's operator, and the creator's (decision D163).
        updated.operator.assert().isEqualTo(BOB)
        updated.firstOperator.assert().isEqualTo(ALICE)
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        store.load(ref("a"))!!.run {
            operator.assert().isNull()
            firstOperator.assert().isEqualTo(ALICE)
        }
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
    fun `the snapshots of a partition's aggregates built while a write runs are written together`(): Unit =
        runBlocking {
            runtime.start(setOf(0))
            val gate = CompletableDeferred<Unit>().also { store.gate = it }
            val first = async { coordinator.execute(counter, add("a")) }
            eventually { store.batches.size == 1 }
            // Eight other aggregates commit while a's write is held: their snapshots wait for the next write.
            val ids = (1..8).map { "b$it" }
            val others = ids.map { async { coordinator.execute(counter, add(it)) } }
            eventually { ids.all { id -> probed.contains(id to 1) } }
            delay(50)
            // No wait is answered before the write that covers it.
            (listOf(first) + others).forEach { it.isCompleted.assert().isFalse() }
            store.gate = null
            gate.complete(Unit)
            (listOf(first) + others).map { it.await() }.forEach {
                it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                it.hop.assert().isEqualTo(Hop.snapshot("shop"))
            }
            store.batches.assert().containsExactly(1, ids.size)
            ids.forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
        }

    @Test
    fun `the snapshots of the partitions of one lane share the writes`(): Unit = runBlocking {
        // B.O-5: partitions 0, 4 and 8 share a writer (p % 4), so a write holds more than one partition's snapshot.
        listOf("p0", "p1", "p2").forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
        fun entry(id: String, partition: Int) = async {
            val stream = log.read(ref(id)).toList().single()
            snapshots.subscription.handle(
                snapshots.subscription.decoder.decode(stream, LogPosition(partition, 1), null)
            )
        }
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val first = entry("p0", 0)
        eventually { store.batches.size == 1 }
        // Partitions 4 and 8 wait for the write partition 0 leads, and are written together after it.
        val others = listOf(entry("p1", 4), entry("p2", 8))
        delay(50)
        store.batches.size.assert().isEqualTo(1)
        store.gate = null
        gate.complete(Unit)
        (listOf(first) + others).awaitAll()
        store.batches.assert().containsExactly(1, 2)
        listOf("p0", "p1", "p2").forEach { store.load(ref(it))!!.version.assert().isEqualTo(1) }
    }

    @Test
    fun `the partitions of other lanes write at once`(): Unit = runBlocking {
        listOf("q0", "q1").forEach { executor.execute(counter, command(it, Add())) as CommandOutcome.Committed }
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        val handled = listOf("q0" to 0, "q1" to 1).map { (id, partition) ->
            async {
                val stream = log.read(ref(id)).toList().single()
                snapshots.subscription.handle(
                    snapshots.subscription.decoder.decode(stream, LogPosition(partition, 1), null)
                )
            }
        }
        // Partition 1's lane does not wait for partition 0's write.
        eventually { store.batches.size == 2 }
        store.gate = null
        gate.complete(Unit)
        handled.awaitAll()
        store.batches.assert().containsExactly(1, 1)
    }

    @Test
    fun `an entry made by hand, without a position, is written with partition 0's snapshots`(): Unit = runBlocking {
        executor.execute(counter, command("h", Add())) as CommandOutcome.Committed
        val stream = log.read(ref("h")).toList().single()
        val entry = snapshots.subscription.decoder.decode(stream, null, null)
        snapshots.subscription.handle(entry)
        store.load(ref("h"))!!.version.assert().isEqualTo(1)
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
    fun `a state view recorded after its entry was written is never written again`(): Unit = runBlocking {
        runtime.start(setOf(0))
        repeat(2) { coordinator.execute(counter, add("a")) }
        // Version 3's append returns only once the subscription has written it without a view: the executor records
        // the view of version 3 afterwards, as when the subscription wins the race to the entry.
        log.afterAppend = { stream -> eventually { store.load(stream.ref)?.version == stream.version } }
        val third = executor.execute(counter, add("a", WaitPlan.COMMITTED)) as CommandOutcome.Committed
        log.afterAppend = null
        third.stream.version.assert().isEqualTo(3)
        snapshots.views.size.assert().isEqualTo(1)
        runtime.stop()
        val saved = store.saves.size

        // A replay finds the late view of a version the store already holds: passed, never saved again. It stays only
        // as the base of the aggregate's next commit.
        val replay = runtime(InMemoryCheckpointStore())
        replay.start(setOf(0))
        eventually { replay.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, third.stream) }
        store.saves.size.assert().isEqualTo(saved)
        snapshots.views.size.assert().isEqualTo(1)
        store.load(ref("a"))!!.state.assert().isEqualTo("""{"total":3}""")
    }

    @Test
    fun `without a state view the snapshot and the log's tail give the state`(): Unit = runBlocking {
        // Commands run outside the executor that records views: as on another node, or before an ownership change.
        val elsewhere = Executor(kernel)
        repeat(2) { elsewhere.execute(counter, add("a", WaitPlan.COMMITTED).copyWith(operator = ALICE)) }
        runtime.start(setOf(0))
        eventually { store.load(ref("a"))?.version == 2 }
        runtime.stop()
        repeat(3) { elsewhere.execute(counter, add("a", WaitPlan.COMMITTED).copyWith(operator = BOB)) }
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
        // The first operator comes from the stored snapshot too.
        snapshot.operator.assert().isEqualTo(BOB)
        snapshot.firstOperator.assert().isEqualTo(ALICE)
    }

    @Test
    fun `a state view is written without reading the log or the store`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        runtime.stop()
        // The view is recorded as the command commits, before the stopped runtime reads the entry.
        val committed = executor.execute(counter, add("a", WaitPlan.COMMITTED)) as CommandOutcome.Committed
        snapshots.views.get(ref("a"), 2).assert().isNotNull()
        log.reads.clear()
        val loads = store.loads.get()

        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "snapshot", 0, 1) })
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, committed.stream) }
        store.loads.get().assert().isEqualTo(loads)
        log.reads.assert().isEmpty()
        // Its only reader passed it: the earlier view is evicted, the latest kept as the next commit's base.
        snapshots.views.get(ref("a"), 1).assert().isNull()
        snapshots.views.size.assert().isEqualTo(1)
        store.load(ref("a"))!!.lastEventTime.assert().isEqualTo(committed.stream.commitTime)
    }

    @Test
    fun `a deletion written from its state view reloads as deleted until it is restored`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        runtime.stop()
        // The view is recorded as the deletion commits, before the stopped runtime reads the entry.
        val deleted = executor.execute(counter, command("a", DeleteAggregate)) as CommandOutcome.Committed
        snapshots.views.get(ref("a"), 2)!!.deleted.assert().isTrue()
        log.reads.clear()
        val loads = store.loads.get()

        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "snapshot", 0, 1) })
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, deleted.stream) }
        // Written from the view: neither the store nor the log was read.
        store.loads.get().assert().isEqualTo(loads)
        log.reads.assert().isEmpty()
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(2)
        snapshot.deleted.assert().isTrue()

        // The kernel caches no state, as after an eviction: it reloads from the snapshot, whose tail is empty.
        executor.execute(counter, add("a", WaitPlan.COMMITTED)).rejection().assert().isEqualTo(KernelRejections.DELETED)
        log.reads.assert().containsExactly(2)

        val restored = executor.execute(counter, command("a", RestoreAggregate)) as CommandOutcome.Committed
        eventually { resumed.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, restored.stream) }
        store.load(ref("a"))!!.deleted.assert().isFalse()
        val added = executor.execute(counter, add("a", WaitPlan.COMMITTED)) as CommandOutcome.Committed
        added.stream.version.assert().isEqualTo(4)
    }

    @Test
    fun `a state view a new subscription has not written before keeps the view's metadata`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        runtime.stop()
        val tags = mapOf("tier" to listOf("gold"))
        Executor(kernel).execute(counter, command("a", ApplyTags(tags)))
        // A subscription that has written nothing yet, as after a restart: its views are recorded before it runs.
        val other = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
        val deleted = Executor(kernel, views = other.views)
            .execute(counter, command("a", DeleteAggregate)) as CommandOutcome.Committed
        deleted.first.assert().isNotNull()
        log.reads.clear()
        val loads = store.loads.get()

        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "snapshot", 0, 2) }, other)
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, deleted.stream) }
        // The view carries what the kernel loaded of the first stream (work package B.O-4): neither the stored
        // snapshot nor the log is read.
        store.loads.get().assert().isEqualTo(loads)
        log.reads.assert().isEmpty()
        other.views.size.assert().isEqualTo(1)
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(3)
        snapshot.deleted.assert().isTrue()
        snapshot.tags.assert().isEqualTo(tags)
        snapshot.firstEventTime.assert().isEqualTo(log.read(ref("a"), 1, 1).toList().single().commitTime)
    }

    @Test
    fun `the snapshot holds the scope and the tags the lifecycle commands gave`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a").copyWith(scope = Scope("alice", "space-a")))
        val tags = mapOf("region" to listOf("eu", "us"))
        listOf(TransferOwner("bob"), TransferSpace("space-b"), ApplyTags(tags)).forEach { body ->
            coordinator.execute(counter, command("a", body, WaitPlan.SNAPSHOT)).hop
                .assert().isEqualTo(Hop.snapshot("shop"))
            store.load(ref("a"))!!.scope.assert().isEqualTo(log.read(ref("a")).toList().last().scope)
        }
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(4)
        snapshot.scope.assert().isEqualTo(Scope("bob", "space-b"))
        snapshot.tags.assert().isEqualTo(tags)
        snapshot.deleted.assert().isFalse()

        // Reloaded from the snapshot alone, the aggregate has them: applying them again changes nothing.
        log.reads.clear()
        executor.execute(counter, command("a", ApplyTags(tags))).assert().isEqualTo(CommandOutcome.Unchanged(4))
        executor.execute(counter, command("a", TransferOwner("bob"))).assert().isEqualTo(CommandOutcome.Unchanged(4))
        executor.execute(counter, command("a", TransferSpace("space-b")))
            .assert().isEqualTo(CommandOutcome.Unchanged(4))
        log.reads.assert().containsExactly(4, 4, 4)
    }

    @Test
    fun `without a state view the snapshot holds the deleted mark, the scope and the tags`(): Unit = runBlocking {
        // Commands run outside the executor that records views: as on another node, or before an ownership change.
        val elsewhere = Executor(kernel)
        val tags = mapOf("tier" to listOf("gold"))
        elsewhere.execute(counter, command("a", Add(), scope = Scope("alice", "space-a")))
        listOf(ApplyTags(tags), TransferOwner("bob"), DeleteAggregate).forEach {
            elsewhere.execute(counter, command("a", it)).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        }
        // No snapshot is stored yet: the log is replayed.
        runtime.start(setOf(0))
        eventually { store.load(ref("a"))?.version == 4 }
        runtime.stop()
        val deleted = store.load(ref("a"))!!
        deleted.deleted.assert().isTrue()
        deleted.tags.assert().isEqualTo(tags)
        deleted.scope.assert().isEqualTo(Scope("bob", "space-a"))

        log.reads.clear()
        elsewhere.execute(
            counter,
            add("a", WaitPlan.COMMITTED)
        ).rejection().assert().isEqualTo(KernelRejections.DELETED)
        log.reads.assert().containsExactly(4)

        // The restore is written from the stored snapshot evolved with the log's tail.
        val restored = elsewhere.execute(counter, command("a", RestoreAggregate)) as CommandOutcome.Committed
        log.reads.clear()
        val other = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "snapshot", 0, 4) }, other)
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, restored.stream) }
        log.reads.assert().isNotEmpty()
        log.reads.none { it == 1 }.assert().isTrue()
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(5)
        snapshot.deleted.assert().isFalse()
        snapshot.tags.assert().isEqualTo(tags)
        snapshot.scope.assert().isEqualTo(Scope("bob", "space-a"))
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
        // Version 2 cannot be serialized: still committed, without a view; the view of version 1 stays, right for it.
        val second = executor.execute(fragile, add()) as CommandOutcome.Committed
        second.stream.version.assert().isEqualTo(2)
        subscription.views.get(ref, 2).assert().isNull()
        subscription.views.size.assert().isEqualTo(1)

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
