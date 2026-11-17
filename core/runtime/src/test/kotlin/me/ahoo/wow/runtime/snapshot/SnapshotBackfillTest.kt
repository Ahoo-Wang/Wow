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

import kotlinx.coroutines.flow.toList
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
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

/**
 * Which nodes backfill a snapshot store ([SnapshotMaintenance.prepare]): none on a cluster started on fresh stores,
 * every one, for its partitions, on a store its subscription is already past the beginning of; and a backfill stops on
 * its table's mark, whichever node wrote it.
 */
class SnapshotBackfillTest {
    data class Add(val amount: Int)
    data class Added(val amount: Int)

    class Counter {
        var total = 0
    }

    private val model: AggregateModel<Counter> = aggregate<Counter>(CONTEXT, "counter") {
        initial { Counter() }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        evolve<Added> { total += it.amount }
    }

    private val log = InMemoryEventLog(PARTITIONS)
    private val codec = EventCodec()
    private val kernel = Kernel(log, codec)
    private val leases = InMemoryLeaseStore()
    private val checkpoints = InMemoryCheckpointStore()
    private val memory = InMemorySnapshotStore()
    private val type = AggregateType(CONTEXT, "counter")
    private val primary = SnapshotTargets.single(memory).subscriptionOf(SnapshotTargets.DEFAULT_STORE)
    private val backfill = "$primary#counter"

    private fun maintenance(node: String, store: SnapshotStore = memory) = SnapshotMaintenance(
        CONTEXT,
        GROUP,
        node,
        listOf(model),
        SnapshotTargets.single(store),
        kernel,
        codec,
        log,
        leases,
        listOf(LogProgress(GROUP, log, checkpoints)),
        SnapshotOptions(interval = 1.hours, leaseTtl = 2.hours, memberTtl = 2.hours),
    )

    private suspend fun commit(id: String): AggregateRef {
        val ref = type.ref("tenant", id)
        kernel.execute(model, Command("c-$id", "r-$id", ref, Add(1)))
        return ref
    }

    /** [subscription]'s checkpoint in every partition at [at]: what a runtime saves as it starts it, or later. */
    private suspend fun checkpointed(subscription: String, at: (Int) -> Long = { 0L }) {
        (0 until PARTITIONS).forEach { checkpoints.save(GROUP, subscription, it, at(it)) }
    }

    private suspend fun head(partition: Int): Long = checkNotNull(log.head(partition))

    @Test
    fun `a cluster started on fresh stores runs no backfill, the first node marking the table for the others`(): Unit =
        runBlocking {
            maintenance("node-1").prepare().assert().isEmpty()
            memory.table(type)!!.backfilled.assert().isTrue()
            // The first node's runtime starts: its starting checkpoints, then commits.
            checkpointed(primary)
            commit("a")
            maintenance("node-2").prepare().assert().isEmpty()
            maintenance("node-3").prepare().assert().isEmpty()
        }

    @Test
    fun `a table is never marked while its subscription has a checkpoint, so one left unmarked is backfilled`(): Unit =
        runBlocking {
            // A node met the table and stopped before marking it; the other nodes' runtimes started meanwhile.
            memory.claim(type, 1)
            checkpointed(primary)
            // An empty log does not make it marked: the subscription has a checkpoint. Redundant, never missing.
            maintenance("node-2").prepare().map { it.name }.assert().containsExactly(backfill)
            memory.table(type)!!.backfilled.assert().isFalse()
        }

    @Test
    fun `a store its subscription is past the beginning of is backfilled by every node until each partition`(): Unit =
        runBlocking {
            var id = 0
            while ((0 until PARTITIONS).any { log.head(it) == null }) {
                commit("a-${id++}")
            }
            checkpointed(primary) { runBlocking { head(it) } }
            val first = maintenance("node-1")
            first.prepare().map { it.name }.assert().containsExactly(backfill)
            // A node joining meanwhile backfills the partitions it runs too.
            maintenance("node-2").prepare().map { it.name }.assert().containsExactly(backfill)

            // The first run records the heads the next ones check against.
            first.maintain()
            checkpoints.save(GROUP, backfill, 0, head(0))
            first.maintain()
            memory.table(type)!!.backfilled.assert().isFalse()
            checkpoints.save(GROUP, backfill, 1, head(1))
            first.maintain()
            memory.table(type)!!.backfilled.assert().isTrue()
        }

    @Test
    fun `a running backfill stops writing once another node marked its table`(): Unit = runBlocking {
        val loads = AtomicInteger()
        val counting = object : SnapshotStore by memory {
            override suspend fun load(ref: AggregateRef): SnapshotRecord? =
                memory.load(ref).also { loads.incrementAndGet() }
        }
        val refs = listOf(commit("a"), commit("b"))
        // Snapshots at the entries' versions: a write that runs loads one and finds nothing to save.
        refs.forEach { memory.save(codec.snapshotOf(model, it, Counter(), 1, Scope.NONE, 0, 0)) }
        memory.claim(type, 1)
        checkpointed(primary)
        val node = maintenance("node-2", counting)
        val running = node.prepare().single()
        suspend fun handle(ref: AggregateRef) {
            running.subscription.handle(SubscribedEntry(log.read(ref, 1, 1).toList().single(), null, emptyList()))
        }

        handle(refs[0])
        loads.get().assert().isEqualTo(1)
        // Another node marks the table, as its own check of the partitions found them caught up.
        memory.mark(type, 1, backfilled = true)
        node.maintain()
        handle(refs[1])
        loads.get().assert().isEqualTo(1)
    }

    private companion object {
        const val CONTEXT = "shop"
        const val GROUP = "service/shop"
        const val PARTITIONS = 2
    }
}
