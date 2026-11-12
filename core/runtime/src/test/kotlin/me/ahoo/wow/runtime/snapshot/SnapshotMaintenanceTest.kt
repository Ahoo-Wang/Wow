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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** The snapshot maintenance of one context (decision D172) on its own: the edges the node scenarios do not reach. */
class SnapshotMaintenanceTest {
    data class Add(val amount: Int)
    data class Added(val amount: Int)

    class Counter {
        var total = 0
    }

    private fun model(revision: Int): AggregateModel<Counter> = aggregate<Counter>(CONTEXT, "counter") {
        stateRevision(revision)
        initial { Counter() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    private val log = InMemoryEventLog(2)
    private val codec = EventCodec()
    private val kernel = Kernel(log, codec)
    private val leases = InMemoryLeaseStore()
    private val checkpoints = InMemoryCheckpointStore()
    private val type = AggregateType(CONTEXT, "counter")

    private fun maintenance(
        revision: Int,
        store: SnapshotStore,
        node: String = "node",
        options: SnapshotOptions = SnapshotOptions(interval = 1.hours, leaseTtl = 2.hours, memberTtl = 2.hours),
        progressLog: EventLog = log,
        observer: Observer = Observer.NONE,
        targets: SnapshotTargets = SnapshotTargets.single(store),
        rebuildLog: EventLog = log,
    ) = SnapshotMaintenance(
        CONTEXT,
        GROUP,
        node,
        listOf(model(revision)),
        targets,
        kernel,
        codec,
        rebuildLog,
        leases,
        listOf(LogProgress(GROUP, progressLog, checkpoints)),
        options,
        observer = observer,
    )

    private suspend fun commit(id: String, amount: Int = 1): AggregateRef {
        val ref = type.ref("tenant", id)
        kernel.execute(model(1), Command("c-$id-$amount", "r-$id-$amount", ref, Add(amount)))
        return ref
    }

    @Test
    fun `a rebuild request rewrites in place before any claim, and ignores another type`(): Unit = runBlocking {
        val store = InMemorySnapshotStore()
        val maintenance = maintenance(1, store)
        maintenance.rebuilding(type).assert().isTrue()
        val ref = commit("a", 3)
        maintenance.rebuild(ref)
        store.load(ref)!!.state.assert().contains("\"total\":3")
        maintenance.rebuild(AggregateType(CONTEXT, "other").ref("tenant", "a"))
        maintenance.rebuild(type.ref("tenant", "nothing"))
        store.load(type.ref("tenant", "nothing")).assert().isNull()
    }

    @Test
    fun `a rebuild request should read and replay the history once, for its own store and its copy alike`(): Unit =
        runBlocking {
            val stores = listOf("a", "b").associateWith { InMemorySnapshotStore() }
            val reads = AtomicInteger()
            val counted = object : EventLog by log {
                override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
                    log.read(ref, fromVersion, toVersion).onStart { reads.incrementAndGet() }
            }
            val maintenance = maintenance(
                1,
                stores.getValue("a"),
                targets = SnapshotTargets("a", stores, fallback = SnapshotPlacement("a", copy = "b")),
                rebuildLog = counted,
            )
            val ref = commit("a", 3)

            maintenance.rebuild(ref)

            reads.get().assert().isEqualTo(1)
            stores.values.forEach { it.load(ref)!!.state.assert().contains("\"total\":3") }
        }

    @Test
    fun `a rebuild replays past a stored TagsApplied of the tags Wow no longer has`(): Unit = runBlocking {
        val store = InMemorySnapshotStore()
        val ref = commit("t", 3)
        val first = log.read(ref).toList().single()
        val tagsApplied = EventRecord("wow.lifecycle.TagsApplied", 1, """{"tags":{"region":["eu"]}}""")
        log.append(first.copyWith(version = 2, commandId = "old-2", requestId = "old-2", events = listOf(tagsApplied)))
            .assert().isInstanceOf(AppendOutcome.Committed::class.java)
        maintenance(1, store).rebuild(ref)
        val snapshot = store.load(ref)!!
        snapshot.version.assert().isEqualTo(2)
        snapshot.state.assert().contains("\"total\":3")
    }

    @Test
    fun `a store without a rebuild control plane is left out of the maintenance, its fallbacks counted`(): Unit =
        runBlocking {
            // Finding R5.B-9: the rebuild control plane is an optional capability of a snapshot store.
            val fallbacks = ArrayList<String>()
            val observer = object : Observer {
                override fun observed(event: ObservedEvent) {
                    when (event) {
                        is ObservedEvent.StoreFellBack -> {
                            fallbacks += "${event.context} ${event.capability.value} ${event.reason.value}"
                        }
                        else -> Unit
                    }
                }
            }
            val memory = InMemorySnapshotStore()
            val plain = object : SnapshotStore by memory {
                override val rebuilds: SnapshotRebuildStore? = null
            }
            val maintenance = maintenance(2, plain, observer = observer)
            maintenance.prepare().assert().isEmpty()
            fallbacks.assert().containsExactly("$CONTEXT snapshot-rebuild unsupported")
            maintenance.rebuilding(type).assert().isFalse()
            maintenance.maintain()
            memory.table(type).assert().isNull()
            val ref = commit("a")
            maintenance.rebuild(ref)
            memory.load(ref).assert().isNull()
            fallbacks.assert().hasSize(2)
            maintenance.stop()
        }

    @Test
    fun `a rebuild request on a node of another revision writes a missing snapshot at its own`(): Unit = runBlocking {
        val store = InMemorySnapshotStore()
        store.claim(type, 2)
        val maintenance = maintenance(1, store)
        val ref = commit("a")
        maintenance.rebuild(ref)
        store.load(ref)!!.stateRevision.assert().isEqualTo(1)
        store.table(type).assert().isEqualTo(SnapshotTable(2, rebuilding = true, backfilled = false))
        maintenance.tables().keys.assert().containsExactly(SnapshotTargets.DEFAULT_STORE to type)
        // An aggregate the log holds nothing of has no snapshot to write.
        val nothing = type.ref("tenant", "nothing")
        maintenance.rebuild(nothing)
        store.load(nothing).assert().isNull()
    }

    @Test
    fun `a run reads the partition heads only while a target has not caught up`(): Unit = runBlocking {
        // B.D diagnostics: the heads serve the backfill check alone.
        val heads = AtomicInteger()
        val counting = object : EventLog by log {
            override suspend fun head(partition: Int): Long? = log.head(partition).also { heads.incrementAndGet() }
        }
        val store = InMemorySnapshotStore()
        val maintenance = maintenance(1, store, progressLog = counting)
        commit("a")
        // The table is not known yet, then claimed and not backfilled: each run reads both partitions' heads.
        maintenance.maintain()
        heads.get().assert().isEqualTo(2)
        maintenance.maintain()
        heads.get().assert().isEqualTo(4)
        store.mark(type, 1, backfilled = true)
        maintenance.maintain()
        heads.get().assert().isEqualTo(6)
        // Every target caught up: no more reads.
        maintenance.maintain()
        maintenance.maintain()
        heads.get().assert().isEqualTo(6)
        store.table(type)!!.backfilled.assert().isTrue()
    }

    @Test
    fun `only the holder of the rebuild lease rewrites a table`(): Unit = runBlocking {
        val store = InMemorySnapshotStore()
        val ref = commit("a")
        store.save(codec.snapshotOf(model(1), ref, Counter(), 1, Scope.NONE, 0, 0))
        val maintenance = maintenance(2, store)
        maintenance.prepare()
        leases.acquire("$GROUP#snapshot-rebuild/${SnapshotTargets.DEFAULT_STORE}/counter", 0, "other", 1.hours)
        maintenance.maintain()
        store.pending(type, 2, limit = 10).assert().containsExactly(ref)
        leases.release("$GROUP#snapshot-rebuild/${SnapshotTargets.DEFAULT_STORE}/counter", 0, "other")
        maintenance.maintain()
        store.pending(type, 2, limit = 10).assert().isEmpty()
        maintenance.maintain()
        store.table(type)!!.rebuilding.assert().isFalse()
    }

    /** [memory] whose rebuild control plane records where each scan of the pending snapshots started. */
    private class RecordingStore(
        val memory: InMemorySnapshotStore = InMemorySnapshotStore()
    ) : SnapshotStore by memory {
        val scans = mutableListOf<String?>()

        /** Runs before each scan, with where it starts: what happens to the table meanwhile. */
        var beforeScan: suspend (after: AggregateRef?) -> Unit = {}

        override val rebuilds = object : SnapshotRebuildStore by memory {
            override suspend fun pending(
                type: AggregateType,
                revision: Int,
                after: AggregateRef?,
                limit: Int,
            ): List<AggregateRef> {
                beforeScan(after)
                return memory.pending(type, revision, after, limit).also { scans += after?.id }
            }
        }
    }

    @Test
    fun `a rebuild should go on from where the last run stopped, and start over when the lease is taken again`(): Unit =
        runBlocking {
            val store = RecordingStore()
            val refs = listOf("a", "b", "c").map { id ->
                commit(id).also { store.save(codec.snapshotOf(model(1), it, Counter(), 1, Scope.NONE, 0, 0)) }
            }
            val one = SnapshotOptions(interval = 1.hours, batch = 1, pages = 1, leaseTtl = 2.hours, memberTtl = 2.hours)
            val maintenance = maintenance(2, store, options = one)
            maintenance.prepare()
            maintenance.maintain()
            maintenance.maintain()
            // Each run reads on from the snapshot the last one stopped at, not from the first again.
            store.scans.assert().containsExactly(null, "a")
            // The lease lost and taken again: what was rewritten under the other may have changed, it starts over.
            leases.release("$GROUP#snapshot-rebuild/${SnapshotTargets.DEFAULT_STORE}/counter", 0, "node")
            store.scans.clear()
            maintenance.maintain()
            store.scans.assert().containsExactly(null)
            // From the first, the scan finds what is still pending: the last one, rewritten now.
            store.memory.pending(type, 2, limit = 10).assert().isEmpty()
            store.memory.load(refs[2])!!.stateRevision.assert().isEqualTo(2)
        }

    @Test
    fun `a snapshot marked stale behind the cursor during a pass should be rewritten before the table is rebuilt`():
        Unit = runBlocking {
        val store = RecordingStore()
        val (a, b, c) = listOf("a", "b", "c").map { id ->
            commit(id).also { store.save(codec.snapshotOf(model(1), it, Counter(), 1, Scope.NONE, 0, 0)) }
        }
        // One run passes the whole table, a page a snapshot.
        val options = SnapshotOptions(
            interval = 1.hours,
            batch = 1,
            pages = 10,
            leaseTtl = 2.hours,
            memberTtl = 2.hours
        )
        val maintenance = maintenance(2, store, options = options)
        maintenance.prepare()
        // Once a and b are rewritten, a refused save of a node of the old revision marks a stale again.
        store.beforeScan = { after -> if (after == b) store.memory.markStale(a) }
        maintenance.maintain()
        // The pass reaches the end, starts over, finds a, rewrites it, and only an empty scan from the first ends it.
        store.scans.assert().containsExactly(null, "a", "b", "c", null, "a", null)
        store.memory.pending(type, 2, limit = 10).assert().isEmpty()
        store.memory.table(type)!!.rebuilding.assert().isFalse()
        listOf(a, b, c).forEach { store.memory.load(it)!!.stateRevision.assert().isEqualTo(2) }
    }

    @Test
    fun `a rebuild should mark its table rebuilt only once a scan from the first finds nothing`(): Unit =
        runBlocking {
            val store = RecordingStore()
            val (a, b) = listOf("a", "b").map { id ->
                commit(id).also { store.save(codec.snapshotOf(model(1), it, Counter(), 1, Scope.NONE, 0, 0)) }
            }
            val options = SnapshotOptions(
                interval = 1.hours,
                batch = 1,
                pages = 2,
                leaseTtl = 2.hours,
                memberTtl = 2.hours
            )
            val maintenance = maintenance(2, store, options = options)
            maintenance.prepare()
            maintenance.maintain()
            store.memory.pending(type, 2, limit = 10).assert().isEmpty()
            // A save refused meanwhile marks a snapshot behind the cursor stale: the scan starts over and rewrites it.
            store.memory.markStale(a)
            maintenance.maintain()
            store.scans.assert().containsExactly(null, "a", "b", null)
            store.memory.table(type)!!.rebuilding.assert().isTrue()
            store.memory.pending(type, 2, limit = 10).assert().isEmpty()
            maintenance.maintain()
            store.memory.table(type)!!.rebuilding.assert().isFalse()
            store.memory.load(b)!!.stateRevision.assert().isEqualTo(2)
        }

    @Test
    fun `a table claimed at a higher revision during the last page should not be taken for rebuilt`(): Unit =
        runBlocking {
            val store = RecordingStore()
            listOf("a", "b").forEach { id ->
                commit(id).also { store.save(codec.snapshotOf(model(1), it, Counter(), 1, Scope.NONE, 0, 0)) }
            }
            val options = SnapshotOptions(
                interval = 1.hours,
                batch = 1,
                pages = 10,
                leaseTtl = 2.hours,
                memberTtl = 2.hours
            )
            val maintenance = maintenance(2, store, options = options)
            maintenance.prepare()
            // A node of revision 3, alive, claims the table just before the scan from the first that would end it.
            leases.heartbeat("$GROUP#snapshot/counter@3", "three", "three:8080", 1.hours)
            store.beforeScan = { after -> if (after == null && store.scans.isNotEmpty()) store.memory.claim(type, 3) }
            maintenance.maintain()
            store.scans.assert().containsExactly(null, "a", "b", null)
            val key = SnapshotTargets.DEFAULT_STORE to type
            maintenance.tables().getValue(key).rebuilding.assert().isTrue()
            store.memory.table(type)!!.revision.assert().isEqualTo(3)
            // The lease is released: the node of the claimed revision may rebuild the table.
            val lease = "$GROUP#snapshot-rebuild/${SnapshotTargets.DEFAULT_STORE}/counter"
            leases.acquire(lease, 0, "other", 1.hours).assert().isNotNull()
            leases.release(lease, 0, "other")
            // The next run acts on the new claim: a node of revision 2 no longer rebuilds the table.
            store.scans.clear()
            maintenance.maintain()
            store.scans.assert().isEmpty()
            maintenance.tables().getValue(key).revision.assert().isEqualTo(3)
            store.memory.table(type)!!.rebuilding.assert().isTrue()
        }

    @Test
    fun `a pending snapshot the log holds nothing of is left as it is`(): Unit = runBlocking {
        val store = InMemorySnapshotStore()
        val ghost = SnapshotRecord(type.ref("tenant", "ghost"), 1, "f", "{}", firstEventTime = 0, lastEventTime = 0)
        store.save(ghost)
        val maintenance = maintenance(2, store)
        maintenance.prepare()
        maintenance.maintain()
        store.load(ghost.ref).assert().isEqualTo(ghost)
        store.rebuilds!!.table(type)!!.rebuilding.assert().isTrue()
    }

    @Test
    fun `the runs go on after one fails, until the maintenance stops`(): Unit = runBlocking {
        val tables = AtomicInteger()
        val memory = InMemorySnapshotStore()
        val failing = object : SnapshotStore by memory {
            override val rebuilds = object : SnapshotRebuildStore by memory {
                override suspend fun table(type: AggregateType): SnapshotTable? {
                    tables.incrementAndGet()
                    error("The store is down.")
                }
            }
        }
        val maintenance = maintenance(
            1,
            failing,
            options = SnapshotOptions(interval = 10.milliseconds, leaseTtl = 1.hours, memberTtl = 1.hours),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            maintenance.start(scope)
            eventually { tables.get() >= 2 }
            maintenance.stop()
            val stopped = tables.get()
            // The stop cancels the loop without joining it: joined through the scope it runs in, no run can follow.
            scope.coroutineContext.job.children.forEach { it.join() }
            tables.get().assert().isBetween(stopped, stopped + 1)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val CONTEXT = "shop"
        const val GROUP = "service/shop"
    }
}
