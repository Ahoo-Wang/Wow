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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A context of a counter, a note and a fragile aggregate over a recording log and store, its snapshot subscription,
 * runtime and coordinator: what the snapshot subscription's tests share, each test with its own.
 */
abstract class SnapshotSubscriptionFixtures {
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

    protected val counter = aggregate<Counter>("shop", "counter") {
        initial { Counter() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    /** Snapshots disabled: not given to the snapshot subscription. */
    protected val note = aggregate<Note>("shop", "note") {
        initial { Note() }
        decide<Write>(Creation.ALLOWED) { _, body -> Decision.events(Written(body.text)) }
        evolve<Written> { text = it.text }
    }

    protected val fragile = aggregate<Fragile>("shop", "fragile") {
        initial { Fragile() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    /**
     * Records the first version of each read of an aggregate's streams (the tail is not recorded); while [afterAppend]
     * is set, an append returns only once it has run on the committed stream.
     */
    protected class RecordingLog(private val delegate: EventLog) : EventLog by delegate {
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
    }

    /**
     * Records each save asked of it, and the size of each batch; while [gate] is set, a save waits for it first. Its
     * subscription may have [inFlight] of its writes running at once.
     */
    protected class RecordingStore(
        private val delegate: SnapshotStore = InMemorySnapshotStore(),
        private val inFlight: Int = SnapshotStore.DEFAULT_WRITES_IN_FLIGHT,
    ) : SnapshotStore by delegate {
        override val writesInFlight: Int get() = inFlight

        val saves = CopyOnWriteArrayList<Int>()
        val batches = CopyOnWriteArrayList<Int>()
        val loads = AtomicInteger()

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        /** Runs once a save is written and before it returns: a save whose reply is slow. */
        @Volatile
        var afterSave: (suspend () -> Unit)? = null

        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            saves += snapshot.version
            gate?.await()
            return delegate.save(snapshot).also { afterSave?.invoke() }
        }

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            batches += snapshots.size
            saves += snapshots.map { it.version }
            gate?.await()
            return delegate.saveAll(snapshots).also { afterSave?.invoke() }
        }

        override suspend fun load(ref: AggregateRef): SnapshotRecord? {
            loads.incrementAndGet()
            return delegate.load(ref)
        }
    }

    protected val codec = EventCodec()
    protected val log = RecordingLog(InMemoryEventLog(partitions = 1))
    protected val store = RecordingStore()
    protected val kernel = Kernel(log, codec, snapshots = store)
    protected val snapshots = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
    protected val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    protected val registry = WaitRegistry()
    protected val signals = LocalSignalSender(origin.node, registry)
    protected val sequence = AtomicInteger()

    /** What a second subscription of the same runtime has taken: the shared tail gave it to the snapshot one too. */
    protected val probed = CopyOnWriteArrayList<Pair<String, Int>>()
    protected val probe = Subscription("probe", SubscriptionFilter("shop")) { probed += it.ref.id to it.version }

    protected val runtimes = CopyOnWriteArrayList<SubscriptionRuntime>()

    protected fun runtime(
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

    protected val runtime = runtime()
    protected val executor = Executor(kernel, views = snapshots.views)
    protected val coordinator = WaitCoordinator(executor, registry, runtime, signals, origin, WaitOptions())

    protected fun add(id: String, wait: WaitPlan = WaitPlan.SNAPSHOT, requestId: String? = null): Command<Add> {
        val n = sequence.incrementAndGet()
        return Command("c-$n", requestId ?: "r-$n", counter.type.ref("t", id), Add(), wait = wait)
    }

    protected fun ref(id: String) = counter.type.ref("t", id)

    protected companion object {
        val ALICE = Operator("alice", "t")
        val BOB = Operator("bob", "home", actedElsewhere = true)
    }

    /** A command of any [body], a lifecycle one included, to counter [id]. */
    protected fun command(
        id: String,
        body: Any,
        wait: WaitPlan = WaitPlan.COMMITTED,
        scope: Scope = Scope.NONE,
    ): Command<Any> {
        val n = sequence.incrementAndGet()
        return Command("c-$n", "r-$n", ref(id), body, scope = scope, wait = wait)
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        store.gate?.complete(Unit)
        runtimes.forEach { it.stop() }
    }
}
