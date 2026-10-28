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
import me.ahoo.wow.spi.log.EventLog
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
    ) = SnapshotMaintenance(
        CONTEXT,
        GROUP,
        node,
        listOf(model(revision)),
        SnapshotTargets.single(store),
        kernel,
        codec,
        log,
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
    fun `a store without a rebuild control plane is left out of the maintenance, its fallbacks counted`(): Unit =
        runBlocking {
            // Finding R5.B-9: the rebuild control plane is an optional capability of a snapshot store.
            val fallbacks = ArrayList<String>()
            val observer = object : Observer {
                override fun observed(event: ObservedEvent) {
                    when (event) {
                        is ObservedEvent.StoreFellBack -> {
                            fallbacks += "${event.context} ${event.capability} ${event.reason}"
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
            Thread.sleep(50)
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
